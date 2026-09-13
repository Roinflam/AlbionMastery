package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import mods.flammpfeil.slashblade.SlashBlade;
import mods.flammpfeil.slashblade.registry.slashblade.SlashBladeDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.Optional;

/**
 * 拔刀剑来源。
 *
 * <p>刀的清单来自 {@code SlashBlade.getSlashBladeDefinitionRegistry(Level)} —— 一个数据包注册表，
 * 装了几个附属包就能读到几个包的刀，不需要任何手写配置。
 *
 * <p><b>类型层用命名空间</b>，也就是「这把刀是哪个附属包出的」。拔刀剑的定义文件里没有通用的
 * 分类字段：{@code creativeGroup} 实测八成的刀都没填，{@code sword_type} 是状态（附魔/封印）
 * 而不是分类。命名空间是唯一 100% 覆盖且对玩家有意义的维度。
 *
 * <p>注意：本类直接引用了拔刀剑的类型，只有在 {@link WeaponResolver#isSlashBladeLoaded()}
 * 为真时才允许被加载与调用。
 */
public final class SlashBladeProvider implements IWeaponProvider {

    /** 单例。 */
    public static final SlashBladeProvider INSTANCE = new SlashBladeProvider();

    private SlashBladeProvider() {
    }

    @Override
    public String providerId() {
        return WeaponKeys.PROVIDER_SLASHBLADE;
    }

    @Override
    public boolean isAvailable() {
        return WeaponResolver.isSlashBladeLoaded();
    }

    @Override
    public Component sourceName() {
        return Component.translatable("albionmastery.source.slashblade");
    }

    @Override
    public void collect(List<WeaponEntry> out) {
        if (!isAvailable()) {
            return;
        }
        // 数据包注册表要从世界拿，没进世界时读不到——界面本来也只在游戏里打开
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Registry<SlashBladeDefinition> registry;
        try {
            registry = SlashBlade.getSlashBladeDefinitionRegistry(level);
        } catch (Exception exception) {
            AlbionMastery.LOGGER.debug("读取拔刀剑定义注册表失败", exception);
            return;
        }
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
            String type = id.getNamespace();
            ItemStack icon = createIcon(definition);

            out.add(new WeaponEntry(
                    WeaponKeys.weaponKey(providerId(), id),
                    WeaponKeys.typeKey(providerId(), type),
                    WeaponKeys.overallKey(providerId()),
                    id,
                    type,
                    resolveDisplayName(icon, definition),
                    type,
                    0,
                    icon
            ));
        }
    }

    @Override
    public Component typeName(String type) {
        // 1) 允许用本模组的语言文件覆盖某个附属包的显示名
        String overrideKey = "albionmastery.type." + providerId() + "." + type;
        if (I18n.exists(overrideKey)) {
            return Component.translatable(overrideKey);
        }
        // 2) 命名空间基本都对应一个已安装的模组，直接用模组名
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
     * <p>{@code SlashBladeDefinition.getBlade()} 直接给出一把配置好的成品刀，
     * 比 TACZ 那边还省事，不用自己拼 NBT。
     *
     * @param definition 刀的定义
     * @return 物品堆，失败时返回 {@link ItemStack#EMPTY}
     */
    private static ItemStack createIcon(SlashBladeDefinition definition) {
        try {
            ItemStack stack = definition.getBlade();
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Exception exception) {
            AlbionMastery.LOGGER.debug("构建拔刀剑图标失败: {}", definition.getName(), exception);
            return ItemStack.EMPTY;
        }
    }

    /**
     * 解析显示名称。
     *
     * @param icon       图标物品堆
     * @param definition 刀的定义
     * @return 显示名称
     */
    private static Component resolveDisplayName(ItemStack icon, SlashBladeDefinition definition) {
        if (!icon.isEmpty()) {
            return icon.getHoverName();
        }
        String key = definition.getTranslationKey();
        if (key != null && !key.isBlank()) {
            return Component.translatable(key);
        }
        return Component.literal(definition.getName().getPath());
    }
}
