package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 持续引导的法术每隔一段时间看一次「还够不够蓝继续」，同样按实际价格判断（见 {@link SpellManaCheckMixin}）。
 */
@Mixin(value = MagicManager.class, remap = false)
public abstract class MagicManagerManaMixin {

    @WrapOperation(
            method = "lambda$tick$0",
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"),
            require = 0
    )
    private int albionmastery$channelCost(AbstractSpell spell, int level, Operation<Integer> original,
                                          boolean flag, Player player) {
        return IronsSpellsSupport.effectiveManaCost(player, spell, original.call(spell, level));
    }
}
