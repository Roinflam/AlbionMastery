package com.flameforge.albionmastery.client.rank;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.mastery.rank.MasteryRank;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.Arrays;

/**
 * 段位徽章。
 *
 * <p>优先画 {@code albionmastery:textures/gui/rank/<段位>.png}（0~39 各一张，48×48 像素画，模组自带，
 * 资源包可以照同名覆盖）。传奇 7（37 段）是最后一个独有造型，38、39 是它的白银、黄金版；
 * 再往上按青铜、白银、黄金轮着用这三张。从 38 段起造型重复，底部挂一块写着传奇序号的小牌。
 * 贴图是否存在的检查结果会缓存，资源重载时清掉（见 {@link #invalidateTextures()}）。
 *
 * <p>缺图时退回程序画的矢量纹章（见 {@link EmblemPainter}）：十组段位各有一枚，同组三段分青铜、白银、黄金，
 * 0 段是一个暗淡的空心记号，传奇段位是虚空紫配棱镜环，同样挂序号小牌。
 *
 * <p>可以在任意位姿变换里调用（缩放、平移都行）。画之前会先把 {@link GuiGraphics} 攒着的东西冲刷掉，
 * 画完把混合函数恢复默认、重新打开背面剔除、着色器颜色复位，调用方可以接着用 GuiGraphics 画字画框。
 */
public final class RankEmblem {

    /** 动画时钟的周期（毫秒）：扫光 5.2 秒、棱镜 8 秒、闪烁 2.6 秒都能整除它，回绕时看不出跳变。 */
    private static final long CLOCK_PERIOD_MS = 104_000L;

    /** 扫光的周期和单次时长（秒）。 */
    private static final float GLOSS_PERIOD = 5.2F;
    private static final float GLOSS_DURATION = 0.9F;

    /** 传奇序号小牌从多大的徽章开始画，再小就看不清了。 */
    private static final float NUMERAL_MIN_SIZE = 28.0F;

    /** 最后一个独有造型的段位（传奇 7），往上的段位都用它的造型。 */
    private static final int LAST_SHAPE_RANK = MasteryRank.NAMED_RANKS + 7;

    /** 有自己贴图的最高段位：传奇 7 的青铜、白银、黄金三张，到 39 段为止。 */
    private static final int LAST_TEXTURED_RANK = LAST_SHAPE_RANK + 2;

    /** 贴图本身的边长（像素），按它判断画出来是放大还是缩小。 */
    private static final int TEXTURE_PIXELS = 48;

    private static final ResourceLocation[] TEXTURES = new ResourceLocation[LAST_TEXTURED_RANK + 1];

    /** 贴图是否存在：0 未查、1 没有、2 有。 */
    private static final byte[] TEXTURE_STATE = new byte[LAST_TEXTURED_RANK + 1];

    private static final byte TEXTURE_UNKNOWN = 0;
    private static final byte TEXTURE_ABSENT = 1;
    private static final byte TEXTURE_PRESENT = 2;

    private static final EmblemPainter PAINTER = new EmblemPainter();
    private static final BufferSink SINK = new BufferSink();

    /** 上一次画的传奇序号和它的文字，避免每帧拼字符串。 */
    private static int numeralIndex = -1;
    private static String numeralText = "";

    static {
        for (int rank = 0; rank <= LAST_TEXTURED_RANK; rank++) {
            TEXTURES[rank] = new ResourceLocation(AlbionMastery.MOD_ID, "textures/gui/rank/" + rank + ".png");
        }
    }

    private RankEmblem() {
    }

    /**
     * 画一枚静止的徽章。
     *
     * @param guiGraphics 绘图上下文
     * @param rank        段位
     * @param cx          中心 X（当前位姿下的 GUI 像素）
     * @param cy          中心 Y
     * @param size        整体边长
     * @param alpha       透明度 0~1
     */
    public static void draw(GuiGraphics guiGraphics, int rank, float cx, float cy, float size, float alpha) {
        draw(guiGraphics, rank, cx, cy, size, alpha, 0.0F);
    }

    /**
     * 画一枚带动画的徽章：每隔几秒扫过一道光，传奇的棱镜环缓缓流转。
     *
     * <p>{@code timeSeconds} 传 {@link #clockSeconds()} 即可；传 0 等于静止。
     *
     * @param guiGraphics 绘图上下文
     * @param rank        段位
     * @param cx          中心 X（当前位姿下的 GUI 像素）
     * @param cy          中心 Y
     * @param size        整体边长
     * @param alpha       透明度 0~1
     * @param timeSeconds 动画时间（秒）
     */
    public static void draw(GuiGraphics guiGraphics, int rank, float cx, float cy, float size, float alpha,
                            float timeSeconds) {
        drawInternal(guiGraphics, rank, cx, cy, size, alpha, timeSeconds, glossPhaseAt(timeSeconds), 0.0F);
    }

    /**
     * 段位名文字该用的颜色：金属的主色调。
     *
     * @param rank 段位
     * @return ARGB
     */
    public static int primaryColor(int rank) {
        return EmblemPainter.PRIMARY[metalOf(rank)];
    }

    /**
     * 高光色，用于点缀（边线、光效）。
     *
     * @param rank 段位
     * @return ARGB
     */
    public static int accentColor(int rank) {
        return EmblemPainter.ACCENT[metalOf(rank)];
    }

    /**
     * 给 {@link #draw(GuiGraphics, int, float, float, float, float, float)} 用的动画时钟。
     *
     * <p>按 104 秒回绕：直接把毫秒数转成 float 会丢精度，动画一顿一顿的。
     *
     * @return 秒
     */
    public static float clockSeconds() {
        return (Util.getMillis() % CLOCK_PERIOD_MS) / 1000.0F;
    }

    /**
     * 资源重载后清掉贴图存在与否的缓存。
     */
    public static void invalidateTextures() {
        Arrays.fill(TEXTURE_STATE, TEXTURE_UNKNOWN);
    }

    /**
     * 晋升动画用的完整入口：可以单独控制扫光进度和闪白。
     *
     * @param guiGraphics 绘图上下文
     * @param rank        段位
     * @param cx          中心 X
     * @param cy          中心 Y
     * @param size        整体边长
     * @param alpha       透明度
     * @param timeSeconds 动画时间（秒）
     * @param glossPhase  扫光进度 0~1，小于 0 不扫
     * @param flash       闪白程度 0~1
     */
    static void drawInternal(GuiGraphics guiGraphics, int rank, float cx, float cy, float size, float alpha,
                             float timeSeconds, float glossPhase, float flash) {
        if (alpha <= 0.004F || size < 2.0F) {
            return;
        }
        guiGraphics.flush();
        ResourceLocation texture = textureFor(rank);
        if (texture != null) {
            drawTexture(guiGraphics, texture, cx, cy, size, alpha, flash);
            // 传奇 7 以后造型都一样，靠序号小牌区分
            if (rank > LAST_SHAPE_RANK && size >= NUMERAL_MIN_SIZE) {
                drawNumeral(guiGraphics, MasteryRank.legendaryIndex(rank), cx, cy, size, alpha);
            }
            return;
        }
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        if (buffer.building()) {
            // 调用方自己还开着一批顶点没提交：这时再 begin 会直接抛异常，宁可这一帧不画
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        SINK.buffer = buffer;
        SINK.matrix = guiGraphics.pose().last().pose();
        try {
            PAINTER.paint(SINK, tierOf(rank), metalOf(rank), cx, cy, size, alpha, timeSeconds, glossPhase, flash);
        } finally {
            SINK.buffer = null;
            SINK.matrix = null;
            Tesselator.getInstance().end();
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
        if (MasteryRank.isLegendary(rank) && size >= NUMERAL_MIN_SIZE) {
            drawNumeral(guiGraphics, MasteryRank.legendaryIndex(rank), cx, cy, size, alpha);
        }
    }

    /**
     * 传奇徽章底部的序号小牌。
     */
    private static void drawNumeral(GuiGraphics guiGraphics, int index, float cx, float cy, float size, float alpha) {
        int textAlpha = (int) (255.0F * EmblemPainter.clamp01(alpha));
        // 字的透明度低于 4 会被 Font 当成不透明画出来，快消失时干脆不画
        if (textAlpha < 5) {
            return;
        }
        if (index != numeralIndex) {
            numeralIndex = index;
            numeralText = String.valueOf(index);
        }
        Font font = Minecraft.getInstance().font;
        float scale = Math.max(0.5F, Math.min(2.0F, size / 52.0F));
        int width = font.width(numeralText);
        int half = width / 2 + 3;
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(cx, cy + size * 0.5F * 0.76F, 0.0F);
        pose.scale(scale, scale, 1.0F);
        int accent = EmblemPainter.ACCENT[EmblemPainter.METAL_LEGENDARY];
        int plate = EmblemPainter.withAlpha(0xF00B0614, alpha);
        int edge = EmblemPainter.withAlpha(EmblemPainter.PRIMARY[EmblemPainter.METAL_LEGENDARY], alpha);
        // 四角各缺一个像素，看起来是圆角小牌
        guiGraphics.fill(-half, -5, half, 6, plate);
        guiGraphics.fill(-half + 1, -6, half - 1, -5, edge);
        guiGraphics.fill(-half + 1, 6, half - 1, 7, edge);
        guiGraphics.fill(-half - 1, -5, -half, 6, edge);
        guiGraphics.fill(half, -5, half + 1, 6, edge);
        guiGraphics.drawString(font, numeralText, -width / 2.0F, -4.0F,
                (textAlpha << 24) | (accent & 0x00FFFFFF), false);
        pose.popPose();
    }

    /**
     * 把像素画贴图整张画上去。
     *
     * <p>放大时用最近邻，保住像素画的硬边；缩小（屏幕上比贴图本身还小，比如 GUI 缩放 2 时的排行榜）
     * 改用线性过滤，否则最近邻会整行整列地丢像素，细线断成虚线。
     * 闪白时用叠加混合再画一遍同一张图，金属被冲成近白的亮色，再慢慢冷却回本色。
     */
    private static void drawTexture(GuiGraphics guiGraphics, ResourceLocation texture,
                                    float cx, float cy, float size, float alpha, float flash) {
        PoseStack pose = guiGraphics.pose();
        Matrix4f matrix = pose.last().pose();
        float poseScale = (float) Math.sqrt(matrix.m00() * matrix.m00() + matrix.m01() * matrix.m01());
        double physical = size * poseScale * Minecraft.getInstance().getWindow().getGuiScale();
        AbstractTexture handle = Minecraft.getInstance().getTextureManager().getTexture(texture);
        handle.setFilter(physical < TEXTURE_PIXELS - 0.5D, false);

        pose.pushPose();
        pose.translate(cx - size * 0.5F, cy - size * 0.5F, 0.0F);
        pose.scale(size / TEXTURE_PIXELS, size / TEXTURE_PIXELS, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, EmblemPainter.clamp01(alpha));
        guiGraphics.blit(texture, 0, 0, 0.0F, 0.0F, TEXTURE_PIXELS, TEXTURE_PIXELS, TEXTURE_PIXELS, TEXTURE_PIXELS);
        float glow = EmblemPainter.clamp01(flash) * EmblemPainter.clamp01(alpha);
        if (glow > 0.004F) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, glow);
            guiGraphics.blit(texture, 0, 0, 0.0F, 0.0F, TEXTURE_PIXELS, TEXTURE_PIXELS, TEXTURE_PIXELS, TEXTURE_PIXELS);
            guiGraphics.blit(texture, 0, 0, 0.0F, 0.0F, TEXTURE_PIXELS, TEXTURE_PIXELS, TEXTURE_PIXELS, TEXTURE_PIXELS);
            RenderSystem.defaultBlendFunc();
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        pose.popPose();
        // 过滤方式是贴图对象自己的状态，画完放回最近邻，别的地方按默认来
        handle.setFilter(false, false);
    }

    /**
     * 查这一段有没有贴图，结果缓存到下次资源重载。39 段以后按金属轮着用 37、38、39 三张
     * （段位的金属是 (段位 - 1) % 3，和这三张的顺序对得上）。
     */
    private static ResourceLocation textureFor(int rank) {
        int slot = rank <= LAST_TEXTURED_RANK ? Math.max(0, rank)
                : LAST_SHAPE_RANK + (rank - LAST_SHAPE_RANK) % 3;
        byte state = TEXTURE_STATE[slot];
        if (state == TEXTURE_UNKNOWN) {
            boolean present = Minecraft.getInstance().getResourceManager().getResource(TEXTURES[slot]).isPresent();
            state = present ? TEXTURE_PRESENT : TEXTURE_ABSENT;
            TEXTURE_STATE[slot] = state;
        }
        return state == TEXTURE_PRESENT ? TEXTURES[slot] : null;
    }

    /**
     * 某个时刻的扫光进度：每 {@value #GLOSS_PERIOD} 秒扫一次，扫的时候返回 0~1，其余时间返回 -1。
     *
     * @param timeSeconds 时间
     * @return 进度
     */
    static float glossPhaseAt(float timeSeconds) {
        if (timeSeconds <= 0.0F) {
            return -1.0F;
        }
        float cycle = timeSeconds % GLOSS_PERIOD;
        return cycle < GLOSS_DURATION ? cycle / GLOSS_DURATION : -1.0F;
    }

    /**
     * @param rank 段位
     * @return 画法用的组号：-1 = 0 段，0~9 = 十组，10 = 传奇
     */
    static int tierOf(int rank) {
        return MasteryRank.isLegendary(rank) ? EmblemPainter.TIER_LEGENDARY : MasteryRank.tier(rank);
    }

    /**
     * @param rank 段位
     * @return 画法用的金属种类
     */
    static int metalOf(int rank) {
        return switch (MasteryRank.metal(rank)) {
            case NONE -> EmblemPainter.METAL_NONE;
            case BRONZE -> EmblemPainter.METAL_BRONZE;
            case SILVER -> EmblemPainter.METAL_SILVER;
            case GOLD -> EmblemPainter.METAL_GOLD;
        };
    }

    /**
     * 把顶点写进 Tesselator 的缓冲。
     */
    private static final class BufferSink implements EmblemPainter.Sink {

        private BufferBuilder buffer;
        private Matrix4f matrix;

        @Override
        public void vertex(float x, float y, int argb) {
            this.buffer.vertex(this.matrix, x, y, 0.0F)
                    .color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
                    .endVertex();
        }
    }
}
