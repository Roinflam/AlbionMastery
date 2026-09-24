package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.GunRateScaling;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.entity.shooter.LivingEntityShoot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 服务端点射冷却按射速特化缩放。
 *
 * <p>点射的「两次点射之间最短隔多久」在一个静态 lambda 里读 {@code BurstData.getMinInterval()}，拿不到射手；
 * 由外层的 {@code getShootCoolDown(long)} 在进出时把射手登记到 {@link GunRateScaling}。
 */
@Mixin(value = LivingEntityShoot.class, remap = false)
public abstract class GunBurstRateMixin {

    @Shadow
    @Final
    private LivingEntity shooter;

    @Inject(method = "getShootCoolDown(J)J", at = @At("HEAD"), require = 0)
    private void albionmastery$enter(long timestamp, CallbackInfoReturnable<Long> callback) {
        GunRateScaling.enter(this.shooter);
    }

    @Inject(method = "getShootCoolDown(J)J", at = @At("RETURN"), require = 0)
    private void albionmastery$exit(long timestamp, CallbackInfoReturnable<Long> callback) {
        GunRateScaling.exit();
    }

    /**
     * 点射最短间隔。
     *
     * @param interval 原间隔（秒）
     * @return 缩放后的间隔
     */
    @ModifyExpressionValue(
            method = "lambda$getShootCoolDown$1",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/resource/pojo/data/gun/BurstData;getMinInterval()D"),
            require = 0
    )
    private static double albionmastery$burstInterval(double interval) {
        return GunRateScaling.scaleSeconds(GunRateScaling.current(), interval);
    }
}
