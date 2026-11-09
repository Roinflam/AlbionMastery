package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecRules;
import com.flameforge.albionmastery.mastery.MasteryAdmin;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 登录时对特化存档做的两件事：老存档迁移到分级特化，以及「花的点比有的多」时整份退点。
 *
 * <p><b>迁移</b>（存档特化版本小于 {@link MasteryData#SPEC_DATA_VERSION} 时做一次）：
 * <ol>
 *     <li>点数按现有等级现算，等级一点不丢；</li>
 *     <li>把每个来源下正在用的特化排个序——原来装在槽 1 的排前面、用的套数多的排前面——
 *         依次升到普通版满级，点数不够就升到能升的最高级；</li>
 *     <li>原来槽 1 的特化留在新槽 1；</li>
 *     <li>原来槽 2 的特化：普通版满级、这个类型又有突破点时自动突破（能升多高升多高），留在新槽 2（突破槽）；
 *         否则挪到新槽 3（普通槽）；槽数配得太少放不下时卸下，等级照样保留；</li>
 *     <li>单件武器的独立配置按同样规则处理。</li>
 * </ol>
 * 改版前每个槽的键是「类型键」「类型键@2」，新版沿用，所以存档本身不用改格式，只是 {@code @2} 的含义变成了突破槽。
 */
public final class SpecMigration {

    private SpecMigration() {
    }

    /**
     * 登录时调用，在全量同步之前。
     *
     * @param player 玩家
     */
    public static void onLogin(ServerPlayer player) {
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        // 读档后等级缓存全是 0，点数、满级武器数都靠它，先按当前曲线算一遍
        data.recomputeLevels(snapshot.weaponCurve(), snapshot.typeCurve(), snapshot.overallCurve());
        if (data.getSpecDataVersion() < MasteryData.SPEC_DATA_VERSION) {
            MinecraftServer server = player.getServer();
            boolean hadSpecs = migrate(data, snapshot, weaponKey -> server == null
                    ? data.getWeaponTypeKey(weaponKey) : MasteryAdmin.typeKeyOf(server, data, weaponKey, null));
            AlbionMastery.LOGGER.info("玩家 {} 的特化存档已迁移到分级特化（原来有没有装特化：{}）",
                    player.getGameProfile().getName(), hadSpecs);
            if (hadSpecs) {
                player.sendSystemMessage(Component.translatable("msg.albionmastery.spec_migrated",
                                spareModPoints(data, snapshot), spareBreakthroughPoints(data, snapshot))
                        .withStyle(ChatFormatting.GOLD));
            }
        }
        for (String providerId : refundOverspent(data, snapshot, player.getGameProfile().getName())) {
            player.sendSystemMessage(Component.translatable("msg.albionmastery.spec_refunded",
                    SpecTerms.of(providerId, "page")).withStyle(ChatFormatting.YELLOW));
        }
    }

    /**
     * 迁移一份改版前的存档。只改数据，不发消息。
     *
     * @param data     玩家数据（等级缓存要先算好）
     * @param snapshot 配置快照
     * @param typeOf   武器键 → 类型键（1.0.0 存盘时会丢掉经验为 0 的武器记录，独立配置的类型要能从武器目录查）
     * @return 原来有没有装着特化
     */
    public static boolean migrate(MasteryData data, MasteryConfig.Snapshot snapshot,
                                  Function<String, String> typeOf) {
        SpecRules rules = snapshot.specRules;
        // 配置键（类型键或武器键）-> 四个槽里的特化。1.0.0 只有前两个；
        // 退回 1.0.0 再升回来时（1.0.0 不认新存档字段，会再迁移一遍），上次挪进槽 3 的也要算进来
        Map<String, Specialization[]> configs = new TreeMap<>(Comparator
                .comparing((String key) -> WeaponKeys.isValidWeaponKey(key) ? 1 : 0)
                .thenComparing(Comparator.naturalOrder()));
        for (Map.Entry<String, String> entry : data.getSpecializationsView().entrySet()) {
            int slot = WeaponKeys.specSlotOf(entry.getKey());
            Specialization spec = Specialization.byKey(entry.getValue());
            if (slot < 1 || slot > WeaponKeys.SPEC_SLOTS || spec == null || spec.isStandard()) {
                continue;
            }
            configs.computeIfAbsent(WeaponKeys.specBaseKey(entry.getKey()),
                    key -> new Specialization[WeaponKeys.SPEC_SLOTS])[slot - 1] = spec;
        }
        if (configs.isEmpty()) {
            data.setSpecDataVersion(MasteryData.SPEC_DATA_VERSION);
            return false;
        }

        // 1. 自动加点：按来源排序，原来槽 1 的在前，用的套数多的在前。
        //    和前面的槽同系（或就是同一个）的老组合本来就不生效，不给它分点
        Map<String, Map<Specialization, int[]>> usage = new LinkedHashMap<>();
        for (Specialization[] slots : configs.values()) {
            for (int i = 0; i < slots.length; i++) {
                Specialization spec = slots[i];
                if (spec == null || deadAt(slots, i)) {
                    continue;
                }
                int[] counts = usage.computeIfAbsent(spec.sourceId(), id -> new HashMap<>())
                        .computeIfAbsent(spec, key -> new int[2]);
                if (i == 0) {
                    counts[0]++;
                }
                counts[1]++;
            }
        }
        for (Map.Entry<String, Map<Specialization, int[]>> perSource : usage.entrySet()) {
            List<Map.Entry<Specialization, int[]>> ordered = new ArrayList<>(perSource.getValue().entrySet());
            ordered.sort(Comparator
                    .comparing((Map.Entry<Specialization, int[]> e) -> e.getValue()[0] > 0 ? 0 : 1)
                    .thenComparing(e -> -e.getValue()[1])
                    .thenComparing(e -> -e.getValue()[0])
                    .thenComparing(e -> e.getKey().ordinal()));
            int available = SpecPoints.modPointsAvailable(data, perSource.getKey(), snapshot);
            for (Map.Entry<Specialization, int[]> entry : ordered) {
                String key = entry.getKey().key();
                int level = data.getSpecLevel(key);
                while (level < rules.normalMax() && available >= rules.normalCost(level + 1)) {
                    available -= rules.normalCost(level + 1);
                    level++;
                }
                data.setSpecLevel(key, level);
            }
        }

        // 2. 原来槽 2 的特化：能突破就突破、留在突破槽，不能就挪到第二个普通槽；
        //    和槽 1 同系的老组合直接卸下（挪到哪都不生效，还会挡着同系的特化）
        for (Map.Entry<String, Specialization[]> entry : configs.entrySet()) {
            Specialization second = entry.getValue()[1];
            if (second == null) {
                continue;
            }
            String configKey = entry.getKey();
            boolean weapon = WeaponKeys.isValidWeaponKey(configKey);
            if (deadAt(entry.getValue(), 1)) {
                unequip(data, configKey, 2, weapon);
                continue;
            }
            String typeKey = weapon ? typeOf.apply(configKey) : configKey;
            if (rules.singleSlot()) {
                unequip(data, configKey, 2, weapon);
                continue;
            }
            if (typeKey != null && data.getSpecLevel(second.key()) >= rules.normalMax()) {
                int star = data.getBreakthrough(typeKey, second.key());
                int available = SpecPoints.breakthroughPointsAvailable(data, typeKey, snapshot);
                while (star < rules.breakthroughMax() && available >= rules.breakthroughCost(star + 1)) {
                    available -= rules.breakthroughCost(star + 1);
                    star++;
                }
                data.setBreakthrough(typeKey, second.key(), star);
                if (star > 0) {
                    // 键还是「@2」，新版里就是突破槽，不用动
                    continue;
                }
            }
            data.setSpecialization(configKey, 2, null);
            if (rules.slotCount >= 3 && data.getSpecialization(configKey, 3) == null) {
                data.setSpecialization(configKey, 3, second.key());
            } else {
                unequip(data, configKey, 2, weapon);
            }
        }
        data.setSpecDataVersion(MasteryData.SPEC_DATA_VERSION);
        return true;
    }

    /** 第 index 个槽的特化和前面某个槽同系或就是同一个：按槽位顺序，它不生效。 */
    private static boolean deadAt(Specialization[] slots, int index) {
        Specialization spec = slots[index];
        if (spec == null) {
            return false;
        }
        for (int i = 0; i < index; i++) {
            if (slots[i] != null && (slots[i] == spec || spec.conflictsWith(slots[i]))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 卸下一个槽。武器的独立配置要写成制式（全删光的话独立配置就认不出来了，武器会突然改跟类型的配置）。
     */
    private static void unequip(MasteryData data, String configKey, int slot, boolean weapon) {
        Specialization standard = weapon ? Specialization.standardOf(WeaponKeys.providerOf(configKey)) : null;
        data.setSpecialization(configKey, slot, standard == null ? null : standard.key());
    }

    /**
     * 花的点比有的多（服主调了配置、管理员降了等级）的来源 / 类型，整份退掉、卸下用不了的特化。
     *
     * <p>退之前把旧等级写进日志：配置写错、经验曲线改了都会触发，管理员能照着用 {@code spec level} 补回去。
     *
     * @param data     玩家数据
     * @param snapshot 配置快照
     * @param player   玩家名，只用来写日志
     * @return 被退过点的来源 ID（同一来源只列一次）
     */
    public static Set<String> refundOverspent(MasteryData data, MasteryConfig.Snapshot snapshot, String player) {
        SpecRules rules = snapshot.specRules;
        Set<String> refunded = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        for (String specKey : data.getSpecLevelsView().keySet()) {
            Specialization spec = Specialization.byKey(specKey);
            if (spec != null) {
                sources.add(spec.sourceId());
            }
        }
        for (String providerId : sources) {
            if (SpecPoints.modPointsAvailable(data, providerId, snapshot) < 0) {
                AlbionMastery.LOGGER.warn("玩家 {} 在来源 {} 花的改装点超过现有点数，整份退回；退回前的等级 {}，突破 {}",
                        player, providerId, levelsOf(data, providerId), breakthroughsOf(data, providerId, null));
                data.clearSourceProgress(providerId);
                SpecializationManager.pruneUnusable(data, rules, providerId, null);
                refunded.add(providerId);
            }
        }
        for (String typeKey : new ArrayList<>(data.getBreakthroughsView().keySet())) {
            if (SpecPoints.breakthroughPointsAvailable(data, typeKey, snapshot) < 0) {
                AlbionMastery.LOGGER.warn("玩家 {} 在类型 {} 花的突破点超过现有点数，整份退回；退回前的突破 {}",
                        player, typeKey, breakthroughsOf(data, null, typeKey));
                data.clearTypeBreakthroughs(typeKey);
                String providerId = WeaponKeys.providerOf(typeKey);
                SpecializationManager.pruneUnusable(data, rules, providerId, typeKey);
                if (providerId != null) {
                    refunded.add(providerId);
                }
            }
        }
        return refunded;
    }

    /** 某个来源各特化的普通版等级，写日志用。 */
    private static Map<String, Integer> levelsOf(MasteryData data, String providerId) {
        Map<String, Integer> levels = new TreeMap<>();
        data.getSpecLevelsView().forEach((key, level) -> {
            Specialization spec = Specialization.byKey(key);
            if (spec != null && spec.sourceId().equals(providerId)) {
                levels.put(key, level);
            }
        });
        return levels;
    }

    /** 突破表（按来源或按类型筛），写日志用。 */
    private static Map<String, Map<String, Integer>> breakthroughsOf(MasteryData data, @Nullable String providerId,
                                                                     @Nullable String typeKey) {
        Map<String, Map<String, Integer>> result = new TreeMap<>();
        data.getBreakthroughsView().forEach((type, perType) -> {
            if ((typeKey == null || typeKey.equals(type))
                    && (providerId == null || providerId.equals(WeaponKeys.providerOf(type)))) {
                result.put(type, new TreeMap<>(perType));
            }
        });
        return result;
    }

    /** 各个练过的来源剩下的改装点加起来。 */
    private static int spareModPoints(MasteryData data, MasteryConfig.Snapshot snapshot) {
        int spare = 0;
        for (String overallKey : data.getOverallsView().keySet()) {
            spare += Math.max(0, SpecPoints.modPointsAvailable(data, WeaponKeys.providerOf(overallKey), snapshot));
        }
        return spare;
    }

    /** 各个练过的类型剩下的突破点加起来。 */
    private static int spareBreakthroughPoints(MasteryData data, MasteryConfig.Snapshot snapshot) {
        int spare = 0;
        for (String typeKey : data.getTypesView().keySet()) {
            spare += Math.max(0, SpecPoints.breakthroughPointsAvailable(data, typeKey, snapshot));
        }
        return spare;
    }
}
