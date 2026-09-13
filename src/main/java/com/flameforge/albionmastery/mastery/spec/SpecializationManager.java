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

/**
 * 特化切换的服务端逻辑：解锁校验、冷却、写入。
 *
 * <p><b>冷却只存在内存里</b>，服务器重启就清零，这是设计要求不是偷懒——
 * 存盘意味着要给每个玩家的每个来源写一个时间戳，还得处理跨版本迁移，
 * 而这个冷却的目的只是防止玩家在战斗中反复横跳，重启清掉没有任何危害。
 *
 * <p>玩家下线不清冷却：重连就能重置的话，冷却等于形同虚设。
 * 表的规模是「上过线且切过流派的玩家数 × 来源数」，几百个条目都算多，不值得做淘汰。
 */
public final class SpecializationManager {

    /** 玩家 UUID -> （类型键 -> 上次切换的时刻，毫秒）。 */
    private static final Map<UUID, Map<String, Long>> LAST_SWITCH = new HashMap<>();

    private SpecializationManager() {
    }

    /**
     * 切换结果。
     */
    public enum Result {
        /** 切换成功。 */
        OK,
        /** 功能被配置关闭。 */
        DISABLED,
        /** 总体专精还没满级。 */
        LOCKED,
        /** 还在冷却里。 */
        COOLDOWN,
        /** 流派不存在，或者和来源对不上。 */
        INVALID,
        /** 已经就是这个流派了。 */
        UNCHANGED
    }

    /**
     * 尝试给玩家切换某个来源的特化。
     *
     * @param player 玩家
     * @param typeKey 目标武器类型的键
     * @param spec    目标流派
     * @return 切换结果
     */
    public static Result select(ServerPlayer player, String typeKey, Specialization spec) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        if (!snapshot.enableSpecialization) {
            return Result.DISABLED;
        }
        // 流派必须属于这个类型所在的来源：枪械的流派不能安到近战武器头上
        if (!WeaponKeys.isValidTypeKey(typeKey)
                || !spec.sourceId().equals(WeaponKeys.providerOf(typeKey))) {
            return Result.INVALID;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return Result.INVALID;
        }
        if (!SpecializationEffects.isUnlocked(data, typeKey, snapshot)) {
            return Result.LOCKED;
        }
        if (spec.key().equals(data.getSpecialization(typeKey))) {
            return Result.UNCHANGED;
        }
        if (remainingCooldownSeconds(player, typeKey, snapshot) > 0L) {
            return Result.COOLDOWN;
        }

        data.setSpecialization(typeKey, spec.key());
        LAST_SWITCH.computeIfAbsent(player.getUUID(), uuid -> new HashMap<>())
                .put(typeKey, System.currentTimeMillis());
        // 移速是挂在玩家属性上的，切了流派要立刻重算，不能等到下次换武器
        SpecMoveSpeed.refresh(player);
        AlbionMastery.LOGGER.debug("玩家 {} 将类型 {} 的特化切换为 {}",
                player.getGameProfile().getName(), typeKey, spec.key());
        return Result.OK;
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
        Map<String, Long> perType = LAST_SWITCH.get(player.getUUID());
        if (perType == null) {
            return 0L;
        }
        Long last = perType.get(typeKey);
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
}
