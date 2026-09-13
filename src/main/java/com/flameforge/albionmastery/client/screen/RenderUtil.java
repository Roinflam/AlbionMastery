package com.flameforge.albionmastery.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
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
    private static final int CIRCLE_SEGMENTS = 28;

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
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        return buffer;
    }

    /**
     * 结束并提交本批图元。
     */
    public static void end() {
        Tesselator.getInstance().end();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    // ======================================================================
    // 六边形
    // ======================================================================

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
            // 偏移 30 度得到平顶六边形
            double angle = Math.PI / 6.0D + Math.PI / 3.0D * i;
            points[i][0] = cx + (float) (Math.cos(angle) * radius);
            points[i][1] = cy + (float) (Math.sin(angle) * radius);
        }
        return points;
    }

    /**
     * 以扇形三角化的方式填充一个凸多边形。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param points 顶点数组
     * @param argb   颜色
     */
    public static void polygon(BufferBuilder buffer, Matrix4f matrix, float[][] points, int argb) {
        if (points.length < 3) {
            return;
        }
        float a = alpha(argb);
        float r = red(argb);
        float g = green(argb);
        float b = blue(argb);
        for (int i = 1; i < points.length - 1; i++) {
            buffer.vertex(matrix, points[0][0], points[0][1], 0.0F).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, points[i][0], points[i][1], 0.0F).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, points[i + 1][0], points[i + 1][1], 0.0F).color(r, g, b, a).endVertex();
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

    // ======================================================================
    // 圆与线
    // ======================================================================

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
        double step = Math.PI * 2.0D / CIRCLE_SEGMENTS;
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double a0 = step * i;
            double a1 = step * (i + 1);
            buffer.vertex(matrix, cx, cy, 0.0F).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, cx + (float) (Math.cos(a0) * radius), cy + (float) (Math.sin(a0) * radius), 0.0F)
                    .color(r, g, b, a).endVertex();
            buffer.vertex(matrix, cx + (float) (Math.cos(a1) * radius), cy + (float) (Math.sin(a1) * radius), 0.0F)
                    .color(r, g, b, a).endVertex();
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
        int segments = Math.max(2, (int) Math.ceil(CIRCLE_SEGMENTS * (sweep / (Math.PI * 2.0D))));
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

            buffer.vertex(matrix, x0i, y0i, 0.0F).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, x0o, y0o, 0.0F).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, x1o, y1o, 0.0F).color(r, g, b, a).endVertex();

            buffer.vertex(matrix, x0i, y0i, 0.0F).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, x1o, y1o, 0.0F).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, x1i, y1i, 0.0F).color(r, g, b, a).endVertex();
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

        buffer.vertex(matrix, x1 + nx, y1 + ny, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1 - nx, y1 - ny, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2 - nx, y2 - ny, 0.0F).color(r, g, b, a).endVertex();

        buffer.vertex(matrix, x1 + nx, y1 + ny, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2 - nx, y2 - ny, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2 + nx, y2 + ny, 0.0F).color(r, g, b, a).endVertex();
    }

    // ======================================================================
    // 颜色工具
    // ======================================================================

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
