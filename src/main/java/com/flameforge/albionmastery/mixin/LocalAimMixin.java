package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.client.gameplay.LocalPlayerAim;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 开镜速度按特化缩放（客户端画面）。
 *
 * <p>补的是什么：客户端自己也推进一份瞄准进度（{@code clientAimingProgress}），镜头拉近、准星收拢、
 * 持枪动画都按它走，算法和服务端 {@code LivingEntityAim} 一模一样。这里在 {@code getAlphaProgress}
 * 那次 {@code Math.max(0, aimTime)} 上乘同一个倍率，保证画面开镜完的那一刻，服务端也认为开镜完了。
 *
 * <p>纯客户端类，只放在 mixins.json 的 client 段。
 */
@Mixin(LocalPlayerAim.class)
public abstract class LocalAimMixin {

    @Shadow
    @Final
    private LocalPlayer player;

    /**
     * 开镜耗时（秒）乘特化倍率。
     *
     * @param aimTime 配件修正后的开镜耗时
     * @return 缩放后的开镜耗时
     */
    @ModifyExpressionValue(
            method = "getAlphaProgress(Lcom/tacz/guns/resource/pojo/data/gun/GunData;)F",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F"),
            require = 0,
            expect = 1
    )
    private float albionmastery$adsTime(float aimTime) {
        double factor = SpecializationEffects.forStack(this.player, this.player.getMainHandItem()).adsTime();
        return factor == 1.0D ? aimTime : (float) (aimTime * factor);
    }
}
