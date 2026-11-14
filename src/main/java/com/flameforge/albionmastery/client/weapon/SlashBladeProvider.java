package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.client.ClientMasteryCache;
import com.flameforge.albionmastery.weapon.compat.SlashBladeSupport;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import mods.flammpfeil.slashblade.SlashBlade;
import mods.flammpfeil.slashblade.capability.slashblade.BladeStateAccess;
import mods.flammpfeil.slashblade.registry.slashblade.SlashBladeDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.flameforge.albionmastery.AMIds;
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
 * <p><b>形态刀</b>：有的附属包让一把刀切形态（绮梦真我的初汐切成「拂晓故世」之类），做法是直接改刀的
 * 翻译键，于是 {@link SlashBladeSupport#bladeId} 解析出来的是一个定义表里没有的 ID。专精照这个 ID 记（双端一致），
 * 可树上没有它的节点：手拿形态刀按 B 找不到节点，等级也没处看。所以除了定义表，
 * 玩家身上带着的刀、专精记录里有的刀也一起列进来。
 *
 * <p>注意：本类直接引用了拔刀剑的类型，只有在 {@link WeaponResolver#isSlashBladeLoaded()}
 * 为真时才允许被加载与调用。
 */
public final class SlashBladeProvider implements IWeaponProvider {

    /** 单例。 */
    public static final SlashBladeProvider INSTANCE = new SlashBladeProvider();

    /** 上次收集时列进去的全部武器键，用来判断有没有新冒出来的形态刀。 */
    private Set<String> listedKeys = Set.of();

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

        Set<String> listed = new HashSet<>();
        for (WeaponEntry entry : out) {
            listed.add(entry.key());
        }
        for (Map.Entry<ResourceLocation, ItemStack> extra : extraBlades(registry).entrySet()) {
            ResourceLocation id = extra.getKey();
            String key = WeaponKeys.weaponKey(providerId(), id);
            if (!listed.add(key)) {
                continue;
            }
            String type = id.getNamespace();
            ItemStack icon = extra.getValue();
            String langKey = translationKeyOf(id);
            Component name = I18n.exists(langKey) ? Component.translatable(langKey)
                    : icon.isEmpty() ? Component.literal(id.getPath()) : icon.getHoverName();
            out.add(new WeaponEntry(
                    key,
                    WeaponKeys.typeKey(providerId(), type),
                    WeaponKeys.overallKey(providerId()),
                    id,
                    type,
                    name,
                    type,
                    0,
                    icon
            ));
        }
        this.listedKeys = Set.copyOf(listed);
    }

    @Override
    public boolean isStale() {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        Registry<SlashBladeDefinition> registry;
        try {
            registry = SlashBlade.getSlashBladeDefinitionRegistry(level);
        } catch (Exception exception) {
            return false;
        }
        if (registry == null) {
            return false;
        }
        for (ResourceLocation id : extraBlades(registry).keySet()) {
            if (!this.listedKeys.contains(WeaponKeys.weaponKey(providerId(), id))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 定义表以外、但该在树上出现的刀：玩家身上带着的，以及专精记录里有的。
     *
     * <p>身上带着的直接拿那件物品当图标；只在记录里的，借同一附属包里名字最接近的那把定义刀，
     * 把翻译键改成形态刀的，名字和刀模就都对了（形态刀和本体共用一个物品类）。
     *
     * @param registry 拔刀剑定义表
     * @return 刀 ID -&gt; 图标，按发现顺序
     */
    private Map<ResourceLocation, ItemStack> extraBlades(Registry<SlashBladeDefinition> registry) {
        Map<ResourceLocation, ItemStack> found = new LinkedHashMap<>();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            for (ItemStack stack : player.getInventory().items) {
                addCarried(found, registry, stack);
            }
            for (ItemStack stack : player.getInventory().offhand) {
                addCarried(found, registry, stack);
            }
        }
        String prefix = providerId() + WeaponKeys.WEAPON_SEPARATOR;
        for (String key : ClientMasteryCache.data().getWeaponsView().keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(key.substring(prefix.length()));
            if (id == null || found.containsKey(id) || registry.containsKey(id)) {
                continue;
            }
            // 卸掉的附属包留下的记录不列：没有语言文件也没有刀模，挂上去只是一个光秃秃的 ID
            if (!ModList.get().isLoaded(id.getNamespace()) || !I18n.exists(translationKeyOf(id))) {
                continue;
            }
            found.put(id, borrowIcon(registry, id));
        }
        return found;
    }

    private static void addCarried(Map<ResourceLocation, ItemStack> found,
                                   Registry<SlashBladeDefinition> registry, ItemStack stack) {
        ResourceLocation id = SlashBladeSupport.bladeId(stack);
        if (id != null && !registry.containsKey(id) && !found.containsKey(id)) {
            found.put(id, stack.copyWithCount(1));
        }
    }

    /**
     * 给只在记录里出现的形态刀找个图标：按下划线从后往前截，找同包里最长的那把定义刀
     * （{@code clear_dream_samsara} → {@code clear_dream}），换上形态刀的翻译键。
     *
     * @param registry 拔刀剑定义表
     * @param id       形态刀 ID
     * @return 图标；找不到本体时为空
     */
    private static ItemStack borrowIcon(Registry<SlashBladeDefinition> registry, ResourceLocation id) {
        String path = id.getPath();
        for (int cut = path.lastIndexOf('_'); cut > 0; cut = path.lastIndexOf('_', cut - 1)) {
            SlashBladeDefinition base = registry.get(AMIds.of(id.getNamespace(), path.substring(0, cut)));
            if (base == null) {
                continue;
            }
            ItemStack icon = createIcon(base);
            if (!icon.isEmpty()) {
                String langKey = translationKeyOf(id);
                // 图标是 getBlade() 新造的，写回它的 blade_state 组件不影响任何实际物品
                BladeStateAccess.of(icon).ifPresent(state -> state.setTranslationKey(langKey));
            }
            return icon;
        }
        return ItemStack.EMPTY;
    }

    /**
     * 刀 ID 反推翻译键，和拔刀剑 {@code parseBladeID} 互逆：{@code ns:path} ↔ {@code item.ns.path}。
     */
    private static String translationKeyOf(ResourceLocation id) {
        return "item." + id.getNamespace() + "." + id.getPath();
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
