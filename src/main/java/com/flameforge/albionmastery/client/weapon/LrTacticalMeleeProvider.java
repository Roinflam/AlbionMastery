package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.client.ClientMasteryCache;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.LrTacticalSupport;
import me.xjqsh.lrtactical.api.LrTacticalAPI;
import me.xjqsh.lrtactical.item.index.MeleeWeaponIndex;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.Optional;

/**
 * lrtactical 近战武器来源（刀包）。
 *
 * <p>清单来自 {@link LrTacticalAPI#getMeleeIndexes()}，也就是近战创造标签栏用的同一份数据；
 * 装了几个刀包就能读到几个刀包。
 *
 * <p>武器类型按<b>刀包</b>拆分（配置项 {@code splitMeleeByPack}，默认开启）：一个刀包一个类型节点，
 * 和拔刀剑的附属包一个口径，专精树上近战这一支不再只有孤零零的一层。
 * 关掉之后回到旧行为——所有近战共用一个「近战」类型。
 *
 * <p>拆分口径取自<b>服务端下发的配置快照</b>，不是客户端本地配置：类型键要和服务端算出来的一致，
 * 否则界面上的类型节点永远是 0 级。
 *
 * <p>注意：本类直接引用了 lrtactical 的类型，只有在
 * {@link WeaponResolver#isLrTacticalLoaded()} 为真时才允许被加载与调用。
 */
public final class LrTacticalMeleeProvider implements IWeaponProvider {

    /** 单例。 */
    public static final LrTacticalMeleeProvider INSTANCE = new LrTacticalMeleeProvider();

    private LrTacticalMeleeProvider() {
    }

    @Override
    public String providerId() {
        return WeaponKeys.PROVIDER_LRTACTICAL_MELEE;
    }

    @Override
    public boolean isAvailable() {
        return WeaponResolver.isLrTacticalLoaded();
    }

    @Override
    public Component sourceName() {
        return Component.translatable("albionmastery.source.lrtactical_melee");
    }

    @Override
    public void collect(List<WeaponEntry> out) {
        if (!isAvailable()) {
            return;
        }
        boolean splitByPack = ClientMasteryCache.snapshot().splitMeleeByPack;
        for (MeleeWeaponIndex<?> index : LrTacticalAPI.getMeleeIndexes()) {
            if (index == null) {
                continue;
            }
            ResourceLocation id = index.getId();
            if (id == null || id.getPath().isEmpty()) {
                continue;
            }
            // 类型标识和服务端 LrTacticalSupport 用的是同一个函数，两边不会算出不同的键
            String type = LrTacticalSupport.typeOf(id, splitByPack);
            ItemStack icon = createIcon(index);
            out.add(new WeaponEntry(
                    WeaponKeys.weaponKey(providerId(), id),
                    WeaponKeys.typeKey(providerId(), type),
                    WeaponKeys.overallKey(providerId()),
                    id,
                    id.getNamespace(),
                    resolveDisplayName(icon, index),
                    type,
                    0,
                    icon
            ));
        }
    }

    @Override
    public Component typeName(String type) {
        // 1) 本模组语言文件里的专用覆盖，优先级最高（用来给某个刀包起个好听的名字）
        String overrideKey = "albionmastery.type." + providerId() + "." + type;
        if (I18n.exists(overrideKey)) {
            return Component.translatable(overrideKey);
        }
        // 2) 通用类型名，没拆分时的 melee 走这里
        String plainKey = "albionmastery.type." + type;
        if (I18n.exists(plainKey)) {
            return Component.translatable(plainKey);
        }
        // 3) 刀包的命名空间通常就是一个已安装的模组，直接用模组名当分类名
        Optional<String> modName = ModList.get().getModContainerById(type)
                .map(container -> container.getModInfo().getDisplayName());
        if (modName.isPresent() && !modName.get().isBlank()) {
            return Component.literal(modName.get());
        }
        return Component.literal(type);
    }

    /**
     * 构造节点图标。
     *
     * @param index 近战武器索引
     * @return 物品堆，失败时返回 {@link ItemStack#EMPTY}
     */
    private static ItemStack createIcon(MeleeWeaponIndex<?> index) {
        try {
            ItemStack stack = index.createItemStack();
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Exception exception) {
            AlbionMastery.LOGGER.debug("构建近战武器图标失败: {}", index.getId(), exception);
            return ItemStack.EMPTY;
        }
    }

    /**
     * 解析显示名称。
     *
     * <p>{@code MeleeItem} 覆写了 {@code getDescriptionId(ItemStack)}，所以物品堆的悬浮名
     * 就是刀包语言文件里配的那个名字，和创造栏完全一致。
     *
     * @param icon  图标物品堆
     * @param index 近战武器索引
     * @return 显示名称
     */
    private static Component resolveDisplayName(ItemStack icon, MeleeWeaponIndex<?> index) {
        if (!icon.isEmpty()) {
            return icon.getHoverName();
        }
        String name = index.getName();
        if (name != null && !name.isBlank()) {
            return Component.translatable(name);
        }
        return Component.literal("?");
    }
}
