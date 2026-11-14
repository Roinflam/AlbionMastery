package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.event.damage.DamageStages;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Stack;

/**
 * 在 {@link LivingEntity#actuallyHurt} 里原位发本模组的受伤、伤害两个阶段（玩家覆写了这个方法且不调 super，
 * 另见 {@link PlayerDamageStagesMixin}；为什么这么做、两个阶段里做什么见 {@link DamageStages}）。
 *
 * <ul>
 *     <li><b>受伤阶段</b>：{@code actuallyHurt} 开头。盾、冰冻、无敌帧都已经算过，护甲还没算，
 *         和 1.20.1 {@code actuallyHurt} 开头的 {@code onLivingHurt} 同位置。</li>
 *     <li><b>伤害阶段</b>：{@code setAbsorptionAmount} 调用之后（方法里只有这一处）。吸收已经扣完、还没扣血，
 *         和 1.20.1「扣完吸收再发 {@code onLivingDamage}」一致；原版紧接着读的容器值就是扣血量。</li>
 * </ul>
 *
 * <p>回调里的 {@code amount} 不用，数一律从 NeoForge 补进来的伤害容器栈 {@code damageContainers} 读写。
 * 两个注入器各命中一次；多打上会让监听器的倍率乘两遍，所以命中数上下限都卡死。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageStagesMixin {

    @Shadow
    protected Stack<DamageContainer> damageContainers;

    @Inject(method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;F)V",
            at = @At("HEAD"), expect = 1, allow = 1)
    private void albionmastery$hurtStage(DamageSource source, float amount, CallbackInfo ci) {
        DamageStages.hurt((LivingEntity) (Object) this, source, this.damageContainers);
    }

    @Inject(method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;F)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;setAbsorptionAmount(F)V",
                    shift = At.Shift.AFTER),
            expect = 1, allow = 1)
    private void albionmastery$damageStage(DamageSource source, float amount, CallbackInfo ci) {
        DamageStages.damage((LivingEntity) (Object) this, source, this.damageContainers);
    }
}
