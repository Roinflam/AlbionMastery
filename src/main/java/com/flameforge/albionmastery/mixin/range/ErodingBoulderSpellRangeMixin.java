package com.flameforge.albionmastery.mixin.range;

import com.flameforge.albionmastery.weapon.compat.SpellRangeProtection;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

// 范围按没乘专精强度的法强算，免得专精把范围越撑越大。
@Pseudo
@Mixin(targets = "com.gametechbc.gtbcs_geomancy_plus.spells.geo.ErodingBoulderSpell", remap = false)
public abstract class ErodingBoulderSpellRangeMixin {

    @Redirect(method = {"getRadius(ILnet/minecraft/world/entity/LivingEntity;)I"},
            at = @At(value = "INVOKE", target = "Lcom/gametechbc/gtbcs_geomancy_plus/spells/geo/ErodingBoulderSpell;getSpellPower(ILnet/minecraft/world/entity/Entity;)F"),
            require = 0, expect = 1)
    private float albionmastery$rangePower(@Coerce Object spell, int spellLevel, Entity caster) {
        return SpellRangeProtection.spellPower((AbstractSpell) spell, spellLevel, caster);
    }
}
