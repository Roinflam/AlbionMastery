package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.GunRateScaling;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.client.gameplay.LocalPlayerShoot;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 客户端点射的冷却和连发间隔按射速特化缩放，和服务端一致，不然客户端会按原速排程开火、被服务端拒掉。
 */
@Mixin(value = LocalPlayerShoot.class, remap = false)
public abstract class LocalBurstRateMixin {

    /**
     * 两次点射之间的最短间隔。
     *
     * @param interval 原间隔（秒）
     * @return 缩放后的间隔
     */
    @ModifyExpressionValue(
            method = "getCoolDown",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/resource/pojo/data/gun/BurstData;getMinInterval()D"),
            require = 0
    )
    private double albionmastery$burstCoolDown(double interval) {
        return GunRateScaling.scaleSeconds(Minecraft.getInstance().player, interval);
    }

    /**
     * 点射内每发之间的间隔。
     *
     * @param interval 原间隔（毫秒）
     * @return 缩放后的间隔
     */
    @ModifyExpressionValue(
            method = "doShoot",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/resource/pojo/data/gun/GunData;getBurstShootInterval()J"),
            require = 0
    )
    private long albionmastery$burstShootInterval(long interval) {
        return GunRateScaling.scaleMillis(Minecraft.getInstance().player, interval);
    }
}
