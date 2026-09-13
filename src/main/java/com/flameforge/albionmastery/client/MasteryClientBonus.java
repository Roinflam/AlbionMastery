package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 客户端加成查询入口。
 *
 * <p>Mixin、提示文本、属性改写都需要「这件物品当前享有多少专精加成」，
 * 集中在这里一份实现，避免几处算法漂移。
 */
public final class MasteryClientBonus {

    private MasteryClientBonus() {
    }

    /**
     * 查询某件物品当前的伤害倍率。
     *
     * @param stack 物品堆
     * @return 伤害倍率；不是可专精武器时返回 1.0
     */
    public static double multiplierFor(ItemStack stack) {
        MasteryHelper.Bonus bonus = bonusFor(stack);
        return bonus == null ? 1.0D : bonus.multiplier();
    }

    /**
     * 查询某件物品当前的加成明细。
     *
     * @param stack 物品堆
     * @return 加成明细；不是可专精武器时返回 null
     */
    @Nullable
    public static MasteryHelper.Bonus bonusFor(ItemStack stack) {
        ResolvedWeapon weapon = resolve(stack);
        if (weapon == null) {
            return null;
        }
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        return MasteryHelper.computeBonus(ClientMasteryCache.data(), weapon, snapshot);
    }

    /**
     * 解析物品堆的专精定位。
     *
     * @param stack 物品堆
     * @return 定位结果；不是可专精武器时返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        // 客户端一律按服务端下发的快照解析，保证类型键和服务端算出来的完全一致
        return WeaponResolver.resolve(stack, ClientMasteryCache.snapshot().splitMeleeByPack);
    }
}
