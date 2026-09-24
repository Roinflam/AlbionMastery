package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.util.TooltipsUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 法术书、卷轴提示里的法力消耗和冷却秒数按实际值显示。
 */
@Mixin(value = TooltipsUtils.class, remap = false)
public abstract class TooltipsManaMixin {

    @WrapOperation(
            method = "formatActiveSpellTooltip",
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"),
            require = 0
    )
    private static int albionmastery$activeSpellCost(AbstractSpell spell, int level, Operation<Integer> original,
                                                     ItemStack stack, SpellData spellData, CastSource castSource,
                                                     LocalPlayer player) {
        return IronsSpellsSupport.effectiveManaCost(player, spell, original.call(spell, level));
    }

    /**
     * 法术书提示里的冷却。
     *
     * @param spell       法术
     * @param player      玩家
     * @param source      施法来源
     * @param original    原方法
     * @param stack       物品
     * @param spellData   法术数据
     * @param castSource  施法来源
     * @param localPlayer 本地玩家
     * @return 实际冷却
     */
    @WrapOperation(
            method = "formatActiveSpellTooltip",
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/capabilities/magic/MagicManager;getEffectiveSpellCooldown(Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;Lnet/minecraft/world/entity/player/Player;Lio/redspace/ironsspellbooks/api/spells/CastSource;)I"),
            require = 0
    )
    private static int albionmastery$activeSpellCooldown(AbstractSpell spell, Player player, CastSource source,
                                                         Operation<Integer> original, ItemStack stack,
                                                         SpellData spellData, CastSource castSource,
                                                         LocalPlayer localPlayer) {
        return IronsSpellsSupport.effectiveCooldown(player, spell,
                original.call(spell, player, source));
    }

    /**
     * 卷轴提示里的冷却。
     *
     * @param spell    法术
     * @param player   玩家
     * @param source   施法来源
     * @param original 原方法
     * @param stack    物品
     * @param holder   拿着卷轴的玩家
     * @return 实际冷却
     */
    @WrapOperation(
            method = "formatScrollTooltip",
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/capabilities/magic/MagicManager;getEffectiveSpellCooldown(Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;Lnet/minecraft/world/entity/player/Player;Lio/redspace/ironsspellbooks/api/spells/CastSource;)I"),
            require = 0
    )
    private static int albionmastery$scrollCooldown(AbstractSpell spell, Player player, CastSource source,
                                                    Operation<Integer> original, ItemStack stack, Player holder) {
        return IronsSpellsSupport.effectiveCooldown(player, spell,
                original.call(spell, player, source));
    }

    @WrapOperation(
            method = "formatScrollTooltip",
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"),
            require = 0
    )
    private static int albionmastery$scrollCost(AbstractSpell spell, int level, Operation<Integer> original,
                                                ItemStack stack, Player player) {
        return IronsSpellsSupport.effectiveManaCost(player, spell, original.call(spell, level));
    }
}
