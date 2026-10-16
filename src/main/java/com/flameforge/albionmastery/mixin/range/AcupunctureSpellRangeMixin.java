package com.flameforge.albionmastery.mixin.range;

import com.flameforge.albionmastery.weapon.compat.SpellRangeProtection;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 隔离 AcupunctureSpell（针刺术）的飞针数量计算，防止专精强度把飞针数量放大成几倍。
 *
 * <p>数量是「(4 + 等级) × 法术强度」，只替换 getCount 里这一次强度调用；伤害照常吃专精加成。
 * 使用字符串目标与 Pseudo，未安装目标模组时由 AlbionMixinPlugin 跳过；无需编译依赖附属。
 * 所有目标为模组的可读方法名，不使用混淆名称。
 */
@Pseudo
@Mixin(targets = "io.redspace.ironsspellbooks.spells.blood.AcupunctureSpell", remap = false)
public abstract class AcupunctureSpellRangeMixin {

    /**
     * 用原始强度计算飞针数量。
     *
     * @param spell 当前法术实例，由 Mixin 校验并传入
     * @param spellLevel 法术等级
     * @param caster 施法者，可能为空
     * @return 不受专精额外放大的强度值
     */
    @Redirect(method = {"getCount"},
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/spells/blood/AcupunctureSpell;getSpellPower(ILnet/minecraft/world/entity/Entity;)F"),
            require = 1, expect = 1)
    private float albionmastery$countPower(@Coerce Object spell, int spellLevel, Entity caster) {
        return SpellRangeProtection.spellPower((AbstractSpell) spell, spellLevel, caster);
    }
}
