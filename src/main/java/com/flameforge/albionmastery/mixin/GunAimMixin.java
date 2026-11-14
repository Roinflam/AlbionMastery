package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.entity.shooter.LivingEntityAim;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 开镜速度与冲刺后恢复射击的速度按特化缩放（逻辑端）。
 *
 * <p>补的是什么：
 * <ul>
 *     <li>开镜：{@code tickAimingProgress} 每 tick 按「已过时间 / 开镜耗时」推进瞄准进度，
 *         开镜耗时取自配件缓存后再做一次 {@code Math.max(0, aimTime)}。在这个 max 的结果上乘特化倍率，
 *         配件的加成已经在里面了。瞄准进度决定散布用哪一档（进度满 1 才算「开镜」）和移速，
 *         所以服务端这份必须改，只改客户端的话画面开镜了、子弹却还按腰射散布飞。</li>
 *     <li>冲刺：{@code tickSprint} 里奔跑时累加 {@code sprintTimeS}、停下后按真实时间往回扣，
 *         扣到 0 才允许开枪；累加的上限就是枪械数据里的冲刺时间。把这个上限乘倍率，
 *         停跑后要等的时间就跟着缩放。这段在 lambda 里，按合成方法名定位。</li>
 * </ul>
 *
 * <p>客户端画面上的开镜进度由 {@code LocalAimMixin} 用同一个倍率另改一份，两边才对得上。
 */
@Mixin(value = LivingEntityAim.class, remap = false)
public abstract class GunAimMixin {

    @Shadow
    @Final
    private LivingEntity shooter;

    /**
     * 开镜耗时（秒）乘特化倍率。
     *
     * @param aimTime 配件修正后的开镜耗时
     * @return 缩放后的开镜耗时
     */
    @ModifyExpressionValue(
            method = "tickAimingProgress()V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F"),
            require = 0, expect = 1
    )
    private float albionmastery$adsTime(float aimTime) {
        double factor = SpecializationEffects.forStack(this.shooter, this.shooter.getMainHandItem()).adsTime();
        return factor == 1.0D ? aimTime : (float) (aimTime * factor);
    }

    /**
     * 冲刺后恢复射击的耗时（秒）乘特化倍率。
     *
     * @param sprintTime 枪械数据里的冲刺时间
     * @return 缩放后的冲刺时间
     */
    @ModifyExpressionValue(
            method = "lambda$tickSprint$1(Lcom/tacz/guns/resource/index/CommonGunIndex;)V",
            at = @At(value = "INVOKE", target = "Lcom/tacz/guns/resource/pojo/data/gun/GunData;getSprintTime()F"),
            require = 0, expect = 1
    )
    private float albionmastery$sprintTime(float sprintTime) {
        double factor = SpecializationEffects.forStack(this.shooter, this.shooter.getMainHandItem()).sprintTime();
        return factor == 1.0D ? sprintTime : (float) (sprintTime * factor);
    }
}
