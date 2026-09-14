package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * 通用近战武器：所有继承原版 {@code SwordItem} / {@code AxeItem} / {@code TridentItem} 的物品，
 * 不管来自哪个模组。来源「近战武器」，大类三个：剑、斧、三叉戟；单件 = 物品。
 *
 * <p>兜底来源：解析器里排在最后，前面几个来源（近战附属、拔刀剑……）认走了的物品不会落到这里。
 * 拔刀剑的刀也是 {@code SwordItem}，装了拔刀剑时它先认走，不会重复。只用原版类。
 *
 * <p>1.20.1 原版近战就这三类，重锤是 1.21 才有的。三叉戟投掷出去走弓弩那边的「投射物」路径。
 */
public final class VanillaMeleeSupport {

    /** 剑。 */
    public static final String TYPE_SWORD = "sword";

    /** 斧。 */
    public static final String TYPE_AXE = "axe";

    /** 三叉戟。 */
    public static final String TYPE_TRIDENT = "trident";

    private VanillaMeleeSupport() {
    }

    /**
     * 这件物品是不是原版近战武器（剑 / 斧 / 三叉戟）。
     *
     * @param stack 物品
     * @return 是返回 true
     */
    public static boolean isVanillaMelee(ItemStack stack) {
        return typeOf(stack) != null;
    }

    /**
     * 物品 → 大类。
     *
     * @param stack 物品
     * @return 剑 / 斧 / 三叉戟；都不是返回 null
     */
    @Nullable
    public static String typeOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() instanceof SwordItem) {
            return TYPE_SWORD;
        }
        if (stack.getItem() instanceof AxeItem) {
            return TYPE_AXE;
        }
        if (stack.getItem() instanceof TridentItem) {
            return TYPE_TRIDENT;
        }
        return null;
    }

    /**
     * 原版近战武器 → 武器。
     *
     * @param stack 物品
     * @return 武器；不是或没注册名返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack) {
        String type = typeOf(stack);
        if (type == null) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_VANILLA_MELEE, id, type);
    }
}
