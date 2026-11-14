package com.flameforge.albionmastery.mastery;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.event.CommonEventHandler;
import com.flameforge.albionmastery.leaderboard.MasteryLeaderboard;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 管理员改等级、发武器的服务端实现，指令和罗盘里的管理按钮共用这一套。
 *
 * <p>改等级一律是「直接设经验」，不走正常发经验那条路：不乘学习点、不弹升级广播，
 * 改完由调用方 {@link #commit} 一次全量同步。
 */
public final class MasteryAdmin {

    /** 当「满级」用的等级：比任何曲线的上限都大，设进去会被夹到各层自己的上限。 */
    public static final int MAX = Integer.MAX_VALUE;

    private MasteryAdmin() {
    }

    /**
     * 一次批量操作改了多少条。
     *
     * @param weapons 武器数
     * @param types   类型数
     * @param sources 来源数
     */
    public record Result(int weapons, int types, int sources) {

        /**
         * @return 总条数
         */
        public int total() {
            return this.weapons + this.types + this.sources;
        }
    }

    // 单个节点

    /**
     * 设一件武器的等级。
     *
     * @param data      玩家数据
     * @param weaponKey 武器键
     * @param typeKey   它所属的类型键
     * @param level     等级，超出上限按上限算
     */
    public static void setWeaponLevel(MasteryData data, String weaponKey, String typeKey, int level) {
        LevelCurve curve = MasteryConfig.serverSnapshot().weaponCurve();
        data.setWeaponXp(weaponKey, typeKey, xpFor(curve, level), curve, true);
    }

    /**
     * 设一个类型的等级。
     *
     * @param data    玩家数据
     * @param typeKey 类型键
     * @param level   等级
     */
    public static void setTypeLevel(MasteryData data, String typeKey, int level) {
        LevelCurve curve = MasteryConfig.serverSnapshot().typeCurve();
        data.setTypeXp(typeKey, xpFor(curve, level), curve, true);
    }

    /**
     * 设一个来源的总体等级。
     *
     * @param data       玩家数据
     * @param overallKey 总体键（就是来源 ID）
     * @param level      等级
     */
    public static void setSourceLevel(MasteryData data, String overallKey, int level) {
        LevelCurve curve = MasteryConfig.serverSnapshot().overallCurve();
        data.setOverallXp(overallKey, xpFor(curve, level), curve, true);
    }

    private static long xpFor(LevelCurve curve, int level) {
        return curve.totalXpToReach(Mth.clamp(level, 0, curve.getMaxLevel()));
    }

    /**
     * 按键的格式判断是哪一层，取当前等级。
     *
     * @param data 玩家数据
     * @param key  武器键、类型键或总体键
     * @return 等级；键格式不对返回 -1
     */
    public static int levelOf(MasteryData data, String key) {
        if (WeaponKeys.isValidWeaponKey(key)) {
            return data.getWeaponLevel(key);
        }
        if (WeaponKeys.isValidTypeKey(key)) {
            return data.getTypeLevel(key);
        }
        if (WeaponKeys.isValidOverallKey(key)) {
            return data.getOverallLevel(key);
        }
        return -1;
    }

    /**
     * 设任意一层的等级，按键的格式分派。武器需要类型键，查不到就返回 false。
     *
     * @param player  玩家
     * @param data    玩家数据
     * @param key     武器键、类型键或总体键
     * @param typeKey 武器所属的类型键，可为 null（从清单或记录里找）
     * @param level   等级
     * @return 设成了返回 true
     */
    public static boolean setLevel(ServerPlayer player, MasteryData data, String key, @Nullable String typeKey,
                                   int level) {
        if (WeaponKeys.isValidWeaponKey(key)) {
            String resolvedType = typeKeyOf(player.server, data, key, typeKey);
            if (resolvedType == null) {
                return false;
            }
            setWeaponLevel(data, key, resolvedType, level);
            return true;
        }
        if (WeaponKeys.isValidTypeKey(key)) {
            setTypeLevel(data, key, level);
            return true;
        }
        if (WeaponKeys.isValidOverallKey(key)) {
            setSourceLevel(data, key, level);
            return true;
        }
        return false;
    }

    /**
     * 找一件武器所属的类型：服务端清单说了算，其次是玩家自己的记录，最后才信调用方给的。
     *
     * <p>调用方给的类型（界面发来的）只在和武器同一个来源时才接受，免得一个坏包把武器挂到别的来源下面。
     *
     * @param server    服务端
     * @param data      玩家数据
     * @param weaponKey 武器键
     * @param hint      调用方给的类型键，可为 null
     * @return 类型键；哪儿都找不到返回 null
     */
    @Nullable
    public static String typeKeyOf(MinecraftServer server, MasteryData data, String weaponKey,
                                   @Nullable String hint) {
        WeaponCatalog.Entry entry = WeaponCatalog.byKey(server, weaponKey);
        if (entry != null) {
            return entry.weapon().typeKey();
        }
        MasteryData.WeaponRecord record = data.getWeaponsView().get(weaponKey);
        if (record != null && record.typeKey != null) {
            return record.typeKey;
        }
        if (hint != null && WeaponKeys.isValidTypeKey(hint)
                && hint.startsWith(WeaponKeys.providerOf(weaponKey) + "#")) {
            return hint;
        }
        return null;
    }

    // 批量

    /**
     * 全部设成同一个等级：服务端认得的所有武器、它们的类型和来源，外加玩家记录里已有的条目。
     *
     * <p>设成 0 时只动已有的记录，不给清单里一千多件没碰过的武器各建一条 0 级记录。
     *
     * @param player 玩家
     * @param data   玩家数据
     * @param level  等级
     * @return 改了多少条
     */
    public static Result setAll(ServerPlayer player, MasteryData data, int level) {
        return setWhere(player, data, level, key -> true);
    }

    /**
     * 整个来源设成同一个等级：来源本身、旗下所有类型和武器。
     *
     * @param player     玩家
     * @param data       玩家数据
     * @param providerId 来源 ID
     * @param level      等级
     * @return 改了多少条
     */
    public static Result setSource(ServerPlayer player, MasteryData data, String providerId, int level) {
        return setWhere(player, data, level, key -> providerId.equals(WeaponKeys.providerOf(key)));
    }

    /**
     * 整个类型设成同一个等级：类型本身和旗下所有武器（不动来源）。
     *
     * @param player  玩家
     * @param data    玩家数据
     * @param typeKey 类型键
     * @param level   等级
     * @return 改了多少条
     */
    public static Result setType(ServerPlayer player, MasteryData data, String typeKey, int level) {
        Map<String, String> weapons = weaponsWhere(player.server, data, level > 0,
                (weaponKey, weaponType) -> typeKey.equals(weaponType));
        for (Map.Entry<String, String> entry : weapons.entrySet()) {
            setWeaponLevel(data, entry.getKey(), entry.getValue(), level);
        }
        setTypeLevel(data, typeKey, level);
        return new Result(weapons.size(), 1, 0);
    }

    private static Result setWhere(ServerPlayer player, MasteryData data, int level, Predicate<String> keyFilter) {
        boolean create = level > 0;
        Map<String, String> weapons = weaponsWhere(player.server, data, create,
                (weaponKey, weaponType) -> keyFilter.test(weaponKey));

        Set<String> types = new LinkedHashSet<>(weapons.values());
        for (String typeKey : data.getTypesView().keySet()) {
            if (keyFilter.test(typeKey)) {
                types.add(typeKey);
            }
        }
        Set<String> sources = new LinkedHashSet<>();
        for (String typeKey : types) {
            sources.add(WeaponKeys.overallKey(WeaponKeys.providerOf(typeKey)));
        }
        for (String overallKey : data.getOverallsView().keySet()) {
            if (keyFilter.test(overallKey)) {
                sources.add(overallKey);
            }
        }

        for (Map.Entry<String, String> entry : weapons.entrySet()) {
            setWeaponLevel(data, entry.getKey(), entry.getValue(), level);
        }
        for (String typeKey : types) {
            setTypeLevel(data, typeKey, level);
        }
        for (String overallKey : sources) {
            setSourceLevel(data, overallKey, level);
        }
        return new Result(weapons.size(), types.size(), sources.size());
    }

    /**
     * 挑出要改的武器（武器键 -&gt; 类型键）。
     *
     * @param create 是否把清单里还没有记录的武器也算进来（设 0 级时不算）
     */
    private static Map<String, String> weaponsWhere(MinecraftServer server, MasteryData data, boolean create,
                                                    WeaponFilter filter) {
        Map<String, String> weapons = new LinkedHashMap<>();
        if (create) {
            for (WeaponCatalog.Entry entry : WeaponCatalog.all(server)) {
                String weaponKey = entry.weapon().weaponKey();
                String typeKey = entry.weapon().typeKey();
                if (filter.test(weaponKey, typeKey)) {
                    weapons.put(weaponKey, typeKey);
                }
            }
        }
        // 记录里有、清单里没有的（卸掉的枪包之类）也一起改，类型以清单为准
        for (Map.Entry<String, MasteryData.WeaponRecord> entry : data.getWeaponsView().entrySet()) {
            String weaponKey = entry.getKey();
            if (weapons.containsKey(weaponKey)) {
                continue;
            }
            String typeKey = typeKeyOf(server, data, weaponKey, null);
            if (typeKey != null && filter.test(weaponKey, typeKey)) {
                weapons.put(weaponKey, typeKey);
            }
        }
        return weapons;
    }

    @FunctionalInterface
    private interface WeaponFilter {
        boolean test(String weaponKey, String typeKey);
    }

    // 收尾与发物品

    /**
     * 改完之后：全量同步给本人，并立刻刷新排行榜上他那一行（不广播前三易主）。
     *
     * @param player 玩家
     */
    public static void commit(ServerPlayer player) {
        CommonEventHandler.syncFull(player);
        if (MasteryConfig.COMMON.leaderboardEnable.get()) {
            try {
                MasteryLeaderboard.get(player.server).updateQuietly(player);
            } catch (Exception exception) {
                AlbionMastery.LOGGER.warn("管理操作后刷新排行榜失败", exception);
            }
        }
    }

    /**
     * 把一件武器对应的物品发给玩家，背包满了就掉在脚下（只有他能捡）。
     *
     * @param player 玩家
     * @param entry  清单条目
     * @return 发出去的第一件物品（拿来显示名字）；一件都没造出来返回空
     */
    public static ItemStack give(ServerPlayer player, WeaponCatalog.Entry entry) {
        List<ItemStack> items = new ArrayList<>(entry.createItems());
        if (items.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack first = items.get(0).copy();
        for (ItemStack stack : items) {
            boolean added = player.getInventory().add(stack);
            if (!added || !stack.isEmpty()) {
                ItemEntity dropped = player.drop(stack, false);
                if (dropped != null) {
                    dropped.setNoPickUpDelay();
                    dropped.setTarget(player.getUUID());
                }
            }
        }
        player.containerMenu.broadcastChanges();
        return first;
    }
}
