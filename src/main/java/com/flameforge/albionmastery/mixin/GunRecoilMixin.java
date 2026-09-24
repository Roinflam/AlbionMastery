package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.tacz.guns.client.event.CameraSetupEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 后座按特化缩放。
 *
 * <p>补的是什么：TACZ 每次开枪在客户端 {@code CameraSetupEvent.initialCameraRecoil} 里生成镜头后座曲线，
 * 俯仰和偏航各调一次 {@code GunRecoil.genXxxSplineFunction(修正系数)}，那个系数就是后座的总倍率
 * （配件、趴下都往这里乘）。把它再乘上特化的后座倍率就行，和 KuvaLich 的做法一样。
 *
 * <p>只对本地玩家生效（这个事件本来就只为本机镜头触发）。纯客户端类。
 */
@Mixin(value = CameraSetupEvent.class, remap = false)
public abstract class GunRecoilMixin {

    /**
     * 俯仰后座。
     *
     * @param modifier 原始修正系数
     * @return 乘上特化后座倍率
     */
    @ModifyArg(
            method = "initialCameraRecoil",
            at = @At(value = "INVOKE", target = "Lcom/tacz/guns/resource/pojo/data/gun/GunRecoil;genPitchSplineFunction(F)"
                    + "Lorg/apache/commons/math3/analysis/polynomials/PolynomialSplineFunction;"),
            index = 0,
            require = 0
    )
    private static float albionmastery$pitchRecoil(float modifier) {
        return (float) (modifier * albionmastery$recoilFactor());
    }

    /**
     * 偏航后座。
     *
     * @param modifier 原始修正系数
     * @return 乘上特化后座倍率
     */
    @ModifyArg(
            method = "initialCameraRecoil",
            at = @At(value = "INVOKE", target = "Lcom/tacz/guns/resource/pojo/data/gun/GunRecoil;genYawSplineFunction(F)"
                    + "Lorg/apache/commons/math3/analysis/polynomials/PolynomialSplineFunction;"),
            index = 0,
            require = 0
    )
    private static float albionmastery$yawRecoil(float modifier) {
        return (float) (modifier * albionmastery$recoilFactor());
    }

    private static double albionmastery$recoilFactor() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 1.0D;
        }
        return SpecializationEffects.forStack(player, player.getMainHandItem()).recoil();
    }
}
