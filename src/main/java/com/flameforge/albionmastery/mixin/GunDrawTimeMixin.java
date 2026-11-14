package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.entity.shooter.LivingEntityDrawGun;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 掏枪（切枪）速度按特化缩放。
 *
 * <p>补的是什么：切到枪之后多久能开火，由服务端 {@code LivingEntityDrawGun.getDrawCoolDown()} 算
 * 「枪械数据里的掏枪时长 − 切枪后已过去的时间」，结果同步给客户端（{@code getSynDrawCoolDown}），
 * 客户端开火前查的也是这个同步值。所以只改服务端这一处，两端的判断就一起变了。
 * 计算写在 {@code Optional.map} 的 lambda 里，按合成方法名定位；TACZ 改了实现就是这一项不生效（{@code require = 0}）。
 *
 * <p>收枪（上一把枪放下去）的时间不动：那段是旧武器的事，和手上这把枪练得怎么样无关。
 */
@Mixin(value = LivingEntityDrawGun.class, remap = false)
public abstract class GunDrawTimeMixin {

    @Shadow
    @Final
    private LivingEntity shooter;

    /**
     * 掏枪时长（秒）乘特化倍率。
     *
     * @param drawTime 枪械数据里的掏枪时长
     * @return 缩放后的掏枪时长
     */
    @ModifyExpressionValue(
            method = "lambda$getDrawCoolDown$0(Lcom/tacz/guns/resource/index/CommonGunIndex;)Ljava/lang/Long;",
            at = @At(value = "INVOKE", target = "Lcom/tacz/guns/resource/pojo/data/gun/GunData;getDrawTime()F"),
            require = 0, expect = 1
    )
    private float albionmastery$drawTime(float drawTime) {
        double factor = SpecializationEffects.forStack(this.shooter, this.shooter.getMainHandItem()).drawTime();
        return factor == 1.0D ? drawTime : (float) (drawTime * factor);
    }
}
