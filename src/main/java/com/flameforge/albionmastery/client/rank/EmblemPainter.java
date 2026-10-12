package com.flameforge.albionmastery.client.rank;

/**
 * 段位徽章的几何与上色。
 *
 * <p>只管算顶点，不碰任何渲染接口：每个顶点的位置和颜色算好后交给 {@link Sink}，
 * 由 {@link RankEmblem} 接到 Tesselator 上。拆成纯 Java 是为了能脱离游戏单独出图、检查造型。
 *
 * <p>没有贴图，金属感全靠逐顶点颜色：
 * <ul>
 *     <li>光从左上来，整体左上亮、右下暗；</li>
 *     <li>星芒、菱形、羽片拆成刻面，每个刻面按自己外边的朝向受光，中线是一道亮棱，像切割过的金属；</li>
 *     <li>边框外沿受光、内沿背光，看起来是凸起的一圈；釉面反过来，看起来是凹进去的；</li>
 *     <li>外框先垫一层放大一圈的暗色剪影当描边，纹章先垫一层错开的投影，任何底色上都能看清轮廓。</li>
 * </ul>
 *
 * <p>造型的递进：前四组（启蒙者 ~ 探求者）是一枚圆章，只换中间的纹章；从巡猎者起外轮廓开始变——
 * 四芒、双翼、六角、龙鳞八芒、日轮十二芒、双方八角星，最后宗师加轮环、至崇宗师加冠；
 * 传奇是虚空紫的底配一圈流动的棱镜光。同一组的三段只换金属色，白银在边框上多四颗铆钉，黄金八颗。
 *
 * <p>坐标统一用「单位坐标」：徽章中心为原点，半边长为 1，y 朝下（和屏幕一致）。
 * 整个类只在渲染线程用，状态放在字段里反复用，画一次不产生任何新对象。
 */
final class EmblemPainter {

    /**
     * 顶点去向。
     */
    interface Sink {

        /**
         * 收一个顶点。每三个顶点是一个三角形。
         *
         * @param x    屏幕 X
         * @param y    屏幕 Y
         * @param argb 颜色
         */
        void vertex(float x, float y, int argb);
    }

    /** 金属种类，顺序和 {@code MasteryRank.Metal} 一致。 */
    static final int METAL_NONE = 0;
    static final int METAL_BRONZE = 1;
    static final int METAL_SILVER = 2;
    static final int METAL_GOLD = 3;
    static final int METAL_LEGENDARY = 4;

    /** 传奇段位在 {@link #MEDALLION} 里的下标。 */
    static final int TIER_LEGENDARY = 10;

    /**
     * 每种金属的五级色阶：最暗 / 暗 / 中 / 亮 / 高光。顶点按「亮度」在这五级之间插值。
     */
    private static final int[][] RAMPS = {
            // 0 段：灰褐，没有金属光泽
            {0xFF0F0C08, 0xFF2E281F, 0xFF5A5040, 0xFF8A7D63, 0xFFB8AA8A},
            // 青铜
            {0xFF1E0F06, 0xFF5A3218, 0xFFA2622F, 0xFFDDA06A, 0xFFFFE2C0},
            // 白银：偏冷一点，和黄金一眼分开
            {0xFF12161B, 0xFF4E5864, 0xFF98A3B0, 0xFFD9E0E8, 0xFFFFFFFF},
            // 黄金
            {0xFF1F1405, 0xFF6E4A0E, 0xFFC9961F, 0xFFFFD45A, 0xFFFFF6D0},
            // 传奇：虚空紫，边框另外叠棱镜色
            {0xFF0B0614, 0xFF2E1650, 0xFF7A4FD0, 0xFFC4A8FF, 0xFFF4EEFF},
    };

    /** 段位名文字用的主色，下标同金属种类。 */
    static final int[] PRIMARY = {0xFF9C8E70, 0xFFE3A56C, 0xFFDCE3EC, 0xFFFFD65E, 0xFFC9A8FF};

    /** 高光色，下标同金属种类。传奇用青色，和紫色主色拉开。 */
    static final int[] ACCENT = {0xFFC8BC9E, 0xFFFFDDB8, 0xFFFFFFFF, 0xFFFFF4C2, 0xFF8FF2FF};

    /**
     * 每组圆章的半径。外轮廓越复杂，圆章让出的地方越多，整体占的面积大致相当。
     * 下标 0~9 是十组，10 是传奇。
     */
    private static final float[] MEDALLION = {
            0.86F, 0.86F, 0.86F, 0.84F, 0.78F, 0.60F, 0.72F, 0.70F, 0.66F, 0.62F, 0.68F
    };

    /** 苍鹰飞羽的长度和半宽，自上而下。 */
    private static final float[] WING_LENGTH = {0.62F, 0.60F, 0.56F, 0.48F};
    private static final float[] WING_WIDTH = {0.165F, 0.16F, 0.15F, 0.13F};

    /** 釉面半径占圆章半径的比例，剩下的是金属边框。 */
    private static final float ENAMEL = 0.80F;

    /** 低于这个像素尺寸就去掉细节（细线、铆钉、内圈阴影），不然 12~16 像素时糊成一团。 */
    static final float DETAIL_SIZE = 20.0F;

    // ---- 光照 ----

    /** 光的方向（指向光源）：左上。 */
    private static final float LIGHT_U = -0.70710677F;
    private static final float LIGHT_V = -0.70710677F;

    /** 整体由左上到右下的明暗幅度。 */
    private static final float FACE_GRAD = 0.20F;

    /** 刻面朝向带来的明暗幅度。 */
    private static final float FACET = 0.22F;

    /** 刻面中心那条棱额外提亮多少。 */
    private static final float RIDGE = 0.07F;

    /** 扫光的强度和半宽（单位坐标里沿对角线量）。 */
    private static final float GLOSS = 0.34F;
    private static final float GLOSS_WIDTH = 0.42F;

    // ---- 各部件的基础亮度 ----

    private static final float LEVEL_SIGIL = 0.70F;
    private static final float LEVEL_FRAME = 0.52F;
    private static final float LEVEL_RIM = 0.60F;

    /** 外框描边色。 */
    private static final int OUTLINE = 0xD8050302;

    private static final float UP = (float) (-Math.PI / 2.0D);
    private static final float DOWN = (float) (Math.PI / 2.0D);
    private static final float PI = (float) Math.PI;

    /** 整圈的查表分段数，画圆不用现算三角函数。 */
    private static final int TABLE = 96;
    private static final float[] COS = new float[TABLE + 1];
    private static final float[] SIN = new float[TABLE + 1];

    static {
        for (int i = 0; i <= TABLE; i++) {
            double angle = Math.PI * 2.0D * i / TABLE;
            COS[i] = (float) Math.cos(angle);
            SIN[i] = (float) Math.sin(angle);
        }
    }

    // ---- 每次绘制的状态 ----

    private Sink sink;
    private float originX;
    private float originY;
    private float unit;
    private float alpha;
    private float flash;
    private float glossPos;
    private float time;
    private int[] ramp = RAMPS[0];
    private boolean detail;

    /** 剪影模式：所有顶点统一用 {@link #silhouetteColor}，用来垫描边和投影。 */
    private boolean silhouette;
    private int silhouetteColor;
    private float inflate;
    private float offsetU;
    private float offsetV;

    /**
     * 画一枚徽章。
     *
     * @param sink       顶点去向
     * @param tier       组号：-1 = 0 段，0~9 = 十组，{@link #TIER_LEGENDARY} = 传奇
     * @param metal      金属种类
     * @param cx         中心 X
     * @param cy         中心 Y
     * @param size       整体边长
     * @param alpha      整体透明度
     * @param time       动画时间（秒），只影响传奇的流光和至崇宗师的宝石呼吸
     * @param glossPhase 扫光进度 0~1，小于 0 表示不扫
     * @param flash      向白色闪的程度 0~1，晋升动画用
     */
    void paint(Sink sink, int tier, int metal, float cx, float cy, float size, float alpha,
               float time, float glossPhase, float flash) {
        this.sink = sink;
        this.originX = cx;
        this.originY = cy;
        this.unit = size * 0.5F;
        this.alpha = clamp01(alpha);
        this.time = time;
        this.flash = clamp01(flash);
        this.glossPos = glossPhase < 0.0F || glossPhase > 1.0F ? Float.NaN : -1.5F + 3.0F * glossPhase;
        this.ramp = RAMPS[Math.max(0, Math.min(RAMPS.length - 1, metal))];
        this.detail = size >= DETAIL_SIZE;
        solid();
        try {
            if (tier < 0) {
                paintUnranked();
                return;
            }
            int index = Math.min(tier, TIER_LEGENDARY);
            float m = MEDALLION[index];
            float e = m * ENAMEL;

            // 外框：先垫放大一圈的暗色剪影当描边，再画金属本体
            outlinePass();
            frame(index, metal);
            solid();
            frame(index, metal);

            medallion(m, metal);

            // 纹章：先垫一层错开的投影，再画本体，浮在釉面上
            shadowPass();
            sigil(index, metal, e);
            solid();
            sigil(index, metal, e);

            if (this.detail && index < TIER_LEGENDARY) {
                studs(m, e, metal);
            }
        } finally {
            this.sink = null;
        }
    }

    // ======================================================================
    // 各组造型
    // ======================================================================

    /**
     * 0 段：一个暗淡的空心记号，表示还没有段位。
     */
    private void paintUnranked() {
        this.silhouette = true;
        this.silhouetteColor = 0x80000000;
        this.inflate = Math.max(0.05F, 0.6F / this.unit);
        band(0.0F, 0.0F, 0.58F, 0.70F, 0, 0.0F, 0.0F, 0.0F);
        band(0.0F, 0.0F, 0.15F, 0.31F, 4, UP, 0.0F, 0.0F);
        solid();
        band(0.0F, 0.0F, 0.58F, 0.70F, 0, 0.0F, 0.50F, 0.14F);
        band(0.0F, 0.0F, 0.15F, 0.31F, 4, UP, 0.56F, 0.12F);
    }

    /**
     * 圆章本体：暗边、内凹的釉面、凸起的金属边框。
     *
     * @param m     圆章半径
     * @param metal 金属种类
     */
    private void medallion(float m, int metal) {
        float e = m * ENAMEL;
        // 外沿一圈暗边：亮天空、雪地上也能看出徽章的轮廓
        outlinePass();
        disc(0.0F, 0.0F, m, 0.0F, 0.0F, 0.0F);
        solid();
        if (metal == METAL_LEGENDARY) {
            // 虚空：中心透出一点紫光，四周沉到黑
            disc(0.0F, 0.0F, e, 0.03F, 0.30F, -0.03F);
        } else {
            // 釉面内凹：左上被边框挡住偏暗、右下偏亮
            disc(0.0F, 0.0F, e, 0.13F, 0.05F, -0.08F);
        }
        band(0.0F, 0.0F, e, m, 0, 0.0F, metal == METAL_GOLD ? LEVEL_RIM + 0.04F : LEVEL_RIM, 0.30F);
        if (this.detail) {
            // 边框在釉面上投下的一圈阴影
            band(0.0F, 0.0F, e * 0.93F, e, 0, 0.0F, 0.02F, 0.0F);
        }
    }

    /**
     * 圆章外面的轮廓。前四组没有。
     *
     * @param tier  组号
     * @param metal 金属种类
     */
    private void frame(int tier, int metal) {
        switch (tier) {
            case 4 -> {
                // 巡猎者：四个方位各伸出一枚锋刃
                star(0.0F, 0.0F, 4, 0.98F, 0.62F, UP, LEVEL_FRAME);
            }
            case 5 -> {
                // 苍鹰：两翼各四片飞羽，从肩部扇形张开、向上扬起，正上方是鹰首
                for (int side = -1; side <= 1; side += 2) {
                    for (int k = 3; k >= 0; k--) {
                        float angle = -1.08F + 0.33F * k;
                        if (side < 0) {
                            angle = PI - angle;
                        }
                        blade(0.42F * side, -0.06F, angle, WING_LENGTH[k], WING_WIDTH[k],
                                LEVEL_FRAME + 0.10F - 0.06F * k);
                    }
                }
                kite(0.0F, -0.70F, UP, 0.26F, 0.22F, 0.15F, LEVEL_FRAME + 0.08F);
            }
            case 6 -> {
                // 猎虎：一块切面六角板
                star(0.0F, 0.0F, 6, 0.96F, 0.96F * 0.8660254F, UP, LEVEL_FRAME);
                if (this.detail && !this.silhouette) {
                    for (int k = 0; k < 6; k++) {
                        float angle = UP + PI / 3.0F * k;
                        disc((float) Math.cos(angle) * 0.86F, (float) Math.sin(angle) * 0.86F,
                                0.035F, 0.82F, 0.12F, 0.12F);
                    }
                }
            }
            case 7 -> {
                // 天龙：长短交错的十六枚鳞刺
                star(0.0F, 0.0F, 8, 0.84F, 0.56F, UP + PI / 8.0F, LEVEL_FRAME - 0.14F);
                star(0.0F, 0.0F, 8, 0.99F, 0.58F, UP, LEVEL_FRAME);
            }
            case 8 -> {
                // 圣者：十二道日芒，外加一圈光轮
                for (int k = 0; k < 6; k++) {
                    float angle = UP + PI / 6.0F + PI / 3.0F * k;
                    blade((float) Math.cos(angle) * 0.46F, (float) Math.sin(angle) * 0.46F,
                            angle, 0.38F, 0.07F, LEVEL_FRAME - 0.10F);
                }
                for (int k = 0; k < 6; k++) {
                    float angle = UP + PI / 3.0F * k;
                    blade((float) Math.cos(angle) * 0.46F, (float) Math.sin(angle) * 0.46F,
                            angle, 0.53F, 0.088F, LEVEL_FRAME + 0.04F);
                }
                if (this.detail) {
                    band(0.0F, 0.0F, 0.76F, 0.81F, 0, 0.0F, LEVEL_FRAME + 0.08F, 0.28F);
                }
            }
            case 9 -> {
                // 大师一组：两块方板叠成八角星；宗师加轮环，至崇宗师再加冠和一圈细光芒
                if (metal >= METAL_GOLD) {
                    for (int k = 0; k < 8; k++) {
                        float angle = UP + PI / 8.0F + PI / 4.0F * k;
                        blade((float) Math.cos(angle) * 0.55F, (float) Math.sin(angle) * 0.55F,
                                angle, 0.42F, 0.035F, LEVEL_FRAME + 0.16F);
                    }
                }
                star(0.0F, 0.0F, 4, 0.92F, 0.92F * 0.70710677F, UP + PI / 4.0F, LEVEL_FRAME - 0.10F);
                star(0.0F, 0.0F, 4, 0.97F, 0.97F * 0.70710677F, UP, LEVEL_FRAME);
                if (metal >= METAL_SILVER) {
                    band(0.0F, 0.0F, 0.77F, 0.84F, 0, 0.0F, LEVEL_FRAME + 0.10F, 0.30F);
                    if (this.detail && !this.silhouette) {
                        for (int k = 0; k < 8; k++) {
                            float angle = UP + PI / 8.0F + PI / 4.0F * k;
                            disc((float) Math.cos(angle) * 0.805F, (float) Math.sin(angle) * 0.805F,
                                    0.042F, 0.86F, 0.14F, 0.14F);
                        }
                    }
                }
                if (metal >= METAL_GOLD) {
                    // 冠：正上方一根长尖、两侧两根副尖，比八角星的尖角更亮
                    blade(0.0F, -0.50F, UP, 0.50F, 0.085F, LEVEL_FRAME + 0.24F);
                    for (int side = -1; side <= 1; side += 2) {
                        float angle = UP + PI / 8.0F * side;
                        blade((float) Math.cos(angle) * 0.50F, (float) Math.sin(angle) * 0.50F,
                                angle, 0.46F, 0.07F, LEVEL_FRAME + 0.20F);
                    }
                }
            }
            case TIER_LEGENDARY -> {
                // 传奇：一圈虚空紫的光晕，斜向四芒衬在下面，棱镜环压在上面，正向四芒刺穿棱镜环
                if (this.detail && !this.silhouette) {
                    glow(0.0F, 0.0F, 1.0F, 0x806A3CC8);
                }
                star(0.0F, 0.0F, 4, 0.96F, 0.30F, UP + PI / 4.0F, 0.56F);
                prismBand(this.detail ? 0.81F : 0.79F, this.detail ? 0.91F : 0.95F);
                star(0.0F, 0.0F, 4, 1.0F, 0.34F, UP, 0.84F);
            }
            default -> {
                // 前四组只有圆章
            }
        }
    }

    /**
     * 釉面上的纹章。
     *
     * @param tier  组号
     * @param metal 金属种类
     * @param e     釉面半径
     */
    private void sigil(int tier, int metal, float e) {
        float level = LEVEL_SIGIL;
        switch (tier) {
            case 0 -> {
                // 启蒙者：地平线上的一点火光
                kite(0.0F, -0.10F * e, UP, 0.70F * e, 0.42F * e, 0.22F * e, level);
                if (this.detail) {
                    bar(-0.50F * e, 0.56F * e, 0.50F * e, 0.56F * e, 0.07F * e, level - 0.06F);
                    star(-0.46F * e, -0.30F * e, 4, 0.14F * e, 0.04F * e, UP, level + 0.12F);
                    star(0.46F * e, -0.30F * e, 4, 0.14F * e, 0.04F * e, UP, level + 0.12F);
                }
            }
            case 1 -> {
                // 信徒：一枚切面三角，中间一只眼
                star(0.0F, 0.08F * e, 3, 0.74F * e, 0.37F * e, UP, level);
                if (!this.silhouette) {
                    disc(0.0F, 0.08F * e, 0.13F * e, 0.06F, 0.0F, -0.04F);
                    if (this.detail) {
                        disc(0.0F, 0.08F * e, 0.05F * e, 0.95F, 0.0F, 0.0F);
                    }
                }
            }
            case 2 -> {
                // 门徒：三角框里嵌一枚圆
                float inner = this.detail ? 0.46F * e : 0.34F * e;
                band(0.0F, 0.04F * e, inner, 0.78F * e, 3, UP, level, 0.26F);
                disc(0.0F, 0.04F * e, inner * 0.40F, level + 0.08F, 0.14F, 0.16F);
            }
            case 3 -> {
                // 探求者：罗盘，南北长、东西短，背后衬一层斜向的短芒
                star(0.0F, 0.0F, 4, 0.56F * e, 0.12F * e, UP + PI / 4.0F, level - 0.26F);
                starAlternating(0.0F, 0.0F, 4, 0.86F * e, 0.52F * e, 0.15F * e, UP, level);
                if (this.detail && !this.silhouette) {
                    disc(0.0F, 0.0F, 0.09F * e, 0.92F, 0.10F, 0.10F);
                }
            }
            case 4 -> {
                // 巡猎者：箭头 + 两道军衔折纹
                float tipV = -0.80F * e;
                float notchV = -0.30F * e;
                float barbU = 0.50F * e;
                float barbV = -0.10F * e;
                facet(0.0F, notchV, -barbU, barbV, 0.0F, tipV, level);
                facet(0.0F, notchV, 0.0F, tipV, barbU, barbV, level);
                float w = this.detail ? 0.09F * e : 0.13F * e;
                bar(-0.46F * e, 0.30F * e, 0.0F, 0.04F * e, w, level - 0.04F);
                bar(0.0F, 0.04F * e, 0.46F * e, 0.30F * e, w, level - 0.04F);
                if (this.detail) {
                    bar(-0.40F * e, 0.62F * e, 0.0F, 0.36F * e, w, level - 0.10F);
                    bar(0.0F, 0.36F * e, 0.40F * e, 0.62F * e, w, level - 0.10F);
                }
            }
            case 5 -> {
                // 苍鹰：俯冲的鹰，身子是一枚朝下的尖刃，两翼向后收成 V
                float w = this.detail ? 0.075F * e : 0.11F * e;
                bar(-0.66F * e, -0.44F * e, -0.05F * e, 0.08F * e, w, level - 0.06F);
                bar(0.05F * e, 0.08F * e, 0.66F * e, -0.44F * e, w, level - 0.06F);
                kite(0.0F, 0.0F, DOWN, 0.70F * e, 0.42F * e, 0.17F * e, level);
            }
            case 6 -> {
                // 猎虎：三道爪痕
                float angle = 2.15F;
                float du = (float) Math.cos(angle);
                float dv = (float) Math.sin(angle);
                float w = this.detail ? 0.12F * e : 0.15F * e;
                for (int k = -1; k <= 1; k++) {
                    float offset = 0.36F * e * k;
                    float half = (k == 0 ? 0.82F : 0.66F) * e;
                    kite(-dv * offset, du * offset, angle, half, half, w, level);
                }
            }
            case 7 -> {
                // 天龙：竖瞳
                lens(0.0F, 0.0F, 0.86F * e, 0.46F * e, level, 0.12F);
                if (!this.silhouette) {
                    disc(0.0F, 0.0F, 0.30F * e, level - 0.24F, 0.10F, 0.10F);
                    kite(0.0F, 0.0F, UP, 0.40F * e, 0.40F * e, 0.10F * e, 0.03F);
                    if (this.detail) {
                        disc(-0.20F * e, -0.15F * e, 0.055F * e, 1.0F, 0.0F, 0.0F);
                    }
                }
            }
            case 8 -> {
                // 圣者：六芒星里一簇火
                if (this.detail) {
                    band(0.0F, 0.0F, 0.60F * e, 0.84F * e, 3, UP, level - 0.04F, 0.22F);
                    band(0.0F, 0.0F, 0.60F * e, 0.84F * e, 3, DOWN, level - 0.04F, 0.22F);
                    kite(0.0F, 0.04F * e, UP, 0.36F * e, 0.20F * e, 0.15F * e, level + 0.14F);
                } else {
                    star(0.0F, 0.0F, 6, 0.86F * e, 0.86F * e * 0.57735F, UP, level);
                }
            }
            case 9 -> {
                // 大师一组：菱形里套三角，中间一颗宝石——前面几组的图形都在里面
                if (this.detail) {
                    band(0.0F, 0.0F, 0.64F * e, 0.86F * e, 4, UP, level - 0.04F, 0.24F);
                    band(0.0F, 0.04F * e, 0.36F * e, 0.56F * e, 3, UP, level, 0.24F);
                    if (!this.silhouette && metal >= METAL_GOLD) {
                        float pulse = 0.5F + 0.5F * (float) Math.sin(this.time * PI * 2.0F / 2.6F);
                        glow(0.0F, 0.04F * e, 0.42F * e, withAlpha(ACCENT[METAL_GOLD], 0.30F + 0.25F * pulse));
                    }
                    disc(0.0F, 0.04F * e, 0.14F * e, level + 0.16F, 0.16F, 0.16F);
                } else {
                    star(0.0F, 0.0F, 4, 0.86F * e, 0.30F * e, UP, level);
                }
            }
            case TIER_LEGENDARY -> {
                // 传奇：一大一小两枚四芒星叠成八芒，中心一点白光，轻轻闪烁
                float twinkle = 1.0F + 0.05F * (float) Math.sin(this.time * PI * 2.0F / 2.6F);
                star(0.0F, 0.0F, 4, 0.58F * e, 0.13F * e, UP + PI / 4.0F, 0.72F);
                star(0.0F, 0.0F, 4, 0.90F * e * twinkle, 0.15F * e, UP, 0.92F);
                if (!this.silhouette) {
                    disc(0.0F, 0.0F, 0.09F * e, 1.05F, 0.0F, 0.0F);
                    if (this.detail) {
                        // 虚空里几点星光，各自错开节奏闪
                        for (int k = 0; k < 8; k++) {
                            float angle = UP + PI / 8.0F + PI / 4.0F * k;
                            float radius = (k & 1) == 0 ? 0.66F * e : 0.48F * e;
                            float blink = 0.5F + 0.5F * (float) Math.sin(this.time * PI * 2.0F / 2.6F + k * 2.1F);
                            disc((float) Math.cos(angle) * radius, (float) Math.sin(angle) * radius,
                                    0.035F * e, 0.55F + 0.45F * blink, 0.0F, 0.0F);
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    /**
     * 白银、黄金在边框上加铆钉：白银四颗、黄金八颗。
     *
     * @param m     圆章半径
     * @param e     釉面半径
     * @param metal 金属种类
     */
    private void studs(float m, float e, int metal) {
        int count;
        float start;
        if (metal == METAL_SILVER) {
            count = 4;
            start = PI / 4.0F;
        } else if (metal == METAL_GOLD) {
            count = 8;
            start = PI / 8.0F;
        } else {
            return;
        }
        float radius = (e + m) * 0.5F;
        float size = (m - e) * 0.40F;
        for (int k = 0; k < count; k++) {
            float angle = start + PI * 2.0F / count * k;
            disc((float) Math.cos(angle) * radius, (float) Math.sin(angle) * radius, size, 0.82F, 0.16F, 0.14F);
        }
    }

    // ======================================================================
    // 绘制模式
    // ======================================================================

    /**
     * 正常上色。
     */
    private void solid() {
        this.silhouette = false;
        this.inflate = 0.0F;
        this.offsetU = 0.0F;
        this.offsetV = 0.0F;
    }

    /**
     * 描边：图形放大一圈、统一涂暗色。至少放大 0.6 像素，小尺寸下也有一道看得见的暗边。
     */
    private void outlinePass() {
        this.silhouette = true;
        this.silhouetteColor = OUTLINE;
        this.inflate = Math.max(0.05F, 0.6F / this.unit);
        this.offsetU = 0.0F;
        this.offsetV = 0.0F;
    }

    /**
     * 投影：图形往右下错开一点、涂最暗的那级颜色。
     */
    private void shadowPass() {
        this.silhouette = true;
        this.silhouetteColor = withAlpha(this.ramp[0], 0.85F);
        this.inflate = 0.0F;
        float offset = Math.max(0.035F, 0.7F / this.unit);
        this.offsetU = offset;
        this.offsetV = offset * 1.2F;
    }

    // ======================================================================
    // 图元（单位坐标）
    // ======================================================================

    /**
     * 刻面：中心点和外缘一条边组成的三角形，按这条边朝外的方向受光，中心那一角加一道亮棱。
     */
    private void facet(float cu, float cv, float pu, float pv, float qu, float qv, float level) {
        float eu = qu - pu;
        float ev = qv - pv;
        float length = (float) Math.sqrt(eu * eu + ev * ev);
        if (length < 1.0E-5F) {
            return;
        }
        float nu = ev / length;
        float nv = -eu / length;
        // 法线朝向背离中心的一侧
        if (nu * ((pu + qu) * 0.5F - cu) + nv * ((pv + qv) * 0.5F - cv) < 0.0F) {
            nu = -nu;
            nv = -nv;
        }
        float lit = level + FACET * (nu * LIGHT_U + nv * LIGHT_V);
        put(cu, cv, lit + RIDGE);
        put(pu, pv, lit);
        put(qu, qv, lit);
    }

    /**
     * 切面星形。
     *
     * @param points   角数
     * @param outer    尖角半径
     * @param inner    凹角半径
     * @param rotation 第一个尖角的方向
     */
    private void star(float u, float v, int points, float outer, float inner, float rotation, float level) {
        starAlternating(u, v, points, outer, outer, inner, rotation, level);
    }

    /**
     * 切面星形，奇偶尖角长短不同（罗盘）。
     *
     * @param points   角数
     * @param outerA   偶数号尖角半径
     * @param outerB   奇数号尖角半径
     * @param inner    凹角半径
     * @param rotation 第一个尖角的方向
     */
    private void starAlternating(float u, float v, int points, float outerA, float outerB, float inner,
                                 float rotation, float level) {
        float half = PI / points;
        float ri = inner + this.inflate;
        for (int i = 0; i < points; i++) {
            float ro = ((i & 1) == 0 ? outerA : outerB) + this.inflate * 1.6F;
            float angle = rotation + 2.0F * half * i;
            float tu = u + (float) Math.cos(angle) * ro;
            float tv = v + (float) Math.sin(angle) * ro;
            float lu = u + (float) Math.cos(angle - half) * ri;
            float lv = v + (float) Math.sin(angle - half) * ri;
            float ru = u + (float) Math.cos(angle + half) * ri;
            float rv = v + (float) Math.sin(angle + half) * ri;
            facet(u, v, lu, lv, tu, tv, level);
            facet(u, v, tu, tv, ru, rv, level);
        }
    }

    /**
     * 切面菱形：沿 {@code angle} 方向前长后短，四个刻面。
     *
     * @param angle     朝向
     * @param front     中心到前尖的距离
     * @param back      中心到后尖的距离
     * @param halfWidth 半宽
     */
    private void kite(float u, float v, float angle, float front, float back, float halfWidth, float level) {
        float du = (float) Math.cos(angle);
        float dv = (float) Math.sin(angle);
        float f = front + this.inflate * 1.6F;
        float b = back + this.inflate * 1.6F;
        float w = halfWidth + this.inflate;
        float fu = u + du * f;
        float fv = v + dv * f;
        float bu = u - du * b;
        float bv = v - dv * b;
        float lu = u - dv * w;
        float lv = v + du * w;
        float ru = u + dv * w;
        float rv = v - du * w;
        facet(u, v, fu, fv, lu, lv, level);
        facet(u, v, lu, lv, bu, bv, level);
        facet(u, v, bu, bv, ru, rv, level);
        facet(u, v, ru, rv, fu, fv, level);
    }

    /**
     * 刃 / 羽片：从 (u, v) 出发朝 {@code angle} 伸出去的菱形，最宽处在三成长度。
     */
    private void blade(float u, float v, float angle, float length, float halfWidth, float level) {
        float du = (float) Math.cos(angle);
        float dv = (float) Math.sin(angle);
        kite(u + du * length * 0.28F, v + dv * length * 0.28F, angle,
                length * 0.72F, length * 0.28F, halfWidth, level);
    }

    /**
     * 粗线：沿中线分成两半，各按自己那一侧的朝向受光，中线是一道棱。
     */
    private void bar(float u1, float v1, float u2, float v2, float halfWidth, float level) {
        float du = u2 - u1;
        float dv = v2 - v1;
        float length = (float) Math.sqrt(du * du + dv * dv);
        if (length < 1.0E-5F) {
            return;
        }
        du /= length;
        dv /= length;
        float a1 = u1 - du * this.inflate;
        float b1 = v1 - dv * this.inflate;
        float a2 = u2 + du * this.inflate;
        float b2 = v2 + dv * this.inflate;
        float w = halfWidth + this.inflate;
        float pu = -dv * w;
        float pv = du * w;
        float side = FACET * (-dv * LIGHT_U + du * LIGHT_V);
        float ridge = level + RIDGE;
        float left = level + side;
        float right = level - side;
        put(a1, b1, ridge);
        put(a1 + pu, b1 + pv, left);
        put(a2 + pu, b2 + pv, left);
        put(a1, b1, ridge);
        put(a2 + pu, b2 + pv, left);
        put(a2, b2, ridge);
        put(a1, b1, ridge);
        put(a2, b2, ridge);
        put(a2 - pu, b2 - pv, right);
        put(a1, b1, ridge);
        put(a2 - pu, b2 - pv, right);
        put(a1 - pu, b1 - pv, right);
    }

    /**
     * 圆。
     *
     * @param r     半径
     * @param dome  中心额外提亮（鼓起来的感觉）
     * @param edge  边缘按朝光方向的明暗幅度：正数是凸、负数是凹
     */
    private void disc(float u, float v, float r, float level, float dome, float edge) {
        float rr = r + this.inflate;
        int step = stepFor(rr);
        float center = level + dome;
        for (int i = 0; i < TABLE; i += step) {
            float c0 = COS[i];
            float s0 = SIN[i];
            float c1 = COS[i + step];
            float s1 = SIN[i + step];
            put(u, v, center);
            put(u + c0 * rr, v + s0 * rr, level + edge * (c0 * LIGHT_U + s0 * LIGHT_V));
            put(u + c1 * rr, v + s1 * rr, level + edge * (c1 * LIGHT_U + s1 * LIGHT_V));
        }
    }

    /**
     * 环 / 多边形框。
     *
     * @param r0       内半径
     * @param r1       外半径
     * @param sides    边数，0 表示圆环
     * @param rotation 多边形第一个角的方向
     * @param bevel    倒角明暗：外沿朝光的一侧亮、内沿反过来
     */
    private void band(float u, float v, float r0, float r1, int sides, float rotation, float level, float bevel) {
        float ri = Math.max(0.0F, r0 - this.inflate);
        float ro = r1 + this.inflate;
        if (sides <= 0) {
            int step = stepFor(ro);
            for (int i = 0; i < TABLE; i += step) {
                float c0 = COS[i];
                float s0 = SIN[i];
                float c1 = COS[i + step];
                float s1 = SIN[i + step];
                float lit0 = c0 * LIGHT_U + s0 * LIGHT_V;
                float lit1 = c1 * LIGHT_U + s1 * LIGHT_V;
                quad(u + c0 * ri, v + s0 * ri, level - bevel * 0.7F * lit0,
                        u + c0 * ro, v + s0 * ro, level + bevel * lit0,
                        u + c1 * ro, v + s1 * ro, level + bevel * lit1,
                        u + c1 * ri, v + s1 * ri, level - bevel * 0.7F * lit1);
            }
            return;
        }
        // 多边形框每条边单独受光，转角处颜色断开，棱角才分明
        float step = PI * 2.0F / sides;
        for (int k = 0; k < sides; k++) {
            float a0 = rotation + step * k;
            float a1 = a0 + step;
            float mid = a0 + step * 0.5F;
            float lit = (float) Math.cos(mid) * LIGHT_U + (float) Math.sin(mid) * LIGHT_V;
            float c0 = (float) Math.cos(a0);
            float s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1);
            float s1 = (float) Math.sin(a1);
            float outer = level + bevel * lit;
            float inner = level - bevel * 0.7F * lit;
            quad(u + c0 * ri, v + s0 * ri, inner,
                    u + c0 * ro, v + s0 * ro, outer,
                    u + c1 * ro, v + s1 * ro, outer,
                    u + c1 * ri, v + s1 * ri, inner);
        }
    }

    /**
     * 杏仁形（两段抛物线围成），龙瞳用。
     */
    private void lens(float u, float v, float halfWidth, float halfHeight, float level, float edge) {
        float hw = halfWidth + this.inflate * 1.6F;
        float hh = halfHeight + this.inflate;
        final int n = 12;
        float prevU = u - hw;
        float prevV = v;
        for (int i = 1; i <= n * 2; i++) {
            float x;
            float y;
            if (i <= n) {
                x = -hw + 2.0F * hw * i / n;
                y = -hh * (1.0F - (x / hw) * (x / hw));
            } else {
                x = hw - 2.0F * hw * (i - n) / n;
                y = hh * (1.0F - (x / hw) * (x / hw));
            }
            float nextU = u + x;
            float nextV = v + y;
            put(u, v, level + 0.10F);
            put(prevU, prevV, level + edge * lightOf(prevU - u, prevV - v));
            put(nextU, nextV, level + edge * lightOf(x, y));
            prevU = nextU;
            prevV = nextV;
        }
    }

    /**
     * 柔光：中心是给定颜色，往外渐隐到透明。
     */
    private void glow(float u, float v, float r, int argb) {
        int step = stepFor(r);
        int edge = argb & 0x00FFFFFF;
        for (int i = 0; i < TABLE; i += step) {
            emit(u, v, argb);
            emit(u + COS[i] * r, v + SIN[i] * r, edge);
            emit(u + COS[i + step] * r, v + SIN[i + step] * r, edge);
        }
    }

    /**
     * 传奇的棱镜环：沿圆周铺一圈色相，随时间转动。
     */
    private void prismBand(float r0, float r1) {
        if (this.silhouette) {
            band(0.0F, 0.0F, r0, r1, 0, 0.0F, 0.0F, 0.0F);
            return;
        }
        float phase = this.time * 0.125F;
        int step = stepFor(r1);
        for (int i = 0; i < TABLE; i += step) {
            float c0 = COS[i];
            float s0 = SIN[i];
            float c1 = COS[i + step];
            float s1 = SIN[i + step];
            float hue0 = (float) i / TABLE + phase;
            float hue1 = (float) (i + step) / TABLE + phase;
            float lit0 = c0 * LIGHT_U + s0 * LIGHT_V;
            float lit1 = c1 * LIGHT_U + s1 * LIGHT_V;
            int outer0 = hsv(hue0, 0.42F, 0.86F + 0.14F * lit0);
            int outer1 = hsv(hue1, 0.42F, 0.86F + 0.14F * lit1);
            int inner0 = hsv(hue0 + 0.07F, 0.62F, 0.58F - 0.10F * lit0);
            int inner1 = hsv(hue1 + 0.07F, 0.62F, 0.58F - 0.10F * lit1);
            emitShaded(c0 * r0, s0 * r0, inner0);
            emitShaded(c0 * r1, s0 * r1, outer0);
            emitShaded(c1 * r1, s1 * r1, outer1);
            emitShaded(c0 * r0, s0 * r0, inner0);
            emitShaded(c1 * r1, s1 * r1, outer1);
            emitShaded(c1 * r0, s1 * r0, inner1);
        }
    }

    private void quad(float u0, float v0, float l0, float u1, float v1, float l1,
                      float u2, float v2, float l2, float u3, float v3, float l3) {
        put(u0, v0, l0);
        put(u1, v1, l1);
        put(u2, v2, l2);
        put(u0, v0, l0);
        put(u2, v2, l2);
        put(u3, v3, l3);
    }

    // ======================================================================
    // 上色与输出
    // ======================================================================

    /**
     * 按亮度上色后输出一个顶点。
     */
    private void put(float u, float v, float level) {
        emit(u, v, colorAt(u, v, level));
    }

    /**
     * 现成颜色再叠上闪白后输出。
     */
    private void emitShaded(float u, float v, int argb) {
        emit(u, v, this.silhouette ? this.silhouetteColor
                : this.flash > 0.0F ? lerpColor(argb, 0xFFFFFFFF, this.flash) : argb);
    }

    private void emit(float u, float v, int argb) {
        int a = (int) (((argb >>> 24) & 0xFF) * this.alpha);
        this.sink.vertex(this.originX + (u + this.offsetU) * this.unit,
                this.originY + (v + this.offsetV) * this.unit,
                (a << 24) | (argb & 0x00FFFFFF));
    }

    /**
     * 某个位置、某个基础亮度的颜色：叠上左上到右下的渐变、扫光和闪白。
     */
    private int colorAt(float u, float v, float level) {
        if (this.silhouette) {
            return this.silhouetteColor;
        }
        float diagonal = (u + v) * 0.5F;
        float l = level - FACE_GRAD * diagonal;
        if (this.glossPos == this.glossPos) {
            float distance = Math.abs(diagonal - this.glossPos);
            if (distance < GLOSS_WIDTH) {
                float k = 1.0F - distance / GLOSS_WIDTH;
                l += GLOSS * k * k * (0.4F + Math.max(0.0F, level));
            }
        }
        int color = rampColor(l);
        return this.flash > 0.0F ? lerpColor(color, 0xFFFFFFFF, this.flash) : color;
    }

    private int rampColor(float level) {
        if (level <= 0.0F) {
            return this.ramp[0];
        }
        if (level >= 1.0F) {
            return this.ramp[4];
        }
        float p = level * 4.0F;
        int index = Math.min(3, (int) p);
        return lerpColor(this.ramp[index], this.ramp[index + 1], p - index);
    }

    /**
     * 按屏幕半径挑查表步长：半径越大分段越多。
     */
    private int stepFor(float radius) {
        float pixels = radius * this.unit;
        if (pixels < 5.0F) {
            return 6;
        }
        if (pixels < 12.0F) {
            return 4;
        }
        if (pixels < 24.0F) {
            return 3;
        }
        if (pixels < 48.0F) {
            return 2;
        }
        return 1;
    }

    private static float lightOf(float u, float v) {
        float length = (float) Math.sqrt(u * u + v * v);
        return length < 1.0E-5F ? 0.0F : (u * LIGHT_U + v * LIGHT_V) / length;
    }

    // ======================================================================
    // 颜色工具（不依赖游戏类，出图检查时也能用）
    // ======================================================================

    static float clamp01(float value) {
        return value < 0.0F ? 0.0F : Math.min(1.0F, value);
    }

    /**
     * 把颜色的透明度乘上一个系数。
     *
     * @param argb  原色
     * @param scale 系数，0~1
     * @return 新颜色
     */
    static int withAlpha(int argb, float scale) {
        int a = (int) (((argb >>> 24) & 0xFF) * clamp01(scale));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * 两色线性插值（含透明度）。
     *
     * @param from 起始色
     * @param to   目标色
     * @param t    0~1
     * @return 混合色
     */
    static int lerpColor(int from, int to, float t) {
        float k = clamp01(t);
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * k);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * HSV 转不透明 ARGB。
     *
     * @param hue        色相，任意实数，取小数部分
     * @param saturation 饱和度 0~1
     * @param value      明度 0~1
     * @return 颜色
     */
    static int hsv(float hue, float saturation, float value) {
        float h = (hue - (float) Math.floor(hue)) * 6.0F;
        float s = clamp01(saturation);
        float v = clamp01(value);
        int sector = Math.min(5, (int) h);
        float f = h - sector;
        float p = v * (1.0F - s);
        float q = v * (1.0F - s * f);
        float t = v * (1.0F - s * (1.0F - f));
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> {
                r = v;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = v;
                b = p;
            }
            case 2 -> {
                r = p;
                g = v;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = v;
            }
            case 4 -> {
                r = t;
                g = p;
                b = v;
            }
            default -> {
                r = v;
                g = p;
                b = q;
            }
        }
        return 0xFF000000 | ((int) (r * 255.0F) << 16) | ((int) (g * 255.0F) << 8) | (int) (b * 255.0F);
    }
}
