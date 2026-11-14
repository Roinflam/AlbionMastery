package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.GunRateScaling;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.tacz.guns.resource.pojo.data.gun.BurstData;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 服务端点射冷却里的「两次点射最短间隔」按射速特化缩放。
 *
 * <p>{@code BurstData} 本身拿不到射手，只在 {@link GunBurstRateMixin} 登记了射手的那段时间里
 * （服务端 {@code LivingEntityShoot.getShootCoolDown(long)} 执行期间）才乘，其余时候原样返回。
 * {@code getMinInterval} 在整个 TACZ 里还有三个调用点：脚本查询的点射间隔
 * （{@code GunScriptBurstMixin}）、客户端放行开火和客户端动画（{@code LocalBurstRateMixin}、
 * {@code GunAnimationContextMixin}），它们都在这个窗口之外、各自有缩放，这里不会让它们多乘一次。
 */
@Mixin(value = BurstData.class, remap = false)
public abstract class BurstIntervalMixin {

    /**
     * 缩放点射最短间隔。
     *
     * @param interval 原间隔（秒）
     * @return 缩放后的间隔；没有登记射手时原样返回
     */
    @ModifyReturnValue(method = "getMinInterval()D", at = @At("RETURN"), require = 0, expect = 1)
    private double albionmastery$scale(double interval) {
        LivingEntity shooter = GunRateScaling.current();
        return shooter == null ? interval : GunRateScaling.scaleSeconds(shooter, interval);
    }
}
