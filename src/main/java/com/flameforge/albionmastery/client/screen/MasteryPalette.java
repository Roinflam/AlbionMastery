package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

/**
 * 界面配色。
 *
 * <p>照阿尔比恩命运板的路子走：近黑的暖褐底 + 金色描边，
 * 解锁的节点发亮金光，未解锁的压成暗青铜色。
 *
 * <p>刻意不给每个来源随机分配色相——那样会变成一盘彩虹节点，
 * 阿尔比恩的整体感恰恰来自「全场只有一种金」。
 *
 * <p>每种颜色都能在客户端配置 {@code [client.palette]} 里改，整合包想统一界面风格时用。
 * 所以这些字段不是常量，别在别处用 {@code static final} 把它们抄一份，那样改了配置不生效。
 */
public final class MasteryPalette {

    public static int BG = 0xF20B0906;

    /**
     * 面板底色。不透明：罗盘的连线和节点铭牌是亮金色，压在半透明面板底下会透出一道，
     * 看着像线画到了面板上面。
     */
    public static int PANEL = 0xFF130F09;

    /** 按钮、卡片悬停时的底色。 */
    public static int PANEL_HOVER = 0xFF1E1810;

    /** 进度条没填满那一截。 */
    public static int TRACK = 0xFF17120A;

    /** 面板与分隔线的暗金。 */
    public static int EDGE = 0xFF6B5223;

    /** 主文字（羊皮纸白）。 */
    public static int TEXT = 0xFFEFE3C8;

    public static int TEXT_DIM = 0xFF9C8E70;

    public static int GOLD = 0xFFE8C25A;

    /** 高亮金（选中、满级）。 */
    public static int GOLD_BRIGHT = 0xFFFFE9A8;

    /** 暗金（未解锁的描边与连线）。 */
    public static int GOLD_DARK = 0xFF5A4520;

    /** 节点底板（压在六边形下面的一圈深色）。 */
    public static int NODE_BACKPLATE = 0xFF070604;

    public static int NODE_FILL = 0xFF2E2617;

    public static int NODE_FILL_LOCKED = 0xFF191510;

    public static int PROGRESS = 0xFFB98F28;

    public static int LINE = 0xFFA07C33;

    public static int LINE_LOCKED = 0xFF453722;

    public static int WARN = 0xFFE08A3C;

    /** 白金：比亮金再白一档，只给「整条链路都满级」的节点用，是这套金色里的最高一档。 */
    public static int GOLD_WHITE = 0xFFFFF7DE;

    /** 盖在背景图上的一层颜色，压暗一点让字看得清。 */
    public static int BG_IMAGE_TINT = 0x80000000;

    private static ResourceLocation backgroundImage;
    /** 0 = 没查过，-1 = 资源里没有这张图，正数 = 图的宽。 */
    private static int backgroundWidth;
    private static int backgroundHeight;

    private MasteryPalette() {
    }

    /**
     * 把颜色的透明度换掉，RGB 不变。
     */
    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /**
     * 铺满整屏的背景：资源里有背景图就画图再盖一层 {@link #BG_IMAGE_TINT}，没有就是纯色 {@link #BG}。
     */
    public static void drawBackground(GuiGraphics guiGraphics, int width, int height) {
        if (backgroundImage != null && backgroundWidth == 0) {
            probeBackground();
        }
        if (backgroundImage == null || backgroundWidth <= 0) {
            guiGraphics.fill(0, 0, width, height, BG);
            return;
        }
        // 等比铺满、居中裁掉多出来的部分，不拉伸变形
        float scale = Math.max((float) width / backgroundWidth, (float) height / backgroundHeight);
        float u = (backgroundWidth - width / scale) / 2.0F;
        float v = (backgroundHeight - height / scale) / 2.0F;
        guiGraphics.blit(backgroundImage, 0, 0, width, height, u, v,
                Math.round(width / scale), Math.round(height / scale), backgroundWidth, backgroundHeight);
        guiGraphics.fill(0, 0, width, height, BG_IMAGE_TINT);
    }

    /**
     * 资源重载后重新看一次背景图在不在。
     */
    public static void invalidateBackground() {
        backgroundWidth = 0;
        backgroundHeight = 0;
    }

    private static void probeBackground() {
        backgroundWidth = -1;
        Optional<net.minecraft.server.packs.resources.Resource> resource =
                Minecraft.getInstance().getResourceManager().getResource(backgroundImage);
        if (resource.isEmpty()) {
            return;
        }
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            backgroundWidth = image.getWidth();
            backgroundHeight = image.getHeight();
        } catch (Exception exception) {
            AlbionMastery.LOGGER.warn("Failed to read mastery screen background {}", backgroundImage, exception);
        }
    }

    /**
     * 从客户端配置读颜色。配置里填错的项保持原值并打一行日志。
     */
    public static void reload() {
        Map<String, ForgeConfigSpec.ConfigValue<String>> colors = MasteryConfig.CLIENT.paletteColors;
        BG = color(colors, "background", BG);
        BG_IMAGE_TINT = color(colors, "backgroundImageTint", BG_IMAGE_TINT);
        PANEL = color(colors, "panel", PANEL);
        PANEL_HOVER = color(colors, "panelHover", PANEL_HOVER);
        TRACK = color(colors, "barTrack", TRACK);
        EDGE = color(colors, "edge", EDGE);
        TEXT = color(colors, "text", TEXT);
        TEXT_DIM = color(colors, "textDim", TEXT_DIM);
        GOLD = color(colors, "gold", GOLD);
        GOLD_BRIGHT = color(colors, "goldBright", GOLD_BRIGHT);
        GOLD_DARK = color(colors, "goldDark", GOLD_DARK);
        GOLD_WHITE = color(colors, "goldWhite", GOLD_WHITE);
        NODE_BACKPLATE = color(colors, "nodeBackplate", NODE_BACKPLATE);
        NODE_FILL = color(colors, "nodeFill", NODE_FILL);
        NODE_FILL_LOCKED = color(colors, "nodeFillLocked", NODE_FILL_LOCKED);
        PROGRESS = color(colors, "progress", PROGRESS);
        LINE = color(colors, "line", LINE);
        LINE_LOCKED = color(colors, "lineLocked", LINE_LOCKED);
        WARN = color(colors, "warn", WARN);

        String image = MasteryConfig.CLIENT.backgroundImage.get().trim();
        backgroundImage = image.isEmpty() ? null : ResourceLocation.tryParse(image);
        if (!image.isEmpty() && backgroundImage == null) {
            AlbionMastery.LOGGER.warn("Invalid mastery screen background image id: {}", image);
        }
        invalidateBackground();
    }

    private static int color(Map<String, ForgeConfigSpec.ConfigValue<String>> colors, String key, int fallback) {
        ForgeConfigSpec.ConfigValue<String> value = colors.get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.get();
        Integer parsed = text.isBlank() ? null : MasteryConfig.parseColor(text);
        if (parsed == null) {
            if (!text.isBlank()) {
                AlbionMastery.LOGGER.warn("Invalid color for client.palette.{}: {}", key, text);
            }
            parsed = MasteryConfig.parseColor(value.getDefault());
        }
        return parsed == null ? fallback : parsed;
    }
}
