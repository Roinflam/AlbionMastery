package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.client.ClientMasteryCache;
import com.flameforge.albionmastery.client.rank.ClientRank;
import com.flameforge.albionmastery.client.rank.RankEmblem;
import com.flameforge.albionmastery.client.weapon.ClientWeaponRegistry;
import com.flameforge.albionmastery.client.weapon.WeaponSource;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.rank.MasteryRank;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 段位之路：专精界面右上角的段位牌点进来的一页。
 *
 * <p>全部段位从左到右排成一条路，起点是「无等级」，终点是当前装的全部武器练满级时能到的段位。
 * 每个包装的枪包、刀包、法术模组不一样，武器件数不一样，终点也跟着变，所以终点是按当前武器清单现算的。
 * 走过的段位点亮，当前段位放大带光圈，连线按到下一段的进度填一截。
 * 打开时进度从起点一路填到当前位置，镜头跟着走，经过的徽章依次亮起。
 *
 * <p>滚轮、拖动、方向键横着看；底下的小地图是整条路的缩略，点哪跳哪；Home 回到当前段位，End 看终点。
 * 看别人的罗盘时点进来，看的是别人的段位。
 */
public class RankTrackScreen extends Screen {

    private static final int HEADER_HEIGHT = 20;
    private static final int BACK_WIDTH = 52;
    private static final int MARGIN = 10;

    /** 顶部概况条。 */
    private static final int SUMMARY_TOP = HEADER_HEIGHT + 6;
    private static final int SUMMARY_HEIGHT = 40;
    private static final int SUMMARY_EMBLEM = 32;

    /** 相邻两个段位节点的间距。段位名最长五个字，0.75 倍下约 34 像素，留出空隙。 */
    private static final int SPACING = 50;
    /** 路两头留白，起点和终点的字不贴边。 */
    private static final int PAD = 34;
    private static final float EMBLEM = 24.0F;
    private static final float EMBLEM_CURRENT = 34.0F;
    private static final float EMBLEM_END = 28.0F;
    /** 没走到的段位徽章：半透明再压暗。 */
    private static final float LOCKED_ALPHA = 0.7F;
    private static final float LOCKED_BRIGHTNESS = 0.4F;

    private static final int MINIMAP_HEIGHT = 7;
    private static final int FOOTER_HEIGHT = 12;
    private static final int EDGE_FADE = 26;
    private static final float SMALL = 0.75F;

    private static final int TOOLTIP_PAD_X = 5;
    private static final int TOOLTIP_PAD_Y = 4;
    private static final int LINE = 10;

    /** 没走到的段位名的颜色：比 TEXT_DIM 再暗一档，一眼分出走过和没走过。 */
    private static final int TEXT_LOCKED = 0xFF5E5442;

    private final MasteryData data;
    @Nullable
    private final Screen parent;

    // ---- 打开时算一次：武器清单不会在这一页里变 ----
    private int weaponCount;
    private long endLevels;
    private long endXp;

    // ---- 每秒刷新一次：开着这页时服务端可能又同步了等级 ----
    private long xp;
    private int rank;
    /** 终点段位。现有段位已经超过按清单算的终点（比如删过枪包），终点就是现有段位。 */
    private int endRank;
    private int refreshTicks;

    /** 横向偏移：路的最左端（含留白）到可视区左边的距离。 */
    private float scroll;
    private float scrollTarget;
    private long lastFrame;

    /** 打开动画：进度从起点填到当前位置。任何操作都会让它立刻填满。 */
    private long openedAt;
    private boolean animating = true;

    private boolean draggingTrack;
    private boolean draggingMinimap;
    private double dragDistance;

    /**
     * @param data   要看的专精数据
     * @param owner  这份数据的主人；传 null 表示是自己的
     * @param parent 上一层界面，ESC 和「返回」回到它
     */
    public RankTrackScreen(MasteryData data, @Nullable Component owner, @Nullable Screen parent) {
        super(owner == null
                ? Component.translatable("gui.albionmastery.rank_track.title")
                : Component.translatable("gui.albionmastery.rank_track.title_viewing", owner));
        this.data = data;
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        if (this.openedAt == 0L) {
            countWeapons();
            refresh();
            this.openedAt = Util.getMillis();
            this.lastFrame = this.openedAt;
            this.scroll = clampScroll(0.0F);
            this.scrollTarget = this.scroll;
        } else {
            // 改窗口大小：可视宽度变了，镜头按新宽度重新夹一次
            this.scroll = clampScroll(this.scroll);
            this.scrollTarget = clampScroll(this.scrollTarget);
        }
    }

    /**
     * 数武器清单，算终点：全部武器都练到满级时的段位经验。
     */
    private void countWeapons() {
        int count = 0;
        for (WeaponSource source : ClientWeaponRegistry.builtSources()) {
            count += source.allWeapons().size();
        }
        this.weaponCount = count;
        int maxLevel = Math.max(1, ClientMasteryCache.snapshot().maxLevel);
        this.endLevels = (long) count * maxLevel;
        this.endXp = ClientRank.rankXpForLevelSum(this.endLevels);
    }

    private void refresh() {
        this.xp = ClientRank.rankXpOf(this.data);
        this.rank = MasteryRank.rankFor(this.xp);
        int fromWeapons = this.weaponCount > 0 ? MasteryRank.rankFor(this.endXp) : MasteryRank.NAMED_RANKS;
        // 至少比现在高一段，路才有往前的方向；清单为空（还没建好）时先画到至崇宗师
        this.endRank = Math.max(Math.max(fromWeapons, this.rank), 1);
    }

    @Override
    public void tick() {
        super.tick();
        if (++this.refreshTicks >= 20) {
            this.refreshTicks = 0;
            refresh();
        }
    }

    // ======================================================================
    // 布局
    // ======================================================================

    private int trackLeft() {
        return MARGIN;
    }

    private int trackRight() {
        return this.width - MARGIN;
    }

    private int viewWidth() {
        return trackRight() - trackLeft();
    }

    private int trackTop() {
        return SUMMARY_TOP + SUMMARY_HEIGHT + 4;
    }

    private int minimapTop() {
        return this.height - FOOTER_HEIGHT - MINIMAP_HEIGHT - 4;
    }

    private int trackBottom() {
        return minimapTop() - 6;
    }

    /**
     * 路所在的水平线。上面留段位分组和门槛，下面留段位名和编号，两边差不多高，取中间略偏下。
     */
    private int lineY() {
        return (trackTop() + trackBottom()) / 2 + 3;
    }

    /**
     * @return 整条路（含两头留白）的宽度
     */
    private float contentWidth() {
        return this.endRank * (float) SPACING + PAD * 2.0F;
    }

    /**
     * 把偏移夹到合法范围。整条路放得下时居中，不让拖。
     */
    private float clampScroll(float value) {
        float overflow = contentWidth() - viewWidth();
        if (overflow <= 0.0F) {
            return overflow / 2.0F;
        }
        return Mth.clamp(value, 0.0F, overflow);
    }

    /**
     * @param position 段位位置，可以带小数（当前段位 + 到下一段的进度）
     * @return 屏幕 X
     */
    private float xOf(float position) {
        return trackLeft() + PAD + position * SPACING - this.scroll;
    }

    /**
     * 让某个段位位置落在可视区正中的偏移。
     */
    private float scrollToCenter(float position) {
        return clampScroll(PAD + position * SPACING - viewWidth() / 2.0F);
    }

    /**
     * @return 当前位置：段位 + 到下一段的进度。到了终点或上限就停在段位上
     */
    private float currentPosition() {
        if (this.rank >= this.endRank || this.rank >= MasteryRank.MAX_RANK) {
            return this.rank;
        }
        return this.rank + MasteryRank.progress(this.xp);
    }

    /**
     * @return 打开动画的时长：段位越高路越长，最长 1.8 秒
     */
    private float animationSeconds() {
        return Mth.clamp(0.5F + currentPosition() * 0.035F, 0.5F, 1.8F);
    }

    /**
     * @return 这一帧进度填到哪：动画中按缓出曲线从 0 走到当前位置，动画结束就是当前位置
     */
    private float shownPosition(long now) {
        float target = currentPosition();
        if (!this.animating) {
            return target;
        }
        float t = (now - this.openedAt) / 1000.0F / animationSeconds();
        if (t >= 1.0F) {
            this.animating = false;
            return target;
        }
        float eased = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
        return target * eased;
    }

    private void stopAnimation() {
        this.animating = false;
    }

    // ======================================================================
    // 绘制
    // ======================================================================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = Mth.clamp((now - this.lastFrame) / 1000.0F, 0.0F, 0.1F);
        this.lastFrame = now;

        float shown = shownPosition(now);
        if (this.animating) {
            // 镜头跟着进度头走
            this.scroll = scrollToCenter(shown);
            this.scrollTarget = this.scroll;
        } else if (!this.draggingTrack && !this.draggingMinimap) {
            // 滚轮、按键改的是目标，这里平滑追过去
            float k = 1.0F - (float) Math.exp(-dt * 14.0F);
            this.scroll += (this.scrollTarget - this.scroll) * k;
            if (Math.abs(this.scrollTarget - this.scroll) < 0.3F) {
                this.scroll = this.scrollTarget;
            }
        }

        guiGraphics.fill(0, 0, this.width, this.height, MasteryPalette.BG);
        drawHeader(guiGraphics, mouseX, mouseY);
        drawSummary(guiGraphics, shown);

        int hovered = hoveredNode(mouseX, mouseY);
        guiGraphics.enableScissor(trackLeft(), trackTop(), trackRight(), trackBottom());
        drawTrack(guiGraphics, shown, hovered, now);
        drawEdgeFade(guiGraphics);
        guiGraphics.disableScissor();

        drawMinimap(guiGraphics, shown, mouseX, mouseY);
        drawFooter(guiGraphics);
        if (hovered >= 0 && !this.draggingTrack) {
            drawNodeTooltip(guiGraphics, hovered, mouseX, mouseY);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void drawHeader(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.fill(0, 0, this.width, HEADER_HEIGHT, MasteryPalette.PANEL);
        guiGraphics.fill(0, HEADER_HEIGHT - 1, this.width, HEADER_HEIGHT, MasteryPalette.EDGE);
        int textY = (HEADER_HEIGHT - this.font.lineHeight) / 2 + 1;
        guiGraphics.drawString(this.font, this.title, 10, textY, MasteryPalette.GOLD_BRIGHT, false);
        boolean hover = isOnBack(mouseX, mouseY);
        int x = backX();
        int y = 2;
        int h = HEADER_HEIGHT - 4;
        guiGraphics.fill(x, y, x + BACK_WIDTH, y + h, hover ? 0xF01E1810 : 0xC0130F09);
        int border = hover ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK;
        guiGraphics.fill(x, y, x + BACK_WIDTH, y + 1, border);
        guiGraphics.fill(x, y + h - 1, x + BACK_WIDTH, y + h, border);
        guiGraphics.fill(x, y, x + 1, y + h, border);
        guiGraphics.fill(x + BACK_WIDTH - 1, y, x + BACK_WIDTH, y + h, border);
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.albionmastery.spec_page.back"),
                x + BACK_WIDTH / 2, y + (h - 8) / 2, hover ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT);
    }

    private int backX() {
        return this.width - BACK_WIDTH - 6;
    }

    private boolean isOnBack(double mouseX, double mouseY) {
        return mouseX >= backX() && mouseX < backX() + BACK_WIDTH && mouseY >= 2 && mouseY < HEADER_HEIGHT - 2;
    }

    /**
     * 顶部概况：左边是当前段位，右边是终点和走了多少。
     */
    private void drawSummary(GuiGraphics guiGraphics, float shown) {
        int left = MARGIN;
        int right = this.width - MARGIN;
        int top = SUMMARY_TOP;
        int bottom = top + SUMMARY_HEIGHT;
        int primary = RankEmblem.primaryColor(this.rank);
        guiGraphics.fill(left, top, right, bottom, MasteryPalette.PANEL);
        guiGraphics.fill(left, top, right, top + 1, MasteryPalette.GOLD_DARK);
        guiGraphics.fill(left, bottom - 1, right, bottom, MasteryPalette.GOLD_DARK);
        guiGraphics.fill(right - 1, top, right, bottom, MasteryPalette.GOLD_DARK);
        guiGraphics.fill(left, top, left + 2, bottom, primary);

        RankEmblem.draw(guiGraphics, this.rank, left + 6 + SUMMARY_EMBLEM / 2.0F, top + SUMMARY_HEIGHT / 2.0F,
                SUMMARY_EMBLEM, 1.0F, RankEmblem.clockSeconds());

        // ---- 左：当前段位 ----
        int textX = left + 12 + SUMMARY_EMBLEM;
        String name = MasteryRank.name(this.rank).getString();
        guiGraphics.drawString(this.font, name, textX, top + 6, primary, false);
        String rankLabel = I18n.get("gui.albionmastery.rank.badge.rank", this.rank);
        drawSmall(guiGraphics, rankLabel, textX + this.font.width(name) + 5, top + 7, MasteryPalette.TEXT_DIM);
        drawSmall(guiGraphics, I18n.get("gui.albionmastery.rank.tooltip.xp", digits(this.xp)),
                textX, top + 18, MasteryPalette.TEXT);
        String next;
        if (this.rank >= MasteryRank.MAX_RANK) {
            next = I18n.get("gui.albionmastery.rank.tooltip.max");
        } else if (this.rank >= this.endRank) {
            next = I18n.get("gui.albionmastery.rank_track.at_end");
        } else {
            next = I18n.get("gui.albionmastery.rank_track.next", MasteryRank.name(this.rank + 1).getString(),
                    digits(MasteryRank.xpForRank(this.rank + 1) - this.xp));
        }
        drawSmall(guiGraphics, next, textX, top + 27, MasteryPalette.TEXT_DIM);
        int leftEnd = textX + Math.round(Math.max(
                this.font.width(name) + 5 + this.font.width(rankLabel) * SMALL,
                Math.max(this.font.width(next), this.font.width(I18n.get("gui.albionmastery.rank.tooltip.xp",
                        digits(this.xp)))) * SMALL));

        // ---- 右：终点和总进度 ----
        int endPrimary = RankEmblem.primaryColor(this.endRank);
        String endName = MasteryRank.name(this.endRank).getString();
        String endPrefix = I18n.get("gui.albionmastery.rank_track.end") + " ";
        String endRankLabel = " " + I18n.get("gui.albionmastery.rank.badge.rank", this.endRank);
        String detail = this.weaponCount > 0
                ? I18n.get("gui.albionmastery.rank_track.end_detail", this.weaponCount, digits(this.endLevels))
                : I18n.get("gui.albionmastery.rank_track.end_unknown");
        int blockWidth = Math.max(this.font.width(endPrefix + endName) + Math.round(this.font.width(endRankLabel) * SMALL),
                Math.round(this.font.width(detail) * SMALL));
        blockWidth = Math.max(blockWidth, 120);
        int blockRight = right - 8;
        int blockLeft = blockRight - blockWidth;
        if (blockLeft < leftEnd + 12) {
            // 窄屏放不下右半边，只留一条总进度条
            blockLeft = Math.min(blockRight - 60, leftEnd + 12);
            if (blockLeft >= blockRight - 30) {
                return;
            }
        } else {
            guiGraphics.drawString(this.font, endPrefix, blockLeft, top + 6, MasteryPalette.TEXT_DIM, false);
            int cursor = blockLeft + this.font.width(endPrefix);
            guiGraphics.drawString(this.font, endName, cursor, top + 6, endPrimary, false);
            drawSmall(guiGraphics, endRankLabel, cursor + this.font.width(endName), top + 7, MasteryPalette.TEXT_DIM);
            drawSmall(guiGraphics, detail, blockLeft, top + 18, MasteryPalette.TEXT_DIM);
        }

        // 总进度条：按段位经验算，和动画同步走
        float total = this.endXp <= 0L ? 0.0F : (float) Math.min(1.0D, (double) this.xp / this.endXp);
        float target = currentPosition();
        float shownRatio = target <= 0.0F ? total : total * Mth.clamp(shown / target, 0.0F, 1.0F);
        String percent = String.format(Locale.ROOT, "%.1f%%", shownRatio * 100.0F);
        int barTop = top + 29;
        int percentWidth = Math.round(this.font.width(percent) * SMALL);
        int barRight = blockRight - percentWidth - 4;
        guiGraphics.fill(blockLeft, barTop, barRight, barTop + 3, 0xFF2E2617);
        int fill = blockLeft + Math.round((barRight - blockLeft) * shownRatio);
        if (fill > blockLeft) {
            guiGraphics.fill(blockLeft, barTop, fill, barTop + 3, MasteryPalette.PROGRESS);
            guiGraphics.fill(blockLeft, barTop, fill, barTop + 1, MasteryPalette.GOLD);
        }
        drawSmall(guiGraphics, percent, blockRight - percentWidth, barTop - 1, MasteryPalette.GOLD);
    }

    /**
     * 中间的路：段位分组、连线、徽章、门槛和名字。
     */
    private void drawTrack(GuiGraphics guiGraphics, float shown, int hovered, long now) {
        int lineY = lineY();
        int from = Math.max(0, (int) Math.floor((this.scroll - PAD) / SPACING) - 1);
        int to = Math.min(this.endRank, (int) Math.ceil((this.scroll + viewWidth()) / SPACING) + 1);
        float time = RankEmblem.clockSeconds();
        int shownRank = (int) Math.floor(shown + 1.0E-4F);

        drawTierBands(guiGraphics, from, to, lineY, shownRank);

        // ---- 连线：每两个段位之间一段，在徽章处断开，徽章镂空的地方不透出线 ----
        // 底下一段暗线，上面按进度填亮
        float startX = xOf(0.0F);
        float endX = xOf(this.endRank);
        float headX = xOf(shown);
        for (int i = Math.max(0, from - 1); i < Math.min(to + 1, this.endRank); i++) {
            int x0 = Math.round(xOf(i) + nodeSize(i, shown, hovered) / 2.0F + 2.0F);
            int x1 = Math.round(xOf(i + 1) - nodeSize(i + 1, shown, hovered) / 2.0F - 2.0F);
            if (x1 <= x0) {
                continue;
            }
            guiGraphics.fill(x0, lineY - 1, x1, lineY + 1, MasteryPalette.LINE_LOCKED);
            int lit = Math.min(x1, Math.round(headX));
            if (lit > x0) {
                guiGraphics.fill(x0, lineY - 1, lit, lineY + 1, MasteryPalette.PROGRESS);
                guiGraphics.fill(x0, lineY - 1, lit, lineY, MasteryPalette.GOLD);
            }
        }

        Matrix4f matrix = guiGraphics.pose().last().pose();
        // 光：进度头的亮点、当前段位的呼吸光圈、终点的金圈
        float pulse = 0.75F + 0.25F * Mth.sin(time * 2.6F);
        BufferBuilder buffer = RenderUtil.beginAdditive();
        boolean moving = shown < currentPosition() - 1.0E-3F || this.animating;
        if (headX > startX && (moving || shown < this.endRank)) {
            RenderUtil.radialGradient(buffer, matrix, headX, lineY, 9.0F,
                    RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT, 0.75F), MasteryPalette.GOLD_BRIGHT & 0x00FFFFFF);
        }
        if (!moving && this.rank >= from && this.rank <= to) {
            int primary = RankEmblem.primaryColor(this.rank);
            RenderUtil.radialGradient(buffer, matrix, xOf(this.rank), lineY, EMBLEM_CURRENT * 0.95F,
                    RenderUtil.withAlpha(primary, 0.42F * pulse), primary & 0x00FFFFFF);
            RenderUtil.softRing(buffer, matrix, xOf(this.rank), lineY, EMBLEM_CURRENT * 0.58F, 1.6F,
                    RenderUtil.withAlpha(primary, 0.9F * pulse));
        }
        if (this.endRank >= from && this.endRank <= to && this.endRank != this.rank) {
            RenderUtil.softRing(buffer, matrix, endX, lineY, EMBLEM_END * 0.62F, 1.4F,
                    RenderUtil.withAlpha(MasteryPalette.GOLD, shownRank >= this.endRank ? 0.9F : 0.45F));
        }
        RenderUtil.endAdditive();

        for (int i = from; i <= to; i++) {
            boolean reached = i <= shownRank;
            float size = nodeSize(i, shown, hovered);
            if (reached) {
                RankEmblem.draw(guiGraphics, i, xOf(i), lineY, size, 1.0F, i == this.rank ? time : 0.0F);
            } else {
                // 光调透明度压不下金属高光，没走到的连颜色一起压暗，和走过的拉开
                RankEmblem.drawDimmed(guiGraphics, i, xOf(i), lineY, size, LOCKED_ALPHA, LOCKED_BRIGHTNESS);
            }
        }

        // ---- 文字：上面门槛，下面名字和编号 ----
        for (int i = from; i <= to; i++) {
            boolean reached = i <= shownRank;
            boolean current = i == this.rank && reached && !this.animating;
            float x = xOf(i);
            float half = nodeSize(i, shown, hovered) / 2.0F;

            String threshold = i == 0 ? I18n.get("gui.albionmastery.rank_track.start") : digits(MasteryRank.xpForRank(i));
            drawSmallCentered(guiGraphics, threshold, x, lineY - half - 9,
                    current ? MasteryPalette.GOLD : reached ? MasteryPalette.TEXT_DIM : TEXT_LOCKED);

            String name = MasteryRank.name(i).getString();
            int nameColor = reached ? RankEmblem.primaryColor(i) : TEXT_LOCKED;
            float nameY = lineY + half + 3;
            if (this.font.width(name) * SMALL <= SPACING - 6) {
                drawSmallCentered(guiGraphics, name, x, nameY, nameColor);
            } else {
                drawScaledCentered(guiGraphics, name, x, nameY, nameColor, (SPACING - 6.0F) / this.font.width(name));
            }
            String sub;
            int subColor;
            if (i == this.endRank && i != this.rank) {
                sub = I18n.get("gui.albionmastery.rank_track.node.end");
                subColor = MasteryPalette.GOLD;
            } else if (current) {
                sub = I18n.get("gui.albionmastery.rank_track.node.current");
                subColor = MasteryPalette.GOLD_BRIGHT;
            } else {
                sub = I18n.get("gui.albionmastery.rank.badge.rank", i);
                subColor = reached ? MasteryPalette.TEXT_DIM : TEXT_LOCKED;
            }
            drawSmallCentered(guiGraphics, sub, x, nameY + 9, subColor);
        }
    }

    /**
     * 段位每三段一组（青铜、白银、黄金），组上方画一道括线和组名；传奇段位合成一组。
     */
    private void drawTierBands(GuiGraphics guiGraphics, int from, int to, int lineY, int shownRank) {
        // 让开当前段位放大后（再加鼠标悬停）的门槛数字
        int bandY = lineY - Math.round(EMBLEM_CURRENT / 2.0F) - 21;
        int lastTier = Integer.MIN_VALUE;
        for (int i = Math.max(1, from); i <= to; i++) {
            int tier = MasteryRank.tier(i);
            if (tier == lastTier) {
                continue;
            }
            lastTier = tier;
            int first = tier >= MasteryRank.TIER_COUNT ? MasteryRank.NAMED_RANKS + 1 : tier * 3 + 1;
            int last = tier >= MasteryRank.TIER_COUNT ? this.endRank : Math.min(tier * 3 + 3, this.endRank);
            if (first > this.endRank) {
                break;
            }
            float x0 = xOf(first) - SPACING * 0.42F;
            float x1 = xOf(last) + SPACING * 0.42F;
            boolean reached = shownRank >= first;
            boolean here = this.rank >= first && this.rank <= last && !this.animating;
            int color = here ? MasteryPalette.GOLD : reached ? MasteryPalette.GOLD_DARK : MasteryPalette.LINE_LOCKED;
            guiGraphics.fill(Math.round(x0), bandY, Math.round(x1), bandY + 1, color);
            guiGraphics.fill(Math.round(x0), bandY, Math.round(x0) + 1, bandY + 4, color);
            guiGraphics.fill(Math.round(x1) - 1, bandY, Math.round(x1), bandY + 4, color);

            String label = tierLabel(tier);
            float labelWidth = this.font.width(label) * SMALL + 6;
            float cx = (x0 + x1) / 2.0F;
            // 传奇组很长，组名跟着可视区走，别滚出屏幕看不见
            if (tier >= MasteryRank.TIER_COUNT) {
                float visibleLeft = Math.max(x0, trackLeft() + EDGE_FADE);
                float visibleRight = Math.min(x1, trackRight() - EDGE_FADE);
                if (visibleRight - visibleLeft > labelWidth) {
                    cx = Mth.clamp(cx, visibleLeft + labelWidth / 2.0F, visibleRight - labelWidth / 2.0F);
                }
            }
            int textY = bandY - 3;
            guiGraphics.fill(Math.round(cx - labelWidth / 2.0F), textY - 1, Math.round(cx + labelWidth / 2.0F),
                    textY + 7, MasteryPalette.BG | 0xFF000000);
            drawSmallCentered(guiGraphics, label, cx, textY,
                    here ? MasteryPalette.GOLD_BRIGHT : reached ? MasteryPalette.TEXT_DIM : TEXT_LOCKED);
        }
    }

    /**
     * 组名取这一组第一段的名字（启蒙者、信徒……）；最后一组三段各有名字，取前两段；传奇单独一个词。
     */
    private static String tierLabel(int tier) {
        if (tier >= MasteryRank.TIER_COUNT) {
            return I18n.get("gui.albionmastery.rank_track.legendary_band");
        }
        if (tier == MasteryRank.TIER_COUNT - 1) {
            return MasteryRank.name(tier * 3 + 1).getString() + " · " + MasteryRank.name(tier * 3 + 2).getString();
        }
        return MasteryRank.name(tier * 3 + 1).getString();
    }

    /**
     * 徽章边长：当前段位最大，终点次之；动画里刚被点亮的徽章鼓一下；鼠标停着的放大一点。
     */
    private float nodeSize(int i, float shown, int hovered) {
        float size = EMBLEM;
        if (i == this.rank && !this.animating) {
            size = EMBLEM_CURRENT;
        } else if (i == this.endRank) {
            size = EMBLEM_END;
        }
        if (this.animating) {
            float since = shown - i;
            if (since >= 0.0F && since < 0.35F) {
                size *= 1.0F + 0.28F * (1.0F - since / 0.35F);
            }
        }
        if (i == hovered) {
            size += 3.0F;
        }
        return size;
    }

    /**
     * 可视区两头渐隐，提示两边还有。
     */
    private void drawEdgeFade(GuiGraphics guiGraphics) {
        float overflow = contentWidth() - viewWidth();
        if (overflow <= 0.0F) {
            return;
        }
        Matrix4f matrix = guiGraphics.pose().last().pose();
        int solid = MasteryPalette.BG | 0xFF000000;
        int clear = MasteryPalette.BG & 0x00FFFFFF;
        float top = trackTop();
        float bottom = trackBottom();
        BufferBuilder buffer = RenderUtil.begin();
        if (this.scroll > 0.5F) {
            float x0 = trackLeft();
            float x1 = x0 + EDGE_FADE;
            RenderUtil.gradientQuad(buffer, matrix, x0, top, solid, x1, top, clear, x1, bottom, clear, x0, bottom, solid);
        }
        if (this.scroll < overflow - 0.5F) {
            float x1 = trackRight();
            float x0 = x1 - EDGE_FADE;
            RenderUtil.gradientQuad(buffer, matrix, x0, top, clear, x1, top, solid, x1, bottom, solid, x0, bottom, clear);
        }
        RenderUtil.end();
    }

    /**
     * 底部小地图：整条路的缩略。亮的是走过的部分，金框是当前看到的范围，竖线是每组的分界。
     */
    private void drawMinimap(GuiGraphics guiGraphics, float shown, int mouseX, int mouseY) {
        int left = trackLeft();
        int right = trackRight();
        int top = minimapTop();
        int bottom = top + MINIMAP_HEIGHT;
        boolean hover = isOnMinimap(mouseX, mouseY);
        guiGraphics.fill(left, top, right, bottom, 0xFF17120A);
        float span = Math.max(1, this.endRank);
        int fill = left + Math.round((right - left) * Mth.clamp(shown / span, 0.0F, 1.0F));
        if (fill > left) {
            guiGraphics.fill(left, top + 2, fill, bottom - 2, MasteryPalette.PROGRESS);
        }
        // 每组分界画一道暗线，到至崇宗师那道亮一些
        for (int boundary = 3; boundary < this.endRank && boundary <= MasteryRank.NAMED_RANKS; boundary += 3) {
            int x = left + Math.round((right - left) * boundary / span);
            guiGraphics.fill(x, top + 1, x + 1, bottom - 1,
                    boundary == MasteryRank.NAMED_RANKS ? MasteryPalette.GOLD_DARK : 0xFF2A2215);
        }
        int current = left + Math.round((right - left) * Mth.clamp(this.rank / span, 0.0F, 1.0F));
        guiGraphics.fill(current - 1, top - 1, current + 1, bottom + 1, MasteryPalette.GOLD_BRIGHT);

        float overflow = contentWidth() - viewWidth();
        if (overflow > 0.0F) {
            float total = contentWidth();
            int x0 = left + Math.round((right - left) * Mth.clamp(this.scroll / total, 0.0F, 1.0F));
            int x1 = left + Math.round((right - left) * Mth.clamp((this.scroll + viewWidth()) / total, 0.0F, 1.0F));
            int border = hover || this.draggingMinimap ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK;
            guiGraphics.fill(x0, top, x1, top + 1, border);
            guiGraphics.fill(x0, bottom - 1, x1, bottom, border);
            guiGraphics.fill(x0, top, x0 + 1, bottom, border);
            guiGraphics.fill(x1 - 1, top, x1, bottom, border);
        }
    }

    private boolean isOnMinimap(double mouseX, double mouseY) {
        return contentWidth() > viewWidth() && mouseX >= trackLeft() && mouseX < trackRight()
                && mouseY >= minimapTop() - 2 && mouseY < minimapTop() + MINIMAP_HEIGHT + 2;
    }

    private void drawFooter(GuiGraphics guiGraphics) {
        String hint = I18n.get(contentWidth() > viewWidth()
                ? "gui.albionmastery.rank_track.hint" : "gui.albionmastery.rank_track.hint_short");
        drawSmallCentered(guiGraphics, hint, this.width / 2.0F, this.height - FOOTER_HEIGHT + 2, MasteryPalette.TEXT_DIM);
    }

    /**
     * 鼠标停在段位上：门槛、相当于多少武器等级、走到了没有。
     */
    private void drawNodeTooltip(GuiGraphics guiGraphics, int i, int mouseX, int mouseY) {
        long need = MasteryRank.xpForRank(i);
        String name = MasteryRank.name(i).getString();
        String rankLabel = I18n.get("gui.albionmastery.rank.badge.rank", i);
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (i > 0) {
            lines.add(I18n.get("gui.albionmastery.rank_track.node.threshold", digits(need)));
            colors.add(MasteryPalette.TEXT);
            int perLevel = Math.max(1, ClientRank.xpPerLevel());
            lines.add(I18n.get("gui.albionmastery.rank_track.node.levels", digits((need + perLevel - 1) / perLevel)));
            colors.add(MasteryPalette.TEXT_DIM);
        }
        if (i == this.rank) {
            lines.add(I18n.get("gui.albionmastery.rank_track.node.current"));
            colors.add(MasteryPalette.GOLD_BRIGHT);
        } else if (i < this.rank) {
            lines.add(I18n.get("gui.albionmastery.rank_track.node.reached"));
            colors.add(MasteryPalette.GOLD);
        } else {
            lines.add(I18n.get("gui.albionmastery.rank.tooltip.remaining", digits(need - this.xp)));
            colors.add(MasteryPalette.WARN);
        }
        if (i == this.endRank) {
            lines.add(I18n.get("gui.albionmastery.rank_track.node.end_detail"));
            colors.add(MasteryPalette.TEXT_DIM);
        }
        // 段位给特化的两样好处：开槽门槛减几级、每个来源多几个改装点
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        if (snapshot.enableSpecialization) {
            int reduction = snapshot.specRules.rankReductionLevels(i, snapshot.maxLevel);
            int points = snapshot.specRules.modPointsFromRank(i);
            if (reduction > 0 || points > 0) {
                lines.add(I18n.get("gui.albionmastery.rank_track.node.spec", reduction, points));
                colors.add(MasteryPalette.PROGRESS);
            }
        }

        int width = this.font.width(name) + 6 + this.font.width(rankLabel);
        for (String line : lines) {
            width = Math.max(width, this.font.width(line));
        }
        int height = (lines.size() + 1) * LINE - (LINE - this.font.lineHeight) + TOOLTIP_PAD_Y * 2;
        int x = mouseX + 10;
        int y = mouseY + 10;
        if (x + width + TOOLTIP_PAD_X > this.width - 2) {
            x = mouseX - 10 - width;
        }
        if (y + height > this.height - 2) {
            y = mouseY - 10 - height;
        }
        x = Math.max(TOOLTIP_PAD_X + 2, x);
        y = Math.max(2, y);
        int left = x - TOOLTIP_PAD_X;
        int right = x + width + TOOLTIP_PAD_X;
        int bottom = y + height;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 400.0F);
        guiGraphics.fill(left, y, right, bottom, MasteryPalette.PANEL);
        guiGraphics.fill(left, y, right, y + 1, MasteryPalette.EDGE);
        guiGraphics.fill(left, bottom - 1, right, bottom, MasteryPalette.EDGE);
        guiGraphics.fill(left, y, left + 1, bottom, MasteryPalette.GOLD_DARK);
        guiGraphics.fill(right - 1, y, right, bottom, MasteryPalette.GOLD_DARK);
        int cursor = y + TOOLTIP_PAD_Y;
        guiGraphics.drawString(this.font, name, x, cursor, RankEmblem.primaryColor(i), false);
        guiGraphics.drawString(this.font, rankLabel, x + this.font.width(name) + 6, cursor, MasteryPalette.TEXT_DIM, false);
        for (int k = 0; k < lines.size(); k++) {
            cursor += LINE;
            guiGraphics.drawString(this.font, lines.get(k), x, cursor, colors.get(k), false);
        }
        guiGraphics.pose().popPose();
    }

    /**
     * @return 鼠标停在哪个段位的徽章上，没有返回 -1
     */
    private int hoveredNode(double mouseX, double mouseY) {
        if (mouseX < trackLeft() || mouseX >= trackRight() || mouseY < trackTop() || mouseY >= trackBottom()) {
            return -1;
        }
        int lineY = lineY();
        // 徽章连同上下的字都算，竖着放宽一些
        if (Math.abs(mouseY - lineY) > EMBLEM_CURRENT / 2.0F + 20.0F) {
            return -1;
        }
        int i = Math.round((float) ((mouseX - trackLeft() - PAD + this.scroll) / SPACING));
        if (i < 0 || i > this.endRank) {
            return -1;
        }
        return Math.abs(mouseX - xOf(i)) <= SPACING / 2.0F - 2.0F ? i : -1;
    }

    // ======================================================================
    // 输入
    // ======================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (isOnBack(mouseX, mouseY)) {
            playClick();
            onClose();
            return true;
        }
        stopAnimation();
        if (isOnMinimap(mouseX, mouseY)) {
            this.draggingMinimap = true;
            jumpToMinimap(mouseX);
            return true;
        }
        if (mouseY >= trackTop() && mouseY < trackBottom()) {
            this.draggingTrack = true;
            this.dragDistance = 0.0D;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingMinimap) {
            jumpToMinimap(mouseX);
            return true;
        }
        if (this.draggingTrack) {
            this.dragDistance += Math.abs(dragX);
            this.scroll = clampScroll(this.scroll - (float) dragX);
            this.scrollTarget = this.scroll;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.draggingTrack && this.dragDistance < 3.0D) {
            // 没拖动就是点了一下：点到段位就把它挪到正中
            int node = hoveredNode(mouseX, mouseY);
            if (node >= 0) {
                this.scrollTarget = scrollToCenter(node);
            }
        }
        this.draggingTrack = false;
        this.draggingMinimap = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * 小地图上点到哪，就让那里落在可视区正中。
     */
    private void jumpToMinimap(double mouseX) {
        float ratio = (float) Mth.clamp((mouseX - trackLeft()) / (double) viewWidth(), 0.0D, 1.0D);
        this.scroll = clampScroll(ratio * contentWidth() - viewWidth() / 2.0F);
        this.scrollTarget = this.scroll;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        stopAnimation();
        // 滚轮往上看低段位，往下看高段位
        this.scrollTarget = clampScroll(this.scrollTarget - (float) delta * SPACING * 1.5F);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> {
                stopAnimation();
                this.scrollTarget = clampScroll(this.scrollTarget - SPACING * 3.0F);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> {
                stopAnimation();
                this.scrollTarget = clampScroll(this.scrollTarget + SPACING * 3.0F);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                stopAnimation();
                this.scrollTarget = scrollToCenter(currentPosition());
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                stopAnimation();
                this.scrollTarget = scrollToCenter(this.endRank);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ======================================================================
    // 小工具
    // ======================================================================

    private void drawSmall(GuiGraphics guiGraphics, String text, float x, float y, int color) {
        drawScaled(guiGraphics, text, x, y, color, SMALL);
    }

    private void drawSmallCentered(GuiGraphics guiGraphics, String text, float cx, float y, int color) {
        drawScaledCentered(guiGraphics, text, cx, y, color, SMALL);
    }

    private void drawScaledCentered(GuiGraphics guiGraphics, String text, float cx, float y, int color, float scale) {
        drawScaled(guiGraphics, text, cx - this.font.width(text) * scale / 2.0F, y, color, scale);
    }

    private void drawScaled(GuiGraphics guiGraphics, String text, float x, float y, int color, float scale) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawString(this.font, text, 0, 0, color, false);
        guiGraphics.pose().popPose();
    }

    private static String digits(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static void playClick() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
