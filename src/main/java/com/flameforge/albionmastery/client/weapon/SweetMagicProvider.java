package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.SweetMagicSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;

/**
 * 甜蜜魔法的客户端来源：中圈按元素分，外圈是每一张魔法卷轴。
 */
public final class SweetMagicProvider implements IWeaponProvider {

    public static final SweetMagicProvider INSTANCE = new SweetMagicProvider();

    private SweetMagicProvider() {
    }

    @Override
    public String providerId() {
        return WeaponKeys.PROVIDER_SWEETMAGIC;
    }

    @Override
    public boolean isAvailable() {
        return WeaponResolver.isSweetMagicLoaded();
    }

    @Override
    public Component sourceName() {
        return Component.translatable("albionmastery.source.sweetmagic");
    }

    @Override
    public void collect(List<WeaponEntry> out) {
        int sort = 0;
        // 直接遍历注册表本身，不再复制一份临时表
        for (Map.Entry<ResourceKey<Item>, Item> entry : ForgeRegistries.ITEMS.getEntries()) {
            ItemStack stack = new ItemStack(entry.getValue());
            ResolvedWeapon resolved = SweetMagicSupport.resolve(stack);
            if (resolved == null) {
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
