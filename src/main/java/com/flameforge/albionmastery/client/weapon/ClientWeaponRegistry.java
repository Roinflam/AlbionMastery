package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.flameforge.albionmastery.weapon.compat.WeaponOverrides;

/**
 * 客户端武器注册表。
 *
 * <p>把各个 {@link IWeaponProvider} 收集到的武器整理成「来源 -&gt; 类型 -&gt; 武器」的三层结构，
 * 供专精界面直接使用。
 *
 * <p>构建时机：每次打开专精界面时重建一次。整个过程只是遍历几百个索引项，开销可以忽略，
 * 好处是玩家中途 {@code /tacz reload} 换了枪包也能立刻反映出来。
 */
public final class ClientWeaponRegistry {

    /** 武器类型的展示顺序，按「从轻到重」排，界面上看着有规律。 */
    private static final List<String> TYPE_ORDER = List.of(
            "pistol", "smg", "rifle", "shotgun", "sniper", "mg", "rpg", "melee");

    /** 已注册的来源。后续接入拔刀剑时在这里追加即可。 */
    private static final List<IWeaponProvider> PROVIDERS = new ArrayList<>();

    static {
        // 四个来源全是可选依赖，模组缺失时不能触碰它的 Provider，否则会在类加载阶段报错
        if (WeaponResolver.isTaczLoaded()) {
            PROVIDERS.add(TaczGunProvider.INSTANCE);
        }
        if (WeaponResolver.isLrTacticalLoaded()) {
            PROVIDERS.add(LrTacticalMeleeProvider.INSTANCE);
        }
        if (WeaponResolver.isSlashBladeLoaded()) {
            PROVIDERS.add(SlashBladeProvider.INSTANCE);
        }
        if (WeaponResolver.isIronsLoaded()) {
            PROVIDERS.add(IronsSpellsProvider.INSTANCE);
        }
        if (WeaponResolver.isArsLoaded()) {
            PROVIDERS.add(ArsNouveauProvider.INSTANCE);
        }
        // 通用剑类只用原版类，永远可用；排最后，和解析器里的顺序一致
        if (WeaponResolver.isSweetMagicLoaded()) {
            PROVIDERS.add(SweetMagicProvider.INSTANCE);
        }
        if (WeaponResolver.isTinkersLoaded()) {
            PROVIDERS.add(TinkersProvider.INSTANCE);
        }
        PROVIDERS.add(VanillaMeleeProvider.INSTANCE);
        PROVIDERS.add(BowProvider.INSTANCE);
    }

    /** 上一次构建出来的来源列表。 */
    private static List<WeaponSource> sources = List.of();

    /** 武器键 -> 武器条目。 */
    private static Map<String, WeaponEntry> weaponByKey = Map.of();

    /** 类型键 -> 显示名称。 */
    private static Map<String, Component> typeNames = Map.of();

    /** 清单是不是该重建了。 */
    private static boolean dirty = true;

    private ClientWeaponRegistry() {
    }

    /**
     * 追加一个武器来源。
     *
     * @param provider 来源实现
     */
    public static void registerProvider(IWeaponProvider provider) {
        PROVIDERS.add(provider);
    }

    /**
     * 重新构建注册表。
     *
     * @return 构建出来的来源列表（来源 -&gt; 类型 -&gt; 武器，三层都已排序）
     */
    public static List<WeaponSource> rebuild() {
        dirty = false;
        List<WeaponEntry> collected = new ArrayList<>();
        Map<String, IWeaponProvider> providerById = new LinkedHashMap<>();

        for (IWeaponProvider provider : PROVIDERS) {
            if (!provider.isAvailable()) {
                continue;
            }
            providerById.put(provider.providerId(), provider);
            if (!WeaponKeys.isKnownProvider(provider.providerId())) {
                // 没登记的来源在配置里没有自己的倍率键，服主调不到它
                AlbionMastery.LOGGER.warn("[albionmastery] 武器来源「{}」没有登记进 WeaponKeys.ALL_PROVIDERS，"
                        + "配置里不会有它的专精伤害倍率那一行", provider.providerId());
            }
            try {
                provider.collect(collected);
            } catch (Exception exception) {
                AlbionMastery.LOGGER.error("收集武器清单失败: {}", provider.providerId(), exception);
            }
        }

        Map<String, WeaponSource> sourceMap = new LinkedHashMap<>();
        Map<String, WeaponTypeGroup> groupMap = new LinkedHashMap<>();
        Map<String, WeaponEntry> byKey = new HashMap<>();
        Map<String, Component> names = new HashMap<>();
        Set<String> seen = new HashSet<>();

        for (WeaponEntry entry : collected) {
            // 排除表对所有来源生效：TACZ、拔刀剑、铁魔法这些来源自己建清单，
            // 不经过 WeaponResolver，不在这儿滤一道的话物品不涨经验却还挂在树上
            if (WeaponOverrides.isExcluded(entry.weaponId())) {
                continue;
            }
            if (!seen.add(entry.key())) {
                continue;
            }
            byKey.put(entry.key(), entry);

            String providerId = WeaponKeys.providerOf(entry.key());
            IWeaponProvider provider = providerId == null ? null : providerById.get(providerId);

            WeaponSource source = sourceMap.computeIfAbsent(entry.overallKey(), overallKey ->
                    new WeaponSource(
                            providerId == null ? overallKey : providerId,
                            overallKey,
                            provider == null ? Component.literal(overallKey) : provider.sourceName()));

            WeaponTypeGroup group = groupMap.computeIfAbsent(entry.typeKey(), typeKey -> {
                Component displayName = provider == null
                        ? Component.literal(entry.type()) : provider.typeName(entry.type());
                names.put(typeKey, displayName);
                WeaponTypeGroup created = new WeaponTypeGroup(typeKey, entry.type(), displayName);
                source.types().add(created);
                return created;
            });
            group.weapons().add(entry);
        }

        // 类型内部：先按来源给的排序值，再按显示名，保证顺序稳定
        Comparator<WeaponEntry> weaponOrder = Comparator
                .comparingInt(WeaponEntry::sort)
                .thenComparing((WeaponEntry entry) -> entry.displayName().getString())
                .thenComparing(WeaponEntry::key);
        // 类型之间：枪械那套按预设顺序；不在预设里的（比如拔刀剑用命名空间当类型）
        // 按武器数量降序，内容多的附属包排前面
        Comparator<WeaponTypeGroup> typeOrder = Comparator
                .comparingInt((WeaponTypeGroup group) -> {
                    int index = TYPE_ORDER.indexOf(group.type());
                    return index < 0 ? TYPE_ORDER.size() : index;
                })
                .thenComparingInt(group -> -group.weapons().size())
                .thenComparing(WeaponTypeGroup::type);

        for (WeaponSource source : sourceMap.values()) {
            for (WeaponTypeGroup group : source.types()) {
                group.weapons().sort(weaponOrder);
            }
            source.types().sort(typeOrder);
        }

        List<WeaponSource> result = new ArrayList<>(sourceMap.values());
        result.sort(Comparator.comparingInt((WeaponSource source) -> -source.weaponCount())
                .thenComparing(WeaponSource::id));

        sources = List.copyOf(result);
        weaponByKey = Map.copyOf(byKey);
        typeNames = Map.copyOf(names);
        return sources;
    }

    /**
     * @return 上一次构建出来的来源列表
     */
    public static List<WeaponSource> sources() {
        return sources;
    }

    /**
     * 确保注册表已经构建过。
     *
     * <p>升级提示需要武器名与图标，但玩家可能一次都没打开过专精界面，
     * 那时注册表还是空的。这里在第一次用到时补建一次。
     */
    public static void ensureBuilt() {
        if (dirty || sources.isEmpty() || anyProviderStale()) {
            rebuild();
        }
    }

    private static boolean anyProviderStale() {
        for (IWeaponProvider provider : PROVIDERS) {
            if (provider.isAvailable() && provider.isStale()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 已经构建好的来源列表，没建过或者标记过期时先建一次。
     *
     * <p>专精界面每次打开都要这份清单。以前是每次打开都从头重扫一遍整个物品注册表
     * （这个整合包里一两万件物品、四个来源各扫一遍），开界面那一下要卡十几毫秒；
     * 现在只在真的可能变了的时候重建：换存档、收到服务端全量同步、数据包 / 标签重载。
     *
     * @return 来源列表
     */
    public static List<WeaponSource> builtSources() {
        ensureBuilt();
        return sources;
    }

    /**
     * 标记清单过期，下次用到时重建。
     *
     * <p>什么时候该叫：进出存档、收到服务端的全量同步（服主可能改了配置）、
     * 标签或数据包重载（武器判定读标签）、枪包重载。
     */
    public static void invalidate() {
        dirty = true;
    }

    /**
     * 按武器键查询武器条目。
     *
     * @param key 武器键
     * @return 武器条目；未收录时返回 null
     */
    @Nullable
    public static WeaponEntry weaponByKey(@Nullable String key) {
        return key == null ? null : weaponByKey.get(key);
    }

    /**
     * 按类型键取回类型分组。
     *
     * @param typeKey 类型键
     * @return 类型分组；未收录时返回 null
     */
    @Nullable
    public static WeaponTypeGroup typeGroup(String typeKey) {
        for (WeaponSource source : sources) {
            for (WeaponTypeGroup group : source.types()) {
                if (group.key().equals(typeKey)) {
                    return group;
                }
            }
        }
        return null;
    }

    /**
     * 按总体键取回来源。
     *
     * @param overallKey 总体键
     * @return 来源；未收录时返回 null
     */
    @Nullable
    public static WeaponSource source(String overallKey) {
        for (WeaponSource source : sources) {
            if (source.overallKey().equals(overallKey)) {
                return source;
            }
        }
        return null;
    }

    /**
     * 查询类型的显示名称。
     *
     * @param typeKey 类型键
     * @return 显示名称；未收录时回落到类型标识本身
     */
    public static Component typeName(String typeKey) {
        Component name = typeNames.get(typeKey);
        return name == null ? Component.literal(WeaponKeys.typeOf(typeKey)) : name;
    }

    /**
     * 查询来源的显示名称。
     *
     * @param overallKey 总体键
     * @return 显示名称
     */
    public static Component sourceName(String overallKey) {
        for (WeaponSource source : sources) {
            if (source.overallKey().equals(overallKey)) {
                return source.displayName();
            }
        }
        for (IWeaponProvider provider : PROVIDERS) {
            if (provider.providerId().equals(overallKey) && provider.isAvailable()) {
                return provider.sourceName();
            }
        }
        return Component.literal(overallKey);
    }
}
