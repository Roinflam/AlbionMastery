package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.event.damage.DamageStages;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家的受伤、伤害阶段：{@link Player} 覆写了 {@code actuallyHurt} 且不调 super，两个阶段要在它自己的方法里发
 * （说明见 {@link LivingEntityDamageStagesMixin} 和 {@link DamageStages}）。
 *
 * <p>伤害阶段的 {@code target} 写 {@code Player;setAbsorptionAmount}：{@code Player} 没有覆写这个方法，
 * 但编译器按接收者的静态类型写所有者，字节码里就是 {@code Player.setAbsorptionAmount}，写 {@code LivingEntity} 匹配不到。
 *
 * <p>写成 {@code extends LivingEntity}，直接用 NeoForge 补进 {@code LivingEntity} 的受保护字段 {@code damageContainers}。
 */
@Mixin(Player.class)
public abstract class PlayerDamageStagesMixin extends LivingEntity {

    protected PlayerDamageStagesMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    @Inject(method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;F)V",
            at = @At("HEAD"), expect = 1, allow = 1)
    private void albionmastery$hurtStage(DamageSource source, float amount, CallbackInfo ci) {
        DamageStages.hurt(this, source, this.damageContainers);
    }

    @Inject(method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;F)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;setAbsorptionAmount(F)V",
                    shift = At.Shift.AFTER),
            expect = 1, allow = 1)
    private void albionmastery$damageStage(DamageSource source, float amount, CallbackInfo ci) {
        DamageStages.damage(this, source, this.damageContainers);
    }
}
