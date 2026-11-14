package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.event.BowCombatHandler;
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 箭速特化不顺带放大伤害：命中时把箭速倍率从伤害里除回去。
 *
 * <p>补的是什么：原版箭的伤害 = 飞行速度 × 基础伤害，箭速特化把箭速乘上去，伤害也跟着被乘了。
 * 以前是射出时把基础伤害按同一倍率除掉，但力量附魔现在不在射出时加进基础伤害，而是命中时
 * 由 {@code EnchantmentHelper.modifyDamage} 加，射出时除不到它，力量那截会被箭速放大。
 * 所以射出时只把倍率记在箭上（{@link BowCombatHandler#speedScaleOf}），到这里再除。
 *
 * <p>改的是 {@code onHitEntity} 里 {@code Mth.ceil(Mth.clamp(速度 × 伤害, 0, 上限))} 那次 {@code clamp} 的第一个实参，
 * 整个类只有这一处 {@code clamp}。到这一步力量已经加进去了，除完之后箭速特化前后的伤害比例和以前一样；
 * 暴击的随机加成在下面按除过的数照原版算。不在受伤阶段再除：那样传给 {@code hurt} 的数是放大过的，
 * 无敌帧比较、盾牌掉的耐久、别的模组看到的伤害都会偏大。
 *
 * <p>没记倍率的箭（不是玩家射的、没有箭速特化的）原样返回。三叉戟自己覆写了 {@code onHitEntity} 且不调父类，
 * 不经过这里，它本来也不用补。
 *
 * <p>命中数上下限都是 1：多打上会把倍率除两遍。
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowDamageMixin {

    /**
     * 按箭速倍率除回伤害。
     *
     * @param damage 速度 × 伤害（力量已加）
     * @return 除过箭速倍率的值
     */
    @ModifyArg(method = "onHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(DDD)D"),
            index = 0, expect = 1, allow = 1)
    private double albionmastery$undoSpeedScaleDamage(double damage) {
        double scale = BowCombatHandler.speedScaleOf((AbstractArrow) (Object) this);
        return scale == 1.0D ? damage : damage / scale;
    }
}
