package com.flameforge.albionmastery.client.rank;

import com.flameforge.albionmastery.client.screen.MasteryPalette;
import com.flameforge.albionmastery.client.screen.RenderUtil;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.rank.MasteryRank;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.Locale;

/**
 * 段位晋升动画：屏幕上方居中，整套约五秒半（传奇六秒）。
 *
 * <p>分镜：
 * <ol>
 *     <li><b>蓄力</b>（0~0.34 秒）——中线拉出一道横向光痕，旧徽章（有的话）缩小、发白、收进光里；</li>
 *     <li><b>揭晓</b>——新徽章从略大处白光一闪落定，冲击波向外扩散，一圈火花四散飞出，
 *     背后亮起缓慢旋转的光芒，两圈由弧段组成的装饰环一顺一逆地画出来；</li>
 *     <li><b>铭文</b>——小字「段位晋升 / 段位评定」字距收拢，段位名用金属色放大浮现并扫过一道流光，
 *     下面一行「段位 N · 段位经验 X」；一次连升好几段时徽章右上角挂「+N」；</li>
 *     <li><b>停留</b>——光芒呼吸、装饰环慢转、周围零星闪光，徽章和段位名再扫一次光；</li>
 *     <li><b>退场</b>——整体上移、淡出，装饰环略微外扩。</li>
 * </ol>
 * 传奇段位换成棱镜色：光芒、火花、装饰环、段位名都按色相流转，多一道冲击波，停留更久。
 *
 * <p>时间一律按墙钟（{@link Util#getMillis()}）算，从第一次真正画出来的那一帧开始计时；
 * 每帧不分配任何数组和列表，火花、闪光的参数在类加载时一次算好。
 *
 * <p>什么时候开始：本人在游戏里、没开界面（聊天框除外）、没按 F1 隐藏界面。
 * 开播之后打开背包之类的界面照样画完（画在界面之上），按 F1 时不画但照常计时。
 * 播放期间 {@link com.flameforge.albionmastery.client.MasteryToastManager} 的升级横幅会让开。
 */
public final class RankCeremony {

    // ---- 时间轴（毫秒） ----

    /** 新徽章揭晓的时刻。 */
    private static final float REVEAL_MS = 340.0F;

    /** 旧徽章消失的时刻。 */
    private static final float OLD_FADE_MS = 400.0F;

    private static final float TITLE_IN_MS = 760.0F;
    private static final float NAME_IN_MS = 950.0F;
    private static final float DETAIL_IN_MS = 1300.0F;
    private static final float JUMP_IN_MS = 900.0F;

    /** 段位名流光：第一次的时刻、间隔、单次时长。 */
    private static final float SHIMMER_START_MS = 1250.0F;
    private static final float SHIMMER_PERIOD_MS = 2600.0F;
    private static final float SHIMMER_MS = 800.0F;

    /** 停留结束、开始退场的时刻。 */
    private static final float HOLD_END_MS = 4600.0F;
    private static final float LEGENDARY_HOLD_END_MS = 5300.0F;

    private static final float EXIT_MS = 800.0F;

    /** 音效最多迟到多久还补放：按 F1 隐藏了一阵再打开时，不把攒下的几声一起放出来。 */
    private static final float SOUND_GRACE_MS = 500.0F;

    // ---- 文案 ----

    private static final String KEY_PROMOTED = "gui.albionmastery.rank.ceremony.promoted";
    private static final String KEY_ASSESSED = "gui.albionmastery.rank.ceremony.assessed";
    private static final String KEY_DETAIL = "gui.albionmastery.rank.ceremony.detail";

    /** 段位名的字间距（放大前的像素）。 */
    private static final float NAME_SPACING = 1.0F;

    /** 文字底板上下的羽化宽度。 */
    private static final float SCRIM_FEATHER = 9.0F;

    // ---- 火花与闪光（类加载时按序号算好，结果固定，每帧不再分配） ----

    private static final int SPARKS = 36;
    private static final int SPARKS_LEGENDARY = 52;
    private static final float[] SPARK_COS = new float[SPARKS_LEGENDARY];
    private static final float[] SPARK_SIN = new float[SPARKS_LEGENDARY];
    private static final float[] SPARK_SPEED = new float[SPARKS_LEGENDARY];
    private static final float[] SPARK_DELAY = new float[SPARKS_LEGENDARY];
    private static final float[] SPARK_LIFE = new float[SPARKS_LEGENDARY];
    private static final float[] SPARK_LENGTH = new float[SPARKS_LEGENDARY];
    private static final float[] SPARK_HUE = new float[SPARKS_LEGENDARY];

    private static final int GLINTS = 7;
    private static final float[] GLINT_COS = new float[GLINTS];
    private static final float[] GLINT_SIN = new float[GLINTS];
    private static final float[] GLINT_RADIUS = new float[GLINTS];
    private static final float[] GLINT_PHASE = new float[GLINTS];

    private static final float PI = (float) Math.PI;

    static {
        for (int i = 0; i < SPARKS_LEGENDARY; i++) {
            // 黄金角铺开再加一点抖动：既均匀又不像排好队的
            double angle = i * 2.399963D + (hash(i, 1) - 0.5D) * 0.5D;
            SPARK_COS[i] = (float) Math.cos(angle);
            SPARK_SIN[i] = (float) Math.sin(angle);
            SPARK_SPEED[i] = 0.55F + 0.45F * hash(i, 2);
            SPARK_DELAY[i] = 140.0F * hash(i, 3);
            SPARK_LIFE[i] = 520.0F + 540.0F * hash(i, 4);
            SPARK_LENGTH[i] = 0.18F + 0.30F * hash(i, 5);
            SPARK_HUE[i] = hash(i, 6);
        }
        for (int i = 0; i < GLINTS; i++) {
            double angle = i * 2.399963D + 0.7D;
            GLINT_COS[i] = (float) Math.cos(angle);
            GLINT_SIN[i] = (float) Math.sin(angle);
            GLINT_RADIUS[i] = 1.05F + 0.45F * hash(i, 7);
            GLINT_PHASE[i] = 1.9F * hash(i, 8);
        }
    }

    /** 正在播的一场；null 表示没有。 */
    private static Show current;

    /** 排队等着的一场，还没开播的会合并成这一场。 */
    private static Show pending;

    private RankCeremony() {
    }

    /**
     * 排一场晋升动画。
     *
     * <p>还没开播的会合并：取最低的旧段位、最高的新段位，一口气连升几段只播一场；
     * 正在播的不受影响，新的一场排在它后面。客户端设置里关了动画时什么也不做。
     *
     * @param oldRank 旧段位
     * @param newRank 新段位
     * @param initial true 表示这是功能上线后第一次评定段位（标题写「段位评定」），否则是「段位晋升」
     */
    public static void enqueue(int oldRank, int newRank, boolean initial) {
        if (!MasteryConfig.CLIENT.rankUpAnimation.get()) {
            return;
        }
        int from = Math.max(0, oldRank);
        int to = Math.max(0, newRank);
        // 评定出 0 段没什么好庆祝的；降段（服主调了曲线）也不放晋升动画
        if (to <= 0 || (!initial && to <= from)) {
            return;
        }
        // 正在播的已经覆盖了这次晋升（服务端重发、或几个包前后脚到），不再重播
        if (current != null && !initial && to <= current.newRank) {
            return;
        }
        if (pending != null) {
            pending.oldRank = Math.min(pending.oldRank, from);
            pending.newRank = Math.max(pending.newRank, to);
            pending.initial |= initial;
            return;
        }
        pending = new Show(from, to, initial);
    }

    /**
     * 清空正在播和排队的，断线时调用。
     */
    public static void clear() {
        current = null;
        pending = null;
    }

    /**
     * 晋升动画是否正占着屏幕上方：正在播，或者排着队、这一帧就能开播。
     *
     * <p>后一种也算进来，是为了升级横幅在同一帧就让开，不会先弹出来再被撤回去。
     * 上一场刚播完、{@link #current} 还没在 {@link #render} 里清掉的那一帧同样要看队列：
     * 下一场这一帧就接着开播，横幅不能趁这一帧冒出来响一声又被撤回。
     *
     * @return 正占着返回 true
     */
    public static boolean isActive() {
        if (current != null && Util.getMillis() - current.startedAt < current.total()) {
            return true;
        }
        return pending != null && MasteryConfig.CLIENT.rankUpAnimation.get() && canStart(Minecraft.getInstance());
    }

    /**
     * 每帧调用一次：推进队列、按时放音效、画当前这一场。
     *
     * <p>由 {@code ClientEventHandler} 调用：没开界面时在 HUD 最上层，开着界面时在界面之上，
     * 两处互斥，保证一帧只走一次。按 F1 隐藏界面时不画，但照常计时。
     *
     * @param guiGraphics 绘图上下文
     */
    public static void render(GuiGraphics guiGraphics) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = Util.getMillis();
        if (current != null && now - current.startedAt >= current.total()) {
            current = null;
        }
        if (current == null && pending != null) {
            if (!MasteryConfig.CLIENT.rankUpAnimation.get()) {
                pending = null;
            } else if (canStart(minecraft)) {
                current = pending;
                pending = null;
                begin(current, now, minecraft);
            }
        }
        if (current == null) {
            return;
        }
        float elapsed = now - current.startedAt;
        playSounds(current, elapsed, minecraft);
        if (minecraft.options.hideGui) {
            return;
        }
        draw(guiGraphics, minecraft, current, elapsed);
    }

    /**
     * 现在能不能开播：本人在游戏里、没开界面（聊天框不算）、没隐藏界面。
     */
    private static boolean canStart(Minecraft minecraft) {
        return minecraft.player != null && minecraft.level != null && !minecraft.options.hideGui
                && (minecraft.screen == null || minecraft.screen instanceof ChatScreen);
    }

    /**
     * 开播：记下起始时刻，把这一场要用的文字、颜色、字宽都算好，后面每帧直接用。
     */
    private static void begin(Show show, long now, Minecraft minecraft) {
        Font font = minecraft.font;
        show.startedAt = now;
        show.soundsPlayed = 0;
        show.legendary = MasteryRank.isLegendary(show.newRank);
        show.holdEnd = show.legendary ? LEGENDARY_HOLD_END_MS : HOLD_END_MS;
        show.showOld = !show.initial && show.oldRank > 0;
        show.primary = RankEmblem.primaryColor(show.newRank);
        show.accent = RankEmblem.accentColor(show.newRank);
        show.titleColor = EmblemPainter.lerpColor(MasteryPalette.TEXT, show.accent, 0.45F);

        show.titleGlyphs = glyphs(Component.translatable(show.initial ? KEY_ASSESSED : KEY_PROMOTED).getString());
        show.titleAdvance = advances(font, show.titleGlyphs);
        show.titleWidth = sum(show.titleAdvance);

        show.nameGlyphs = glyphs(MasteryRank.name(show.newRank).getString());
        show.nameAdvance = advances(font, show.nameGlyphs);
        show.nameWidth = sum(show.nameAdvance) + NAME_SPACING * Math.max(0, show.nameGlyphs.length - 1);

        // 晋升包可能比经验同步先到一步，这时本地算出来的段位经验还没够新段位，按门槛兜底
        long xp = Math.max(ClientRank.selfRankXp(), MasteryRank.xpForRank(show.newRank));
        show.detail = Component.translatable(KEY_DETAIL, show.newRank,
                String.format(Locale.ROOT, "%,d", xp)).getString();
        show.detailWidth = font.width(show.detail);

        int jump = show.newRank - show.oldRank;
        show.jump = !show.initial && jump > 1 ? "+" + jump : null;
        show.jumpWidth = show.jump == null ? 0 : font.width(show.jump);
    }

    // ======================================================================
    // 音效
    // ======================================================================

    /**
     * 按时间轴放音效：蓄力时一声信标启动，揭晓时挑战完成的号角叠紫水晶的叮，段位名浮现时再叮一声。
     * 传奇多一声低沉的信标选能力音和一声高音。
     */
    private static void playSounds(Show show, float elapsed, Minecraft minecraft) {
        boolean enabled = MasteryConfig.CLIENT.rankUpSound.get();
        cue(show, 0, elapsed, 40.0F, SoundEvents.BEACON_ACTIVATE, 1.45F, 0.40F, enabled, minecraft);
        cue(show, 1, elapsed, REVEAL_MS, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.60F, enabled, minecraft);
        cue(show, 2, elapsed, REVEAL_MS, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 1.0F, enabled, minecraft);
        cue(show, 3, elapsed, NAME_IN_MS + 30.0F, SoundEvents.AMETHYST_BLOCK_CHIME, 1.75F, 0.9F, enabled, minecraft);
        if (show.legendary) {
            cue(show, 4, elapsed, REVEAL_MS, SoundEvents.BEACON_POWER_SELECT, 0.75F, 0.55F, enabled, minecraft);
            cue(show, 5, elapsed, SHIMMER_START_MS, SoundEvents.AMETHYST_BLOCK_CHIME, 2.0F, 0.7F, enabled, minecraft);
        }
    }

    private static void cue(Show show, int bit, float elapsed, float at, SoundEvent sound, float pitch, float volume,
                            boolean enabled, Minecraft minecraft) {
        int mask = 1 << bit;
        if (elapsed < at || (show.soundsPlayed & mask) != 0) {
            return;
        }
        show.soundsPlayed |= mask;
        if (enabled && elapsed - at <= SOUND_GRACE_MS) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
        }
    }

    // ======================================================================
    // 绘制
    // ======================================================================

    private static void draw(GuiGraphics guiGraphics, Minecraft minecraft, Show show, float e) {
        Font font = minecraft.font;
        int screenWidth = guiGraphics.guiWidth();
        int screenHeight = guiGraphics.guiHeight();

        // 徽章大小随界面高度走：GUI 缩放 2 的 1080p 约 92 像素，缩放 4 约 46 像素；窄窗口再按宽度压一压
        float size = Math.min(Math.max(44.0F, Math.min(96.0F, screenHeight * 0.17F)), screenWidth * 0.24F);
        // 徽章贴图是 48 像素的像素画：落定后的大小取到屏幕上 48 物理像素的整数倍，每格像素一样大
        double guiScale = minecraft.getWindow().getGuiScale();
        long cells = Math.max(1L, Math.round(size * guiScale / 48.0D));
        size = (float) (cells * 48.0D / guiScale);
        float r = size * 0.5F;
        float exit = clamp01((e - show.holdEnd) / EXIT_MS);
        float exitEase = exit * exit;
        float fade = 1.0F - exit;
        float cx = screenWidth * 0.5F;
        boolean compact = screenHeight < 300;
        float nameScale = compact ? 1.5F : 2.0F;
        float detailScale = compact ? 0.8F : 1.0F;
        // 下面各行相对徽章中心的位置；底板比文字上下各多 3 像素，再往外羽化
        float titleOffset = r * 1.34F + 4.0F;
        float nameOffset = titleOffset + font.lineHeight + 6.0F;
        float detailOffset = nameOffset + font.lineHeight * nameScale + 5.0F;
        float reachWithDetail = detailOffset + font.lineHeight * detailScale + 3.0F + SCRIM_FEATHER;
        float reachNameOnly = nameOffset + font.lineHeight * nameScale + 3.0F + SCRIM_FEATHER;
        // 徽章中心在屏幕高度的 22% 左右（矮屏 20%），顶上留出装饰环的位置。
        // 准星画在 (h - 15) / 2 往下 15 像素：下面的字连同底板的羽化都要停在它上面 2 像素。
        // 放不下就把徽章往上提，最多提到外圈装饰环刚好不出屏幕顶；
        // 矮屏提到顶还放不下，就不画「段位 N · 段位经验 X」那一行
        float preferred = Math.max(r * 1.36F + 6.0F, screenHeight * (compact ? 0.20F : 0.22F));
        float limit = (screenHeight - 15) / 2 - 2.0F;
        float highest = r * 1.30F + 2.0F;
        boolean detailShown = !compact || limit - reachWithDetail >= highest;
        float reach = detailShown ? reachWithDetail : reachNameOnly;
        float cy = Math.max(highest, Math.min(preferred, limit - reach)) - 16.0F * exitEase;

        float titleTop = cy + titleOffset;
        float nameTop = cy + nameOffset;
        float detailTop = cy + detailOffset;
        float blockBottom = cy + reach - 3.0F - SCRIM_FEATHER;

        float titleIn = smooth(TITLE_IN_MS, TITLE_IN_MS + 400.0F, e);
        float nameRaw = clamp01((e - NAME_IN_MS) / 380.0F);
        float nameIn = nameRaw * nameRaw * (3.0F - 2.0F * nameRaw);
        float detailIn = smooth(DETAIL_IN_MS, DETAIL_IN_MS + 350.0F, e);
        float ringIn = easeInOut(clamp01((e - 520.0F) / 700.0F));
        float ringAlpha = smooth(480.0F, 800.0F, e) * fade;
        float breath = 0.85F + 0.15F * (float) Math.sin(e * 0.0025F);
        float shimmer = shimmerPhase(e, show.holdEnd);
        float hueBase = 0.70F + e * 0.00012F;

        float tracking = 2.0F + 5.0F * (1.0F - easeOutCubic(clamp01((e - TITLE_IN_MS) / 500.0F)));
        float titleWidth = show.titleWidth + tracking * Math.max(0, show.titleGlyphs.length - 1);
        float nameWidth = show.nameWidth * nameScale;
        float detailWidth = show.detailWidth * detailScale;

        int primary = show.primary;
        int accent = show.accent;
        // 传奇的光以紫为主，青色只点缀；金属段位的光用高光色更亮
        int light = show.legendary ? primary : accent;

        guiGraphics.flush();
        Matrix4f matrix = guiGraphics.pose().last().pose();

        // ---- 第一层（普通混合）：压暗底、文字垫底、标题两侧的饰线、装饰环 ----
        BufferBuilder buffer = RenderUtil.begin();
        float vignette = smooth(0.0F, 300.0F, e) * fade;
        RenderUtil.radialGradient(buffer, matrix, cx, cy, r * 2.4F,
                EmblemPainter.withAlpha(0xFF000000, 0.42F * vignette), 0x00000000);
        float scrim = Math.max(titleIn, nameIn) * fade;
        if (scrim > 0.0F) {
            float half = Math.max(titleWidth, Math.max(nameWidth, detailShown ? detailWidth : 0.0F)) * 0.5F + 12.0F;
            RenderUtil.softRect(buffer, matrix, cx - half, titleTop - 3.0F, cx + half, blockBottom + 3.0F,
                    40.0F, SCRIM_FEATHER, EmblemPainter.withAlpha(0xFF000000, 0.46F * scrim));
        }
        if (titleIn > 0.0F) {
            // 标题两侧各一条向外渐隐的细线，内端一颗小菱形
            float lineY = titleTop + 3.5F;
            float length = 30.0F * easeOutCubic(titleIn);
            int lineColor = EmblemPainter.withAlpha(primary, 0.85F * titleIn * fade);
            float leftInner = cx - titleWidth * 0.5F - 7.0F;
            float rightInner = cx + titleWidth * 0.5F + 7.0F;
            RenderUtil.gradientLine(buffer, matrix, leftInner - length, lineY, leftInner, lineY, 1.0F,
                    lineColor & 0x00FFFFFF, lineColor);
            RenderUtil.gradientLine(buffer, matrix, rightInner + length, lineY, rightInner, lineY, 1.0F,
                    lineColor & 0x00FFFFFF, lineColor);
            RenderUtil.diamond(buffer, matrix, leftInner + 1.5F, lineY, 2.0F, lineColor);
            RenderUtil.diamond(buffer, matrix, rightInner - 1.5F, lineY, 2.0F, lineColor);
        }
        if (detailShown && nameIn > 0.0F) {
            // 段位名和下面那行之间一道细分隔线
            RenderUtil.lightStreak(buffer, matrix, cx, detailTop - 3.0F, nameWidth * 0.5F + 22.0F, 0.75F,
                    EmblemPainter.withAlpha(primary, 0.75F * nameIn * fade));
        }
        if (ringAlpha > 0.0F && ringIn > 0.0F) {
            drawRings(buffer, matrix, show, cx, cy, r, e, ringIn, ringAlpha, exitEase, hueBase);
        }
        RenderUtil.end();

        // ---- 第二层（叠加）：徽章背后的光芒、横向光痕、冲击波、光晕 ----
        buffer = RenderUtil.beginAdditive();
        float raysAlpha = smooth(420.0F, 1000.0F, e) * fade * breath * (show.legendary ? 0.26F : 0.18F);
        if (raysAlpha > 0.0F) {
            drawRays(buffer, matrix, show, cx, cy, r, e, raysAlpha, exitEase, hueBase);
        }
        float grow = easeOutCubic(clamp01((e - 40.0F) / 300.0F));
        float streak = grow * (1.0F - smooth(560.0F, 1150.0F, e));
        float streakLength = screenWidth * 0.34F * grow * (1.0F + 0.15F * clamp01((e - REVEAL_MS) / 800.0F));
        if (streak > 0.0F) {
            RenderUtil.lightStreak(buffer, matrix, cx, cy, streakLength, Math.max(2.5F, r * 0.09F),
                    EmblemPainter.withAlpha(light, 0.65F * streak));
        }
        float wave = (e - REVEAL_MS) / 700.0F;
        if (wave > 0.0F && wave < 1.0F) {
            float k = 1.0F - wave;
            RenderUtil.softRing(buffer, matrix, cx, cy, r * (0.75F + 1.95F * easeOutCubic(wave)),
                    r * 0.16F * k + 1.0F, EmblemPainter.withAlpha(light, 0.85F * (float) Math.pow(k, 1.6D)));
        }
        wave = (e - REVEAL_MS - 160.0F) / 900.0F;
        if (wave > 0.0F && wave < 1.0F) {
            float k = 1.0F - wave;
            RenderUtil.softRing(buffer, matrix, cx, cy, r * (0.9F + 1.45F * easeOutCubic(wave)),
                    r * 0.06F + 1.0F, EmblemPainter.withAlpha(primary, 0.5F * k * k));
        }
        if (show.legendary) {
            wave = (e - REVEAL_MS - 320.0F) / 1100.0F;
            if (wave > 0.0F && wave < 1.0F) {
                float k = 1.0F - wave;
                RenderUtil.softRing(buffer, matrix, cx, cy, r * (1.0F + 2.4F * easeOutCubic(wave)),
                        r * 0.10F + 1.0F, EmblemPainter.withAlpha(EmblemPainter.hsv(hueBase + wave, 0.5F, 1.0F),
                                0.6F * k * k));
            }
        }
        float bloom = e < REVEAL_MS ? smooth(REVEAL_MS - 220.0F, REVEAL_MS, e)
                : (float) Math.exp(-(e - REVEAL_MS) / 280.0F);
        float glow = Math.max(bloom, 0.28F * breath * smooth(500.0F, 900.0F, e)) * fade;
        if (glow > 0.0F) {
            // 光晕用金属主色，青铜是暖橙、黄金是金黄，一眼看出是哪种金属的光
            RenderUtil.radialGradient(buffer, matrix, cx, cy, r * 1.75F,
                    EmblemPainter.withAlpha(primary, 0.70F * glow), primary & 0x00FFFFFF);
        }
        RenderUtil.endAdditive();

        // ---- 徽章：旧的收进光里，新的从略大处落定 ----
        if (show.showOld && e < OLD_FADE_MS) {
            float t = e / OLD_FADE_MS;
            float oldAlpha = smooth(0.0F, 0.18F, t) * (1.0F - smooth(0.70F, 1.0F, t));
            RankEmblem.drawInternal(guiGraphics, show.oldRank, cx, cy, size * (1.0F - 0.30F * t * t),
                    oldAlpha, e / 1000.0F, -1.0F, smooth(0.25F, 0.9F, t));
        }
        if (e >= REVEAL_MS) {
            float t = clamp01((e - REVEAL_MS) / 520.0F);
            float scale = (1.36F - 0.36F * easeOutBack(t)) * (1.0F - 0.08F * exitEase);
            float alpha = clamp01((e - REVEAL_MS) / 110.0F) * fade;
            float flash = 1.0F - smooth(REVEAL_MS, REVEAL_MS + 600.0F, e);
            RankEmblem.drawInternal(guiGraphics, show.newRank, cx, cy, size * scale, alpha,
                    e / 1000.0F, shimmer, flash);
        }

        // ---- 第三层（叠加）：揭晓的白闪、光痕的白芯、火花、零星闪光 ----
        matrix = guiGraphics.pose().last().pose();
        buffer = RenderUtil.beginAdditive();
        float flashGlow = e < REVEAL_MS ? 0.6F * smooth(200.0F, REVEAL_MS, e)
                : (float) Math.exp(-(e - REVEAL_MS) / 170.0F);
        if (flashGlow > 0.01F) {
            RenderUtil.radialGradient(buffer, matrix, cx, cy, r * (e < REVEAL_MS ? 0.6F : 1.2F),
                    EmblemPainter.withAlpha(0xFFFFFFFF, 0.85F * flashGlow), 0x00FFFFFF);
        }
        if (streak > 0.0F) {
            RenderUtil.lightStreak(buffer, matrix, cx, cy, streakLength * 0.9F, Math.max(1.0F, r * 0.02F),
                    EmblemPainter.withAlpha(0xFFFFFFFF, 0.9F * streak));
        }
        drawSparks(buffer, matrix, show, cx, cy, r, e, fade, hueBase);
        float glints = smooth(1300.0F, 1600.0F, e) * fade;
        if (glints > 0.0F) {
            drawGlints(buffer, matrix, cx, cy, r, e, glints, show.legendary ? primary : accent);
        }
        RenderUtil.endAdditive();

        // ---- 文字 ----
        drawTitle(guiGraphics, font, show, cx, titleTop, titleWidth, tracking, titleIn * fade);
        drawName(guiGraphics, font, show, cx, nameTop + 8.0F * (1.0F - easeOutCubic(nameRaw)),
                nameScale, nameIn * fade, shimmer, hueBase);
        int detailColor = detailShown ? textColor(MasteryPalette.TEXT, 0.9F * detailIn * fade) : 0;
        if (detailColor != 0) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(cx - detailWidth * 0.5F, detailTop, 0.0F);
            pose.scale(detailScale, detailScale, 1.0F);
            guiGraphics.drawString(font, show.detail, 0.0F, 0.0F, detailColor, true);
            pose.popPose();
        }
        if (show.jump != null && e >= JUMP_IN_MS) {
            drawJump(guiGraphics, font, show, cx + r * 0.80F, cy - r * 0.78F,
                    clamp01((e - JUMP_IN_MS) / 260.0F), fade);
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
        // 整场都按抬高的 z 写了深度（开着界面时是 850）。界面画完到原版 toast 之间不清深度，
        // 不清掉的话，后画、z 更低的原版 toast（800）和别的模组的覆盖层在暗角、光痕扫过的地方会被挖掉一块。
        // 先把还攒在批里的字刷出去，免得它们在清完之后才落下、又把深度写回来
        guiGraphics.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    /**
     * 两圈装饰环：内圈十二段弧顺时针转，外圈三段长弧逆时针转，弧端各挂一颗菱形，外面一圈刻度。
     */
    private static void drawRings(BufferBuilder buffer, Matrix4f matrix, Show show, float cx, float cy, float r,
                                  float e, float ringIn, float ringAlpha, float exitEase, float hueBase) {
        float r1 = r * 1.13F * (1.0F + 0.10F * exitEase);
        float t1 = Math.max(1.0F, r * 0.035F);
        float spin1 = e * 0.00030F;
        for (int k = 0; k < 12; k++) {
            int color = show.legendary ? EmblemPainter.hsv(hueBase + k / 12.0F, 0.45F, 1.0F) : show.primary;
            RenderUtil.ring(buffer, matrix, cx, cy, r1 - t1 * 0.5F, r1 + t1 * 0.5F,
                    EmblemPainter.withAlpha(color, 0.80F * ringAlpha), spin1 + k * PI / 6.0F, 0.38F * ringIn);
        }
        float r2 = r * 1.26F * (1.0F + 0.14F * exitEase);
        float t2 = Math.max(1.0F, r * 0.022F);
        float spin2 = 0.6F - e * 0.00018F;
        int outer = EmblemPainter.withAlpha(show.accent, 0.55F * ringAlpha);
        int node = EmblemPainter.withAlpha(show.accent, 0.90F * ringAlpha);
        for (int k = 0; k < 3; k++) {
            float start = spin2 + k * PI * 2.0F / 3.0F;
            float sweep = 1.30F * ringIn;
            RenderUtil.ring(buffer, matrix, cx, cy, r2 - t2 * 0.5F, r2 + t2 * 0.5F, outer, start, sweep);
            float end = start + sweep;
            RenderUtil.diamond(buffer, matrix, cx + (float) Math.cos(end) * r2, cy + (float) Math.sin(end) * r2,
                    Math.max(1.5F, r * 0.045F), node);
        }
        if (r >= 30.0F) {
            int tick = EmblemPainter.withAlpha(show.primary, 0.35F * ringAlpha * ringIn);
            float inner = r2 + 3.0F;
            float outerTick = inner + r * 0.05F;
            for (int k = 0; k < 36; k++) {
                float angle = spin2 + k * PI / 18.0F;
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);
                RenderUtil.line(buffer, matrix, cx + cos * inner, cy + sin * inner,
                        cx + cos * outerTick, cy + sin * outerTick, 1.0F, tick);
            }
        }
    }

    /**
     * 徽章背后缓慢旋转的光芒，长短交错，根部亮、梢部透明。
     */
    private static void drawRays(BufferBuilder buffer, Matrix4f matrix, Show show, float cx, float cy, float r,
                                 float e, float alpha, float exitEase, float hueBase) {
        int rays = show.legendary ? 16 : 12;
        float spin = e * 0.00012F;
        float inner = r * 0.62F;
        for (int i = 0; i < rays; i++) {
            float angle = spin + i * PI * 2.0F / rays;
            float length = r * ((i & 1) == 0 ? 2.5F : 1.85F) * (1.0F + 0.10F * exitEase);
            int color = show.legendary
                    ? EmblemPainter.hsv(hueBase + (float) i / rays, 0.45F, 1.0F) : show.primary;
            int root = EmblemPainter.withAlpha(color, alpha);
            int tip = color & 0x00FFFFFF;
            RenderUtil.gradientQuad(buffer, matrix,
                    cx + (float) Math.cos(angle - 0.11F) * inner, cy + (float) Math.sin(angle - 0.11F) * inner, root,
                    cx + (float) Math.cos(angle + 0.11F) * inner, cy + (float) Math.sin(angle + 0.11F) * inner, root,
                    cx + (float) Math.cos(angle + 0.035F) * length, cy + (float) Math.sin(angle + 0.035F) * length, tip,
                    cx + (float) Math.cos(angle - 0.035F) * length, cy + (float) Math.sin(angle - 0.035F) * length, tip);
        }
    }

    /**
     * 揭晓时四散的火花：带拖尾，飞出去减速、变细、淡出。
     */
    private static void drawSparks(BufferBuilder buffer, Matrix4f matrix, Show show, float cx, float cy, float r,
                                   float e, float fade, float hueBase) {
        int count = show.legendary ? SPARKS_LEGENDARY : SPARKS;
        float width = Math.max(1.0F, r * 0.024F);
        for (int i = 0; i < count; i++) {
            float age = (e - REVEAL_MS - SPARK_DELAY[i]) / SPARK_LIFE[i];
            if (age <= 0.0F || age >= 1.0F) {
                continue;
            }
            float travel = r * (0.55F + SPARK_SPEED[i] * easeOutCubic(age) * 2.1F);
            float headX = cx + SPARK_COS[i] * travel;
            float headY = cy + SPARK_SIN[i] * travel;
            float tail = r * SPARK_LENGTH[i] * (1.0F - age) + 1.5F;
            int color = show.legendary
                    ? EmblemPainter.hsv(hueBase + SPARK_HUE[i], 0.5F, 1.0F)
                    : EmblemPainter.lerpColor(0xFFFFFFFF, show.accent, age);
            int head = EmblemPainter.withAlpha(color, (float) Math.pow(1.0F - age, 1.3D) * fade);
            RenderUtil.gradientLine(buffer, matrix, headX - SPARK_COS[i] * tail, headY - SPARK_SIN[i] * tail,
                    headX, headY, width * (1.0F - 0.4F * age), head & 0x00FFFFFF, head);
        }
    }

    /**
     * 停留时徽章周围零星亮起的十字闪光，各自错开节奏。
     */
    private static void drawGlints(BufferBuilder buffer, Matrix4f matrix, float cx, float cy, float r, float e,
                                   float gate, int color) {
        for (int i = 0; i < GLINTS; i++) {
            float cycle = ((e * 0.001F + GLINT_PHASE[i]) % 1.9F) / 0.55F;
            if (cycle >= 1.0F) {
                continue;
            }
            float k = (float) Math.sin(PI * cycle);
            float intensity = k * k * gate;
            if (intensity <= 0.01F) {
                continue;
            }
            float x = cx + GLINT_COS[i] * r * GLINT_RADIUS[i];
            float y = cy + GLINT_SIN[i] * r * GLINT_RADIUS[i];
            float arm = r * 0.12F * (0.6F + 0.4F * k);
            int bright = EmblemPainter.withAlpha(color, 0.9F * intensity);
            int clear = color & 0x00FFFFFF;
            RenderUtil.gradientLine(buffer, matrix, x, y, x + arm, y, 1.2F, bright, clear);
            RenderUtil.gradientLine(buffer, matrix, x, y, x - arm, y, 1.2F, bright, clear);
            RenderUtil.gradientLine(buffer, matrix, x, y, x, y + arm, 1.2F, bright, clear);
            RenderUtil.gradientLine(buffer, matrix, x, y, x, y - arm, 1.2F, bright, clear);
        }
    }

    /**
     * 小字标题，字距从宽收拢到正常。
     */
    private static void drawTitle(GuiGraphics guiGraphics, Font font, Show show, float cx, float top,
                                  float width, float tracking, float alpha) {
        int color = textColor(show.titleColor, alpha);
        if (color == 0) {
            return;
        }
        float x = cx - width * 0.5F;
        for (int i = 0; i < show.titleGlyphs.length; i++) {
            guiGraphics.drawString(font, show.titleGlyphs[i], x, top, color, true);
            x += show.titleAdvance[i] + tracking;
        }
    }

    /**
     * 段位名：放大、金属色，流光扫过时逐字提亮；传奇逐字按色相流转。
     */
    private static void drawName(GuiGraphics guiGraphics, Font font, Show show, float cx, float top,
                                 float scale, float alpha, float shimmer, float hueBase) {
        if (textColor(0xFFFFFFFF, alpha) == 0) {
            return;
        }
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(cx - show.nameWidth * scale * 0.5F, top, 0.0F);
        pose.scale(scale, scale, 1.0F);
        // 流光的位置：从左边外面扫到右边外面
        float head = shimmer < 0.0F ? Float.NaN : -16.0F + (show.nameWidth + 32.0F) * shimmer;
        float x = 0.0F;
        for (int i = 0; i < show.nameGlyphs.length; i++) {
            float advance = show.nameAdvance[i];
            int base = show.legendary
                    ? EmblemPainter.hsv(hueBase + i * 0.07F, 0.38F, 1.0F) : show.primary;
            if (head == head) {
                float distance = Math.abs(x + advance * 0.5F - head) / 12.0F;
                if (distance < 1.0F) {
                    float k = 1.0F - distance;
                    base = EmblemPainter.lerpColor(base, 0xFFFFFFFF, 0.85F * k * k);
                }
            }
            guiGraphics.drawString(font, show.nameGlyphs[i], x, 0.0F, textColor(base, alpha), true);
            x += advance + NAME_SPACING;
        }
        pose.popPose();
    }

    /**
     * 连升好几段时挂在徽章右上角的「+N」，弹出来带一点回弹。
     */
    private static void drawJump(GuiGraphics guiGraphics, Font font, Show show, float x, float y,
                                 float t, float fade) {
        float alpha = Math.min(1.0F, t * 3.0F) * fade;
        int textColor = textColor(show.accent, alpha);
        if (textColor == 0) {
            return;
        }
        float scale = Math.max(0.05F, easeOutBack(t));
        int half = show.jumpWidth / 2 + 3;
        int edge = EmblemPainter.withAlpha(show.primary, alpha);
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0F);
        pose.scale(scale, scale, 1.0F);
        guiGraphics.fill(-half, -6, half, 6, EmblemPainter.withAlpha(0xF0130F09, alpha));
        guiGraphics.fill(-half + 1, -7, half - 1, -6, edge);
        guiGraphics.fill(-half + 1, 6, half - 1, 7, edge);
        guiGraphics.fill(-half - 1, -6, -half, 6, edge);
        guiGraphics.fill(half, -6, half + 1, 6, edge);
        guiGraphics.drawString(font, show.jump, -show.jumpWidth / 2.0F, -4.0F, textColor, false);
        pose.popPose();
    }

    // ======================================================================
    // 工具
    // ======================================================================

    /**
     * 段位名流光的进度：揭晓后扫一次，停留期间每隔一段再扫，退场前不再起新的。
     *
     * @return 0~1，不在扫的时候返回 -1
     */
    private static float shimmerPhase(float e, float holdEnd) {
        if (e < SHIMMER_START_MS || e > holdEnd) {
            return -1.0F;
        }
        float cycle = (e - SHIMMER_START_MS) % SHIMMER_PERIOD_MS;
        return cycle < SHIMMER_MS ? cycle / SHIMMER_MS : -1.0F;
    }

    /**
     * 文字颜色乘上透明度。透明度太低时返回 0 表示别画：Font 会把透明度低于 4 的颜色当成不透明。
     */
    private static int textColor(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * clamp01(alpha));
        return a < 5 ? 0 : (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * 按码点拆成单字，逐字画字距和流光用。
     */
    private static String[] glyphs(String text) {
        String[] out = new String[text.codePointCount(0, text.length())];
        int index = 0;
        for (int i = 0; i < text.length(); ) {
            int next = i + Character.charCount(text.codePointAt(i));
            out[index++] = text.substring(i, next);
            i = next;
        }
        return out;
    }

    private static float[] advances(Font font, String[] glyphs) {
        float[] out = new float[glyphs.length];
        for (int i = 0; i < glyphs.length; i++) {
            out[i] = font.width(glyphs[i]);
        }
        return out;
    }

    private static float sum(float[] values) {
        float total = 0.0F;
        for (float value : values) {
            total += value;
        }
        return total;
    }

    private static float clamp01(float value) {
        return value < 0.0F ? 0.0F : Math.min(1.0F, value);
    }

    /**
     * 平滑阶跃：x 从 edge0 到 edge1 时由 0 平滑过渡到 1。
     */
    private static float smooth(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0F - 2.0F * t);
    }

    private static float easeOutCubic(float t) {
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;
    }

    private static float easeInOut(float t) {
        return t < 0.5F ? 4.0F * t * t * t : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 3.0D) / 2.0F;
    }

    /**
     * 末尾越过 1 再回落，落定时有一下顿挫。
     */
    private static float easeOutBack(float t) {
        float inv = t - 1.0F;
        return 1.0F + inv * inv * (2.2F * inv + 1.2F);
    }

    /**
     * 按序号和盐值算一个固定的 0~1 伪随机数：每次启动都一样，火花的样子是定的。
     */
    private static float hash(int index, int salt) {
        int h = index * 0x27D4EB2D ^ salt * 0x165667B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return (h >>> 8) / 16777216.0F;
    }

    /**
     * 一场晋升动画。开播前只有段位三个字段有意义，其余在 {@link #begin} 里填。
     */
    private static final class Show {

        int oldRank;
        int newRank;
        boolean initial;

        long startedAt;
        int soundsPlayed;
        boolean legendary;
        boolean showOld;
        float holdEnd;
        int primary;
        int accent;
        int titleColor;
        String[] titleGlyphs;
        float[] titleAdvance;
        float titleWidth;
        String[] nameGlyphs;
        float[] nameAdvance;
        float nameWidth;
        String detail;
        int detailWidth;
        String jump;
        int jumpWidth;

        Show(int oldRank, int newRank, boolean initial) {
            this.oldRank = oldRank;
            this.newRank = newRank;
            this.initial = initial;
        }

        /**
         * @return 整场时长（毫秒）
         */
        float total() {
            return this.holdEnd + EXIT_MS;
        }
    }
}
