package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.GunRateScaling;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 枪械 Lua 脚本里的点射间隔按射速特化缩放。
 *
 * <p>点射一次打出去的几发之间隔多久（{@code burst_shoot_interval}）、脚本查询的射击间隔，
 * 都在这个脚本 API 里读，和 {@link GunBurstRateMixin} 缩放的是同一个倍率。
 */
@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class GunScriptBurstMixin {

    @Shadow
    private LivingEntity shooter;

    /**
     * 点射内每发之间的间隔。
     *
     * @param interval 原间隔（毫秒）
     * @return 缩放后的间隔
     */
    @ModifyExpressionValue(
            method = "shootOnce(Z)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/resource/pojo/data/gun/GunData;getBurstShootInterval()J"),
            require = 0, expect = 1
    )
    private long albionmastery$burstShootInterval(long interval) {
        return GunRateScaling.scaleMillis(this.shooter, interval);
    }

    /**
     * 脚本查询的点射最短间隔。
     *
     * @param interval 原间隔（秒）
     * @return 缩放后的间隔
     */
    @ModifyExpressionValue(
            method = "getShootInterval()J",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/resource/pojo/data/gun/BurstData;getMinInterval()D"),
            require = 0, expect = 1
    )
    private double albionmastery$scriptBurstInterval(double interval) {
        return GunRateScaling.scaleSeconds(this.shooter, interval);
    }
}
