package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.gui.overlays.SpellWheelOverlay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 法术轮盘上显示的法力消耗和冷却按实际值显示。
 */
@Mixin(value = SpellWheelOverlay.class, remap = false)
public abstract class SpellWheelManaMixin {

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"),
            require = 0
    )
    private int albionmastery$wheelCost(AbstractSpell spell, int level, Operation<Integer> original) {
        return IronsSpellsSupport.effectiveManaCost(Minecraft.getInstance().player, spell,
                original.call(spell, level));
    }

    /**
     * 轮盘上的冷却。
     *
     * @param spell    法术
     * @param player   玩家
     * @param source   施法来源
     * @param original 原方法
     * @return 实际冷却
     */
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/capabilities/magic/MagicManager;getEffectiveSpellCooldown(Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;Lnet/minecraft/world/entity/player/Player;Lio/redspace/ironsspellbooks/api/spells/CastSource;)I"),
            require = 0
    )
    private int albionmastery$wheelCooldown(AbstractSpell spell, net.minecraft.world.entity.player.Player player,
                                            io.redspace.ironsspellbooks.api.spells.CastSource source,
                                            Operation<Integer> original) {
        return IronsSpellsSupport.effectiveCooldown(player, spell, original.call(spell, player, source));
    }
}
