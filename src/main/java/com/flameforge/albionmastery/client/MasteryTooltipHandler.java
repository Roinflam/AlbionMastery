package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.client.screen.MasteryScreen;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.client.weapon.ClientWeaponRegistry;
import com.flameforge.albionmastery.client.weapon.WeaponEntry;
import com.flameforge.albionmastery.weapon.compat.ArsNouveauSupport;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

import net.neoforged.fml.common.EventBusSubscriber;
/**
 * 物品提示文本处理器：给所有可专精的武器加同一块专精信息。
 *
 * <p><b>插在哪一行是按武器种类挑的</b>，不是一律塞在物品名下面：
 * <ul>
 *     <li>拔刀剑：紧跟在刀型那一行（「妖」「印」「封」）下面，
 *         也就是排在耀魂数 / 杀敌数 / SA 这些行的前面；</li>
 *     <li>近战附属等其它武器：紧跟在物品名下面。</li>
 * </ul>
 * 锚点靠<b>语言键前缀</b>认，不是靠匹配中文文本——
 * 玩家换个语言、附属包改个措辞，按文本匹配立刻就失灵了。
 *
 * <p>TACZ 枪械不走这里：它的提示是一个自绘组件，文本行里根本没有伤害那一行，
 * 专精信息由 {@code ClientGunTooltipMixin} 单独画在伤害那一行的下面。
 *
 * <p><b>不做缩进、不加装饰符号。</b>
 * Minecraft 的字体不是等宽的，用空格去凑列对齐必然歪；「▍」「◆」这类字符要走 unifont 回退，
 * 宽度和普通字符对不上，行首放一个就会让整块看起来斜了一点。
 * 所以每一行都从最左边开始，靠颜色分层次而不是靠位置。
 *
 * <p>配色不用 {@code ChatFormatting}：那 16 个是原版聊天用的高饱和纯色，
 * 几种混在一起就是一股记事本味。这里用和专精界面同一套的金—褐色系，靠明度分主次。
 * 满级时主行的颜色特效由 {@link MasteryFlair} 负责。
 */
@EventBusSubscriber(modid = AlbionMastery.MOD_ID, value = Dist.CLIENT)
public final class MasteryTooltipHandler {

    /** 同一行里几段信息之间的分隔符。 */
    private static final String SEPARATOR = " · ";

    /** 标签色：压暗的金褐，用于「专精」这类说明字。 */
    private static final int COLOR_LABEL = 0x9C8E70;

    /** 明细色：最暗的一档，明细行整体退到背景里。 */
    private static final int COLOR_MUTED = 0x7A6E56;

    /** 警告色：橙而不是纯红，不至于在提示里炸眼。 */
    private static final int COLOR_WARN = 0xE08A3C;

    /**
     * 拔刀剑刀型那一行用的语言键前缀。
     *
     * <p>拔刀剑（Resharped）的 {@code appendSwordType} 会按刀的状态往提示里加
     * {@code slashblade.sword_type.bewitched}（妖）/ {@code .enchanted}（印）/
     * {@code .noname}（封）三者之一；封印状态（SEALED）的刀什么都不加。
     */
    private static final String SLASHBLADE_SWORD_TYPE_PREFIX = "slashblade.sword_type.";

    private MasteryTooltipHandler() {
    }

    /**
     * 插入专精提示文本。
     *
     * @param event 提示文本事件
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!MasteryConfig.CLIENT.showMasteryTooltip.get()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }

        ResolvedWeapon weapon = MasteryClientBonus.resolve(stack);
        if (weapon == null || WeaponKeys.PROVIDER_TACZ_GUN.equals(weapon.providerId())) {
            return;
        }

        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        MasteryHelper.Bonus bonus = MasteryHelper.computeBonus(
                ClientMasteryCache.data(), weapon, snapshot);
        // 没练过的武器什么都不加，别挂一行全是 0 的字
        if (bonus.total() <= 0.0D && bonus.weaponLevel() <= 0) {
            return;
        }

        // 铁魔法的法术容器：一本书里几个法术就几行，每行「法术名 · Lv · 加成」，插在名字下面
        if (WeaponResolver.isIronsLoaded() && IronsSpellsSupport.isContainer(stack)) {
            List<ResolvedWeapon> spells = IronsSpellsSupport.resolveAll(stack);
            if (spells.isEmpty()) {
                return;
            }
            List<Component> spellBlock = new ArrayList<>(spells.size());
            for (ResolvedWeapon spell : spells) {
                MasteryHelper.Bonus spellBonus = MasteryClientBonus.bonusFor(spell);
                if (spellBonus == null) {
                    continue;
                }
                spellBlock.add(spellLine(spell, spellBonus, snapshot.maxLevel));
            }
            List<Component> lines = event.getToolTip();
            lines.addAll(Math.min(1, lines.size()), spellBlock);
            return;
        }

        // 新生魔艺的施法物品：当前选中法术用到的每个符文一行，插在名字下面
        if (WeaponResolver.isArsLoaded() && ArsNouveauSupport.isCaster(stack)) {
            List<ResolvedWeapon> parts = ArsNouveauSupport.resolveAll(stack);
            if (parts.isEmpty()) {
                return;
            }
            List<Component> partBlock = new ArrayList<>(parts.size());
            for (ResolvedWeapon part : parts) {
                MasteryHelper.Bonus partBonus = MasteryClientBonus.bonusFor(part);
                if (partBonus == null) {
                    continue;
                }
                WeaponEntry entry = ClientWeaponRegistry.weaponByKey(part.weaponKey());
                Component name = entry != null ? entry.displayName()
                        : Component.literal(part.weaponId().toString());
                partBlock.add(namedLine(name, partBonus, snapshot.maxLevel));
            }
            List<Component> lines = event.getToolTip();
            lines.addAll(Math.min(1, lines.size()), partBlock);
            return;
        }

        // 先攒成一小块再整体插入：逐行 add(index) 要自己维护偏移量，容易插错位置
        List<Component> block = new ArrayList<>(4);

        // 主行：专精 Lv.12/100 · +18.5%，满级特效（三圈各一段、全满彩虹）在 MasteryFlair 里
        block.add(MasteryFlair.titleLine(bonus, snapshot.maxLevel));

        if (MasteryConfig.CLIENT.detailedTooltip.get()) {
            // 明细两行，每行两项。四项各占一行会把提示拉得老长，挤成一行又看不清
            block.add(detailPair(
                    "tooltip.albionmastery.detail_overall", bonus.overallLevel(), bonus.fromOverall(),
                    "tooltip.albionmastery.detail_type", bonus.typeLevel(), bonus.fromType()));
            block.add(detailPair(
                    "tooltip.albionmastery.detail_self", bonus.weaponLevel(), bonus.fromSelf(),
                    "tooltip.albionmastery.detail_others", bonus.typeLevelSum(), bonus.fromOthers()));
        }
        if (bonus.capped()) {
            block.add(text("tooltip.albionmastery.capped", COLOR_WARN));
        }

        List<Component> lines = event.getToolTip();
        lines.addAll(insertIndex(lines, weapon.providerId()), block);
    }

    /**
     * 法术容器里一个法术的那一行：法术名 · 专精 Lv.x/上限 · 加成。
     *
     * <p>名字用法术自己的语言键，和铁魔法书里显示的一致；满级特效沿用主行那一套。
     *
     * @param spell    法术
     * @param bonus    加成
     * @param maxLevel 等级上限
     * @return 一行
     */
    private static Component spellLine(ResolvedWeapon spell, MasteryHelper.Bonus bonus, int maxLevel) {
        String nameKey = "spell." + spell.weaponId().getNamespace() + "." + spell.weaponId().getPath();
        return namedLine(Component.translatable(nameKey), bonus, maxLevel);
    }

    /**
     * 「名字 · 专精 Lv.x/上限 · 加成」这种带名字的一行，给一件物品装着多件武器时用。
     *
     * @param name     武器名
     * @param bonus    加成
     * @param maxLevel 等级上限
     * @return 一行
     */
    private static Component namedLine(Component name, MasteryHelper.Bonus bonus, int maxLevel) {
        return name.copy().withStyle(color(COLOR_LABEL))
                .append(literal(SEPARATOR, COLOR_MUTED))
                .append(MasteryFlair.titleLine(bonus, maxLevel));
    }

    /**
     * 拼一行明细：两项并排，中间用分隔符隔开。
     *
     * @param leftKey    左项的语言键
     * @param leftLevel  左项的等级
     * @param leftValue  左项贡献的加成
     * @param rightKey   右项的语言键
     * @param rightLevel 右项的等级
     * @param rightValue 右项贡献的加成
     * @return 明细行
     */
    private static Component detailPair(String leftKey, int leftLevel, double leftValue,
                                        String rightKey, int rightLevel, double rightValue) {
        return detailPart(leftKey, leftLevel, leftValue)
                .append(literal(SEPARATOR, COLOR_MUTED))
                .append(detailPart(rightKey, rightLevel, rightValue));
    }

    /**
     * 拼明细里的一项，形如「总体 Lv.5 +5.0%」。
     *
     * @param key   语言键，参数是等级
     * @param level 等级
     * @param value 该项贡献的加成
     * @return 组件
     */
    private static MutableComponent detailPart(String key, int level, double value) {
        return Component.translatable(key, level).withStyle(color(COLOR_MUTED))
                .append(literal(" " + MasteryScreen.formatPercent(value), COLOR_LABEL));
    }

    /**
     * 造一个带颜色的可翻译组件。
     *
     * @param key   语言键
     * @param rgb   颜色
     * @return 组件
     */
    private static MutableComponent text(String key, int rgb) {
        return Component.translatable(key).withStyle(color(rgb));
    }

    /**
     * 造一个带颜色的字面组件。
     *
     * @param value 文本
     * @param rgb   颜色
     * @return 组件
     */
    private static MutableComponent literal(String value, int rgb) {
        return Component.literal(value).withStyle(color(rgb));
    }

    /**
     * 按 RGB 造一个只带颜色的样式。
     *
     * <p>注意传的是 24 位 RGB，不是界面调色板里那种带透明度的 ARGB——
     * 多出来的高 8 位会被当成颜色值的一部分，算出来的颜色是错的。
     *
     * @param rgb 颜色，形如 {@code 0xE8C25A}
     * @return 样式
     */
    private static Style color(int rgb) {
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb));
    }

    /**
     * 算专精信息块该插在第几行。
     *
     * <p>第 0 行固定是物品名。拔刀剑要找刀型那一行（「妖」「印」「封」）接在它下面；
     * 找不到（封印状态的刀没有这一行）就退回到名字下面。其它武器一律接在名字下面——
     * 直接 {@code add} 会落到属性栏、附魔、模组名之后，按住 Shift 展开属性时更是被顶到最下面。
     *
     * @param lines      当前的提示行
     * @param providerId 武器来源
     * @return 插入位置
     */
    private static int insertIndex(List<Component> lines, String providerId) {
        if (WeaponKeys.PROVIDER_SLASHBLADE.equals(providerId)) {
            for (int i = 0; i < lines.size(); i++) {
                if (isFrom(lines.get(i), SLASHBLADE_SWORD_TYPE_PREFIX)) {
                    return i + 1;
                }
            }
        }
        return Math.min(1, lines.size());
    }

    /**
     * 判断一行提示是不是由某个前缀的语言键渲染出来的。
     *
     * <p>会连子组件一起看：有些行是「标签 + 数值」拼起来的，
     * 只看最外层的内容类型会漏掉。
     *
     * @param component 提示行
     * @param keyPrefix 语言键前缀，例如 {@code slashblade.sword_type.}
     * @return 命中返回 true
     */
    private static boolean isFrom(Component component, String keyPrefix) {
        if (component.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().startsWith(keyPrefix)) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (isFrom(sibling, keyPrefix)) {
                return true;
            }
        }
        return false;
    }
}
