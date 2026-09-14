package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.BowSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;

/**
 * 通用弓弩的客户端来源：遍历物品注册表，弓一类、弩一类。被别的来源认走的不重复列。
 */
public final class BowProvider implements IWeaponProvider {

    public static final BowProvider INSTANCE = new BowProvider();

    private BowProvider() {
    }

    @Override
    public String providerId() {
        return WeaponKeys.PROVIDER_BOW;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Component sourceName() {
        return Component.translatable("albionmastery.source.bow");
    }

    @Override
    public void collect(List<WeaponEntry> out) {
        int sort = 0;
        for (Map.Entry<ResourceLocation, Item> entry : ForgeRegistries.ITEMS.getEntries()
                .stream().map(e -> Map.entry(e.getKey().location(), e.getValue())).toList()) {
            Item item = entry.getValue();
            if (!(item instanceof BowItem) && !(item instanceof CrossbowItem)) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            ResolvedWeapon resolved = WeaponResolver.resolve(stack, false);
            if (resolved == null || !WeaponKeys.PROVIDER_BOW.equals(resolved.providerId())) {
                continue;
            }
            ResourceLocation id = entry.getKey();
            out.add(new WeaponEntry(
                    resolved.weaponKey(),
                    resolved.typeKey(),
                    resolved.overallKey(),
                    id,
                    id.getNamespace(),
                    stack.getHoverName(),
                    resolved.type(),
                    sort++,
                    stack));
        }
    }

    @Override
    public Component typeName(String type) {
        return Component.translatable("albionmastery.type." + providerId() + "." + type);
    }
}
