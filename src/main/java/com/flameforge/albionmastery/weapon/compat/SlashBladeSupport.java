package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import mods.flammpfeil.slashblade.SlashBlade;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.slashblade.SlashBladeDefinition;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import mods.flammpfeil.slashblade.entity.EntityAbstractSummonedSword;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;
import com.flameforge.albionmastery.weapon.WeaponResolver;

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

    /**
     * 把全部具名拔刀剑列进清单。拔刀剑的定义是数据包注册表，得从世界里拿。
     *
     * @param out   输出
     * @param level 任意一个服务端世界
     */
    public static void catalog(List<WeaponCatalog.Entry> out, Level level) {
        Registry<SlashBladeDefinition> registry = SlashBlade.getSlashBladeDefinitionRegistry(level);
        if (registry == null) {
            return;
        }
        for (SlashBladeDefinition definition : registry) {
            if (definition == null) {
                continue;
            }
            ResourceLocation id = definition.getName();
            if (id == null || id.getPath().isEmpty()) {
                continue;
            }
            // 类型和 resolve() 一样取命名空间
            ResolvedWeapon weapon = ResolvedWeapon.of(WeaponKeys.PROVIDER_SLASHBLADE, id, id.getNamespace());
            out.add(new WeaponCatalog.Entry(weapon, () -> {
                ItemStack stack = definition.getBlade();
                return stack == null ? List.of() : List.of(stack);
            }));
        }
    }

    /**
     * 直接造成伤害的实体算不算拔刀剑打出来的：斩击特效、各种召唤剑、Drive、审判斩，
     * 以及附属包里继承召唤剑的实体。手里拿着刀却用弓射出去的箭不算。
     *
     * @param direct 直接来源
     * @return 算返回 true
     */
    public static boolean isBladeEntity(@Nullable Entity direct) {
        if (direct == null) {
            return false;
        }
        if (direct instanceof EntityAbstractSummonedSword) {
            return true;
        }
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(direct.getType());
        return typeId != null && WeaponResolver.SLASHBLADE_MOD_ID.equals(typeId.getNamespace());
    }
}
