package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.flameforge.albionmastery.mastery.MasteryAdmin;
import javax.annotation.Nullable;

/**
 * 特化切换的服务端逻辑：解锁校验、冷却、写入。
 *
 * <p><b>冷却只存在内存里</b>，服务端重启就清零，这是设计要求不是偷懒——
 * 存盘意味着要给每个玩家的每个来源写一个时间戳，还得处理跨版本迁移，
 * 而这个冷却的目的只是防止玩家在战斗中反复横跳，重启清掉没有任何危害。
 *
 * <p>玩家下线不清冷却：重连就能重置的话，冷却等于形同虚设。
 * 表的规模是「上过线且切过特化的玩家数 × 来源数」，几百个条目都算多，不值得做淘汰。
 */
public final class SpecializationManager {

    /** 玩家 UUID -> （类型键 -> 上次切换的时刻，毫秒）。 */
    private static final Map<UUID, Map<String, Long>> LAST_SWITCH = new HashMap<>();

    private SpecializationManager() {
    }

    /**
     * 清空所有人的切换冷却。
     *
     * <p>关服时调用。类注释说的「服务端重启就清零」在专用服务端上自然成立，
     * 但单人 / 开局域网时集成服务端关了客户端进程还活着，静态表会跟着活到下一个存档，
     * 玩家 UUID 又是同一个——退出重进另一个存档，冷却还在。
     */
    public static void clearAll() {
        LAST_SWITCH.clear();
    }

    /**
     * 切换结果。
     */
    public enum Result {
        /** 切换成功。 */
        OK,
        /** 功能被配置关闭。 */
        DISABLED,
        /** 这个槽还没解锁（本类型进度不够）。 */
        LOCKED,
        /** 还在冷却里。 */
        COOLDOWN,
        /** 特化不存在，或者和来源对不上。 */
        INVALID,
        /** 已经就是这个特化了。 */
        UNCHANGED,
        /** 另一个槽已经选了同一个特化。 */
        DUPLICATE,
        /** 另一个槽已经装了同系的特化（比如两种弹匣）。 */
        CONFLICT
    }

    /**
     * 尝试给玩家切换某个来源的特化。
     *
     * @param player 玩家
     * @param typeKey 目标武器类型的键
     * @param spec    目标特化
     * @return 切换结果
     */
    public static Result select(ServerPlayer player, String typeKey, Specialization spec) {
        return select(player, typeKey, 1, spec);
    }

    /**
     * 给某个类型的某个槽位选特化。
     *
     * <p>两个槽不能选同一个特化（叠两份同样的东西没意义，还会把倍率翻倍）。
     * 选「制式」等于把这个槽清空。冷却按槽位各记各的。
     *
     * @param player  玩家
     * @param typeKey 类型键
     * @param slot    槽位，1 或 2
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
     * 不然另一栏会突然变空，玩家只想换一栏，结果两栏都变了。
     * 解锁门槛两种配置一样，都看类型和来源的等级。
     *
     * @param player    玩家
     * @param typeKey   类型键
     * @param weaponKey 武器键，null 表示改通用配置
     * @param slot      槽位
     * @param spec      目标特化
     * @return 切换结果
     */
    public static Result select(ServerPlayer player, String typeKey, @Nullable String weaponKey, int slot,
                                Specialization spec) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        if (!snapshot.enableSpecialization) {
            return Result.DISABLED;
        }
        if (slot < 1 || slot > WeaponKeys.SPEC_SLOTS) {
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
        if (!SpecializationEffects.isSlotUnlocked(data, typeKey, slot, snapshot)) {
            return Result.LOCKED;
        }
        // 比较、查冲突都按「这件武器此刻实际在用的那一份」：还没有独立配置时就是类型的通用配置
        String effective = weaponKey == null ? typeKey : data.specConfigKey(typeKey, weaponKey);
        String current = data.getSpecialization(effective, slot);
        if (spec.key().equals(current) || (current == null && spec.isStandard())
                || (spec.isStandard() && isStandard(current))) {
            return Result.UNCHANGED;
        }
        // 另一个槽已经选了同一个特化：不许重复；选了同系的：不许同时装
        for (int other = 1; other <= WeaponKeys.SPEC_SLOTS; other++) {
            if (other == slot || spec.isStandard()) {
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
     * 这件武器 / 这个类型的某个槽此刻还剩多少冷却：两个键取大。
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
     * 所有槽里最长的那个剩余冷却。
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
     * 查询某个来源的切换冷却还剩多少秒。
     *
     * @param player   玩家
     * @param typeKey  类型键
     * @param snapshot 配置快照
     * @return 剩余秒数，不在冷却中时返回 0
     */
    public static long remainingCooldownSeconds(ServerPlayer player, String typeKey,
                                                MasteryConfig.Snapshot snapshot) {
        return remainingCooldownSeconds(player, typeKey, 1, snapshot);
    }

    /**
     * 某个类型某个槽位的切换冷却还剩几秒。
     *
     * @param player   玩家
     * @param typeKey  类型键
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
     * 清掉某名玩家全部类型、全部槽位的切换冷却（管理指令用）。
     *
     * @param uuid 玩家 UUID
     * @return 原来有没有冷却记录
     */
    public static boolean clearCooldowns(UUID uuid) {
        return LAST_SWITCH.remove(uuid) != null;
    }
}
