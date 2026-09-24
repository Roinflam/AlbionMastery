package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.client.weapon.ClientWeaponRegistry;
import com.flameforge.albionmastery.client.weapon.WeaponEntry;
import com.flameforge.albionmastery.client.weapon.WeaponSource;
import com.flameforge.albionmastery.client.weapon.WeaponTypeGroup;
import com.flameforge.albionmastery.client.screen.MasteryScreen;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.network.S2CSyncMasteryPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.flameforge.albionmastery.weapon.compat.WeaponOverrides;

/**
 * 客户端专精数据缓存。
 *
 * <p>客户端不做任何权威计算，只保存服务端下发的数据用于界面显示。
 * 配置快照同样来自服务端，因此界面上显示的加成与服务端实际结算的完全一致。
 */
public final class ClientMasteryCache {

    /** 本地缓存的专精数据。 */
    private static final MasteryData DATA = new MasteryData();

    /** 服务端下发的数值配置快照。 */
    private static MasteryConfig.Snapshot snapshot = MasteryConfig.Snapshot.createDefault();

    /** 一次全量同步里改动超过这么多条，就只弹来源那一层的提示。 */
    private static final int BULK_NOTIFY_LIMIT = 8;

    /** 是否已经收到过至少一次全量同步。 */
    private static boolean synced = false;

    private ClientMasteryCache() {
    }

    /**
     * @return 本地缓存的专精数据
     */
    public static MasteryData data() {
        return DATA;
    }

    /**
     * @return 服务端下发的数值配置快照
     */
    public static MasteryConfig.Snapshot snapshot() {
        return snapshot;
    }

    /**
     * @return 是否已经收到过全量同步
     */
    public static boolean isSynced() {
        return synced;
    }

    /**
     * 这份缓存是不是这个玩家的。
     *
     * <p>缓存里只有本机玩家自己的专精数据；客户端上别的玩家拿枪开火、拉栓时，TACZ 同样会走到那些
     * 被特化改过的方法里，这时不能拿本机玩家的特化去套别人。
     *
     * @param player 玩家
     * @return 是本机玩家返回 true
     */
    public static boolean isLocalPlayer(net.minecraft.world.entity.player.Player player) {
        return player == net.minecraft.client.Minecraft.getInstance().player;
    }

    /**
     * 接收全量同步包。
     *
     * @param newSnapshot 服务端配置快照
     * @param weapons     武器表
     * @param types       类型表
     * @param overalls    总体表
     * @param specializations 特化选择表
     * @param studyMultiplier 学习点倍率
     */
    public static void acceptFullSync(MasteryConfig.Snapshot newSnapshot,
                                      Map<String, S2CSyncMasteryPacket.WeaponSync> weapons,
                                      Map<String, Long> types,
                                      Map<String, Long> overalls,
                                      Map<String, String> specializations,
                                      double studyMultiplier) {
        // 登录后的第一次全量同步是一次性灌数据，弹一屏提示毫无意义；
        // 之后再来的全量同步一定是服务端那边动过数据（管理员改等级、切特化），这时候要提示
        boolean notify = synced && MasteryConfig.CLIENT.showLevelUpToast.get();
        Map<String, Integer> before = notify ? levelSnapshot() : Map.of();

        snapshot = newSnapshot;
        // 武器补录表跟着服务端走：服主在配置里补了哪些武器，客户端按同一份认，
        // 免得服务端算出的武器键和客户端对不上（那样界面上永远是 0 级）
        // 只有这两张表真的变了才让武器清单作废：全量同步几乎每件事都会触发
        //（切特化、重生、换维度、配置重载），而重建要遍历整个物品注册表，
        // 代价会落在下一次 ensureBuilt()——正好是战斗里升级弹横幅的那一帧
        if (WeaponOverrides.applyFromServer(newSnapshot.extraWeapons, newSnapshot.notWeapons)) {
            ClientWeaponRegistry.invalidate();
        }
        DATA.clear();
        applyDelta(weapons, types, overalls, false);
        DATA.replaceSpecializations(specializations);
        DATA.setStudyMultiplier(studyMultiplier);
        DATA.recomputeLevels(newSnapshot.weaponCurve(), newSnapshot.typeCurve(), newSnapshot.overallCurve());
        DATA.clearDirty();
        synced = true;
        if (notify) {
            notifyChanges(before);
        }
    }

    /**
     * 把当前所有条目的等级拍一张快照。
     *
     * <p>键加了一个字母前缀区分三层：三层的键格式不同，但没规定它们一定不重名，
     * 混在一张表里不加前缀迟早出事。
     *
     * @return 键 -&gt; 等级
     */
    private static Map<String, Integer> levelSnapshot() {
        Map<String, Integer> levels = new HashMap<>();
        for (String key : DATA.getWeaponsView().keySet()) {
            levels.put("w" + key, DATA.getWeaponLevel(key));
        }
        for (String key : DATA.getTypesView().keySet()) {
            levels.put("t" + key, DATA.getTypeLevel(key));
        }
        for (String key : DATA.getOverallsView().keySet()) {
            levels.put("o" + key, DATA.getOverallLevel(key));
        }
        return levels;
    }

    /**
     * 对比全量同步前后的等级，变了的都弹一条提示。
     *
     * <p>升降都提示：管理员用指令把等级调下去，玩家不该只看到数字悄悄变了。
     * 同步前不存在的条目按 0 级算，这样新拿到的武器也会正常弹升级提示。
     *
     * @param before 同步前的等级快照
     */
    private static void notifyChanges(Map<String, Integer> before) {
        List<LevelChange> changes = changesSince(before);
        if (changes.size() <= BULK_NOTIFY_LIMIT) {
            for (LevelChange change : changes) {
                notifyIfLevelUp(change.oldLevel(), change.newLevel(), change.key(), change.tier());
            }
            return;
        }
        // 管理员一键满级 / 清零会一次改掉上千条，逐条弹提示没法看：从上往下挑一层来弹。
        // 有来源变了就只弹来源；来源没变（比如整类满级）就弹类型，最多弹上限那么多条
        List<LevelChange> picked = new ArrayList<>();
        for (Tier tier : new Tier[]{Tier.OVERALL, Tier.TYPE, Tier.WEAPON}) {
            for (LevelChange change : changes) {
                if (change.tier() == tier && picked.size() < BULK_NOTIFY_LIMIT) {
                    picked.add(change);
                }
            }
            if (!picked.isEmpty()) {
                break;
            }
        }
        for (LevelChange change : picked) {
            notifyIfLevelUp(change.oldLevel(), change.newLevel(), change.key(), change.tier());
        }
    }

    /**
     * 同步前后等级变了的条目。
     *
     * <p>全量包不带经验为 0 的条目，清零之后它们直接从表里消失了，要从同步前的快照里补回来按 0 级算，
     * 不然「清零」一条提示都没有。
     *
     * @param before 同步前的等级快照
     * @return 变化列表，来源、类型、武器的顺序
     */
    private static List<LevelChange> changesSince(Map<String, Integer> before) {
        List<LevelChange> changes = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (String key : DATA.getOverallsView().keySet()) {
            present.add("o" + key);
            addChange(changes, before.getOrDefault("o" + key, 0), DATA.getOverallLevel(key), key, Tier.OVERALL);
        }
        for (String key : DATA.getTypesView().keySet()) {
            present.add("t" + key);
            addChange(changes, before.getOrDefault("t" + key, 0), DATA.getTypeLevel(key), key, Tier.TYPE);
        }
        for (String key : DATA.getWeaponsView().keySet()) {
            present.add("w" + key);
            addChange(changes, before.getOrDefault("w" + key, 0), DATA.getWeaponLevel(key), key, Tier.WEAPON);
        }
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            String prefixed = entry.getKey();
            if (present.contains(prefixed) || entry.getValue() == 0) {
                continue;
            }
            Tier tier = switch (prefixed.charAt(0)) {
                case 'w' -> Tier.WEAPON;
                case 't' -> Tier.TYPE;
                default -> Tier.OVERALL;
            };
            changes.add(new LevelChange(prefixed.substring(1), tier, entry.getValue(), 0));
        }
        return changes;
    }

    private static void addChange(List<LevelChange> changes, int oldLevel, int newLevel, String key, Tier tier) {
        if (oldLevel != newLevel) {
            changes.add(new LevelChange(key, tier, oldLevel, newLevel));
        }
    }

    /**
     * 一条等级变化。
     */
    private record LevelChange(String key, Tier tier, int oldLevel, int newLevel) {
    }

    /**
     * 接收增量同步包。
     *
     * @param weapons  变动的武器表
     * @param types    变动的类型表
     * @param overalls 变动的总体表
     */
    public static void acceptDelta(Map<String, S2CSyncMasteryPacket.WeaponSync> weapons,
                                   Map<String, Long> types,
                                   Map<String, Long> overalls) {
        applyDelta(weapons, types, overalls, synced && MasteryConfig.CLIENT.showLevelUpToast.get());
        DATA.clearDirty();
        synced = true;
    }

    /**
     * 应用一批经验变化。
     *
     * <p>升级提示完全在客户端判定：应用前后各读一次等级，涨了就弹。
     * 这样服务端不用再为「升级」单独发一种包。
     *
     * @param weapons  武器表
     * @param types    类型表
     * @param overalls 总体表
     * @param notify   是否弹升级提示
     */
    private static void applyDelta(Map<String, S2CSyncMasteryPacket.WeaponSync> weapons,
                                   Map<String, Long> types,
                                   Map<String, Long> overalls,
                                   boolean notify) {
        Map<String, Integer> before = notify ? snapshotLevels(weapons, types, overalls) : Map.of();

        for (Map.Entry<String, S2CSyncMasteryPacket.WeaponSync> entry : weapons.entrySet()) {
            String typeKey = entry.getValue().typeKey();
            DATA.setWeaponXp(entry.getKey(), typeKey.isEmpty() ? null : typeKey,
                    entry.getValue().xp(), snapshot.weaponCurve(), false);
        }
        for (Map.Entry<String, Long> entry : types.entrySet()) {
            DATA.setTypeXp(entry.getKey(), entry.getValue(), snapshot.typeCurve(), false);
        }
        for (Map.Entry<String, Long> entry : overalls.entrySet()) {
            DATA.setOverallXp(entry.getKey(), entry.getValue(), snapshot.overallCurve(), false);
        }

        if (!notify) {
            return;
        }
        for (String key : weapons.keySet()) {
            notifyIfLevelUp(before.get("w" + key), DATA.getWeaponLevel(key), key, Tier.WEAPON);
        }
        for (String key : types.keySet()) {
            notifyIfLevelUp(before.get("t" + key), DATA.getTypeLevel(key), key, Tier.TYPE);
        }
        for (String key : overalls.keySet()) {
            notifyIfLevelUp(before.get("o" + key), DATA.getOverallLevel(key), key, Tier.OVERALL);
        }
    }

    /**
     * 记录本批涉及到的所有键在应用之前的等级。
     *
     * @param weapons  武器表
     * @param types    类型表
     * @param overalls 总体表
     * @return 键（带层级前缀）到旧等级的映射
     */
    private static Map<String, Integer> snapshotLevels(Map<String, S2CSyncMasteryPacket.WeaponSync> weapons,
                                                       Map<String, Long> types,
                                                       Map<String, Long> overalls) {
        Map<String, Integer> before = new HashMap<>();
        for (String key : weapons.keySet()) {
            before.put("w" + key, DATA.getWeaponLevel(key));
        }
        for (String key : types.keySet()) {
            before.put("t" + key, DATA.getTypeLevel(key));
        }
        for (String key : overalls.keySet()) {
            before.put("o" + key, DATA.getOverallLevel(key));
        }
        return before;
    }

    /**
     * 等级涨了就推一条提示。
     *
     * @param oldLevel 旧等级
     * @param newLevel 新等级
     * @param key      对应的专精键
     * @param tier     所在层级
     */
    private static void notifyIfLevelUp(Integer oldLevel, int newLevel, String key, Tier tier) {
        // 升降都要提示，只有完全没变才跳过
        if (oldLevel == null || newLevel == oldLevel) {
            return;
        }
        ClientWeaponRegistry.ensureBuilt();
        Component title;
        Component tierName;
        List<ItemStack> icons = new ArrayList<>();
        switch (tier) {
            case WEAPON -> {
                WeaponEntry entry = ClientWeaponRegistry.weaponByKey(key);
                title = entry == null ? Component.literal(key) : entry.displayName();
                if (entry != null) {
                    icons.add(entry.icon());
                }
                tierName = Component.translatable("toast.albionmastery.tier_weapon");
            }
            case TYPE -> {
                title = ClientWeaponRegistry.typeName(key);
                tierName = Component.translatable("toast.albionmastery.tier_type");
                // 类型自己没有图标，就拿旗下武器的图标轮换
                WeaponTypeGroup group = ClientWeaponRegistry.typeGroup(key);
                if (group != null) {
                    collectIcons(group.weapons(), icons);
                }
            }
            default -> {
                title = ClientWeaponRegistry.sourceName(key);
                tierName = Component.translatable("toast.albionmastery.tier_overall");
                WeaponSource source = ClientWeaponRegistry.source(key);
                if (source != null) {
                    collectIcons(source.allWeapons(), icons);
                }
            }
        }
        // 外圈（武器）最先显示，其次中圈（类型），内圈（来源）最后
        int priority = switch (tier) {
            case WEAPON -> 0;
            case TYPE -> 1;
            default -> 2;
        };
        MasteryToastManager.push(priority, title, tierName, oldLevel, newLevel, icons);
    }

    /**
     * 从一批武器里挑出图标。
     *
     * <p>数量多时按等间隔抽样，而不是取前几个——不然总体层的提示永远只在同几把枪之间轮换。
     *
     * @param weapons 武器列表
     * @param out     输出列表
     */
    private static void collectIcons(List<WeaponEntry> weapons, List<ItemStack> out) {
        if (weapons.isEmpty()) {
            return;
        }
        int want = Math.min(8, weapons.size());
        int step = Math.max(1, weapons.size() / want);
        for (int i = 0; i < weapons.size() && out.size() < want; i += step) {
            ItemStack icon = weapons.get(i).icon();
            if (!icon.isEmpty()) {
                out.add(icon);
            }
        }
    }

    /**
     * 专精的三个层级。
     */
    private enum Tier {
        /** 单件武器。 */
        WEAPON,
        /** 武器类型。 */
        TYPE,
        /** 总体。 */
        OVERALL
    }

    /**
     * 断开连接时清空缓存，避免下次进服看到上一局的数据。
     */
    public static void reset() {
        DATA.clear();
        DATA.clearDirty();
        snapshot = MasteryConfig.Snapshot.createDefault();
        WeaponOverrides.clearServerData();
        WeaponOverrides.apply(List.of(), List.of());
        ClientWeaponRegistry.invalidate();
        synced = false;
        MasteryToastManager.clear();
        // 记住的界面视角也要丢掉：换个服务端武器清单完全不一样，
        // 拿旧坐标恢复出来就是对着一片空白
        MasteryScreen.forgetView();
    }
}
