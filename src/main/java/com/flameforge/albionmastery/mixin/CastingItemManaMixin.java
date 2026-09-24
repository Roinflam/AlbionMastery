package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.item.CastingItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 法杖右键施法前的「够不够蓝」判断按实际价格算（见 {@link SpellManaCheckMixin}）。
 *
 * <p>这个方法覆盖的是原版物品的右键方法，开发环境里叫 {@code use}，正式环境里是混淆名 {@code m_7203_}，
 * 两个都写上，哪个在就注入哪个。
 */
@Mixin(value = CastingItem.class, remap = false)
public abstract class CastingItemManaMixin {

    @WrapOperation(
            method = {"use", "m_7203_"},
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"),
            require = 0
    )
    private int albionmastery$useCost(AbstractSpell spell, int level, Operation<Integer> original,
                                      Level world, Player player, InteractionHand hand) {
        return IronsSpellsSupport.effectiveManaCost(player, spell, original.call(spell, level));
    }
}
