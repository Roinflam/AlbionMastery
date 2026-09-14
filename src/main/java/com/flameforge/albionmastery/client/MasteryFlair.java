package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.client.screen.MasteryScreen;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;

import java.util.function.IntUnaryOperator;

/**
 * 满级特效：提示文本里那一行「专精」怎么变色，以及界面里通用的彩虹 / 混色算法。
 *
 * <p>三圈各管一段，满哪一圈哪一段就亮：
 * <ul>
 *     <li><b>外圈</b>（这把武器满级）：等级数字在金色和白色之间呼吸；</li>
 *     <li><b>中圈</b>（武器类型满级）：加成百分比变成流动的金—橙渐变；</li>
 *     <li><b>内圈</b>（模组总体满级）：「专精」两个字从褐金亮成白金，逐字起伏；</li>
 *     <li><b>三圈全满</b>：整行逐字彩虹流动，末尾多一个「禅师」标记。</li>
 * </ul>
 *
 * <p>动画全部按 {@link Util#getMillis()} 算。提示文本是每帧重建的，所以颜色每帧都不一样，
 * 看起来就在动；成本是每帧多拆二十几个单字组件，只在鼠标停在武器上时发生，可以忽略。
 *
 * <p>只在客户端用。
 */
public final class MasteryFlair {

    /** 标签色：压暗的金褐。 */
    public static final int COLOR_LABEL = 0x9C8E70;

    /** 等级色：亮金。 */
    public static final int COLOR_VALUE = 0xFFE9A8;

    /** 加成数值色：金。 */
    public static final int COLOR_BONUS = 0xE8C25A;

    /** 明细色：最暗的一档。 */
    public static final int COLOR_MUTED = 0x7A6E56;

    /** 白金：金色系里最白最亮的一档。 */
    private static final int COLOR_GOLD_WHITE = 0xFFF7DE;

    /** 橙，和金色一起做流动渐变。 */
    private static final int COLOR_ORANGE = 0xFF9A3C;

    /** 白。 */
    private static final int COLOR_WHITE = 0xFFFFFF;

    /** 同一行里几段信息之间的分隔符。 */
    private static final String SEPARATOR = " · ";

    /** 彩虹走完一圈要多少毫秒。 */
    private static final double RAINBOW_CYCLE_MS = 2400.0D;

    /** 彩虹逐字的相位差，字越多整行的色带越长。 */
    private static final double RAINBOW_CHAR_STEP = 0.055D;

    private MasteryFlair() {
    }

    /**
     * 拼提示文本里的「专精」主行，并按满级情况上特效。
     *
     * @param bonus    加成明细
     * @param maxLevel 等级上限
     * @return 主行组件
     */
    public static MutableComponent titleLine(MasteryHelper.Bonus bonus, int maxLevel) {
        long now = Util.getMillis();
        boolean weaponMax = bonus.weaponLevel() >= maxLevel;
        boolean typeMax = bonus.typeLevel() >= maxLevel;
        boolean overallMax = bonus.overallLevel() >= maxLevel;

        String label = Component.translatable("tooltip.albionmastery.title").getString();
        String level = " Lv." + bonus.weaponLevel();
        String cap = "/" + maxLevel;
        String percent = MasteryScreen.formatPercent(bonus.total());

        if (weaponMax && typeMax && overallMax) {
            // 三圈全满：整行彩虹，逐字错开相位，看起来是一条色带在流
            String master = " " + Component.translatable("tooltip.albionmastery.master").getString();
            String whole = label + level + SEPARATOR + percent + master;
            return perChar(whole, i -> rainbow(now / RAINBOW_CYCLE_MS + i * RAINBOW_CHAR_STEP));
        }

        MutableComponent line;
        if (overallMax) {
            // 内圈满：标签在金和白金之间逐字起伏，整套只用金色系
            line = perChar(label, i -> blend(COLOR_BONUS, COLOR_GOLD_WHITE,
                    0.5F + 0.5F * (float) Math.sin(now / 500.0D + i * 0.9D)));
        } else {
            line = literal(label, COLOR_LABEL);
        }

        if (weaponMax) {
            // 外圈满：等级数字金白呼吸
            float pulse = 0.5F + 0.5F * (float) Math.sin(now / 380.0D);
            line.append(literal(level, blend(COLOR_VALUE, COLOR_WHITE, pulse)));
        } else {
            line.append(literal(level, COLOR_VALUE));
        }
        line.append(literal(cap, COLOR_MUTED));
        line.append(literal(SEPARATOR, COLOR_MUTED));

        if (typeMax) {
            // 中圈满：百分比金橙流动
            line.append(perChar(percent, i -> blend(COLOR_BONUS, COLOR_ORANGE,
                    0.5F + 0.5F * (float) Math.sin(now / 320.0D - i * 0.7D))));
        } else {
            line.append(literal(percent, COLOR_BONUS));
        }
        return line;
    }

    /**
     * 把一段文字拆成单字组件，每个字自己上色。
     *
     * @param text    文字
     * @param colorAt 第 i 个字该用的 24 位 RGB
     * @return 组件
     */
    public static MutableComponent perChar(String text, IntUnaryOperator colorAt) {
        MutableComponent result = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            result.append(literal(String.valueOf(text.charAt(i)), colorAt.applyAsInt(i)));
        }
        return result;
    }

    /**
     * 彩虹上的一个点。
     *
     * @param phase 相位，整数部分无所谓，只看小数
     * @return 24 位 RGB
     */
    public static int rainbow(double phase) {
        float hue = (float) (phase - Math.floor(phase));
        // 饱和度压一点，纯色彩虹在深色提示框上会刺眼
        return Mth.hsvToRgb(hue, 0.62F, 1.0F);
    }

    /**
     * 两个 24 位 RGB 之间按比例混色。
     *
     * @param from 起点
     * @param to   终点
     * @param t    0 取起点，1 取终点
     * @return 混出来的颜色
     */
    public static int blend(int from, int to, float t) {
        float k = Mth.clamp(t, 0.0F, 1.0F);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * 造一个带颜色的字面组件。
     *
     * @param value 文本
     * @param rgb   24 位 RGB
     * @return 组件
     */
    public static MutableComponent literal(String value, int rgb) {
        return Component.literal(value).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }
}
