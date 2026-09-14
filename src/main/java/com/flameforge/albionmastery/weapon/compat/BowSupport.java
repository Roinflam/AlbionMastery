package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * 通用弓弩：所有继承原版 {@code BowItem} / {@code CrossbowItem} 的物品，不管来自哪个模组。
 *
 * <p>来源「弓弩」，大类两个：弓、弩；单件 = 物品。和剑一样是兜底来源，排在解析器最后，只用原版类。
 *
 * <p>箭是延迟命中的：射出去的时候拿的是弓，命中的时候手里可能已经换了东西。
 * 所以箭一出生就把「是哪把弓射的」记进箭的持久数据里（{@link #tagArrow}），
 * 命中时从箭上读（{@link #fromArrow}），不看手里。投出去的三叉戟也是 {@code AbstractArrow}，
 * 走同一套，只是记的来源是「近战武器」。
 */
public final class BowSupport {

    /** 弓的大类。 */
    public static final String TYPE_BOW = "bow";

    /** 弩的大类。 */
    public static final String TYPE_CROSSBOW = "crossbow";

    /** 箭的持久数据里记武器用的键。 */
    private static final String TAG_WEAPON = "albionmastery_weapon";

    private BowSupport() {
    }

    /**
     * 这件物品是不是弓或弩。
     *
     * @param stack 物品
     * @return 是返回 true
     */
    public static boolean isBowOrCrossbow(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem);
    }

    /**
     * 弓弩 → 武器。
     *
     * @param stack 物品
     * @return 武器；不是弓弩或没注册名返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack) {
        if (!isBowOrCrossbow(stack)) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        String type = stack.getItem() instanceof CrossbowItem ? TYPE_CROSSBOW : TYPE_BOW;
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_BOW, id, type);
    }

    /**
     * 把射出这支箭的武器记到箭上。
     *
     * @param arrow  箭
     * @param weapon 武器
     */
    public static void tagArrow(AbstractArrow arrow, ResolvedWeapon weapon) {
        CompoundTag tag = new CompoundTag();
        tag.putString("provider", weapon.providerId());
        tag.putString("id", weapon.weaponId().toString());
        tag.putString("type", weapon.type());
        arrow.getPersistentData().put(TAG_WEAPON, tag);
    }

    /**
     * 从箭上读回射它的武器。
     *
     * @param arrow 箭
     * @return 武器；没记过返回 null
     */
    @Nullable
    public static ResolvedWeapon fromArrow(AbstractArrow arrow) {
        CompoundTag data = arrow.getPersistentData();
        if (!data.contains(TAG_WEAPON)) {
            return null;
        }
        CompoundTag tag = data.getCompound(TAG_WEAPON);
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("id"));
        String type = tag.getString("type");
        String provider = tag.getString("provider");
        if (id == null || type.isEmpty() || provider.isEmpty()) {
            return null;
        }
        return ResolvedWeapon.of(provider, id, type);
    }
}
