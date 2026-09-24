package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import com.flameforge.albionmastery.weapon.compat.SpellRangeProtection;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把专精加成乘进铁魔法的「法术强度」，但在范围计算上下文中跳过。
 *
 * <p>范围 Mixin 只在计算半径、距离等尺寸时进入保护上下文；本类检查该标记，
 * 防止伤害倍率被用于扩张实体搜索范围、方块遍历和粒子尺寸。伤害与治疗计算仍按原方式加成。
 *
 * <p>补的是什么：铁魔法的法术数值有两个源头，两个都挂：
 * <ul>
 *     <li>{@code getSpellPower(level, caster)}：绝大多数法术的伤害、治疗量、控制时长、
 *         书和卷轴上显示的数字都从它算；</li>
 *     <li>{@code getEntityPowerMultiplier(caster)}：另一批法术（潜声触手、冰棺、暴风雪、雪球、
 *         充能……）自己按等级算基础值，再乘这个「施法者强度倍率」。</li>
 * </ul>
 * 两个方法各自独立算（前者没有调后者），所以两处都乘不会叠成双倍——一个法术的同一个数只会走其中一条路。
 * 在返回之前乘上专精倍率，这些东西就一起跟着涨，不用一个个法术去接。
 *
 * <p>为什么不只在伤害事件里乘：那样治疗和控制时长吃不到加成，提示框上的数字也不会变，
 * 玩家看着「伤害 12」实际打出来 16，对不上。
 *
 * <p>只对玩家施法者生效，怪物施法不动。目标是铁魔法的类，不参与 SRG 映射，{@code remap = false}；
 * 没装铁魔法时由 {@code AlbionMixinPlugin} 直接跳过这个 Mixin，连目标类都不会去找。
 */
@Mixin(value = AbstractSpell.class, remap = false)
public abstract class AbstractSpellPowerMixin {

    /**
     * 在法术强度算完之后乘上专精倍率。
     *
     * @param spellLevel   法术等级
     * @param caster       施法者，可能为 null
     * @param callbackInfo 携带原始强度的回调
     */
    @Inject(method = "getSpellPower", at = @At("RETURN"), cancellable = true, require = 0)
    private void albionmastery$applyMastery(int spellLevel, Entity caster,
                                            CallbackInfoReturnable<Float> callbackInfo) {
        albionmastery$scale(caster, callbackInfo);
    }

    /**
     * 施法者强度倍率算完之后乘上专精倍率。
     *
     * @param caster       施法者，可能为 null
     * @param callbackInfo 携带原始倍率的回调
     */
    @Inject(method = "getEntityPowerMultiplier", at = @At("RETURN"), cancellable = true, require = 0)
    private void albionmastery$applyMasteryToEntityMultiplier(LivingEntity caster,
                                                              CallbackInfoReturnable<Float> callbackInfo) {
        albionmastery$scale(caster, callbackInfo);
    }

    /**
     * 两处共用：施法者是玩家就把返回值乘上专精倍率。
     *
     * @param caster       施法者
     * @param callbackInfo 回调
     */
    private void albionmastery$scale(Entity caster, CallbackInfoReturnable<Float> callbackInfo) {
        if (SpellRangeProtection.isSuppressed() || !(caster instanceof Player player)) {
            return;
        }
        double multiplier = IronsSpellsSupport.powerMultiplier(player, (AbstractSpell) (Object) this);
        if (multiplier != 1.0D) {
            callbackInfo.setReturnValue((float) (callbackInfo.getReturnValueF() * multiplier));
        }
    }
}
