package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.client.screen.MasteryPalette;
import com.flameforge.albionmastery.config.MasteryConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

/**
 * 升级提示。
 *
 * <p>屏幕正上方弹一条两行横幅：图标 + 名称 + 「层级 · Lv.旧 → 新」，全程不吃鼠标、不挡准星。
 *
 * <p>动画分四段：
 * <ol>
 *     <li><b>弹入</b>——整块从 0.75 倍放大到 1 倍并下滑，用 easeOutBack 收尾带一点回弹</li>
 *     <li><b>流光</b>——一道竖向高光从左扫到右，扫过金色边框时亮一下</li>
 *     <li><b>停留</b>——边框做低频呼吸，不至于是一块死图</li>
 *     <li><b>退出</b>——上移 + 淡出 + 轻微缩小</li>
 * </ol>
 *
 * <p>同时最多显示 {@value #MAX_VISIBLE} 条，连升好几级也不会糊满半个屏幕。
 */
public final class MasteryToastManager {

    /** 同屏最多显示几条。 */
    private static final int MAX_VISIBLE = 3;

    /** 弹入时长（毫秒）。 */
    private static final float ENTER_MS = 260.0F;

    /** 停留时长（毫秒）。 */
    private static final float HOLD_MS = 2000.0F;

    /** 退出时长（毫秒）。 */
    private static final float EXIT_MS = 380.0F;

    /** 流光从开始到扫完的时长（毫秒）。 */
    private static final float SWEEP_MS = 620.0F;

    /** 横幅高度。 */
    private static final int BANNER_HEIGHT = 30;

    /** 相邻两条之间的纵向间距。 */
    private static final int ROW_GAP = 4;

    /** 第一条距离屏幕顶部的距离。 */
    private static final int TOP_MARGIN = 12;

    /** 多图标轮换的间隔（毫秒）。横幅只活两秒多，换得比节点上快一些才看得到变化。 */
    private static final long ICON_CYCLE_MS = 520L;

    /** 单条横幅最多轮换几个图标，太多也看不过来。 */
    private static final int MAX_ICONS = 8;

    private static final List<Toast> ACTIVE = new ArrayList<>();

    /** 等待上屏的提示，已按优先级排好序。 */
    private static final List<Pending> PENDING = new ArrayList<>();

    /** 等待队列最多攒多少条，再多就丢掉末尾（优先级最低）的。 */
    private static final int MAX_PENDING = 9;

    /** 到达序号，同优先级按它保持先来先显示。 */
    private static long sequence = 0L;

    private MasteryToastManager() {
    }

    /**
     * 排一条升级提示。
     *
     * <p>不是立刻上屏：先进等待队列，按「外圈（武器）&gt; 中圈（类型）&gt; 内圈（来源）」
     * 排好序，屏幕上有空位时再一条条放出来，同时最多 {@value #MAX_VISIBLE} 条。
     * 一次击杀把一整本书的法术都升了级的时候，不会满屏横幅乱飞，也不会把重要的挤掉。
     * 队列超过 {@value #MAX_PENDING} 条时把优先级最低的丢掉——内圈升一级本来就是好几件武器
     * 升级的结果，少看一条不影响什么。
     *
     * @param priority 优先级，0 武器 / 1 类型 / 2 来源，越小越先显示
     * @param title    标题
     * @param tier     层级名
     * @param oldLevel 旧等级
     * @param newLevel 新等级
     * @param icons    轮换图标
     */
    public static void push(int priority, Component title, Component tier, int oldLevel, int newLevel,
                            List<ItemStack> icons) {
        boolean down = newLevel < oldLevel;
        List<ItemStack> usable = new ArrayList<>();
        if (icons != null) {
            for (ItemStack stack : icons) {
                if (stack != null && !stack.isEmpty()) {
                    usable.add(stack);
                    if (usable.size() >= MAX_ICONS) {
                        break;
                    }
                }
            }
        }
        // 学习点不是 1 的时候在横幅上挂个「×2」，让玩家知道涨得快慢是因为什么
        double study = ClientMasteryCache.data().getStudyMultiplier();
        Pending pending = new Pending(priority, sequence++, title, tier, oldLevel, newLevel,
                List.copyOf(usable), down, study);

        // 按优先级插到该在的位置，同优先级按到达先后；最后一条永远是最不重要的
        int insertAt = PENDING.size();
        for (int index = 0; index < PENDING.size(); index++) {
            if (PENDING.get(index).priority() > priority) {
                insertAt = index;
                break;
            }
        }
        PENDING.add(insertAt, pending);
        while (PENDING.size() > MAX_PENDING) {
            PENDING.remove(PENDING.size() - 1);
        }
    }

    /**
     * 有空位就从等待队列放一条上屏。
     *
     * @param now 当前时刻
     */
    private static void activatePending(long now) {
        while (ACTIVE.size() < MAX_VISIBLE && !PENDING.isEmpty()) {
            Pending pending = PENDING.remove(0);
            ACTIVE.add(new Toast(pending.title(), pending.tier(), pending.oldLevel(), pending.newLevel(),
                    pending.icons(), now, pending.down(), pending.study()));
            if (MasteryConfig.CLIENT.levelUpSound.get()) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player != null) {
                    // 音量压得很低，做成一声轻微的「叮」，不打断正在进行的战斗。
                    // 降级用低音调，不看屏幕也能听出方向不对
                    minecraft.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP,
                            0.3F, pending.down() ? 0.7F : 1.7F);
                }
            }
        }
    }

    /**
     * 清空，断线时调用。
     */
    public static void clear() {
        ACTIVE.clear();
        PENDING.clear();
    }

    /**
     * 渲染所有存活的提示。
     *
     * @param guiGraphics 绘图上下文
     */
    public static void render(GuiGraphics guiGraphics) {
        long now = Util.getMillis();
        float total = ENTER_MS + HOLD_MS + EXIT_MS;
        // 先把播完的清掉，腾出位子再从队列里放新的
        ACTIVE.removeIf(toast -> now - toast.bornAt >= total);
        activatePending(now);
        if (ACTIVE.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int index = 0;
        for (Toast toast : ACTIVE) {
            draw(guiGraphics, minecraft, toast, now - toast.bornAt, index);
            index++;
        }
    }

    /**
     * 画单条横幅。
     *
     * @param guiGraphics 绘图上下文
     * @param minecraft   客户端实例
     * @param toast       提示
     * @param elapsed     已存活时长
     * @param index       第几条，用于纵向堆叠
     */
    private static void draw(GuiGraphics guiGraphics, Minecraft minecraft,
                             Toast toast, float elapsed, int index) {
        float alpha;
        float scale;
        float slide;
        if (elapsed < ENTER_MS) {
            float t = elapsed / ENTER_MS;
            // easeOutBack：末尾越过 1 再回落，弹出来的手感
            float inv = t - 1.0F;
            float eased = 1.0F + inv * inv * (2.2F * inv + 1.2F);
            alpha = Math.min(1.0F, t * 1.6F);
            scale = 0.75F + 0.25F * eased;
            slide = (1.0F - eased) * -16.0F;
        } else if (elapsed < ENTER_MS + HOLD_MS) {
            alpha = 1.0F;
            scale = 1.0F;
            slide = 0.0F;
        } else {
            float t = (elapsed - ENTER_MS - HOLD_MS) / EXIT_MS;
            alpha = 1.0F - t;
            scale = 1.0F - 0.12F * t;
            slide = t * -12.0F;
        }
        alpha = Mth.clamp(alpha, 0.0F, 1.0F);
        if (alpha <= 0.01F) {
            return;
        }

        String name = toast.title.getString();
        String detail = toast.tier.getString() + "  Lv." + toast.oldLevel + " → " + toast.newLevel;
        int textWidth = Math.max(minecraft.font.width(name), minecraft.font.width(detail));
        ItemStack icon = toast.iconAt(elapsed);
        boolean hasIcon = !icon.isEmpty();
        // 挂了学习点标记就给右边多留出它的宽度，不然会压到文字上
        int badgeRoom = toast.hasStudyBadge()
                ? minecraft.font.width("\u00D7" + toast.studyText()) + 12 : 0;
        int width = 10 + (hasIcon ? 24 : 0) + textWidth + 12 + badgeRoom;

        int centerX = guiGraphics.guiWidth() / 2;
        int top = TOP_MARGIN + index * (BANNER_HEIGHT + ROW_GAP);

        guiGraphics.pose().pushPose();
        // 抬高一层，压在准星与物品栏之上
        guiGraphics.pose().translate(0.0F, 0.0F, 200.0F);
        // 以横幅中心为原点做缩放，弹出时才是从中间胀开而不是从左上角
        guiGraphics.pose().translate(centerX, top + BANNER_HEIGHT / 2.0F + slide, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.pose().translate(-width / 2.0F, -BANNER_HEIGHT / 2.0F, 0.0F);

        // 降级整条横幅换成橙色系：升级和降级长得一样的话，扫一眼分不出好事坏事
        int accent = toast.down() ? MasteryPalette.WARN : MasteryPalette.GOLD;
        int accentBright = toast.down() ? MasteryPalette.WARN : MasteryPalette.GOLD_BRIGHT;
        int back = alphaOf(MasteryPalette.PANEL, alpha * 0.94F);
        int gold = alphaOf(accent, alpha);
        int goldDark = alphaOf(toast.down() ? 0xFF5A3316 : MasteryPalette.GOLD_DARK, alpha);

        guiGraphics.fill(0, 0, width, BANNER_HEIGHT, back);

        // 停留阶段边框做一次低频呼吸，避免看着像一张死图
        float pulse = elapsed > ENTER_MS
                ? 0.75F + 0.25F * (float) Math.sin((elapsed - ENTER_MS) / 260.0D) : 1.0F;
        int borderTop = alphaOf(accentBright, alpha * pulse);
        guiGraphics.fill(0, 0, width, 1, borderTop);
        guiGraphics.fill(0, BANNER_HEIGHT - 1, width, BANNER_HEIGHT, goldDark);
        guiGraphics.fill(0, 0, 1, BANNER_HEIGHT, goldDark);
        guiGraphics.fill(width - 1, 0, width, BANNER_HEIGHT, goldDark);
        // 左侧一道竖金条，给等级提示一个视觉锚点
        guiGraphics.fill(0, 0, 2, BANNER_HEIGHT, gold);

        drawSweep(guiGraphics, elapsed, width, alpha, accentBright);

        int textX = 10;
        if (hasIcon) {
            guiGraphics.renderItem(icon, 8, (BANNER_HEIGHT - 16) / 2);
            textX = 34;
        }
        guiGraphics.drawString(minecraft.font, name, textX, 6,
                alphaOf(accentBright, alpha), false);
        guiGraphics.drawString(minecraft.font, detail, textX, 17,
                alphaOf(MasteryPalette.TEXT_DIM, alpha), false);
        if (toast.hasStudyBadge()) {
            String badge = "\u00D7" + toast.studyText();
            int badgeWidth = minecraft.font.width(badge);
            int badgeX = width - badgeWidth - 6;
            guiGraphics.fill(badgeX - 3, 5, width - 3, 16,
                    alphaOf(toast.study() > 1.0D ? MasteryPalette.GOLD_DARK : 0xFF5A3316, alpha));
            guiGraphics.drawString(minecraft.font, badge, badgeX, 7,
                    alphaOf(MasteryPalette.GOLD_BRIGHT, alpha), false);
        }

        guiGraphics.pose().popPose();
    }

    /**
     * 画一道从左扫到右的竖向高光。
     *
     * @param guiGraphics 绘图上下文
     * @param elapsed     已存活时长
     * @param width       横幅宽度
     * @param alpha       整体透明度
     */
    private static void drawSweep(GuiGraphics guiGraphics, float elapsed, int width, float alpha,
                                  int color) {
        if (elapsed > SWEEP_MS) {
            return;
        }
        float progress = elapsed / SWEEP_MS;
        // 起点在横幅左侧外面，终点在右侧外面，保证扫过全程
        float head = -20.0F + progress * (width + 40.0F);
        for (int i = 0; i < 10; i++) {
            float x = head - i * 2.0F;
            if (x < 1.0F || x > width - 1.0F) {
                continue;
            }
            float fade = (1.0F - i / 10.0F) * (1.0F - progress) * 0.55F * alpha;
            guiGraphics.fill((int) x, 1, (int) x + 2, BANNER_HEIGHT - 1, alphaOf(color, fade));
        }
    }

    /**
     * 把颜色的透明度替换成指定值。
     *
     * @param argb  原色
     * @param alpha 透明度，0~1
     * @return 新颜色
     */
    private static int alphaOf(int argb, float alpha) {
        int a = (int) (255.0F * Mth.clamp(alpha, 0.0F, 1.0F));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * 等待上屏的一条提示。
     *
     * @param priority 优先级，越小越先显示
     * @param sequence 到达序号
     * @param title    标题
     * @param tier     层级名
     * @param oldLevel 旧等级
     * @param newLevel 新等级
     * @param icons    轮换图标
     * @param down     是否降级
     * @param study    学习倍率
     */
    private record Pending(int priority, long sequence, Component title, Component tier,
                           int oldLevel, int newLevel, List<ItemStack> icons,
                           boolean down, double study) {
    }

    /**
     * 一条提示。
     *
     * @param title    名称
     * @param tier     层级说明
     * @param oldLevel 旧等级
     * @param newLevel 新等级
     * @param icons    可轮换的图标列表
     * @param bornAt   创建时刻
     */
    private record Toast(Component title, Component tier, int oldLevel, int newLevel,
                         List<ItemStack> icons, long bornAt, boolean down, double study) {

        /**
         * @return 是否要画学习点标记
         */
        boolean hasStudyBadge() {
            return Math.abs(this.study - 1.0D) > 1.0E-6D;
        }

        /**
         * 把学习点倍率写成好读的样子。
         *
         * @return 文本，整数不带小数点
         */
        String studyText() {
            if (this.study == Math.rint(this.study)) {
                return String.valueOf((long) this.study);
            }
            return String.format(Locale.ROOT, "%.2f", this.study)
                    .replaceAll("0+$", "").replaceAll("\\.$", "");
        }

        /**
         * 取当前该显示的图标。
         *
         * @param elapsed 已存活时长
         * @return 图标，列表为空时返回 {@link ItemStack#EMPTY}
         */
        ItemStack iconAt(float elapsed) {
            if (this.icons.isEmpty()) {
                return ItemStack.EMPTY;
            }
            int index = (int) ((long) (elapsed / ICON_CYCLE_MS) % this.icons.size());
            return this.icons.get(index);
        }
    }
}
