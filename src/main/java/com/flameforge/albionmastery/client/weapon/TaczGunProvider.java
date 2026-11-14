package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import com.flameforge.albionmastery.AMIds;
/**
 * TACZ 枪械来源。
 *
 * <p>枪械清单直接来自 {@link TimelessAPI#getAllClientGunIndex()}，也就是创造模式标签栏
 * 用的同一份数据。玩家装了多少枪包，这里就能读到多少枪，不需要任何手写配置。
 *
 * <p>类型取索引里的 {@code type} 字段，跨枪包通用。
 */
public final class TaczGunProvider implements IWeaponProvider {

    /** 单例。 */
    public static final TaczGunProvider INSTANCE = new TaczGunProvider();

    /** TACZ 的模组 ID。 */
    private static final String TACZ_MOD_ID = "tacz";

    /** 现代动能枪械物品的注册名，用于构造图标失败时的兜底。 */
    private static final ResourceLocation MODERN_KINETIC_GUN =
            AMIds.of(TACZ_MOD_ID, "modern_kinetic_gun");

    private TaczGunProvider() {
    }

    @Override
    public String providerId() {
        return WeaponKeys.PROVIDER_TACZ_GUN;
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded(TACZ_MOD_ID);
    }

    @Override
    public Component sourceName() {
        return Component.translatable("albionmastery.source.tacz_gun");
    }

    @Override
    public void collect(List<WeaponEntry> out) {
        if (!isAvailable()) {
            return;
        }
        for (Map.Entry<ResourceLocation, ClientGunIndex> indexEntry : TimelessAPI.getAllClientGunIndex()) {
            ResourceLocation gunId = indexEntry.getKey();
            ClientGunIndex index = indexEntry.getValue();
            if (gunId == null || index == null || gunId.getPath().isEmpty()) {
                continue;
            }

            String type = index.getType() == null || index.getType().isBlank()
                    ? WeaponKeys.TYPE_UNKNOWN : index.getType();
            ItemStack icon = createIcon(gunId);

            out.add(new WeaponEntry(
                    WeaponKeys.weaponKey(providerId(), gunId),
                    WeaponKeys.typeKey(providerId(), type),
                    WeaponKeys.overallKey(providerId()),
                    gunId,
                    gunId.getNamespace(),
                    resolveDisplayName(icon, index),
                    type,
                    resolveSort(gunId),
                    icon
            ));
        }
    }

    @Override
    public Component typeName(String type) {
        // 1) 允许用本模组的语言文件覆盖
        String overrideKey = "albionmastery.type." + type;
        if (I18n.exists(overrideKey)) {
            return Component.translatable(overrideKey);
        }
        // 2) TACZ 自带的类型名，任何枪包都能复用
        String taczKey = "tacz.type." + type + ".name";
        if (I18n.exists(taczKey)) {
            return Component.translatable(taczKey);
        }
        return Component.literal(type);
    }

    /**
     * 构造用于渲染节点图标的物品堆。
     *
     * <p>先走 TACZ 官方的 {@link GunItemBuilder}，它会根据枪械的 {@code item_type}
     * 选到正确的物品；失败时退回手动设置 {@code GunId}，至少保证图标不缺失。
     *
     * @param gunId 枪械 ID
     * @return 物品堆，彻底失败时返回 {@link ItemStack#EMPTY}
     */
    private static ItemStack createIcon(ResourceLocation gunId) {
        try {
            ItemStack stack = GunItemBuilder.create().setId(gunId).build();
            if (!stack.isEmpty()) {
                return stack;
            }
        } catch (Exception exception) {
            AlbionMastery.LOGGER.debug("构建枪械图标失败，回退到手动方式: {}", gunId, exception);
        }

        Item item = BuiltInRegistries.ITEM.get(MODERN_KINETIC_GUN);
        // 物品注册表查不到时返回默认值（空气），下面还要再判一次空堆
        if (item == null) {
            return ItemStack.EMPTY;
        }
        ItemStack fallback = new ItemStack(item);
        if (fallback.isEmpty()) {
            return ItemStack.EMPTY;
        }
        IGun iGun = IGun.getIGunOrNull(fallback);
        if (iGun == null) {
            return ItemStack.EMPTY;
        }
        iGun.setGunId(fallback, gunId);
        return fallback;
    }

    /**
     * 解析枪械的显示名称。
     *
     * @param icon  已构造的图标物品堆
     * @param index 客户端枪械索引
     * @return 显示名称
     */
    private static Component resolveDisplayName(ItemStack icon, ClientGunIndex index) {
        if (!icon.isEmpty()) {
            return icon.getHoverName();
        }
        String name = index.getName();
        if (name != null && !name.isBlank()) {
            return Component.translatable(name);
        }
        return Component.literal("?");
    }

    /**
     * 取得枪械在创造栏里的排序值，让专精界面的排列顺序和创造栏一致。
     *
     * @param gunId 枪械 ID
     * @return 排序值，取不到时为 0
     */
    private static int resolveSort(ResourceLocation gunId) {
        try {
            return TimelessAPI.getCommonGunIndex(gunId).map(CommonGunIndex::getSort).orElse(0);
        } catch (Exception exception) {
            return 0;
        }
    }
}
