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
import com.flameforge.albionmastery.mastery.spec.SpecTerms;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.network.C2SAdminActionPacket;
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
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.client.server.IntegratedServer;

/**
 * 阿尔比恩专精系统主界面。
 *
 * <p>五层放射状节点树，节点是六边形，等级越高从底部填得越满：
 * <pre>
 *   总览 → 来源模组 → 总体 → 武器类型 → 具体武器
 * </pre>
 *
 * <p>操作：左键点节点看详情 / 双击节点飞过去（来源、类型整支收进屏幕）/ 空白处拖动平移 / 滚轮缩放 / ESC 关闭。
 *
 * <p>同一个界面也用来看别人的罗盘：从排行榜点进来时，传入那个人的 {@link MasteryData}，
 * 界面就按他的数据画，特化选择照样显示，只是不能点（只读）。
 * 所有数据读取都走 {@link #data} 这个字段而不是直接读客户端缓存，就是为了这一点。
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
     * <p>真正的下限是 {@link #minZoom()} 按当前这张图算出来的：图多大就允许缩到多小，
     * 保证任何时候都能把整张图收进屏幕。写死一个下限两头都不对——只有几把武器时能缩到看不见，
     * 几千把武器时想看全貌又缩不下去。
     */
    private static final double MIN_ZOOM_FLOOR = 0.003D;

    /**
     * 放到最大时，外圈武器节点有多少像素半径。
     *
     * <p>上限也跟着图走：武器多的图节点本身就小，得允许放得更大才能看清；
     * 武器少的图节点大，放到同样的倍数就是一整块屏幕一个六边形，没意义。
     */
    private static final double MAX_NODE_PIXELS_AT_MAX_ZOOM = 80.0D;

    /** 缩放上限的取值范围，{@link #maxZoom()} 按节点大小算出来之后夹在这之间。 */
    private static final double MAX_ZOOM_LOW = 3.0D;
    private static final double MAX_ZOOM_HIGH = 9.0D;

    /**
     * 缩到最小时整张图占屏幕的比例（相对「刚好放下」的缩放再乘这个系数）。
     *
     * <p>几乎不再额外留边，让最小缩放尽量贴近「整张圆盘刚好看完」。
     */
    private static final double MIN_ZOOM_WHOLE_FACTOR = 0.98D;

    /**
     * 从最小缩到最大要滚多少格。
     *
     * <p>每格的倍数不写死，按当前这张图的缩放范围算：图小范围窄，每格走得少；
     * 图大范围宽，每格走得多。十二格是「从全貌到贴脸」的总数，
     * 一千多件武器的图从全貌滚到能认出图标大概六七格，几十件的图三四格。
     */
    private static final double ZOOM_NOTCHES = 12.0D;

    /**
     * 在原始全貌最小值的基础上，额外往里抬多少格滚轮。
     *
     * <p>1 表示「旧最小值放大一格」；你上一轮就是这个效果。
     * 这次又明确要求在那基础上再限制一格，所以这里改成 2。
     */
    private static final int MIN_ZOOM_EXTRA_NOTCHES = 2;

    /** 每格倍数的取值范围，太小滚不动，太大跳得晃眼。 */
    private static final double ZOOM_STEP_MIN = 1.15D;
    private static final double ZOOM_STEP_MAX = 2.0D;

    /**
     * 内层节点在屏幕上的最小半径（像素）：中心 / 来源 / 类型。
     *
     * <p>缩到全貌时按真实比例它们只剩一个点，可它们一共才几十个，是认路用的——
     * 给个下限让它们始终是个带图标的六边形，缩放只影响最外圈那一千多件武器。
     * 下限都比节点的真实半径小，放大之后自然过渡到真实尺寸，不会突然跳一下。
     *
     * <p>这只是上限意义上的下限：真正用的值还要按圈与圈在屏幕上的间距再压一道
     * （见 {@link #updateRadiusFloors()}），整张图缩得很小时节点跟着小，
     * 不至于来源把中心盖住、类型把来源盖住。
     */
    private static final double INFO_MIN_SCREEN_RADIUS = 18.0D;
    private static final double SOURCE_MIN_SCREEN_RADIUS = 13.0D;
    private static final double TYPE_MIN_SCREEN_RADIUS = 8.0D;

    /**
     * 节点下限最多占相邻两圈屏幕间距的多少。
     *
     * <p>中心到来源那一段，中心占 0.38、来源占 0.28，加起来 0.66，剩下三成多是空的，
     * 连线看得见；来源到类型、类型到武器同理。类型之间横向挤在一起不管——
     * 中圈本来就密，横向叠一点不影响认路，纵向盖住别的圈才要命。
     */
    private static final double INFO_FLOOR_SHARE = 0.38D;
    private static final double SOURCE_FLOOR_SHARE = 0.28D;
    private static final double TYPE_FLOOR_SHARE = 0.28D;

    /** 这一帧实际生效的三个下限，每帧按当前缩放算一次。 */
    private double infoFloor;
    private double sourceFloor;
    private double typeFloor;

    /** 节点标签的字号缩放上下限，让文字跟着板子一起缩放而不是固定大小。 */
    private static final float LABEL_SCALE_MIN = 0.5F;
    private static final float LABEL_SCALE_MAX = 1.1F;

    /** 节点屏幕半径小于该值才不渲染图标。只挡住小到没有意义的那一档，正常缩放一律画。 */
    private static final float ICON_MIN_SCREEN_RADIUS = 1.5F;

    /**
     * 来源节点在「节点最小屏幕半径」完全接管尺寸时，图标最少缩到节点图标正常尺寸的多少。
     *
     * <p>来源节点本体保留较大的点击区和导航轮廓，但全貌视角下不再让物品图标把六边形塞满，
     * 避免内圈看起来和中心总汇抢视觉重心；但也不能缩成空壳，所以这一版继续往回拉一些。
     */
    private static final float SOURCE_ICON_MIN_SCALE = 0.68F;

    /**
     * 类型节点在「节点最小屏幕半径」完全接管尺寸时，图标最少缩到节点图标正常尺寸的多少。
     *
     * <p>中圈节点数量比来源多，远景下更容易挤成一圈大图标，所以比来源再收一点；
     * 全貌视角依然比近景更收敛，但这次会更接近“把格子填满”的观感。
     */
    private static final float TYPE_ICON_MIN_SCALE = 0.62F;

    /**
     * 贴图图标占节点半径的比例。
     *
     * <p>物品图标是节点半径的 0.95 倍，铺满整个节点；法术贴图裁成六边形之后天然就是节点的形状，
     * 取 0.9 让它和物品图标一样铺满、又留一圈金边不顶到描边上。
     */
    private static final float TEXTURE_ICON_RADIUS_FACTOR = 0.9F;

    /** 贴图图标往里裁边的比例：只切掉四周一圈边缘像素，别再放大。 */
    private static final float TEXTURE_ICON_CROP = 0.97F;

    /** 节点屏幕半径小于该值时只画一个实心六边形，不画底板、进度和描边。 */
    private static final float TINY_NODE_RADIUS = 3.0F;

    /**
     * 武器节点屏幕半径小于该值时不画等级铭牌和名字。
     *
     * <p>铭牌是字，字的缩放有下限（{@value #LABEL_SCALE_MIN}），节点缩到两三个像素时铭牌反而比节点大，
     * 几千个节点就是几千次文字渲染——「缩到最小就掉帧」大头在这里，不在六边形上。
     */
    private static final float LABEL_MIN_SCREEN_RADIUS = 5.0F;

    /**
     * 中圈名字至少要有多少字体坐标宽度的可用空间才显示（大约三个汉字）。
     */
    private static final int TYPE_LABEL_MIN_BUDGET = 30;

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

    /**
     * 旧版武器名称开始显示的基准缩放。
     *
     * <p>真正使用的阈值见 {@link #weaponNameZoomThreshold()}：按你的要求提前一格滚轮倍率。
     */
    private static final double NAME_ZOOM = 0.68D;

    /** 分支合计行相对节点名称的字号系数，比名称小一号，免得抢了名字的视线。 */
    private static final float SUM_LABEL_SCALE = 0.82F;

    /**
     * 对准某个武器节点（手持武器、搜索命中、双击）时，让它在屏幕上有多少像素半径。
     *
     * <p>不按倍数算：武器多的图节点本身只有十来个单位大，同样的倍数下就是几个像素，
     * 认不出图标。按像素定，大图小图对准之后看到的是一样大的节点。
     */
    private static final double FOCUS_NODE_PIXELS = 17.0D;

    /** 双击聚焦到一个分支时，分支占屏幕可用区域的比例，留点边。 */
    private static final double FLY_FIT_FACTOR = 0.82D;

    /**
     * 双击类型节点飞过去时，武器节点至少多少像素半径。
     *
     * <p>几十件武器排成一排，整排塞进屏幕每件只剩两三个像素，什么都看不清；
     * 宁可先看清中间这一段，两边的拖一下就到。
     */
    private static final double FLY_TYPE_NODE_PIXELS = 7.0D;

    /** 两次点击间隔在这之内算双击（毫秒）。 */
    private static final long DOUBLE_CLICK_MS = 380L;

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

    /** 「得 / 失」行首符号占的宽度。 */
    private static final int SPEC_BULLET_WIDTH = 8;

    /** 默认视角下外圈节点至少要有的屏幕像素半径。 */
    private static final double MIN_DEFAULT_NODE_PIXELS = 5.0D;

    /** 重置视角按钮的尺寸。 */
    private static final int BUTTON_WIDTH = 74;
    private static final int BUTTON_HEIGHT = 17;

    /** 左下角两个按钮之间的间距。 */
    private static final int BUTTON_GAP = 6;

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

    /** 来源节点的个数，算名字与等级牌宽度预算用。 */
    private int sourceCount;

    /** 类型节点的个数，算名字与等级牌宽度预算用。 */
    private int typeCount;

    /**
     * 中圈最挤的两个相邻类型节点之间的距离（图坐标）。
     *
     * <p>中圈的名字按整圈统一决定显示不显示、截多长：以最挤的那一对之间放得下的宽度为准，一起出现、一起隐藏。
     * 以前是每个节点各自看离邻居多远，武器多的类型分到的扇区宽、名字就出来了，
     * 武器少的几个挤在一起就整块不显示，同一圈上有的有名字有的没有。
     */
    private double minTypeSpacing = Double.POSITIVE_INFINITY;

    /** 上一帧面板显示的是哪个节点，换节点时用来把滚动归零。 */
    @Nullable
    private MasteryNode panelNode;

    /** 上一帧面板内容是否超出可视区域，决定滚轮要不要被面板吃掉。 */
    private boolean panelScrollable;

    /** 顶栏右侧的武器搜索框。 */
    @Nullable
    private EditBox searchBox;


    /** 当前搜索命中的节点，空搜索时为空集。 */
    private final Set<MasteryNode> searchHits = new HashSet<>();

    /** 本帧鼠标位置，面板要靠它做悬停高亮。 */
    private int mouseXNow;

    /** 本帧鼠标位置，面板要靠它做悬停高亮。 */
    private int mouseYNow;

    /**
     * 上一帧画出来的「特化配置」按钮。
     *
     * <p>存的是「内容坐标」——也就是没有减去滚动量的 Y。点击时把鼠标 Y 加上滚动量再比，
     * 免得面板一滚点击区就全错位。
     */
    @Nullable
    private SpecButton specButton;

    /** 上一帧画出来的管理按钮，和特化按钮一样存内容坐标、每帧重建。 */
    private final List<AdminButton> adminButtons = new ArrayList<>();

    /** 总览面板的分页按钮点击区，每帧重建。 */
    private final List<InfoTabHit> infoTabHits = new ArrayList<>();

    /** 总览面板当前停在哪一页。 */
    private static InfoTab infoTab = InfoTab.PROGRESS;

    /** 等二次确认的危险操作（清零）的 id，null 表示没有；以及确认的截止时刻。 */
    @Nullable
    private String armedAdmin;
    private long armedUntilMs;

    /** 上一帧面板的可视区（屏幕坐标），被裁掉的按钮不能点。 */
    private int panelViewTop;
    private int panelViewBottom;

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

    /** 帧号，每次 render 加一，节点上的等级缓存靠它判断是不是本帧算的。 */
    private int frameStamp;

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

    /** 正在飞向某个位置（双击聚焦），每帧把平移往目标逼近。拖动或滚轮会打断。 */
    private boolean flying;

    /** 飞行的目标：让这个图坐标落在屏幕中心。 */
    private double flyCenterX;
    private double flyCenterY;

    /** 上一次点中的节点和时刻，用来判断双击。 */
    @Nullable
    private MasteryNode lastClickedNode;
    private long lastClickMs;

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

    /**
     * 这个界面画的是谁的数据。
     *
     * <p>自己的界面就是 {@code ClientMasteryCache.data()} 那个对象（同步包会直接改它，界面跟着变）；
     * 看别人时是一份从服务端拿来的独立副本。
     */
    private final MasteryData data;

    /** 从哪个界面进来的（排行榜），ESC 回到它；null 表示直接关闭。 */
    @Nullable
    private final Screen parentScreen;

    /** 是否只读：看别人的罗盘时特化卡片只显示不响应。 */
    private final boolean viewOnly;

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
     * 构造自己的专精界面。
     */
    public MasteryScreen() {
        this(ClientMasteryCache.data(), null, null);
    }

    /**
     * 构造一个看别人（或指定数据）的专精界面。
     *
     * @param data   要画的专精数据
     * @param owner  这份数据的主人的名字；传 null 表示是自己的
     * @param parent 上一层界面，ESC 回到它；传 null 则直接关闭
     */
    public MasteryScreen(MasteryData data, @Nullable Component owner, @Nullable Screen parent) {
        super(owner == null
                ? Component.translatable("gui.albionmastery.title")
                : Component.translatable("gui.albionmastery.title_viewing", owner));
        this.data = data;
        this.parentScreen = parent;
        this.viewOnly = owner != null;
    }

    @Override
    protected void init() {
        if (!this.initialized) {
            // 清单只在标记过期时才重建（换存档、收到全量同步、标签重载），
            // 不然一两万件物品要重扫一遍，开界面就是一下明显的卡顿
            this.sources = ClientWeaponRegistry.builtSources();
            rebuildNodes();
        } else {
            // 从特化页面、排行榜回来，或者窗口改了大小：节点原样保留。
            // 以前这里也重建，节点对象全换了一批，选中链路还指着旧对象，整张图就按「不在链路上」压暗了；
            // 上千把枪每次还要重新造一遍图标，拖窗口边框时一卡一卡的。
            // 节点坐标是图空间的，和窗口大小无关，不用重算
            this.layoutTimeMs = Util.getMillis() - 600000L;
        }
        if (!this.initialized) {
            // 看别人的罗盘：手上的武器和记住的视角都是自己的事，跟他无关，直接居中看全貌。
            // 顺序很重要：手上拿着武器就一定定位到那把。
            // 「对着物品按 B」这个动作本身就是在说「我要看这把」，
            // 拿上次的视角把它顶掉，等于把这个功能废了。
            if (this.viewOnly || (!focusHeldWeapon() && !restoreSavedView())) {
                // 空手打开、又没有上次的视角：居中看全貌
                this.zoom = fitZoom();
                this.targetZoom = this.zoom;
            }
            this.initialized = true;
        }
        // 搜索框放顶栏右端。用原版 EditBox 而不是自绘：输入法、光标、选中、
        // 粘贴这些细节自己实现一遍不现实，样式差一点可以接受
        // 去掉原版边框之后要自己留出左右内边距，不然文字会贴着描边
        String previousSearch = this.searchBox == null ? "" : this.searchBox.getValue();
        // 无边框的 EditBox 文字就画在 y 上：放 6 和顶栏标题、命中数同一条线。
        // 右边留出命中数的位置，搜索词很长时两个不会叠在一起
        this.searchBox = new EditBox(this.font, this.width - SEARCH_WIDTH - 8 + SEARCH_PADDING, 6,
                SEARCH_WIDTH - SEARCH_PADDING * 2 - this.font.width("0000") - 4, HEADER_HEIGHT - 10,
                Component.translatable("gui.albionmastery.search"));
        this.searchBox.setHint(Component.translatable("gui.albionmastery.search")
                .withStyle(ChatFormatting.DARK_GRAY));
        // 关掉原版那圈灰边和黑底，自己画一个和整体配色一致的框
        this.searchBox.setBordered(false);
        this.searchBox.setTextColor(MasteryPalette.TEXT & 0xFFFFFF);
        this.searchBox.setTextColorUneditable(MasteryPalette.TEXT_DIM & 0xFFFFFF);
        this.searchBox.setMaxLength(48);
        this.searchBox.setValue(previousSearch);
        this.searchBox.setResponder(text -> updateSearch());
        // 只注册成可交互的子组件、不注册成自动绘制的：render 里已经在 z=600 手动画了一次，
        // 再让 super.render 在 z=0 画一次，选中文字的反色高亮叠两层会互相抵消
        addWidget(this.searchBox);
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
            guiGraphics.pose().translate(right, HEADER_HEIGHT + 4.0F, 0.0F);
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
            double radius = screenRadius(node) + 4.0D;
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
        // 看别人的视角不记：记下来的话回到自己的界面会对着别人那张图的坐标
        if (this.viewOnly) {
            return;
        }
        // 关界面时把视角记下来，下次打开接着看，不用每次重新缩放和拖动
        savedView = new ViewState(this.zoom, this.panX, this.panY,
                this.selected == null ? null : this.selected.dataKey());
    }

    @Override
    public void onClose() {
        // 从排行榜进来的，退出去应该回到排行榜，而不是直接关掉
        if (this.parentScreen != null) {
            Minecraft.getInstance().setScreen(this.parentScreen);
        } else {
            super.onClose();
        }
    }

    /**
     * 丢掉记住的视角。
     *
     * <p>断线或者切服时调用：换个服务端武器清单完全不一样，拿旧坐标恢复出来就是对着一片空白。
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
        return Mth.clamp(FOCUS_NODE_PIXELS / Math.max(1.0D, this.weaponNodeRadius), minZoom(), maxZoom());
    }

    /**
     * 不额外抬高之前，整张图按当前窗口「刚好看完」时对应的原始最小缩放。
     *
     * <p>这是纯几何意义上的全貌缩放：整张圆盘放进屏幕，再乘上
     * {@value #MIN_ZOOM_WHOLE_FACTOR} 这点留白系数。真正给玩家用的最小缩放在
     * {@link #minZoom()} 里还会再往里抬一格，因为上一版虽然已经顺眼很多，
     * 但你实机看下来仍然觉得能再放大一档更舒服。
     *
     * @return 原始全貌缩放下限
     */
    private double baseMinZoom() {
        double whole = Math.min(this.width, this.height) * 0.44D / Math.max(1.0D, this.outerRadius);
        // 原始下限不能高过上限的一半，否则滚轮只剩一两格可滚。
        return Mth.clamp(whole * MIN_ZOOM_WHOLE_FACTOR, MIN_ZOOM_FLOOR, maxZoom() * 0.5D);
    }

    /**
     * 当前这张图允许缩到多小。
     *
     * <p>这里不是直接返回原始全貌缩放，而是按你这次要求：
     * 「以现在的为基础，然后放大一次的效果，就是之后的缩小上限」。
     * 也就是先拿旧逻辑算出原始最小值，再按那一版的滚轮倍率往里推一格，
     * 把那两格之后的位置当成新的最小缩放。这样以后再往外滚，就会停在“旧版最小值放大两格”这个位置。
     *
     * @return 给玩家实际使用的缩放下限
     */
    private double minZoom() {
        double base = baseMinZoom();
        double max = maxZoom();
        double baseStep = Mth.clamp(Math.pow(max / Math.max(base, 1.0E-6D), 1.0D / ZOOM_NOTCHES),
                ZOOM_STEP_MIN, ZOOM_STEP_MAX);
        // 下限不能高过上限的一半，否则滚轮只剩一两格可滚。
        return Mth.clamp(base * Math.pow(baseStep, MIN_ZOOM_EXTRA_NOTCHES), MIN_ZOOM_FLOOR, max * 0.5D);
    }

    /**
     * 当前这张图允许放到多大：让外圈武器节点放到 {@value #MAX_NODE_PIXELS_AT_MAX_ZOOM} 像素半径为止。
     *
     * @return 缩放上限
     */
    private double maxZoom() {
        return Mth.clamp(MAX_NODE_PIXELS_AT_MAX_ZOOM / Math.max(1.0D, this.weaponNodeRadius),
                MAX_ZOOM_LOW, MAX_ZOOM_HIGH);
    }

    /**
     * 滚轮每格的缩放倍数，按当前范围分成 {@value #ZOOM_NOTCHES} 格。
     *
     * <p>注意这里用的是抬高后的 {@link #minZoom()}；也就是说新的最小缩放本身就已经
     * 等于“旧版最小值再放大一格”，之后再继续滚轮时，会在这个新范围里重新均分格数。
     *
     * @return 倍数，大于 1
     */
    private double zoomStep() {
        double range = maxZoom() / minZoom();
        return Mth.clamp(Math.pow(range, 1.0D / ZOOM_NOTCHES), ZOOM_STEP_MIN, ZOOM_STEP_MAX);
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
        drawFramedButton(guiGraphics, buttonX(), buttonY(),
                Component.translatable("gui.albionmastery.reset_view"), isOnButton(mouseX, mouseY));
    }

    /**
     * 画一个描边按钮，重置视角和排行榜两个按钮共用。
     *
     * @param guiGraphics 绘图上下文
     * @param x           左边缘
     * @param y           顶边
     * @param label       文字
     * @param hover       是否悬停
     */
    private void drawFramedButton(GuiGraphics guiGraphics, int x, int y, Component label, boolean hover) {
        guiGraphics.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, hover ? 0xF01E1810 : 0xC0130F09);
        int border = hover ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK;
        guiGraphics.fill(x, y, x + BUTTON_WIDTH, y + 1, border);
        guiGraphics.fill(x, y + BUTTON_HEIGHT - 1, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, border);
        guiGraphics.fill(x, y, x + 1, y + BUTTON_HEIGHT, border);
        guiGraphics.fill(x + BUTTON_WIDTH - 1, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, border);
        guiGraphics.drawCenteredString(this.font, label,
                x + BUTTON_WIDTH / 2, y + (BUTTON_HEIGHT - 8) / 2 + 1,
                hover ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT);
    }

    /**
     * 左下角第二个按钮要不要画。
     *
     * <p>看自己时是「全局排行」，看别人时是「返回排行榜」。单人世界又没开局域网时不画：
     * 榜上只可能有自己一行，点进去什么也看不到，白占一个按钮位。
     * 开了局域网就是好几个人，榜单照样有意义，照画。
     *
     * @return 要画返回 true
     */
    private boolean hasSecondButton() {
        if (this.viewOnly) {
            return true;
        }
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        return server == null || server.isPublished();
    }

    /**
     * @return 第二个按钮的左边界，排在重置视角按钮右边
     */
    private int secondButtonX() {
        return buttonX() + BUTTON_WIDTH + BUTTON_GAP;
    }

    /**
     * 判断坐标是否落在第二个按钮上。
     *
     * @param mouseX 鼠标 X
     * @param mouseY 鼠标 Y
     * @return 落在按钮上返回 true
     */
    private boolean isOnSecondButton(double mouseX, double mouseY) {
        return hasSecondButton()
                && mouseX >= secondButtonX() && mouseX <= secondButtonX() + BUTTON_WIDTH
                && mouseY >= buttonY() && mouseY <= buttonY() + BUTTON_HEIGHT;
    }

    /**
     * 画第二个按钮。
     *
     * @param guiGraphics 绘图上下文
     * @param mouseX      鼠标 X
     * @param mouseY      鼠标 Y
     */
    private void drawSecondButton(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (!hasSecondButton()) {
            return;
        }
        Component label = Component.translatable(this.viewOnly
                ? "gui.albionmastery.leaderboard.back"
                : "gui.albionmastery.leaderboard.open");
        drawFramedButton(guiGraphics, secondButtonX(), buttonY(), label, isOnSecondButton(mouseX, mouseY));
    }

    /**
     * 第二个按钮被点了：看自己时打开排行榜，看别人时回到排行榜。
     */
    private void clickSecondButton() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        if (this.viewOnly) {
            onClose();
        } else {
            // 把自己这个界面传过去当上一层，从排行榜按 ESC 能原样回来
            minecraft.setScreen(new LeaderboardScreen(this));
        }
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
        double limit = Math.min(this.width, this.height) * 0.44D;
        double whole = limit / Math.max(1.0D, this.outerRadius);
        double readable = MIN_DEFAULT_NODE_PIXELS / Math.max(1.0D, this.weaponNodeRadius);
        double zoom = Math.max(whole, readable);
        // 图大到「看清节点」的缩放下连来源圈都在屏幕外时（一两千件武器就是这样），
        // 居中打开只能看到中心一个六边形和几条伸出屏幕的线，什么都认不出。
        // 这种图默认改成看全貌：三个圈和分支名字都在，想看哪支双击过去就行
        if (this.sourceRingRadius * zoom > limit) {
            zoom = whole;
        }
        return Mth.clamp(zoom * MasteryConfig.CLIENT.defaultZoom.get(), minZoom(), maxZoom());
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
        this.sourceCount = 0;
        this.typeCount = 0;
        for (MasteryNode node : this.nodes) {
            if (node.type == MasteryNode.Type.SOURCE) {
                this.sourceCount++;
            } else if (node.type == MasteryNode.Type.TYPE) {
                this.typeCount++;
            }
        }
        this.minTypeSpacing = Double.POSITIVE_INFINITY;
        for (MasteryNode node : this.nodes) {
            if (node.type != MasteryNode.Type.TYPE) {
                continue;
            }
            for (MasteryNode other : this.nodes) {
                if (other != node && other.type == MasteryNode.Type.TYPE) {
                    this.minTypeSpacing = Math.min(this.minTypeSpacing,
                            Math.hypot(other.graphX - node.graphX, other.graphY - node.graphY));
                }
            }
        }
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
        this.flying = false;
    }

    /**
     * 飞到一个节点。
     * <ul>
     *     <li>中心节点：看全貌。</li>
     *     <li>来源节点：把这一支的所有类型节点收进屏幕——类型名字是这一层的路标，
     *         武器不算进来，否则一支大的和全貌没区别。</li>
     *     <li>类型节点：把它底下的武器收进屏幕，但武器节点不小于 {@value #FLY_TYPE_NODE_PIXELS} 像素半径，
     *         排太长就先看中间那段。</li>
     *     <li>武器节点：放到 {@value #FOCUS_NODE_PIXELS} 像素半径，居中。</li>
     * </ul>
     *
     * <p>一两千件武器的图，靠拖动从全貌走到某一把武器要滚二三十格、拖十几下；
     * 双击一支、再双击一个类型、再单击武器，三下就到了。
     *
     * @param node 目标节点
     */
    private void flyTo(MasteryNode node) {
        double zoom;
        double centerX;
        double centerY;
        switch (node.type) {
            case WEAPON -> {
                zoom = focusZoom();
                centerX = node.graphX;
                centerY = node.graphY;
            }
            case INFO -> {
                zoom = Mth.clamp(minZoom() / MIN_ZOOM_WHOLE_FACTOR, minZoom(), maxZoom());
                centerX = 0.0D;
                centerY = 0.0D;
            }
            default -> {
                // 来源看它的类型，类型看它的武器
                MasteryNode.Type wanted = node.type == MasteryNode.Type.SOURCE
                        ? MasteryNode.Type.TYPE : MasteryNode.Type.WEAPON;
                double minX = Double.MAX_VALUE;
                double minY = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE;
                double maxY = -Double.MAX_VALUE;
                int count = 0;
                for (MasteryNode other : this.nodes) {
                    if (other.type != wanted || !isDescendant(other, node)) {
                        continue;
                    }
                    minX = Math.min(minX, other.graphX - other.radius);
                    minY = Math.min(minY, other.graphY - other.radius);
                    maxX = Math.max(maxX, other.graphX + other.radius);
                    maxY = Math.max(maxY, other.graphY + other.radius);
                    count++;
                }
                if (count == 0) {
                    // 底下什么都没有（理论上不会），就对准它自己
                    zoom = focusZoom();
                    centerX = node.graphX;
                    centerY = node.graphY;
                    break;
                }
                // 左侧详情面板会挡住一块，可用宽度扣掉它；上下扣掉顶栏和按钮
                double availWidth = Math.max(60.0D, this.width - PANEL_WIDTH - 24.0D);
                double availHeight = Math.max(60.0D, this.height - HEADER_HEIGHT - BUTTON_HEIGHT - 30.0D);
                double boxWidth = Math.max(1.0D, maxX - minX);
                double boxHeight = Math.max(1.0D, maxY - minY);
                zoom = Math.min(availWidth / boxWidth, availHeight / boxHeight) * FLY_FIT_FACTOR;
                if (node.type == MasteryNode.Type.TYPE) {
                    zoom = Math.max(zoom, FLY_TYPE_NODE_PIXELS / Math.max(1.0D, this.weaponNodeRadius));
                }
                zoom = Mth.clamp(zoom, minZoom(), maxZoom());
                centerX = (minX + maxX) / 2.0D;
                centerY = (minY + maxY) / 2.0D;
            }
        }
        this.targetZoom = zoom;
        this.flyCenterX = centerX;
        this.flyCenterY = centerY;
        this.flying = true;
        this.hasZoomAnchor = false;
    }

    /**
     * 判断一个节点是不是另一个节点的后代（顺着父链往上找）。
     *
     * @param node     待判断的节点
     * @param ancestor 祖先
     * @return 是后代返回 true
     */
    private static boolean isDescendant(MasteryNode node, MasteryNode ancestor) {
        MasteryNode current = node.parent;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.parent;
        }
        return false;
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

    /**
     * 节点在屏幕上的半径：武器按真实比例缩放，内层节点有下限（见 {@link #INFO_MIN_SCREEN_RADIUS}）。
     *
     * <p>画节点、画图标、画文字、判断鼠标点中都走这一个方法，几处算出来的尺寸才是一致的。
     *
     * @param node 节点
     * @return 屏幕半径（像素）
     */
    private double screenRadius(MasteryNode node) {
        double radius = node.radius * this.zoom;
        double floor = switch (node.type) {
            case INFO -> this.infoFloor;
            case SOURCE -> this.sourceFloor;
            case TYPE -> this.typeFloor;
            default -> 0.0D;
        };
        return Math.max(radius, floor);
    }

    /**
     * 取节点图标相对节点本体的额外缩放。
     *
     * <p>来源和类型节点为了远景还能认路，节点本体有屏幕半径下限；问题是如果图标也完全跟着
     * 这个下限走，缩到全貌时图标会显得异常肥，甚至和中心总汇差不多抢眼。这里不改节点本体、
     * 点击范围、等级牌和文字，只根据「真实缩放半径 / 最终屏幕半径」判断下限介入了多少：
     * 下限介入越多，来源 / 类型图标越接近各自的最小比例。这里再额外做一层缓动，
     * 让远景保持更小一些，往近处放大时再平滑恢复到 1.0。
     *
     * @param node         节点
     * @param screenRadius 已应用屏幕下限后的节点半径
     * @return 图标额外缩放，武器与中心恒为 1
     */
    private float iconScaleFor(MasteryNode node, float screenRadius) {
        float minScale = switch (node.type) {
            case SOURCE -> SOURCE_ICON_MIN_SCALE;
            case TYPE -> TYPE_ICON_MIN_SCALE;
            default -> 1.0F;
        };
        if (minScale >= 1.0F || screenRadius <= 0.0F) {
            return 1.0F;
        }

        float naturalRadius = (float) (node.radius * this.zoom);
        float naturalShare = Mth.clamp(naturalRadius / screenRadius, 0.0F, 1.0F);
        // 不用线性插值：线性会让图标刚一放大就回弹得太快。
        // 稍微压一下低端，让「全貌 / 很远」这段保持更克制，往近处拉时再平滑恢复到 1.0。
        float easedShare = (float) Math.pow(naturalShare, 1.05D);
        return minScale + (1.0F - minScale) * easedShare;
    }

    /**
     * 额外把图标内容往节点内部铺满一点。
     *
     * <p>前几轮把来源 / 类型图标压得太狠，副作用就是六边形壳还在，里面却像贴了一张小缩略图。
     * 这里不去把黑底和描边重新做粗，而是在远景时只把“图标内容”轻轻放大一点：
     * 节点下限介入越多，额外填充越明显；近景自动退回 1.0。
     *
     * @param node         节点
     * @param screenRadius 已应用屏幕下限后的节点半径
     * @return 图标内容填充系数
     */
    private float iconFillFor(MasteryNode node, float screenRadius) {
        if ((node.type != MasteryNode.Type.SOURCE && node.type != MasteryNode.Type.TYPE)
                || screenRadius <= 0.0F) {
            return 1.0F;
        }
        float naturalRadius = (float) (node.radius * this.zoom);
        float naturalShare = Mth.clamp(naturalRadius / screenRadius, 0.0F, 1.0F);
        float farZoom = 1.0F - naturalShare;
        float maxBoost = node.type == MasteryNode.Type.SOURCE ? 0.10F : 0.14F;
        return 1.0F + maxBoost * farZoom;
    }

    /**
     * 当前缩放下，界面装饰与特效应保留多少存在感。
     *
     * <p>最小缩放时玩家主要看结构，不需要一圈光环、流光、轨道点在那里抢眼；
     * 放大后再把这些细节慢慢放回来。返回值范围约为 0.3 ~ 1.0。
     *
     * @return 特效强度系数
     */
    private float effectScaleForFarZoom() {
        double min = minZoom();
        double full = Math.min(maxZoom(), min * 2.2D);
        if (full <= min + 1.0E-6D) {
            return 1.0F;
        }
        float progress = (float) ((this.zoom - min) / (full - min));
        progress = Mth.clamp(progress, 0.0F, 1.0F);
        float eased = (float) Math.pow(progress, 0.85D);
        return 0.30F + 0.70F * eased;
    }

    /**
     * 按当前缩放算这一帧的三个节点下限。
     *
     * <p>每个下限取「固定上限」和「相邻两圈屏幕间距 × 份额」里更小的：
     * 图缩得越小圈越挤，下限跟着缩，圈与圈之间始终留得出一条看得见连线的空当。
     */
    private void updateRadiusFloors() {
        double centerGap = this.sourceRingRadius * this.zoom;
        double typeRing = this.ringRadii[MasteryNode.Type.TYPE.ordinal()];
        double weaponRing = this.ringRadii[MasteryNode.Type.WEAPON.ordinal()];
        double midGap = Math.max(0.0D, typeRing - this.sourceRingRadius) * this.zoom;
        double outerGap = Math.max(0.0D, weaponRing - typeRing) * this.zoom;
        // 没有来源（图是空的）时圈距都是 0，下限也就是 0，只剩真实尺寸
        this.infoFloor = Math.min(INFO_MIN_SCREEN_RADIUS, centerGap * INFO_FLOOR_SHARE);
        this.sourceFloor = Math.min(SOURCE_MIN_SCREEN_RADIUS,
                Math.min(centerGap, midGap > 0.0D ? midGap : centerGap) * SOURCE_FLOOR_SHARE);
        this.typeFloor = Math.min(TYPE_MIN_SCREEN_RADIUS,
                Math.min(midGap, outerGap > 0.0D ? outerGap : midGap) * TYPE_FLOOR_SHARE);
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
        this.frameStamp++;
        long now = Util.getMillis();
        float delta = Math.min(0.1F, (now - this.lastFrameMs) / 1000.0F);
        this.lastFrameMs = now;
        this.frameProgress = null;
        this.frameAggregates.clear();

        // 缩放做指数逼近，滚轮滚起来是平滑推进而不是一跳一跳
        double before = this.zoom;
        float ease = 1.0F - (float) Math.exp(-16.0F * delta);
        this.zoom += (this.targetZoom - this.zoom) * ease;
        if (this.flying) {
            // 目标平移随当前缩放走：缩放还在逼近的过程中，平移要一直对准同一个图坐标，
            // 否则飞到一半的画面会先偏出去再飘回来
            double wantX = -this.flyCenterX * this.zoom;
            double wantY = -this.flyCenterY * this.zoom;
            this.panX += (wantX - this.panX) * ease;
            this.panY += (wantY - this.panY) * ease;
            if (Math.abs(this.targetZoom - this.zoom) < 1.0E-4D
                    && Math.abs(wantX - this.panX) < 0.05D && Math.abs(wantY - this.panY) < 0.05D) {
                this.zoom = this.targetZoom;
                this.panX = wantX;
                this.panY = wantY;
                this.flying = false;
            }
        } else if (this.hasZoomAnchor) {
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

        updateRadiusFloors();
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
        // 文字层一次性提交：每次 drawString 默认会立刻把缓冲刷出去，
        // 上千个节点、每个六七行字，一帧就是几千次 draw call。包在 drawManaged 里只在最后刷一次
        guiGraphics.drawManaged(() -> drawNodeOverlays(guiGraphics, now));
        if (this.selected != null) {
            drawPanel(guiGraphics);
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 350.0F);
        drawSearchHighlights(guiGraphics, now);
        guiGraphics.pose().popPose();
        drawHeader(guiGraphics);
        if (this.searchBox != null) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0F, 0.0F, 600.0F);
            drawSearchFrame(guiGraphics, now);
            this.searchBox.render(guiGraphics, mouseX, mouseY, partialTick);
            guiGraphics.pose().popPose();
        }
        // 左下角两个按钮也要抬到物品图标上面：renderItem 自带 150 以上的深度，
        // 按钮画在 0 层的话，节点刚好飘到按钮底下时图标会压在字上
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 600.0F);
        drawResetButton(guiGraphics, mouseX, mouseY);
        drawSecondButton(guiGraphics, mouseX, mouseY);
        guiGraphics.pose().popPose();

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
            float radius = (float) screenRadius(node) * scale;
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
        float effectScale = effectScaleForFarZoom();
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
                    RenderUtil.withAlpha(MasteryPalette.GOLD_DARK, 0.12F + 0.18F * effectScale),
                    0.0D, Math.PI * 2.0D);
        }

        // 中心的装饰环：一道慢慢转的缺口环，让正中间不至于是一片空地
        float base = (float) (this.sourceRingRadius * this.zoom * 0.42D);
        if (this.sourceRingRadius > 0.0D && base >= 24.0F
                && !isOffScreen(cx, cy, base + 30.0F)) {
            float ringWidth = Math.max(1.0F, 2.0F * (float) this.zoom);
            RenderUtil.ring(buffer, matrix, cx, cy, base - ringWidth, base + ringWidth,
                    RenderUtil.withAlpha(MasteryPalette.GOLD_DARK, (0.18F + 0.27F * effectScale)),
                    0.0D, Math.PI * 2.0D);
            double spin = now / 5200.0D;
            for (int i = 0; i < CENTER_ARCS; i++) {
                double start = spin + i * (Math.PI * 2.0D / CENTER_ARCS);
                RenderUtil.ring(buffer, matrix, cx, cy, base + ringWidth * 2.0F,
                        base + ringWidth * 4.0F,
                        RenderUtil.withAlpha(MasteryPalette.GOLD, (0.12F + 0.23F * effectScale)),
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
        float effectScale = effectScaleForFarZoom();
        // 自己满级是金光；从这个节点一路到中心全满（武器节点就是外圈 + 中圈 + 内圈三圈全满），
        // 换成白金——整套界面只用金色系，「完满」就是金色里最白最亮的那一档，不引入别的颜色
        boolean chain = chainMaxed(node, maxLevel);
        int bright = chain ? MasteryPalette.GOLD_WHITE : MasteryPalette.GOLD_BRIGHT;
        int soft = chain ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.GOLD;

        float breathe = 0.5F + 0.5F * (float) Math.sin(now / 700.0D + node.graphX * 0.01D);
        float auraSpread = 0.45F + 0.55F * effectScale;
        RenderUtil.polygonOutline(buffer, matrix,
                RenderUtil.hexagon(sx, sy, radius + (4.0F + breathe * 2.0F) * auraSpread),
                Math.max(1.0F, radius * (0.03F + 0.03F * effectScale)),
                RenderUtil.withAlpha(bright, (0.08F + (0.17F + 0.25F * breathe) * effectScale) * dim));
        RenderUtil.polygonOutline(buffer, matrix,
                RenderUtil.hexagon(sx, sy, radius + (9.0F + (1.0F - breathe) * 3.0F) * auraSpread),
                Math.max(1.0F, radius * (0.02F + 0.02F * effectScale)),
                RenderUtil.withAlpha(soft, (0.04F + (0.08F + 0.14F * (1.0F - breathe)) * effectScale) * dim));

        // 节点太小的时候星点会挤成一坨，直接省掉
        if (radius < 8.0F || effectScale < 0.45F) {
            return;
        }
        // 完满的节点多两颗星、转得稍快、外面再多一圈很淡的大环，隔着半屏也能和普通满级区分开
        int stars = chain ? MAXED_STARS + 2 : MAXED_STARS;
        double spin = now / (chain ? 1400.0D : 1800.0D) + node.graphY * 0.004D;
        float star = Math.max(1.0F, radius * (0.09F + 0.07F * effectScale));
        for (int i = 0; i < stars; i++) {
            double angle = spin + i * (Math.PI * 2.0D / stars);
            float orbit = radius + (4.0F + 3.0F * effectScale);
            float px = sx + (float) (Math.cos(angle) * orbit);
            float py = sy + (float) (Math.sin(angle) * orbit);
            RenderUtil.polygon(buffer, matrix, RenderUtil.hexagon(px, py, star),
                    RenderUtil.withAlpha(bright, (0.12F + (0.20F + 0.22F * breathe) * effectScale) * dim));
        }
        if (chain) {
            RenderUtil.polygonOutline(buffer, matrix,
                    RenderUtil.hexagon(sx, sy, radius + (10.0F + breathe * 2.0F) * auraSpread),
                    Math.max(1.0F, radius * (0.015F + 0.015F * effectScale)),
                    RenderUtil.withAlpha(MasteryPalette.GOLD_WHITE, (0.03F + (0.04F + 0.06F * breathe) * effectScale) * dim));
        }
    }

    /**
     * 从这个节点一路往中心走，沿途带等级的节点是不是全都满级了。
     *
     * <p>武器节点看的就是「外圈 + 中圈 + 内圈」三圈全满；类型节点看中圈 + 内圈；来源节点只看自己。
     * 只有已经确认自己满级的节点才会调到这里，所以这条链平均只多查一两次表。
     *
     * @param node     节点
     * @param maxLevel 等级上限
     * @return 整条链都满级返回 true
     */
    private boolean chainMaxed(MasteryNode node, int maxLevel) {
        for (MasteryNode current = node; current != null; current = current.parent) {
            if (current.hasMastery() && levelOf(current) < maxLevel) {
                return false;
            }
        }
        return true;
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
        float effectScale = effectScaleForFarZoom();
        if (effectScale < 0.35F) {
            return;
        }
        float dotSize = (float) Math.max(1.2D, 3.2D * this.zoom * (0.55F + 0.45F * effectScale));
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
                        RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT,
                                (0.04F + (0.11F + 0.50F * fade) * effectScale)));
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
        float effectScale = effectScaleForFarZoom();
        if (effectScale < 0.30F) {
            return;
        }
        float sx = (float) toScreenX(this.selected.graphX);
        float sy = (float) toScreenY(this.selected.graphY);
        float orbit = (float) screenRadius(this.selected) + (4.0F + 6.0F * effectScale);
        if (isOffScreen(sx, sy, orbit + 20.0F)) {
            return;
        }
        float dotSize = (float) Math.max(1.1D, 2.6D * this.zoom * (0.60F + 0.40F * effectScale));
        double spin = now / 2600.0D;
        for (int i = 0; i < ORBIT_DOTS; i++) {
            double angle = spin + i * (Math.PI * 2.0D / ORBIT_DOTS);
            float px = sx + (float) (Math.cos(angle) * orbit);
            float py = sy + (float) (Math.sin(angle) * orbit);
            // 每颗点自己再呼吸一下，整圈就不会像个死板的齿轮
            float breathe = 0.55F + 0.45F * (float) Math.sin(now / 420.0D + i);
            RenderUtil.polygon(buffer, matrix, RenderUtil.hexagon(px, py, dotSize * breathe),
                    RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT,
                            0.08F + (0.18F + 0.34F * breathe) * effectScale));
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

        // 缩到只剩两三个像素时，底板、进度、描边在屏幕上都糊成同一个点，
        // 只画一个实心六边形：几千个节点全在视野里的时候，这里少画三份就是少三分之二的顶点
        if (radius < TINY_NODE_RADIUS && node != this.selected && node.hoverAnim < 0.01F) {
            RenderUtil.polygon(buffer, matrix, hex, RenderUtil.withAlpha(
                    unlocked ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK, dim));
            return;
        }

        if (node == this.selected) {
            // 选中：缓慢呼吸的金色外环。最小缩放时收一点，免得一眼过去只剩特效在跳。
            float pulse = 0.5F + 0.5F * (float) Math.sin(now / 420.0D);
            float effectScale = effectScaleForFarZoom();
            RenderUtil.polygonOutline(buffer, matrix,
                    RenderUtil.hexagon(sx, sy, radius + (3.0F + 4.0F * effectScale)),
                    1.2F + 0.8F * effectScale,
                    RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT,
                            0.08F + (0.14F + 0.36F * pulse) * effectScale));
        }

        // 黑色底板不能固定向外扩 3px：图特别大、缩到全貌时节点本身只剩十来像素，
        // 固定 3px 就会占掉节点半径两三成，看起来像每个节点都套了一个黑轮胎。
        // 改成跟当前屏幕半径一起缩放，近景仍保留厚重层次，远景自动收细。
        float backplatePadding = nodeBackplatePadding(node, radius);
        RenderUtil.polygon(buffer, matrix,
                RenderUtil.hexagon(sx, sy, radius + backplatePadding),
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
        RenderUtil.polygonOutline(buffer, matrix, hex, nodeOutlineWidth(node, radius),
                RenderUtil.withAlpha(outline, dim));
    }

    /**
     * 计算节点黑色底板向外扩多少像素。
     *
     * <p>原来固定外扩 3px，在正常缩放下没问题；但武器特别多时整张图会缩得很小，
     * 3px 相对节点本体就会突然变得非常粗。这里直接按当前屏幕半径算，真正解决比例问题，
     * 而不是继续缩图标把黑边衬得更明显。中心总览稍厚，维持视觉重心；其余层更克制。
     *
     * @param node   节点
     * @param radius 当前节点屏幕半径
     * @return 黑色底板向外扩的像素数
     */
    private float nodeBackplatePadding(MasteryNode node, float radius) {
        return switch (node.type) {
            case INFO -> Mth.clamp(radius * 0.10F, 1.1F, 3.0F);
            case SOURCE -> Mth.clamp(radius * 0.068F, 0.58F, 2.1F);
            case TYPE -> Mth.clamp(radius * 0.062F, 0.50F, 1.9F);
            case WEAPON -> Mth.clamp(radius * 0.080F, 0.55F, 2.0F);
        };
    }

    /**
     * 计算节点金色描边宽度。
     *
     * <p>同样不能再用固定的 1.2px 下限，否则全貌视角里节点越小、描边占比反而越大。
     * 这里按层级给一个较低的安全下限，保证远景还能看出六边形轮廓，同时不会黑金边一起糊成粗圈。
     *
     * @param node   节点
     * @param radius 当前节点屏幕半径
     * @return 描边宽度（像素）
     */
    private float nodeOutlineWidth(MasteryNode node, float radius) {
        return switch (node.type) {
            case INFO -> Mth.clamp(radius * 0.09F, 0.85F, 2.5F);
            case SOURCE -> Mth.clamp(radius * 0.068F, 0.50F, 1.8F);
            case TYPE -> Mth.clamp(radius * 0.062F, 0.46F, 1.65F);
            case WEAPON -> Mth.clamp(radius * 0.080F, 0.45F, 1.6F);
        };
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
            float radius = (float) screenRadius(node) * (1.0F + node.hoverAnim * 0.14F);
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
            // 距离先算好再排：放在比较器里的话，每比较一次就要重算两个节点的屏幕坐标，
            // 节点一多（这个整合包里上千个）排一次就是几千次多余的坐标变换
            double centerX = baseX();
            double centerY = baseY();
            record Ranked(MasteryNode node, double distance) {
            }
            List<Ranked> ranked = new ArrayList<>(candidates.size());
            for (MasteryNode node : candidates) {
                double dx = toScreenX(node.graphX) - centerX;
                double dy = toScreenY(node.graphY) - centerY;
                ranked.add(new Ranked(node, dx * dx + dy * dy));
            }
            ranked.sort(Comparator.comparingDouble(Ranked::distance));
            candidates = new ArrayList<>(MAX_ICONS_PER_FRAME);
            for (int i = 0; i < MAX_ICONS_PER_FRAME; i++) {
                candidates.add(ranked.get(i).node());
            }
        }

        for (MasteryNode node : candidates) {
            WeaponEntry entry = node.iconEntryAt(now);
            if (entry == null) {
                continue;
            }
            ItemStack icon = entry.icon();
            ResourceLocation texture = entry.iconTexture();
            if (texture == null && icon.isEmpty()) {
                continue;
            }
            float appear = Mth.clamp(appearOf(node, now), 0.0F, 1.0F);
            float radius = (float) screenRadius(node) * (1.0F + node.hoverAnim * 0.14F);
            float iconRadius = radius * iconScaleFor(node, radius);
            float iconFill = iconFillFor(node, radius);
            float sx = (float) toScreenX(node.graphX);
            float sy = (float) toScreenY(node.graphY);
            if (texture != null) {
                // 贴图图标（法术）：方形贴图没有透明通道，整块贴上去是个方框，很突兀。
                // 裁成和节点同形的六边形，尺寸比节点小一圈留出金边，再往里裁掉一成边缘。
                // 这里不用先 flush：物品图标那条路每画完一个自己就会刷一次，
                // 轮到这里时 GuiGraphics 手上没有攒着的东西，白刷一次相当于每帧空转一百多遍
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(0.0F, 0.0F, 150.0F);
                RenderUtil.texturedHexagon(guiGraphics.pose().last().pose(), texture,
                        sx, sy, iconRadius * TEXTURE_ICON_RADIUS_FACTOR * iconFill * appear,
                        TEXTURE_ICON_CROP, appear);
                guiGraphics.pose().popPose();
                continue;
            }
            // 物品图标固定 16x16，按节点大小放大后居中
            float scale = iconRadius / 16.0F * 1.9F * iconFill * appear;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(sx - 8.0F * scale, sy - 8.0F * scale, 0.0F);
            guiGraphics.pose().scale(scale, scale, 1.0F);
            guiGraphics.renderItem(icon, 0, 0);
            guiGraphics.pose().popPose();
        }
    }

    /**
     * 外圈武器名称实际开始显示的缩放阈值。
     *
     * <p>上一版是达到 {@link #NAME_ZOOM} 才显示。现在按你的要求，把它提前到
     * 「原来显示名字之前的那一格滚轮倍率」：也就是旧阈值除以当前一格的缩放倍数。
     * 不同大小的专精树滚轮倍率不同，所以这里动态算，别再塞一个碰运气的魔法数字。
     *
     * @return 武器名开始显示的缩放
     */
    private double weaponNameZoomThreshold() {
        return Mth.clamp(NAME_ZOOM / zoomStep(), minZoom(), maxZoom());
    }

    /**
     * 中圈名字在字体坐标下能写多宽：整圈统一，按最挤的一对相邻节点之间的空隙算。
     *
     * @param scale 当前文字缩放
     * @return 宽度预算
     */
    private int typeLabelBudget(float scale) {
        if (Double.isInfinite(this.minTypeSpacing)) {
            return 170;
        }
        double room = this.minTypeSpacing * this.zoom - 10.0D;
        return (int) Math.floor(Math.max(0.0D, room) / Math.max(0.1F, scale));
    }

    /**
     * 这一帧中圈的名字显示不显示：整圈统一判断。
     *
     * <p>以前是每个节点各自看离邻居多远，武器多的类型分到的扇区宽、名字就出来了，武器少的几个挤在一起就不显示，
     * 同一圈上有的有名字有的没有。现在以最挤的那一对为准，缩放到位之后整圈一起出现，名字也截成一样长。
     *
     * @param scale 当前文字缩放
     * @return 显示返回 true
     */
    private boolean typeLabelsVisible(float scale) {
        return this.typeCount > 0 && typeLabelBudget(scale) >= TYPE_LABEL_MIN_BUDGET;
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
        boolean showLevelDetail = this.zoom >= LEVEL_DETAIL_ZOOM;
        boolean showTypeLabels = typeLabelsVisible(labelScale);
        double weaponNameThreshold = weaponNameZoomThreshold();
        boolean showWeaponNames = this.zoom >= weaponNameThreshold;
        int maxLevel = ClientMasteryCache.snapshot().maxLevel;

        guiGraphics.pose().pushPose();
        // 抬到物品渲染（z=150）之上
        guiGraphics.pose().translate(0.0F, 0.0F, 320.0F);

        // 悬停的那个留到最后画：它的名字放宽了预算、会横着压到邻居头上，
        // 要是按顺序画，排在后面的邻居的等级铭牌（带黑底）会盖掉它名字的后半截
        MasteryNode hoveredNode = this.hovered;
        for (MasteryNode node : this.nodes) {
            if (node == hoveredNode) {
                continue;
            }
            drawNodeOverlay(guiGraphics, node, now, labelScale, showLevelDetail,
                    showTypeLabels, showWeaponNames, maxLevel);
        }
        if (hoveredNode != null) {
            drawNodeOverlay(guiGraphics, hoveredNode, now, labelScale, showLevelDetail,
                    showTypeLabels, showWeaponNames, maxLevel);
        }
        guiGraphics.pose().popPose();
    }


    /**
     * 画一个节点的文字层（等级铭牌 + 名字）。
     *
     * @param guiGraphics     绘图上下文
     * @param node            节点
     * @param now             当前时刻
     * @param labelScale      字号缩放
     * @param showLevelDetail 是否显示「等级 / 上限」
     * @param showTypeLabels  中圈是否显示名字
     * @param showWeaponNames 外圈是否显示名字
     * @param maxLevel        等级上限
     */
    private void drawNodeOverlay(GuiGraphics guiGraphics, MasteryNode node, long now, float labelScale,
                                 boolean showLevelDetail, boolean showTypeLabels, boolean showWeaponNames,
                                 int maxLevel) {
        if (Mth.clamp(appearOf(node, now), 0.0F, 1.0F) < 0.9F) {
            return;
        }
        // 和六边形、图标用同一个放大系数：以前铭牌和名字不跟着放大，
        // 悬停时节点长大了、铭牌却原地不动，看起来像被节点吃掉了一截
        float radius = (float) screenRadius(node) * (1.0F + node.hoverAnim * 0.14F);
        float sx = (float) toScreenX(node.graphX);
        float sy = (float) toScreenY(node.graphY);
        if (isOffScreen(sx, sy, radius + 46.0F)) {
            return;
        }

        boolean focused = node == this.hovered || node == this.selected;
        // 外圈名字现在提前一格显示，因此不能再被旧的 5px 半径门槛二次卡住。
        // 低于新名字阈值时仍然保留旧门槛，避免全貌视角画成一片文字海。
        float minLabelRadius = switch (node.type) {
            case SOURCE, INFO, TYPE -> 0.0F;
            case WEAPON -> showWeaponNames ? 0.0F : LABEL_MIN_SCREEN_RADIUS;
        };
        if (radius < minLabelRadius && !focused) {
            return;
        }
        switch (node.type) {
            case INFO -> drawScaledLabel(guiGraphics, node.displayName().getString(),
                    sx, sy - 4.0F * labelScale, labelScale, MasteryPalette.GOLD_BRIGHT, 170);
            case SOURCE -> drawNodeText(guiGraphics, node, sx, sy, radius, labelScale,
                    true, showLevelDetail, maxLevel, MasteryPalette.TEXT);
            // 中圈：等级铭牌按整圈的平均间距统一缩放；名字整圈统一出现 / 隐藏；
            // 分支合计那行只在悬停 / 选中时出，免得挤成一团
            case TYPE -> drawNodeText(guiGraphics, node, sx, sy, radius, labelScale,
                    focused || showTypeLabels, showLevelDetail && focused, maxLevel,
                    focused ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT);
            case WEAPON -> drawNodeText(guiGraphics, node, sx, sy, radius, labelScale,
                    showWeaponNames || focused, showLevelDetail, maxLevel,
                    focused ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT);
        }
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
        boolean focused = node == this.hovered || node == this.selected;

        if (node.hasMastery() && level > 0) {
            String levelText = showDetail ? level + " / " + maxLevel : String.valueOf(level);
            float plaqueScale = levelPlaqueScale(node, scale, focused, levelText);
            if (plaqueScale > 0.0F) {
                below = drawLevelPlaque(guiGraphics, sx, sy, radius, plaqueScale, levelText);
            }
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
            drawScaledLabel(guiGraphics, node.displayName().getString(), sx, below + 2.0F, scale,
                    nameColor, labelBudget(node, scale, focused));
        }
    }

    /**
     * 计算当前节点的等级牌该用多大的字号。
     *
     * <p>问题不在于「等级牌存在」，而在于来源 / 类型节点挤在一起时，所有牌子都按同一个字号往外探，
     * 就很容易压住旁边的图标。这里按当前圈层的可用横向间距动态收缩：够宽就原样画，挤一点就缩小，
     * 再挤到看不清时直接不画。选中 / 悬停节点和外圈武器节点不做这个保守限制，避免把关键信息也收没了。
     *
     * @param node    节点
     * @param scale   原始字号缩放
     * @param focused 是否悬停或选中
     * @param text    等级文字
     * @return 实际字号缩放；返回 0 表示当前太挤，跳过等级牌
     */
    private float levelPlaqueScale(MasteryNode node, float scale, boolean focused, String text) {
        if (focused || node.type == MasteryNode.Type.INFO || node.type == MasteryNode.Type.WEAPON) {
            return scale;
        }
        double spacing;
        if (node.type == MasteryNode.Type.SOURCE) {
            double circumference = Math.PI * 2.0D * this.sourceRingRadius * this.zoom;
            spacing = circumference / Math.max(1, this.sourceCount) - 10.0D;
        } else if (node.type == MasteryNode.Type.TYPE) {
            double circumference = Math.PI * 2.0D * this.ringRadii[MasteryNode.Type.TYPE.ordinal()] * this.zoom;
            spacing = circumference / Math.max(1, this.typeCount) - 8.0D;
        } else {
            spacing = node.radius * 2.0D * 1.35D * this.zoom - 6.0D;
        }
        double safeWidth = Math.max(0.0D, spacing);
        double wantedWidth = (this.font.width(text) + 8.0D) * scale;
        if (wantedWidth <= safeWidth) {
            return scale;
        }
        float shrunk = (float) (scale * safeWidth / Math.max(1.0D, wantedWidth));
        // 小到这个程度就只剩一条糊掉的金边，不如不画，免得还挡旁边图标。
        if (shrunk < 0.46F) {
            return 0.0F;
        }
        return Math.min(scale, shrunk);
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
        // 铭牌更多地「坐」在节点内部，少往下面探，避免压到相邻节点的图标。
        float top = sy + radius - 5.6F * scale;
        guiGraphics.pose().translate(sx, top, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);

        int left = -boxWidth / 2;
        guiGraphics.fill(left - 1, -1, left + boxWidth + 1, boxHeight + 1, 0xFF0A0806);
        guiGraphics.fill(left, 0, left + boxWidth, boxHeight, 0xFF1E1810);
        guiGraphics.fill(left, 0, left + boxWidth, 1, MasteryPalette.GOLD);
        guiGraphics.fill(left, boxHeight - 1, left + boxWidth, boxHeight, MasteryPalette.GOLD_DARK);
        guiGraphics.drawCenteredString(this.font, text, 0, 2, MasteryPalette.GOLD_BRIGHT);

        guiGraphics.pose().popPose();
        return top + (boxHeight + 1) * scale;
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
        // 来源和中心一共没几个、隔得远，名字永远写全
        if (node.type == MasteryNode.Type.SOURCE || node.type == MasteryNode.Type.INFO) {
            return 170;
        }
        if (node.type == MasteryNode.Type.TYPE) {
            // 中圈整圈用同一个预算，名字截得一样长
            return Mth.clamp(typeLabelBudget(scale), 24, 170);
        }
        // 相邻武器的间距，和布局里用的间距系数保持一致
        double spacing = node.radius * 2.0D * 1.35D * this.zoom;
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
        // 搜索框固定占右端这么宽，顶栏的字一律不许越过它
        int headerLimit = this.width - SEARCH_WIDTH - 16;
        int cursor = 10;
        String title = trimToWidth(this.title.getString(), Math.max(20, headerLimit - 20 - cursor));
        guiGraphics.drawString(this.font, title, cursor, textY,
                MasteryPalette.GOLD_BRIGHT, false);
        cursor += this.font.width(title) + 10;

        double study = this.data.getStudyMultiplier();
        if (Math.abs(study - 1.0D) > 1.0E-6D) {
            // 只在不是 1 倍时才占位置：默认值挂在标题旁边纯属噪音
            Component label = Component.translatable("gui.albionmastery.header.study",
                    formatMultiplier(study));
            cursor = drawHeaderChip(guiGraphics, label, cursor, textY,
                    study > 1.0D ? MasteryPalette.GOLD : MasteryPalette.WARN, headerLimit);
        }

        Progress progress = frameProgress();
        int maxLevel = ClientMasteryCache.snapshot().maxLevel;
        long ceiling = (long) progress.total() * Math.max(1, maxLevel);
        double ratio = ceiling <= 0L ? 0.0D : (double) progress.levelSum() / ceiling;
        drawHeaderChip(guiGraphics, Component.translatable("gui.albionmastery.header.progress",
                progress.levelSum(), ceiling,
                String.format(Locale.ROOT, "%.1f%%", ratio * 100.0D)),
                cursor, textY, MasteryPalette.TEXT, headerLimit);

        // 提示压到顶栏下面，字号 0.75，够看又不抢地方。看别人时换一句：他的特化只能看不能点
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(10.0F, HEADER_HEIGHT + 4.0F, 0.0F);
        guiGraphics.pose().scale(0.75F, 0.75F, 1.0F);
        guiGraphics.drawString(this.font, Component.translatable(this.viewOnly
                        ? "gui.albionmastery.hint_viewing" : "gui.albionmastery.hint"),
                0, 0, MasteryPalette.TEXT_DIM, false);
        guiGraphics.pose().popPose();

        if (this.sources.isEmpty()) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.albionmastery.no_weapon"),
                    this.width / 2, this.height / 2 - 4, MasteryPalette.TEXT_DIM);
        } else if (!this.viewOnly && !ClientMasteryCache.isSynced()) {
            // 别人的数据是整份拿来的，不存在「还没同步」这回事
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
     * @param limit       右边界：画出去会压到搜索框，放不下就整块不画
     * @return 下一段的起始 X
     */
    private int drawHeaderChip(GuiGraphics guiGraphics, Component text, int x, int y, int color, int limit) {
        int textX = x + this.font.width("\u00B7") + 6;
        // 放不下就整块不画：顶栏和搜索框都在 z=600，搜索框底色只是半透明，压上去就是两层字叠在一起。
        // 排行榜那边早就这么做了，罗盘这边漏了
        if (textX + this.font.width(text) > limit) {
            return x;
        }
        guiGraphics.drawString(this.font, "\u00B7", x, y, MasteryPalette.GOLD_DARK, false);
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
            this.specButton = null;
            this.adminButtons.clear();
            this.infoTabHits.clear();
            this.panelViewTop = 0;
            this.panelViewBottom = 0;
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
        this.panelViewTop = top + 1;
        this.panelViewBottom = bottom - 1;
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
        this.specButton = null;
        this.adminButtons.clear();
        this.infoTabHits.clear();

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
        if (isAdminView()) {
            this.panelBottom = drawAdminBlock(guiGraphics, this.selected, x, this.panelBottom, width);
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

    /**
     * 总览面板。
     *
     * <p>以前是一长条：进度、加成规则、经验倍率、维度倍率、用法、玩法教程全堆在一列里滚，
     * 光教程就四大段，打开界面第一眼看到的就是一屏密字。现在分成三页，
     * 想看数字的看「进度」，想查倍率的看「规则」，第一次玩的看「玩法」，每页都不用怎么滚。
     *
     * @param guiGraphics 绘图上下文
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 内容底边
     */
    private int drawInfoPanel(GuiGraphics guiGraphics, int x, int y, int width) {
        int cursor = drawTitle(guiGraphics, Component.translatable("gui.albionmastery.panel.info_title"),
                null, x, y, width);
        cursor = drawInfoTabs(guiGraphics, x, cursor, width);
        return switch (infoTab) {
            case RULES -> drawInfoRules(guiGraphics, x, cursor, width);
            case HOWTO -> drawInfoHowto(guiGraphics, x, cursor, width);
            default -> drawInfoProgress(guiGraphics, x, cursor, width);
        };
    }

    /**
     * 总览面板顶部的三个分页按钮。
     *
     * @param guiGraphics 绘图上下文
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 结束后的 Y
     */
    private int drawInfoTabs(GuiGraphics guiGraphics, int x, int y, int width) {
        this.infoTabHits.clear();
        InfoTab[] tabs = InfoTab.values();
        int height = 14;
        int gap = 2;
        int each = (width - gap * (tabs.length - 1)) / tabs.length;
        int left = x;
        for (int i = 0; i < tabs.length; i++) {
            InfoTab tab = tabs[i];
            // 最后一个补齐余数，三等分除不尽时右边不会缺一两个像素
            int right = i == tabs.length - 1 ? x + width : left + each;
            boolean active = tab == infoTab;
            guiGraphics.fill(left, y, right, y + height, active ? 0xFF241B0D : 0x60120E08);
            guiGraphics.fill(left, y + height - 1, right, y + height,
                    active ? MasteryPalette.GOLD : MasteryPalette.EDGE);
            guiGraphics.drawCenteredString(this.font, Component.translatable(tab.key()),
                    (left + right) / 2, y + (height - 8) / 2,
                    active ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT_DIM);
            this.infoTabHits.add(new InfoTabHit(tab, left, right, y, y + height));
            left = right + gap;
        }
        return y + height + 6;
    }

    /**
     * 「进度」页：只有数字，不讲道理。
     *
     * @param guiGraphics 绘图上下文
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 内容底边
     */
    private int drawInfoProgress(GuiGraphics guiGraphics, int x, int y, int width) {
        Progress progress = frameProgress();
        int cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.info_progress"),
                x, y, width);
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
        return drawStudyRow(guiGraphics, x, cursor, width);
    }

    /**
     * 「规则」页：加成、升级经验、维度倍率这些查得到的数。
     *
     * @param guiGraphics 绘图上下文
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 内容底边
     */
    private int drawInfoRules(GuiGraphics guiGraphics, int x, int y, int width) {
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        int cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.info_rules"),
                x, y, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_max_level"),
                Component.literal(String.valueOf(snapshot.maxLevel)), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_overall"),
                Component.literal(perLevel(snapshot.overallDamagePerLevel)), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_type"),
                Component.literal(perLevel(snapshot.typeDamagePerLevel)), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_self"),
                Component.literal(perLevel(snapshot.weaponDamagePerLevel)), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_cross"),
                Component.literal(perLevel(snapshot.weaponCrossDamagePerLevel)), x, cursor, width) + 8;

        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.info_grind"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grind_weapon"),
                Component.literal("\u00D71"), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grind_type"),
                Component.literal("\u00D7" + trimNumber(snapshot.typeXpMultiplier)), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grind_overall"),
                Component.literal("\u00D7" + trimNumber(snapshot.overallXpMultiplier)), x, cursor, width) + 8;

        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.info_dimension"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_dim_nether"),
                Component.literal("\u00D7" + trimNumber(snapshot.netherXpMultiplier))
                        .withStyle(ChatFormatting.GOLD), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_dim_end"),
                Component.literal("\u00D7" + trimNumber(snapshot.endXpMultiplier))
                        .withStyle(ChatFormatting.GOLD), x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_dim_modded"),
                Component.literal("\u00D7" + trimNumber(snapshot.moddedDimensionXpMultiplier))
                        .withStyle(ChatFormatting.GOLD), x, cursor, width) + 8;

        return drawWrapped(guiGraphics, Component.translatable("gui.albionmastery.panel.info_usage"),
                x, cursor, width, MasteryPalette.TEXT_DIM);
    }

    /**
     * 「玩法」页：六条，每条一个小标题加一句话。
     *
     * <p>整段整段的说明看着累，眼睛没有落脚的地方。拆成「标题一行、正文一行」之后，
     * 会玩的人扫标题就能跳过，第一次玩的人也知道每条在讲哪件事。
     *
     * @param guiGraphics 绘图上下文
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 内容底边
     */
    private int drawInfoHowto(GuiGraphics guiGraphics, int x, int y, int width) {
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        int cursor = y;
        for (int i = 1; i <= 6; i++) {
            Component body = i == 6
                    ? Component.translatable("gui.albionmastery.panel.play_6_d",
                            snapshot.specSlot1Percent, snapshot.specSlot2Percent)
                    : Component.translatable("gui.albionmastery.panel.play_" + i + "_d");
            cursor = drawHowtoItem(guiGraphics, i,
                    Component.translatable("gui.albionmastery.panel.play_" + i), body, x, cursor, width);
        }
        return cursor - 8;
    }

    /**
     * 「玩法」里的一条。
     *
     * @param guiGraphics 绘图上下文
     * @param index       序号
     * @param title       小标题
     * @param body        正文
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 下一条的 Y
     */
    private int drawHowtoItem(GuiGraphics guiGraphics, int index, Component title, Component body,
                              int x, int y, int width) {
        int indent = 11;
        guiGraphics.drawString(this.font, Component.literal(String.valueOf(index)),
                x, y, MasteryPalette.GOLD, false);
        guiGraphics.drawString(this.font, title, x + indent, y, MasteryPalette.TEXT, false);
        int cursor = drawWrapped(guiGraphics, body, x + indent, y + LINE + 1,
                width - indent, MasteryPalette.TEXT_DIM);
        return cursor + 8;
    }

    private int drawSourcePanel(GuiGraphics guiGraphics, int x, int y, int width) {
        WeaponSource source = this.selected == null ? null : this.selected.source;
        if (source == null) {
            return y;
        }
        MasteryData data = this.data;
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        int level = data.getOverallLevel(source.overallKey());
        // 服务端结算时每一层加成都会再乘一道来源倍率（见 MasteryHelper.computeBonus），
        // 面板不乘的话，服主把某个来源调到 20 倍之后，这里写的数和实际伤害差 20 倍
        double sourceMultiplier = snapshot.masteryDamageMultiplier(source.id());

        int cursor = drawTitle(guiGraphics, source.displayName(),
                Component.translatable("gui.albionmastery.panel.source_tag"), x, y, width);
        cursor = drawLevelBlock(guiGraphics, x, cursor, width, level,
                data.getOverallXp(source.overallKey()), snapshot.overallCurve(), snapshot.maxLevel) + 8;

        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.section_effect"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grants_all"),
                Component.literal(formatPercent(level * snapshot.overallDamagePerLevel * sourceMultiplier))
                        .withStyle(ChatFormatting.GOLD), x, cursor, width) + 6;

        // 只有一个类型的来源不画类型节点（比如近战附属），它的等级就并到这里显示
        if (source.types().size() == 1) {
            WeaponTypeGroup only = source.types().get(0);
            int typeLevel = data.getTypeLevel(only.key());
            cursor = drawRow(guiGraphics, only.displayName(),
                    Component.literal("Lv." + typeLevel), x, cursor, width);
            cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grants_type"),
                    Component.literal(formatPercent(typeLevel * snapshot.typeDamagePerLevel
                                    * sourceMultiplier))
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
            cursor = drawSection(guiGraphics, SpecTerms.of(source.id(), "page"), x, cursor + 6, width);
            cursor = drawWrapped(guiGraphics,
                    Component.translatable("gui.albionmastery.panel.spec_in_type",
                            SpecTerms.of(source.id(), "page")),
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
        double multiplier = this.data.getStudyMultiplier();
        boolean boosted = Math.abs(multiplier - 1.0D) > 1.0E-6D;
        Component value = Component.literal("\u00D7" + formatMultiplier(multiplier));

        if (boosted) {
            // 加成用金底，惩罚（小于 1）用橙底，两种情况玩家都该注意到
            int tint = multiplier > 1.0D ? 0x30E8C25A : 0x30E08A3C;
            guiGraphics.fill(x - 3, y - 2, x + width + 3, y + LINE + 1, tint);
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
     * 画类型 / 武器面板底部的「专精特化」摘要：两个槽现在装的是什么、合起来的效果，
     * 再加一个进特化页面的按钮。
     *
     * <p>完整的特化列表放在单独的 {@link SpecializationScreen} 里：枪械二十几个特化塞在这条
     * 186 像素宽的面板里，找一个要滚好几屏，也看不出装上之后合起来是什么效果。
     *
     * @param guiGraphics 绘图上下文
     * @param typeKey     武器类型键
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
        MasteryData data = this.data;
        String provider = WeaponKeys.providerOf(typeKey);
        int cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.spec_section_type",
                SpecTerms.of(provider, "page")), x, y, width);

        // 两栏各自一行状态：解锁了显示当前选的，没解锁显示门槛
        int typeLevel = data.getTypeLevel(typeKey);
        int overallLevel = provider == null ? 0 : data.getOverallLevel(WeaponKeys.overallKey(provider));
        boolean anyUnlocked = false;
        for (int slot = 1; slot <= WeaponKeys.SPEC_SLOTS; slot++) {
            int need = snapshot.specSlotLevel(slot);
            boolean unlocked = typeLevel >= need && overallLevel >= need;
            anyUnlocked |= unlocked;
            Specialization chosenSpec = Specialization.byKey(data.getSpecialization(typeKey, slot));
            Component value = !unlocked
                    ? Component.translatable("gui.albionmastery.panel.spec_slot_locked", need)
                            .withStyle(ChatFormatting.GRAY)
                    : Component.translatable(chosenSpec == null || chosenSpec.isStandard()
                            ? "spec.albionmastery." + provider + ".standard"
                            : chosenSpec.nameKey()).withStyle(ChatFormatting.GOLD);
            cursor = drawRow(guiGraphics, SpecTerms.slot(provider, slot), value, x, cursor, width);
        }
        if (!anyUnlocked) {
            cursor = drawSpecLocked(guiGraphics, data, typeKey, snapshot, x, cursor + 2, width);
        } else {
            // 两槽叠加之后的实际效果，和特化页面左栏的「合计效果」一致
            SpecDescriber.Lines described = SpecDescriber.describe(
                    SpecializationEffects.forData(data, typeKey, snapshot), provider);
            cursor += 2;
            for (Component gain : described.gains()) {
                cursor = drawSpecLine(guiGraphics, "+", gain, MasteryPalette.PROGRESS, x, cursor, width);
            }
            for (Component cost : described.costs()) {
                cursor = drawSpecLine(guiGraphics, "\u2212", cost, MasteryPalette.WARN, x, cursor, width);
            }
        }
        // 外圈的独立配置优先于这里：有几件单独配过的，提一句，免得玩家改了通用配置发现某把枪没变
        int overrides = SpecializationScreen.countOverrides(data, typeKey);
        if (overrides > 0) {
            cursor = drawWrapped(guiGraphics, Component.translatable("gui.albionmastery.panel.spec_overrides",
                    overrides), x, cursor + 3, width, MasteryPalette.TEXT_DIM);
        }
        return drawSpecButton(guiGraphics, typeKey, null, x, cursor + 6, width);
    }

    /**
     * 画「特化配置」按钮，点了进特化页面。按钮位置按内容坐标记下，点击时再换算滚动。
     *
     * @param guiGraphics 绘图上下文
     * @param typeKey     武器类型键
     * @param x           起始 X
     * @param y           起始 Y（内容坐标）
     * @param width       宽度
     * @return 结束后的 Y
     */
    private int drawSpecButton(GuiGraphics guiGraphics, String typeKey, @Nullable WeaponEntry weapon,
                               int x, int y, int width) {
        int height = 16;
        double contentY = this.mouseYNow + this.panelScroll;
        boolean hover = isInPanel(this.mouseXNow, this.mouseYNow) && this.mouseXNow >= x && this.mouseXNow < x + width
                && contentY >= y && contentY < y + height;
        guiGraphics.fill(x, y, x + width, y + height, hover ? 0xF01E1810 : 0xC0130F09);
        int border = hover ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK;
        guiGraphics.fill(x, y, x + width, y + 1, border);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, border);
        guiGraphics.fill(x, y, x + 1, y + height, border);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, border);
        Component label = Component.translatable(weapon == null
                        ? (this.viewOnly ? "gui.albionmastery.panel.spec_open_view" : "gui.albionmastery.panel.spec_open")
                        : (this.viewOnly ? "gui.albionmastery.panel.spec_open_weapon_view"
                                : "gui.albionmastery.panel.spec_open_weapon"),
                SpecTerms.of(WeaponKeys.providerOf(typeKey), "page"));
        guiGraphics.drawCenteredString(this.font, label, x + width / 2, y + (height - 8) / 2,
                hover ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT);
        this.specButton = new SpecButton(typeKey, weapon, y, height);
        return y + height;
    }

    /**
     * 自己有管理员权限（单人开了作弊也算）、而且看的是自己的罗盘时，面板底下多一块管理区。
     *
     * <p>这里只决定画不画；真正的权限由服务端对每个包重新判。
     *
     * @return 是否显示管理区
     */
    private boolean isAdminView() {
        LocalPlayer player = Minecraft.getInstance().player;
        return !this.viewOnly && player != null && player.hasPermissions(C2SAdminActionPacket.PERMISSION_LEVEL);
    }

    /**
     * 面板底下的管理区：按节点层级给不同的按钮，一律只改自己。
     *
     * <ul>
     *     <li>中心：全部满级、全部清零、清除特化冷却。</li>
     *     <li>来源 / 类型：本节点满级、清零、±10 级，外加连同下级一起满级或清零。</li>
     *     <li>武器：满级、清零、±10 级，外加拿一把。</li>
     * </ul>
     * 按住 Shift 时 ±10 变成 ±1。清零要点两下确认。
     */
    private int drawAdminBlock(GuiGraphics guiGraphics, MasteryNode node, int x, int y, int width) {
        int cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.admin.section")
                .withStyle(style -> style.withColor(MasteryPalette.WARN & 0xFFFFFF)), x, y + 10, width);
        cursor = drawWrapped(guiGraphics, Component.translatable("gui.albionmastery.admin.hint"),
                x, cursor, width, MasteryPalette.TEXT_DIM) + 3;
        C2SAdminActionPacket.Action set = C2SAdminActionPacket.Action.SET_LEVEL;
        C2SAdminActionPacket.Action adjust = C2SAdminActionPacket.Action.ADJUST_LEVEL;
        C2SAdminActionPacket.Action subtree = C2SAdminActionPacket.Action.SET_SUBTREE;
        int step = Screen.hasShiftDown() ? 1 : 10;
        switch (node.type) {
            case INFO -> {
                cursor = drawAdminRow(guiGraphics, x, cursor, width,
                        admin("max_all", "max_all", C2SAdminActionPacket.Action.SET_ALL, "", "", -1, true),
                        admin("wipe_all", "wipe_all", C2SAdminActionPacket.Action.SET_ALL, "", "", 0, true));
                cursor = drawAdminRow(guiGraphics, x, cursor, width,
                        admin("cooldown", "cooldown", C2SAdminActionPacket.Action.CLEAR_COOLDOWN, "", "", 0, false));
            }
            case SOURCE, TYPE -> {
                String key = node.type == MasteryNode.Type.SOURCE ? node.source.overallKey() : node.typeGroup.key();
                String tree = node.type == MasteryNode.Type.SOURCE ? "source" : "type";
                cursor = drawAdminRow(guiGraphics, x, cursor, width,
                        admin("max", "max", set, key, "", -1, false),
                        admin("wipe", "wipe", set, key, "", 0, true),
                        adminStep(-step, adjust, key, ""),
                        adminStep(step, adjust, key, ""));
                cursor = drawAdminRow(guiGraphics, x, cursor, width,
                        admin("max_" + tree, "max_" + tree, subtree, key, "", -1, true),
                        admin("wipe_" + tree, "wipe_" + tree, subtree, key, "", 0, true));
            }
            case WEAPON -> {
                WeaponEntry weapon = node.weapon;
                cursor = drawAdminRow(guiGraphics, x, cursor, width,
                        admin("max", "max", set, weapon.key(), weapon.typeKey(), -1, false),
                        admin("wipe", "wipe", set, weapon.key(), weapon.typeKey(), 0, true),
                        adminStep(-step, adjust, weapon.key(), weapon.typeKey()),
                        adminStep(step, adjust, weapon.key(), weapon.typeKey()));
                String giveKey = "gui.albionmastery.admin.give." + WeaponKeys.providerOf(weapon.key());
                cursor = drawAdminRow(guiGraphics, x, cursor, width,
                        new AdminAction("give", Component.translatable(I18n.exists(giveKey)
                                ? giveKey : "gui.albionmastery.admin.give.default"),
                                C2SAdminActionPacket.Action.GIVE_WEAPON, weapon.key(), weapon.typeKey(), 0, false));
            }
            default -> {
            }
        }
        return cursor;
    }

    /**
     * 二次确认认的是「哪个节点上的哪个按钮」：只记按钮的话，在 A 类型上点一下、换到 B 类型再点，B 会被直接清零。
     */
    private static String armKey(AdminAction action) {
        return action.id() + "|" + action.key();
    }

    private static AdminAction admin(String id, String labelKey, C2SAdminActionPacket.Action action, String key,
                                     String typeKey, int value, boolean confirm) {
        return new AdminAction(id, Component.translatable("gui.albionmastery.admin." + labelKey), action, key,
                typeKey, value, confirm);
    }

    private static AdminAction adminStep(int delta, C2SAdminActionPacket.Action action, String key, String typeKey) {
        return new AdminAction(delta < 0 ? "minus" : "plus",
                Component.literal((delta < 0 ? "\u2212" : "+") + Math.abs(delta)), action, key, typeKey, delta, false);
    }

    /**
     * 一排等宽的管理按钮。
     *
     * @return 这一排的底边
     */
    private int drawAdminRow(GuiGraphics guiGraphics, int x, int y, int width, AdminAction... actions) {
        int gap = 3;
        int height = 14;
        int buttonWidth = (width - gap * (actions.length - 1)) / actions.length;
        double contentY = this.mouseYNow + this.panelScroll;
        long now = Util.getMillis();
        for (int i = 0; i < actions.length; i++) {
            AdminAction action = actions[i];
            int left = x + i * (buttonWidth + gap);
            int right = i == actions.length - 1 ? x + width : left + buttonWidth;
            boolean hover = isInPanel(this.mouseXNow, this.mouseYNow) && this.mouseXNow >= left && this.mouseXNow < right
                    && contentY >= y && contentY < y + height;
            boolean armed = armKey(action).equals(this.armedAdmin) && now < this.armedUntilMs;
            guiGraphics.fill(left, y, right, y + height, armed ? 0x60E08A3C : hover ? 0xF01E1810 : 0xC0130F09);
            int border = armed || (action.confirm() && hover) ? MasteryPalette.WARN
                    : hover ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK;
            guiGraphics.fill(left, y, right, y + 1, border);
            guiGraphics.fill(left, y + height - 1, right, y + height, border);
            guiGraphics.fill(left, y, left + 1, y + height, border);
            guiGraphics.fill(right - 1, y, right, y + height, border);
            Component label = armed ? Component.translatable("gui.albionmastery.admin.confirm") : action.label();
            String text = this.font.plainSubstrByWidth(label.getString(), right - left - 4);
            guiGraphics.drawCenteredString(this.font, text, (left + right) / 2, y + (height - 8) / 2,
                    armed ? MasteryPalette.GOLD_WHITE : hover ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT);
            this.adminButtons.add(new AdminButton(action, left, right, y, height));
        }
        return y + height + 3;
    }

    /**
     * 点了管理按钮：清零类的第一下只是「上膛」，三秒内再点一下才发出去。
     *
     * @return 点中了返回 true
     */
    private boolean clickAdmin(double mouseX, double contentY) {
        for (AdminButton button : this.adminButtons) {
            if (mouseX < button.left() || mouseX >= button.right()
                    || contentY < button.top() || contentY >= button.top() + button.height()) {
                continue;
            }
            AdminAction action = button.action();
            long now = Util.getMillis();
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            if (action.confirm() && !(armKey(action).equals(this.armedAdmin) && now < this.armedUntilMs)) {
                this.armedAdmin = armKey(action);
                this.armedUntilMs = now + 3000L;
                return true;
            }
            this.armedAdmin = null;
            NetworkHandler.sendToServer(new C2SAdminActionPacket(action.action(), action.key(), action.typeKey(),
                    action.value()));
            return true;
        }
        return false;
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
        int need = snapshot.specSlotLevel(1);
        String providerId = WeaponKeys.providerOf(typeKey);
        int overallLevel = providerId == null ? 0
                : data.getOverallLevel(WeaponKeys.overallKey(providerId));
        int typeLevel = data.getTypeLevel(typeKey);

        int cursor = drawWrapped(guiGraphics,
                Component.translatable("gui.albionmastery.panel.spec_locked", SpecTerms.slot(providerId, 1)),
                x, y, width, MasteryPalette.TEXT_DIM) + 2;
        // 内圈和中圈都得到门槛：分别画一根条，缺哪条一眼就看出来
        cursor = drawSpecRequirement(guiGraphics, "gui.albionmastery.panel.spec_need_overall",
                overallLevel, need, x, cursor, width);
        return drawSpecRequirement(guiGraphics, "gui.albionmastery.panel.spec_need_type",
                typeLevel, need, x, cursor, width);
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
        MasteryData data = this.data;
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        int level = data.getTypeLevel(group.key());
        // 同上：这一层加成在结算时也要乘来源倍率
        double sourceMultiplier = snapshot.masteryDamageMultiplier(WeaponKeys.providerOf(group.key()));

        int cursor = drawTitle(guiGraphics, group.displayName(),
                Component.translatable("gui.albionmastery.panel.type_tag"), x, y, width);
        cursor = drawLevelBlock(guiGraphics, x, cursor, width, level,
                data.getTypeXp(group.key()), snapshot.typeCurve(), snapshot.maxLevel) + 8;

        cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.panel.section_effect"),
                x, cursor, width);
        cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_grants_type"),
                Component.literal(formatPercent(level * snapshot.typeDamagePerLevel * sourceMultiplier))
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
                        + formatPercent(totalLevels * snapshot.weaponCrossDamagePerLevel
                                * sourceMultiplier)),
                x, cursor, width);

        // 特化挂在类型这一层：同样是枪，步枪和霰弹枪该走的路子完全不同
        return drawSpecializationBlock(guiGraphics, group.key(), x, cursor + 6, width);
    }

    private int drawWeaponPanel(GuiGraphics guiGraphics, int x, int y, int width) {
        WeaponEntry weapon = this.selected == null ? null : this.selected.weapon;
        if (weapon == null) {
            return y;
        }
        MasteryData data = this.data;
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
        // 特化的伤害倍率不算在「专精加成」里，单独一行，再给一行两者相乘的最终倍率——
        // 重型弹药那 3 倍不在这里显示的话，玩家会以为没生效
        SpecializationValues specValues = SpecializationEffects.forData(data, resolved, snapshot);
        if (specValues.damage() != 1.0D) {
            cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_spec_damage",
                            SpecTerms.of(WeaponKeys.providerOf(weapon.typeKey()), "spec")),
                    Component.literal("\u00d7" + formatMultiplier(specValues.damage())).withStyle(ChatFormatting.GOLD),
                    x, cursor, width);
            cursor = drawRow(guiGraphics, Component.translatable("gui.albionmastery.panel.row_final_damage",
                            SpecTerms.of(WeaponKeys.providerOf(weapon.typeKey()), "spec")),
                    Component.literal("\u00d7" + formatMultiplier(bonus.multiplier() * specValues.damage()))
                            .withStyle(ChatFormatting.GOLD),
                    x, cursor, width);
        }
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
                Component.literal(formatPercent(level * snapshot.weaponCrossDamagePerLevel
                        * snapshot.masteryDamageMultiplier(WeaponKeys.providerOf(weapon.typeKey())))),
                x, cursor, width);

        cursor = drawSpecEffects(guiGraphics, weapon.typeKey(), weapon.key(), data, snapshot, x, cursor + 6, width);
        if (snapshot.enableSpecialization) {
            // 对着武器按 B 打开时选中的是武器节点，从这里直接进这件武器自己的特化页面
            cursor = drawSpecButton(guiGraphics, weapon.typeKey(), weapon, x, cursor + 6, width);
        }
        return cursor;
    }

    /**
     * 在武器面板里列出当前特化对这把武器的实际影响。
     *
     * <p>只列真正变了的那几项：一个特化通常只动两三个维度，把几十项全列出来、
     * 其中大半写着「×1」，等于让人自己去里面找哪个是重点。列的是两个槽叠加之后的实际数值，
     * 文本由 {@link SpecDescriber} 按数值生成，和特化卡片上的说法一致。
     *
     * @param guiGraphics 绘图上下文
     * @param typeKey     这把武器所属类型的键
     * @param weaponKey   武器键：有独立配置就按它显示
     * @param data        专精数据
     * @param snapshot    配置快照
     * @param x           起始 X
     * @param y           起始 Y
     * @param width       可用宽度
     * @return 结束后的 Y
     */
    private int drawSpecEffects(GuiGraphics guiGraphics, String typeKey, @Nullable String weaponKey,
                                MasteryData data, MasteryConfig.Snapshot snapshot, int x, int y, int width) {
        if (!snapshot.enableSpecialization) {
            return y;
        }
        String provider = WeaponKeys.providerOf(typeKey);
        // 先说清楚这件武器用的是哪一份：单独配置（外圈）优先，没有就跟随类型的通用配置（中圈）
        int cursor = drawSection(guiGraphics, SpecTerms.of(provider, "page"), x, y, width);
        boolean own = data.hasWeaponOverride(weaponKey);
        cursor = drawWrapped(guiGraphics, Component.translatable(own
                        ? "gui.albionmastery.panel.spec_mode_own" : "gui.albionmastery.panel.spec_mode_follow",
                ClientWeaponRegistry.typeName(typeKey)), x, cursor, width,
                own ? MasteryPalette.GOLD : MasteryPalette.TEXT_DIM) + 2;
        // 两个槽都生效时两个名字都列出来，否则玩家看不出叠加的第二个特化在不在起作用
        List<Specialization> active = new ArrayList<>(WeaponKeys.SPEC_SLOTS);
        Specialization applied = null;
        for (int slot = 1; slot <= WeaponKeys.SPEC_SLOTS; slot++) {
            Specialization spec = SpecializationEffects.activeSpec(data, typeKey, weaponKey, slot, snapshot);
            // 和前一槽同系的不生效（规则见 SpecializationEffects.forData），这里也不列
            if (spec != null && !spec.conflictsWith(applied)) {
                active.add(spec);
                applied = spec;
            }
        }
        if (active.isEmpty()) {
            return cursor;
        }
        SpecializationValues values = SpecializationEffects.forData(data, typeKey, weaponKey, snapshot);

        for (Specialization spec : active) {
            cursor = drawRow(guiGraphics, Component.translatable(spec.nameKey()),
                    Component.translatable("gui.albionmastery.panel.spec_active")
                            .withStyle(ChatFormatting.GOLD), x, cursor, width);
        }

        // 两槽叠加之后的实际数值，逐条列出（只列变了的）
        SpecDescriber.Lines described = SpecDescriber.describe(values, provider);
        for (Component gain : described.gains()) {
            cursor = drawSpecLine(guiGraphics, "+", gain, MasteryPalette.PROGRESS, x, cursor, width);
        }
        for (Component cost : described.costs()) {
            cursor = drawSpecLine(guiGraphics, "\u2212", cost, MasteryPalette.WARN, x, cursor, width);
        }
        return cursor;
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
        int cursor = y;
        // 用自己那套折行：原版的折法会把逗号句号甩到行首，整页看下来很累（见 TextWrap）
        for (String line : TextWrap.wrap(this.font, text.getString(), width)) {
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
        if (node.type == MasteryNode.Type.WEAPON && node.weapon != null && snapshot.enableSpecialization
                && this.data.hasWeaponOverride(node.weapon.key())) {
            lines.add(Component.translatable("gui.albionmastery.tooltip.spec_own",
                    SpecTerms.of(WeaponKeys.providerOf(node.weapon.key()), "page")).withStyle(ChatFormatting.GOLD));
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
        if (isOnSecondButton(mouseX, mouseY)) {
            clickSecondButton();
            return true;
        }
        if (this.searchBox != null && mouseY < HEADER_HEIGHT) {
            // 顶栏整条都不参与地图操作，免得点标题就把镜头拖跑了
            return this.searchBox.mouseClicked(mouseX, mouseY, button);
        }
        // 点到顶栏以外的地方就退出搜索框，和特化页、排行榜一致
        if (this.searchBox != null && this.searchBox.isFocused()) {
            this.searchBox.setFocused(false);
            setFocused(null);
        }
        if (isInPanel(mouseX, mouseY)) {
            return clickPanel(mouseX, mouseY);
        }

        MasteryNode node = pickNode(mouseX, mouseY);
        if (node != null) {
            long now = Util.getMillis();
            boolean doubleClick = node == this.lastClickedNode && now - this.lastClickMs < DOUBLE_CLICK_MS;
            this.lastClickedNode = node;
            this.lastClickMs = now;
            if (doubleClick) {
                // 双击：飞过去。来源 / 类型节点把整支收进屏幕，武器节点放到能看清图标，中心节点看全貌
                this.selected = node;
                updatePath();
                flyTo(node);
                return true;
            }
            // 整张图是全展开的，单击节点就只是选中它、在左侧弹出详情
            this.selected = node == this.selected ? null : node;
            updatePath();
            return true;
        }
        this.lastClickedNode = null;

        // 点空白处收起详情面板，同时开始拖动
        this.selected = null;
        updatePath();
        this.dragging = true;
        this.flying = false;
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
            this.flying = false;
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
        if (isInPanel(mouseX, mouseY) && this.panelScrollable) {
            this.panelScroll = Math.max(0.0D, this.panelScroll - delta * PANEL_SCROLL_STEP);
            return true;
        }
        this.flying = false;
        double step = zoomStep();
        double newZoom = Mth.clamp(this.targetZoom * (delta > 0.0D ? step : 1.0D / step),
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
            this.searchBox.keyPressed(keyCode, scanCode, modifiers);
            // 不管 EditBox 认不认这个键都吞掉：字母键它返回 false（字符是从 charTyped 进来的），
            // 放下去就会匹配到打开界面的 B 键，打拼音「bu」「ba」一按 b 界面就关了
            return true;
        }
        if (KeyBindings.OPEN_MASTERY.matches(keyCode, scanCode)) {
            // 按开界面那个键是「我不看了」：看别人时也直接整叠关掉，不回排行榜
            Minecraft.getInstance().setScreen(null);
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
     * @param mouseY 鼠标 Y
     * @return 落在面板上返回 true
     */
    private boolean isInPanel(double mouseX, double mouseY) {
        // 面板只占左边一截高度，上下露出来的仍然是地图：只判 X 的话那两块地图点不动、拖不动、滚轮也不缩放
        return this.selected != null && mouseX <= PANEL_WIDTH
                && mouseY >= this.panelViewTop - 1 && mouseY <= this.panelViewBottom;
    }

    /**
     * 处理落在面板里的点击：只认管理按钮和「特化配置」按钮，其余位置不做事。
     *
     * @param mouseX 鼠标 X
     * @param mouseY 鼠标 Y
     * @return 点中了按钮返回 true
     */
    private boolean clickPanel(double mouseX, double mouseY) {
        // 面板滚动时，超出可视区的按钮虽然被裁掉了，点击区还在；不先挡住的话，
        // 点面板下方的空白处会触发看不见的「全部满级」
        if (mouseY < this.panelViewTop || mouseY >= this.panelViewBottom) {
            return false;
        }
        // 画的时候用的是内容坐标，这里把鼠标位置换算回去
        double contentY = mouseY + this.panelScroll;
        for (InfoTabHit hit : this.infoTabHits) {
            if (mouseX >= hit.left() && mouseX < hit.right()
                    && contentY >= hit.top() && contentY < hit.bottom()) {
                if (infoTab != hit.tab()) {
                    infoTab = hit.tab();
                    this.panelScroll = 0.0D;
                }
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                return true;
            }
        }
        if (clickAdmin(mouseX, contentY)) {
            return true;
        }
        SpecButton button = this.specButton;
        if (button == null) {
            return false;
        }
        if (contentY < button.top() || contentY >= button.top() + button.height()) {
            return false;
        }
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        WeaponEntry weapon = button.weapon();
        Minecraft.getInstance().setScreen(new SpecializationScreen(this.data, button.typeKey(),
                ClientWeaponRegistry.typeName(button.typeKey()),
                weapon == null ? null : weapon.key(), weapon == null ? null : weapon.displayName(),
                this, this.viewOnly));
        return true;
    }

    @Nullable
    private MasteryNode pickNode(double mouseX, double mouseY) {
        // 面板、顶栏、左下两个按钮底下的节点不算：不然鼠标停在按钮上，底下节点的悬停提示会盖住按钮
        if (isInPanel(mouseX, mouseY) || mouseY < HEADER_HEIGHT
                || isOnButton(mouseX, mouseY) || isOnSecondButton(mouseX, mouseY)) {
            return null;
        }
        for (int i = this.nodes.size() - 1; i >= 0; i--) {
            MasteryNode node = this.nodes.get(i);
            double radius = screenRadius(node);
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


    private int levelOf(MasteryNode node) {
        if (node.cachedLevelStamp == this.frameStamp) {
            return node.cachedLevel;
        }
        String key = node.dataKey();
        int level = 0;
        if (key != null) {
            MasteryData data = this.data;
            level = switch (node.type) {
                case SOURCE -> data.getOverallLevel(key);
                case TYPE -> data.getTypeLevel(key);
                case WEAPON -> data.getWeaponLevel(key);
                default -> 0;
            };
        }
        node.cachedLevel = level;
        node.cachedLevelStamp = this.frameStamp;
        return level;
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

        MasteryData data = this.data;
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
        MasteryData data = this.data;
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
    private Progress collectProgress() {
        MasteryData data = this.data;
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
     * 面板里「特化配置」按钮的点击区。
     *
     * @param typeKey 点了要打开哪个类型的特化页面
     * @param top     内容坐标下的顶边
     * @param height  高度
     */
    /**
     * 总览面板的分页。
     *
     * <p>记成静态的：关掉界面再打开、点别的节点再回来，还停在刚才那一页，
     * 看完玩法的人不用每次都切回去。
     */
    private enum InfoTab {

        /** 进度数字。 */
        PROGRESS("gui.albionmastery.panel.tab_progress"),

        /** 加成与倍率。 */
        RULES("gui.albionmastery.panel.tab_rules"),

        /** 玩法说明。 */
        HOWTO("gui.albionmastery.panel.tab_howto");

        /** 分页名的翻译键。 */
        private final String key;

        InfoTab(String key) {
            this.key = key;
        }

        /**
         * @return 分页名的翻译键
         */
        String key() {
            return this.key;
        }
    }

    /** 一个分页按钮的点击区。 */
    private record InfoTabHit(InfoTab tab, int left, int right, int top, int bottom) {
    }

    private record SpecButton(String typeKey, @Nullable WeaponEntry weapon, int top, int height) {
    }

    /**
     * 一个管理操作。
     *
     * @param id      按钮标识，二次确认靠它认「是不是同一个按钮」
     * @param label   按钮文字
     * @param action  发给服务端的操作
     * @param key     目标节点的键
     * @param typeKey 武器所属的类型键，其余为空串
     * @param value   等级或增减量
     * @param confirm 是否要点两下确认
     */
    private record AdminAction(String id, Component label, C2SAdminActionPacket.Action action, String key,
                               String typeKey, int value, boolean confirm) {
    }

    /**
     * 上一帧画出来的一个管理按钮（横向是屏幕坐标，纵向是内容坐标）。
     */
    private record AdminButton(AdminAction action, int left, int right, int top, int height) {
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
