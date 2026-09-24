package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import com.flameforge.albionmastery.weapon.compat.VanillaMeleeSupport;
import net.minecraft.resources.ResourceKey;

/**
 * 通用近战武器的客户端来源：遍历物品注册表，剑一类、斧一类、三叉戟一类。被别的来源认走的不重复列。
 */
public final class VanillaMeleeProvider implements IWeaponProvider {

    public static final VanillaMeleeProvider INSTANCE = new VanillaMeleeProvider();

    private VanillaMeleeProvider() {
    }

    @Override
    public String providerId() {
        return WeaponKeys.PROVIDER_VANILLA_MELEE;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Component sourceName() {
        return Component.translatable("albionmastery.source.vanilla_melee");
    }

    @Override
    public void collect(List<WeaponEntry> out) {
        int sort = 0;
        // 直接遍历注册表本身：以前先 stream().map().toList() 复制一份一两万条的临时表，
        // 那份表唯一的用处就是被遍历一次，纯属白扔内存
        for (Map.Entry<ResourceKey<Item>, Item> entry : ForgeRegistries.ITEMS.getEntries()) {
            Item item = entry.getValue();
            ItemStack stack = new ItemStack(item);
            // 判定统一交给 VanillaMeleeSupport：它认标签，模组自己写基类的武器也能收到
            if (!VanillaMeleeSupport.isVanillaMelee(stack)) {
                continue;
            }
            // 解析器排在前面的来源认走了就跳过（拔刀剑的刀就是 SwordItem，但归拔刀剑）
            ResolvedWeapon resolved = WeaponResolver.resolve(stack, false);
            if (resolved == null || !WeaponKeys.PROVIDER_VANILLA_MELEE.equals(resolved.providerId())) {
                continue;
            }
            ResourceLocation id = entry.getKey().location();
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
