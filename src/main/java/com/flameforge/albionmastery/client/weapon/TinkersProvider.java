package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.TinkersSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;

/**
 * 匠魂近战武器的客户端来源：中圈按武器形态分（剑、匕首、巨刃、斧、锤、长柄），不按材料。
 */
public final class TinkersProvider implements IWeaponProvider {

    public static final TinkersProvider INSTANCE = new TinkersProvider();

    private TinkersProvider() {
    }

    @Override
    public String providerId() {
        return WeaponKeys.PROVIDER_TCONSTRUCT;
    }

    @Override
    public boolean isAvailable() {
        return WeaponResolver.isTinkersLoaded();
    }

    @Override
    public Component sourceName() {
        return Component.translatable("albionmastery.source.tconstruct");
    }

    @Override
    public void collect(List<WeaponEntry> out) {
        int sort = 0;
        // 直接遍历注册表本身，不再复制一份临时表
        for (Map.Entry<ResourceKey<Item>, Item> entry : ForgeRegistries.ITEMS.getEntries()) {
            ItemStack stack = TinkersSupport.displayStack(entry.getValue());
            if (!TinkersSupport.isTinkersWeapon(stack)) {
                continue;
            }
            // 解析器排在前面的来源认走了就跳过（匠魂拔刀的刀归拔刀剑）
            ResolvedWeapon resolved = WeaponResolver.resolve(stack, false);
            if (resolved == null || !WeaponKeys.PROVIDER_TCONSTRUCT.equals(resolved.providerId())) {
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
