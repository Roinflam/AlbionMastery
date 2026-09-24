package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.player.ServerPlayerEvents;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 右键施法物品时的「够不够蓝」判断按实际价格算（见 {@link SpellManaCheckMixin}）。
 */
@Mixin(value = ServerPlayerEvents.class, remap = false)
public abstract class ServerPlayerEventsManaMixin {

    @WrapOperation(
            method = "onUseItem",
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"),
            require = 0
    )
    private static int albionmastery$useItemCost(AbstractSpell spell, int level, Operation<Integer> original,
                                                 PlayerInteractEvent.RightClickItem event) {
        return IronsSpellsSupport.effectiveManaCost(event.getEntity(), spell, original.call(spell, level));
    }
}
