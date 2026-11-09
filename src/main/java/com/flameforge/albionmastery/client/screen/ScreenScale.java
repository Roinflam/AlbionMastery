package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 专精界面自己的界面缩放（1 界面像素 = 几个屏幕像素），不跟原版「界面尺寸」走。
 *
 * <p>原版自动档只看窗口高度：1080p 是 4 倍、1440p 是 6 倍。专精罗盘、特化页这些界面版面大、字多，
 * 照原版那一档放大后在大屏上显得又大又挤，能放下的卡片和行数反而少。这里给它们单独挑一档：</p>
 *
 * <ul>
 *     <li>只取整数倍。MC 的字是点阵字，2.5 倍这种半档下笔画宽窄交替，同一行字看着忽粗忽细。</li>
 *     <li>自动：按显示器的物理尺寸算，让 1 界面像素在屏幕上约 {@value #TARGET_MM} 毫米，四舍五入到整数倍；
 *     再不超过显示器还能放下 640×360 版面的那一档（1080p 3 倍、1440p 4 倍、4K 6 倍），也不超过原版当前那一档。
 *     显示器没报物理尺寸、或报得离谱时当 27 寸算。</li>
 *     <li>窗口放不下 480×270 的版面就往下退一倍（特化页、罗盘最小按这个尺寸排过版）。</li>
 *     <li>玩家可以用界面上的放大镜按钮在自动的基础上加减一倍，存在客户端配置 {@code screenScale}：
 *     {@code auto}、{@code auto+1}、{@code auto-1}；写一个数（如 {@code 3}）就固定用那一档。</li>
 * </ul>
 *
 * <p>做法：专精界面 init 时把窗口的 guiScale 换成这一档（投影、鼠标坐标、裁剪都从窗口读，跟着一起变），
 * removed 时换回原版算出来的那一档。界面之间切换走的是 setScreen：旧界面 removed、新界面 init 在同一次调用里，
 * 中间不出帧，所以从罗盘点进特化页不会闪一下原版大小。窗口改大小时原版先换回自己那一档再调 resize → init，
 * 这里会重新换过来。</p>
 */
public final class ScreenScale {

    /**
     * 自动档的目标：1 界面像素在屏幕上约多少毫米。要换落点只改这个数：
     * 0.85 时 24 寸 1080p 3.07 → 3 倍、27 寸 1440p 3.64 → 4 倍、27 寸 4K 5.46 → 5 倍。
     */
    static final double TARGET_MM = 0.85;
    /** 读不到显示器物理尺寸时，当它是 27 寸 16:9（宽 597.7 毫米）来算像素距。 */
    private static final double FALLBACK_WIDTH_MM = 597.7;
    private static final int MAX = 8;
    private static final int MIN_W = 480;
    private static final int MIN_H = 270;
    private static final int DESIGN_W = 640;
    private static final int DESIGN_H = 360;
    private static final Pattern VALUE = Pattern.compile("auto(?:([+-])([1-8]))?|[1-8]");

    private ScreenScale() {
    }

    /**
     * 把窗口换成专精界面这一档。
     *
     * @return 换完后的界面宽高 {w, h}
     */
    public static int[] apply(Minecraft mc) {
        Window win = mc.getWindow();
        int s = pick(mc);
        if (win.getGuiScale() != s) {
            win.setGuiScale(s);
        }
        return new int[]{win.getGuiScaledWidth(), win.getGuiScaledHeight()};
    }

    /** 换回原版那一档（和 Minecraft.resizeDisplay 同一个算法）。 */
    public static void restore(Minecraft mc) {
        Window win = mc.getWindow();
        int s = vanilla(mc);
        if (win.getGuiScale() != s) {
            win.setGuiScale(s);
        }
    }

    /**
     * 放大 / 缩小一倍：dir = +1 放大、-1 缩小。到头了返回 false、什么都不改。改完由界面重新 init。
     */
    public static boolean bump(Minecraft mc, int dir) {
        Setting set = read();
        Window win = mc.getWindow();
        int now = pick(mc);
        if (set.fixed > 0) {
            // 固定档也按一倍加减，加减完还是固定档
            int s = fit(win, set.fixed + dir);
            if (s == now) {
                return false;
            }
            write(String.valueOf(s));
        } else {
            int a = auto(mc);
            int s = fit(win, a + set.offset + dir);
            if (s == now) {
                return false;
            }
            int offset = s - a;
            write(offset == 0 ? "auto" : "auto" + (offset > 0 ? "+" : "") + offset);
        }
        return true;
    }

    /** 「恢复自动」：去掉手动加减和固定档；已经是自动返回 false。改完由界面重新 init。 */
    public static boolean reset() {
        if (isAuto()) {
            return false;
        }
        write("auto");
        return true;
    }

    /** 现在是不是自动档（没加减过、也没写固定档）。 */
    public static boolean isAuto() {
        Setting set = read();
        return set.fixed <= 0 && set.offset == 0;
    }

    /** 现在用的这一档（专精界面开着时才准）。 */
    public static int current(Minecraft mc) {
        return (int) Math.round(mc.getWindow().getGuiScale());
    }

    /** 自动档会是几倍（卡片上「自动是 4×」用）。 */
    public static int autoScale(Minecraft mc) {
        return fit(mc.getWindow(), auto(mc));
    }

    /** 放大 / 缩小还有没有余地（按钮变灰用）。 */
    public static boolean canBump(Minecraft mc, int dir) {
        Setting set = read();
        int base = set.fixed > 0 ? set.fixed : auto(mc) + set.offset;
        return fit(mc.getWindow(), base + dir) != pick(mc);
    }

    /** 倍数写成「4×」。 */
    public static String label(int s) {
        return s + "×";
    }

    static int pick(Minecraft mc) {
        Setting set = read();
        return fit(mc.getWindow(), set.fixed > 0 ? set.fixed : auto(mc) + set.offset);
    }

    /** 自动档：按像素距四舍五入，不超过显示器能放下 640×360 的那一档，不超过原版当前那一档。 */
    static int auto(Minecraft mc) {
        Window win = mc.getWindow();
        int[] scr = screenSize(win);
        double pitch = pitchMm(win);
        if (pitch <= 0) {
            pitch = FALLBACK_WIDTH_MM / Math.max(1, scr[0]);
        }
        // 按显示器分辨率而不是窗口算上限：最大化的窗口去掉标题栏、任务栏后高度不到 1080，按窗口算会掉一档；
        // 窗口真放不下由 fit 往下退
        int cap = Math.max(1, Math.min(scr[0] / DESIGN_W, scr[1] / DESIGN_H));
        int raw = (int) Math.max(1, Math.round(TARGET_MM / pitch));
        return Math.min(Math.min(raw, cap), vanilla(mc));
    }

    /** 夹到 1～8 倍；画布小于 480×270 就往下退一倍。 */
    static int fit(Window win, int s) {
        s = Math.max(1, Math.min(MAX, s));
        while (s > 1 && (win.getWidth() / s < MIN_W || win.getHeight() / s < MIN_H)) {
            s--;
        }
        return s;
    }

    /** 窗口所在显示器的分辨率 {宽, 高}（读不到就用窗口的）。 */
    private static int[] screenSize(Window win) {
        try {
            Monitor mon = win.findBestMonitor();
            VideoMode vm = mon == null ? null : mon.getCurrentMode();
            if (vm != null && vm.getWidth() > 0 && vm.getHeight() > 0) {
                return new int[]{vm.getWidth(), vm.getHeight()};
            }
        } catch (Throwable ignored) {
            // 取不到显示器信息时按窗口算
        }
        return new int[]{win.getWidth(), win.getHeight()};
    }

    /** 窗口所在显示器一个屏幕像素多宽（毫米）；读不到或明显不对返回 0。 */
    private static double pitchMm(Window win) {
        try {
            Monitor mon = win.findBestMonitor();
            if (mon == null) {
                return 0;
            }
            VideoMode vm = mon.getCurrentMode();
            int[] wmm = new int[1];
            int[] hmm = new int[1];
            GLFW.glfwGetMonitorPhysicalSize(mon.getMonitor(), wmm, hmm);
            if (vm == null || vm.getWidth() <= 0 || wmm[0] < 150 || wmm[0] > 2000) {
                return 0;
            }
            double p = (double) wmm[0] / vm.getWidth();
            return p >= 0.1 && p <= 0.7 ? p : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int vanilla(Minecraft mc) {
        return mc.getWindow().calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode());
    }

    /** 配置里的值：fixed > 0 是固定档，否则 offset 是在自动上加减的倍数。认不出的当 auto。 */
    private record Setting(int fixed, int offset) {
    }

    private static Setting read() {
        String v;
        try {
            v = MasteryConfig.CLIENT.screenScale.get().trim();
        } catch (Exception e) {
            return new Setting(0, 0);
        }
        Matcher m = VALUE.matcher(v);
        if (!m.matches()) {
            return new Setting(0, 0);
        }
        if (!v.startsWith("auto")) {
            return new Setting(Integer.parseInt(v), 0);
        }
        if (m.group(1) == null) {
            return new Setting(0, 0);
        }
        int n = Integer.parseInt(m.group(2));
        return new Setting(0, "-".equals(m.group(1)) ? -n : n);
    }

    private static void write(String value) {
        MasteryConfig.CLIENT.screenScale.set(value);
        MasteryConfig.CLIENT.screenScale.save();
    }
}
