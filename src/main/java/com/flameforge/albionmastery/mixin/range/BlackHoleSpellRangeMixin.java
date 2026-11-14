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
 * 隔离 BlackHoleSpell 的范围或移动距离计算，防止专精强度扩大范围造成额外负载。
 *
 * <p>只替换 getRadius 中已经核对的强度调用；伤害、治疗、时长的调用保持原样。
 * 使用字符串目标与 Pseudo，未安装目标模组时由 AlbionMixinPlugin 跳过；无需编译依赖附属。
 * 所有目标为模组的可读方法名，不使用混淆名称。
 */
@Pseudo
@Mixin(targets = "io.redspace.ironsspellbooks.spells.ender.BlackHoleSpell", remap = false)
public abstract class BlackHoleSpellRangeMixin {

    /**
     * 用原始强度计算空间尺寸；范围保护关闭时恢复原逻辑。
     *
     * @param spell 当前法术实例，由 Mixin 校验并传入
     * @param spellLevel 法术等级
     * @param caster 施法者，可能为空
     * @return 不受专精额外放大的强度值
     */
    @Redirect(method = {"getRadius(ILnet/minecraft/world/entity/LivingEntity;)F"},
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/spells/ender/BlackHoleSpell;getSpellPower(ILnet/minecraft/world/entity/Entity;)F"),
            require = 0, expect = 1)
    private float albionmastery$rangePower(@Coerce Object spell, int spellLevel, Entity caster) {
        return SpellRangeProtection.spellPower((AbstractSpell) spell, spellLevel, caster);
    }
}
