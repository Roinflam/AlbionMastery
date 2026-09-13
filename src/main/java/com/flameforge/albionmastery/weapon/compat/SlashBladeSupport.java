package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 拔刀剑的双端解析支持。
 *
 * <p>拔刀剑是可选依赖，本类直接引用它的类，因此<b>调用方必须先确认该模组已安装</b>
 * （见 {@link com.flameforge.albionmastery.weapon.WeaponResolver}）。
 */
public final class SlashBladeSupport {

    private SlashBladeSupport() {
    }

    /**
     * 取出物品堆对应的刀 ID。
     *
     * <p>拔刀剑把「是哪一把刀」记在物品的 {@code BLADESTATE} Capability 里，
     * {@code ItemSlashBlade.getBladeId} 已经封装好了取值与解析，不用自己去拆翻译键。
     *
     * <p>各附属包会注册自己的物品类（例如 {@code srelic:srelic_slashblade}），
     * 但都继承自 {@code ItemSlashBlade}，所以用 instanceof 判定能一并覆盖。
     *
     * @param stack 物品堆
     * @return 刀 ID；不是拔刀剑时返回 null
     */
    @Nullable
    public static ResourceLocation bladeId(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemSlashBlade blade)) {
            return null;
        }
        ResourceLocation id = blade.getBladeId(stack);
        if (id == null || id.getPath().isEmpty()) {
            return null;
        }
        return id;
    }

    /**
     * 把物品堆解析成完整定位。
     *
     * <p>类型取刀 ID 的<b>命名空间</b>，也就是「这把刀是哪个附属包出的」。
     * 拔刀剑的定义文件里没有通用的分类字段——{@code creativeGroup} 八成的刀都没填，
     * {@code sword_type} 是状态不是分类——命名空间是唯一 100% 覆盖且有意义的维度。
     *
     * @param stack 物品堆
     * @return 定位结果；不是拔刀剑时返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack) {
        ResourceLocation id = bladeId(stack);
        if (id == null) {
            return null;
        }
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_SLASHBLADE, id, id.getNamespace());
    }
}
