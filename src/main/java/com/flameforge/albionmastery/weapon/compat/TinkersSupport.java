package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import com.flameforge.albionmastery.AlbionMastery;
import slimeknights.tconstruct.library.tools.item.IModifiableDisplay;

/**
 * 匠魂（Tinkers' Construct）及其附属的近战武器。
 *
 * <p>为什么要单独开一个来源：匠魂的武器全是 {@code ModifiableItem extends TieredItem}，
 * 一件都不继承原版武器类，原来一个也认不到。而且它的手感自成一派——同一把剑换套材料就是另一把武器，
 * 和原版剑放在同一个大类下不合适。
 *
 * <p>中圈按<b>武器形态</b>分，不按材料：材料是 NBT，不是物品，同一个 {@code tconstruct:sword}
 * 不管拿什么材料做出来都是同一件武器，练的是「你会不会用剑」而不是「你有没有钴」。
 *
 * <p>怎么认出来：以匠魂自己的标签 {@code tconstruct:modifiable/melee/primary} 为准——
 * 这张表正好就是「真近战武器」，镐、挖沟机、锄镐、砍刀、钓竿只在 {@code melee/weapon} 里而不在它里面，
 * 天然被挡掉。附属模组（工匠创新、魂樱工匠、匠魂杂物）也往这张表里加自己的武器，所以它们白捡。
 * 具体分到哪一类先查内置表，查不到再按匠魂的形态标签兜底。
 *
 * <p>弓弩不归这里：匠魂的长弓短弩已经打了 {@code forge:tools/bows|crossbows}，
 * 现成的弓弩来源就认得，拉弓速度那些特化也直接生效，没必要搬家。
 */
public final class TinkersSupport {

    /** 剑。 */
    public static final String TYPE_SWORD = "sword";

    /** 匕首。 */
    public static final String TYPE_DAGGER = "dagger";

    /** 巨刃（大剑、斩首刀）。 */
    public static final String TYPE_CLEAVER = "cleaver";

    /** 斧。 */
    public static final String TYPE_AXE = "axe";

    /** 锤（钝器）。 */
    public static final String TYPE_HAMMER = "hammer";

    /** 长柄（镰、戟、标枪、战旗）。 */
    public static final String TYPE_POLEARM = "polearm";

    /** 匠魂的「真近战武器」表。 */
    private static final TagKey<Item> MELEE_PRIMARY = tag("modifiable/melee/primary");

    /** 剑形。 */
    private static final TagKey<Item> MELEE_SWORD = tag("modifiable/melee/sword");

    /** 可格挡的小型武器（匕首）。 */
    private static final TagKey<Item> MELEE_PARRY = tag("modifiable/melee/parry");

    /** 双手大型武器。 */
    private static final TagKey<Item> BROAD = tag("modifiable/broad");

    /** 单手小型武器。 */
    private static final TagKey<Item> SMALL = tag("modifiable/small");

    /** 挖掘工具表：在这张表里、又不在近战表里的一律不收。 */
    private static final TagKey<Item> HARVEST_PRIMARY = tag("modifiable/harvest/primary");

    /** 内置的形态表：物品 id → 大类。 */
    private static final Map<String, String> BUILT_IN = buildBuiltIn();

    /**
     * 明确不收的：附属模组往匠魂的近战标签里塞了枪、法术书、弹药和拔刀剑，
     * 纯按标签会把激光枪当剑用。
     */
    private static final Set<String> EXCLUDED = Set.of(
            // 魂樱工匠把枪和法术书也放进了近战表
            "sakuratinker:laser_gun",
            "sakuratinker:revolver",
            "sakuratinker:tinker_spell_book",
            "sakuratinker:shuriken",
            "sakuratinker:tinker_arrow",
            "sakuratinker:tinker_bullet",
            "sakuratinker:yoyo",
            "sakuratinker:grappling_hook",
            "sakuratinker:tinker_wand",
            // 匠魂拔刀：本体是拔刀剑，归拔刀剑来源
            "ticex:reforged_slashblade",
            "ticex:blitz_gun",
            "ticex:revival_spellbook_irons",
            "ticex:meka_tool",
            // 可堆叠的投掷物：堆叠状态下匠魂会直接取消整次攻击，收进来只会时灵时不灵
            "tconstruct:shuriken",
            "tconstruct:throwing_axe",
            // 盾
            "tinkersinnovation:heavy_shield",
            "tconstruct:travelers_shield",
            "tconstruct:plate_shield"
    );

    private TinkersSupport() {
    }

    /**
     * 这件物品是不是匠魂近战武器。
     *
     * @param stack 物品
     * @return 是返回 true
     */
    public static boolean isTinkersWeapon(ItemStack stack) {
        return typeOf(stack) != null;
    }

    /**
     * 物品 → 大类。
     *
     * @param stack 物品
     * @return 大类；不是匠魂近战武器返回 null
     */
    @Nullable
    public static String typeOf(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(MELEE_PRIMARY)) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || WeaponOverrides.isExcluded(id) || EXCLUDED.contains(id.toString())) {
            return null;
        }
        String known = BUILT_IN.get(id.toString());
        if (known != null) {
            return known;
        }
        // 认不出来的（附属自己加的）按匠魂的形态标签兜底
        if (stack.is(MELEE_PARRY) || stack.is(SMALL)) {
            return TYPE_DAGGER;
        }
        if (stack.is(MELEE_SWORD)) {
            return stack.is(BROAD) ? TYPE_CLEAVER : TYPE_SWORD;
        }
        // 挖掘工具混进近战表的不收
        if (stack.is(HARVEST_PRIMARY)) {
            return null;
        }
        return TYPE_SWORD;
    }

    /**
     * 匠魂近战武器 → 武器。
     *
     * @param stack 物品
     * @return 武器；不是或没注册名返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack) {
        String type = typeOf(stack);
        if (type == null) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_TCONSTRUCT, id, type);
    }

    /**
     * 内置形态表。
     *
     * @return 物品 id → 大类
     */
    private static Map<String, String> buildBuiltIn() {
        Map<String, String> map = new HashMap<>();
        map.put("tconstruct:sword", TYPE_SWORD);
        map.put("tconstruct:swasher", TYPE_SWORD);
        map.put("tconstruct:battlesign", TYPE_SWORD);
        map.put("tconstruct:dagger", TYPE_DAGGER);
        map.put("tconstruct:cleaver", TYPE_CLEAVER);
        map.put("tconstruct:hand_axe", TYPE_AXE);
        map.put("tconstruct:broad_axe", TYPE_AXE);
        map.put("tconstruct:minotaur_axe", TYPE_AXE);
        map.put("tconstruct:sledge_hammer", TYPE_HAMMER);
        map.put("tconstruct:scythe", TYPE_POLEARM);
        map.put("tconstruct:javelin", TYPE_POLEARM);
        // ── 工匠创新 ──
        map.put("tinkersinnovation:long_sword", TYPE_SWORD);
        map.put("tinkersinnovation:skelewag_sword", TYPE_SWORD);
        map.put("tinkersinnovation:claw", TYPE_DAGGER);
        // ── 魂樱工匠 ──
        map.put("sakuratinker:great_sword", TYPE_CLEAVER);
        map.put("sakuratinker:swift_sword", TYPE_SWORD);
        map.put("sakuratinker:vampire_knife", TYPE_DAGGER);
        map.put("sakuratinker:blade_convergence", TYPE_SWORD);
        map.put("sakuratinker:first_fractal", TYPE_SWORD);
        map.put("sakuratinker:scythe", TYPE_POLEARM);
        map.put("sakuratinker:battle_flag", TYPE_POLEARM);
        // ── 匠魂杂物 ──
        map.put("tinkers_things:halberd", TYPE_POLEARM);
        return Map.copyOf(map);
    }

    /**
     * 用来展示的物品：匠魂工具得带上材料才有贴图。
     *
     * <p>直接 {@code new ItemStack(item)} 建出来的匠魂工具没有材料 NBT，
     * 模型渲染出来是一团黑——界面上的图标、管理指令发的武器都得用它自己的展示物品。
     *
     * @param item 物品
     * @return 展示用的物品堆；不是匠魂工具就按原样新建一个
     */
    public static ItemStack displayStack(Item item) {
        if (item instanceof IModifiableDisplay) {
            try {
                ItemStack display = IModifiableDisplay.getDisplayStack(item);
                if (display != null && !display.isEmpty()) {
                    // 必须 copy：匠魂返回的是缓存在 Item 上的那一个渲染用 ItemStack 本体，
                    // 全匠魂共用。原样交出去，管理指令把它发进背包、界面拿它当图标，
                    // 谁改一下（堆叠数、NBT）就污染了所有匠魂物品的显示
                    return display.copy();
                }
            } catch (Exception exception) {
                // 匠魂版本换了接口就退回普通物品，顶多是图标难看，不该让界面崩掉
                AlbionMastery.LOGGER.debug("取匠魂展示物品失败：{}", item, exception);
            }
        }
        return new ItemStack(item);
    }

    /**
     * 拼一个匠魂的物品标签键。
     *
     * @param path 路径
     * @return 标签键
     */
    private static TagKey<Item> tag(String path) {
        return ItemTags.create(new ResourceLocation("tconstruct", path));
    }
}
