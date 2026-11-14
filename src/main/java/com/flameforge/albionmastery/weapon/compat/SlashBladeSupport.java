package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import mods.flammpfeil.slashblade.SlashBlade;
import mods.flammpfeil.slashblade.capability.slashblade.BladeStateAccess;
import mods.flammpfeil.slashblade.capability.slashblade.BladeStateData;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.slashblade.SlashBladeDefinition;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import mods.flammpfeil.slashblade.entity.EntityAbstractSummonedSword;
import net.minecraft.world.entity.Entity;
import com.flameforge.albionmastery.weapon.WeaponResolver;

import net.minecraft.core.registries.BuiltInRegistries;
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
     * <p>拔刀剑把「是哪一把刀」记在物品的 {@code slashblade:blade_state} 组件的翻译键里
     * （{@code item.ns.path}），刀 ID 由 {@code ItemSlashBlade.parseBladeID} 解析成 {@code ns:path}。
     * 这里不调 {@code ItemSlashBlade.getBladeId}：它经 {@code BladeStateAccess.of} 取状态，
     * 刀上没有组件时会顺手写入一个默认组件。本方法在提示框、HUD、受伤判定里调得很频繁，
     * 客户端还会翻背包，写物品会让本地物品堆和服务端对不上，所以改用只读的
     * {@code BladeStateAccess.getData}，取值顺序和 {@code getBladeId} 相同。
     *
     * <p>组件缺失或翻译键为空时，再读 {@code minecraft:custom_data} 下的 {@code bladeState.translationKey}：
     * 1.20.1 把刀状态存在物品标签 {@code bladeState} 里，升级存档时原版的物品组件化修复把认不得的标签
     * 整体挪进 {@code custom_data}，拔刀剑新版不读它。读这里能让旧档里的刀认回原来的刀 ID，
     * 专精经验记到同一个节点上。只读，不替拔刀剑迁移刀的状态。
     *
     * <p>两处都没有翻译键时退回物品 ID，和 {@code getBladeId} 一致。
     *
     * <p>各附属包会注册自己的物品类（例如 {@code srelic:srelic_slashblade}），
     * 但都继承自 {@code ItemSlashBlade}，所以用 instanceof 判定能一并覆盖。
     *
     * @param stack 物品堆
     * @return 刀 ID；不是拔刀剑时返回 null
     */
    @Nullable
    public static ResourceLocation bladeId(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemSlashBlade)) {
            return null;
        }
        ResourceLocation id = parseKey(BladeStateAccess.getData(stack)
                .map(BladeStateData::translationKey)
                .orElse(""));
        if (id == null) {
            id = parseKey(legacyTranslationKey(stack));
        }
        if (id == null) {
            id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        }
        if (id == null || id.getPath().isEmpty()) {
            return null;
        }
        return id;
    }

    /**
     * 把翻译键解析成刀 ID，空键或解析不出时返回 null。
     *
     * <p>{@code parseBladeID} 直接截掉前 5 个字符（{@code item.}），过短的键先挡掉，免得抛异常。
     */
    @Nullable
    private static ResourceLocation parseKey(@Nullable String key) {
        if (key == null || key.isBlank() || key.length() <= LANG_PREFIX_LENGTH) {
            return null;
        }
        return ItemSlashBlade.parseBladeID(key);
    }

    /** 翻译键前缀 {@code item.} 的长度。 */
    private static final int LANG_PREFIX_LENGTH = 5;

    /**
     * 旧档刀上留下的翻译键：{@code minecraft:custom_data} → {@code bladeState.translationKey}。
     *
     * @param stack 物品堆
     * @return 翻译键；没有时为空串
     */
    private static String legacyTranslationKey(ItemStack stack) {
        CustomData legacy = stack.get(DataComponents.CUSTOM_DATA);
        if (legacy == null || !legacy.contains(LEGACY_STATE_KEY)) {
            return "";
        }
        return legacy.copyTag().getCompound(LEGACY_STATE_KEY).getString(LEGACY_TRANSLATION_KEY);
    }

    /** 1.20.1 拔刀剑存刀状态的物品标签键。 */
    private static final String LEGACY_STATE_KEY = "bladeState";

    /** 刀状态里翻译键的字段名。 */
    private static final String LEGACY_TRANSLATION_KEY = "translationKey";

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
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType());
        return typeId != null && WeaponResolver.SLASHBLADE_MOD_ID.equals(typeId.getNamespace());
    }
}
