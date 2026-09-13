package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.client.ClientMasteryCache;
import com.flameforge.albionmastery.client.KeyBindings;
import com.flameforge.albionmastery.client.weapon.ClientWeaponRegistry;
import com.flameforge.albionmastery.client.weapon.WeaponEntry;
import com.flameforge.albionmastery.client.weapon.WeaponSource;
import com.flameforge.albionmastery.client.weapon.WeaponTypeGroup;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.LevelCurve;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.network.C2SSelectSpecializationPacket;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 阿尔比恩专精系统主界面。
 *
 * <p>五层放射状节点树，节点是六边形，等级越高从底部填得越满：
 * <pre>
 *   总览 → 来源模组 → 总体 → 武器类型 → 具体武器
 * </pre>
 *
 * <p>操作：左键点节点看详情 / 点来源或类型展开收起 / 空白处拖动平移 / 滚轮缩放 / ESC 关闭。
 */
public class MasteryScreen extends Screen {

    /** 左侧详情面板宽度，点节点才出现。 */
    private static final int PANEL_WIDTH = 186;

    /** 面板内边距。 */
    private static final int PANEL_PAD = 10;

    /** 正文行高。 */
    private static final int LINE = 10;

    /**
     * 缩放的绝对下限，只在图大到离谱时兜底。
     *
     * <p>真正的下限是 {@link #minZoom()} 按当前这张图算出来的：图小就不许缩太小，
     * 图大才允许缩得更小。写死一个下限两头都不对——只有几把武器时能缩到看不见，
     * 几千把武器时想看全貌又缩不下去。
     */
    private static final double MIN_ZOOM_FLOOR = 0.012D;

    /** 缩放上限。 */
    private static final double MAX_ZOOM = 6.0D;

    /**
     * 缩到最小时，外圈武器节点至少还有多少像素半径。
     *
     * <p>这是「不许缩太小」那一半：再小节点就成了一粒像素，看着毫无意义。
     * 22 半径的节点按这个算出来的下限约 0.07，和老版本手调出来的那个值差不多。
     */
    private static final double MIN_NODE_PIXELS_AT_MIN_ZOOM = 1.6D;

    /**
     * 缩到最小时整张图占屏幕的比例（相对「刚好放下」的缩放再乘这个系数）。
     *
     * <p>这是「允许看全貌」那一半：留三成余量，图四周有点空当，不至于贴着边框。
     */
    private static final double MIN_ZOOM_WHOLE_FACTOR = 0.7D;

    /** 每滚一格的缩放倍数。步子小一点更好微调。 */
    private static final double ZOOM_STEP = 1.11D;

    /** 节点标签的字号缩放上下限，让文字跟着板子一起缩放而不是固定大小。 */
    private static final float LABEL_SCALE_MIN = 0.5F;
    private static final float LABEL_SCALE_MAX = 1.1F;

    /** 节点屏幕半径小于该值才不渲染图标。只挡住小到没有意义的那一档，正常缩放一律画。 */
    private static final float ICON_MIN_SCREEN_RADIUS = 1.5F;

    /**
     * 单帧最多渲染多少个物品图标。
     *
     * <p>TACZ 枪械用的是自定义 3D 渲染器，一个图标就是一次模型绘制。缩到能看见全貌时
     * 上百个节点全在视野内，全画就是每帧上百次模型渲染，低配机器会直接掉帧。
     * 超过这个数就按「离视野中心的远近」取前 N 个——你正在看的那一片一定有图标。
     */
    private static final int MAX_ICONS_PER_FRAME = 120;

    /** 铭牌里带上「/ 上限」所需的最小缩放，再小就只留等级数字。 */
    private static final double LEVEL_DETAIL_ZOOM = 0.55D;

    /** 显示武器名称所需的最小缩放。 */
    private static final double NAME_ZOOM = 0.68D;

    /** 分支合计行相对节点名称的字号系数，比名称小一号，免得抢了名字的视线。 */
    private static final float SUM_LABEL_SCALE = 0.82F;

    /** 对准手持武器时，相对「看全貌」缩放放大多少倍。 */
    private static final double FOCUS_ZOOM_FACTOR = 5.0D;

    /** 对准手持武器时的缩放下限，保证节点图标认得出来。 */
    private static final double MIN_FOCUS_ZOOM = 0.5D;

    /**
     * 对准手持武器时的缩放上限。
     *
     * <p>更早的版本这里写死 0.75，看着最舒服，但整张图变大之后写死就成了「贴脸」。
     * 现在按「看全貌」的倍数算，再用这个值封顶——大图小图都落在同一种观感上。
     */
    private static final double MAX_FOCUS_ZOOM = 0.78D;

    /** 悬停提示的左右内边距。 */
    private static final int TOOLTIP_PAD_X = 5;

    /** 悬停提示的上下内边距。 */
    private static final int TOOLTIP_PAD_Y = 4;

    /** 悬停提示里那根等级条的高度。 */
    private static final int BAR_HEIGHT = 3;

    /** 高亮链路上每段连线跑几颗流光。 */
    private static final int FLOW_DOTS = 3;

    /** 中心装饰环的弧段数量。 */
    private static final int CENTER_ARCS = 5;

    /** 满级节点外围绕圈的星点数量。 */
    private static final int MAXED_STARS = 4;

    /** 选中节点外围绕圈的小光点数量。 */
    private static final int ORBIT_DOTS = 6;

    /** 顶栏高度。 */
    private static final int HEADER_HEIGHT = 20;

    /** 搜索框宽度。 */
    private static final int SEARCH_WIDTH = 150;

    /** 搜索框描边到文字之间的内边距。 */
    private static final int SEARCH_PADDING = 5;

    /** 流派卡片之间的间距。 */
    private static final int SPEC_CARD_GAP = 3;

    /** 流派卡片内容相对面板左边缘的缩进。 */
    private static final int SPEC_TEXT_INSET = 3;

    /** 「得 / 失」行首符号占的宽度。 */
    private static final int SPEC_BULLET_WIDTH = 8;

    /** 默认视角下外圈节点至少要有的屏幕像素半径。 */
    private static final double MIN_DEFAULT_NODE_PIXELS = 5.0D;

    /** 重置视角按钮的尺寸。 */
    private static final int BUTTON_WIDTH = 74;
    private static final int BUTTON_HEIGHT = 17;

    /** 单个节点出现动画的时长（毫秒）。 */
    private static final float APPEAR_MS = 240.0F;

    /** 详情面板滚轮一格滚动的像素数。 */
    private static final double PANEL_SCROLL_STEP = 18.0D;

    private List<WeaponSource> sources = List.of();
    private List<MasteryNode> nodes = List.of();

    @Nullable
    private MasteryNode selected;

    @Nullable
    private MasteryNode hovered;

    /** 选中节点到中心的整条链路，用于高亮。 */
    private final Set<MasteryNode> pathNodes = new HashSet<>();

    /** 上一帧详情面板内容的底边，用来把底板画成刚好包住内容的高度。 */
    private int panelBottom = 120;

    /** 详情面板的滚动量（像素，向下为正）。 */
    private double panelScroll;

    /**
     * 各层节点所在的图坐标半径，用来在背景上画同心刻度环。
     *
     * <p>不是另外算一遍布局，而是直接量现成节点到中心的距离——布局怎么改这里都跟着对。
     */
    private final double[] ringRadii = new double[MasteryNode.Type.values().length];

    /**
     * 来源节点那一圈的半径，中心装饰环按它定大小。
     *
     * <p>没有任何来源时保持 0，装饰环就不画——总比按一个凭空的默认值画出来对不上强。
     */
    private double sourceRingRadius;

    /** 上一帧面板显示的是哪个节点，换节点时用来把滚动归零。 */
    @Nullable
    private MasteryNode panelNode;

    /** 上一帧面板内容是否超出可视区域，决定滚轮要不要被面板吃掉。 */
    private boolean panelScrollable;

    /** 顶栏右侧的武器搜索框。 */
    @Nullable
    private EditBox searchBox;

    /** 正在画流派卡片的那个类型键，点击发包时要带上。 */
    @Nullable
    private String specTypeKey;

    /** 当前搜索命中的节点，空搜索时为空集。 */
    private final Set<MasteryNode> searchHits = new HashSet<>();

    /** 本帧鼠标位置，面板要靠它做悬停高亮。 */
    private int mouseXNow;

    /** 本帧鼠标位置，面板要靠它做悬停高亮。 */
    private int mouseYNow;

    /**
     * 上一帧画出来的流派选项点击区。
     *
     * <p>存的是「内容坐标」——也就是没有减去滚动量的 Y。点击时把鼠标 Y 加上滚动量再比，
     * 免得面板一滚点击区就全错位。
     */
    private final List<SpecHit> specHits = new ArrayList<>();

    /** 本帧缓存的全局进度。中心节点的填充和说明面板都要用，一帧算一次就够。 */
    @Nullable
    private Progress frameProgress;

    /**
     * 本帧缓存的分支等级合计：节点数据键 -> 合计。
     *
     * <p>来源节点要把底下上百件武器的等级加一遍，一帧里同一个节点只算一次，
     * 否则每帧都要把整个武器表遍历好几轮。
     */
    private final Map<String, Aggregate> frameAggregates = new HashMap<>();

    private long layoutTimeMs;
    private long lastFrameMs;

    private double panX;
    private double panY;
    private double zoom = 0.3D;
    private double targetZoom = 0.3D;

    /** 整张图的外接半径（含外圈武器）。 */
    private double outerRadius = 1.0D;

    /** 内四层（总览 / 模组 / 总体 / 类型）的外接半径。 */
    private double focusRadius = 1.0D;

    /** 外圈武器节点的半径，用于保证默认视角下图标不会小到看不清。 */
    private double weaponNodeRadius = 22.0D;

    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;
    private boolean initialized;

    /**
     * 上一次关闭界面时的视角，下次打开直接接着看。
     *
     * <p>做成静态的：界面每次打开都是新对象，不存在这里就只能每次从头调。
     * 只在本次游戏进程内有效，重进游戏回到默认视角。
     */
    @Nullable
    private static ViewState savedView;

    /** 缩放锚点是否有效。 */
    private boolean hasZoomAnchor;

    /** 缩放锚点在图空间的坐标。 */
    private double anchorGraphX;

    /** 缩放锚点在图空间的坐标。 */
    private double anchorGraphY;

    /** 缩放锚点在屏幕上的坐标。 */
    private double anchorScreenX;

    /** 缩放锚点在屏幕上的坐标。 */
    private double anchorScreenY;

    /**
     * 构造界面。
     */
    public MasteryScreen() {
        super(Component.translatable("gui.albionmastery.title"));
    }

    @Override
    protected void init() {
        // 每次打开都重建一次清单：玩家可能中途重载了枪包
        this.sources = ClientWeaponRegistry.rebuild();
        rebuildNodes();
        if (!this.initialized) {
            // 顺序很重要：手上拿着武器就一定定位到那把。
            // 「对着物品按 B」这个动作本身就是在说「我要看这把」，
            // 拿上次的视角把它顶掉，等于把这个功能废了。
            if (!focusHeldWeapon() && !restoreSavedView()) {
                // 空手打开、又没有上次的视角：居中看全貌
                this.zoom = fitZoom();
                this.targetZoom = this.zoom;
            }
            this.initialized = true;
        }
        // 搜索框放顶栏右端。用原版 EditBox 而不是自绘：输入法、光标、选中、
        // 粘贴这些细节自己实现一遍不现实，样式差一点可以接受
        // 去掉原版边框之后要自己留出左右内边距，不然文字会贴着描边
        this.searchBox = new EditBox(this.font, this.width - SEARCH_WIDTH - 8 + SEARCH_PADDING, 5,
                SEARCH_WIDTH - SEARCH_PADDING * 2, HEADER_HEIGHT - 10,
                Component.translatable("gui.albionmastery.search"));
        this.searchBox.setHint(Component.translatable("gui.albionmastery.search")
                .withStyle(ChatFormatting.DARK_GRAY));
        // 关掉原版那圈灰边和黑底，自己画一个和整体配色一致的框
        this.searchBox.setBordered(false);
        this.searchBox.setTextColor(MasteryPalette.TEXT & 0xFFFFFF);
        this.searchBox.setTextColorUneditable(MasteryPalette.TEXT_DIM & 0xFFFFFF);
        this.searchBox.setMaxLength(48);
        this.searchBox.setResponder(text -> updateSearch());
        addRenderableWidget(this.searchBox);
        updateSearch();

        this.lastFrameMs = Util.getMillis();
    }

    /**
     * 画搜索框的外框。
     *
     * <p>原版 {@code EditBox} 自带的是灰边黑底，和这套金褐配色完全不搭。
     * 关掉它的边框自己画：底色用面板色，描边平时是暗金，获得焦点后亮起来并缓慢呼吸，
     * 有命中结果时右端补一个计数。
     *
     * @param guiGraphics 绘图上下文
     * @param now         当前时刻
     */
    private void drawSearchFrame(GuiGraphics guiGraphics, long now) {
        int left = this.width - SEARCH_WIDTH - 8;
        int right = this.width - 8;
        int top = 3;
        int bottom = HEADER_HEIGHT - 3;

        boolean focused = this.searchBox != null && this.searchBox.isFocused();
        float pulse = focused ? 0.65F + 0.35F * (float) Math.sin(now / 320.0D) : 0.0F;
        int border = focused
                ? RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT, 0.55F + 0.45F * pulse)
                : MasteryPalette.GOLD_DARK;

        guiGraphics.fill(left, top, right, bottom, 0xC0100C07);
        guiGraphics.fill(left, top, right, top + 1, border);
        guiGraphics.fill(left, bottom - 1, right, bottom, border);
        guiGraphics.fill(left, top, left + 1, bottom, border);
        guiGraphics.fill(right - 1, top, right, bottom, border);

        if (!this.searchHits.isEmpty()) {
            String count = String.valueOf(this.searchHits.size());
            int width = this.font.width(count);
            guiGraphics.drawString(this.font, count, right - width - SEARCH_PADDING,
                    top + (bottom - top - this.font.lineHeight) / 2 + 1,
                    MasteryPalette.GOLD, false);
        }

        // 有内容时在框底下提一句怎么用：不写的话没人知道回车能跳过去
        if (this.searchBox != null && !this.searchBox.getValue().trim().isEmpty()) {
            Component hint = this.searchHits.isEmpty()
                    ? Component.translatable("gui.albionmastery.search_empty")
                    : Component.translatable("gui.albionmastery.search_hint");
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(right, bottom + 3.0F, 0.0F);
            guiGraphics.pose().scale(0.75F, 0.75F, 1.0F);
            guiGraphics.drawString(this.font, hint, -this.font.width(hint), 0,
                    this.searchHits.isEmpty() ? MasteryPalette.WARN : MasteryPalette.TEXT_DIM, false);
            guiGraphics.pose().popPose();
        }
    }

    /**
     * 按搜索框里的内容重算命中集合。
     *
     * <p>只匹配节点的显示名，大小写不敏感。空搜索时清空集合，界面就不画任何高亮。
     */
    private void updateSearch() {
        this.searchHits.clear();
        if (this.searchBox == null) {
            return;
        }
        String query = this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return;
        }
        for (MasteryNode node : this.nodes) {
            Component name = node.displayName();
            if (name != null && name.getString().toLowerCase(Locale.ROOT).contains(query)) {
                this.searchHits.add(node);
            }
        }
    }

    /**
     * 跳到第一个搜索命中的节点。
     *
     * <p>顺序按节点列表的自然顺序，也就是由内向外、同层按布局顺序，
     * 大致等于「最上层的那个先来」。
     *
     * @return 跳过去了返回 true
     */
    private boolean jumpToFirstHit() {
        for (MasteryNode node : this.nodes) {
            if (!this.searchHits.contains(node)) {
                continue;
            }
            this.selected = node;
            updatePath();
            this.zoom = focusZoom();
            this.targetZoom = this.zoom;
            this.hasZoomAnchor = false;
            this.panX = -node.graphX * this.zoom;
            this.panY = -node.graphY * this.zoom;
            return true;
        }
        return false;
    }

    /**
     * 给搜索命中的节点画一圈脉动的高亮。
     *
     * <p>画在所有节点之上、不改动节点本身的绘制：命中集合一变就只是多画几个圈，
     * 不用去动那套已经挺复杂的节点渲染逻辑。
     *
     * @param guiGraphics 绘图上下文
     * @param now         当前时刻
     */
    private void drawSearchHighlights(GuiGraphics guiGraphics, long now) {
        if (this.searchHits.isEmpty()) {
            return;
        }
        float pulse = 0.55F + 0.45F * (float) Math.sin(now / 260.0D);
        int color = RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT, 0.35F + 0.45F * pulse);
        for (MasteryNode node : this.searchHits) {
            double screenX = baseX() + this.panX + node.graphX * this.zoom;
            double screenY = baseY() + this.panY + node.graphY * this.zoom;
            double radius = node.radius * this.zoom + 4.0D;
            if (screenX + radius < 0 || screenX - radius > this.width
                    || screenY + radius < 0 || screenY - radius > this.height) {
                continue;
            }
            // 四条边框拼一个方框，比画圆便宜，缩得很小的时候也看得见
            int left = (int) Math.round(screenX - radius);
            int right = (int) Math.round(screenX + radius);
            int top = (int) Math.round(screenY - radius);
            int bottom = (int) Math.round(screenY + radius);
            guiGraphics.fill(left, top, right, top + 1, color);
            guiGraphics.fill(left, bottom - 1, right, bottom, color);
            guiGraphics.fill(left, top, left + 1, bottom, color);
            guiGraphics.fill(right - 1, top, right, bottom, color);
        }
    }

    @Override
    public void removed() {
        super.removed();
        // 关界面时把视角记下来，下次打开接着看，不用每次重新缩放和拖动
        savedView = new ViewState(this.zoom, this.panX, this.panY,
                this.selected == null ? null : this.selected.dataKey());
    }

    /**
     * 丢掉记住的视角。
     *
     * <p>断线或者切服时调用：换个服务器武器清单完全不一样，拿旧坐标恢复出来就是对着一片空白。
     */
    public static void forgetView() {
        savedView = null;
    }

    /**
     * 算「对准某个节点」时该用多大的缩放。
     *
     * @return 缩放
     */
    private double focusZoom() {
        double wanted = Math.max(fitZoom() * FOCUS_ZOOM_FACTOR, MIN_FOCUS_ZOOM);
        return Mth.clamp(Math.min(wanted, MAX_FOCUS_ZOOM), minZoom(), maxZoom());
    }

    /**
     * 当前这张图允许缩到多小。
     *
     * <p>取两个约束里更大的那个：
     * <ul>
     *     <li>整张图放进屏幕之后再留三成余量（{@value #MIN_ZOOM_WHOLE_FACTOR}）——图小的时候
     *         这一条起作用，只有几把武器时缩到底也就是「整张图占大半个屏幕」；</li>
     *     <li>外圈节点至少 {@value #MIN_NODE_PIXELS_AT_MIN_ZOOM} 像素半径——图大的时候这一条起作用，
     *         几千把武器时能缩到看清结构，但不会缩成一片像素点。</li>
     * </ul>
     * 每次开界面、每次窗口大小变化都会重新按当前屏幕算，不缓存。
     *
     * @return 缩放下限
     */
    private double minZoom() {
        double whole = Math.min(this.width, this.height) * 0.44D / Math.max(1.0D, this.outerRadius);
        double readable = MIN_NODE_PIXELS_AT_MIN_ZOOM / Math.max(1.0D, this.weaponNodeRadius);
        double bound = Math.max(whole * MIN_ZOOM_WHOLE_FACTOR, readable);
        // 下限不能高过上限的一半，否则滚轮只剩一两格可滚
        return Mth.clamp(bound, MIN_ZOOM_FLOOR, MAX_ZOOM * 0.5D);
    }

    /**
     * 当前这张图允许放到多大。
     *
     * <p>上限本身是固定的；这里单独做成方法是为了和 {@link #minZoom()} 对称，
     * 以后要按图大小调上限只改这一处。
     *
     * @return 缩放上限
     */
    private double maxZoom() {
        return MAX_ZOOM;
    }

    /**
     * 恢复上次关界面时的视角。
     *
     * <p>节点可能因为重载枪包而变了，所以选中项按数据键去重新找，找不到就只恢复镜头位置。
     *
     * @return 恢复成功返回 true
     */
    private boolean restoreSavedView() {
        ViewState view = savedView;
        if (view == null) {
            return false;
        }
        this.zoom = Mth.clamp(view.zoom(), minZoom(), maxZoom());
        this.targetZoom = this.zoom;
        this.panX = view.panX();
        this.panY = view.panY();
        if (view.selectedKey() != null) {
            for (MasteryNode node : this.nodes) {
                if (view.selectedKey().equals(node.dataKey())) {
                    this.selected = node;
                    updatePath();
                    break;
                }
            }
        }
        return true;
    }

    /**
     * 打开界面时自动选中并对准玩家手上那把武器。
     *
     * <p>一百多个节点，每次打开都从中心开始找自己那把枪太折磨。主手优先，主手没有就看副手。
     *
     * @return 找到并对准了返回 true；空手或者手上那把不在图里返回 false
     */
    private boolean focusHeldWeapon() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ResolvedWeapon weapon = WeaponResolver.resolve(player.getItemInHand(hand),
                    ClientMasteryCache.snapshot().splitMeleeByPack);
            if (weapon == null) {
                continue;
            }
            for (MasteryNode node : this.nodes) {
                if (node.type == MasteryNode.Type.WEAPON && node.weapon != null
                        && node.weapon.key().equals(weapon.weaponKey())) {
                    this.selected = node;
                    updatePath();
                    // 拉到能看清图标和铭牌的程度，并把该节点摆到视野中心。
                    // 不再写死 0.75：统一外圈半径之后整张图大了很多，0.75 等于贴着一个节点看，
                    // 每次打开都得先往外滚半天才认得出自己在哪
                    this.zoom = focusZoom();
                    this.targetZoom = this.zoom;
                    this.panX = -node.graphX * this.zoom;
                    this.panY = -node.graphY * this.zoom;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return 重置视角按钮的左边界
     */
    private int buttonX() {
        return 12;
    }

    /**
     * @return 重置视角按钮的上边界
     */
    private int buttonY() {
        return this.height - BUTTON_HEIGHT - 12;
    }

    /**
     * 判断坐标是否落在重置视角按钮上。
     *
     * @param mouseX 鼠标 X
     * @param mouseY 鼠标 Y
     * @return 落在按钮上返回 true
     */
    private boolean isOnButton(double mouseX, double mouseY) {
        return mouseX >= buttonX() && mouseX <= buttonX() + BUTTON_WIDTH
                && mouseY >= buttonY() && mouseY <= buttonY() + BUTTON_HEIGHT;
    }

    /**
     * 画重置视角按钮。
     *
     * <p>原版 Button 那套灰石头贴图和这套金色配色完全不搭，所以自绘一个描边按钮。
     *
     * @param guiGraphics 绘图上下文
     * @param mouseX      鼠标 X
     * @param mouseY      鼠标 Y
     */
    private void drawResetButton(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = buttonX();
        int y = buttonY();
        boolean hover = isOnButton(mouseX, mouseY);
        guiGraphics.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, hover ? 0xF01E1810 : 0xC0130F09);
        int border = hover ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK;
        guiGraphics.fill(x, y, x + BUTTON_WIDTH, y + 1, border);
        guiGraphics.fill(x, y + BUTTON_HEIGHT - 1, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, border);
        guiGraphics.fill(x, y, x + 1, y + BUTTON_HEIGHT, border);
        guiGraphics.fill(x + BUTTON_WIDTH - 1, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, border);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("gui.albionmastery.reset_view"),
                x + BUTTON_WIDTH / 2, y + (BUTTON_HEIGHT - 8) / 2 + 1,
                hover ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT);
    }

    /**
     * 算出默认视角的缩放。
     *
     * <p>取两个约束里更大的那个：
     * <ul>
     *     <li>整张图刚好放进屏幕</li>
     *     <li>外圈节点至少有 {@value #MIN_DEFAULT_NODE_PIXELS} 像素，图标能看出是个什么</li>
     * </ul>
     *
     * <p>武器一多，纯按整张图适配算出来的缩放会让节点只剩两三个像素，
     * 「看到全貌」了但什么都认不出来——这正是阿尔比恩玩家吐槽最多的一点。
     * 加上节点尺寸下限之后，默认视角优先保证看得清，看全貌可以自己往外滚
     * （能滚到多小由 {@link #minZoom()} 按图的大小决定）。
     *
     * @return 缩放值
     */
    private double fitZoom() {
        double whole = Math.min(this.width, this.height) * 0.44D / Math.max(1.0D, this.outerRadius);
        double readable = MIN_DEFAULT_NODE_PIXELS / Math.max(1.0D, this.weaponNodeRadius);
        return Mth.clamp(Math.max(whole, readable) * MasteryConfig.CLIENT.defaultZoom.get(),
                minZoom(), maxZoom());
    }

    /**
     * 重建节点布局，并尽量把选中状态迁移到新节点上。
     */
    /**
     * 重算「中心 → … → 选中节点」这条链路。
     *
     * <p>高亮这条链、压暗其余部分，缩小时那一坨连线才看得出结构。
     */
    private void updatePath() {
        this.pathNodes.clear();
        MasteryNode node = this.selected;
        while (node != null) {
            this.pathNodes.add(node);
            node = node.parent;
        }
    }

    private void rebuildNodes() {
        String previous = this.selected == null ? null : this.selected.identity();
        this.nodes = NodeLayout.build(this.sources);
        this.layoutTimeMs = Util.getMillis();

        this.outerRadius = 1.0D;
        this.focusRadius = 1.0D;
        this.weaponNodeRadius = 0.0D;
        for (MasteryNode node : this.nodes) {
            double reach = Math.hypot(node.graphX, node.graphY) + node.radius;
            this.outerRadius = Math.max(this.outerRadius, reach);
            if (node.type == MasteryNode.Type.WEAPON) {
                this.weaponNodeRadius = Math.max(this.weaponNodeRadius, node.radius);
            } else {
                this.focusRadius = Math.max(this.focusRadius, reach);
            }
        }
        if (this.weaponNodeRadius <= 0.0D) {
            this.weaponNodeRadius = 22.0D;
        }

        this.searchHits.clear();
        java.util.Arrays.fill(this.ringRadii, 0.0D);
        for (MasteryNode node : this.nodes) {
            int index = node.type.ordinal();
            this.ringRadii[index] = Math.max(this.ringRadii[index],
                    Math.hypot(node.graphX, node.graphY));
        }
        this.sourceRingRadius = this.ringRadii[MasteryNode.Type.SOURCE.ordinal()];
        this.selected = null;
        if (previous != null) {
            for (MasteryNode node : this.nodes) {
                if (node.identity().equals(previous)) {
                    this.selected = node;
                    break;
                }
            }
        }
    }

    private void resetView() {
        this.panX = 0.0D;
        this.panY = 0.0D;
        this.targetZoom = fitZoom();
        this.hasZoomAnchor = false;
    }

    // ======================================================================
    // 坐标换算
    // ======================================================================

    private double baseX() {
        // 面板是浮在左侧的覆盖层，不参与居中计算，否则开关面板时整张图会跳
        return this.width / 2.0D;
    }

    private double baseY() {
        return this.height / 2.0D;
    }

    private double toScreenX(double graphX) {
        return baseX() + this.panX + graphX * this.zoom;
    }

    private double toScreenY(double graphY) {
        return baseY() + this.panY + graphY * this.zoom;
    }

    // ======================================================================
    // 渲染
    // ======================================================================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.mouseXNow = mouseX;
        this.mouseYNow = mouseY;
        long now = Util.getMillis();
        float delta = Math.min(0.1F, (now - this.lastFrameMs) / 1000.0F);
        this.lastFrameMs = now;
        this.frameProgress = null;
        this.frameAggregates.clear();

        // 缩放做指数逼近，滚轮滚起来是平滑推进而不是一跳一跳
        double before = this.zoom;
        this.zoom += (this.targetZoom - this.zoom) * (1.0F - (float) Math.exp(-16.0F * delta));
        if (this.hasZoomAnchor) {
            // 每帧都把锚点重新钉回原位。
            // 之前只在滚轮那一下按「目标缩放」算一次平移，可缩放是逐帧逼近过去的，
            // 中间每一帧的实际缩放都和当时的平移对不上，画面就会一路飘偏。
            this.panX = this.anchorScreenX - baseX() - this.anchorGraphX * this.zoom;
            this.panY = this.anchorScreenY - baseY() - this.anchorGraphY * this.zoom;
            if (Math.abs(this.targetZoom - this.zoom) < 1.0E-4D && Math.abs(this.zoom - before) < 1.0E-5D) {
                this.zoom = this.targetZoom;
                this.hasZoomAnchor = false;
            }
        }

        guiGraphics.fill(0, 0, this.width, this.height, MasteryPalette.BG);
        this.hovered = pickNode(mouseX, mouseY);
        for (MasteryNode node : this.nodes) {
            float target = node == this.hovered ? 1.0F : 0.0F;
            node.hoverAnim += (target - node.hoverAnim) * (1.0F - (float) Math.exp(-16.0F * delta));
        }

        // GuiGraphics 是批量提交的，先冲刷掉背景，再插入自绘的三角形
        guiGraphics.flush();
        drawGraph(guiGraphics, now);

        drawNodeIcons(guiGraphics, now);
        drawNodeOverlays(guiGraphics, now);
        if (this.selected != null) {
            drawPanel(guiGraphics);
        }
        drawSearchHighlights(guiGraphics, now);
        drawHeader(guiGraphics);
        if (this.searchBox != null) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0F, 0.0F, 600.0F);
            drawSearchFrame(guiGraphics, now);
            this.searchBox.render(guiGraphics, mouseX, mouseY, partialTick);
            guiGraphics.pose().popPose();
        }
        drawResetButton(guiGraphics, mouseX, mouseY);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (this.hovered != null && this.hovered != this.selected) {
            drawHoverTooltip(guiGraphics, this.hovered, mouseX, mouseY);
        }
    }

    /**
     * 计算某个节点的出现动画进度。
     *
     * @param node 节点
     * @param now  当前时刻
     * @return 进度，末尾会略微过冲再回弹
     */
    private float appearOf(MasteryNode node, long now) {
        // 当前选中的那条链立刻出现，不排队等动画。
        // 对着武器按 B 打开时，要看的就是这几个节点，让它们等一秒才冒出来毫无道理
        float delay = this.pathNodes.contains(node) ? 0.0F : node.appearDelayMs;
        float elapsed = (now - this.layoutTimeMs) - delay;
        if (elapsed <= 0.0F) {
            return 0.0F;
        }
        float t = Math.min(1.0F, elapsed / APPEAR_MS);
        float inv = t - 1.0F;
        return 1.0F + inv * inv * (2.70158F * inv + 1.70158F);
    }

    /**
     * 绘制连线与节点本体。
     *
     * @param guiGraphics 绘图上下文
     * @param now         当前时刻
     */
    private void drawGraph(GuiGraphics guiGraphics, long now) {
        Matrix4f matrix = guiGraphics.pose().last().pose();
        BufferBuilder buffer = RenderUtil.begin();
        drawGuideRings(buffer, matrix, now);
        // 选中了具体分支时才压暗其余部分；选中总览等于没筛选，不该把全屏压灰
        boolean focusing = this.selected != null && this.selected.type != MasteryNode.Type.INFO;
        // 每帧读一次就够，别在几百个节点的循环里反复取
        int maxLevel = ClientMasteryCache.snapshot().maxLevel;

        for (MasteryNode node : this.nodes) {
            if (node.parent == null) {
                continue;
            }
            float appear = Mth.clamp(appearOf(node, now), 0.0F, 1.0F);
            if (appear <= 0.0F) {
                continue;
            }
            float x1 = (float) toScreenX(node.parent.graphX);
            float y1 = (float) toScreenY(node.parent.graphY);
            float x2 = (float) toScreenX(node.graphX);
            float y2 = (float) toScreenY(node.graphY);
            // 整段都在屏幕外就跳过。上千件武器时这个循环是最大的一笔开销，
            // 而缩放到能看清细节时，视野里通常只剩几十条线
            if (isSegmentOffScreen(x1, y1, x2, y2)) {
                continue;
            }
            // 连线随子节点的出现进度从父节点长出去
            float gx = x1 + (x2 - x1) * appear;
            float gy = y1 + (y2 - y1) * appear;

            boolean unlocked = levelOf(node) > 0 || !node.hasMastery();
            boolean onPath = focusing && this.pathNodes.contains(node);
            int base = onPath ? MasteryPalette.GOLD_BRIGHT
                    : (unlocked ? MasteryPalette.LINE : MasteryPalette.LINE_LOCKED);
            float lineAlpha = appear * (focusing && !onPath ? 0.22F : 1.0F);
            float lineWidth = (float) Math.max(1.0D, (onPath ? 2.6D : 1.4D) * this.zoom);
            RenderUtil.line(buffer, matrix, x1, y1, gx, gy, lineWidth,
                    RenderUtil.withAlpha(base, lineAlpha));
        }

        for (MasteryNode node : this.nodes) {
            float appear = appearOf(node, now);
            if (appear <= 0.01F) {
                continue;
            }
            float scale = Mth.clamp(appear, 0.0F, 1.15F) * (1.0F + node.hoverAnim * 0.14F);
            float radius = (float) (node.radius * this.zoom) * scale;
            float sx = (float) toScreenX(node.graphX);
            float sy = (float) toScreenY(node.graphY);
            if (isOffScreen(sx, sy, radius + 40.0F)) {
                continue;
            }
            float dim = focusing && !this.pathNodes.contains(node) ? 0.35F : 1.0F;
            drawNodeShape(buffer, matrix, node, sx, sy, radius, now, dim);
            drawMaxedAura(buffer, matrix, node, sx, sy, radius, now, dim, maxLevel);
        }

        drawPathFlow(buffer, matrix, now);
        drawSelectionOrbit(buffer, matrix, now);
        RenderUtil.end();
    }

    /**
     * 画背景上的同心刻度环。
     *
     * <p>每一层节点各对应一圈很淡的细环，中心那圈再多两道装饰。
     * 作用是把「由内向外分四层」这件事直接画出来——只有连线的话，
     * 缩得远一点就只剩一团放射状的线，看不出层次。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param now    当前时刻
     */
    private void drawGuideRings(BufferBuilder buffer, Matrix4f matrix, long now) {
        float cx = (float) toScreenX(0.0D);
        float cy = (float) toScreenY(0.0D);
        for (double radius : this.ringRadii) {
            if (radius <= 0.0D) {
                continue;
            }
            float screen = (float) (radius * this.zoom);
            // 缩得太小时几圈会叠在一起糊成一坨，干脆不画
            if (screen < 40.0F || isOffScreen(cx, cy, screen + 40.0F)) {
                continue;
            }
            float width = Math.max(0.6F, 1.2F * (float) this.zoom);
            RenderUtil.ring(buffer, matrix, cx, cy, screen - width, screen + width,
                    RenderUtil.withAlpha(MasteryPalette.GOLD_DARK, 0.30F), 0.0D, Math.PI * 2.0D);
        }

        // 中心的装饰环：一道慢慢转的缺口环，让正中间不至于是一片空地
        float base = (float) (this.sourceRingRadius * this.zoom * 0.42D);
        if (this.sourceRingRadius > 0.0D && base >= 24.0F
                && !isOffScreen(cx, cy, base + 30.0F)) {
            float ringWidth = Math.max(1.0F, 2.0F * (float) this.zoom);
            RenderUtil.ring(buffer, matrix, cx, cy, base - ringWidth, base + ringWidth,
                    RenderUtil.withAlpha(MasteryPalette.GOLD_DARK, 0.45F), 0.0D, Math.PI * 2.0D);
            double spin = now / 5200.0D;
            for (int i = 0; i < CENTER_ARCS; i++) {
                double start = spin + i * (Math.PI * 2.0D / CENTER_ARCS);
                RenderUtil.ring(buffer, matrix, cx, cy, base + ringWidth * 2.0F,
                        base + ringWidth * 4.0F,
                        RenderUtil.withAlpha(MasteryPalette.GOLD, 0.35F),
                        start, Math.PI / CENTER_ARCS);
            }
        }
    }

    /**
     * 给已经满级的节点加一层额外特效。
     *
     * <p>练满是这套系统里最值得炫耀的事，光把数字变成「100 / 100」太安静了。
     * 这里在节点外面套两圈错开呼吸的光环，再让四颗小星点绕着转，
     * 缩到很小的时候只剩一圈淡光，不会把满屏节点糊成一片。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param node   节点
     * @param sx     屏幕 X
     * @param sy     屏幕 Y
     * @param radius 当前半径
     * @param now      当前时刻
     * @param dim      压暗系数
     * @param maxLevel 等级上限，由调用方每帧取一次传进来
     */
    private void drawMaxedAura(BufferBuilder buffer, Matrix4f matrix, MasteryNode node,
                               float sx, float sy, float radius, long now, float dim,
                               int maxLevel) {
        // 缩得很小的时候满屏光环既看不清又白烧性能，直接不画
        if (radius < 4.0F || !node.hasMastery() || levelOf(node) < maxLevel) {
            return;
        }
        float breathe = 0.5F + 0.5F * (float) Math.sin(now / 700.0D + node.graphX * 0.01D);
        RenderUtil.polygonOutline(buffer, matrix,
                RenderUtil.hexagon(sx, sy, radius + 4.0F + breathe * 2.0F),
                Math.max(1.0F, radius * 0.06F),
                RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT, (0.25F + 0.35F * breathe) * dim));
        RenderUtil.polygonOutline(buffer, matrix,
                RenderUtil.hexagon(sx, sy, radius + 9.0F + (1.0F - breathe) * 3.0F),
                Math.max(1.0F, radius * 0.04F),
                RenderUtil.withAlpha(MasteryPalette.GOLD, (0.12F + 0.22F * (1.0F - breathe)) * dim));

        // 节点太小的时候星点会挤成一坨，直接省掉
        if (radius < 8.0F) {
            return;
        }
        double spin = now / 1800.0D + node.graphY * 0.004D;
        float star = Math.max(1.2F, radius * 0.16F);
        for (int i = 0; i < MAXED_STARS; i++) {
            double angle = spin + i * (Math.PI * 2.0D / MAXED_STARS);
            float px = sx + (float) (Math.cos(angle) * (radius + 7.0F));
            float py = sy + (float) (Math.sin(angle) * (radius + 7.0F));
            RenderUtil.polygon(buffer, matrix, RenderUtil.hexagon(px, py, star),
                    RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT, (0.4F + 0.4F * breathe) * dim));
        }
    }

    /**
     * 沿着高亮链路跑的流光。
     *
     * <p>每段连线上跑三颗光点，越靠外的层跑得越慢，看起来像能量从中心往外输送。
     * 只在选中了具体分支时才画——选中总览等于没筛选，全图跑光点只会晃眼。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param now    当前时刻
     */
    private void drawPathFlow(BufferBuilder buffer, Matrix4f matrix, long now) {
        if (this.selected == null || this.selected.type == MasteryNode.Type.INFO) {
            return;
        }
        float dotSize = (float) Math.max(1.6D, 3.2D * this.zoom);
        for (MasteryNode node : this.pathNodes) {
            if (node.parent == null) {
                continue;
            }
            float x1 = (float) toScreenX(node.parent.graphX);
            float y1 = (float) toScreenY(node.parent.graphY);
            float x2 = (float) toScreenX(node.graphX);
            float y2 = (float) toScreenY(node.graphY);
            for (int i = 0; i < FLOW_DOTS; i++) {
                // 每颗点在 0~1 之间循环，彼此错开 1/FLOW_DOTS 个身位
                double phase = (now / 1400.0D + (double) i / FLOW_DOTS) % 1.0D;
                float px = x1 + (x2 - x1) * (float) phase;
                float py = y1 + (y2 - y1) * (float) phase;
                // 两头淡、中间亮，免得光点在节点上突然出现又突然消失
                float fade = (float) Math.sin(phase * Math.PI);
                RenderUtil.polygon(buffer, matrix, RenderUtil.hexagon(px, py, dotSize),
                        RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT, 0.15F + 0.65F * fade));
            }
        }
    }

    /**
     * 选中节点外围绕圈的小光点。
     *
     * <p>比「描一圈静态的边」更容易在一屏几百个节点里被一眼找到，
     * 转速压得很慢，不会变成干扰。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param now    当前时刻
     */
    private void drawSelectionOrbit(BufferBuilder buffer, Matrix4f matrix, long now) {
        if (this.selected == null) {
            return;
        }
        float sx = (float) toScreenX(this.selected.graphX);
        float sy = (float) toScreenY(this.selected.graphY);
        float orbit = (float) (this.selected.radius * this.zoom) + 10.0F;
        if (isOffScreen(sx, sy, orbit + 20.0F)) {
            return;
        }
        float dotSize = (float) Math.max(1.5D, 2.6D * this.zoom);
        double spin = now / 2600.0D;
        for (int i = 0; i < ORBIT_DOTS; i++) {
            double angle = spin + i * (Math.PI * 2.0D / ORBIT_DOTS);
            float px = sx + (float) (Math.cos(angle) * orbit);
            float py = sy + (float) (Math.sin(angle) * orbit);
            // 每颗点自己再呼吸一下，整圈就不会像个死板的齿轮
            float breathe = 0.55F + 0.45F * (float) Math.sin(now / 420.0D + i);
            RenderUtil.polygon(buffer, matrix, RenderUtil.hexagon(px, py, dotSize * breathe),
                    RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT, 0.35F + 0.5F * breathe));
        }
    }

    /**
     * 绘制单个六边形节点：底板、等级填充、描边、选中光环。
     *
     * @param buffer 顶点缓冲
     * @param matrix 变换矩阵
     * @param node   节点
     * @param sx     屏幕 X
     * @param sy     屏幕 Y
     * @param radius 屏幕半径
     * @param now    当前时刻
     */
    private void drawNodeShape(BufferBuilder buffer, Matrix4f matrix, MasteryNode node,
                               float sx, float sy, float radius, long now, float dim) {
        boolean unlocked = !node.hasMastery() || levelOf(node) > 0;
        float[][] hex = RenderUtil.hexagon(sx, sy, radius);

        if (node == this.selected) {
            // 选中：缓慢呼吸的金色外环
            float pulse = 0.5F + 0.5F * (float) Math.sin(now / 420.0D);
            RenderUtil.polygonOutline(buffer, matrix, RenderUtil.hexagon(sx, sy, radius + 7.0F),
                    2.0F, RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT, 0.3F + 0.5F * pulse));
        }

        RenderUtil.polygon(buffer, matrix, RenderUtil.hexagon(sx, sy, radius + 3.0F),
                MasteryPalette.NODE_BACKPLATE);
        RenderUtil.polygon(buffer, matrix, hex,
                unlocked ? MasteryPalette.NODE_FILL : MasteryPalette.NODE_FILL_LOCKED);

        // 等级进度：从底部往上把六边形填满，这是阿尔比恩命运板最标志的表现
        double progress = progressOf(node);
        if (progress > 0.0D) {
            float top = sy + radius - (float) (2.0D * radius * progress);
            RenderUtil.polygonClippedBelow(buffer, matrix, hex, top,
                    RenderUtil.withAlpha(MasteryPalette.PROGRESS, 0.55F * dim));
        }

        int outline;
        if (node == this.selected) {
            outline = MasteryPalette.GOLD_BRIGHT;
        } else if (node.hoverAnim > 0.01F) {
            outline = RenderUtil.lerpColor(
                    unlocked ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK,
                    MasteryPalette.GOLD_BRIGHT, node.hoverAnim);
        } else {
            outline = unlocked ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK;
        }
        RenderUtil.polygonOutline(buffer, matrix, hex, Math.max(1.2F, radius * 0.10F),
                RenderUtil.withAlpha(outline, dim));
    }

    /**
     * 绘制节点上的物品图标。
     *
     * <p>每一层都有图标：武器节点用自己的，上层节点在旗下武器的图标之间轮换，
     * 这样一眼能看出这个分支底下大概是些什么。
     *
     * <p>TACZ 枪械用的是自定义 3D 渲染器，逐个渲染开销不小，所以只画屏幕内、
     * 且屏幕半径够大的节点——缩到很远时本来也糊成一团，画了纯属浪费。
     *
     * @param guiGraphics 绘图上下文
     * @param now         当前时刻
     */
    private void drawNodeIcons(GuiGraphics guiGraphics, long now) {
        if (!MasteryConfig.CLIENT.renderItemIcons.get()) {
            return;
        }
        // 先挑出这一帧真正要画的节点，多了就按离视野中心的远近截断
        List<MasteryNode> candidates = new ArrayList<>();
        for (MasteryNode node : this.nodes) {
            if (Mth.clamp(appearOf(node, now), 0.0F, 1.0F) <= 0.05F) {
                continue;
            }
            float radius = (float) (node.radius * this.zoom) * (1.0F + node.hoverAnim * 0.14F);
            if (radius < ICON_MIN_SCREEN_RADIUS) {
                continue;
            }
            float sx = (float) toScreenX(node.graphX);
            float sy = (float) toScreenY(node.graphY);
            if (isOffScreen(sx, sy, radius + 20.0F)) {
                continue;
            }
            candidates.add(node);
        }
        if (candidates.size() > MAX_ICONS_PER_FRAME) {
            float centerX = (float) baseX();
            float centerY = (float) baseY();
            candidates.sort(Comparator.comparingDouble(node -> {
                double dx = toScreenX(node.graphX) - centerX;
                double dy = toScreenY(node.graphY) - centerY;
                return dx * dx + dy * dy;
            }));
            candidates = candidates.subList(0, MAX_ICONS_PER_FRAME);
        }

        for (MasteryNode node : candidates) {
            ItemStack icon = node.iconAt(now);
            if (icon.isEmpty()) {
                continue;
            }
            float appear = Mth.clamp(appearOf(node, now), 0.0F, 1.0F);
            float radius = (float) (node.radius * this.zoom) * (1.0F + node.hoverAnim * 0.14F);
            float sx = (float) toScreenX(node.graphX);
            float sy = (float) toScreenY(node.graphY);
            // 物品图标固定 16x16，按节点大小放大后居中
            float scale = radius / 16.0F * 1.9F * appear;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(sx - 8.0F * scale, sy - 8.0F * scale, 0.0F);
            guiGraphics.pose().scale(scale, scale, 1.0F);
            guiGraphics.renderItem(icon, 0, 0);
            guiGraphics.pose().popPose();
        }
    }

    /**
     * 绘制节点上的等级数字与名称。
     *
     * <p>文字跟着板子一起缩放，而不是固定屏幕字号——否则缩远之后节点只有几像素，
     * 字却还是原来那么大，整屏全是字。
     *
     * @param guiGraphics 绘图上下文
     * @param now         当前时刻
     */
    private void drawNodeOverlays(GuiGraphics guiGraphics, long now) {
        float labelScale = Mth.clamp((float) this.zoom, LABEL_SCALE_MIN, LABEL_SCALE_MAX);
        boolean showGroupLabels = (float) this.zoom >= 0.11F;
        boolean showLevelDetail = this.zoom >= LEVEL_DETAIL_ZOOM;
        boolean showWeaponNames = this.zoom >= NAME_ZOOM;
        int maxLevel = ClientMasteryCache.snapshot().maxLevel;

        guiGraphics.pose().pushPose();
        // 抬到物品渲染（z=150）之上
        guiGraphics.pose().translate(0.0F, 0.0F, 320.0F);

        for (MasteryNode node : this.nodes) {
            if (Mth.clamp(appearOf(node, now), 0.0F, 1.0F) < 0.9F) {
                continue;
            }
            float radius = (float) (node.radius * this.zoom);
            float sx = (float) toScreenX(node.graphX);
            float sy = (float) toScreenY(node.graphY);
            if (isOffScreen(sx, sy, radius + 46.0F)) {
                continue;
            }

            boolean focused = node == this.hovered || node == this.selected;
            switch (node.type) {
                case INFO -> drawScaledLabel(guiGraphics, node.displayName().getString(),
                        sx, sy - 4.0F * labelScale, labelScale, MasteryPalette.GOLD_BRIGHT, 170);
                case SOURCE, TYPE -> drawNodeText(guiGraphics, node, sx, sy, radius, labelScale,
                        showGroupLabels || focused, showLevelDetail, maxLevel, MasteryPalette.TEXT);
                case WEAPON -> drawNodeText(guiGraphics, node, sx, sy, radius, labelScale,
                        showWeaponNames || focused, showLevelDetail, maxLevel,
                        focused ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT);
                default -> {
                }
            }
        }
        guiGraphics.pose().popPose();
    }

    /**
     * 画节点的等级铭牌与名称。
     *
     * <p>等级<b>做成一块贴在六边形底边上的铭牌</b>，而不是飘在下面的一行字——
     * 之前那样在一堆节点里根本找不到是哪个数配哪个图标。铭牌压住节点边缘，
     * 视觉上就明确属于这个节点了。
     *
     * <p>缩放够大时铭牌里显示「等级 / 上限」，缩小时只留等级数字。名称排在铭牌下面。
     *
     * @param guiGraphics 绘图上下文
     * @param node        节点
     * @param sx          屏幕 X
     * @param sy          屏幕 Y
     * @param radius      屏幕半径
     * @param scale       字号缩放
     * @param showName    是否显示名称
     * @param showDetail  铭牌里是否带上限、是否显示分支合计
     * @param maxLevel    等级上限
     * @param nameColor   名称颜色
     */
    private void drawNodeText(GuiGraphics guiGraphics, MasteryNode node, float sx, float sy,
                              float radius, float scale, boolean showName, boolean showDetail,
                              int maxLevel, int nameColor) {
        float below = sy + radius + 3.0F;
        int level = node.hasMastery() ? levelOf(node) : 0;

        if (node.hasMastery() && level > 0) {
            below = drawLevelPlaque(guiGraphics, sx, sy, radius, scale,
                    showDetail ? level + " / " + maxLevel : String.valueOf(level));
        }
        // 来源 / 类型节点在放大到能看清时，铭牌下面再补一行「分支等级合计」。
        // 铭牌上的数字是这一层自己的等级，看不出底下几十件武器练成什么样，
        // 合计才是这一支的实际进度：左边是当前总级数，右边是全部满级时的总级数。
        if (showDetail && (node.type == MasteryNode.Type.SOURCE || node.type == MasteryNode.Type.TYPE)) {
            Aggregate aggregate = aggregateOf(node, maxLevel);
            if (aggregate != null) {
                float sumScale = scale * SUM_LABEL_SCALE;
                drawScaledLabel(guiGraphics,
                        Component.translatable("gui.albionmastery.node.level_sum",
                                aggregate.levelSum(), aggregate.maxSum()).getString(),
                        sx, below + 1.0F, sumScale, MasteryPalette.TEXT_DIM, 170);
                below += (this.font.lineHeight + 1) * sumScale;
            }
        }
        if (showName) {
            boolean focused = node == this.hovered || node == this.selected;
            drawScaledLabel(guiGraphics, node.displayName().getString(), sx, below + 2.0F, scale,
                    nameColor, labelBudget(node, scale, focused));
        }
    }

    /**
     * 在节点底边画一块等级铭牌。
     *
     * @param guiGraphics 绘图上下文
     * @param sx          节点屏幕 X
     * @param sy          节点屏幕 Y
     * @param radius      节点屏幕半径
     * @param scale       字号缩放
     * @param text        铭牌文字
     * @return 铭牌底边的屏幕 Y，供名称接着往下排
     */
    private float drawLevelPlaque(GuiGraphics guiGraphics, float sx, float sy,
                                  float radius, float scale, String text) {
        int textWidth = this.font.width(text);
        int boxWidth = textWidth + 8;
        int boxHeight = 11;

        guiGraphics.pose().pushPose();
        // 铭牌骑在节点底边上，压住一点点边框
        guiGraphics.pose().translate(sx, sy + radius - 2.0F * scale, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);

        int left = -boxWidth / 2;
        guiGraphics.fill(left - 1, -1, left + boxWidth + 1, boxHeight + 1, 0xFF0A0806);
        guiGraphics.fill(left, 0, left + boxWidth, boxHeight, 0xFF1E1810);
        guiGraphics.fill(left, 0, left + boxWidth, 1, MasteryPalette.GOLD);
        guiGraphics.fill(left, boxHeight - 1, left + boxWidth, boxHeight, MasteryPalette.GOLD_DARK);
        guiGraphics.drawCenteredString(this.font, text, 0, 2, MasteryPalette.GOLD_BRIGHT);

        guiGraphics.pose().popPose();
        return sy + radius - 2.0F * scale + (boxHeight + 1) * scale;
    }

    /**
     * 画一段跟随缩放的居中文字。
     *
     * @param guiGraphics 绘图上下文
     * @param text        文字
     * @param sx          屏幕 X
     * @param sy          屏幕 Y
     * @param scale       字号缩放
     * @param color       颜色
     */
    private void drawScaledLabel(GuiGraphics guiGraphics, String text,
                                 float sx, float sy, float scale, int color, int budget) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(sx, sy, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawCenteredString(this.font, trimToWidth(text, budget), 0, 0, color);
        guiGraphics.pose().popPose();
    }

    /**
     * 算一个节点的名称最多能占多宽。
     *
     * <p>外圈相邻节点的圆心间距是固定的（节点直径 × 间距系数），名字超过这个宽度就会和
     * 隔壁的糊在一起。所以按实际间距换算出预算再截断，而不是所有节点都用同一个固定值。
     * 文字是缩放绘制的，预算要除回字号缩放才是「字体坐标系」下的宽度。
     *
     * <p>被悬停或选中的节点画在最上层，可以放宽一些，方便看全名。
     *
     * @param node    节点
     * @param scale   字号缩放
     * @param focused 是否悬停或选中
     * @return 最大宽度
     */
    private int labelBudget(MasteryNode node, float scale, boolean focused) {
        if (focused) {
            return 170;
        }
        // 相邻节点的间距，和布局里用的间距系数保持一致
        double spacing = node.radius * 2.0D * 1.35D * this.zoom;
        if (node.type != MasteryNode.Type.WEAPON) {
            // 内层节点稀疏得多，给宽一点
            spacing *= 3.0D;
        }
        return (int) Mth.clamp(spacing / Math.max(0.1F, scale), 24.0D, 170.0D);
    }


    /**
     * 绘制左上角标题与操作提示。
     *
     * @param guiGraphics 绘图上下文
     */
    private void drawHeader(GuiGraphics guiGraphics) {
        // 抬到物品图标之上。物品是用 ItemRenderer 画的，它内部会把深度推到 150 左右，
        // 比普通 fill 高，所以顶栏哪怕画在后面也会被靠近顶边的武器图标盖住
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 600.0F);

        // 一条贯穿顶部的窄条：标题、学习点、总进度挤在同一行，
        // 比原来「标题一行、提示一行」省掉一半高度，也不会散在角落里
        guiGraphics.fill(0, 0, this.width, HEADER_HEIGHT, MasteryPalette.PANEL);
        guiGraphics.fill(0, HEADER_HEIGHT - 1, this.width, HEADER_HEIGHT, MasteryPalette.EDGE);

        int textY = (HEADER_HEIGHT - this.font.lineHeight) / 2 + 1;
        int cursor = 10;
        guiGraphics.drawString(this.font, this.title, cursor, textY,
                MasteryPalette.GOLD_BRIGHT, false);
        cursor += this.font.width(this.title) + 10;

        double study = ClientMasteryCache.data().getStudyMultiplier();
        if (Math.abs(study - 1.0D) > 1.0E-6D) {
            // 只在不是 1 倍时才占位置：默认值挂在标题旁边纯属噪音
            Component label = Component.translatable("gui.albionmastery.header.study",
                    formatMultiplier(study));
            cursor = drawHeaderChip(guiGraphics, label, cursor, textY,
                    study > 1.0D ? MasteryPalette.GOLD : MasteryPalette.WARN);
        }

        Progress progress = frameProgress();
        int maxLevel = ClientMasteryCache.snapshot().maxLevel;
        long ceiling = (long) progress.total() * Math.max(1, maxLevel);
        double ratio = ceiling <= 0L ? 0.0D : (double) progress.levelSum() / ceiling;
        drawHeaderChip(guiGraphics, Component.translatable("gui.albionmastery.header.progress",
                progress.levelSum(), ceiling,
                String.format(Locale.ROOT, "%.1f%%", ratio * 100.0D)),
                cursor, textY, MasteryPalette.TEXT);

        // 提示压到顶栏下面，字号 0.75，够看又不抢地方
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(10.0F, HEADER_HEIGHT + 4.0F, 0.0F);
        guiGraphics.pose().scale(0.75F, 0.75F, 1.0F);
        guiGraphics.drawString(this.font, Component.translatable("gui.albionmastery.hint"),
                0, 0, MasteryPalette.TEXT_DIM, false);
        guiGraphics.pose().popPose();

        if (this.sources.isEmpty()) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.albionmastery.no_weapon"),
                    this.width / 2, this.height / 2 - 4, MasteryPalette.TEXT_DIM);
        } else if (!ClientMasteryCache.isSynced()) {
            guiGraphics.drawString(this.font, Component.translatable("gui.albionmastery.not_synced"),
                    10, HEADER_HEIGHT + 14, MasteryPalette.WARN, false);
        }
        guiGraphics.pose().popPose();
    }

    /**
     * 在顶栏上画一小段带分隔点的信息。
     *
     * @param guiGraphics 绘图上下文
     * @param text        文本
     * @param x           起始 X
     * @param y           文字基线 Y
     * @param color       文字颜色
     * @return 下一段的起始 X
     */
    private int drawHeaderChip(GuiGraphics guiGraphics, Component text, int x, int y, int color) {
        guiGraphics.drawString(this.font, "\u00B7", x, y, MasteryPalette.GOLD_DARK, false);
        int textX = x + this.font.width("\u00B7") + 6;
        guiGraphics.drawString(this.font, text, textX, y, color, false);
        return textX + this.font.width(text) + 10;
    }

    // ======================================================================
    // 详情面板
    // ======================================================================

    /**
     * 绘制右侧详情面板。
     *
     * @param guiGraphics 绘图上下文
     */
    private void drawPanel(GuiGraphics guiGraphics) {
        if (this.selected == null) {
            this.panelScrollable = false;
            this.specHits.clear();
            return;
        }
        int x = PANEL_PAD;
        int width = PANEL_WIDTH - PANEL_PAD * 2;
        int y = 56;

        // 换了节点就把滚动拉回顶部，不然点开新面板是从上一个面板停的位置开始看
        if (this.selected != this.panelNode) {
            this.panelNode = this.selected;
            this.panelScroll = 0.0D;
        }

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 400.0F);

        // 底板高度取上一帧内容实测到的底边，比给每种面板硬编码一个高度靠谱：
        // 换节点时会有一帧的滞后，肉眼看不出来，但内容再怎么变都不会露到底板外面
        int top = y - PANEL_PAD;
        int bottom = Math.min(this.height - 34, Math.max(y + 40, this.panelBottom + PANEL_PAD));
        guiGraphics.fill(0, top, PANEL_WIDTH, bottom, MasteryPalette.PANEL);
        guiGraphics.fill(PANEL_WIDTH - 2, top, PANEL_WIDTH, bottom, MasteryPalette.EDGE);
        guiGraphics.fill(0, top, PANEL_WIDTH, top + 1, MasteryPalette.EDGE);
        guiGraphics.fill(0, bottom - 1, PANEL_WIDTH, bottom, MasteryPalette.EDGE);

        // 总览面板内容比一屏还高（尤其是 GUI 缩放调大时），以前是直接画出去、
        // 字压在地图上甚至掉到屏幕外面。现在超出的部分裁掉，用滚轮看。
        int viewport = Math.max(1, bottom - PANEL_PAD - y);
        int content = Math.max(0, this.panelBottom - y);
        double maxScroll = Math.max(0.0D, content - viewport);
        this.panelScroll = Mth.clamp(this.panelScroll, 0.0D, maxScroll);
        this.panelScrollable = maxScroll > 0.0D;

        // 点击区每帧重建：面板内容会随等级、解锁状态变化，缓存反而容易点错
        this.specHits.clear();

        // enableScissor 用的是未经 pose 变换的屏幕坐标，所以要在下面那次 translate 之前调用
        guiGraphics.enableScissor(0, top + 1, PANEL_WIDTH, bottom - 1);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, (float) -this.panelScroll, 0.0F);
        switch (this.selected.type) {
            case INFO -> this.panelBottom = drawInfoPanel(guiGraphics, x, y, width);
            case SOURCE -> this.panelBottom = drawSourcePanel(guiGraphics, x, y, width);
            case TYPE -> this.panelBottom = drawTypePanel(guiGraphics, x, y, width);
            case WEAPON -> this.panelBottom = drawWeaponPanel(guiGraphics, x, y, width);
            default -> {
            }
        }
        guiGraphics.pose().popPose();
        guiGraphics.disableScissor();

        if (this.panelScrollable) {
            drawPanelScrollbar(guiGraphics, top, bottom, viewport, content);
        }
        guiGraphics.pose().popPose();
    }

    /**
     * 画详情面板右侧的细滚动条。
     *
     * <p>只在内容超出时出现：没超出还画一根空轨道，反而让人以为下面还有东西。
     *
     * @param guiGraphics 绘图上下文
     * @param top         面板顶边
     * @param bottom      面板底边
     * @param viewport    可视高度
     * @param content     内容总高度
     */
    private void drawPanelScrollbar(GuiGraphics guiGraphics, int top, int bottom,
                                    int viewport, int content) {
        int trackTop = top + 3;
        int trackBottom = bottom - 3;
        int trackHeight = Math.max(8, trackBottom - trackTop);
        // 滑块长度按「可视占内容的比例」给，最短 12 像素，免得内容很长时缩成一个点
        int thumbHeight = Mth.clamp((int) ((long) trackHeight * viewport / Math.max(1, content)),
                12, trackHeight);
        double maxScroll = Math.max(1.0D, content - viewport);
        int thumbTop = trackTop + (int) Math.round((trackHeight - thumbHeight)
                * Mth.clamp(this.panelScroll / maxScroll, 0.0D, 1.0D));
        int left = PANEL_WIDTH - 6;
        guiGraphics.fill(left, trackTop, left + 2, trackBottom, 0x30000000 | (MasteryPalette.EDGE & 0xFFFFFF));
        guiGraphics.fill(left, thumbTop, left + 2, thumbTop + thumbHeight, MasteryPalette.GOLD);
    }

    private int drawInfoPanel(GuiGraphics guiGraphics, int x, int y, int width) {
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        Progress progress = frameProgress();

        int cursor = drawTitle(guiGraphics, Component.translatable("gui.albionmastery.panel.info_title"),
                null, x, y, width);

        // 中心是最显眼的位置，先给总进度，再讲规则
        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.info_progress"),
                x, cursor, width);
        double ratio = progress.total() <= 0 ? 0.0D : (double) progress.unlocked() / progress.total();
        guiGraphics.fill(x - 1, cursor - 1, x + width + 1, cursor + 8, MasteryPalette.EDGE);
        guiGraphics.fill(x, cursor, x + width, cursor + 7, 0xFF17120A);
        int filled = (int) Math.round(width * Mth.clamp(ratio, 0.0D, 1.0D));
        if (filled > 0) {
            guiGraphics.fill(x, cursor, x + filled, cursor + 7, MasteryPalette.PROGRESS);
        }
        cursor += 12;
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_unlocked"),
                Component.literal(progress.unlocked() + " / " + progress.total()), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_maxed"),
                Component.literal(String.valueOf(progress.maxed())), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_level_sum"),
                Component.literal(String.valueOf(progress.levelSum())), x, cursor, width);
        cursor = drawStudyRow(guiGraphics, x, cursor, width) + 6;

        cursor = drawWrapped(guiGraphics, Component.translatable("gui.albionmastery.panel.info_layers"),
                x, cursor, width, MasteryPalette.TEXT_DIM) + 8;

        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.info_rules"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_max_level"),
                Component.literal(String.valueOf(snapshot.maxLevel)), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_overall"),
                Component.literal(perLevel(snapshot.overallDamagePerLevel)), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_type"),
                Component.literal(perLevel(snapshot.typeDamagePerLevel)), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_self"),
                Component.literal(perLevel(snapshot.weaponDamagePerLevel)), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_cross"),
                Component.literal(perLevel(snapshot.weaponCrossDamagePerLevel)), x, cursor, width) + 6;

        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.info_grind"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grind_weapon"),
                Component.literal("×1"), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grind_type"),
                Component.literal("×" + trimNumber(snapshot.typeXpMultiplier)), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grind_overall"),
                Component.literal("×" + trimNumber(snapshot.overallXpMultiplier)), x, cursor, width) + 6;

        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.info_dimension"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_dim_nether"),
                Component.literal("×" + trimNumber(snapshot.netherXpMultiplier))
                        .withStyle(ChatFormatting.GOLD), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_dim_end"),
                Component.literal("×" + trimNumber(snapshot.endXpMultiplier))
                        .withStyle(ChatFormatting.GOLD), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_dim_modded"),
                Component.literal("×" + trimNumber(snapshot.moddedDimensionXpMultiplier))
                        .withStyle(ChatFormatting.GOLD), x, cursor, width) + 8;

        return drawWrapped(guiGraphics, Component.translatable("gui.albionmastery.panel.info_usage"),
                x, cursor, width, MasteryPalette.TEXT_DIM);
    }

    private int drawSourcePanel(GuiGraphics guiGraphics, int x, int y, int width) {
        WeaponSource source = this.selected == null ? null : this.selected.source;
        if (source == null) {
            return y;
        }
        MasteryData data = ClientMasteryCache.data();
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        int level = data.getOverallLevel(source.overallKey());

        int cursor = drawTitle(guiGraphics, source.displayName(),
                Component.translatable("gui.albionmastery.panel.source_tag"), x, y, width);
        cursor = drawLevelBlock(guiGraphics, x, cursor, width, level,
                data.getOverallXp(source.overallKey()), snapshot.overallCurve(), snapshot.maxLevel) + 8;

        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.section_effect"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grants_all"),
                Component.literal(formatPercent(level * snapshot.overallDamagePerLevel))
                        .withStyle(ChatFormatting.GOLD), x, cursor, width) + 6;

        // 只有一个类型的来源不画类型节点（比如近战附属），它的等级就并到这里显示
        if (source.types().size() == 1) {
            WeaponTypeGroup only = source.types().get(0);
            int typeLevel = data.getTypeLevel(only.key());
            cursor = drawRow(guiGraphics, only.displayName(),
                    Component.literal("Lv." + typeLevel), x, cursor, width);
            cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grants_type"),
                    Component.literal(formatPercent(typeLevel * snapshot.typeDamagePerLevel))
                            .withStyle(ChatFormatting.GOLD), x, cursor, width);
        }
        cursor += 6;

        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.section_progress"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_type_count"),
                Component.literal(String.valueOf(source.types().size())), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_weapon_count"),
                Component.literal(String.valueOf(source.weaponCount())), x, cursor, width);

        if (ClientMasteryCache.snapshot().enableSpecialization) {
            cursor = drawSection(guiGraphics,
                    Component.translatable("gui.albionmastery.panel.section_spec"),
                    x, cursor + 6, width);
            cursor = drawWrapped(guiGraphics,
                    Component.translatable("gui.albionmastery.panel.spec_in_type"),
                    x, cursor, width, MasteryPalette.TEXT_DIM);
        }
        return cursor;
    }

    /**
     * 画「学习点」那一行。
     *
     * <p>默认的 1 倍只画一行普通的灰字；被管理员调过之后整行压一层底色、数值加粗成金色，
     * 让玩家一眼看见自己现在是几倍——这东西直接决定练级速度，藏在灰字里等于没告诉他。
     *
     * @param guiGraphics 绘图上下文
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 结束后的 Y
     */
    private int drawStudyRow(GuiGraphics guiGraphics, int x, int y, int width) {
        double multiplier = ClientMasteryCache.data().getStudyMultiplier();
        boolean boosted = Math.abs(multiplier - 1.0D) > 1.0E-6D;
        Component value = Component.literal("\u00D7" + formatMultiplier(multiplier));

        if (boosted) {
            // 加成用金底，惩罚（小于 1）用橙底，两种情况玩家都该注意到
            int tint = multiplier > 1.0D ? 0x30E8C25A : 0x30E08A3C;
            guiGraphics.fill(x - 3, y - 2, x + width, y + LINE + 1, tint);
        }
        int valueWidth = this.font.width(value);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.albionmastery.panel.row_study"),
                x, y, MasteryPalette.TEXT_DIM, false);
        guiGraphics.drawString(this.font, value, x + width - valueWidth, y,
                boosted ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT, false);
        return y + LINE + 2;
    }

    /**
     * 把倍率写成好读的样子：整数不带小数点。
     *
     * @param multiplier 倍率
     * @return 文本
     */
    private static String formatMultiplier(double multiplier) {
        if (multiplier == Math.rint(multiplier)) {
            return String.valueOf((long) multiplier);
        }
        return String.format(Locale.ROOT, "%.2f", multiplier)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**
     * 画来源面板底部的「专精特化」区块。
     *
     * <p>没解锁时只留一行提示，解锁后把该来源的全部流派列出来，当前选中的高亮，
     * 点一下就发请求切换。切换的合法性（解锁、冷却）由服务端说了算，
     * 这里只负责把选项摆出来。
     *
     * @param guiGraphics 绘图上下文
     * @param overallKey  来源的总体专精键
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 结束后的 Y
     */
    private int drawSpecializationBlock(GuiGraphics guiGraphics, String typeKey,
                                        int x, int y, int width) {
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        if (!snapshot.enableSpecialization) {
            return y;
        }
        MasteryData data = ClientMasteryCache.data();
        this.specTypeKey = typeKey;
        int cursor = drawSection(guiGraphics,
                Component.translatable("gui.albionmastery.panel.section_spec"), x, y, width);

        if (!SpecializationEffects.isUnlocked(data, typeKey, snapshot)) {
            return drawSpecLocked(guiGraphics, data, typeKey, snapshot, x, cursor, width);
        }

        String providerId = WeaponKeys.providerOf(typeKey);
        String current = data.getSpecialization(typeKey);
        for (Specialization spec : Specialization.forSource(providerId == null ? "" : providerId)) {
            boolean selected = spec.key().equals(current) || (current == null && spec.isStandard());
            cursor = drawSpecCard(guiGraphics, spec, selected, x, cursor, width) + SPEC_CARD_GAP;
        }
        return cursor;
    }

    /**
     * 画未解锁时的提示：一句话 + 一根进度条，让玩家知道还差多少。
     *
     * <p>比干写一行「满级后解锁」有用得多——玩家真正想知道的是「还要多久」。
     *
     * @param guiGraphics 绘图上下文
     * @param data        专精数据
     * @param overallKey  来源的总体专精键
     * @param snapshot    配置快照
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 结束后的 Y
     */
    private int drawSpecLocked(GuiGraphics guiGraphics, MasteryData data, String typeKey,
                               MasteryConfig.Snapshot snapshot, int x, int y, int width) {
        int max = Math.max(1, snapshot.maxLevel);
        String providerId = WeaponKeys.providerOf(typeKey);
        int overallLevel = providerId == null ? 0
                : data.getOverallLevel(WeaponKeys.overallKey(providerId));
        int typeLevel = data.getTypeLevel(typeKey);

        int cursor = drawWrapped(guiGraphics,
                Component.translatable("gui.albionmastery.panel.spec_locked"),
                x, y, width, MasteryPalette.TEXT_DIM) + 2;
        // 两条都得满：分别画一根条，缺哪条一眼就看出来
        cursor = drawSpecRequirement(guiGraphics, "gui.albionmastery.panel.spec_need_overall",
                overallLevel, max, x, cursor, width);
        return drawSpecRequirement(guiGraphics, "gui.albionmastery.panel.spec_need_type",
                typeLevel, max, x, cursor, width);
    }

    /**
     * 画一条解锁条件：一行「名称 + 当前/上限」，底下一根进度条。
     *
     * @param guiGraphics 绘图上下文
     * @param labelKey    条件名称的语言键
     * @param level       当前等级
     * @param max         等级上限
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 结束后的 Y
     */
    private int drawSpecRequirement(GuiGraphics guiGraphics, String labelKey, int level, int max,
                                    int x, int y, int width) {
        boolean done = level >= max;
        int cursor = drawRow(guiGraphics, Component.translatable(labelKey),
                Component.literal(level + " / " + max)
                        .withStyle(done ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                x, y, width);
        guiGraphics.fill(x, cursor, x + width, cursor + 4, 0xFF17120A);
        int filled = (int) Math.round(width * Mth.clamp((double) level / max, 0.0D, 1.0D));
        if (filled > 0) {
            guiGraphics.fill(x, cursor, x + filled, cursor + 4,
                    done ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.PROGRESS);
        }
        return cursor + 8;
    }

    /**
     * 画一张流派卡片：标题一行，得失各一行。
     *
     * <p>三种状态用三层视觉区分，而不是只靠文字颜色：
     * 选中的有金色左条 + 淡金底 + 右上角「使用中」；鼠标悬停的压一层更淡的底；
     * 其余什么都不画。这样一眼扫过去能立刻分清「哪个在用」和「鼠标在哪」。
     *
     * <p>底色必须在文字之前画，所以整张卡的高度要先算出来——
     * 文字换几行取决于字体和语言，得先问 {@code font.split}。
     *
     * @param guiGraphics 绘图上下文
     * @param spec        流派
     * @param selected    是否是当前选中的
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 卡片底边的 Y
     */
    private int drawSpecCard(GuiGraphics guiGraphics, Specialization spec, boolean selected,
                             int x, int y, int width) {
        int textLeft = x + SPEC_TEXT_INSET;
        int textWidth = width - SPEC_TEXT_INSET;

        // 先量高度：制式只有一行说明，其余流派是「得」「失」两行
        Component gain = Component.translatable(spec.gainKey());
        Component cost = spec.isStandard() ? null : Component.translatable(spec.costKey());
        int lines = this.font.split(gain, textWidth - SPEC_BULLET_WIDTH).size();
        if (cost != null) {
            lines += this.font.split(cost, textWidth - SPEC_BULLET_WIDTH).size();
        }
        int top = y;
        int bottom = top + LINE + 2 + lines * LINE + 3;

        boolean hovered = isInPanel(this.mouseXNow)
                && this.mouseYNow + this.panelScroll >= top
                && this.mouseYNow + this.panelScroll < bottom;

        if (selected) {
            guiGraphics.fill(x - 3, top, x + width, bottom, 0x30E8C25A);
            guiGraphics.fill(x - 3, top, x - 1, bottom, MasteryPalette.GOLD);
        } else if (hovered) {
            guiGraphics.fill(x - 3, top, x + width, bottom, 0x1AFFFFFF);
            guiGraphics.fill(x - 3, top, x - 1, bottom, MasteryPalette.GOLD_DARK);
        }

        Component name = Component.translatable(spec.nameKey());
        guiGraphics.drawString(this.font, name, textLeft, top + 2,
                selected ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT, false);
        if (selected) {
            // 「使用中」右对齐，和标题同一行，不占额外高度
            Component tag = Component.translatable("gui.albionmastery.panel.spec_active");
            int tagWidth = this.font.width(tag);
            guiGraphics.drawString(this.font, tag, x + width - tagWidth, top + 2,
                    MasteryPalette.GOLD, false);
        }

        this.specHits.add(new SpecHit(spec, this.specTypeKey, top, bottom - top));

        int cursor = top + LINE + 2;
        cursor = drawSpecLine(guiGraphics, "+", gain, MasteryPalette.PROGRESS,
                textLeft, cursor, textWidth);
        if (cost != null) {
            cursor = drawSpecLine(guiGraphics, "\u2212", cost, MasteryPalette.WARN,
                    textLeft, cursor, textWidth);
        }
        return Math.max(bottom, cursor);
    }

    /**
     * 画一条「得」或「失」：行首一个符号，后面跟自动换行的说明。
     *
     * <p>符号单独画、说明整体缩进，换行之后第二行会和第一行的文字左边缘对齐，
     * 不会顶到符号下面去。
     *
     * @param guiGraphics 绘图上下文
     * @param bullet      行首符号
     * @param text        说明文字
     * @param bulletColor 符号颜色
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 结束后的 Y
     */
    private int drawSpecLine(GuiGraphics guiGraphics, String bullet, Component text,
                             int bulletColor, int x, int y, int width) {
        guiGraphics.drawString(this.font, bullet, x, y, bulletColor, false);
        return drawWrapped(guiGraphics, text, x + SPEC_BULLET_WIDTH, y,
                width - SPEC_BULLET_WIDTH, MasteryPalette.TEXT_DIM);
    }

    private int drawTypePanel(GuiGraphics guiGraphics, int x, int y, int width) {
        WeaponTypeGroup group = this.selected == null ? null : this.selected.typeGroup;
        if (group == null) {
            return y;
        }
        MasteryData data = ClientMasteryCache.data();
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        int level = data.getTypeLevel(group.key());

        int cursor = drawTitle(guiGraphics, group.displayName(),
                Component.translatable("gui.albionmastery.panel.type_tag"), x, y, width);
        cursor = drawLevelBlock(guiGraphics, x, cursor, width, level,
                data.getTypeXp(group.key()), snapshot.typeCurve(), snapshot.maxLevel) + 8;

        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.section_effect"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grants_type"),
                Component.literal(formatPercent(level * snapshot.typeDamagePerLevel))
                        .withStyle(ChatFormatting.GOLD), x, cursor, width);

        int unlocked = 0;
        int totalLevels = 0;
        for (WeaponEntry entry : group.weapons()) {
            int weaponLevel = data.getWeaponLevel(entry.key());
            if (weaponLevel > 0) {
                unlocked++;
            }
            totalLevels += weaponLevel;
        }
        cursor += 6;
        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.section_progress"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_unlocked"),
                Component.literal(unlocked + " / " + group.weapons().size()), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_pool"),
                Component.literal(totalLevels + "  "
                        + formatPercent(totalLevels * snapshot.weaponCrossDamagePerLevel)),
                x, cursor, width);

        // 流派挂在类型这一层：同样是枪，步枪和霰弹枪该走的路子完全不同
        return drawSpecializationBlock(guiGraphics, group.key(), x, cursor + 6, width);
    }

    private int drawWeaponPanel(GuiGraphics guiGraphics, int x, int y, int width) {
        WeaponEntry weapon = this.selected == null ? null : this.selected.weapon;
        if (weapon == null) {
            return y;
        }
        MasteryData data = ClientMasteryCache.data();
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        int level = data.getWeaponLevel(weapon.key());

        ResolvedWeapon resolved = new ResolvedWeapon(
                weapon.overallKey(), weapon.weaponId(), weapon.type(),
                weapon.key(), weapon.typeKey(), weapon.overallKey());
        MasteryHelper.Bonus bonus = MasteryHelper.computeBonus(data, resolved, snapshot);

        int cursor = drawTitle(guiGraphics, weapon.displayName(),
                ClientWeaponRegistry.typeName(weapon.typeKey()), x, y, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_pack"),
                Component.literal(weapon.packNamespace()), x, cursor, width) + 6;

        cursor = drawLevelBlock(guiGraphics, x, cursor, width, level,
                data.getWeaponXp(weapon.key()), snapshot.weaponCurve(), snapshot.maxLevel) + 10;

        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.section_bonus"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_total"),
                Component.literal(formatPercent(bonus.total())).withStyle(ChatFormatting.GOLD),
                x, cursor, width);
        if (bonus.capped()) {
            cursor = drawWrapped(guiGraphics, Component.translatable("gui.albionmastery.panel.capped"),
                    x, cursor, width, MasteryPalette.WARN);
        }
        cursor += 4;
        cursor = drawRow(guiGraphics,
                Component.translatable("gui.albionmastery.panel.row_from_overall", bonus.overallLevel()),
                Component.literal(formatPercent(bonus.fromOverall())), x, cursor, width);
        cursor = drawRow(guiGraphics,
                Component.translatable("gui.albionmastery.panel.row_from_type", bonus.typeLevel()),
                Component.literal(formatPercent(bonus.fromType())), x, cursor, width);
        cursor = drawRow(guiGraphics,
                Component.translatable("gui.albionmastery.panel.row_from_self", bonus.weaponLevel()),
                Component.literal(formatPercent(bonus.fromSelf())), x, cursor, width);
        cursor = drawRow(guiGraphics,
                Component.translatable("gui.albionmastery.panel.row_from_others", bonus.typeLevelSum()),
                Component.literal(formatPercent(bonus.fromOthers())), x, cursor, width) + 8;

        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_contribution"),
                Component.literal(formatPercent(level * snapshot.weaponCrossDamagePerLevel)),
                x, cursor, width);

        return drawSpecEffects(guiGraphics, weapon.typeKey(), data, snapshot, x, cursor + 6, width);
    }

    /**
     * 在武器面板里列出当前特化对这把武器的实际影响。
     *
     * <p>只列真正变了的那几项：一个流派通常只动两三个维度，把八项全列出来、
     * 其中六项写着「×1」，等于让人自己去里面找哪个是重点。
     *
     * @param guiGraphics 绘图上下文
     * @param typeKey     这把武器所属类型的键
     * @param data        专精数据
     * @param snapshot    配置快照
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 结束后的 Y
     */
    private int drawSpecEffects(GuiGraphics guiGraphics, String typeKey, MasteryData data,
                                MasteryConfig.Snapshot snapshot, int x, int y, int width) {
        if (!snapshot.enableSpecialization) {
            return y;
        }
        Specialization spec = Specialization.byKey(data.getSpecialization(typeKey));
        if (spec == null || spec.isStandard()) {
            return y;
        }
        SpecializationValues values = SpecializationEffects.forData(data, typeKey, snapshot);

        int cursor = drawSection(guiGraphics,
                Component.translatable("gui.albionmastery.panel.section_spec"), x, y, width);
        cursor = drawRow(guiGraphics, Component.translatable(spec.nameKey()),
                Component.translatable("gui.albionmastery.panel.spec_active")
                        .withStyle(ChatFormatting.GOLD), x, cursor, width);

        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_damage",
                values.damage(), false, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_magazine",
                values.magazine(), false, x, cursor, width);
        // 换弹和攻速是「时间」，小于 1 才是变好，所以判断方向要反过来
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_reload",
                values.reloadTime(), true, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_attack_interval",
                values.attackInterval(), true, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_summoned",
                values.summonedDamage(), false, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_direct",
                values.directDamage(), false, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_vs_player",
                values.vsPlayerDamage(), false, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_vs_mob",
                values.vsMobDamage(), false, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_armored",
                values.armoredDamage(), false, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_unarmored",
                values.unarmoredDamage(), false, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_headshot",
                values.headShotDamage(), false, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_bodyshot",
                values.bodyShotDamage(), false, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_execute",
                values.executeDamage(), false, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_fresh",
                values.freshDamage(), false, x, cursor, width);
        cursor = drawSpecEffectRow(guiGraphics, "gui.albionmastery.panel.effect_desperation",
                values.desperationDamage(), false, x, cursor, width);
        return cursor;
    }

    /**
     * 画一项特化影响，中性值（×1）直接跳过。
     *
     * @param guiGraphics  绘图上下文
     * @param labelKey     标签语言键
     * @param multiplier   倍率
     * @param lowerIsBette 小于 1 是否算变好（时间类的项是这样）
     * @param x            起始 X
     * @param y            起始 Y
     * @param width        可用宽度
     * @return 结束后的 Y
     */
    private int drawSpecEffectRow(GuiGraphics guiGraphics, String labelKey, double multiplier,
                                  boolean lowerIsBette, int x, int y, int width) {
        if (Math.abs(multiplier - 1.0D) <= 1.0E-6D) {
            return y;
        }
        boolean better = lowerIsBette ? multiplier < 1.0D : multiplier > 1.0D;
        return drawRow(guiGraphics, Component.translatable(labelKey),
                Component.literal("\u00D7" + formatMultiplier(multiplier))
                        .withStyle(better ? ChatFormatting.GREEN : ChatFormatting.RED),
                x, y, width);
    }

    /**
     * 画面板标题：主标题 + 可选副标题 + 一条金色分隔线。
     *
     * @param guiGraphics 绘图上下文
     * @param title       主标题
     * @param subtitle    副标题，可为 null
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 结束后的 Y
     */
    private int drawTitle(GuiGraphics guiGraphics, Component title, @Nullable Component subtitle,
                          int x, int y, int width) {
        int cursor = drawWrapped(guiGraphics, title, x, y, width, MasteryPalette.GOLD_BRIGHT);
        if (subtitle != null) {
            cursor = drawWrapped(guiGraphics, subtitle, x, cursor + 1, width, MasteryPalette.TEXT_DIM);
        }
        guiGraphics.fill(x, cursor + 4, x + width, cursor + 5, MasteryPalette.EDGE);
        return cursor + 11;
    }

    /**
     * 画一个小节标题。
     *
     * @param guiGraphics 绘图上下文
     * @param title       小节名
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 结束后的 Y
     */
    private int drawSection(GuiGraphics guiGraphics, Component title, int x, int y, int width) {
        guiGraphics.drawString(this.font, title, x, y, MasteryPalette.GOLD, false);
        guiGraphics.fill(x, y + LINE, x + width, y + LINE + 1, 0x406B5223);
        return y + LINE + 5;
    }

    /**
     * 画一行「左标签 + 右对齐数值」。
     *
     * <p>之前把数值直接塞进句子里，中文换行会把「每 1」和「级」拆到两行，很难看。
     * 改成表格式的两栏，右侧数值统一右对齐。
     *
     * @param guiGraphics 绘图上下文
     * @param label       左侧标签
     * @param value       右侧数值
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 结束后的 Y
     */
    private int drawRow(GuiGraphics guiGraphics, Component label, Component value,
                        int x, int y, int width) {
        int valueWidth = this.font.width(value);
        int labelMax = Math.max(20, width - valueWidth - 8);
        guiGraphics.drawString(this.font, trimToWidth(label.getString(), labelMax),
                x, y, MasteryPalette.TEXT_DIM, false);
        guiGraphics.drawString(this.font, value, x + width - valueWidth, y, MasteryPalette.TEXT, false);
        return y + LINE + 2;
    }

    /**
     * 绘制「等级 + 经验进度条 + 距升级还差多少」。
     *
     * @param guiGraphics 绘图上下文
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @param level       当前等级
     * @param xp          总经验
     * @param curve       等级曲线
     * @param maxLevel    等级上限
     * @return 结束后的 Y
     */
    private int drawLevelBlock(GuiGraphics guiGraphics, int x, int y, int width,
                               int level, long xp, LevelCurve curve, int maxLevel) {
        String levelText = "Lv." + level;
        String maxText = "/ " + maxLevel;
        guiGraphics.drawString(this.font, levelText, x, y, MasteryPalette.GOLD_BRIGHT, false);
        guiGraphics.drawString(this.font, maxText, x + this.font.width(levelText) + 4, y,
                MasteryPalette.TEXT_DIM, false);
        int cursor = y + LINE + 3;

        long inLevel = curve.xpInCurrentLevel(xp);
        long span = curve.xpForCurrentLevelSpan(xp);
        double ratio = span > 0L ? (double) inLevel / (double) span : 1.0D;

        guiGraphics.fill(x - 1, cursor - 1, x + width + 1, cursor + 8, MasteryPalette.EDGE);
        guiGraphics.fill(x, cursor, x + width, cursor + 7, 0xFF17120A);
        int filled = (int) Math.round(width * Mth.clamp(ratio, 0.0D, 1.0D));
        if (filled > 0) {
            guiGraphics.fill(x, cursor, x + filled, cursor + 7, MasteryPalette.PROGRESS);
        }
        cursor += 12;

        if (level >= maxLevel) {
            guiGraphics.drawString(this.font, Component.translatable("gui.albionmastery.panel.maxed"),
                    x, cursor, MasteryPalette.GOLD_BRIGHT, false);
            return cursor + LINE;
        }
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_level_xp"),
                Component.literal(inLevel + " / " + span), x, cursor, width);
        return drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_to_next"),
                Component.literal(String.valueOf(curve.xpRemainingToNextLevel(xp))), x, cursor, width);
    }

    /**
     * 绘制自动换行的文本。
     *
     * @param guiGraphics 绘图上下文
     * @param text        文本
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @param color       颜色
     * @return 结束后的 Y
     */
    private int drawWrapped(GuiGraphics guiGraphics, Component text, int x, int y, int width, int color) {
        List<FormattedCharSequence> lines = this.font.split(text, width);
        int cursor = y;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(this.font, line, x, cursor, color, false);
            cursor += LINE;
        }
        return cursor;
    }

    /**
     * 悬停提示。
     *
     * @param guiGraphics 绘图上下文
     * @param node        节点
     * @param mouseX      鼠标 X
     * @param mouseY      鼠标 Y
     */
    private void drawHoverTooltip(GuiGraphics guiGraphics, MasteryNode node, int mouseX, int mouseY) {
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        List<Component> lines = new ArrayList<>();
        lines.add(node.displayName());
        int level = node.hasMastery() ? levelOf(node) : -1;
        if (level >= 0) {
            lines.add(Component.translatable("gui.albionmastery.tooltip.level", level,
                    snapshot.maxLevel));
        }

        // 不用 renderComponentTooltip：原版那圈紫边渐变和这套金褐配色完全不搭。
        // 自己画一个和详情面板同款的深底金边小卡片。
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, this.font.width(line));
        }
        // 高度按「文字实际占的行高 + 上下等量内边距」算。
        // 之前用的是 行数×LINE + 7，LINE 比字高多出来的那点行距全堆在了底部，
        // 看起来就是字贴着上边、底下空一截
        int textHeight = lines.size() * LINE - (LINE - this.font.lineHeight);
        boolean bar = level > 0;
        int height = textHeight + TOOLTIP_PAD_Y * 2 + (bar ? BAR_HEIGHT + 3 : 0);

        int x = mouseX + 12;
        int y = mouseY - 6;
        // 贴到屏幕边缘就翻到鼠标另一侧，别被裁掉
        if (x + width + 10 > this.width) {
            x = mouseX - width - 14;
        }
        if (y + height > this.height) {
            y = this.height - height - 2;
        }
        y = Math.max(2, y);

        int left = x - TOOLTIP_PAD_X;
        int right = x + width + TOOLTIP_PAD_X;
        int top = y;
        int bottom = y + height;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 600.0F);
        guiGraphics.fill(left, top, right, bottom, MasteryPalette.PANEL);
        guiGraphics.fill(left, top, right, top + 1, MasteryPalette.EDGE);
        guiGraphics.fill(left, bottom - 1, right, bottom, MasteryPalette.EDGE);
        guiGraphics.fill(left, top, left + 1, bottom, MasteryPalette.GOLD_DARK);
        guiGraphics.fill(right - 1, top, right, bottom, MasteryPalette.GOLD_DARK);

        int cursor = y + TOOLTIP_PAD_Y;
        guiGraphics.drawString(this.font, lines.get(0), x, cursor, MasteryPalette.GOLD_BRIGHT, false);
        cursor += LINE;
        for (int i = 1; i < lines.size(); i++) {
            guiGraphics.drawString(this.font, lines.get(i), x, cursor, MasteryPalette.TEXT_DIM, false);
            cursor += LINE;
        }
        if (bar) {
            double ratio = Mth.clamp((double) level / Math.max(1, snapshot.maxLevel), 0.0D, 1.0D);
            int barTop = bottom - TOOLTIP_PAD_Y - BAR_HEIGHT;
            guiGraphics.fill(x, barTop, x + width, barTop + BAR_HEIGHT, 0xFF17120A);
            guiGraphics.fill(x, barTop, x + (int) Math.round(width * ratio), barTop + BAR_HEIGHT,
                    ratio >= 1.0D ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.PROGRESS);
        }
        guiGraphics.pose().popPose();
    }

    // ======================================================================
    // 交互
    // ======================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (isOnButton(mouseX, mouseY)) {
            resetView();
            return true;
        }
        if (this.searchBox != null && mouseY < HEADER_HEIGHT) {
            // 顶栏整条都不参与地图操作，免得点标题就把镜头拖跑了
            return this.searchBox.mouseClicked(mouseX, mouseY, button);
        }
        if (isInPanel(mouseX)) {
            return clickSpecialization(mouseX, mouseY);
        }

        MasteryNode node = pickNode(mouseX, mouseY);
        if (node != null) {
            // 整张图是全展开的，点节点就只是选中它、在左侧弹出详情
            this.selected = node == this.selected ? null : node;
            updatePath();
            return true;
        }

        // 点空白处收起详情面板，同时开始拖动
        this.selected = null;
        updatePath();
        this.dragging = true;
        this.hasZoomAnchor = false;
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.dragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.dragging && button == 0) {
            // 拖动直接改当前值，不做插值，否则手感会发飘
            this.hasZoomAnchor = false;
            this.panX += mouseX - this.lastMouseX;
            this.panY += mouseY - this.lastMouseY;
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // 鼠标停在面板上且内容超出时，滚轮归面板用，不去缩放地图
        if (isInPanel(mouseX) && this.panelScrollable) {
            this.panelScroll = Math.max(0.0D, this.panelScroll - delta * PANEL_SCROLL_STEP);
            return true;
        }
        double newZoom = Mth.clamp(this.targetZoom * (delta > 0.0D ? ZOOM_STEP : 1.0D / ZOOM_STEP),
                minZoom(), maxZoom());
        if (Math.abs(newZoom - this.targetZoom) < 1.0E-6D) {
            return true;
        }
        // 只在没有进行中的缩放时重新取锚点。
        // 连续滚轮时沿用同一个锚点，视角才是稳稳地往那个点推进，
        // 而不是每滚一格就换一个参照点、越滚越偏。
        if (!this.hasZoomAnchor) {
            this.anchorScreenX = mouseX;
            this.anchorScreenY = mouseY;
            this.anchorGraphX = (mouseX - baseX() - this.panX) / this.zoom;
            this.anchorGraphY = (mouseY - baseY() - this.panY) / this.zoom;
            this.hasZoomAnchor = true;
        }
        this.targetZoom = newZoom;
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.searchBox != null && this.searchBox.isFocused()) {
            return this.searchBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 搜索框有焦点时，除了 ESC 之外的按键一律先给它——
        // 不然打字打到「b」就把界面关了
        if (this.searchBox != null && this.searchBox.isFocused()
                && keyCode != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                jumpToFirstHit();
                return true;
            }
            if (this.searchBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        if (KeyBindings.OPEN_MASTERY.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        // ESC 第一次先退出搜索框，再按收起面板，最后才关界面
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
                && this.searchBox != null && this.searchBox.isFocused()) {
            this.searchBox.setFocused(false);
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && this.selected != null) {
            this.selected = null;
            updatePath();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ======================================================================
    // 工具
    // ======================================================================

    /**
     * 判断坐标是否落在详情面板上。面板没显示时永远返回 false。
     *
     * @param mouseX 鼠标 X
     * @return 落在面板上返回 true
     */
    private boolean isInPanel(double mouseX) {
        return this.selected != null && mouseX <= PANEL_WIDTH;
    }

    /**
     * 处理落在面板里的点击：只认流派选项，其余位置吞掉不做事。
     *
     * @param mouseX 鼠标 X
     * @param mouseY 鼠标 Y
     * @return 点中了流派返回 true
     */
    private boolean clickSpecialization(double mouseX, double mouseY) {
        if (this.specHits.isEmpty() || mouseX < 0.0D || mouseX > PANEL_WIDTH) {
            return false;
        }
        // 画的时候用的是内容坐标，这里把鼠标位置换算回去
        double contentY = mouseY + this.panelScroll;
        for (SpecHit hit : this.specHits) {
            if (contentY >= hit.top() && contentY < hit.top() + hit.height()) {
                NetworkHandler.sendToServer(
                        new C2SSelectSpecializationPacket(hit.typeKey(), hit.spec().key()));
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                return true;
            }
        }
        return false;
    }

    @Nullable
    private MasteryNode pickNode(double mouseX, double mouseY) {
        if (isInPanel(mouseX)) {
            return null;
        }
        for (int i = this.nodes.size() - 1; i >= 0; i--) {
            MasteryNode node = this.nodes.get(i);
            double radius = node.radius * this.zoom;
            double dx = mouseX - toScreenX(node.graphX);
            double dy = mouseY - toScreenY(node.graphY);
            if (dx * dx + dy * dy <= radius * radius) {
                return node;
            }
        }
        return null;
    }

    /**
     * 判断一整条连线是不是完全在屏幕外。
     *
     * <p>只做最粗的一步：两个端点同时在屏幕某一侧之外，那整条线一定看不见。
     * 斜穿屏幕角落的极少数线会被判为「可能可见」而照常画，这没关系——
     * 这里要的是便宜且绝不误杀，不是精确。
     *
     * @param x1 起点
     * @param y1 起点
     * @param x2 终点
     * @param y2 终点
     * @return 完全在屏幕外返回 true
     */
    private boolean isSegmentOffScreen(float x1, float y1, float x2, float y2) {
        float margin = 8.0F;
        return (x1 < -margin && x2 < -margin)
                || (x1 > this.width + margin && x2 > this.width + margin)
                || (y1 < -margin && y2 < -margin)
                || (y1 > this.height + margin && y2 > this.height + margin);
    }

    private boolean isOffScreen(float sx, float sy, float margin) {
        return sx + margin < 0.0F || sx - margin > this.width
                || sy + margin < 0.0F || sy - margin > this.height;
    }

    private String trimToWidth(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(4, maxWidth - this.font.width(".."))) + "..";
    }


    private static int levelOf(MasteryNode node) {
        String key = node.dataKey();
        if (key == null) {
            return 0;
        }
        MasteryData data = ClientMasteryCache.data();
        return switch (node.type) {
            case SOURCE -> data.getOverallLevel(key);
            case TYPE -> data.getTypeLevel(key);
            case WEAPON -> data.getWeaponLevel(key);
            default -> 0;
        };
    }

    /**
     * 取一个来源 / 类型节点底下全部武器的等级合计。
     *
     * <p>结果按节点数据键缓存到本帧结束：同一个节点在一帧里可能被文字层反复问到，
     * 而来源节点的一次统计要遍历它下面的所有武器。
     *
     * @param node     节点，只支持来源与类型两种
     * @param maxLevel 单件武器的等级上限
     * @return 合计；节点下没有武器时返回 {@code null}
     */
    @Nullable
    private Aggregate aggregateOf(MasteryNode node, int maxLevel) {
        String key = node.dataKey();
        if (key == null) {
            return null;
        }
        Aggregate cached = this.frameAggregates.get(key);
        if (cached != null) {
            return cached;
        }

        List<WeaponEntry> weapons;
        if (node.type == MasteryNode.Type.SOURCE) {
            weapons = node.source == null ? List.of() : node.source.allWeapons();
        } else {
            weapons = node.typeGroup == null ? List.of() : node.typeGroup.weapons();
        }
        if (weapons.isEmpty()) {
            return null;
        }

        MasteryData data = ClientMasteryCache.data();
        int levelSum = 0;
        for (WeaponEntry entry : weapons) {
            levelSum += data.getWeaponLevel(entry.key());
        }
        Aggregate aggregate = new Aggregate(levelSum, weapons.size() * Math.max(1, maxLevel));
        this.frameAggregates.put(key, aggregate);
        return aggregate;
    }

    private double progressOf(MasteryNode node) {
        if (node.type == MasteryNode.Type.INFO) {
            // 中心节点没有等级，就用「已解锁武器占比」把它填起来，一眼看到总进度
            Progress progress = frameProgress();
            return progress.total() <= 0 ? 0.0D : (double) progress.unlocked() / progress.total();
        }
        String key = node.dataKey();
        if (key == null) {
            return 0.0D;
        }
        MasteryData data = ClientMasteryCache.data();
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        // 三层曲线不同，进度条要按本层自己的曲线算
        LevelCurve curve = switch (node.type) {
            case SOURCE -> snapshot.overallCurve();
            case TYPE -> snapshot.typeCurve();
            default -> snapshot.weaponCurve();
        };
        long xp = switch (node.type) {
            case SOURCE -> data.getOverallXp(key);
            case TYPE -> data.getTypeXp(key);
            case WEAPON -> data.getWeaponXp(key);
            default -> 0L;
        };
        long span = curve.xpForCurrentLevelSpan(xp);
        if (span <= 0L) {
            return xp > 0L ? 1.0D : 0.0D;
        }
        return Mth.clamp((double) curve.xpInCurrentLevel(xp) / (double) span, 0.0D, 1.0D);
    }

    /**
     * 把倍率格式化成简短形式，整数不带小数点。
     *
     * @param value 数值
     * @return 字符串
     */
    private static String trimNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.005D) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * 取本帧的全局进度，一帧只统计一次。
     *
     * <p>统计要遍历全部武器（上百件），而中心节点的填充与说明面板每帧各调一次，
     * 不缓存就是每帧白跑两遍。
     *
     * @return 进度统计
     */
    private Progress frameProgress() {
        if (this.frameProgress == null) {
            this.frameProgress = collectProgress();
        }
        return this.frameProgress;
    }

    /**
     * 汇总全局进度。
     *
     * @return 进度统计
     */
    private static Progress collectProgress() {
        MasteryData data = ClientMasteryCache.data();
        int total = 0;
        int unlocked = 0;
        int levelSum = 0;
        int maxed = 0;
        int maxLevel = ClientMasteryCache.snapshot().maxLevel;
        for (WeaponSource source : ClientWeaponRegistry.sources()) {
            for (WeaponEntry entry : source.allWeapons()) {
                total++;
                int level = data.getWeaponLevel(entry.key());
                if (level > 0) {
                    unlocked++;
                    levelSum += level;
                }
                if (level >= maxLevel) {
                    maxed++;
                }
            }
        }
        return new Progress(total, unlocked, levelSum, maxed);
    }

    /**
     * 全局进度统计。
     *
     * @param total    武器总数
     * @param unlocked 已解锁（等级大于 0）的武器数
     * @param levelSum 全部武器的等级之和
     * @param maxed    已满级的武器数
     */
    private record Progress(int total, int unlocked, int levelSum, int maxed) {
    }

    /**
     * 一个流派选项在面板里的点击区。
     *
     * @param spec   流派
     * @param top    内容坐标下的顶边
     * @param height 高度
     */
    private record SpecHit(Specialization spec, String typeKey, int top, int height) {
    }

    /**
     * 一次关界面时保存下来的视角。
     *
     * @param zoom        缩放
     * @param panX        镜头位移
     * @param panY        镜头位移
     * @param selectedKey 选中节点的数据键，没有选中时为 null
     */
    private record ViewState(double zoom, double panX, double panY, @Nullable String selectedKey) {
    }

    /**
     * 一个分支（来源或类型）底下全部武器的等级合计。
     *
     * @param levelSum 当前等级之和
     * @param maxSum   全部武器都满级时的等级之和
     */
    private record Aggregate(int levelSum, int maxSum) {
    }

    private static String perLevel(double value) {
        return String.format(Locale.ROOT, "+%.1f%%", value * 100.0D);
    }

    /**
     * 把加成比例格式化成带符号的百分比。
     *
     * @param value 比例值
     * @return 形如 {@code +105.7%} 的字符串
     */
    public static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%+.1f%%", value * 100.0D);
    }
}
