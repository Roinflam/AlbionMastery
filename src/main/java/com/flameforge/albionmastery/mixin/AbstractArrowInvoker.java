package com.flameforge.albionmastery.mixin;

import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 调箭的私有方法 {@code setPierceLevel(byte)}。
 *
 * <p>穿透特化要在箭原有的穿透等级上往上加，原版把这个设值方法改成了私有（读值的 {@code getPierceLevel()} 还是公开的），
 * 只能走调用器。用法：{@code ((AbstractArrowInvoker) arrow).albionmastery$setPierceLevel(level)}。
 */
@Mixin(AbstractArrow.class)
public interface AbstractArrowInvoker {

    /**
     * 设穿透等级，即「还能再穿几个」。
     *
     * @param level 穿透等级
     */
    @Invoker("setPierceLevel")
    void albionmastery$setPierceLevel(byte level);
}
