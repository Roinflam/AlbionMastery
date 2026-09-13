package com.flameforge.albionmastery.client.screen;

/**
 * 界面配色。
 *
 * <p>照阿尔比恩命运板的路子走：近黑的暖褐底 + 金色描边，
 * 解锁的节点发亮金光，未解锁的压成暗青铜色。
 *
 * <p>刻意不给每个来源随机分配色相——那样会变成一盘彩虹节点，
 * 阿尔比恩的整体感恰恰来自「全场只有一种金」。
 */
public final class MasteryPalette {

    /** 背景。 */
    public static final int BG = 0xF20B0906;

    /** 面板底色。 */
    public static final int PANEL = 0xF6130F09;

    /** 面板与分隔线的暗金。 */
    public static final int EDGE = 0xFF6B5223;

    /** 主文字（羊皮纸白）。 */
    public static final int TEXT = 0xFFEFE3C8;

    /** 次要文字。 */
    public static final int TEXT_DIM = 0xFF9C8E70;

    /** 主金色。 */
    public static final int GOLD = 0xFFE8C25A;

    /** 高亮金（选中、满级）。 */
    public static final int GOLD_BRIGHT = 0xFFFFE9A8;

    /** 暗金（未解锁的描边与连线）。 */
    public static final int GOLD_DARK = 0xFF5A4520;

    /** 节点底板（压在六边形下面的一圈深色）。 */
    public static final int NODE_BACKPLATE = 0xFF070604;

    /** 已解锁节点的填充。 */
    public static final int NODE_FILL = 0xFF2E2617;

    /** 未解锁节点的填充。 */
    public static final int NODE_FILL_LOCKED = 0xFF191510;

    /** 等级进度的填充色。 */
    public static final int PROGRESS = 0xFFB98F28;

    /** 已解锁的连线。 */
    public static final int LINE = 0xFFA07C33;

    /** 未解锁的连线。 */
    public static final int LINE_LOCKED = 0xFF453722;

    /** 警告色。 */
    public static final int WARN = 0xFFE08A3C;

    private MasteryPalette() {
    }
}
