package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.compat.SweetMagicSupport;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import sweetmagic.init.item.magic.BaseMagicItem;

/**
 * 魔法卷轴的法力消耗和冷却按特化缩放。
 *
 * <p>挂在卷轴自己身上：{@code getUseMF} 是这张魔法的基础法力价，
 * {@code getMaxRecastTime} 是它的基础冷却，模组后面的各种减耗（附魔、药水、装备）
 * 都是在这两个数的基础上算的，所以在这里乘完，「够不够放」的判断和实际扣除、
 * 以及真正挂上去的冷却全都一致。
 *
 * <p>只在施法窗口开着时生效（见 {@code SweetMagicCastMixin}）：那一刻才知道是谁在放。
 * 窗口没开时（比如翻看物品提示）原样返回，显示的是这张卷轴本身的数值。
 *
 * <p>目标是甜蜜魔法的类，{@code remap = false}；没装这个模组时整个 Mixin 不应用。
 */
@Mixin(value = BaseMagicItem.class, remap = false)
public abstract class SweetMagicCostMixin {

    /**
     * 基础法力消耗。
     *
     * @param original 原价
     * @return 按特化缩放后的价格
     */
    @ModifyReturnValue(method = "getUseMF", at = @At("RETURN"), require = 0)
    private int albionmastery$useMF(int original) {
        double factor = albionmastery$values().manaCost();
        if (original <= 0 || factor == 1.0D) {
            return original;
        }
        return Math.max(0, (int) Math.round(original * factor));
    }

    /**
     * 基础冷却。
     *
     * @param original 原冷却
     * @return 按特化缩放后的冷却
     */
    @ModifyReturnValue(method = "getMaxRecastTime", at = @At("RETURN"), require = 0)
    private int albionmastery$maxRecastTime(int original) {
        double factor = albionmastery$values().spellCooldown();
        if (original <= 0 || factor == 1.0D) {
            return original;
        }
        return Math.max(0, (int) Math.round(original * factor));
    }

    /**
     * 正在施法的这个人、这张卷轴现在生效的特化数值。
     *
     * @return 数值；窗口没开或者认不出卷轴时是中性值
     */
    private SpecializationValues albionmastery$values() {
        Player caster = SweetMagicSupport.castingPlayer();
        if (caster == null) {
            return SpecializationValues.NEUTRAL;
        }
        ResolvedWeapon weapon = SweetMagicSupport.resolve(new ItemStack((Item) (Object) this));
        return weapon == null ? SpecializationValues.NEUTRAL
                : SpecializationEffects.forWeapon(caster, weapon);
    }
}
