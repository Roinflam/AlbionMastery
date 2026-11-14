package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.GunRateScaling;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.tacz.guns.entity.shooter.LivingEntityShoot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 服务端点射冷却按射速特化缩放：这里只负责在算冷却的那段时间里登记射手。
 *
 * <p>点射的「两次点射之间最短隔多久」在一个静态 lambda 里读 {@code BurstData.getMinInterval()}，拿不到射手；
 * 外层 {@code getShootCoolDown(long)} 执行期间把射手登记到 {@link GunRateScaling}，
 * 由 {@link BurstIntervalMixin} 在 getter 的返回值上乘倍率。不挂 lambda 本身，是因为 lambda 的编号由编译器排，
 * TACZ 1.2.0 就从 {@code $1} 变成了 {@code $0}，旧写法在 {@code require = 0} 下静默失效。
 *
 * <p>进出合成一个 {@code @WrapMethod}，在 {@code finally} 里撤登记：分开写 HEAD / RETURN 两个注入时，
 * 方法中途抛异常就走不到 RETURN，登记会一直留在服务端线程上，之后脚本查点射间隔时会被多乘一次。
 * 撤登记时恢复进来之前的值，嵌套调用也不会把外层的登记清掉。
 *
 * <p>{@code getShootCoolDown(long)} 里调到 {@code getMinInterval} 的只有点射那条分支；
 * 连发、半自动走 {@code GunData.getShootInterval}，由 {@code GunFireRateMixin} 缩放，这里不重复乘。
 */
@Mixin(value = LivingEntityShoot.class, remap = false)
public abstract class GunBurstRateMixin {

    @Shadow
    @Final
    private LivingEntity shooter;

    /**
     * 算冷却期间登记射手，结束（含抛异常）时撤掉。
     *
     * @param timestamp 开火时间（毫秒）
     * @param original  原方法
     * @return 原方法算出的冷却
     */
    @WrapMethod(method = "getShootCoolDown(J)J", require = 0, expect = 1)
    private long albionmastery$burstWindow(long timestamp, Operation<Long> original) {
        LivingEntity previous = GunRateScaling.current();
        GunRateScaling.enter(this.shooter);
        try {
            return original.call(timestamp);
        } finally {
            if (previous == null) {
                GunRateScaling.exit();
            } else {
                GunRateScaling.enter(previous);
            }
        }
    }
}
