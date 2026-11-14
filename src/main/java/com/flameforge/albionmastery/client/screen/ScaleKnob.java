package com.flameforge.albionmastery.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * 界面大小按钮：一个方形描边按钮，画着放大镜；点开在右边弹一张小卡片
 * 「界面大小 / － 4× ＋ 恢复自动 / 只改专精界面，记在本机」，调的是 {@link ScreenScale} 那一档。
 *
 * <p>罗盘和特化页各放一个，按钮位置由界面每帧用 {@link #place} 给（罗盘：左下按钮那一排末尾；特化页：左栏底部）。
 * 卡片下沿和按钮下沿对齐，左边离按钮 4 像素；超出窗口时往里收。</p>
 *
 * <p>加 / 减一次走一整档，到头变灰；「恢复自动」在自动档时变灰。改了档调界面给的 rescale 重新 init，
 * 这个对象是界面的字段、不随 init 重建，所以卡片保持展开。卡片开着时：点卡片里的空白不关，点卡片外只关卡片
 * （这一下不落到别的东西上），ESC 先关卡片。按钮松手时还在同一个按钮上才算。</p>
 *
 * <p>卡片里的尺寸都按字宽算：「－」「＋」13×12（字形 5 宽，左右各空 3），数字框、恢复自动按钮的字左右留白相等；
 * 三排之间各空 4 像素，四边留白 5 像素。</p>
 */
public final class ScaleKnob {

    private static final int PAD = 5;
    private static final int ROW_H = 12;
    private static final int SIGN_W = 13;
    private static final int CARD_GAP = 4;
    private static final int P_NONE = -1;
    private static final int P_KNOB = 0;
    private static final int P_MINUS = 1;
    private static final int P_PLUS = 2;
    private static final int P_AUTO = 3;
    private static final int BUTTON_BG = 0xFF130F09;
    private static final int BUTTON_BG_HOVER = 0xFF1E1810;
    private static final int BUTTON_BG_SUNK = 0xFF0B0906;
    private static final int OFF_EDGE = 0xFF3A2E18;
    private static final int OFF_TEXT = 0xFF6F634C;
    private static final int BOX_BG = 0xFF080704;
    /** 放大镜：奇数边长的按钮用 7×7，偶数边长用 8×8，这样上下左右留白相等。 */
    private static final String[] MAG_ODD = {
            ".xxx...",
            "x...x..",
            "x...x..",
            "x...x..",
            ".xxx...",
            "....xx.",
            ".....xx"};
    private static final String[] MAG_EVEN = {
            ".xxxx...",
            "x....x..",
            "x....x..",
            "x....x..",
            "x....x..",
            ".xxxx...",
            ".....xx.",
            "......xx"};

    private final Runnable rescale;
    private int x;
    private int y;
    private int size;
    private boolean open;
    private int press = P_NONE;

    /**
     * @param rescale 改了档之后怎么让界面重新排版（一般是重新 init）
     */
    public ScaleKnob(Runnable rescale) {
        this.rescale = rescale;
    }

    /** 这一帧按钮放在哪、多大（正方形）。 */
    public void place(int x, int y, int size) {
        this.x = x;
        this.y = y;
        this.size = size;
    }

    public boolean isOpen() {
        return this.open;
    }

    public void close() {
        this.open = false;
        this.press = P_NONE;
    }

    /** 鼠标在按钮或展开的卡片上（界面拿来屏蔽底下东西的悬停）。 */
    public boolean contains(double mx, double my) {
        return inKnob(mx, my) || inCard(mx, my);
    }

    // 几何

    private record Card(int x, int y, int w, int h, int boxW, int autoW) {
    }

    private Card card() {
        Font font = Minecraft.getInstance().font;
        int boxW = tw(font, ScreenScale.label(8)) + 10;
        int autoW = tw(font, I18n.get("gui.albionmastery.scale.reset")) + 12;
        // 第二排：－ 数字框 ＋ 共用边线（各叠 1 像素），再空 8 像素放「恢复自动」
        int row = SIGN_W - 1 + boxW - 1 + SIGN_W + 8 + autoW;
        int head = tw(font, I18n.get("gui.albionmastery.scale.title")) + 8
                + tw(font, I18n.get("gui.albionmastery.scale.auto_is", ScreenScale.label(8)));
        int note = tw(font, I18n.get("gui.albionmastery.scale.note"));
        int w = PAD * 2 + Math.max(row, Math.max(head, note));
        int h = PAD + 8 + 4 + ROW_H + 4 + 8 + PAD;
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int cx = Math.min(this.x + this.size + CARD_GAP, sw - w - 2);
        int cy = Math.max(2, this.y + this.size - h);
        return new Card(cx, cy, w, h, boxW, autoW);
    }

    private int rowY(Card c) {
        return c.y + PAD + 8 + 4;
    }

    private int bx(Card c, int k) {
        return switch (k) {
            case P_MINUS -> c.x + PAD;
            case P_PLUS -> c.x + PAD + SIGN_W - 1 + c.boxW - 1;
            case P_AUTO -> c.x + c.w - PAD - c.autoW;
            default -> c.x + PAD + SIGN_W - 1;
        };
    }

    private static int bw(Card c, int k) {
        return k == P_AUTO ? c.autoW : k == P_MINUS || k == P_PLUS ? SIGN_W : c.boxW;
    }

    private boolean inKnob(double mx, double my) {
        return this.size > 0 && mx >= this.x && my >= this.y && mx < this.x + this.size && my < this.y + this.size;
    }

    private boolean inCard(double mx, double my) {
        if (!this.open) {
            return false;
        }
        Card c = card();
        return mx >= c.x && my >= c.y && mx < c.x + c.w && my < c.y + c.h;
    }

    private int buttonAt(Card c, double mx, double my) {
        int ry = rowY(c);
        if (!this.open || my < ry || my >= ry + ROW_H) {
            return P_NONE;
        }
        for (int k : new int[]{P_MINUS, P_PLUS, P_AUTO}) {
            if (mx >= bx(c, k) && mx < bx(c, k) + bw(c, k)) {
                return k;
            }
        }
        return P_NONE;
    }

    private static boolean enabled(int k) {
        Minecraft mc = Minecraft.getInstance();
        return switch (k) {
            case P_MINUS -> ScreenScale.canBump(mc, -1);
            case P_PLUS -> ScreenScale.canBump(mc, 1);
            case P_AUTO -> !ScreenScale.isAuto();
            default -> true;
        };
    }

    // 画

    /** 画按钮，展开时画卡片、悬停时画一张小提示。调用方负责把 z 抬到别的东西上面。 */
    public void render(GuiGraphics g, int mx, int my) {
        if (this.size <= 0) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean hover = inKnob(mx, my);
        boolean sunk = this.open || (this.press == P_KNOB && hover);
        g.fill(this.x, this.y, this.x + this.size, this.y + this.size,
                sunk ? BUTTON_BG_SUNK : hover ? BUTTON_BG_HOVER : BUTTON_BG);
        frame(g, this.x, this.y, this.size, this.size, hover || this.open ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK);
        String[] mag = this.size % 2 == 0 ? MAG_EVEN : MAG_ODD;
        int off = (this.size - mag.length) / 2;
        pix(g, mag, this.x + off, this.y + off, hover || this.open ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT);
        if (this.open) {
            drawCard(g, font, mx, my);
        } else if (hover && this.press == P_NONE) {
            drawTip(g, font);
        }
    }

    private void drawTip(GuiGraphics g, Font font) {
        Minecraft mc = Minecraft.getInstance();
        String a = I18n.get("gui.albionmastery.scale.tip", ScreenScale.label(ScreenScale.current(mc)));
        String b = I18n.get("gui.albionmastery.scale.tip_click");
        int w = PAD * 2 + Math.max(tw(font, a), tw(font, b));
        int h = PAD * 2 + 8 + 3 + 8;
        int sw = mc.getWindow().getGuiScaledWidth();
        int tx = Math.min(this.x + this.size + CARD_GAP, sw - w - 2);
        int ty = Math.max(2, this.y + this.size - h);
        g.fill(tx, ty, tx + w, ty + h, MasteryPalette.PANEL);
        frame(g, tx, ty, w, h, MasteryPalette.GOLD_DARK);
        g.drawString(font, a, tx + PAD, ty + PAD, MasteryPalette.TEXT, false);
        g.drawString(font, b, tx + PAD, ty + PAD + 8 + 3, MasteryPalette.TEXT_DIM, false);
    }

    private void drawCard(GuiGraphics g, Font font, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        Card c = card();
        g.fill(c.x, c.y, c.x + c.w, c.y + c.h, MasteryPalette.PANEL);
        frame(g, c.x, c.y, c.w, c.h, MasteryPalette.EDGE);
        // 第一排：左「界面大小」，右「自动」或「自动是 N×」（右边和卡片内边对齐）
        g.drawString(font, I18n.get("gui.albionmastery.scale.title"), c.x + PAD, c.y + PAD, MasteryPalette.GOLD, false);
        String right = ScreenScale.isAuto() ? I18n.get("gui.albionmastery.scale.auto")
                : I18n.get("gui.albionmastery.scale.auto_is", ScreenScale.label(ScreenScale.autoScale(mc)));
        g.drawString(font, right, c.x + c.w - PAD - tw(font, right), c.y + PAD, MasteryPalette.TEXT_DIM, false);
        // 第二排
        int ry = rowY(c);
        int hb = buttonAt(c, mx, my);
        int nx = bx(c, -1);
        g.fill(nx, ry, nx + c.boxW, ry + ROW_H, BOX_BG);
        frame(g, nx, ry, c.boxW, ROW_H, MasteryPalette.GOLD_DARK);
        String n = ScreenScale.label(ScreenScale.current(mc));
        g.drawString(font, n, nx + (c.boxW - tw(font, n)) / 2, ry + (ROW_H - 8) / 2, MasteryPalette.GOLD_BRIGHT, false);
        // －、＋ 后画，和数字框共用的那条边线归按钮：悬停时整圈亮金、变灰时整圈暗边
        button(g, font, c, P_MINUS, ry, hb, "-");
        button(g, font, c, P_PLUS, ry, hb, "+");
        button(g, font, c, P_AUTO, ry, hb, I18n.get("gui.albionmastery.scale.reset"));
        // 第三排
        g.drawString(font, I18n.get("gui.albionmastery.scale.note"), c.x + PAD, ry + ROW_H + 4,
                MasteryPalette.TEXT_DIM, false);
    }

    /** 描边小按钮，和特化页的按钮一个画法：悬停亮金、灰的用暗边暗字；字按亮像素宽居中、余数往左。 */
    private void button(GuiGraphics g, Font font, Card c, int k, int ry, int hb, String label) {
        boolean dis = !enabled(k);
        boolean hov = hb == k && !dis;
        int bx = bx(c, k);
        int w = bw(c, k);
        boolean prs = hov && this.press == k;
        g.fill(bx, ry, bx + w, ry + ROW_H, dis ? BUTTON_BG : prs ? BUTTON_BG_SUNK : hov ? BUTTON_BG_HOVER : BUTTON_BG);
        frame(g, bx, ry, w, ROW_H, dis ? OFF_EDGE : hov ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK);
        int color = dis ? OFF_TEXT : hov ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT;
        g.drawString(font, label, bx + (w - tw(font, label)) / 2, ry + (ROW_H - 8) / 2, color, false);
    }

    // 输入

    /** 界面的 mouseClicked 最先调这里；返回 true 表示这一下归按钮 / 卡片，界面别再处理。 */
    public boolean mouseClicked(double mx, double my, int btn) {
        if (this.size <= 0) {
            return false;
        }
        if (inKnob(mx, my)) {
            this.press = btn == 0 ? P_KNOB : P_NONE;
            return true;
        }
        if (!this.open) {
            return false;
        }
        Card c = card();
        if (!inCard(mx, my)) {
            close();
            return true;
        }
        int k = btn == 0 ? buttonAt(c, mx, my) : P_NONE;
        this.press = k != P_NONE && enabled(k) ? k : P_NONE;
        return true;
    }

    /** 界面的 mouseReleased 最先调这里；按下时归了按钮的，松手也归它。 */
    public boolean mouseReleased(double mx, double my, int btn) {
        int p = this.press;
        if (p == P_NONE) {
            return false;
        }
        this.press = P_NONE;
        if (p == P_KNOB) {
            if (inKnob(mx, my)) {
                this.open = !this.open;
                click();
            }
            return true;
        }
        if (buttonAt(card(), mx, my) != p || !enabled(p)) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean changed = p == P_AUTO ? ScreenScale.reset() : ScreenScale.bump(mc, p == P_PLUS ? 1 : -1);
        click();
        if (changed) {
            this.rescale.run();
        }
        return true;
    }

    /** 卡片开着时 ESC 只关卡片。 */
    public boolean keyPressed(int key) {
        if (this.open && key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return false;
    }

    // 小工具

    /** 字的亮像素宽：font.width 含末尾 1 像素字距，扣掉。 */
    private static int tw(Font font, String s) {
        return Math.max(0, font.width(s) - 1);
    }

    private static void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private static void pix(GuiGraphics g, String[] rows, int x, int y, int color) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            for (int i = 0; i < row.length(); i++) {
                if (row.charAt(i) == 'x') {
                    g.fill(x + i, y + r, x + i + 1, y + r + 1, color);
                }
            }
        }
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
