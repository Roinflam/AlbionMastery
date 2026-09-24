package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import com.flameforge.albionmastery.AlbionMastery;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * 通用近战武器：所有继承原版 {@code SwordItem} / {@code AxeItem} / {@code TridentItem} 的物品，
 * 不管来自哪个模组。来源「近战武器」，大类三个：剑、斧、三叉戟；单件 = 物品。
 *
 * <p>兜底来源：解析器里排在最后，前面几个来源（近战附属、拔刀剑……）认走了的物品不会落到这里。
 * 拔刀剑的刀也是 {@code SwordItem}，装了拔刀剑时它先认走，不会重复。只用原版类。
 *
 * <p>1.20.1 原版近战就这三类，重锤是 1.21 才有的。三叉戟投掷出去走弓弩那边的「投射物」路径。
 */
public final class VanillaMeleeSupport {

    /** 剑。 */
    public static final String TYPE_SWORD = "sword";

    /** 斧。 */
    public static final String TYPE_AXE = "axe";

    /** 三叉戟。 */
    public static final String TYPE_TRIDENT = "trident";

    /** 认不进前三类的近战武器：模组自己写的大剑、长枪、镰、爪、拳套、锤…… */
    public static final String TYPE_OTHER = "other";

    /** Forge 约定的剑标签。 */
    private static final TagKey<Item> FORGE_SWORDS = tag("forge", "tools/swords");

    /** Forge 约定的斧标签。 */
    private static final TagKey<Item> FORGE_AXES = tag("forge", "tools/axes");

    /** 斧标签的单数拼法：灾变等模组用的是这个。 */
    private static final TagKey<Item> FORGE_AXE = tag("forge", "tools/axe");

    /** Forge 约定的三叉戟标签。 */
    private static final TagKey<Item> FORGE_TRIDENTS = tag("forge", "tools/tridents");

    /** 1.21 才进原版、但已经有模组在用的剑标签。 */
    private static final TagKey<Item> VANILLA_SWORDS = tag("minecraft", "swords");

    private VanillaMeleeSupport() {
    }

    /**
     * 这件物品算不算通用近战武器。
     *
     * @param stack 物品
     * @return 是返回 true
     */
    public static boolean isVanillaMelee(ItemStack stack) {
        return typeOf(stack) != null;
    }

    /**
     * 物品 → 大类。
     *
     * <p>为什么不只看物品类：很多模组的武器有自己的基类，根本不继承 {@code SwordItem}。
     * 灾变（L_Ender's Cataclysm）十几件武器全是 {@code Cataclysm_Weapon_Item extends Item}，
     * 匠魂全套是 {@code ModifiableItem extends TieredItem}，按类判定一件都收不到——
     * 玩家拿着它们打怪，专精一点不涨，界面上也找不到这把武器。
     *
     * <p>所以按这个顺序认：
     * <ol>
     *     <li>补录表（{@link WeaponOverrides}）：内置了常见模组的武器，服主还能在配置文件里增删；</li>
     *     <li>原版物品类，剑 / 斧 / 三叉戟；</li>
     *     <li>模组作者自己打的 {@code forge:tools/*} 标签——他们标了的我们白捡。</li>
     * </ol>
     * 三层都不命中就不是近战武器：不去猜「这东西有攻击力所以大概是武器」，
     * 否则镐子锄头和一堆有攻击力的道具会一起被收进树里。
     *
     * @param stack 物品
     * @return 剑 / 斧 / 三叉戟 / 其它近战；都不是返回 null
     */
    @Nullable
    public static String typeOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (WeaponOverrides.isExcluded(id)) {
            return null;
        }
        String override = WeaponOverrides.typeOf(id);
        if (override != null) {
            return switch (override) {
                case WeaponOverrides.SWORD -> TYPE_SWORD;
                case WeaponOverrides.AXE -> TYPE_AXE;
                case WeaponOverrides.TRIDENT -> TYPE_TRIDENT;
                case WeaponOverrides.OTHER -> TYPE_OTHER;
                // 补录成弓弩的物品不归近战，交给 BowSupport
                default -> null;
            };
        }
        if (stack.getItem() instanceof SwordItem) {
            return TYPE_SWORD;
        }
        if (stack.getItem() instanceof AxeItem) {
            return TYPE_AXE;
        }
        if (stack.getItem() instanceof TridentItem) {
            return TYPE_TRIDENT;
        }
        if (stack.is(FORGE_SWORDS) || stack.is(VANILLA_SWORDS)) {
            return TYPE_SWORD;
        }
        if (stack.is(FORGE_AXES) || stack.is(FORGE_AXE)) {
            return TYPE_AXE;
        }
        if (stack.is(FORGE_TRIDENTS)) {
            return TYPE_TRIDENT;
        }
        return null;
    }

    /**
     * 拼一个物品标签键。
     *
     * @param namespace 命名空间
     * @param path      路径
     * @return 标签键
     */
    private static TagKey<Item> tag(String namespace, String path) {
        return ItemTags.create(new ResourceLocation(namespace, path));
    }

    /**
     * 原版近战武器 → 武器。
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
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_VANILLA_MELEE, id, type);
    }
}
