package com.flameforge.albionmastery.client.screen;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 专精界面用的图形绘制工具。
 *
 * <p>{@code GuiGraphics} 只能画轴对齐矩形，而这套界面需要大量六边形、斜线和扇形，
 * 所以直接用 {@link Tesselator} 生成三角形。
 *
 * <p>所有图元统一用 {@link VertexFormat.Mode#TRIANGLES} 提交，一次
 * {@link #begin()} / {@link #end()} 之间可以攒下整屏的节点与连线，最终只产生一次绘制调用。
 */
public final class RenderUtil {

    /** 画圆时的分段数。 */
    /** 圆的最少分段数：再少就明显是多边形了。 */
    private static final int CIRCLE_SEGMENTS = 28;

    /** 圆的最多分段数：整圈画到这个数再大也看不出区别，只是白费顶点。 */
    private static final int CIRCLE_SEGMENTS_MAX = 720;

    /**
     * 按屏幕半径算整圈该分几段：大约每像素半径一段。
     *
     * <p>固定 28 段在半径几十像素时够圆，放大到几百上千像素就能看出棱角——
     * 圆环是整个界面里半径最大的东西，放大一点就露馅。按半径给段数，
     * 半径 300 像素 300 段，弦长约 6 像素、弦高不到 0.02 像素，肉眼是圆的。
     *
     * @param radius 屏幕半径
     * @return 整圈的分段数
     */
    private static int segmentsFor(float radius) {
        return Math.max(CIRCLE_SEGMENTS, Math.min(CIRCLE_SEGMENTS_MAX, (int) Math.ceil(radius)));
    }

    private RenderUtil() {
    }

    /**
     * 开始一批图元绘制。
     *
     * @return 用于写入顶点的缓冲
     */
    public static BufferBuilder begin() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        return openBatch(DefaultVertexFormat.POSITION_COLOR);
    }

    /**
     * 结束并提交本批图元。
     */
    public static void end() {
        submitBatch();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** 当前打开、还没提交的一批顶点；没有时为 null。 */
    private static BufferBuilder open;

    /**
     * 现在是否开着一批顶点没提交。
     *
     * <p>所有 {@link BufferBuilder} 共用 {@link Tesselator} 里同一块缓冲，上一批没提交就再开一批，
     * 前一批的顶点会悄悄混进后一批；画徽章这类可能被嵌套调用的地方先问一声，开着就这一帧不画。
     *
     * @return 开着一批时为 true
     */
    public static boolean isBatchOpen() {
        return open != null;
    }

    /**
     * 开一批三角形顶点。上一批还没提交时直接抛异常，不让两批顶点混在一起。
     *
     * @param format 顶点格式
     * @return 用于写入顶点的缓冲
     */
    public static BufferBuilder openBatch(VertexFormat format) {
        if (open != null) {
            throw new IllegalStateException("上一批顶点还没提交");
        }
        open = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, format);
        return open;
    }

    /**
     * 提交当前这批顶点。一个顶点都没写的空批什么也不画，也不报错。
     */
    public static void submitBatch() {
        BufferBuilder buffer = open;
        open = null;
        if (buffer == null) {
            return;
        }
        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }

    // 六边形

    /**
     * 计算一个平顶六边形的六个顶点。
     *
     * <p>阿尔比恩的专精盘用的就是六边形节点，这里保持一致。
     *
     * @param cx     中心 X
     * @param cy     中心 Y
     * @param radius 外接圆半径
     * @return 顶点数组，每项为 {x, y}
     */
    public static float[][] hexagon(float cx, float cy, float radius) {
        float[][] points = new float[6][2];
        for (int i = 0; i < 6; i++) {
            points[i][0] = cx + UNIT_COS[i] * radius;
            points[i][1] = cy + UNIT_SIN[i] * radius;
        }
        return points;
    }

    /** 单位六边形六个角的余弦，偏移 30 度得到平顶六边形。整张图每帧要画上万次，现算三角函数太亏。 */
    private static final float[] UNIT_COS = unitHexagon(true);

    /** 单位六边形六个角的正弦。 */
    private static final float[] UNIT_SIN = unitHexagon(false);

    /**
     * 算单位六边形的角点分量。
     *
     * @param cosine true 取余弦，false 取正弦
     * @return 六个分量
     */
    private static float[] unitHexagon(boolean cosine) {
        float[] out = new float[6];
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI / 6.0D + Math.PI / 3.0D * i;
            out[i] = (float) (cosine ? Math.cos(angle) : Math.sin(angle));
        }
        return out;
    }

    /**
     * 以扇形三角化的方式填充一个凸多边形。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param points 顶点数组
     * @param argb   颜色
     */
    /**
     * 把一张方形贴图裁成六边形画出来，六边形和节点同形，方形贴图的四个角直接切掉。
     *
     * <p>为什么不用 {@code blit}：法术图标是不带透明通道的 16x16 方块，整块贴上去就是一个方框
     * 戳在圆润的六边形节点里，非常突兀。这里用带纹理坐标的三角形画：六边形的每个顶点
     * 对应贴图上的一个位置，中间由显卡插值，等于把贴图「剪」成了六边形。
     *
     * <p>{@code crop} 控制往里裁多少：1.0 时六边形顶点正好落在贴图边缘，0.9 就再往里收一成，
     * 把贴图四周那圈通常是背景的像素也切掉。
     *
     * <p>自成一次绘制调用，不进 {@link #begin()} 那个批次（批次是纯色的，没有纹理坐标）。
     *
     * @param matrix  变换矩阵
     * @param texture 贴图
     * @param cx      中心 X
     * @param cy      中心 Y
     * @param radius  六边形外接圆半径
     * @param crop    裁边比例，0~1
     * @param alpha   整体透明度，0~1
     */
    public static void texturedHexagon(Matrix4f matrix, ResourceLocation texture,
                                       float cx, float cy, float radius, float crop, float alpha) {
        if (radius <= 0.0F || alpha <= 0.0F) {
            return;
        }
        float[][] points = hexagon(cx, cy, radius);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // 六边形顶点是按屏幕坐标顺时针排的，这个绕向在 GUI 投影下会被背面剔除整个吃掉——
        // 纯色批次（begin()）里就是靠关掉剔除才画得出来的，这里同样要关，画完再开回去
        RenderSystem.disableCull();
        BufferBuilder buffer = openBatch(DefaultVertexFormat.POSITION_TEX_COLOR);
        float half = 0.5F * crop;
        for (int i = 0; i < points.length; i++) {
            float[] a = points[i];
            float[] b = points[(i + 1) % points.length];
            buffer.addVertex(matrix, cx, cy, 0.0F).setUv(0.5F, 0.5F).setColor(1.0F, 1.0F, 1.0F, alpha);
            buffer.addVertex(matrix, a[0], a[1], 0.0F)
                    .setUv(0.5F + (a[0] - cx) / radius * half, 0.5F + (a[1] - cy) / radius * half)
                    .setColor(1.0F, 1.0F, 1.0F, alpha);
            buffer.addVertex(matrix, b[0], b[1], 0.0F)
                    .setUv(0.5F + (b[0] - cx) / radius * half, 0.5F + (b[1] - cy) / radius * half)
                    .setColor(1.0F, 1.0F, 1.0F, alpha);
        }
        submitBatch();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    public static void polygon(BufferBuilder buffer, Matrix4f matrix, float[][] points, int argb) {
        if (points.length < 3) {
            return;
        }
        float a = alpha(argb);
        float r = red(argb);
        float g = green(argb);
        float b = blue(argb);
        for (int i = 1; i < points.length - 1; i++) {
            buffer.addVertex(matrix, points[0][0], points[0][1], 0.0F).setColor(r, g, b, a);
            buffer.addVertex(matrix, points[i][0], points[i][1], 0.0F).setColor(r, g, b, a);
            buffer.addVertex(matrix, points[i + 1][0], points[i + 1][1], 0.0F).setColor(r, g, b, a);
        }
    }

    /**
     * 把多边形裁掉 {@code yThreshold} 以上的部分，只保留下半部分再填充。
     *
     * <p>用来做「节点随等级从底部往上填满」的效果——阿尔比恩专精盘最标志性的表现。
     * 用的是 Sutherland–Hodgman 算法对单条半平面做裁剪，凸多边形裁完仍是凸多边形，
     * 可以直接扇形三角化。
     *
     * @param buffer     顶点缓冲
     * @param matrix     变换矩阵
     * @param points     原始顶点
     * @param yThreshold 分界线，只保留 y &gt;= 此值的部分
     * @param argb       颜色
     */
    public static void polygonClippedBelow(BufferBuilder buffer, Matrix4f matrix,
                                           float[][] points, float yThreshold, int argb) {
        List<float[]> output = new ArrayList<>(points.length + 2);
        int n = points.length;
        for (int i = 0; i < n; i++) {
            float[] current = points[i];
            float[] next = points[(i + 1) % n];
            boolean currentIn = current[1] >= yThreshold;
            boolean nextIn = next[1] >= yThreshold;
            if (currentIn) {
                output.add(current);
            }
            if (currentIn != nextIn) {
                float dy = next[1] - current[1];
                if (Math.abs(dy) > 1.0E-5F) {
                    float t = (yThreshold - current[1]) / dy;
                    output.add(new float[]{current[0] + (next[0] - current[0]) * t, yThreshold});
                }
            }
        }
        if (output.size() >= 3) {
            polygon(buffer, matrix, output.toArray(new float[0][]), argb);
        }
    }

    /**
     * 沿多边形各边画描边。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param points 顶点数组
     * @param width  线宽
     * @param argb   颜色
     */
    public static void polygonOutline(BufferBuilder buffer, Matrix4f matrix,
                                      float[][] points, float width, int argb) {
        int n = points.length;
        for (int i = 0; i < n; i++) {
            float[] current = points[i];
            float[] next = points[(i + 1) % n];
            line(buffer, matrix, current[0], current[1], next[0], next[1], width, argb);
        }
    }

    // 圆与线

    /**
     * 画一个实心圆。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param cx     圆心 X
     * @param cy     圆心 Y
     * @param radius 半径
     * @param argb   颜色
     */
    public static void disc(BufferBuilder buffer, Matrix4f matrix,
                            float cx, float cy, float radius, int argb) {
        float a = alpha(argb);
        float r = red(argb);
        float g = green(argb);
        float b = blue(argb);
        int segments = segmentsFor(radius);
        double step = Math.PI * 2.0D / segments;
        for (int i = 0; i < segments; i++) {
            double a0 = step * i;
            double a1 = step * (i + 1);
            buffer.addVertex(matrix, cx, cy, 0.0F).setColor(r, g, b, a);
            buffer.addVertex(matrix, cx + (float) (Math.cos(a0) * radius), cy + (float) (Math.sin(a0) * radius), 0.0F)
                    .setColor(r, g, b, a);
            buffer.addVertex(matrix, cx + (float) (Math.cos(a1) * radius), cy + (float) (Math.sin(a1) * radius), 0.0F)
                    .setColor(r, g, b, a);
        }
    }

    /**
     * 画一个圆环 / 圆弧。
     *
     * @param buffer     顶点缓冲
     * @param matrix     变换矩阵
     * @param cx         圆心 X
     * @param cy         圆心 Y
     * @param inner      内半径
     * @param outer      外半径
     * @param argb       颜色
     * @param startAngle 起始角（弧度）
     * @param sweep      扫过角度（弧度）
     */
    public static void ring(BufferBuilder buffer, Matrix4f matrix,
                            float cx, float cy, float inner, float outer,
                            int argb, double startAngle, double sweep) {
        if (sweep <= 0.0D) {
            return;
        }
        float a = alpha(argb);
        float r = red(argb);
        float g = green(argb);
        float b = blue(argb);
        int segments = Math.max(2, (int) Math.ceil(segmentsFor(outer) * (sweep / (Math.PI * 2.0D))));
        double step = sweep / segments;
        for (int i = 0; i < segments; i++) {
            double a0 = startAngle + step * i;
            double a1 = startAngle + step * (i + 1);
            float cos0 = (float) Math.cos(a0);
            float sin0 = (float) Math.sin(a0);
            float cos1 = (float) Math.cos(a1);
            float sin1 = (float) Math.sin(a1);

            float x0i = cx + cos0 * inner;
            float y0i = cy + sin0 * inner;
            float x0o = cx + cos0 * outer;
            float y0o = cy + sin0 * outer;
            float x1i = cx + cos1 * inner;
            float y1i = cy + sin1 * inner;
            float x1o = cx + cos1 * outer;
            float y1o = cy + sin1 * outer;

            buffer.addVertex(matrix, x0i, y0i, 0.0F).setColor(r, g, b, a);
            buffer.addVertex(matrix, x0o, y0o, 0.0F).setColor(r, g, b, a);
            buffer.addVertex(matrix, x1o, y1o, 0.0F).setColor(r, g, b, a);

            buffer.addVertex(matrix, x0i, y0i, 0.0F).setColor(r, g, b, a);
            buffer.addVertex(matrix, x1o, y1o, 0.0F).setColor(r, g, b, a);
            buffer.addVertex(matrix, x1i, y1i, 0.0F).setColor(r, g, b, a);
        }
    }

    /**
     * 画一条有宽度的直线。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param x1     起点 X
     * @param y1     起点 Y
     * @param x2     终点 X
     * @param y2     终点 Y
     * @param width  线宽
     * @param argb   颜色
     */
    public static void line(BufferBuilder buffer, Matrix4f matrix,
                            float x1, float y1, float x2, float y2, float width, int argb) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0E-4F) {
            return;
        }
        float nx = -dy / length * (width / 2.0F);
        float ny = dx / length * (width / 2.0F);

        float a = alpha(argb);
        float r = red(argb);
        float g = green(argb);
        float b = blue(argb);

        buffer.addVertex(matrix, x1 + nx, y1 + ny, 0.0F).setColor(r, g, b, a);
        buffer.addVertex(matrix, x1 - nx, y1 - ny, 0.0F).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2 - nx, y2 - ny, 0.0F).setColor(r, g, b, a);

        buffer.addVertex(matrix, x1 + nx, y1 + ny, 0.0F).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2 - nx, y2 - ny, 0.0F).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2 + nx, y2 + ny, 0.0F).setColor(r, g, b, a);
    }

    // 发光与渐变（段位晋升动画用）

    /**
     * 开始一批叠加混合的图元：颜色直接加到底色上，越叠越亮，只适合画光。
     *
     * <p>必须配 {@link #endAdditive()}，它会把混合函数恢复成默认。
     *
     * @return 用于写入顶点的缓冲
     */
    public static BufferBuilder beginAdditive() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        return openBatch(DefaultVertexFormat.POSITION_COLOR);
    }

    /**
     * 结束叠加混合的一批图元，混合函数恢复默认。
     */
    public static void endAdditive() {
        submitBatch();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * 写一个带颜色的顶点。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param x      X
     * @param y      Y
     * @param argb   颜色
     */
    public static void vertex(BufferBuilder buffer, Matrix4f matrix, float x, float y, int argb) {
        // setColor(int) 吃 ARGB 打包色，和以前拆成四个分量写入的字节一样
        buffer.addVertex(matrix, x, y, 0.0F).setColor(argb);
    }

    /**
     * 四个顶点各自带颜色的四边形（按 0-1-2、0-2-3 拆成两个三角形）。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     */
    public static void gradientQuad(BufferBuilder buffer, Matrix4f matrix,
                                    float x0, float y0, int c0, float x1, float y1, int c1,
                                    float x2, float y2, int c2, float x3, float y3, int c3) {
        vertex(buffer, matrix, x0, y0, c0);
        vertex(buffer, matrix, x1, y1, c1);
        vertex(buffer, matrix, x2, y2, c2);
        vertex(buffer, matrix, x0, y0, c0);
        vertex(buffer, matrix, x2, y2, c2);
        vertex(buffer, matrix, x3, y3, c3);
    }

    /**
     * 径向渐变的圆：中心一种颜色，边缘另一种颜色。
     *
     * @param buffer      顶点缓冲
     * @param matrix      变换矩阵
     * @param cx          圆心 X
     * @param cy          圆心 Y
     * @param radius      半径
     * @param centerArgb  中心颜色
     * @param edgeArgb    边缘颜色
     */
    public static void radialGradient(BufferBuilder buffer, Matrix4f matrix, float cx, float cy, float radius,
                                      int centerArgb, int edgeArgb) {
        if (radius <= 0.0F) {
            return;
        }
        int segments = Math.max(24, Math.min(96, (int) Math.ceil(radius * 0.6F)));
        double step = Math.PI * 2.0D / segments;
        for (int i = 0; i < segments; i++) {
            double a0 = step * i;
            double a1 = step * (i + 1);
            vertex(buffer, matrix, cx, cy, centerArgb);
            vertex(buffer, matrix, cx + (float) (Math.cos(a0) * radius), cy + (float) (Math.sin(a0) * radius), edgeArgb);
            vertex(buffer, matrix, cx + (float) (Math.cos(a1) * radius), cy + (float) (Math.sin(a1) * radius), edgeArgb);
        }
    }

    /**
     * 内外沿颜色不同的圆环。
     *
     * @param buffer     顶点缓冲
     * @param matrix     变换矩阵
     * @param cx         圆心 X
     * @param cy         圆心 Y
     * @param inner      内半径
     * @param outer      外半径
     * @param innerArgb  内沿颜色
     * @param outerArgb  外沿颜色
     */
    public static void ringGradient(BufferBuilder buffer, Matrix4f matrix, float cx, float cy,
                                    float inner, float outer, int innerArgb, int outerArgb) {
        if (outer <= inner || outer <= 0.0F) {
            return;
        }
        int segments = segmentsFor(outer);
        double step = Math.PI * 2.0D / segments;
        float ri = Math.max(0.0F, inner);
        for (int i = 0; i < segments; i++) {
            float c0 = (float) Math.cos(step * i);
            float s0 = (float) Math.sin(step * i);
            float c1 = (float) Math.cos(step * (i + 1));
            float s1 = (float) Math.sin(step * (i + 1));
            gradientQuad(buffer, matrix,
                    cx + c0 * ri, cy + s0 * ri, innerArgb,
                    cx + c0 * outer, cy + s0 * outer, outerArgb,
                    cx + c1 * outer, cy + s1 * outer, outerArgb,
                    cx + c1 * ri, cy + s1 * ri, innerArgb);
        }
    }

    /**
     * 两边虚化的光环：半径处最亮，向内外各 {@code halfWidth} 渐隐到透明。冲击波用。
     *
     * @param buffer    顶点缓冲
     * @param matrix    变换矩阵
     * @param cx        圆心 X
     * @param cy        圆心 Y
     * @param radius    半径
     * @param halfWidth 半宽
     * @param argb      颜色
     */
    public static void softRing(BufferBuilder buffer, Matrix4f matrix, float cx, float cy,
                                float radius, float halfWidth, int argb) {
        int clear = argb & 0x00FFFFFF;
        ringGradient(buffer, matrix, cx, cy, radius - halfWidth, radius, clear, argb);
        ringGradient(buffer, matrix, cx, cy, radius, radius + halfWidth, argb, clear);
    }

    /**
     * 两端颜色不同的线段，拖尾的火花用：尾端给透明色，头端给亮色。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param x1     起点 X
     * @param y1     起点 Y
     * @param x2     终点 X
     * @param y2     终点 Y
     * @param width  线宽
     * @param argb1  起点颜色
     * @param argb2  终点颜色
     */
    public static void gradientLine(BufferBuilder buffer, Matrix4f matrix, float x1, float y1, float x2, float y2,
                                    float width, int argb1, int argb2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0E-4F) {
            return;
        }
        float nx = -dy / length * (width / 2.0F);
        float ny = dx / length * (width / 2.0F);
        gradientQuad(buffer, matrix,
                x1 + nx, y1 + ny, argb1,
                x1 - nx, y1 - ny, argb1,
                x2 - nx, y2 - ny, argb2,
                x2 + nx, y2 + ny, argb2);
    }

    /**
     * 一道横向的光：中心最亮，向左右两个尖端和上下两边线性渐隐，整体是一枚压扁的菱形，
     * 两头自然收尖，像镜头上拉出的一道光。
     *
     * @param buffer        顶点缓冲
     * @param matrix        变换矩阵
     * @param cx            中心 X
     * @param cy            中心 Y
     * @param halfLength    半长
     * @param halfThickness 中心处的半厚
     * @param argb          中心颜色
     */
    public static void lightStreak(BufferBuilder buffer, Matrix4f matrix, float cx, float cy,
                                   float halfLength, float halfThickness, int argb) {
        if (halfLength <= 0.0F || halfThickness <= 0.0F) {
            return;
        }
        int clear = argb & 0x00FFFFFF;
        float left = cx - halfLength;
        float right = cx + halfLength;
        float top = cy - halfThickness;
        float bottom = cy + halfThickness;
        vertex(buffer, matrix, cx, cy, argb);
        vertex(buffer, matrix, left, cy, clear);
        vertex(buffer, matrix, cx, top, clear);
        vertex(buffer, matrix, cx, cy, argb);
        vertex(buffer, matrix, cx, top, clear);
        vertex(buffer, matrix, right, cy, clear);
        vertex(buffer, matrix, cx, cy, argb);
        vertex(buffer, matrix, right, cy, clear);
        vertex(buffer, matrix, cx, bottom, clear);
        vertex(buffer, matrix, cx, cy, argb);
        vertex(buffer, matrix, cx, bottom, clear);
        vertex(buffer, matrix, left, cy, clear);
    }

    /**
     * 四边羽化的矩形：内部是给定颜色，四周向外渐隐到透明。给文字垫一块看不出边的暗底。
     *
     * @param buffer   顶点缓冲
     * @param matrix   变换矩阵
     * @param x0       内部左边
     * @param y0       内部上边
     * @param x1       内部右边
     * @param y1       内部下边
     * @param featherX 左右羽化宽度
     * @param featherY 上下羽化宽度
     * @param argb     颜色
     */
    public static void softRect(BufferBuilder buffer, Matrix4f matrix, float x0, float y0, float x1, float y1,
                                float featherX, float featherY, int argb) {
        int clear = argb & 0x00FFFFFF;
        float ox0 = x0 - featherX;
        float ox1 = x1 + featherX;
        float oy0 = y0 - featherY;
        float oy1 = y1 + featherY;
        gradientQuad(buffer, matrix, x0, y0, argb, x1, y0, argb, x1, y1, argb, x0, y1, argb);
        gradientQuad(buffer, matrix, x0, oy0, clear, x1, oy0, clear, x1, y0, argb, x0, y0, argb);
        gradientQuad(buffer, matrix, x0, y1, argb, x1, y1, argb, x1, oy1, clear, x0, oy1, clear);
        gradientQuad(buffer, matrix, ox0, y0, clear, x0, y0, argb, x0, y1, argb, ox0, y1, clear);
        gradientQuad(buffer, matrix, x1, y0, argb, ox1, y0, clear, ox1, y1, clear, x1, y1, argb);
        gradientQuad(buffer, matrix, x0, y0, argb, ox0, y0, clear, ox0, oy0, clear, x0, oy0, clear);
        // 四个角：亮的那个顶点放在第一个，两个三角形都带上它，角上才是均匀的渐隐
        gradientQuad(buffer, matrix, x1, y0, argb, x1, oy0, clear, ox1, oy0, clear, ox1, y0, clear);
        gradientQuad(buffer, matrix, x0, y1, argb, x0, oy1, clear, ox0, oy1, clear, ox0, y1, clear);
        gradientQuad(buffer, matrix, x1, y1, argb, ox1, y1, clear, ox1, oy1, clear, x1, oy1, clear);
    }

    /**
     * 实心小菱形。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param cx     中心 X
     * @param cy     中心 Y
     * @param radius 中心到尖角的距离
     * @param argb   颜色
     */
    public static void diamond(BufferBuilder buffer, Matrix4f matrix, float cx, float cy, float radius, int argb) {
        gradientQuad(buffer, matrix, cx, cy - radius, argb, cx + radius, cy, argb,
                cx, cy + radius, argb, cx - radius, cy, argb);
    }

    // 颜色工具

    /**
     * 按系数缩放颜色的透明度。
     *
     * @param argb  原色
     * @param scale 系数
     * @return 新颜色
     */
    public static int withAlpha(int argb, float scale) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, scale)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * 按系数调整亮度。
     *
     * @param argb  原色
     * @param scale 系数，小于 1 变暗，大于 1 变亮
     * @return 新颜色
     */
    public static int scaleBrightness(int argb, float scale) {
        int a = (argb >>> 24) & 0xFF;
        int r = clamp255((int) (((argb >> 16) & 0xFF) * scale));
        int g = clamp255((int) (((argb >> 8) & 0xFF) * scale));
        int b = clamp255((int) ((argb & 0xFF) * scale));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 两色线性插值。
     *
     * @param from 起始色
     * @param to   目标色
     * @param t    插值系数
     * @return 混合后的颜色
     */
    public static int lerpColor(int from, int to, float t) {
        float k = Math.max(0.0F, Math.min(1.0F, t));
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * k);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static float alpha(int argb) {
        return ((argb >>> 24) & 0xFF) / 255.0F;
    }

    private static float red(int argb) {
        return ((argb >> 16) & 0xFF) / 255.0F;
    }

    private static float green(int argb) {
        return ((argb >> 8) & 0xFF) / 255.0F;
    }

    private static float blue(int argb) {
        return (argb & 0xFF) / 255.0F;
    }
}
