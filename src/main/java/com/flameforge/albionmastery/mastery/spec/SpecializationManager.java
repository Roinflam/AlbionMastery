package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecRules;
import com.flameforge.albionmastery.mastery.MasteryAdmin;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 特化的服务端逻辑：装卸、升级、突破、重置，以及各自的校验和冷却。
 *
 * <p><b>换装冷却只存在内存里</b>，服务端重启就清零——它只是防止玩家在战斗中反复横跳，重启清掉没有危害。
 * 玩家下线不清冷却：重连就能重置的话，冷却等于形同虚设。
 *
 * <p><b>重置冷却记在存档里</b>（默认 24 小时，按点数池各记各的）：重置是换整套思路，冷却要长，
 * 重启就清的话形同虚设。升级、突破只花点数，不触发任何冷却。
 */
public final class SpecializationManager {

    /** 玩家 UUID -> （类型键 / 武器键 + 槽位 -> 上次切换的时刻，毫秒）。 */
    private static final Map<UUID, Map<String, Long>> LAST_SWITCH = new HashMap<>();

    private SpecializationManager() {
    }

    /**
     * 清空所有人的切换冷却。
     *
     * <p>关服时调用。单人 / 开局域网时集成服务端关了客户端进程还活着，静态表会跟着活到下一个存档，
     * 玩家 UUID 又是同一个——退出重进另一个存档，冷却还在。
     */
    public static void clearAll() {
        LAST_SWITCH.clear();
    }

    /**
     * 操作结果。
     */
    public enum Result {
        /** 成功。 */
        OK,
        /** 功能被配置关闭。 */
        DISABLED,
        /** 这个槽还没开。 */
        LOCKED,
        /** 还在冷却里。 */
        COOLDOWN,
        /** 特化不存在，或者和来源对不上。 */
        INVALID,
        /** 已经就是这个特化了 / 没有可重置的。 */
        UNCHANGED,
        /** 另一个槽已经选了同一个特化。 */
        DUPLICATE,
        /** 另一个槽已经装了同系的特化（比如两种弹匣）。 */
        CONFLICT,
        /** 特化还没升级（普通槽要 Lv1，突破槽要在这个类型里突破到 ★1）。 */
        NOT_LEVELED,
        /** 点数不够。 */
        NO_POINTS,
        /** 已经满级。 */
        MAXED,
        /** 普通版还没满级，不能突破。 */
        NEED_NORMAL_MAX
    }

    // ======================================================================
    // 装卸
    // ======================================================================

    /**
     * 给某个类型的某个槽位选特化。
     *
     * @param player  玩家
     * @param typeKey 类型键
     * @param slot    槽位
     * @param spec    特化
     * @return 结果
     */
    public static Result select(ServerPlayer player, String typeKey, int slot, Specialization spec) {
        return select(player, typeKey, null, slot, spec);
    }

    /**
     * 尝试切换特化：{@code weaponKey} 为 null 时切类型的通用配置（中圈），否则切这件武器的独立配置（外圈）。
     *
     * <p>武器第一次单独配置时，先把类型的通用配置整套复制过来作为起点，再改玩家点的那一栏——
     * 不然别的栏会突然变空，玩家只想换一栏，结果几栏都变了。
     *
     * <p>槽开没开两种配置一样看：类型里有武器过了槽 1 的门槛、这个槽自己的条件也到了。
     * 独立配置可以提前给还没练到门槛的武器配好，练到了自然生效。
     *
     * @param player    玩家
     * @param typeKey   类型键
     * @param weaponKey 武器键，null 表示改通用配置
     * @param slot      槽位
     * @param spec      目标特化，制式表示清空这个槽
     * @return 结果
     */
    public static Result select(ServerPlayer player, String typeKey, @Nullable String weaponKey, int slot,
                                Specialization spec) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        SpecRules rules = snapshot.specRules;
        if (!snapshot.enableSpecialization) {
            return Result.DISABLED;
        }
        if (slot < 1 || slot > rules.slotCount) {
            return Result.INVALID;
        }
        // 特化必须属于这个类型所在的来源：枪械的特化不能安到近战武器头上
        if (!WeaponKeys.isValidTypeKey(typeKey)
                || !spec.sourceId().equals(WeaponKeys.providerOf(typeKey))) {
            return Result.INVALID;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return Result.INVALID;
        }
        if (weaponKey != null && !weaponBelongsTo(player, data, weaponKey, typeKey)) {
            return Result.INVALID;
        }
        if (!SpecializationEffects.isSlotUnlocked(data, typeKey, null, slot, snapshot)) {
            return Result.LOCKED;
        }
        // 比较、查冲突都按「这件武器此刻实际在用的那一份」：还没有独立配置时就是类型的通用配置
        String effective = weaponKey == null ? typeKey : data.specConfigKey(typeKey, weaponKey);
        String current = data.getSpecialization(effective, slot);
        if (spec.key().equals(current) || (current == null && spec.isStandard())
                || (spec.isStandard() && isStandard(current))) {
            return Result.UNCHANGED;
        }
        if (!spec.isStandard()) {
            if (SpecializationEffects.levelInSlot(data, typeKey, spec, slot, rules) <= 0) {
                return Result.NOT_LEVELED;
            }
            // 别的槽已经选了同一个特化：不许重复（普通版和它的突破版也算同一个）；选了同系的：不许同时装
            for (int other = 1; other <= rules.slotCount; other++) {
                if (other == slot) {
                    continue;
                }
                String otherKey = data.getSpecialization(effective, other);
                if (spec.key().equals(otherKey)) {
                    return Result.DUPLICATE;
                }
                if (spec.conflictsWith(Specialization.byKey(otherKey))) {
                    return Result.CONFLICT;
                }
            }
        }
        String target = weaponKey == null ? typeKey : weaponKey;
        // 冷却同时记在类型键上：不然「中圈切一次、外圈再切一次、换把武器又一次」各算各的键，
        // 冷却形同虚设。同一类武器的配置换来换去，都得等同一个冷却
        if (remainingSwitchCooldown(player, typeKey, weaponKey, slot, snapshot) > 0L) {
            return Result.COOLDOWN;
        }

        if (weaponKey == null) {
            data.setSpecialization(typeKey, slot, spec.isStandard() ? null : spec.key());
        } else {
            if (!data.hasWeaponOverride(weaponKey)) {
                // 第一次单独配置：先把通用配置整套抄过来，空栏也写成制式，独立配置才认得出来
                Specialization standard = Specialization.standardOf(WeaponKeys.providerOf(typeKey));
                for (int copy = 1; copy <= WeaponKeys.SPEC_SLOTS; copy++) {
                    String inherited = data.getSpecialization(typeKey, copy);
                    data.setSpecialization(weaponKey, copy,
                            inherited != null ? inherited : standard == null ? null : standard.key());
                }
            }
            data.setSpecialization(weaponKey, slot, spec.key());
        }
        long switchedAt = System.currentTimeMillis();
        Map<String, Long> cooldowns = LAST_SWITCH.computeIfAbsent(player.getUUID(), uuid -> new HashMap<>());
        cooldowns.put(WeaponKeys.specSlotKey(target, slot), switchedAt);
        if (weaponKey != null) {
            cooldowns.put(WeaponKeys.specSlotKey(typeKey, slot), switchedAt);
        }
        // 移速是挂在玩家属性上的，切了特化要立刻重算，不能等到下次换武器
        SpecMoveSpeed.refresh(player);
        AlbionMastery.LOGGER.debug("玩家 {} 将类型 {} 槽位 {} 的特化切换为 {}",
                player.getGameProfile().getName(), typeKey, slot, spec.key());
        return Result.OK;
    }

    /**
     * 让这件武器回到跟随类型的通用配置：删掉它的独立配置。
     *
     * @param player    玩家
     * @param typeKey   类型键
     * @param weaponKey 武器键
     * @return {@code OK} 删掉了；{@code UNCHANGED} 本来就在跟随；{@code INVALID} 武器和类型对不上
     */
    public static Result followType(ServerPlayer player, String typeKey, String weaponKey) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        // 和 select 一样先认总开关：这是唯一一个能改存档、却不看开关的入口
        if (!snapshot.enableSpecialization) {
            return Result.DISABLED;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null || !weaponBelongsTo(player, data, weaponKey, typeKey)) {
            return Result.INVALID;
        }
        if (!data.hasWeaponOverride(weaponKey)) {
            return Result.UNCHANGED;
        }
        // 删掉独立配置同样是一次实打实的切换：数值立刻变回类型的通用配置。
        // 不查冷却的话，「切一下 → 点恢复通用 → 再切一下」就能绕开整个冷却
        if (remainingSwitchCooldown(player, typeKey, weaponKey, snapshot) > 0L) {
            return Result.COOLDOWN;
        }
        if (!data.clearWeaponOverride(weaponKey)) {
            return Result.UNCHANGED;
        }
        long switchedAt = System.currentTimeMillis();
        Map<String, Long> cooldowns = LAST_SWITCH.computeIfAbsent(player.getUUID(), uuid -> new HashMap<>());
        for (int slot = 1; slot <= WeaponKeys.SPEC_SLOTS; slot++) {
            cooldowns.put(WeaponKeys.specSlotKey(weaponKey, slot), switchedAt);
            cooldowns.put(WeaponKeys.specSlotKey(typeKey, slot), switchedAt);
        }
        SpecMoveSpeed.refresh(player);
        return Result.OK;
    }

    // ======================================================================
    // 升级、突破
    // ======================================================================

    /**
     * 花改装点把特化的普通版升一级。普通版按来源共用，改装点从这个来源那一份里扣。
     *
     * @param player 玩家
     * @param spec   特化
     * @return 结果
     */
    public static Result upgrade(ServerPlayer player, Specialization spec) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        SpecRules rules = snapshot.specRules;
        if (!snapshot.enableSpecialization) {
            return Result.DISABLED;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null || spec.isStandard()) {
            return Result.INVALID;
        }
        int level = data.getSpecLevel(spec.key());
        if (level >= rules.normalMax()) {
            return Result.MAXED;
        }
        if (SpecPoints.modPointsAvailable(data, spec.sourceId(), snapshot) < rules.normalCost(level + 1)) {
            return Result.NO_POINTS;
        }
        data.setSpecLevel(spec.key(), level + 1);
        SpecMoveSpeed.refresh(player);
        return Result.OK;
    }

    /**
     * 花这个类型的突破点把特化突破一级。普通版要先满级。
     *
     * @param player  玩家
     * @param typeKey 类型键
     * @param spec    特化
     * @return 结果
     */
    public static Result breakthrough(ServerPlayer player, String typeKey, Specialization spec) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        SpecRules rules = snapshot.specRules;
        if (!snapshot.enableSpecialization) {
            return Result.DISABLED;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null || spec.isStandard() || !WeaponKeys.isValidTypeKey(typeKey)
                || !spec.sourceId().equals(WeaponKeys.providerOf(typeKey))) {
            return Result.INVALID;
        }
        if (data.getSpecLevel(spec.key()) < rules.normalMax()) {
            return Result.NEED_NORMAL_MAX;
        }
        int star = data.getBreakthrough(typeKey, spec.key());
        if (star >= rules.breakthroughMax()) {
            return Result.MAXED;
        }
        if (SpecPoints.breakthroughPointsAvailable(data, typeKey, snapshot) < rules.breakthroughCost(star + 1)) {
            return Result.NO_POINTS;
        }
        data.setBreakthrough(typeKey, spec.key(), star + 1);
        SpecMoveSpeed.refresh(player);
        return Result.OK;
    }

    // ======================================================================
    // 重置
    // ======================================================================

    /**
     * 退掉一个来源的全部改装点：这个来源所有特化回到 0 级，所有类型里的突破一起退（突破依赖普通版满级）。
     * 装着的特化跟着卸下。
     *
     * @param player     玩家
     * @param providerId 来源 ID
     * @return 结果
     */
    public static Result respecSource(ServerPlayer player, String providerId) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        if (!snapshot.enableSpecialization) {
            return Result.DISABLED;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null || !WeaponKeys.isValidOverallKey(providerId)) {
            return Result.INVALID;
        }
        if (SpecPoints.respecRemainingMillis(data, providerId, snapshot.specRules, System.currentTimeMillis()) > 0L) {
            return Result.COOLDOWN;
        }
        if (!data.clearSourceProgress(providerId)) {
            return Result.UNCHANGED;
        }
        data.setRespecAt(providerId, System.currentTimeMillis());
        pruneUnusable(data, snapshot.specRules, providerId, null);
        SpecMoveSpeed.refresh(player);
        return Result.OK;
    }

    /**
     * 退掉一个类型的全部突破点。突破槽里装着的、突破没了的特化跟着卸下。
     *
     * @param player  玩家
     * @param typeKey 类型键
     * @return 结果
     */
    public static Result respecType(ServerPlayer player, String typeKey) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        if (!snapshot.enableSpecialization) {
            return Result.DISABLED;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null || !WeaponKeys.isValidTypeKey(typeKey)) {
            return Result.INVALID;
        }
        if (SpecPoints.respecRemainingMillis(data, typeKey, snapshot.specRules, System.currentTimeMillis()) > 0L) {
            return Result.COOLDOWN;
        }
        if (!data.clearTypeBreakthroughs(typeKey)) {
            return Result.UNCHANGED;
        }
        data.setRespecAt(typeKey, System.currentTimeMillis());
        pruneUnusable(data, snapshot.specRules, WeaponKeys.providerOf(typeKey), typeKey);
        SpecMoveSpeed.refresh(player);
        return Result.OK;
    }

    /**
     * 卸下等级不够、在槽里已经用不了的特化。
     *
     * <p>类型的通用配置直接清掉那一栏；武器的独立配置写成制式（删掉的话独立配置可能整个认不出来了）。
     *
     * @param data       玩家数据
     * @param rules      规则
     * @param providerId 只处理这个来源；null 表示全部
     * @param onlyType   只处理这个类型（以及它下面武器的独立配置）；null 表示不限
     * @return 卸下了几个
     */
    public static int pruneUnusable(MasteryData data, SpecRules rules, @Nullable String providerId,
                                    @Nullable String onlyType) {
        List<String> remove = new ArrayList<>();
        Map<String, String> replace = new HashMap<>();
        for (Map.Entry<String, String> entry : data.getSpecializationsView().entrySet()) {
            String slotKey = entry.getKey();
            Specialization spec = Specialization.byKey(entry.getValue());
            if (spec == null || spec.isStandard()) {
                continue;
            }
            if (providerId != null && !providerId.equals(spec.sourceId())) {
                continue;
            }
            String base = WeaponKeys.specBaseKey(slotKey);
            boolean weapon = WeaponKeys.isValidWeaponKey(base);
            String typeKey = weapon ? data.getWeaponTypeKey(base) : base;
            if (typeKey == null || (onlyType != null && !onlyType.equals(typeKey))) {
                continue;
            }
            int slot = WeaponKeys.specSlotOf(slotKey);
            if (SpecializationEffects.levelInSlot(data, typeKey, spec, slot, rules) > 0) {
                continue;
            }
            if (weapon) {
                Specialization standard = Specialization.standardOf(spec.sourceId());
                if (standard != null) {
                    replace.put(slotKey, standard.key());
                    continue;
                }
            }
            remove.add(slotKey);
        }
        for (String slotKey : remove) {
            data.setSpecialization(WeaponKeys.specBaseKey(slotKey), WeaponKeys.specSlotOf(slotKey), null);
        }
        replace.forEach((slotKey, standard) ->
                data.setSpecialization(WeaponKeys.specBaseKey(slotKey), WeaponKeys.specSlotOf(slotKey), standard));
        return remove.size() + replace.size();
    }

    // ======================================================================
    // 校验和冷却
    // ======================================================================

    /**
     * 客户端说的「这件武器属于这个类型」对不对：按服务端清单、再按玩家记录核一遍，
     * 不然可以借一个等级高的类型去解锁另一个类型里武器的特化。
     */
    private static boolean weaponBelongsTo(ServerPlayer player, MasteryData data, String weaponKey,
                                           String typeKey) {
        if (!WeaponKeys.isValidWeaponKey(weaponKey) || !WeaponKeys.isValidTypeKey(typeKey)) {
            return false;
        }
        String actual = MasteryAdmin.typeKeyOf(player.server, data, weaponKey, null);
        return typeKey.equals(actual);
    }

    private static boolean isStandard(@Nullable String specKey) {
        Specialization spec = Specialization.byKey(specKey);
        return spec == null || spec.isStandard();
    }

    /**
     * 这件武器 / 这个类型的某个槽此刻还剩多少换装冷却：两个键取大。
     *
     * <p>判定和报时必须走同一个方法：以前判定查两个键、报时只查一个，
     * 拒绝原因来自类型键那一侧时，提示里会写成「还需 0 分钟」。
     *
     * @param player    玩家
     * @param typeKey   类型键
     * @param weaponKey 武器键，可为 null
     * @param slot      槽位
     * @param snapshot  配置快照
     * @return 剩余秒数
     */
    public static long remainingSwitchCooldown(ServerPlayer player, String typeKey, @Nullable String weaponKey,
                                               int slot, MasteryConfig.Snapshot snapshot) {
        long remaining = remainingCooldownSeconds(player, typeKey, slot, snapshot);
        if (weaponKey != null) {
            remaining = Math.max(remaining, remainingCooldownSeconds(player, weaponKey, slot, snapshot));
        }
        return remaining;
    }

    /**
     * 所有槽里最长的那个剩余换装冷却。
     *
     * @param player    玩家
     * @param typeKey   类型键
     * @param weaponKey 武器键，可为 null
     * @param snapshot  配置快照
     * @return 剩余秒数
     */
    public static long remainingSwitchCooldown(ServerPlayer player, String typeKey, @Nullable String weaponKey,
                                               MasteryConfig.Snapshot snapshot) {
        long remaining = 0L;
        for (int slot = 1; slot <= WeaponKeys.SPEC_SLOTS; slot++) {
            remaining = Math.max(remaining, remainingSwitchCooldown(player, typeKey, weaponKey, slot, snapshot));
        }
        return remaining;
    }

    /**
     * 某个类型某个槽位的换装冷却还剩几秒。
     *
     * @param player   玩家
     * @param typeKey  类型键或武器键
     * @param slot     槽位
     * @param snapshot 配置快照
     * @return 剩余秒数，0 表示可以切
     */
    public static long remainingCooldownSeconds(ServerPlayer player, String typeKey, int slot,
                                                MasteryConfig.Snapshot snapshot) {
        Map<String, Long> perType = LAST_SWITCH.get(player.getUUID());
        if (perType == null) {
            return 0L;
        }
        Long last = perType.get(WeaponKeys.specSlotKey(typeKey, slot));
        if (last == null) {
            return 0L;
        }
        long cooldownMs = Math.max(0L, snapshot.specSwitchCooldownSeconds) * 1000L;
        long elapsed = System.currentTimeMillis() - last;
        // 系统时间被往回调过时 elapsed 会是负数，当作冷却已过，别把玩家永久锁死
        if (elapsed < 0L || elapsed >= cooldownMs) {
            return 0L;
        }
        return (cooldownMs - elapsed + 999L) / 1000L;
    }

    /**
     * 清掉某名玩家全部类型、全部槽位的换装冷却（管理指令用）。
     *
     * @param uuid 玩家 UUID
     * @return 原来有没有冷却记录
     */
    public static boolean clearCooldowns(UUID uuid) {
        return LAST_SWITCH.remove(uuid) != null;
    }
}
