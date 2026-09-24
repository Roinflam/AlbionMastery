package com.flameforge.albionmastery.mastery.spec;

import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;

/**
 * 点射模式的射速缩放，给几处点射相关的 Mixin 共用。
 *
 * <p>全自动和半自动的射击间隔 TACZ 统一走 {@code GunData.getShootInterval(射手, …)}，一个 Mixin 就管住了；
 * 点射却分散在好几处直接读 {@code BurstData.getMinInterval()} 和 {@code GunData.getBurstShootInterval()}，
 * 这两个方法拿不到射手，只能在调用点上改。以前这几处都没改，切到点射就能绕开「射速变慢」的代价。
 *
 * <p>服务端算冷却的那个 lambda 是静态的、拿不到射手，由外层方法在调用前后把射手登记进来，按线程存
 * （单人世界客户端和服务端两个线程都可能走到）。
 */
public final class GunRateScaling {

    /** 最短间隔（毫秒）：再快也不低于这个，免得倍率配错时一 tick 打出几十发。 */
    private static final long MIN_INTERVAL_MS = 10L;

    private static final ThreadLocal<LivingEntity> CURRENT_SHOOTER = new ThreadLocal<>();

    private GunRateScaling() {
    }

    /**
     * @param shooter 接下来这段计算属于哪个射手
     */
    public static void enter(@Nullable LivingEntity shooter) {
        CURRENT_SHOOTER.set(shooter);
    }

    /**
     * 计算结束，撤掉登记。
     */
    public static void exit() {
        CURRENT_SHOOTER.remove();
    }

    /**
     * @return 当前登记的射手，没有返回 null
     */
    @Nullable
    public static LivingEntity current() {
        return CURRENT_SHOOTER.get();
    }

    /**
     * 射手主手那把枪的射速倍率。
     */
    private static double factor(@Nullable LivingEntity shooter) {
        if (shooter == null) {
            return 1.0D;
        }
        double factor = SpecializationEffects.forStack(shooter, shooter.getMainHandItem()).fireRate();
        return factor > 0.0D ? factor : 1.0D;
    }

    /**
     * 缩放一个以秒为单位的间隔（{@code getMinInterval}）。
     *
     * @param shooter  射手
     * @param interval 原间隔（秒）
     * @return 缩放后的间隔
     */
    public static double scaleSeconds(@Nullable LivingEntity shooter, double interval) {
        double factor = factor(shooter);
        if (factor == 1.0D || interval <= 0.0D) {
            return interval;
        }
        return Math.max(MIN_INTERVAL_MS / 1000.0D, interval / factor);
    }

    /**
     * 缩放一个以毫秒为单位的间隔（{@code getBurstShootInterval}）。
     *
     * @param shooter  射手
     * @param interval 原间隔（毫秒）
     * @return 缩放后的间隔
     */
    public static long scaleMillis(@Nullable LivingEntity shooter, long interval) {
        double factor = factor(shooter);
        if (factor == 1.0D || interval <= 0L) {
            return interval;
        }
        return Math.max(Math.min(interval, MIN_INTERVAL_MS), Math.round(interval / factor));
    }
}
