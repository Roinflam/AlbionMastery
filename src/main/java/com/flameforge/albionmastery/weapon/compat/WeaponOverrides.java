package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.AlbionMastery;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「这件物品算哪一类武器」的补录表：模组自己写基类、不继承原版武器类时，靠这张表认出来。
 *
 * <p>补的是什么：判定武器原本只看物品类（{@code SwordItem} / {@code AxeItem} / …）。
 * 可很多模组的武器有自己的基类：灾变十几件武器全是 {@code Cataclysm_Weapon_Item extends Item}，
 * 诡厄的镰、锤、匕首是 {@code TieredItem} 直接子类，额外植物学的锤继承的是镐。
 * 这些武器在游戏里就是武器，拿着打怪却一点专精都涨不了，界面上也找不到。
 *
 * <p>两个来源：
 * <ol>
 *     <li><b>内置表</b>（下面这张）：常见模组的武器，装了就生效，不用玩家做任何事；</li>
 *     <li><b>配置</b>：{@code albionmastery-common.toml} 里的 {@code extraWeapons} / {@code notWeapons}，
 *         服主想补或想踢，填一行就行。配置会随服务端快照同步给客户端，两端认的是同一张表。</li>
 * </ol>
 *
 * <p>大类取值：{@code sword}、{@code axe}、{@code trident}、{@code other}（近战），
 * {@code bow}、{@code crossbow}（远程）。
 */
public final class WeaponOverrides {

    /** 近战：剑。 */
    public static final String SWORD = "sword";

    /** 近战：斧。 */
    public static final String AXE = "axe";

    /** 近战：三叉戟。 */
    public static final String TRIDENT = "trident";

    /** 近战：其它（大剑、长枪、镰、爪、拳套、战锤……）。 */
    public static final String OTHER = "other";

    /** 远程：弓。 */
    public static final String BOW = "bow";

    /** 远程：弩。 */
    public static final String CROSSBOW = "crossbow";

    /** 合法的大类。 */
    private static final Set<String> VALID = Set.of(SWORD, AXE, TRIDENT, OTHER, BOW, CROSSBOW);

    /** 内置表：物品 id → 大类。 */
    private static final Map<String, String> BUILT_IN = buildBuiltIn();

    /** 配置补的，优先级高于内置表。 */
    private static volatile Map<String, String> extra = Map.of();

    /** 配置踢掉的。 */
    private static volatile Set<String> excluded = Set.of();

    /**
     * 这份表是不是服务端同步过来的。
     *
     * <p>连服务端玩时，本机的 {@code albionmastery-common.toml} 一旦被写一次，Forge 会重载配置、
     * 顺手把这张表按本地配置刷一遍，服务端同步过来的补录就被本地的空表盖掉了，
     * 武器解析立刻和服务端对不上。所以同步过之后，本地配置的刷新一律让路。
     */
    private static volatile boolean fromServer;

    private WeaponOverrides() {
    }

    /**
     * 查这件物品被补录成了哪一类。
     *
     * @param id 物品注册名
     * @return 大类；没补录过返回 null
     */
    @Nullable
    public static String typeOf(@Nullable ResourceLocation id) {
        if (id == null) {
            return null;
        }
        String key = id.toString();
        if (excluded.contains(key)) {
            return null;
        }
        String type = extra.get(key);
        return type != null ? type : BUILT_IN.get(key);
    }

    /**
     * 这件物品是不是被明确排除了（不管它长得多像武器）。
     *
     * @param id 物品注册名
     * @return 排除了返回 true
     */
    public static boolean isExcluded(@Nullable ResourceLocation id) {
        return id != null && excluded.contains(id.toString());
    }

    /**
     * 按配置刷新补录表。
     *
     * <p>服务端在刷新配置快照时调用，客户端在收到服务端快照时调用——两端读的是同一份列表，
     * 不会出现「服务端认这是武器、客户端不认」这种对不上的情况。
     *
     * @param extraEntries    补录条目，格式 {@code 物品id=大类}；只写 id 时按「其它近战」算
     * @param excludedEntries 要排除的物品 id
     * @return 这两张表和原来相比真的变了返回 true
     */
    public static boolean applyFromServer(List<String> extraEntries, List<String> excludedEntries) {
        boolean changed = apply(extraEntries, excludedEntries);
        fromServer = true;
        return changed;
    }

    /**
     * 按本地配置刷新。
     *
     * <p>已经有服务端同步过来的表时不动：本地配置重载不该把服务端的口径冲掉。
     *
     * @param extraEntries    补录条目
     * @param excludedEntries 排除条目
     * @return 真的变了返回 true
     */
    public static boolean applyFromConfig(List<String> extraEntries, List<String> excludedEntries) {
        if (fromServer) {
            return false;
        }
        return apply(extraEntries, excludedEntries);
    }

    /**
     * 断开服务端连接：忘掉同步过来的表，回到本地配置说了算。
     */
    public static void clearServerData() {
        fromServer = false;
    }

    /**
     * 真正刷新表：解析、规范化、校验。
     *
     * <p>一般走 {@link #applyFromServer} 或 {@link #applyFromConfig}，它们决定谁说了算。
     *
     * @param extraEntries    补录条目，格式 {@code 物品id=大类}；只写 id 时按「其它近战」算
     * @param excludedEntries 要排除的物品 id
     * @return 这两张表和原来相比真的变了返回 true
     */
    public static boolean apply(List<String> extraEntries, List<String> excludedEntries) {
        Map<String, String> parsed = new HashMap<>();
        for (String entry : extraEntries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String text = entry.trim();
            int split = text.indexOf('=');
            String rawId = (split < 0 ? text : text.substring(0, split)).trim();
            String type = split < 0 ? OTHER : text.substring(split + 1).trim().toLowerCase(Locale.ROOT);
            String id = normalize(rawId);
            if (id == null) {
                AlbionMastery.LOGGER.warn("[albionmastery] extraWeapons 里的物品 id 不合法，已跳过：{}", entry);
                continue;
            }
            if (!VALID.contains(type)) {
                AlbionMastery.LOGGER.warn("[albionmastery] extraWeapons 里的类型无法识别（只能是 {}），已跳过：{}",
                        VALID, entry);
                continue;
            }
            if (!id.equals(rawId)) {
                // 漏了命名空间、写了大写，以前是默默不生效，服主只能靠试错发现
                AlbionMastery.LOGGER.info("[albionmastery] extraWeapons 的 {} 已按 {} 处理", rawId, id);
            }
            parsed.put(id, type);
        }
        Set<String> dropped = new HashSet<>();
        for (String entry : excludedEntries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String raw = entry.trim();
            String id = normalize(raw);
            if (id == null) {
                AlbionMastery.LOGGER.warn("[albionmastery] notWeapons 里的物品 id 不合法，已跳过：{}", entry);
                continue;
            }
            if (!id.equals(raw)) {
                AlbionMastery.LOGGER.info("[albionmastery] notWeapons 的 {} 已按 {} 处理", raw, id);
            }
            dropped.add(id);
        }
        boolean changed = !parsed.equals(extra) || !dropped.equals(excluded);
        extra = Map.copyOf(parsed);
        excluded = Set.copyOf(dropped);
        return changed;
    }

    /**
     * 把服主随手写的 id 规范成注册名的标准写法：补上默认命名空间、转成小写。
     *
     * @param raw 原始文本
     * @return 规范后的 id；不合法返回 null
     */
    @Nullable
    private static String normalize(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        return id == null ? null : id.toString();
    }

    /**
     * 内置表。
     *
     * <p>只收「确实是武器、但类不对所以认不出来」的；有正常物品类的（灾变的 athame、黑钢剑、
     * 诡厄的几把剑和斧）不用写，原来那条路就能认出来。
     *
     * @return 物品 id → 大类
     */
    private static Map<String, String> buildBuiltIn() {
        Map<String, String> map = new HashMap<>();

        // ── 灾变 L_Ender's Cataclysm：14 件武器继承自 Cataclysm_Weapon_Item extends Item ──
        map.put("cataclysm:the_incinerator", SWORD);
        map.put("cataclysm:the_annihilator", SWORD);
        map.put("cataclysm:the_immolator", SWORD);
        map.put("cataclysm:meat_shredder", AXE);
        map.put("cataclysm:soul_render", AXE);
        map.put("cataclysm:coral_spear", TRIDENT);
        map.put("cataclysm:coral_bardiche", TRIDENT);
        map.put("cataclysm:ancient_spear", OTHER);
        map.put("cataclysm:ceraunus", OTHER);
        map.put("cataclysm:astrape", OTHER);
        map.put("cataclysm:tidal_claws", OTHER);
        map.put("cataclysm:gauntlet_of_guard", OTHER);
        map.put("cataclysm:gauntlet_of_bulwark", OTHER);
        map.put("cataclysm:gauntlet_of_maelstrom", OTHER);
        // 这三件代码上是镐，模组自己的描述里写明是战锤
        map.put("cataclysm:brontes", OTHER);
        map.put("cataclysm:infernal_forge", OTHER);
        map.put("cataclysm:void_forge", OTHER);
        // 诅咒之弓继承的是 ProjectileWeaponItem，不是 BowItem
        map.put("cataclysm:cursed_bow", BOW);

        // ── 额外植物学：9 把锤继承 PickaxeItem ──
        for (String material : new String[]{"manasteel", "elementium", "terrasteel", "gaia", "photonium",
                "shadowium", "aerialite", "orichalcos", "rhein"}) {
            map.put("extrabotany:" + material + "_hammer", OTHER);
        }

        // ── 诡厄巫法：镰、锤、匕首、冰镐都是 TieredItem 直接子类 ──
        for (String id : new String[]{"dark_scythe", "dark_metal_scythe", "death_scythe", "great_hammer",
                "bonehead_hammer", "fanged_dagger", "hungry_dagger", "iron_ice_axe", "reinforced_ice_axe"}) {
            map.put("goety:" + id, OTHER);
        }

        // ── 血肉重塑：爪 ──
        map.put("biomancy:mob_claw", OTHER);
        map.put("biomancy:ravenous_claws", OTHER);

        return Map.copyOf(map);
    }
}
