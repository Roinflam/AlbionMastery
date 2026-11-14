package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 铁魔法「够不够蓝放这个法术」的判断，按特化和专精折扣之后的实际价格算。
 *
 * <p>真正扣蓝时我们在施法事件里改了价格，可这里的判断一直按原价：省蓝的特化在「够付折后价、不够付原价」时
 * 被拒绝施法，加价的特化在「够原价、不够加价」时照样能放、蓝扣到 0 为止，多出来的代价就被免掉了。
 */
@Mixin(value = AbstractSpell.class, remap = false)
public abstract class SpellManaCheckMixin {

    @ModifyExpressionValue(
            method = "canBeCastedBy(ILio/redspace/ironsspellbooks/api/spells/CastSource;"
                    + "Lio/redspace/ironsspellbooks/api/magic/MagicData;Lnet/minecraft/world/entity/player/Player;)"
                    + "Lio/redspace/ironsspellbooks/api/spells/CastResult;",
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;getManaCost(I)I"),
            require = 0, expect = 1
    )
    private int albionmastery$checkCost(int original, int spellLevel, CastSource source, MagicData magicData,
                                        Player player) {
        return IronsSpellsSupport.effectiveManaCost(player, (AbstractSpell) (Object) this, original);
    }
}
