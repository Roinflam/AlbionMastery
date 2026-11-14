package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.IronsArtificeSupport;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 铁枪的客户端来源：中圈是手枪 / 长枪，外圈是每一把枪。
 *
 * <p>枪是普通的注册物品，客户端遍历物品注册表就能列全，和服务端清单是同一套判定。
 */
public final class IronsArtificeProvider implements IWeaponProvider {

    public static final IronsArtificeProvider INSTANCE = new IronsArtificeProvider();

    private IronsArtificeProvider() {
    }

    @Override
    public String providerId() {
        return WeaponKeys.PROVIDER_IRONS_ARTIFICE;
    }

    @Override
    public boolean isAvailable() {
        return WeaponResolver.isIronsArtificeLoaded();
    }

    @Override
    public Component sourceName() {
        return Component.translatable("albionmastery.source.irons_artifice");
    }

    @Override
    public void collect(List<WeaponEntry> out) {
        int sort = 0;
        // 按注册编号顺序遍历：遍历序号就是界面上的排列，每次启动必须一样，不能用哈希表视图那种顺序不稳的遍历
        for (Holder.Reference<Item> holder : (Iterable<Holder.Reference<Item>>) BuiltInRegistries.ITEM.holders()::iterator) {
            ItemStack stack = new ItemStack(holder.value());
            ResolvedWeapon resolved = IronsArtificeSupport.resolve(stack);
            if (resolved == null) {
                continue;
            }
            ResourceLocation id = holder.key().location();
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
