package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.client.ClientMasteryCache;
import com.flameforge.albionmastery.client.KeyBindings;
import com.flameforge.albionmastery.client.weapon.ClientWeaponRegistry;
import com.flameforge.albionmastery.client.weapon.WeaponEntry;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecRules;
import com.flameforge.albionmastery.config.SpecScreenLayout;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.spec.SpecPoints;
import com.flameforge.albionmastery.mastery.spec.SpecTerms;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.mixin.GuiOverlayAccessor;
import com.flameforge.albionmastery.network.C2SSelectSpecializationPacket;
import com.flameforge.albionmastery.network.C2SSpecProgressPacket;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一个武器类型（或一件武器的独立配置）的特化页面。
 *
 * <p>特化分普通版 Lv1～Lv5 和突破版 ★1～★5：普通版花来源的改装点升级、按来源共用；
 * 突破版要普通版满级，再花这个类型的突破点，只能装进突破槽。默认四个槽（普通、突破、普通、突破）。
 *
 * <p>同一套数据、按钮和请求有三种摆法，玩家在顶栏切换（写回客户端配置 {@code specScreenLayout}）：
 * <ul>
 *     <li><b>A</b>：左栏列出槽位、点数、合计效果，右边卡片两列；</li>
 *     <li><b>B</b>（默认）：槽位横排在顶上，左边窄栏放点数和合计效果，卡片三列；</li>
 *     <li><b>C</b>：左栏槽位，中栏只列名字和等级，右栏是选中那项的每级数值表、按钮和「装上之后和现在比」。
 *         窗口窄于 {@value #C_MIN_WIDTH} 像素时三栏放不下，按 B 显示（配置里存的值不变）。</li>
 * </ul>
 *
 * <p>操作：先点槽位选中它，再点卡片把特化装进去；再点一次已经装在这个槽里的卡片就是卸下，右键槽位也能卸下。
 * 卡片右下角的按钮升级 / 突破。卡片上的效果一项一行（名字靠左、数值右对齐，收益和代价之间一条细线），
 * 放不下只列前几项、末尾写「+N 项」，鼠标停在卡片上会弹出完整的每级数值表。
 * 鼠标停在卡片上时，左栏的合计效果换成「装进选中槽之后」的预览，变了的行亮起来，没了的行划掉。
 *
 * <p>点数、门槛、能不能装都在服务端重新判一遍；服务端的结果提示本来发在物品栏上方，被这个不透明的界面挡住了，
 * 这里在底部原样再显示一遍。看别人的罗盘时同样能打开，只能看不能点。
 *
 * <p>上手引导有三层，三种布局都有：
 * <ul>
 *     <li><b>引导条</b>：B 版在槽位条下面、A / C 版在顶栏下面，按选中的槽列出该走的几步（普通槽三步、突破槽四步，
 *         做完打勾、当前那步高亮），后面一句「下一步」按玩家现状生成，要点的按钮外面套一圈呼吸的金框；
 *         点那句话把列表滚到目标。能收起（写回客户端配置 {@code specGuideBar}）。</li>
 *     <li><b>分步遮罩</b>：第一次打开时压暗整页，依次照亮槽位、卡片、点数框、要突破的那张卡，各配一段说明，
 *         说明里的数字从 {@link SpecRules} 生成；看完记下版本号（{@code specGuideVersion}），顶栏「?」随时重看，
 *         重看时收起的引导条也重新打开。</li>
 *     <li><b>原地说明</b>：空槽写收什么、锁住的槽带进度条、点数框悬停列出点数来源、卡片角标「可升级 / 可突破」、
 *         重置按钮写退几点、底栏跟着选中的槽说怎么装。引导条开着时底栏和空槽不再重复引导条那句话。</li>
 * </ul>
 */
public class SpecializationScreen extends Screen {

    private static final int HEADER_HEIGHT = 20;
    /** 左栏底部的界面大小按钮；是界面的字段、不随 init 重建，改了档重新 init 后卡片还开着。 */
    private final ScaleKnob scaleKnob = new ScaleKnob(() -> init(Minecraft.getInstance(), this.width, this.height));
    private static final int MARGIN = 8;
    private static final int LINE = 10;
    private static final int PAD = 5;
    private static final int GAP = 6;
    private static final int SEARCH_HEIGHT = 14;
    private static final int CHIP_HEIGHT = 12;
    private static final int FOOTER_HEIGHT = 12;
    private static final int BACK_WIDTH = 52;
    private static final int BUTTON_HEIGHT = 12;
    private static final int SIDE_BUTTON_HEIGHT = 14;
    private static final int POINTS_BOX_HEIGHT = 24;
    private static final int SLOT_ROW_HEIGHT = 25;
    private static final int SLOT_TILE_HEIGHT = 42;
    private static final int LIST_ROW_HEIGHT = 13;
    /** C 版列表里两个分组之间的空隙（第一个分组上面不留）。 */
    private static final int LIST_GROUP_GAP = 5;
    /** 卡片上最多几行效果（含「+N 项」那一行）。 */
    private static final int CARD_EFFECT_LINES = 5;
    /** 名字和右对齐的数值之间至少空几像素。 */
    private static final int NAME_VALUE_GAP = 4;
    /** 窗口（GUI 像素）窄于这个宽度时，C 版的三栏放不下，按 B 显示。 */
    private static final int C_MIN_WIDTH = 560;
    private static final double SCROLL_STEP = 22.0D;
    /** 发出请求之后多久之内，底部显示服务端发来的结果提示。 */
    private static final long FEEDBACK_WINDOW_MS = 6000L;
    /** 重置按钮点第一下之后，多久之内再点一下才算确认。 */
    private static final long CONFIRM_WINDOW_MS = 3000L;

    /** 上手引导的版本：引导内容大改时 +1，看过旧版的玩家会再弹一次。 */
    private static final int GUIDE_VERSION = 1;
    /** 引导条高：1 框 + 1 空 + 12 步骤块 + 1 空 + 1 框。 */
    private static final int BAR_HEIGHT = 16;
    /** 步骤号 / 问号小方块，和一行字的格子一样高。 */
    private static final int BADGE = 8;
    /** 顶栏「?」按钮宽。 */
    private static final int QUESTION_WIDTH = 16;
    /** 分步遮罩说明框宽（窄窗口另算）。 */
    private static final int CALLOUT_WIDTH = 220;
    private static final int CALLOUT_NARROW_WIDTH = 196;
    /** 分步遮罩压暗的颜色（黑，约 62% 不透明）。 */
    private static final int OVERLAY = 0x9E000000;
    private static final int TOUR_STEPS = 4;
    /** 勾号（8×8，# 是点亮的像素）。 */
    private static final String[] CHECK = {
            "........",
            "......#.",
            ".....##.",
            ".#..##..",
            ".####...",
            "..##....",
            "........",
            "........"};

    private static final int TEXT_FAINT = 0xFF6F634C;
    private static final int CARD_EDGE = 0xFF2C2314;
    private static final int CHIP_EDGE = 0xFF3A2E18;
    /** 突破相关（突破槽、突破星、突破点）的颜色。 */
    private static final int BREAK = 0xFFFFB27A;
    private static final int DIVIDER = 0x4D6B5223;
    private static final int GREEN = 0xFF7FD06A;
    private static final int STRUCK = 0xFF5A5040;
    private static final String MINUS = "−";

    private final MasteryData data;
    private final String typeKey;
    private final String providerId;
    private final Component typeName;
    private final Screen parent;
    private final boolean viewOnly;
    /** 这个来源的特化（不含制式），按枚举顺序，也就是按分组排好的。 */
    private final List<Specialization> specs;

    /** 武器键：null 表示改的是类型的通用配置（中圈），否则是这件武器的独立配置（外圈）。 */
    @Nullable
    private final String weaponKey;

    @Nullable
    private EditBox searchBox;

    /** 当前只看哪一组；null 表示全部（只有 A、B 有分组筛选）。 */
    @Nullable
    private String familyFilter;

    /** 选中的槽位，1 起；0 表示还没定（第一次 init 时挑一个）。 */
    private int selectedSlot;

    /** C 版右栏正在看的特化。 */
    @Nullable
    private Specialization detailSpec;

    private double listScroll;
    private double listMaxScroll;
    private double sideScroll;
    private double sideMaxScroll;
    private double detailScroll;
    private double detailMaxScroll;

    /** 本帧鼠标停着的卡片（A、B）。 */
    @Nullable
    private Specialization hoveredSpec;

    /** 本帧要画的悬停面板，所有东西画完再画，压在最上面。 */
    @Nullable
    private Runnable pendingTooltip;

    /** 本帧的鼠标位置（走遮罩、鼠标在界面大小卡片上时挪到界面外），给「字被截断时悬停看全文」用。 */
    private int tipMouseX = -10000;
    private int tipMouseY = -10000;

    /** 本帧画出来的可点区域（屏幕坐标，已经扣过滚动和裁剪），点击时倒着找，后画的在上面。 */
    private final List<Hit> hits = new ArrayList<>();

    /** 最近一次发请求的时刻，用来决定要不要显示动作栏提示。 */
    private long lastRequestMs = -FEEDBACK_WINDOW_MS;

    /** 点了第一下、等确认的重置按钮（点数池的键），和确认的截止时刻。 */
    @Nullable
    private String armedPool;
    private long armedUntil;

    /** 卡片效果的缓存：特化 + 版本 + 等级 → 拆好的效果。快照换了整体作废。 */
    private final Map<String, SpecDescriber.Entries> entryCache = new HashMap<>();
    @Nullable
    private MasteryConfig.Snapshot entryCacheSnapshot;

    /** 本帧的版面。 */
    private Regions regions = new Regions(SpecScreenLayout.B, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** 分步遮罩走到第几步（0 起），-1 = 没在走。 */
    private int tourStep = -1;
    /** 打开页面时检查过要不要自动弹引导（缩放窗口也会走 init，只查一次）。 */
    private boolean tourChecked;
    /** 遮罩已经为哪一步把目标滚进视野。 */
    private int tourScrolledFor = -1;

    /** 本帧的引导状态（引导条开着或者在走遮罩时才算）。 */
    @Nullable
    private Guide guide;

    // 本帧记下的几块位置（屏幕坐标 x, y, 宽, 高），给引导条的金框、遮罩照亮和「点那句话滚过去」用
    @Nullable
    private int[] slotsRect;
    @Nullable
    private int[] pointsRect;
    @Nullable
    private int[] firstRowRect;
    @Nullable
    private int[] detailRect;
    @Nullable
    private int[] listClip;
    @Nullable
    private int[] detailClip;
    @Nullable
    private int[] sideClip;
    @Nullable
    private int[] modBoxRect;
    @Nullable
    private int[] breakBoxRect;
    private final Map<Specialization, int[]> cardRects = new HashMap<>();
    private final Map<Specialization, int[]> buttonRects = new HashMap<>();
    /** 卡片 / 列表行在列表内容里的 Y（不扣滚动），滚到目标时用。 */
    private final Map<Specialization, Integer> contentY = new HashMap<>();
    @Nullable
    private int[] detailProgressRect;
    @Nullable
    private int[] detailEquipRect;
    @Nullable
    private int[] selectedSlotRect;
    /** 点了引导条那句话、等下一帧滚过去的目标（筛掉了要先清筛选，位置下一帧才有）。 */
    @Nullable
    private Specialization pendingFocus;

    /**
     * @param data     要显示的专精数据（自己的是客户端缓存那一份，别人的是拿来的副本）
     * @param typeKey  武器类型键
     * @param typeName 类型显示名
     * @param parent   返回到哪个界面
     * @param viewOnly 是否只读
     */
    public SpecializationScreen(MasteryData data, String typeKey, Component typeName, Screen parent,
                                boolean viewOnly) {
        this(data, typeKey, typeName, null, null, parent, viewOnly);
    }

    /**
     * @param data       要显示的专精数据
     * @param typeKey    武器类型键
     * @param typeName   类型显示名
     * @param weaponKey  武器键；null 表示改类型的通用配置
     * @param weaponName 武器显示名，改通用配置时为 null
     * @param parent     返回到哪个界面
     * @param viewOnly   是否只读
     */
    public SpecializationScreen(MasteryData data, String typeKey, Component typeName, @Nullable String weaponKey,
                                @Nullable Component weaponName, Screen parent, boolean viewOnly) {
        super(Component.translatable("gui.albionmastery.spec_page.title",
                SpecTerms.of(WeaponKeys.providerOf(typeKey), "page"),
                weaponName != null ? weaponName : typeName));
        this.weaponKey = weaponKey;
        this.data = data;
        this.typeKey = typeKey;
        String provider = WeaponKeys.providerOf(typeKey);
        this.providerId = provider == null ? "" : provider;
        this.typeName = typeName;
        this.parent = parent;
        this.viewOnly = viewOnly;
        List<Specialization> list = new ArrayList<>();
        for (Specialization spec : Specialization.forSource(this.providerId)) {
            if (!spec.isStandard()) {
                list.add(spec);
            }
        }
        this.specs = List.copyOf(list);
    }

    @Override
    protected void init() {
        // 专精界面共用自己的界面缩放：先换档，宽高按换完的那一档重算（窗口改大小、改档也走这里）
        int[] scaled = ScreenScale.apply(this.minecraft);
        this.width = scaled[0];
        this.height = scaled[1];
        // 换语言、缩放窗口都会走 init，缓存的文字得重来
        this.entryCache.clear();
        if (this.selectedSlot == 0) {
            this.selectedSlot = initialSlot();
        }
        String previous = this.searchBox == null ? "" : this.searchBox.getValue();
        this.searchBox = new EditBox(this.font, 0, 0, 10, SEARCH_HEIGHT - 6,
                Component.translatable("gui.albionmastery.spec_page.search"));
        this.searchBox.setHint(Component.translatable("gui.albionmastery.spec_page.search")
                .withStyle(ChatFormatting.DARK_GRAY));
        this.searchBox.setBordered(false);
        this.searchBox.setTextColor(MasteryPalette.TEXT & 0xFFFFFF);
        this.searchBox.setMaxLength(32);
        this.searchBox.setValue(previous);
        this.searchBox.setResponder(text -> this.listScroll = 0.0D);
        addRenderableWidget(this.searchBox);
        if (!this.tourChecked) {
            this.tourChecked = true;
            if (!this.viewOnly && MasteryConfig.CLIENT.specGuideVersion.get() < GUIDE_VERSION) {
                startTour();
            }
        }
        computeRegions();
    }

    /**
     * 打开时先选中哪个槽：第一个开了但空着的；没有就第一个开了的；都没开选槽 1。
     */
    private int initialSlot() {
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        int firstOpen = 0;
        for (int slot = 1; slot <= rules().slotCount; slot++) {
            if (!SpecializationEffects.isSlotUnlocked(this.data, this.typeKey, slot, snapshot)) {
                continue;
            }
            if (firstOpen == 0) {
                firstOpen = slot;
            }
            if (specInSlot(slot) == null) {
                return slot;
            }
        }
        return firstOpen == 0 ? 1 : firstOpen;
    }

    // ======================================================================
    // 版面
    // ======================================================================

    /**
     * 实际按哪一版摆：配置是 C 但窗口太窄时按 B。
     */
    private SpecScreenLayout effectiveLayout() {
        SpecScreenLayout configured = MasteryConfig.CLIENT.specScreenLayout.get();
        return configured == SpecScreenLayout.C && this.width < C_MIN_WIDTH ? SpecScreenLayout.B : configured;
    }

    /**
     * 算各栏边界。三版的差别基本都在这里，后面的画法按这些边界走。
     */
    private void computeRegions() {
        SpecScreenLayout layout = effectiveLayout();
        int footerTop = this.height - MARGIN - FOOTER_HEIGHT;
        int stripTop = HEADER_HEIGHT + 6;
        int stripBottom = stripTop;
        int sideWidth;
        int sideTop = HEADER_HEIGHT + 6;
        int barTop = 0;
        boolean bar = guideBarVisible();
        switch (layout) {
            case B -> {
                stripBottom = stripTop + stripRows() * (SLOT_TILE_HEIGHT + 4) - 4;
                sideTop = stripBottom + 8;
                if (bar) {
                    barTop = stripBottom + 4;
                    sideTop = barTop + BAR_HEIGHT + 4;
                }
                sideWidth = this.width < C_MIN_WIDTH ? 120 : 150;
            }
            case C -> sideWidth = Mth.clamp(this.width * 26 / 100, 140, 190);
            default -> sideWidth = Mth.clamp(this.width * 30 / 100, 150, 210);
        }
        // A、C 没有槽位条，引导条放在顶栏下面，整页往下让
        if (bar && layout != SpecScreenLayout.B) {
            barTop = HEADER_HEIGHT + 6;
            sideTop = barTop + BAR_HEIGHT + 4;
        }
        int sideLeft = MARGIN;
        int sideRight = sideLeft + sideWidth;
        int mainLeft = sideRight + 13;
        int mainRight = this.width - MARGIN;
        int detailLeft = mainRight;
        if (layout == SpecScreenLayout.C) {
            mainRight = mainLeft + Mth.clamp(this.width * 30 / 100, 150, 230);
            detailLeft = mainRight + 13;
        }
        this.regions = new Regions(layout, sideLeft, sideTop, sideRight, this.height - MARGIN,
                mainLeft, sideTop, mainRight, footerTop, stripTop, stripBottom, detailLeft, barTop);
        if (this.searchBox != null) {
            this.searchBox.setX(mainLeft + 4);
            this.searchBox.setY(sideTop + 3);
            this.searchBox.setWidth(mainRight - mainLeft - 8);
            // 原版输入框的提示字不裁剪，C 版中栏窄时会压到右边详情栏上：放不下换短说法，再放不下截断
            int room = mainRight - mainLeft - 8;
            String hint = I18n.get("gui.albionmastery.spec_page.search");
            if (fw(hint) > room) {
                hint = ellipsis(I18n.get("gui.albionmastery.spec_page.search_short"), room);
            }
            this.searchBox.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** B 版槽位条排几行：每格窄于 80 像素时四个槽排成两行（427 宽的小窗口一行四格还有 99 像素，放得下）。 */
    private int stripRows() {
        int count = rules().slotCount;
        return count > 2 && stripTileWidth(count) < 80 ? 2 : 1;
    }

    private int stripColumns() {
        int count = rules().slotCount;
        return stripRows() == 2 ? (count + 1) / 2 : count;
    }

    private int stripTileWidth(int columns) {
        return (this.width - MARGIN * 2 - 4 * (columns - 1)) / columns;
    }

    // ======================================================================
    // 绘制
    // ======================================================================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        computeRegions();
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        guiGraphics.fill(0, 0, this.width, this.height, MasteryPalette.BG);
        this.hits.clear();
        this.pendingTooltip = null;
        this.hoveredSpec = null;
        clearGeometry();
        if (this.armedPool != null && Util.getMillis() > this.armedUntil) {
            this.armedPool = null;
        }
        if (this.selectedSlot > rules().slotCount) {
            this.selectedSlot = 1;
        }
        Regions r = this.regions;
        // 界面大小按钮在左栏底部最左（位置和 drawSide 里给它让出的那格一致）
        this.scaleKnob.place(r.sideLeft(), r.sideBottom() - SIDE_BUTTON_HEIGHT, SIDE_BUTTON_HEIGHT);
        // 走遮罩时底下的页面不响应鼠标：悬停、预览、悬停面板都不出；鼠标在界面大小按钮或它的卡片上时也一样
        boolean touring = this.tourStep >= 0;
        if (touring) {
            this.scaleKnob.close();
        }
        boolean blocked = touring || this.scaleKnob.contains(mouseX, mouseY);
        int mx = blocked ? -10000 : mouseX;
        int my = blocked ? -10000 : mouseY;
        this.tipMouseX = mx;
        this.tipMouseY = my;
        this.guide = r.barTop() > 0 || touring ? computeGuide(snapshot) : null;

        // 主区先画（要知道鼠标停在哪张卡上），左栏的预览再按它画
        drawSearchFrame(guiGraphics, r);
        if (r.layout() == SpecScreenLayout.C) {
            drawList(guiGraphics, snapshot, r, mx, my);
            drawDetail(guiGraphics, snapshot, r, mx, my);
            guiGraphics.fill(r.mainRight() + 6, r.mainTop(), r.mainRight() + 7, r.sideBottom(), 0x596B5223);
        } else {
            int chipsBottom = drawChips(guiGraphics, mx, my, r.mainLeft(), r.mainRight(),
                    r.mainTop() + SEARCH_HEIGHT + 4);
            drawCardGrid(guiGraphics, snapshot, r, chipsBottom + 6, mx, my);
        }
        if (r.barTop() > 0 && !touring) {
            drawGuideRing(guiGraphics);
        }
        if (r.layout() == SpecScreenLayout.B) {
            drawStrip(guiGraphics, snapshot, r, mx, my);
        }
        guiGraphics.fill(r.sideRight() + 6, r.sideTop(), r.sideRight() + 7, r.sideBottom(), 0x596B5223);
        drawSide(guiGraphics, snapshot, r, mx, my);
        if (r.barTop() > 0) {
            drawGuideBar(guiGraphics, r, mx, my);
        }
        drawHeader(guiGraphics, mx, my);
        drawFooter(guiGraphics, r);
        // 位置这一帧才量出来，滚动下一帧生效
        if (touring) {
            applyTourScroll();
        }
        applyPendingFocus();
        super.render(guiGraphics, mx, my, partialTick);
        if (this.pendingTooltip != null) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0F, 0.0F, 400.0F);
            this.pendingTooltip.run();
            guiGraphics.pose().popPose();
        }
        // 卡片会盖到主区的卡片和悬停面板上，抬得比它们高；遮罩期间压在遮罩底下
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 420.0F);
        this.scaleKnob.render(guiGraphics, touring ? -10000 : mouseX, touring ? -10000 : mouseY);
        guiGraphics.pose().popPose();
        if (touring) {
            // 遮罩期间只认说明框上的按钮
            this.hits.clear();
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0F, 0.0F, 450.0F);
            drawTour(guiGraphics, snapshot, mouseX, mouseY);
            guiGraphics.pose().popPose();
        }
    }

    private void clearGeometry() {
        this.slotsRect = null;
        this.pointsRect = null;
        this.firstRowRect = null;
        this.detailRect = null;
        this.listClip = null;
        this.detailClip = null;
        this.sideClip = null;
        this.modBoxRect = null;
        this.breakBoxRect = null;
        this.detailProgressRect = null;
        this.detailEquipRect = null;
        this.selectedSlotRect = null;
        this.cardRects.clear();
        this.buttonRects.clear();
        this.contentY.clear();
    }

    /** 两块矩形的并（null 当空）。 */
    private static int[] union(@Nullable int[] a, int x, int y, int width, int height) {
        if (a == null) {
            return new int[]{x, y, width, height};
        }
        int left = Math.min(a[0], x);
        int top = Math.min(a[1], y);
        int right = Math.max(a[0] + a[2], x + width);
        int bottom = Math.max(a[1] + a[3], y + height);
        return new int[]{left, top, right - left, bottom - top};
    }

    /**
     * 顶栏：标题、来源名、只读提示、布局切换、返回。
     */
    private void drawHeader(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.fill(0, 0, this.width, HEADER_HEIGHT, MasteryPalette.PANEL);
        guiGraphics.fill(0, HEADER_HEIGHT - 1, this.width, HEADER_HEIGHT, MasteryPalette.EDGE);
        int textY = (HEADER_HEIGHT - this.font.lineHeight) / 2 + 1;
        int backX = this.width - BACK_WIDTH - MARGIN;
        SpecScreenLayout configured = MasteryConfig.CLIENT.specScreenLayout.get();
        Component layoutLabel = Component.translatable("gui.albionmastery.spec_page.layout_button",
                configured.name() + (configured != this.regions.layout() ? "*" : ""));
        int layoutWidth = this.font.width(layoutLabel) + 12;
        int layoutX = backX - 4 - layoutWidth;
        // 「?」重看上手引导（只读时没有）
        int questionX = this.viewOnly ? layoutX : layoutX - 4 - QUESTION_WIDTH;

        int cursor = MARGIN;
        int titleRoom = questionX - 8 - cursor;
        String title = ellipsis(this.title.getString(), titleRoom);
        guiGraphics.drawString(this.font, title, cursor, textY, MasteryPalette.GOLD_BRIGHT, false);
        // 和标题里的「 · 」同样的间距
        cursor += this.font.width(title) + this.font.width(" ");
        String source = "· " + I18n.get("albionmastery.source." + this.providerId);
        int extra = this.viewOnly ? this.font.width(I18n.get("gui.albionmastery.spec_page.view_only")) + 8 : 0;
        if (cursor + this.font.width(source) + extra < questionX - 8) {
            guiGraphics.drawString(this.font, source, cursor, textY, MasteryPalette.TEXT_DIM, false);
            cursor += this.font.width(source) + 8;
        }
        if (this.viewOnly) {
            String hint = I18n.get("gui.albionmastery.spec_page.view_only");
            if (cursor + this.font.width(hint) < layoutX - 8) {
                guiGraphics.drawString(this.font, hint, layoutX - 8 - this.font.width(hint), textY,
                        MasteryPalette.WARN, false);
            }
        }

        if (!this.viewOnly) {
            boolean onQuestion = inside(mouseX, mouseY, questionX, 2, QUESTION_WIDTH, HEADER_HEIGHT - 4);
            drawButton(guiGraphics, questionX, 2, QUESTION_WIDTH, HEADER_HEIGHT - 4, Component.literal("?"),
                    onQuestion, true, false);
            this.hits.add(new Hit(questionX, 2, QUESTION_WIDTH, HEADER_HEIGHT - 4, button -> {
                if (button != 0) {
                    return false;
                }
                playClick();
                if (!MasteryConfig.CLIENT.specGuideBar.get()) {
                    MasteryConfig.CLIENT.specGuideBar.set(true);
                    MasteryConfig.CLIENT.specGuideBar.save();
                }
                startTour();
                return true;
            }));
            if (onQuestion) {
                List<String> lines = new ArrayList<>();
                lines.add(I18n.get("gui.albionmastery.spec_page.guide.replay"));
                if (!MasteryConfig.CLIENT.specGuideBar.get()) {
                    lines.add(I18n.get("gui.albionmastery.spec_page.guide.replay_bar"));
                }
                showTextTooltip(guiGraphics, lines, mouseX, mouseY);
            }
        }

        boolean onLayout = inside(mouseX, mouseY, layoutX, 2, layoutWidth, HEADER_HEIGHT - 4);
        drawButton(guiGraphics, layoutX, 2, layoutWidth, HEADER_HEIGHT - 4, layoutLabel, onLayout, true, false);
        this.hits.add(new Hit(layoutX, 2, layoutWidth, HEADER_HEIGHT - 4, button -> {
            if (button != 0) {
                return false;
            }
            SpecScreenLayout next = MasteryConfig.CLIENT.specScreenLayout.get().next();
            MasteryConfig.CLIENT.specScreenLayout.set(next);
            MasteryConfig.CLIENT.specScreenLayout.save();
            this.listScroll = 0.0D;
            this.sideScroll = 0.0D;
            this.detailScroll = 0.0D;
            playClick();
            return true;
        }));
        if (onLayout) {
            List<String> lines = new ArrayList<>();
            for (SpecScreenLayout layout : SpecScreenLayout.values()) {
                lines.add((layout == configured ? "› " : "  ") + layout.name() + "  "
                        + I18n.get("gui.albionmastery.spec_page.layout." + layout.name().toLowerCase(Locale.ROOT)));
            }
            lines.add(I18n.get("gui.albionmastery.spec_page.layout_hint"));
            if (configured != this.regions.layout()) {
                lines.add(I18n.get("gui.albionmastery.spec_page.layout_fallback"));
            }
            showTextTooltip(guiGraphics, lines, mouseX, mouseY);
        }

        boolean onBack = inside(mouseX, mouseY, backX, 2, BACK_WIDTH, HEADER_HEIGHT - 4);
        drawButton(guiGraphics, backX, 2, BACK_WIDTH, HEADER_HEIGHT - 4,
                Component.translatable("gui.albionmastery.spec_page.back"), onBack, true, false);
        this.hits.add(new Hit(backX, 2, BACK_WIDTH, HEADER_HEIGHT - 4, button -> {
            if (button != 0) {
                return false;
            }
            playClick();
            onClose();
            return true;
        }));
    }

    /**
     * 描边按钮，和罗盘界面左下角那两个同一个样子。
     *
     * @param primary 醒目一点（突破、可以确认的操作）
     */
    private void drawButton(GuiGraphics guiGraphics, int x, int y, int width, int height, Component label,
                            boolean hover, boolean enabled, boolean primary) {
        int background = !enabled ? 0x80130F09 : hover ? 0xF01E1810 : 0xC0130F09;
        guiGraphics.fill(x, y, x + width, y + height, background);
        int border = !enabled ? CHIP_EDGE : hover || primary ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK;
        frame(guiGraphics, x, y, width, height, border);
        int color = !enabled ? TEXT_FAINT : hover ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT;
        String text = ellipsis(label.getString(), width - 6);
        tipIfClipped(guiGraphics, label.getString(), text, x, y, width, height);
        // 文字按不取整的亮像素宽（宽度含末尾 1 像素字距，扣掉）居中；落到屏幕像素格时往左取整
        drawAt(guiGraphics, Component.literal(text), x + (width - (fw(text) - 1.0F)) / 2.0F,
                y + (height - 8) / 2, color);
    }

    private void drawSearchFrame(GuiGraphics guiGraphics, Regions r) {
        int top = r.mainTop();
        boolean focused = this.searchBox != null && this.searchBox.isFocused();
        guiGraphics.fill(r.mainLeft(), top, r.mainRight(), top + SEARCH_HEIGHT, 0xFF080704);
        frame(guiGraphics, r.mainLeft(), top, r.mainRight() - r.mainLeft(), SEARCH_HEIGHT,
                focused ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK);
    }

    /**
     * 分组筛选：「全部」加上每个分组一个小按钮，放不下自动换行。
     *
     * @return 筛选区的底边
     */
    private int drawChips(GuiGraphics guiGraphics, int mouseX, int mouseY, int left, int right, int top) {
        int x = left;
        int y = top;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Specialization spec : this.specs) {
            counts.merge(spec.family(), 1, Integer::sum);
        }
        List<String> families = new ArrayList<>();
        families.add(null);
        families.addAll(counts.keySet());
        for (String family : families) {
            Component label = family == null
                    ? Component.translatable("gui.albionmastery.spec_page.all", this.specs.size())
                    : Component.translatable("gui.albionmastery.spec_page.family_chip",
                            Component.translatable(familyKey(family)), counts.get(family));
            int width = this.font.width(label) + 8;
            if (x + width > right && x > left) {
                x = left;
                y += CHIP_HEIGHT + 3;
            }
            boolean active = family == null ? this.familyFilter == null : family.equals(this.familyFilter);
            boolean hover = inside(mouseX, mouseY, x, y, width, CHIP_HEIGHT);
            guiGraphics.fill(x, y, x + width, y + CHIP_HEIGHT,
                    active ? 0x40E8C25A : hover ? 0x22FFFFFF : 0x80130F09);
            frame(guiGraphics, x, y, width, CHIP_HEIGHT,
                    active ? MasteryPalette.GOLD : hover ? MasteryPalette.GOLD_DARK : CHIP_EDGE);
            guiGraphics.drawString(this.font, label, x + 4, y + 2,
                    active ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT_DIM, false);
            String value = family;
            this.hits.add(new Hit(x, y, width, CHIP_HEIGHT, button -> {
                if (button != 0) {
                    return false;
                }
                this.familyFilter = value;
                this.listScroll = 0.0D;
                playClick();
                return true;
            }));
            x += width + 3;
        }
        return y + CHIP_HEIGHT;
    }

    // ---------------------------------------------------------------- 槽位

    /**
     * B 版：槽位横排在最上面，太窄时排成两行。
     */
    private void drawStrip(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, Regions r,
                           int mouseX, int mouseY) {
        int columns = stripColumns();
        // 总宽除不尽的几像素按列摊开（每块最多宽 1），间距固定 4，左右边距才一样
        int span = this.width - MARGIN * 2 + 4;
        for (int slot = 1; slot <= rules().slotCount; slot++) {
            int index = slot - 1;
            int column = index % columns;
            int x = MARGIN + column * span / columns;
            int width = MARGIN + (column + 1) * span / columns - 4 - x;
            int y = r.stripTop() + (index / columns) * (SLOT_TILE_HEIGHT + 4);
            SlotView view = slotView(slot, snapshot);
            boolean hover = inside(mouseX, mouseY, x, y, width, SLOT_TILE_HEIGHT);
            drawSlotTile(guiGraphics, snapshot, view, x, y, width, hover);
            addSlotHit(view, x, y, width, SLOT_TILE_HEIGHT);
            this.slotsRect = union(this.slotsRect, x, y, width, SLOT_TILE_HEIGHT);
            if (slot == this.selectedSlot) {
                this.selectedSlotRect = new int[]{x, y, width, SLOT_TILE_HEIGHT};
            }
        }
    }

    /**
     * B 版的一个槽：顶上一道色条（普通金、突破橙），槽名、种类，装着的特化和等级；没开的写差什么。
     */
    private void drawSlotTile(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, SlotView view, int x, int y,
                              int width, boolean hover) {
        boolean selected = view.slot() == this.selectedSlot;
        guiGraphics.fill(x, y, x + width, y + SLOT_TILE_HEIGHT, 0xBF130F09);
        if (selected) {
            guiGraphics.fill(x, y, x + width, y + SLOT_TILE_HEIGHT, 0x29E8C25A);
        } else if (hover) {
            guiGraphics.fill(x, y, x + width, y + SLOT_TILE_HEIGHT, 0x14FFFFFF);
        }
        frame(guiGraphics, x, y, width, SLOT_TILE_HEIGHT,
                selected ? MasteryPalette.GOLD : hover ? MasteryPalette.GOLD_DARK : CARD_EDGE);
        int bar = !view.unlocked() ? MasteryPalette.GOLD_DARK : view.breakthrough() ? BREAK : MasteryPalette.GOLD;
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + 3, bar);
        int inner = width - PAD * 2;
        String label = view.label().getString();
        String tag = fitSlotTag(label, slotTag(view, selected), inner);
        int tagColor = selected ? MasteryPalette.GOLD : view.breakthrough() ? BREAK : MasteryPalette.GOLD;
        drawRight(guiGraphics, tag, x + width - PAD, y + 6, tagColor);
        guiGraphics.drawString(this.font, ellipsis(label, tag.isEmpty() ? inner : inner - fw(tag) - NAME_VALUE_GAP),
                x + PAD, y + 6, view.unlocked() ? MasteryPalette.TEXT_DIM : TEXT_FAINT, false);
        if (!view.unlocked()) {
            // 「类型 100 级解锁」+ 进度条「72 / 100」
            Need need = need(view.slot(), snapshot);
            guiGraphics.drawString(this.font, ellipsis(need.text(), inner), x + PAD, y + 18, TEXT_FAINT, false);
            drawNeedProgress(guiGraphics, need, x + PAD, x + width - PAD, y + 29);
            return;
        }
        if (view.spec() == null) {
            // 选中的空槽写收什么；引导条收起时再写一句怎么装（开着时引导条那句已经说了）
            boolean guide = selected && !this.viewOnly;
            String first = guide && this.regions.barTop() == 0
                    ? I18n.get(this.regions.layout() == SpecScreenLayout.C
                    ? "gui.albionmastery.spec_page.slot_empty_pick_c" : "gui.albionmastery.spec_page.slot_empty_pick")
                    : I18n.get("gui.albionmastery.spec_page.slot_empty");
            guiGraphics.drawString(this.font, ellipsis(first, inner), x + PAD, y + 18,
                    guide && this.regions.barTop() == 0 ? MasteryPalette.TEXT : TEXT_FAINT, false);
            if (guide) {
                guiGraphics.drawString(this.font, ellipsis(acceptsText(view), inner), x + PAD, y + 29, TEXT_FAINT,
                        false);
            }
            return;
        }
        String specName = slotSpecName(view, inner);
        String shownName = ellipsis(specName, inner);
        guiGraphics.drawString(this.font, shownName, x + PAD, y + 18,
                selected ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT, false);
        tipIfClipped(guiGraphics, specName, shownName, x, y, width, SLOT_TILE_HEIGHT);
        int levelWidth = drawLevelMarks(guiGraphics, view, x + PAD, y + 29);
        String levelText = levelText(view.breakthrough(), view.level());
        guiGraphics.drawString(this.font, levelText, x + PAD + levelWidth + 5, y + 29, TEXT_FAINT, false);
        if (!view.active()) {
            String off = I18n.get("gui.albionmastery.spec_page.slot_inactive");
            if (PAD + levelWidth + 5 + this.font.width(levelText) + NAME_VALUE_GAP + fw(off) <= width - PAD) {
                drawRight(guiGraphics, off, x + width - PAD, y + 29, MasteryPalette.WARN);
            }
        }
    }

    /**
     * A、C 版左栏的一个槽：左边一道色条，第一行槽名和种类，第二行装着的特化（等级点右对齐）或者差什么。
     */
    private int drawSlotRow(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, SlotView view, int x, int y,
                            int width, boolean hover) {
        boolean selected = view.slot() == this.selectedSlot;
        guiGraphics.fill(x, y, x + width, y + SLOT_ROW_HEIGHT, 0x60000000);
        if (selected) {
            guiGraphics.fill(x, y, x + width, y + SLOT_ROW_HEIGHT, 0x29E8C25A);
            frame(guiGraphics, x, y, width, SLOT_ROW_HEIGHT, MasteryPalette.GOLD);
        } else if (hover) {
            guiGraphics.fill(x, y, x + width, y + SLOT_ROW_HEIGHT, 0x14FFFFFF);
        }
        int bar = !view.unlocked() ? MasteryPalette.GOLD_DARK : view.breakthrough() ? BREAK : MasteryPalette.GOLD;
        guiGraphics.fill(x, y, x + 2, y + SLOT_ROW_HEIGHT, bar);
        int left = x + 6;
        int right = x + width - PAD;
        String label = view.label().getString();
        String tag = fitSlotTag(label, slotTag(view, selected), right - left);
        drawRight(guiGraphics, tag, right, y + 3, selected ? MasteryPalette.GOLD
                : view.breakthrough() ? BREAK : MasteryPalette.GOLD);
        guiGraphics.drawString(this.font, ellipsis(label, tag.isEmpty() ? right - left
                        : right - left - fw(tag) - NAME_VALUE_GAP),
                left, y + 3, view.unlocked() ? MasteryPalette.TEXT_DIM : TEXT_FAINT, false);
        int lineY = y + 14;
        if (!view.unlocked()) {
            // 一行放下：「类型 100 级解锁」靠左，「72 / 100」靠右，中间放得下就画进度条
            Need need = need(view.slot(), snapshot);
            String count = need.now() + " / " + need.need();
            float countWidth = fw(count) - 1.0F;
            String text = ellipsis(need.text(), right - left - countWidth - NAME_VALUE_GAP - 1);
            guiGraphics.drawString(this.font, text, left, lineY, TEXT_FAINT, false);
            drawNeedProgress(guiGraphics, need, left + (int) Math.ceil(fw(text) - 1.0F) + NAME_VALUE_GAP + 1, right,
                    lineY);
            return y + SLOT_ROW_HEIGHT;
        }
        if (view.spec() == null) {
            boolean guide = selected && !this.viewOnly;
            guiGraphics.drawString(this.font, ellipsis(guide ? acceptsText(view)
                            : I18n.get("gui.albionmastery.spec_page.slot_empty"), right - left), left, lineY,
                    TEXT_FAINT, false);
            return y + SLOT_ROW_HEIGHT;
        }
        int marksWidth = levelMarksWidth(view);
        drawLevelMarks(guiGraphics, view, right - marksWidth, lineY);
        int nameColor = !view.active() ? MasteryPalette.WARN
                : selected ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT;
        String specName = slotSpecName(view, right - left - marksWidth - NAME_VALUE_GAP);
        String shownName = ellipsis(specName, right - left - marksWidth - NAME_VALUE_GAP);
        guiGraphics.drawString(this.font, shownName, left, lineY, nameColor, false);
        tipIfClipped(guiGraphics, specName, shownName, x, y, width, SLOT_ROW_HEIGHT);
        return y + SLOT_ROW_HEIGHT;
    }

    private void addSlotHit(SlotView view, int x, int y, int width, int height) {
        this.hits.add(new Hit(x, y, width, height, button -> {
            if (button == 0) {
                this.selectedSlot = view.slot();
                playClick();
                return true;
            }
            if (button == 1 && !this.viewOnly && view.spec() != null) {
                // 右键卸下这个槽
                this.selectedSlot = view.slot();
                sendSelect(view.slot(), null);
                return true;
            }
            return false;
        }));
    }

    /**
     * 槽名和右上角小字一行放不下时先保住槽名、小字不画：选中有亮框，普通 / 突破有顶上的色条，小字只是重复。
     */
    private String fitSlotTag(String label, String tag, float room) {
        return fw(label) + NAME_VALUE_GAP + fw(tag) <= room ? tag : "";
    }

    /** 槽右上角的小字：选中时「装到这里」，否则是「普通 / 突破」。 */
    private String slotTag(SlotView view, boolean selected) {
        if (selected && !this.viewOnly) {
            return I18n.get("gui.albionmastery.spec_page.place_here");
        }
        return I18n.get(view.breakthrough() ? "gui.albionmastery.spec_page.kind_break"
                : "gui.albionmastery.spec_page.kind_normal");
    }

    private String slotSpecName(SlotView view) {
        String name = I18n.get(view.spec().nameKey());
        return view.breakthrough() ? I18n.get("gui.albionmastery.spec_page.break_name", name) : name;
    }

    /** 槽里装着的名字放不下时先去掉突破标记（突破槽有色条，下一行有星），还放不下再截断。 */
    private String slotSpecName(SlotView view, float room) {
        String name = slotSpecName(view);
        return fw(name) <= room ? name : I18n.get(view.spec().nameKey());
    }

    /** 空槽收什么：突破槽「只收突破过的（★1 起）」，普通槽「收普通版（Lv1 起）」。 */
    private String acceptsText(SlotView view) {
        return I18n.get(view.breakthrough() ? "gui.albionmastery.spec_page.slot_accepts_break"
                : "gui.albionmastery.spec_page.slot_accepts_normal");
    }

    /**
     * 锁住的槽的进度：右边「72 / 100」，左边到 {@code left} 之间放进度条（窄于 12 像素不画）。
     *
     * @param y 这一行字的顶
     */
    private void drawNeedProgress(GuiGraphics guiGraphics, Need need, int left, int right, int y) {
        String count = need.now() + " / " + need.need();
        drawRight(guiGraphics, count, right, y, MasteryPalette.TEXT_DIM);
        int barRight = right - (int) Math.ceil(fw(count) - 1.0F) - NAME_VALUE_GAP;
        if (barRight - left < 12) {
            return;
        }
        // 3 像素高，竖直居中在字格里（字格 8 高，亮的是上 7 行）
        int barTop = y + 2;
        guiGraphics.fill(left, barTop, barRight, barTop + 3, 0x806B5223);
        int filled = (int) ((long) (barRight - left) * Mth.clamp(need.now(), 0, need.need()) / Math.max(1, need.need()));
        if (filled > 0) {
            guiGraphics.fill(left, barTop, left + filled, barTop + 3, MasteryPalette.PROGRESS);
        }
    }

    /**
     * 槽没开时差什么（短句 + 现在 / 要求），给原地说明的进度条用。
     *
     * @param text 「类型 100 级解锁」
     * @param now  现在几级
     * @param need 要几级
     */
    private record Need(String text, int now, int need) {
    }

    private Need need(int slot, MasteryConfig.Snapshot snapshot) {
        if (!SpecializationEffects.slotConditionMet(this.data, this.typeKey, slot, snapshot)) {
            int level = SpecializationEffects.slotNeedLevel(slot, snapshot);
            return switch (SpecializationEffects.slotNeed(slot)) {
                case TYPE -> new Need(I18n.get("gui.albionmastery.spec_page.need_type_short", level),
                        this.data.getTypeLevel(this.typeKey), level);
                case SOURCE -> new Need(I18n.get("gui.albionmastery.spec_page.need_source_short", level),
                        this.data.getOverallLevel(WeaponKeys.overallKey(this.providerId)), level);
                case NONE -> new Need("", 0, 0);
            };
        }
        int gate = SpecializationEffects.weaponGate(this.data, this.typeKey, snapshot);
        int now = this.weaponKey != null ? this.data.getWeaponLevel(this.weaponKey) : highestWeaponLevel();
        return new Need(I18n.get("gui.albionmastery.spec_page.need_weapon_short", gate), now, gate);
    }

    // ---------------------------------------------------------------- 左栏

    /**
     * 左栏：现在改的是哪一份配置、（武器页）这把武器过没过门槛、点数、（A、C）槽位、合计效果，
     * 底部界面大小按钮 + 两个重置按钮。
     * 合计效果按剩下的高度决定列几项，放不下写「+N 项」；固定部分本身放不下时左栏可以滚动。
     */
    private void drawSide(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, Regions r,
                          int mouseX, int mouseY) {
        int left = r.sideLeft();
        int right = r.sideRight();
        int width = right - left;
        int top = r.sideTop();
        // 底部一排：最左是界面大小按钮（和重置按钮一样高、和它的下沿对齐），重置按钮在它右边空 4 像素起排；
        // 看别人的配置时没有重置按钮，只剩界面大小按钮
        int knob = SIDE_BUTTON_HEIGHT;
        int respecLeft = left + knob + 4;
        int respecWidth = right - respecLeft;
        int buttonsTop = r.sideBottom() - (this.viewOnly ? knob : respecHeight(respecWidth));
        int viewBottom = buttonsTop - 6;

        guiGraphics.enableScissor(left, top, right, viewBottom);
        this.sideClip = new int[]{left, top, width, viewBottom - top};
        int start = top - (int) Math.round(this.sideScroll);
        int cursor = start;
        cursor = drawModeBlock(guiGraphics, left, cursor, width, mouseX, mouseY, top, viewBottom);
        if (this.weaponKey != null) {
            cursor = drawWeaponGate(guiGraphics, snapshot, left, cursor, width);
        }
        cursor = drawPoints(guiGraphics, snapshot, left, cursor, width, r.layout() != SpecScreenLayout.B,
                mouseX, mouseY, top, viewBottom);
        if (r.layout() != SpecScreenLayout.B) {
            cursor += 4;
            for (int slot = 1; slot <= rules().slotCount; slot++) {
                SlotView view = slotView(slot, snapshot);
                boolean visible = cursor + SLOT_ROW_HEIGHT > top && cursor < viewBottom;
                boolean hover = visible && inside(mouseX, mouseY, left, cursor, width, SLOT_ROW_HEIGHT)
                        && mouseY >= top && mouseY < viewBottom;
                drawSlotRow(guiGraphics, snapshot, view, left, cursor, width, hover);
                this.slotsRect = union(this.slotsRect, left, cursor, width, SLOT_ROW_HEIGHT);
                if (slot == this.selectedSlot) {
                    this.selectedSlotRect = new int[]{left, cursor, width, SLOT_ROW_HEIGHT};
                }
                if (visible) {
                    int hitTop = Math.max(cursor, top);
                    addSlotHit(view, left, hitTop, width, Math.min(cursor + SLOT_ROW_HEIGHT, viewBottom) - hitTop);
                }
                cursor += SLOT_ROW_HEIGHT + 3;
            }
        }
        cursor += 6;
        // 合计效果能用的行数按「不滚动时」剩下的高度算，至少列三行：扣掉小节标题（LINE + 4）
        // 和正负之间的分隔（4）；「+N 项」那行由 pickShown 在 limit 里自己留
        int fixedEnd = cursor + (int) Math.round(this.sideScroll);
        int limit = Math.max(3, (viewBottom - fixedEnd - LINE - 4 - 4) / LINE);
        cursor = drawTotals(guiGraphics, snapshot, left, cursor, width, limit, mouseX, mouseY, top, viewBottom);
        guiGraphics.disableScissor();

        int content = cursor - start;
        int viewport = viewBottom - top;
        this.sideMaxScroll = Math.max(0.0D, content - viewport);
        this.sideScroll = Mth.clamp(this.sideScroll, 0.0D, this.sideMaxScroll);
        if (this.sideMaxScroll > 0.0D) {
            drawScrollbar(guiGraphics, right + 2, top, viewBottom, viewport, content, this.sideScroll);
        }
        if (!this.viewOnly) {
            drawRespecButtons(guiGraphics, snapshot, respecLeft, buttonsTop, respecWidth, mouseX, mouseY);
        }
    }

    /**
     * 左栏最上面：现在改的是哪一份配置，外加一个切换按钮。
     *
     * <ul>
     *     <li>类型页：这是通用配置，本类型下没有单独配置的武器都用它；有几件单独配置的会提一句。</li>
     *     <li>武器页、已单独配置：优先于通用配置，按钮「恢复通用配置」。</li>
     *     <li>武器页、还在跟随：显示跟随的是哪个类型，点任意卡片就变成单独配置；按钮「改通用配置」。</li>
     * </ul>
     *
     * @return 结束后的 Y
     */
    private int drawModeBlock(GuiGraphics guiGraphics, int x, int y, int width, int mouseX, int mouseY,
                              int viewTop, int viewBottom) {
        Component headline;
        Component detail = null;
        int color;
        String action = null;
        Component actionLabel = null;
        if (this.weaponKey == null) {
            headline = Component.translatable("gui.albionmastery.spec_page.mode_type", this.typeName);
            color = MasteryPalette.GOLD;
            int overrides = countOverrides(this.data, this.typeKey);
            if (overrides > 0) {
                detail = Plural.component("gui.albionmastery.spec_page.mode_type_overrides", overrides, overrides);
            }
        } else if (this.data.hasWeaponOverride(this.weaponKey)) {
            headline = Component.translatable("gui.albionmastery.spec_page.mode_own", this.typeName);
            color = MasteryPalette.GOLD;
            action = "follow";
            actionLabel = Component.translatable("gui.albionmastery.spec_page.follow_button");
        } else {
            headline = Component.translatable("gui.albionmastery.spec_page.mode_follow", this.typeName);
            color = MasteryPalette.TEXT;
            detail = Component.translatable("gui.albionmastery.spec_page.mode_follow_hint");
            action = "edit_type";
            actionLabel = Component.translatable("gui.albionmastery.spec_page.edit_type_button");
        }
        int cursor = y;
        for (String line : TextWrap.wrap(this.font, headline.getString(), width)) {
            guiGraphics.drawString(this.font, line, x, cursor, color, false);
            cursor += LINE;
        }
        if (detail != null) {
            for (String line : TextWrap.wrap(this.font, detail.getString(), width)) {
                guiGraphics.drawString(this.font, line, x, cursor, MasteryPalette.TEXT_DIM, false);
                cursor += LINE;
            }
        }
        if (action != null && !this.viewOnly) {
            cursor += 2;
            int height = SIDE_BUTTON_HEIGHT;
            boolean visible = cursor + height > viewTop && cursor < viewBottom;
            boolean hover = visible && inside(mouseX, mouseY, x, cursor, width, height)
                    && mouseY >= viewTop && mouseY < viewBottom;
            drawButton(guiGraphics, x, cursor, width, height, actionLabel, hover, true, false);
            if (visible) {
                int hitTop = Math.max(cursor, viewTop);
                String chosen = action;
                this.hits.add(new Hit(x, hitTop, width, Math.min(cursor + height, viewBottom) - hitTop, button -> {
                    if (button != 0) {
                        return false;
                    }
                    playClick();
                    if ("follow".equals(chosen) && this.weaponKey != null) {
                        NetworkHandler.sendToServer(new C2SSelectSpecializationPacket(this.typeKey, "", 1,
                                this.weaponKey, true));
                        this.lastRequestMs = Util.getMillis();
                    } else if ("edit_type".equals(chosen)) {
                        Minecraft.getInstance().setScreen(new SpecializationScreen(this.data, this.typeKey,
                                this.typeName, null, null, this, this.viewOnly));
                    }
                    return true;
                }));
            }
            cursor += height;
        }
        return cursor + 6;
    }

    /**
     * 武器页：这把武器过没过槽 1 的门槛（「这把武器 63 / 80 级」、段位减免几级、类型练满免门槛）。
     */
    private int drawWeaponGate(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, int x, int y, int width) {
        int level = this.data.getWeaponLevel(this.weaponKey);
        boolean bypass = SpecializationEffects.typeBypassesGate(this.data, this.typeKey, snapshot);
        boolean passed = SpecializationEffects.weaponPassesGate(this.data, this.typeKey, this.weaponKey, snapshot);
        int gate = SpecializationEffects.weaponGate(this.data, this.typeKey, snapshot);
        int cursor = y;
        String label = I18n.get("gui.albionmastery.spec_page.weapon_gate_label");
        String value = bypass ? I18n.get("gui.albionmastery.spec_page.weapon_gate_bypass") : level + " / " + gate;
        guiGraphics.drawString(this.font, ellipsis(label, width - fw(value) - NAME_VALUE_GAP), x, cursor,
                MasteryPalette.TEXT_DIM, false);
        drawRight(guiGraphics, value, x + width, cursor, passed ? GREEN : MasteryPalette.TEXT);
        cursor += LINE;
        int reduction = SpecializationEffects.rankReduction(this.data, this.typeKey, snapshot);
        if (reduction > 0 && !bypass) {
            guiGraphics.drawString(this.font, ellipsis(Plural.get("gui.albionmastery.spec_page.weapon_gate_rank",
                    reduction, reduction), width), x, cursor, TEXT_FAINT, false);
            cursor += LINE;
        }
        if (!passed) {
            for (String line : TextWrap.wrap(this.font, I18n.get("gui.albionmastery.spec_page.weapon_gate_note"),
                    width)) {
                guiGraphics.drawString(this.font, line, x, cursor, MasteryPalette.WARN, false);
                cursor += LINE;
            }
        }
        return cursor + 6;
    }

    /**
     * 改装点、突破点两个框：「剩 / 共」，右上角写点数从哪来（「枪械 85 级」）。
     *
     * @param sideBySide 两个框并排（A、C）还是上下叠（B）
     */
    private int drawPoints(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, int x, int y, int width,
                           boolean sideBySide, int mouseX, int mouseY, int viewTop, int viewBottom) {
        String overallKey = WeaponKeys.overallKey(this.providerId);
        int modAvailable = SpecPoints.modPointsAvailable(this.data, this.providerId, snapshot);
        int modTotal = SpecPoints.modPointsTotal(this.data, this.providerId, snapshot);
        int breakAvailable = SpecPoints.breakthroughPointsAvailable(this.data, this.typeKey, snapshot);
        int breakTotal = SpecPoints.breakthroughPointsTotal(this.data, this.typeKey, snapshot);
        String modLabel = SpecTerms.of(this.providerId, "point").getString();
        String modLabelShort = SpecTerms.of(this.providerId, "point_short").getString();
        String breakLabel = I18n.get("gui.albionmastery.spec_page.break_points");
        String modSource = I18n.get("gui.albionmastery.spec_page.points_source",
                ClientWeaponRegistry.sourceName(overallKey).getString(), this.data.getOverallLevel(overallKey));
        String breakSource = I18n.get("gui.albionmastery.spec_page.points_source",
                this.typeName.getString(), this.data.getTypeLevel(this.typeKey));
        String modShort = I18n.get("gui.albionmastery.spec_page.points_level", this.data.getOverallLevel(overallKey));
        String breakShort = I18n.get("gui.albionmastery.spec_page.points_level", this.data.getTypeLevel(this.typeKey));
        int modNext = nextModLevel(snapshot);
        int breakNext = nextBreakLevel(snapshot);
        String modNextText = modNext > 0 ? I18n.get("gui.albionmastery.spec_page.points_next", modNext) : "";
        String breakNextText = breakNext > 0 ? I18n.get("gui.albionmastery.spec_page.points_next", breakNext) : "";
        int modX = x;
        int modY = y;
        int breakX;
        int breakY;
        int boxWidth;
        int end;
        if (sideBySide) {
            boxWidth = (width - 4) / 2;
            breakX = x + width - boxWidth;
            breakY = y;
            end = y + POINTS_BOX_HEIGHT + 2;
        } else {
            boxWidth = width;
            breakX = x;
            breakY = y + POINTS_BOX_HEIGHT + 4;
            end = y + POINTS_BOX_HEIGHT * 2 + 4 + 2;
        }
        drawPointsBox(guiGraphics, modX, modY, boxWidth, modLabel, modLabelShort, modAvailable, modTotal, modSource,
                modShort, modNextText, MasteryPalette.GOLD);
        drawPointsBox(guiGraphics, breakX, breakY, boxWidth, breakLabel, breakLabel, breakAvailable, breakTotal,
                breakSource, breakShort, breakNextText, BREAK);
        this.modBoxRect = new int[]{modX, modY, boxWidth, POINTS_BOX_HEIGHT};
        this.breakBoxRect = new int[]{breakX, breakY, boxWidth, POINTS_BOX_HEIGHT};
        this.pointsRect = union(this.modBoxRect, breakX, breakY, boxWidth, POINTS_BOX_HEIGHT);
        // 鼠标停在点数框上：列出点数从哪来、花在哪、下一点在几级
        if (mouseY >= viewTop && mouseY < viewBottom) {
            if (inside(mouseX, mouseY, modX, modY, boxWidth, POINTS_BOX_HEIGHT)) {
                this.pendingTooltip = () -> drawPointsTooltip(guiGraphics, snapshot, false, mouseX, mouseY);
            } else if (inside(mouseX, mouseY, breakX, breakY, boxWidth, POINTS_BOX_HEIGHT)) {
                this.pendingTooltip = () -> drawPointsTooltip(guiGraphics, snapshot, true, mouseX, mouseY);
            }
        }
        return end;
    }

    /**
     * 一个点数框：左上「改装点 ?」，右上点数从哪来（「枪械 85 级」），左下「剩 / 共」，右下「90 级再 +1」。
     */
    private void drawPointsBox(GuiGraphics guiGraphics, int x, int y, int width, String label, String labelShort,
                               int available, int total, String source, String sourceShort, String next, int color) {
        guiGraphics.fill(x, y, x + width, y + POINTS_BOX_HEIGHT, 0xB3130F09);
        frame(guiGraphics, x, y, width, POINTS_BOX_HEIGHT, CARD_EDGE);
        int inner = width - PAD * 2;
        // 标签后面跟一个「?」小方块，提示鼠标停上来能看明细；整个标签都放不下时换短说法（Artifice pts）
        if (fw(label) + 2 + BADGE > inner) {
            label = labelShort;
        }
        // 最窄的并排框（480×270 的 A、C）短说法加「?」还放不下：去掉「?」保住整个标签，悬停照样出明细
        boolean badge = fw(label) + 2 + BADGE <= inner;
        int badgeRoom = badge ? 2 + BADGE : 0;
        float labelWidth = fw(label) + badgeRoom;
        // 右上角放得下整句就写「枪械 85 级」，放不下只写「85 级」，再放不下不写
        String shown = labelWidth + NAME_VALUE_GAP + fw(source) <= inner ? source
                : labelWidth + NAME_VALUE_GAP + fw(sourceShort) <= inner ? sourceShort : "";
        String labelText = ellipsis(label, inner - badgeRoom - (shown.isEmpty() ? 0 : fw(shown) + NAME_VALUE_GAP));
        guiGraphics.drawString(this.font, labelText, x + PAD, y + 3, MasteryPalette.TEXT_DIM, false);
        if (badge) {
            drawBadge(guiGraphics, x + PAD + (int) Math.ceil(fw(labelText)) + 1, y + 3, 3, "?");
        }
        if (!shown.isEmpty()) {
            drawRight(guiGraphics, shown, x + width - PAD, y + 3, TEXT_FAINT);
        }
        String left = Integer.toString(available);
        guiGraphics.drawString(this.font, left, x + PAD, y + 13, available < 0 ? MasteryPalette.WARN : color, false);
        String totalText = "/ " + total;
        int totalX = x + PAD + this.font.width(left) + 2;
        guiGraphics.drawString(this.font, totalText, totalX, y + 13, MasteryPalette.TEXT_DIM, false);
        if (!next.isEmpty() && totalX + fw(totalText) - 1 + NAME_VALUE_GAP * 2 + fw(next) - 1 <= x + width - PAD) {
            drawRight(guiGraphics, next, x + width - PAD, y + 13, TEXT_FAINT);
        }
    }

    /** 来源等级再升到几级多 1 改装点；已经到顶返回 0。 */
    private int nextModLevel(MasteryConfig.Snapshot snapshot) {
        SpecRules rules = snapshot.specRules;
        int level = this.data.getOverallLevel(WeaponKeys.overallKey(this.providerId));
        int now = rules.modPointsFromLevel(level, snapshot.maxLevel);
        for (int next = level + 1; next <= snapshot.maxLevel; next++) {
            if (rules.modPointsFromLevel(next, snapshot.maxLevel) > now) {
                return next;
            }
        }
        return 0;
    }

    /** 类型等级再升到几级多 1 突破点；已经到顶返回 0。 */
    private int nextBreakLevel(MasteryConfig.Snapshot snapshot) {
        SpecRules rules = snapshot.specRules;
        int level = this.data.getTypeLevel(this.typeKey);
        int now = rules.breakthroughPointsFromLevel(level, snapshot.maxLevel);
        for (int next = level + 1; next <= snapshot.maxLevel; next++) {
            if (rules.breakthroughPointsFromLevel(next, snapshot.maxLevel) > now) {
                return next;
            }
        }
        return 0;
    }

    /**
     * 点数框的悬停明细：一行一项来源、已花多少、剩多少，下面几行规则说明（每几级 +1、段位 / 满级武器加成、
     * 每级花几点、重置）。数字全按当前规则算。
     */
    private void drawPointsTooltip(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, boolean breakPool,
                                   int mouseX, int mouseY) {
        SpecRules rules = snapshot.specRules;
        String pointName = SpecTerms.of(this.providerId, "point").getString();
        String specTerm = SpecTerms.of(this.providerId, "spec").getString();
        String sourceName = ClientWeaponRegistry.sourceName(WeaponKeys.overallKey(this.providerId)).getString();
        String typeName = this.typeName.getString();
        String title;
        List<String[]> rows = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int spent;
        int left;
        int count = 0;
        if (!breakPool) {
            int level = this.data.getOverallLevel(WeaponKeys.overallKey(this.providerId));
            int fromLevel = rules.modPointsFromLevel(level, snapshot.maxLevel);
            int rank = SpecializationEffects.rankOf(this.data, snapshot);
            int fromRank = rules.modPointsFromRank(rank);
            spent = SpecPoints.modPointsSpent(this.data, this.providerId, rules);
            left = SpecPoints.modPointsAvailable(this.data, this.providerId, snapshot);
            for (Specialization spec : this.specs) {
                if (this.data.getSpecLevel(spec.key()) > 0) {
                    count++;
                }
            }
            title = I18n.get("gui.albionmastery.spec_page.points_tip.mod_title", pointName, sourceName);
            rows.add(new String[]{I18n.get("gui.albionmastery.spec_page.points_source", sourceName, level),
                    I18n.get("gui.albionmastery.spec_page.points_tip.value", fromLevel)});
            colors.add(MasteryPalette.TEXT);
            if (rules.rankModPointMax > 0) {
                rows.add(new String[]{I18n.get("gui.albionmastery.spec_page.points_tip.rank", rank),
                        I18n.get("gui.albionmastery.spec_page.points_tip.plus", fromRank)});
                colors.add(MasteryPalette.TEXT);
            }
            notes.add(levelRule(sourceName, rules.modPointsAtMaxSource, snapshot.maxLevel, nextModLevel(snapshot)));
            if (rules.rankModPointMax > 0) {
                notes.add(Plural.get("gui.albionmastery.spec_page.points_tip.rank_rule", rules.rankModPointEvery,
                        rules.rankModPointStart,
                        rules.rankModPointEvery, rules.rankModPointMax));
            }
            notes.add(costRule(I18n.get("gui.albionmastery.spec_page.points_tip.normal_range", rules.normalMax()),
                    costs(rules, false)));
        } else {
            int level = this.data.getTypeLevel(this.typeKey);
            int fromLevel = rules.breakthroughPointsFromLevel(level, snapshot.maxLevel);
            int maxed = this.data.getMaxedWeaponCount(this.typeKey);
            int fromMaxed = rules.breakthroughPointsFromMaxed(maxed);
            spent = SpecPoints.breakthroughPointsSpent(this.data, this.typeKey, rules);
            left = SpecPoints.breakthroughPointsAvailable(this.data, this.typeKey, snapshot);
            for (Specialization spec : this.specs) {
                if (this.data.getBreakthrough(this.typeKey, spec.key()) > 0) {
                    count++;
                }
            }
            title = I18n.get("gui.albionmastery.spec_page.points_tip.break_title", typeName);
            rows.add(new String[]{I18n.get("gui.albionmastery.spec_page.points_source", typeName, level),
                    I18n.get("gui.albionmastery.spec_page.points_tip.value", fromLevel)});
            colors.add(MasteryPalette.TEXT);
            if (rules.maxedWeaponBreakthroughPointMax > 0) {
                rows.add(new String[]{I18n.get("gui.albionmastery.spec_page.points_tip.maxed", maxed),
                        I18n.get("gui.albionmastery.spec_page.points_tip.plus", fromMaxed)});
                colors.add(MasteryPalette.TEXT);
            }
            notes.add(levelRule(typeName, rules.breakthroughPointsAtMaxType, snapshot.maxLevel,
                    nextBreakLevel(snapshot)));
            if (rules.maxedWeaponBreakthroughPointMax > 0) {
                notes.add(I18n.get("gui.albionmastery.spec_page.points_tip.maxed_rule",
                        rules.maxedWeaponBreakthroughPoint, rules.maxedWeaponBreakthroughPointMax));
            }
            notes.add(costRule(I18n.get("gui.albionmastery.spec_page.points_tip.break_range",
                    rules.breakthroughMax()), costs(rules, true)));
        }
        rows.add(new String[]{Plural.get("gui.albionmastery.spec_page.points_tip.spent", count, count, specTerm),
                I18n.get("gui.albionmastery.spec_page.points_tip.minus", spent)});
        colors.add(MasteryPalette.WARN);
        rows.add(null);
        colors.add(0);
        rows.add(new String[]{I18n.get("gui.albionmastery.spec_page.points_tip.left"),
                I18n.get("gui.albionmastery.spec_page.points_tip.value", left)});
        colors.add(left < 0 ? MasteryPalette.WARN : MasteryPalette.GOLD_BRIGHT);
        notes.add(I18n.get("gui.albionmastery.spec_page.points_tip.respec", duration(rules.respecCooldownSeconds)));

        int maxText = Math.min(220, this.width - 8 - 12);
        // 标题（「改装点 · 枪械共用」）和来源名可能很长（附属包全名），放不下就折行，不截成省略号
        List<String> titleLines = TextWrap.wrap(this.font, title, maxText);
        float widest = 0;
        for (String line : titleLines) {
            widest = Math.max(widest, fw(line));
        }
        for (String[] row : rows) {
            if (row != null) {
                widest = Math.max(widest, fw(row[0]) + 16 + fw(row[1]));
            }
        }
        List<String> noteLines = new ArrayList<>();
        for (String note : notes) {
            noteLines.addAll(TextWrap.wrap(this.font, note, maxText));
        }
        for (String line : noteLines) {
            widest = Math.max(widest, fw(line));
        }
        int width = (int) Math.ceil(Math.min(widest, maxText));
        // 每行左边的名字放不下时折成几行，右边的数值跟第一行
        List<List<String>> rowLabels = new ArrayList<>();
        int rowsHeight = 0;
        for (String[] row : rows) {
            if (row == null) {
                rowLabels.add(null);
                rowsHeight += 4;
                continue;
            }
            List<String> lines = TextWrap.wrap(this.font, row[0],
                    Math.max(20, (int) (width - fw(row[1]) - NAME_VALUE_GAP)));
            rowLabels.add(lines);
            rowsHeight += LINE * Math.max(1, lines.size());
        }
        int height = titleLines.size() * LINE + 3 + rowsHeight + 3 + noteLines.size() * LINE;
        int boxWidth = width + 12;
        int boxHeight = height + 10;
        int x = Mth.clamp(mouseX + 12, 4, Math.max(4, this.width - 4 - boxWidth));
        int y = Mth.clamp(mouseY - 8, HEADER_HEIGHT + 2, Math.max(HEADER_HEIGHT + 2, this.height - 4 - boxHeight));
        panel(guiGraphics, x, y, boxWidth, boxHeight);
        int cy = y + 5;
        for (String line : titleLines) {
            guiGraphics.drawString(this.font, ellipsis(line, width), x + 6, cy, MasteryPalette.GOLD, false);
            cy += LINE;
        }
        cy += 3;
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row == null) {
                guiGraphics.fill(x + 6, cy + 1, x + 6 + width, cy + 2, DIVIDER);
                cy += 4;
                continue;
            }
            drawRight(guiGraphics, row[1], x + 6 + width, cy, colors.get(i));
            List<String> labels = rowLabels.get(i);
            float labelMax = width - fw(row[1]) - NAME_VALUE_GAP;
            for (String line : labels.isEmpty() ? List.of(row[0]) : labels) {
                guiGraphics.drawString(this.font, ellipsis(line, labelMax), x + 6, cy, MasteryPalette.TEXT_DIM, false);
                cy += LINE;
            }
        }
        cy += 3;
        for (String line : noteLines) {
            guiGraphics.drawString(this.font, line, x + 6, cy, TEXT_FAINT, false);
            cy += LINE;
        }
    }

    /** 「枪械每升 5 级 +1，满级 20 点；下一点在 55 级」：除不尽时只写满级给多少。 */
    private String levelRule(String name, int atMax, int maxLevel, int next) {
        String rule = atMax > 0 && maxLevel % atMax == 0
                ? Plural.get("gui.albionmastery.spec_page.points_tip.every", maxLevel / atMax, name, maxLevel / atMax,
                        atMax)
                : I18n.get("gui.albionmastery.spec_page.points_tip.at_max", name, atMax);
        return next > 0 ? I18n.get("gui.albionmastery.spec_page.points_tip.with_next", rule, next) : rule;
    }

    /** 普通版 Lv1～N（或突破版 ★1～N）每一级的价格。 */
    private static int[] costs(SpecRules rules, boolean breakthrough) {
        int max = breakthrough ? rules.breakthroughMax() : rules.normalMax();
        int[] costs = new int[max];
        for (int level = 1; level <= max; level++) {
            costs[level - 1] = breakthrough ? rules.breakthroughCost(level) : rules.normalCost(level);
        }
        return costs;
    }

    /** 「普通版 Lv1～5，每级 1 点」：每级价格不一样时写一共多少。 */
    private static String costRule(String range, int[] costs) {
        boolean same = true;
        int sum = 0;
        for (int cost : costs) {
            same &= cost == costs[0];
            sum += cost;
        }
        return same && costs.length > 0
                ? I18n.get("gui.albionmastery.spec_page.points_tip.cost_each", range, costs[0], unit(costs[0]))
                : I18n.get("gui.albionmastery.spec_page.points_tip.cost_total", range, sum, unit(sum));
    }

    /** 价格后面的单位：英文 1 pt / 3 pts，中文都是「点」。 */
    private static String unit(int n) {
        return Plural.get("gui.albionmastery.spec_page.points_tip.unit_name", n);
    }

    /** 秒数写成「24 小时」「30 分钟」。 */
    private static String duration(int seconds) {
        if (seconds > 0 && seconds % 3600 == 0) {
            return I18n.get("gui.albionmastery.spec_page.guide.hours", seconds / 3600);
        }
        return I18n.get("gui.albionmastery.spec_page.guide.minutes", Math.max(1, (seconds + 59) / 60));
    }

    /**
     * 合计效果；鼠标停在卡片上时换成「装进选中槽之后」的预览，变了的行亮起来，被替换掉的行划掉。
     *
     * @return 结束后的 Y
     */
    private int drawTotals(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, int x, int y, int width,
                           int limit, int mouseX, int mouseY, int viewTop, int viewBottom) {
        SpecDescriber.Entries current = entriesOf(combined(snapshot, Map.of()));
        Specialization candidate = this.viewOnly || this.regions.layout() == SpecScreenLayout.C
                ? null : this.hoveredSpec;
        Component title;
        SpecDescriber.Entries shown = current;
        SpecDescriber.Entries before = null;
        Component blocked = null;
        if (candidate == null) {
            title = Component.translatable("gui.albionmastery.spec_page.total");
        } else if (candidate == specInSlot(this.selectedSlot)) {
            title = Component.translatable("gui.albionmastery.spec_page.preview_remove",
                    SpecTerms.slot(this.providerId, this.selectedSlot));
            Map<Integer, Specialization> change = new HashMap<>();
            change.put(this.selectedSlot, null);
            shown = entriesOf(combined(snapshot, change));
            before = current;
        } else {
            title = Component.translatable("gui.albionmastery.spec_page.preview",
                    SpecTerms.slot(this.providerId, this.selectedSlot));
            blocked = equipBlocked(snapshot, candidate, this.selectedSlot);
            if (blocked == null) {
                Map<Integer, Specialization> change = new HashMap<>();
                change.put(this.selectedSlot, candidate);
                shown = entriesOf(combined(snapshot, change));
                before = current;
            }
        }
        int cursor = drawSection(guiGraphics, title.getString(), x, y, width);
        if (blocked != null) {
            for (String line : TextWrap.wrap(this.font, blocked.getString(), width)) {
                guiGraphics.drawString(this.font, line, x, cursor, MasteryPalette.WARN, false);
                cursor += LINE;
            }
            return cursor;
        }
        if (!gatePassed(snapshot)) {
            for (String line : TextWrap.wrap(this.font, I18n.get("gui.albionmastery.spec_page.total_gated"), width)) {
                guiGraphics.drawString(this.font, line, x, cursor, TEXT_FAINT, false);
                cursor += LINE;
            }
        }
        int listTop = cursor;
        List<Row> rows = rows(shown, before, false);
        int end = drawRows(guiGraphics, rows, x, cursor, width, limit, true);
        // 藏了几项时，鼠标停在这一块上弹出完整的
        if (hiddenCount(rows, limit) > 0 && mouseY >= viewTop && mouseY < viewBottom
                && inside(mouseX, mouseY, x, listTop, width, end - listTop)) {
            String header = title.getString();
            this.pendingTooltip = () -> drawRowsTooltip(guiGraphics, header, rows, mouseX, mouseY);
        }
        return end;
    }

    private int respecHeight(int width) {
        return respecSideBySide(width) ? SIDE_BUTTON_HEIGHT : SIDE_BUTTON_HEIGHT * 2 + 4;
    }

    /** 两个重置按钮的字都放得下半宽就并排，否则上下叠。 */
    private boolean respecSideBySide(int width) {
        int half = (width - 4) / 2 - 6;
        String mod = I18n.get("gui.albionmastery.spec_page.respec_source", SpecTerms.of(this.providerId, "point")
                .getString());
        String cooldown = I18n.get("gui.albionmastery.spec_page.respec_cooldown", mod, "23:59");
        String refund = I18n.get("gui.albionmastery.spec_page.respec_refund", mod, 99);
        return Math.max(this.font.width(cooldown), this.font.width(refund)) <= half;
    }

    private String respecLabel(String base, long remaining, boolean armed, int spent) {
        return remaining > 0L
                ? I18n.get("gui.albionmastery.spec_page.respec_cooldown", base, clock(remaining))
                : armed ? I18n.get("gui.albionmastery.spec_page.respec_confirm")
                : spent > 0 ? I18n.get("gui.albionmastery.spec_page.respec_refund", base, spent)
                : I18n.get("gui.albionmastery.spec_page.respec_nothing", base);
    }

    /**
     * 底部两个重置按钮：重置这个来源的改装点 / 这个类型的突破点。冷却中写还剩多久；
     * 点第一下变成「再点一次确认」，三秒内再点才真的发请求（退点要等一天才能再退，误点代价太大）。
     */
    private void drawRespecButtons(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, int x, int y,
                                   int width, int mouseX, int mouseY) {
        SpecRules rules = snapshot.specRules;
        long now = System.currentTimeMillis();
        String pointName = SpecTerms.of(this.providerId, "point").getString();
        boolean sideBySide = respecSideBySide(width);
        int buttonWidth = sideBySide ? (width - 4) / 2 : width;
        String[] pools = {this.providerId, this.typeKey};
        for (int i = 0; i < 2; i++) {
            String pool = pools[i];
            boolean source = i == 0;
            int bx = sideBySide ? (source ? x : x + width - buttonWidth) : x;
            int by = sideBySide ? y : y + i * (SIDE_BUTTON_HEIGHT + 4);
            String base = source ? I18n.get("gui.albionmastery.spec_page.respec_source", pointName)
                    : I18n.get("gui.albionmastery.spec_page.respec_type");
            long remaining = SpecPoints.respecRemainingMillis(this.data, pool, rules, now);
            int spent = source ? SpecPoints.modPointsSpent(this.data, this.providerId, rules)
                    : SpecPoints.breakthroughPointsSpent(this.data, this.typeKey, rules);
            boolean enabled = remaining <= 0L && spent > 0;
            boolean armed = pool.equals(this.armedPool);
            // 能点时写退几点，没花过写「还没花」，冷却中写还剩多久
            String label = respecLabel(base, remaining, armed, spent);
            if (this.font.width(label) > buttonWidth - 6) {
                // 点数名长、按钮窄时换短说法，悬停看全文
                String full = label;
                label = respecLabel(I18n.get(source ? "gui.albionmastery.spec_page.respec_source_short"
                        : "gui.albionmastery.spec_page.respec_type_short"), remaining, armed, spent);
                if (!label.equals(full)) {
                    tipIfClipped(guiGraphics, full, label, bx, by, buttonWidth, SIDE_BUTTON_HEIGHT);
                }
            }
            boolean hover = inside(mouseX, mouseY, bx, by, buttonWidth, SIDE_BUTTON_HEIGHT);
            drawButton(guiGraphics, bx, by, buttonWidth, SIDE_BUTTON_HEIGHT, Component.literal(label),
                    hover && enabled, enabled, armed);
            if (!enabled) {
                continue;
            }
            this.hits.add(new Hit(bx, by, buttonWidth, SIDE_BUTTON_HEIGHT, button -> {
                if (button != 0) {
                    return false;
                }
                playClick();
                if (pool.equals(this.armedPool) && Util.getMillis() <= this.armedUntil) {
                    this.armedPool = null;
                    NetworkHandler.sendToServer(new C2SSpecProgressPacket(source
                            ? C2SSpecProgressPacket.Action.RESPEC_SOURCE : C2SSpecProgressPacket.Action.RESPEC_TYPE,
                            pool, ""));
                    this.lastRequestMs = Util.getMillis();
                } else {
                    this.armedPool = pool;
                    this.armedUntil = Util.getMillis() + CONFIRM_WINDOW_MS;
                }
                return true;
            }));
        }
    }

    // ---------------------------------------------------------------- 卡片（A、B）

    /**
     * 卡片网格：按分组分段，每段一个小标题，卡片按列数排；一行的高度取这一行里最高的卡片。
     */
    private void drawCardGrid(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, Regions r, int listTop,
                              int mouseX, int mouseY) {
        int left = r.mainLeft();
        int width = r.mainRight() - r.mainLeft() - 4;
        int minWidth = r.layout() == SpecScreenLayout.B ? 140 : 158;
        int columns = Mth.clamp((width + GAP) / (minWidth + GAP), 1, 3);
        int listBottom = r.mainBottom();

        List<Specialization> visible = filtered(snapshot);
        List<Block> blocks = new ArrayList<>();
        int cursor = 0;
        String currentFamily = null;
        List<Specialization> row = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            Specialization spec = visible.get(i);
            if (i == 0 || !spec.family().equals(currentFamily)) {
                cursor = flushRow(blocks, row, cursor, width, columns, snapshot);
                currentFamily = spec.family();
                blocks.add(new Block(null, 0, cursor, width, LINE + 3, I18n.get(spec.familyKey())));
                cursor += LINE + 3;
            }
            row.add(spec);
            if (row.size() == columns) {
                cursor = flushRow(blocks, row, cursor, width, columns, snapshot);
            }
        }
        cursor = flushRow(blocks, row, cursor, width, columns, snapshot);

        int viewport = Math.max(1, listBottom - listTop);
        this.listMaxScroll = Math.max(0.0D, cursor - viewport);
        this.listScroll = Mth.clamp(this.listScroll, 0.0D, this.listMaxScroll);
        int offset = listTop - (int) Math.round(this.listScroll);
        this.listClip = new int[]{left, listTop, r.mainRight() - left, listBottom - listTop};
        int firstRowY = -1;
        for (Block block : blocks) {
            if (block.spec() == null) {
                continue;
            }
            this.contentY.put(block.spec(), block.y());
            if (firstRowY < 0) {
                firstRowY = block.y();
            }
            if (block.y() == firstRowY) {
                this.firstRowRect = union(this.firstRowRect, left + block.x(), offset + block.y(), block.width(),
                        block.height());
            }
        }

        guiGraphics.enableScissor(left, listTop, r.mainRight(), listBottom);
        if (visible.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, Component.translatable("gui.albionmastery.spec_page.no_match",
                    SpecTerms.of(this.providerId, "spec")), (left + r.mainRight()) / 2, listTop + 20,
                    MasteryPalette.TEXT_DIM);
        }
        boolean mouseInList = mouseY >= listTop && mouseY < listBottom;
        for (Block block : blocks) {
            int top = offset + block.y();
            if (block.spec() != null) {
                this.cardRects.put(block.spec(), new int[]{left + block.x(), top, block.width(), block.height()});
            }
            if (top + block.height() < listTop || top > listBottom) {
                continue;
            }
            int x = left + block.x();
            if (block.spec() == null) {
                drawFamilyHeader(guiGraphics, block.header(), x, top, block.width());
                continue;
            }
            boolean hover = mouseInList && inside(mouseX, mouseY, x, top, block.width(), block.height());
            CardView card = cardView(block.spec(), snapshot);
            drawCard(guiGraphics, card, x, top, block.width(), block.height(), hover, mouseX, mouseY,
                    listTop, listBottom);
            if (hover) {
                this.hoveredSpec = block.spec();
                int cardX = x;
                int cardTop = top;
                int cardWidthFinal = block.width();
                int cardHeight = block.height();
                this.pendingTooltip = () -> drawCardTooltip(guiGraphics, snapshot, card, cardX, cardTop,
                        cardWidthFinal, cardHeight);
            }
        }
        guiGraphics.disableScissor();
        if (this.listMaxScroll > 0.0D) {
            drawScrollbar(guiGraphics, r.mainRight() - 2, listTop, listBottom, viewport, cursor, this.listScroll);
        }
    }

    private int flushRow(List<Block> blocks, List<Specialization> row, int cursor, int width, int columns,
                         MasteryConfig.Snapshot snapshot) {
        if (row.isEmpty()) {
            return cursor;
        }
        int height = 0;
        for (Specialization spec : row) {
            height = Math.max(height, cardHeight(cardView(spec, snapshot)));
        }
        // 除不尽的几像素按列摊开（每张最多宽 1），最右一列和分组标题的横线、搜索框右边对齐
        int span = width + GAP;
        for (int i = 0; i < row.size(); i++) {
            int x = i * span / columns;
            int cardWidth = (i + 1) * span / columns - GAP - x;
            blocks.add(new Block(row.get(i), x, cursor, cardWidth, height, null));
        }
        row.clear();
        return cursor + height + GAP;
    }

    private int cardHeight(CardView card) {
        int effects = rowsHeight(rows(card.entries(), null, card.preview()), CARD_EFFECT_LINES);
        return PAD + LINE + 1 + LINE + 1 + effects + 5 + BUTTON_HEIGHT + PAD;
    }

    private void drawFamilyHeader(GuiGraphics guiGraphics, String name, int x, int y, int width) {
        guiGraphics.drawString(this.font, name, x, y + 1, MasteryPalette.GOLD, false);
        int lineLeft = x + this.font.width(name) + 4;
        if (lineLeft < x + width) {
            guiGraphics.fill(lineLeft, y + 5, x + width, y + 6, 0x406B5223);
        }
    }

    /**
     * 画一张卡片：名字和状态、等级点 / 突破星、效果（V1：一项一行）、右下角的升级 / 突破按钮。
     * 点卡片本身是装进选中的槽（已经装在这个槽里就是卸下），点按钮是升级。
     */
    private void drawCard(GuiGraphics guiGraphics, CardView card, int x, int y, int width, int height,
                          boolean hover, int mouseX, int mouseY, int clipTop, int clipBottom) {
        boolean equipped = card.inSlot() != 0;
        guiGraphics.fill(x, y, x + width, y + height, 0xB3130F09);
        if (equipped) {
            guiGraphics.fill(x, y, x + width, y + height, 0x24E8C25A);
        } else if (hover) {
            guiGraphics.fill(x, y, x + width, y + height, 0x14FFFFFF);
        }
        frame(guiGraphics, x, y, width, height,
                equipped ? MasteryPalette.GOLD : hover ? MasteryPalette.GOLD_DARK : CARD_EDGE);
        if (equipped) {
            guiGraphics.fill(x + 1, y + 1, x + 3, y + height - 1, MasteryPalette.GOLD);
        }
        int inner = width - PAD * 2;
        int tagWidth = tagWidth(card);
        String name = I18n.get(card.spec().nameKey());
        // 名字优先：右上角的状态（装在哪个槽、未解锁、可升级）和名字一行放不下就不画。
        // 装着的有金框，未解锁的是暗的，能不能升级看按钮，悬停面板里也都写着
        boolean showTag = tagWidth > 0 && fw(name) + NAME_VALUE_GAP + 2 + tagWidth <= inner;
        int nameColor = equipped ? MasteryPalette.GOLD_BRIGHT : card.dimmed() ? MasteryPalette.TEXT_DIM
                : MasteryPalette.TEXT;
        guiGraphics.drawString(this.font, ellipsis(name, inner - (showTag ? tagWidth + NAME_VALUE_GAP + 2 : 0)),
                x + PAD, y + PAD, nameColor, false);
        if (showTag) {
            drawTag(guiGraphics, card, x + width - PAD, y + PAD);
        }
        int levelY = y + PAD + LINE + 1;
        int marks = drawCardMarks(guiGraphics, card, x + PAD, levelY);
        guiGraphics.drawString(this.font, ellipsis(card.levelText(), inner - marks - 5), x + PAD + marks + 5, levelY,
                TEXT_FAINT, false);
        drawRows(guiGraphics, rows(card.entries(), null, card.preview()), x + PAD, levelY + LINE + 1, inner,
                CARD_EFFECT_LINES, true);

        // 整张卡是「装进选中的槽」，按钮后加，点击时先命中按钮
        if (!this.viewOnly) {
            int hitTop = Math.max(y, clipTop);
            int hitBottom = Math.min(y + height, clipBottom);
            if (hitBottom > hitTop) {
                Specialization spec = card.spec();
                this.hits.add(new Hit(x, hitTop, width, hitBottom - hitTop, button -> {
                    if (button != 0) {
                        return false;
                    }
                    toggleInSelectedSlot(spec);
                    return true;
                }));
            }
        }
        int buttonY = y + height - PAD - BUTTON_HEIGHT;
        drawProgressButton(guiGraphics, card, x + width - PAD, buttonY, inner, mouseX, mouseY, clipTop, clipBottom);
    }

    /** 右上角状态占多宽（带框的角标算上框）。 */
    private int tagWidth(CardView card) {
        if (card.tag() == null) {
            return 0;
        }
        return card.tagFramed() ? (int) Math.ceil(fw(card.tag()) - 1.0F) + 6 : (int) Math.ceil(fw(card.tag()));
    }

    /**
     * 右上角状态：槽名、未解锁、同组冲突是纯字；「可升级 / 可突破」是带框的角标——框的外边贴着 {@code right}，
     * 和下面数值列的右边同一列像素，字在框里左右各空 2 像素。
     *
     * @param y 这一行字的顶
     */
    private void drawTag(GuiGraphics guiGraphics, CardView card, int right, int y) {
        if (card.tag() == null) {
            return;
        }
        if (!card.tagFramed()) {
            drawRight(guiGraphics, card.tag(), right, y, card.tagColor());
            return;
        }
        int boxWidth = tagWidth(card);
        int boxLeft = right - boxWidth;
        // 框高 12：汉字字形占满 8 行，上下各空 1 行
        guiGraphics.fill(boxLeft, y - 2, right, y + 10, (card.tagColor() & 0xFFFFFF) | 0x26000000);
        frame(guiGraphics, boxLeft, y - 2, boxWidth, 12, card.tagColor());
        drawRight(guiGraphics, card.tag(), right - 3, y, card.tagColor());
    }

    /**
     * 卡片右下角：升级 / 解锁 / 突破按钮；满级了写「已满级」。按钮右边对齐到 {@code right}。
     */
    private void drawProgressButton(GuiGraphics guiGraphics, CardView card, int right, int y, int maxWidth,
                                    int mouseX, int mouseY, int clipTop, int clipBottom) {
        Progress progress = card.progress();
        if (progress == null) {
            if (!this.viewOnly) {
                drawRight(guiGraphics, I18n.get("gui.albionmastery.spec_page.maxed"), right, y + 2, TEXT_FAINT);
            }
            return;
        }
        String label = progress.label();
        int width = Math.min(maxWidth, this.font.width(label) + 10);
        int x = right - width;
        this.buttonRects.put(card.spec(), new int[]{x, y, width, BUTTON_HEIGHT});
        boolean visible = y + BUTTON_HEIGHT > clipTop && y < clipBottom;
        boolean hover = visible && progress.enabled() && inside(mouseX, mouseY, x, y, width, BUTTON_HEIGHT)
                && mouseY >= clipTop && mouseY < clipBottom;
        drawButton(guiGraphics, x, y, width, BUTTON_HEIGHT, Component.literal(label), hover, progress.enabled(),
                progress.enabled() && progress.breakthrough());
        if (visible && progress.enabled()) {
            int hitTop = Math.max(y, clipTop);
            this.hits.add(new Hit(x, hitTop, width, Math.min(y + BUTTON_HEIGHT, clipBottom) - hitTop, button -> {
                if (button != 0) {
                    return false;
                }
                sendProgress(card.spec(), progress.breakthrough());
                return true;
            }));
        }
    }

    /**
     * 卡片上的等级点：普通版方点，普通版满级（或已经突破过）后接着画突破星。
     *
     * @return 占的宽度
     */
    private int drawCardMarks(GuiGraphics guiGraphics, CardView card, int x, int y) {
        int width = drawPips(guiGraphics, x, y + 2, card.normalLevel(), rules().normalMax());
        if (card.normalLevel() >= rules().normalMax() || card.star() > 0) {
            width += 4;
            width += drawStars(guiGraphics, x + width, y + 1, card.star(), rules().breakthroughMax());
        }
        return width;
    }

    /**
     * 鼠标停在卡片上：旁边弹出这个特化的每级数值表，合成句（暴击、斩杀血线……）的整句列在表下面。
     * 优先放在卡片右边，放不下放左边，都放不下贴着屏幕边。
     */
    private void drawCardTooltip(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, CardView card,
                                 int cardX, int cardY, int cardWidth, int cardHeight) {
        int maxWidth = Math.max(160, Math.min(320, this.width - 16));
        TableLayout table = tableLayout(snapshot, card.spec(), maxWidth);
        List<String> fulls = fullSentences(card.entries(), table.width());
        String title = I18n.get(card.spec().nameKey());
        int width = Math.max(table.width(), this.font.width(title));
        for (String line : fulls) {
            width = Math.max(width, this.font.width(line));
        }
        int height = LINE + 3 + table.height() + (fulls.isEmpty() ? 0 : 4 + fulls.size() * LINE);
        int boxWidth = width + 12;
        int boxHeight = height + 10;
        int x = cardX + cardWidth + 6;
        if (x + boxWidth > this.width - 4) {
            x = cardX - 6 - boxWidth;
        }
        if (x < 4) {
            x = Mth.clamp(this.width - 4 - boxWidth, 4, this.width);
        }
        int y = Mth.clamp(cardY, HEADER_HEIGHT + 2, Math.max(HEADER_HEIGHT + 2, this.height - 4 - boxHeight));
        panel(guiGraphics, x, y, boxWidth, boxHeight);
        int cx = x + 6;
        int cy = y + 5;
        guiGraphics.drawString(this.font, title, cx, cy, MasteryPalette.GOLD_BRIGHT, false);
        cy += LINE + 3;
        drawTable(guiGraphics, snapshot, card.spec(), table, cx, cy, card.breakthrough(),
                card.preview() ? 0 : card.level());
        cy += table.height();
        if (!fulls.isEmpty()) {
            cy += 4;
            for (String line : fulls) {
                guiGraphics.drawString(this.font, line, cx, cy, MasteryPalette.TEXT_DIM, false);
                cy += LINE;
            }
        }
    }

    /** 合成句的整句（去重、按宽度折行），给悬停面板用。 */
    private List<String> fullSentences(SpecDescriber.Entries entries, int width) {
        Set<String> seen = new LinkedHashSet<>();
        for (SpecDescriber.Entry entry : entries.gains()) {
            if (entry.full() != null) {
                seen.add("+ " + entry.full().getString());
            }
        }
        for (SpecDescriber.Entry entry : entries.costs()) {
            if (entry.full() != null) {
                seen.add(MINUS + " " + entry.full().getString());
            }
        }
        List<String> lines = new ArrayList<>();
        for (String sentence : seen) {
            lines.addAll(TextWrap.wrap(this.font, sentence, Math.max(120, width)));
        }
        return lines;
    }

    // ---------------------------------------------------------------- 列表 + 详情（C）

    /**
     * C 版中栏：按分组列出名字和等级，点一行在右栏看详情。
     */
    private void drawList(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, Regions r, int mouseX,
                          int mouseY) {
        int left = r.mainLeft();
        int right = r.mainRight();
        int listTop = r.mainTop() + SEARCH_HEIGHT + 4;
        int listBottom = r.mainBottom();
        List<Specialization> visible = filtered(snapshot);
        if (this.detailSpec == null && !visible.isEmpty()) {
            this.detailSpec = specInSlot(this.selectedSlot) != null ? specInSlot(this.selectedSlot) : visible.get(0);
        }
        int content = 0;
        String family = null;
        for (Specialization spec : visible) {
            if (!spec.family().equals(family)) {
                content += family != null ? LIST_GROUP_GAP : 0;
                family = spec.family();
                content += LINE + 3;
            }
            content += LIST_ROW_HEIGHT;
        }
        int viewport = Math.max(1, listBottom - listTop);
        this.listMaxScroll = Math.max(0.0D, content - viewport);
        this.listScroll = Mth.clamp(this.listScroll, 0.0D, this.listMaxScroll);
        int cursor = listTop - (int) Math.round(this.listScroll);
        int scrollOffset = cursor;
        this.listClip = new int[]{left, listTop, right - left, listBottom - listTop};

        guiGraphics.enableScissor(left, listTop, right, listBottom);
        if (visible.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, Component.translatable("gui.albionmastery.spec_page.no_match",
                    SpecTerms.of(this.providerId, "spec")), (left + right) / 2, listTop + 20, MasteryPalette.TEXT_DIM);
        }
        family = null;
        int rowRight = right - (this.listMaxScroll > 0.0D ? 5 : 0);
        for (Specialization spec : visible) {
            if (!spec.family().equals(family)) {
                cursor += family != null ? LIST_GROUP_GAP : 0;
                family = spec.family();
                if (cursor + LINE > listTop && cursor < listBottom) {
                    drawFamilyHeader(guiGraphics, I18n.get(spec.familyKey()), left, cursor, rowRight - left);
                }
                cursor += LINE + 3;
            }
            if (cursor + LIST_ROW_HEIGHT > listTop && cursor < listBottom) {
                drawListRow(guiGraphics, snapshot, spec, left, cursor, rowRight - left, mouseX, mouseY,
                        listTop, listBottom);
            }
            this.cardRects.put(spec, new int[]{left, cursor, rowRight - left, LIST_ROW_HEIGHT});
            this.contentY.put(spec, cursor - scrollOffset);
            cursor += LIST_ROW_HEIGHT;
        }
        guiGraphics.disableScissor();
        if (this.listMaxScroll > 0.0D) {
            drawScrollbar(guiGraphics, right - 2, listTop, listBottom, viewport, content, this.listScroll);
        }
    }

    private void drawListRow(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, Specialization spec, int x,
                             int y, int width, int mouseX, int mouseY, int clipTop, int clipBottom) {
        boolean selected = spec == this.detailSpec;
        boolean hover = mouseY >= clipTop && mouseY < clipBottom && inside(mouseX, mouseY, x, y, width,
                LIST_ROW_HEIGHT);
        if (selected) {
            guiGraphics.fill(x, y, x + width, y + LIST_ROW_HEIGHT, 0x29E8C25A);
            frame(guiGraphics, x, y, width, LIST_ROW_HEIGHT, MasteryPalette.GOLD);
        } else if (hover) {
            guiGraphics.fill(x, y, x + width, y + LIST_ROW_HEIGHT, 0x14FFFFFF);
        }
        int level = this.data.getSpecLevel(spec.key());
        int star = this.data.getBreakthrough(this.typeKey, spec.key());
        int pipsWidth = rules().normalMax() * 5 - 1;
        int starsWidth = level >= rules().normalMax() || star > 0 ? 4 + rules().breakthroughMax() * 6 - 1 : 0;
        int marks = pipsWidth + starsWidth;
        int right = x + width - 4;
        int markX = right - marks;
        drawPips(guiGraphics, markX, y + 4, level, rules().normalMax());
        if (starsWidth > 0) {
            drawStars(guiGraphics, markX + pipsWidth + 4, y + 3, star, rules().breakthroughMax());
        }
        int slot = slotOf(spec);
        String tag = slot == 0 ? "" : SpecTerms.slot(this.providerId, slot).getString();
        int room = markX - NAME_VALUE_GAP - (x + 4);
        String name = I18n.get(spec.nameKey());
        int nameColor = slot != 0 || selected ? MasteryPalette.GOLD_BRIGHT
                : level > 0 ? MasteryPalette.TEXT : MasteryPalette.TEXT_DIM;
        if (!tag.isEmpty() && fw(name) + 5 + fw(tag) <= room) {
            guiGraphics.drawString(this.font, name, x + 4, y + 3, nameColor, false);
            guiGraphics.drawString(this.font, tag, x + 4 + this.font.width(name) + 5, y + 3, MasteryPalette.GOLD,
                    false);
        } else {
            guiGraphics.drawString(this.font, ellipsis(name, room), x + 4, y + 3, nameColor, false);
        }
        int hitTop = Math.max(y, clipTop);
        int hitBottom = Math.min(y + LIST_ROW_HEIGHT, clipBottom);
        if (hitBottom > hitTop) {
            this.hits.add(new Hit(x, hitTop, width, hitBottom - hitTop, button -> {
                if (button != 0) {
                    return false;
                }
                this.detailSpec = spec;
                this.detailScroll = 0.0D;
                playClick();
                return true;
            }));
        }
    }

    /**
     * C 版右栏：选中特化的名字、分组、每级数值表、升级和装备按钮、「装上之后和现在比」。
     */
    private void drawDetail(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, Regions r, int mouseX,
                            int mouseY) {
        int left = r.detailLeft();
        int right = this.width - MARGIN;
        int width = right - left;
        int top = r.mainTop();
        int bottom = r.mainBottom();
        Specialization spec = this.detailSpec;
        if (spec == null) {
            return;
        }
        CardView card = cardView(spec, snapshot);
        // 两个按钮钉在栏底，上面的内容单独滚动：数值表一长，按钮跟着内容走就会被栏底裁掉一半
        int buttonHeight = SIDE_BUTTON_HEIGHT;
        int buttonsTop = bottom - buttonHeight;
        int scrollBottom = this.viewOnly ? bottom : buttonsTop - 4;
        guiGraphics.enableScissor(left, top, right, scrollBottom);
        this.detailClip = new int[]{left, top, width, bottom - top};
        int start = top - (int) Math.round(this.detailScroll);
        // 顶上留 2 像素给「可升级 / 可突破」角标的框
        int cursor = start + 2;

        int tagWidth = card.tag() == null ? 0 : tagWidth(card) + NAME_VALUE_GAP;
        guiGraphics.drawString(this.font, ellipsis(I18n.get(spec.nameKey()), width - tagWidth), left, cursor,
                MasteryPalette.GOLD_BRIGHT, false);
        drawTag(guiGraphics, card, right, cursor);
        cursor += LINE + 1;
        String family = I18n.get(spec.familyKey());
        Specialization clash = clashingSpec(spec, this.selectedSlot);
        if (clash != null) {
            family = I18n.get("gui.albionmastery.spec_page.detail_family_clash", family, I18n.get(clash.nameKey()),
                    SpecTerms.of(this.providerId, "group").getString());
        }
        for (String line : TextWrap.wrap(this.font, family, width)) {
            guiGraphics.drawString(this.font, line, left, cursor, MasteryPalette.TEXT_DIM, false);
            cursor += LINE;
        }
        cursor += 4;

        TableLayout table = tableLayout(snapshot, spec, width);
        drawTable(guiGraphics, snapshot, spec, table, left, cursor, card.breakthrough(),
                card.preview() ? 0 : card.level());
        cursor += table.height() + 4;
        for (String line : TextWrap.wrap(this.font, I18n.get("gui.albionmastery.spec_page.detail_break_hint",
                this.typeName.getString()), width)) {
            guiGraphics.drawString(this.font, line, left, cursor, TEXT_FAINT, false);
            cursor += LINE;
        }

        int inSlot = slotOf(spec);
        boolean removing = inSlot == this.selectedSlot;
        Component blocked = this.viewOnly || removing ? null : equipBlocked(snapshot, spec, this.selectedSlot);
        if (!this.viewOnly) {
            cursor += 4;
            // 装不进去的原因：放在内容最后，滚到底正好贴着下面的「装进」按钮
            if (blocked != null) {
                for (String line : TextWrap.wrap(this.font, blocked.getString(), width)) {
                    guiGraphics.drawString(this.font, line, left, cursor, MasteryPalette.WARN, false);
                    cursor += LINE;
                }
                cursor += 4;
            }

            // 装上之后和现在比
            if (blocked == null || removing) {
                Map<Integer, Specialization> change = new HashMap<>();
                change.put(this.selectedSlot, removing ? null : spec);
                SpecDescriber.Entries before = entriesOf(combined(snapshot, Map.of()));
                SpecDescriber.Entries after = entriesOf(combined(snapshot, change));
                cursor = drawSection(guiGraphics, I18n.get(removing ? "gui.albionmastery.spec_page.after_remove"
                        : "gui.albionmastery.spec_page.after_title"), left, cursor, width);
                cursor = drawRows(guiGraphics, rows(after, before, false), left, cursor, width, Integer.MAX_VALUE,
                        true);
                Specialization replaced = specInSlot(this.selectedSlot);
                if (!removing && replaced != null && replaced != spec) {
                    guiGraphics.drawString(this.font, ellipsis(I18n.get("gui.albionmastery.spec_page.after_replaced",
                            I18n.get(replaced.nameKey())), width), left, cursor, TEXT_FAINT, false);
                    cursor += LINE;
                }
            }
        }
        guiGraphics.disableScissor();
        int content = cursor - start;
        int viewport = scrollBottom - top;
        this.detailMaxScroll = Math.max(0.0D, content - viewport);
        this.detailScroll = Mth.clamp(this.detailScroll, 0.0D, this.detailMaxScroll);
        if (this.detailMaxScroll > 0.0D) {
            drawScrollbar(guiGraphics, right + 2, top, scrollBottom, viewport, content, this.detailScroll);
        }
        if (this.viewOnly) {
            return;
        }

        // 两个按钮：升级 / 突破、装进选中的槽 / 卸下
        int buttonWidth = (width - 4) / 2;
        Progress progress = card.progress();
        String progressLabel = progress == null ? I18n.get("gui.albionmastery.spec_page.maxed") : progress.label();
        boolean progressEnabled = progress != null && progress.enabled();
        boolean hoverProgress = progressEnabled && inside(mouseX, mouseY, left, buttonsTop, buttonWidth, buttonHeight);
        drawButton(guiGraphics, left, buttonsTop, buttonWidth, buttonHeight, Component.literal(progressLabel),
                hoverProgress, progressEnabled, progressEnabled && progress.breakthrough());
        if (progressEnabled) {
            this.hits.add(new Hit(left, buttonsTop, buttonWidth, buttonHeight, button -> {
                if (button != 0) {
                    return false;
                }
                sendProgress(spec, progress.breakthrough());
                return true;
            }));
        }
        int equipX = left + width - buttonWidth;
        String equipLabel = removing
                ? I18n.get("gui.albionmastery.spec_page.unequip_button",
                SpecTerms.slot(this.providerId, inSlot).getString())
                : I18n.get("gui.albionmastery.spec_page.equip_button",
                SpecTerms.slot(this.providerId, this.selectedSlot).getString());
        boolean equipEnabled = blocked == null;
        boolean hoverEquip = equipEnabled && inside(mouseX, mouseY, equipX, buttonsTop, buttonWidth, buttonHeight);
        drawButton(guiGraphics, equipX, buttonsTop, buttonWidth, buttonHeight, Component.literal(equipLabel),
                hoverEquip, equipEnabled, false);
        this.detailProgressRect = new int[]{left, buttonsTop, buttonWidth, buttonHeight};
        this.detailEquipRect = new int[]{equipX, buttonsTop, buttonWidth, buttonHeight};
        // 遮罩第 4 步照亮的：名字到两个按钮这一段
        int litTop = Math.max(start, top);
        this.detailRect = new int[]{left, litTop, width, bottom - litTop};
        if (equipEnabled) {
            this.hits.add(new Hit(equipX, buttonsTop, buttonWidth, buttonHeight, button -> {
                if (button != 0) {
                    return false;
                }
                toggleInSelectedSlot(spec);
                return true;
            }));
        }
    }

    // ---------------------------------------------------------------- 每级数值表

    /**
     * 排好的数值表：表头一行项名，下面 Lv1～LvN、一条细线、★1～★M。项多到一张表放不下时，按列切成几张上下叠。
     *
     * @param labels   项名（按收益在前、代价在后的顺序）
     * @param costs    哪些项是代价（数值用橙色）
     * @param chunks   每张表包含哪几列（labels 的下标）
     * @param colWidth 每列宽度
     * @param rowLabel 左边等级列的宽度
     * @param width    总宽
     * @param height   总高
     */
    private record TableLayout(List<String> labels, Set<String> costs, List<int[]> chunks, int[] colWidth,
                               int rowLabel, int width, int height) {
    }

    private TableLayout tableLayout(MasteryConfig.Snapshot snapshot, Specialization spec, int maxWidth) {
        SpecRules rules = snapshot.specRules;
        // 项取最高一级的：低级时四舍五入成中性的项（几乎不会）在表里写「—」
        SpecDescriber.Entries top = entries(snapshot, spec, true, rules.breakthroughMax());
        List<String> labels = new ArrayList<>();
        Set<String> costs = new HashSet<>();
        for (SpecDescriber.Entry entry : top.gains()) {
            labels.add(entry.label());
        }
        for (SpecDescriber.Entry entry : top.costs()) {
            labels.add(entry.label());
            costs.add(entry.label());
        }
        int rowLabel = (int) Math.ceil(Math.max(fw("Lv" + rules.normalMax()), fw("★" + rules.breakthroughMax())))
                + 6;
        int[] colWidth = new int[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            float widest = fw(labels.get(i));
            for (int level = 1; level <= rules.normalMax(); level++) {
                widest = Math.max(widest, fw(valueOf(entries(snapshot, spec, false, level), labels.get(i))));
            }
            for (int star = 1; star <= rules.breakthroughMax(); star++) {
                widest = Math.max(widest, fw(valueOf(entries(snapshot, spec, true, star), labels.get(i))));
            }
            colWidth[i] = Math.min((int) Math.ceil(widest) + 6, Math.max(24, maxWidth - rowLabel));
        }
        List<int[]> chunks = new ArrayList<>();
        int width = rowLabel;
        List<Integer> current = new ArrayList<>();
        int used = rowLabel;
        for (int i = 0; i < labels.size(); i++) {
            if (!current.isEmpty() && used + colWidth[i] > maxWidth) {
                chunks.add(current.stream().mapToInt(Integer::intValue).toArray());
                current.clear();
                used = rowLabel;
            }
            current.add(i);
            used += colWidth[i];
            width = Math.max(width, used);
        }
        if (!current.isEmpty()) {
            chunks.add(current.stream().mapToInt(Integer::intValue).toArray());
        }
        int rows = 1 + rules.normalMax() + rules.breakthroughMax();
        int perChunk = rows * LINE + 3;
        int height = chunks.isEmpty() ? LINE : chunks.size() * perChunk + (chunks.size() - 1) * 4;
        return new TableLayout(labels, costs, chunks, colWidth, rowLabel, width, height);
    }

    /**
     * 画数值表。当前等级那一行加框。
     *
     * @param currentBreak 当前高亮的是突破版那几行
     * @param currentLevel 当前高亮第几级；0 不高亮
     */
    private void drawTable(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, Specialization spec,
                           TableLayout table, int x, int y, boolean currentBreak, int currentLevel) {
        SpecRules rules = snapshot.specRules;
        if (table.chunks().isEmpty()) {
            guiGraphics.drawString(this.font, I18n.get("gui.albionmastery.spec_page.no_effect"), x, y,
                    MasteryPalette.TEXT_DIM, false);
            return;
        }
        int cursor = y;
        for (int[] chunk : table.chunks()) {
            int chunkWidth = table.rowLabel();
            for (int index : chunk) {
                chunkWidth += table.colWidth()[index];
            }
            int colX = x + table.rowLabel();
            for (int index : chunk) {
                int colRight = colX + table.colWidth()[index] - 2;
                String label = ellipsis(table.labels().get(index), table.colWidth()[index] - 4);
                drawRight(guiGraphics, label, colRight, cursor, MasteryPalette.TEXT_DIM);
                colX += table.colWidth()[index];
            }
            cursor += LINE;
            for (int pass = 0; pass < 2; pass++) {
                boolean breakthrough = pass == 1;
                int max = breakthrough ? rules.breakthroughMax() : rules.normalMax();
                if (breakthrough) {
                    guiGraphics.fill(x, cursor + 1, x + chunkWidth, cursor + 2, DIVIDER);
                    cursor += 3;
                }
                for (int level = 1; level <= max; level++) {
                    boolean current = breakthrough == currentBreak && level == currentLevel;
                    if (current) {
                        guiGraphics.fill(x - 2, cursor - 1, x + chunkWidth + 1, cursor + LINE - 1, 0x29E8C25A);
                        frame(guiGraphics, x - 2, cursor - 1, chunkWidth + 3, LINE, MasteryPalette.GOLD_DARK);
                    }
                    String rowName = breakthrough ? "★" + level : "Lv" + level;
                    guiGraphics.drawString(this.font, rowName, x, cursor,
                            current ? MasteryPalette.GOLD_BRIGHT : breakthrough ? BREAK : MasteryPalette.TEXT_DIM,
                            false);
                    SpecDescriber.Entries entries = entries(snapshot, spec, breakthrough, level);
                    colX = x + table.rowLabel();
                    for (int index : chunk) {
                        String label = table.labels().get(index);
                        boolean cost = table.costs().contains(label);
                        drawRight(guiGraphics, valueOf(entries, label), colX + table.colWidth()[index] - 2, cursor,
                                cost ? MasteryPalette.WARN : current ? MasteryPalette.GOLD_BRIGHT
                                        : MasteryPalette.TEXT);
                        colX += table.colWidth()[index];
                    }
                    cursor += LINE;
                }
            }
            cursor += 4;
        }
    }

    private static String valueOf(SpecDescriber.Entries entries, String label) {
        for (SpecDescriber.Entry entry : entries.gains()) {
            if (entry.label().equals(label)) {
                return entry.value();
            }
        }
        for (SpecDescriber.Entry entry : entries.costs()) {
            if (entry.label().equals(label)) {
                return entry.value();
            }
        }
        return "—";
    }

    // ---------------------------------------------------------------- 效果列表（V1）

    /**
     * 效果列表的一行。
     *
     * @param gain   是收益（+）还是代价（−）；划掉的行按原来的归属
     * @param label  名字
     * @param value  数值
     * @param mark   名字和数值的颜色档：0 普通、1 变了（亮）、2 划掉、3 预览（暗）
     */
    private record Row(boolean gain, String label, String value, int mark) {
    }

    /**
     * 把得失拆成行。给了 {@code before} 时，数值变了 / 新出现的行标亮，{@code before} 里有、现在没了的行划掉排在最后。
     */
    private static List<Row> rows(SpecDescriber.Entries entries, @Nullable SpecDescriber.Entries before,
                                  boolean preview) {
        Map<String, String> old = new HashMap<>();
        if (before != null) {
            for (SpecDescriber.Entry entry : before.gains()) {
                old.put("+" + entry.label(), entry.value());
            }
            for (SpecDescriber.Entry entry : before.costs()) {
                old.put("-" + entry.label(), entry.value());
            }
        }
        List<Row> rows = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (SpecDescriber.Entry entry : entries.gains()) {
            present.add("+" + entry.label());
            rows.add(new Row(true, entry.label(), entry.value(), mark(before, old.get("+" + entry.label()),
                    entry.value(), preview)));
        }
        for (SpecDescriber.Entry entry : entries.costs()) {
            present.add("-" + entry.label());
            rows.add(new Row(false, entry.label(), entry.value(), mark(before, old.get("-" + entry.label()),
                    entry.value(), preview)));
        }
        if (before != null) {
            for (SpecDescriber.Entry entry : before.gains()) {
                if (!present.contains("+" + entry.label())) {
                    rows.add(new Row(true, entry.label(), entry.value(), 2));
                }
            }
            for (SpecDescriber.Entry entry : before.costs()) {
                if (!present.contains("-" + entry.label())) {
                    rows.add(new Row(false, entry.label(), entry.value(), 2));
                }
            }
        }
        return rows;
    }

    private static int mark(@Nullable SpecDescriber.Entries before, @Nullable String oldValue, String value,
                            boolean preview) {
        if (preview) {
            return 3;
        }
        if (before == null) {
            return 0;
        }
        return value.equals(oldValue) ? 0 : 1;
    }

    /**
     * 放不下时挑哪几行：收益、代价都至少留一行，剩下按顺序，划掉的行最先舍。
     *
     * @return 要画的行（按画的顺序）
     */
    private static List<Row> pickShown(List<Row> rows, int limit) {
        if (rows.size() <= limit) {
            return rows;
        }
        List<Row> gains = new ArrayList<>();
        List<Row> costs = new ArrayList<>();
        for (Row row : rows) {
            if (row.mark() == 2) {
                continue;
            }
            (row.gain() ? gains : costs).add(row);
        }
        int slots = Math.max(1, limit - 1);
        int costShown = Math.min(costs.size(), Math.max(1, slots / 2));
        int gainShown = Math.min(gains.size(), slots - costShown);
        costShown = Math.min(costs.size(), slots - gainShown);
        List<Row> shown = new ArrayList<>(gains.subList(0, gainShown));
        shown.addAll(costs.subList(0, costShown));
        return shown;
    }

    private static int hiddenCount(List<Row> rows, int limit) {
        return rows.size() - pickShown(rows, limit).size();
    }

    private static int rowsHeight(List<Row> rows, int limit) {
        if (rows.isEmpty()) {
            return LINE;
        }
        List<Row> shown = pickShown(rows, limit);
        int height = shown.size() * LINE;
        if (hasDivider(shown)) {
            height += 4;
        }
        if (shown.size() < rows.size()) {
            height += LINE;
        }
        return height;
    }

    private static boolean hasDivider(List<Row> shown) {
        boolean gain = false;
        boolean cost = false;
        for (Row row : shown) {
            if (row.gain()) {
                gain = true;
            } else {
                cost = true;
            }
        }
        return gain && cost;
    }

    /**
     * V1 排法：一项一行，「+ / −」记号、名字靠左，数值右对齐成一列；收益在上、代价在下，中间一条细线；
     * 放不下只列前几行，末尾右对齐写「+N 项」。卡片和左栏合计效果共用。
     *
     * @return 结束后的 Y
     */
    private int drawRows(GuiGraphics guiGraphics, List<Row> rows, int x, int y, int width, int limit, boolean draw) {
        if (rows.isEmpty()) {
            if (draw) {
                guiGraphics.drawString(this.font, I18n.get("gui.albionmastery.spec_page.no_effect"), x, y,
                        MasteryPalette.TEXT_DIM, false);
            }
            return y + LINE;
        }
        List<Row> shown = pickShown(rows, limit);
        boolean divider = hasDivider(shown);
        int cursor = y;
        boolean seenCost = false;
        for (Row row : shown) {
            if (!row.gain() && !seenCost && row.mark() != 2) {
                seenCost = true;
                if (divider) {
                    if (draw) {
                        guiGraphics.fill(x, cursor + 1, x + width, cursor + 2, DIVIDER);
                    }
                    cursor += 4;
                }
            }
            if (draw) {
                drawRow(guiGraphics, row, x, cursor, width);
            }
            cursor += LINE;
        }
        int hidden = rows.size() - shown.size();
        if (hidden > 0) {
            if (draw) {
                drawRight(guiGraphics, I18n.get("gui.albionmastery.spec_page.more_items", hidden), x + width, cursor,
                        TEXT_FAINT);
            }
            cursor += LINE;
        }
        return cursor;
    }

    private void drawRow(GuiGraphics guiGraphics, Row row, int x, int y, int width) {
        boolean struck = row.mark() == 2;
        int markColor = struck ? STRUCK : row.gain() ? MasteryPalette.PROGRESS : MasteryPalette.WARN;
        int nameColor = switch (row.mark()) {
            case 1 -> MasteryPalette.GOLD_BRIGHT;
            case 2 -> STRUCK;
            case 3 -> TEXT_FAINT;
            default -> MasteryPalette.TEXT_DIM;
        };
        int valueColor = switch (row.mark()) {
            case 1 -> row.gain() ? MasteryPalette.GOLD_BRIGHT : 0xFFFFB27A;
            case 2 -> STRUCK;
            case 3 -> row.gain() ? MasteryPalette.TEXT_DIM : 0xFFAA6B36;
            default -> row.gain() ? MasteryPalette.TEXT : MasteryPalette.WARN;
        };
        guiGraphics.drawString(this.font, row.gain() ? "+" : MINUS, x, y, markColor, false);
        String name = ellipsis(row.label(), width - 8 - fw(row.value()) - NAME_VALUE_GAP);
        if (struck) {
            guiGraphics.drawString(this.font, Component.literal(name).withStyle(ChatFormatting.STRIKETHROUGH),
                    x + 8, y, nameColor, false);
            drawRight(guiGraphics, Component.literal(row.value()).withStyle(ChatFormatting.STRIKETHROUGH),
                    x + width, y, valueColor);
        } else {
            guiGraphics.drawString(this.font, name, x + 8, y, nameColor, false);
            drawRight(guiGraphics, row.value(), x + width, y, valueColor);
        }
    }

    /** 合计效果藏了几项时的悬停面板：完整列表，同样的排法。 */
    private void drawRowsTooltip(GuiGraphics guiGraphics, String title, List<Row> rows, int mouseX, int mouseY) {
        float widest = fw(title);
        for (Row row : rows) {
            widest = Math.max(widest, 8 + fw(row.label()) + 12 + fw(row.value()));
        }
        int width = (int) Math.ceil(widest);
        int height = LINE + 3 + rowsHeight(rows, Integer.MAX_VALUE);
        int boxWidth = width + 12;
        int boxHeight = height + 10;
        int x = Mth.clamp(mouseX + 12, 4, Math.max(4, this.width - 4 - boxWidth));
        int y = Mth.clamp(mouseY - 8, HEADER_HEIGHT + 2, Math.max(HEADER_HEIGHT + 2, this.height - 4 - boxHeight));
        panel(guiGraphics, x, y, boxWidth, boxHeight);
        guiGraphics.drawString(this.font, title, x + 6, y + 5, MasteryPalette.GOLD, false);
        drawRows(guiGraphics, rows, x + 6, y + 5 + LINE + 3, width, Integer.MAX_VALUE, true);
    }

    private void showTextTooltip(GuiGraphics graphics, List<String> lines, int mouseX, int mouseY) {
        this.pendingTooltip = () -> {
            int width = 0;
            for (String line : lines) {
                width = Math.max(width, this.font.width(line));
            }
            int boxWidth = width + 12;
            int boxHeight = lines.size() * LINE + 8;
            int x = Mth.clamp(mouseX - boxWidth + 20, 4, Math.max(4, this.width - 4 - boxWidth));
            // 鼠标下方放不下（贴着底边的重置按钮这类）就放到鼠标上方，别盖住鼠标指着的东西
            int y = mouseY + 12 + boxHeight <= this.height - 4 ? mouseY + 12
                    : Math.max(2, mouseY - 6 - boxHeight);
            panel(graphics, x, y, boxWidth, boxHeight);
            int cy = y + 5;
            for (String line : lines) {
                graphics.drawString(this.font, line, x + 6, cy, MasteryPalette.TEXT, false);
                cy += LINE;
            }
        };
    }

    /**
     * 字放不下被省略号截掉时，鼠标停在那一块上就浮出全文；这一帧已经有别的悬停面板就不出。
     */
    private void tipIfClipped(GuiGraphics guiGraphics, String full, String shown, int x, int y, int width,
                              int height) {
        if (this.pendingTooltip == null && !shown.equals(full)
                && inside(this.tipMouseX, this.tipMouseY, x, y, width, height)) {
            showTextTooltip(guiGraphics, List.of(full), this.tipMouseX, this.tipMouseY);
        }
    }

    private void panel(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        guiGraphics.fill(x, y, x + width, y + height, 0xF5130F09);
        frame(guiGraphics, x, y, width, height, MasteryPalette.EDGE);
    }

    private int drawSection(GuiGraphics guiGraphics, String title, int x, int y, int width) {
        guiGraphics.drawString(this.font, ellipsis(title, width), x, y, MasteryPalette.GOLD, false);
        guiGraphics.fill(x, y + LINE, x + width, y + LINE + 1, 0x406B5223);
        return y + LINE + 4;
    }

    private void drawScrollbar(GuiGraphics guiGraphics, int x, int top, int bottom, int viewport, int content,
                               double scroll) {
        int track = Math.max(8, bottom - top - 4);
        int thumb = Mth.clamp((int) ((long) track * viewport / Math.max(1, content)), 12, track);
        double max = Math.max(1.0D, content - viewport);
        int thumbTop = top + 2 + (int) Math.round((track - thumb) * Mth.clamp(scroll / max, 0.0D, 1.0D));
        guiGraphics.fill(x, top + 2, x + 2, top + 2 + track, 0x306B5223);
        guiGraphics.fill(x, thumbTop, x + 2, thumbTop + thumb, MasteryPalette.GOLD);
    }

    /**
     * 底部：刚发过请求时，把服务端发来的动作栏提示（成功 / 冷却 / 点数不够……）画出来；平时是操作说明。
     */
    private void drawFooter(GuiGraphics guiGraphics, Regions r) {
        int left = r.mainLeft();
        int right = r.layout() == SpecScreenLayout.C ? this.width - MARGIN : r.mainRight();
        int y = this.height - MARGIN - FOOTER_HEIGHT + 3;
        if (Util.getMillis() - this.lastRequestMs <= FEEDBACK_WINDOW_MS) {
            GuiOverlayAccessor gui = (GuiOverlayAccessor) Minecraft.getInstance().gui;
            Component message = gui.albionmastery$getOverlayMessage();
            int time = gui.albionmastery$getOverlayMessageTime();
            if (message != null && time > 0) {
                float alpha = Mth.clamp(time / 10.0F, 0.0F, 1.0F);
                String text = ellipsis(message.getString(), right - left);
                int color = RenderUtil.withAlpha(MasteryPalette.TEXT, alpha);
                int style = message.getStyle().getColor() == null ? color
                        : RenderUtil.withAlpha(0xFF000000 | message.getStyle().getColor().getValue(), alpha);
                guiGraphics.drawString(this.font, text, left + (right - left - this.font.width(text) + 1) / 2, y,
                        style, false);
                return;
            }
        }
        if (this.viewOnly) {
            return;
        }
        if (r.barTop() > 0) {
            // 引导条开着：「下一步」那句已经在上面了，这里只写通用的操作说明（右键卸下、数字键选槽这类）
            String key = r.layout() == SpecScreenLayout.C ? "gui.albionmastery.spec_page.hint_c"
                    : "gui.albionmastery.spec_page.hint";
            // 完整、短、最短三档，挑放得下的最长那档
            String hint = I18n.get(key);
            for (String suffix : new String[]{"_short", "_tiny"}) {
                if (fw(hint) <= right - left) {
                    break;
                }
                hint = I18n.get(key + suffix);
            }
            guiGraphics.drawString(this.font, ellipsis(hint, right - left), left, y, TEXT_FAINT, false);
            return;
        }
        // 引导条收起：底栏跟着选中的槽说这个槽收什么、怎么装
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        SlotView view = slotView(this.selectedSlot, snapshot);
        Seg slot = new Seg(view.label().getString(), view.breakthrough() ? BREAK : MasteryPalette.GOLD);
        String pointName = SpecTerms.of(this.providerId, "point").getString();
        String max = Integer.toString(snapshot.specRules.normalMax());
        List<Seg> full;
        List<Seg> brief;
        if (!view.unlocked()) {
            full = segs("gui.albionmastery.spec_page.hint_locked_slot", TEXT_FAINT, slot,
                    new Seg(need(view.slot(), snapshot).text(), TEXT_FAINT));
            brief = full;
        } else {
            String c = r.layout() == SpecScreenLayout.C ? "_c" : "";
            Seg arg = new Seg(view.breakthrough() ? max : pointName, TEXT_FAINT);
            String base = view.breakthrough() ? "gui.albionmastery.spec_page.hint_break_slot"
                    : "gui.albionmastery.spec_page.hint_normal_slot";
            full = segs(base + c, TEXT_FAINT, slot, arg);
            brief = segs(base + "_short", TEXT_FAINT, slot, arg);
        }
        drawSegments(guiGraphics, segWidth(full) <= right - left ? full : brief, left, y, right - left);
    }

    // ======================================================================
    // 上手引导
    // ======================================================================

    private static final String K = "gui.albionmastery.spec_page.";
    /** 拼带颜色的句子时，占位符先换成「这个字符 + 序号字母」，再按它切开。 */
    private static final char MARK = 1;

    /** 一段带颜色的字。 */
    private record Seg(String text, int color) {
    }

    /**
     * 引导条上的一步。
     *
     * @param state 0 没做、1 做完、2 当前这步
     */
    private record GuideStep(String label, int state) {
    }

    /**
     * 引导条的内容（按选中的槽和玩家现状算）。
     *
     * @param sentence 「下一步」整句
     * @param brief    放不下时换的短句
     * @param target   要点的特化；没有为 null
     * @param onButton 要点的是它的升级 / 突破按钮；false = 点卡片本身装进去
     */
    private record Guide(List<GuideStep> steps, List<Seg> sentence, List<Seg> brief,
                         @Nullable Specialization target, boolean onButton) {
    }

    private boolean guideBarVisible() {
        return !this.viewOnly && MasteryConfig.CLIENT.specGuideBar.get();
    }

    /**
     * 按选中的槽列步骤、挑下一步：
     * <ul>
     *     <li>普通槽：选槽 → 点卡片装进去 → 花点数升级（装着的升满算做完）；</li>
     *     <li>突破槽：选槽 → 升满普通版 → 花突破点突破 → 装进来；</li>
     *     <li>下一步优先装现成能装的，其次突破 / 升级离突破最近的，再次解锁新的；点数不够写下一点在几级。</li>
     * </ul>
     * 候选都要和别的槽不冲突、没装在别的槽里，和服务端装槽的判定一致（{@link #equipBlocked}）。
     */
    private Guide computeGuide(MasteryConfig.Snapshot snapshot) {
        SpecRules rules = snapshot.specRules;
        int slot = this.selectedSlot;
        SlotView view = slotView(slot, snapshot);
        boolean c = this.regions.layout() == SpecScreenLayout.C;
        String pointName = SpecTerms.of(this.providerId, "point").getString();
        Seg slotSeg = new Seg(view.label().getString(), view.breakthrough() ? BREAK : MasteryPalette.GOLD_BRIGHT);
        Seg pointSeg = new Seg(pointName, MasteryPalette.GOLD);
        Specialization placed = view.spec();

        String[] labels;
        boolean[] done;
        if (!view.breakthrough()) {
            boolean maxed = placed != null && this.data.getSpecLevel(placed.key()) >= rules.normalMax();
            labels = new String[]{I18n.get(K + "guide.step.select"),
                    I18n.get(K + (c ? "guide.step.equip_c" : "guide.step.equip")),
                    I18n.get(K + "guide.step.upgrade", pointName)};
            done = new boolean[]{true, placed != null, maxed};
        } else {
            boolean anyMax = placed != null;
            boolean anyStar = placed != null;
            for (Specialization spec : this.specs) {
                anyMax |= this.data.getSpecLevel(spec.key()) >= rules.normalMax();
                anyStar |= this.data.getBreakthrough(this.typeKey, spec.key()) > 0;
            }
            labels = new String[]{I18n.get(K + "guide.step.select"),
                    I18n.get(K + "guide.step.max", rules.normalMax()),
                    I18n.get(K + "guide.step.break"),
                    I18n.get(K + "guide.step.place", view.label().getString())};
            done = new boolean[]{true, anyMax, anyStar, placed != null};
        }
        List<GuideStep> steps = new ArrayList<>();
        boolean current = false;
        for (int i = 0; i < labels.length; i++) {
            int state = done[i] ? 1 : 0;
            if (!done[i] && !current && view.unlocked()) {
                state = 2;
                current = true;
            }
            steps.add(new GuideStep(labels[i], state));
        }

        if (!view.unlocked()) {
            // 一个槽都没开时没有「别的槽」可选，改说可以先把点数花起来
            boolean anyOpen = false;
            for (int other = 1; other <= rules.slotCount; other++) {
                anyOpen |= SpecializationEffects.isSlotUnlocked(this.data, this.typeKey, other, snapshot);
            }
            Seg needSeg = new Seg(need(slot, snapshot).text(), MasteryPalette.TEXT_DIM);
            return anyOpen ? guide(steps, "guide.next.locked", null, false, slotSeg, needSeg)
                    : guide(steps, "guide.next.locked_all", null, false, slotSeg, needSeg, pointSeg);
        }
        int modAvailable = SpecPoints.modPointsAvailable(this.data, this.providerId, snapshot);
        int breakAvailable = SpecPoints.breakthroughPointsAvailable(this.data, this.typeKey, snapshot);
        if (!view.breakthrough()) {
            if (placed == null) {
                Specialization equip = equipCandidate(snapshot, slot, false);
                if (equip != null) {
                    return guide(steps, "guide.next.equip", equip, false, nameSeg(equip), slotSeg);
                }
                return unlockOrWait(steps, snapshot, slot, modAvailable, pointSeg);
            }
            int level = this.data.getSpecLevel(placed.key());
            if (level < rules.normalMax()) {
                if (modAvailable >= rules.normalCost(level + 1)) {
                    return guide(steps, "guide.next.upgrade", placed, true, nameSeg(placed),
                            buttonSeg(false, level + 1), pointSeg);
                }
                return waitForModPoints(steps, snapshot, pointSeg);
            }
            return guide(steps, "guide.next.done", null, false, slotSeg);
        }

        if (placed != null) {
            int star = this.data.getBreakthrough(this.typeKey, placed.key());
            if (star < rules.breakthroughMax()) {
                if (breakAvailable >= rules.breakthroughCost(star + 1)) {
                    return guide(steps, "guide.next.break_up", placed, true, nameSeg(placed),
                            buttonSeg(true, star + 1));
                }
                return waitForBreakPoints(steps, snapshot);
            }
            return guide(steps, "guide.next.done", null, false, slotSeg);
        }
        Specialization equip = equipCandidate(snapshot, slot, true);
        if (equip != null) {
            return guide(steps, "guide.next.equip", equip, false, nameSeg(equip), slotSeg);
        }
        Seg maxSeg = new Seg(Integer.toString(rules.normalMax()), MasteryPalette.TEXT);
        Specialization ready = null;
        Specialization grow = null;
        for (Specialization spec : this.specs) {
            if (slotOf(spec) != 0 || clashingSpec(spec, slot) != null) {
                continue;
            }
            int normal = this.data.getSpecLevel(spec.key());
            if (normal >= rules.normalMax() && this.data.getBreakthrough(this.typeKey, spec.key()) == 0) {
                if (ready == null) {
                    ready = spec;
                }
            } else if (normal > 0 && normal < rules.normalMax()
                    && (grow == null || normal > this.data.getSpecLevel(grow.key()))) {
                grow = spec;
            }
        }
        if (ready != null) {
            if (breakAvailable >= rules.breakthroughCost(1)) {
                return guide(steps, "guide.next.break", ready, true, nameSeg(ready), maxSeg, buttonSeg(true, 1),
                        slotSeg);
            }
            return waitForBreakPoints(steps, snapshot);
        }
        if (grow != null) {
            int normal = this.data.getSpecLevel(grow.key());
            if (modAvailable >= rules.normalCost(normal + 1)) {
                return guide(steps, "guide.next.upgrade_for_break", grow, true, nameSeg(grow),
                        buttonSeg(false, normal + 1), maxSeg);
            }
            return waitForModPoints(steps, snapshot, pointSeg);
        }
        return unlockOrWait(steps, snapshot, slot, modAvailable, pointSeg);
    }

    /** 现成能装进这个槽的里面等级最高的（没装在别的槽、不冲突、这一版等级 &gt; 0）。 */
    @Nullable
    private Specialization equipCandidate(MasteryConfig.Snapshot snapshot, int slot, boolean breakthrough) {
        Specialization best = null;
        int bestLevel = 0;
        for (Specialization spec : this.specs) {
            if (slotOf(spec) != 0 || equipBlocked(snapshot, spec, slot) != null) {
                continue;
            }
            int level = breakthrough ? this.data.getBreakthrough(this.typeKey, spec.key())
                    : this.data.getSpecLevel(spec.key());
            if (best == null || level > bestLevel) {
                best = spec;
                bestLevel = level;
            }
        }
        return best;
    }

    /** 没有现成能装的：点数够就指一个能解锁的，不够就说下一点在几级。 */
    private Guide unlockOrWait(List<GuideStep> steps, MasteryConfig.Snapshot snapshot, int slot, int modAvailable,
                               Seg pointSeg) {
        if (modAvailable >= snapshot.specRules.normalCost(1)) {
            for (Specialization spec : this.specs) {
                if (this.data.getSpecLevel(spec.key()) == 0 && slotOf(spec) == 0
                        && clashingSpec(spec, slot) == null) {
                    return guide(steps, "guide.next.unlock", spec, true, pointSeg, nameSeg(spec),
                            new Seg(I18n.get(K + "guide.btn.unlock"), MasteryPalette.GOLD_BRIGHT));
                }
            }
        }
        return waitForModPoints(steps, snapshot, pointSeg);
    }

    private Guide waitForModPoints(List<GuideStep> steps, MasteryConfig.Snapshot snapshot, Seg pointSeg) {
        int next = nextModLevel(snapshot);
        if (next <= 0) {
            return guide(steps, "guide.next.no_points_max", null, false, pointSeg);
        }
        String source = ClientWeaponRegistry.sourceName(WeaponKeys.overallKey(this.providerId)).getString();
        return guide(steps, "guide.next.no_points", null, false, pointSeg, new Seg(source, MasteryPalette.TEXT),
                new Seg(Integer.toString(next), MasteryPalette.GOLD_BRIGHT));
    }

    private Guide waitForBreakPoints(List<GuideStep> steps, MasteryConfig.Snapshot snapshot) {
        int next = nextBreakLevel(snapshot);
        if (next <= 0) {
            return guide(steps, "guide.next.no_break_points_max", null, false);
        }
        return guide(steps, "guide.next.no_break_points", null, false,
                new Seg(this.typeName.getString(), MasteryPalette.TEXT),
                new Seg(Integer.toString(next), MasteryPalette.GOLD_BRIGHT));
    }

    private Guide guide(List<GuideStep> steps, String key, @Nullable Specialization target, boolean onButton,
                        Seg... args) {
        // C 版没有「点卡片」这回事，说「点卡片」的句子另有 _c 版（选中、按「装进」）
        String base = K + key;
        if (this.regions.layout() == SpecScreenLayout.C && I18n.exists(base + "_c")) {
            base += "_c";
        }
        List<Seg> sentence = segs(base, MasteryPalette.TEXT, args);
        List<Seg> brief = I18n.exists(base + ".short") ? segs(base + ".short", MasteryPalette.TEXT, args)
                : sentence;
        return new Guide(steps, sentence, brief, target, onButton);
    }

    private static Seg nameSeg(Specialization spec) {
        return new Seg(I18n.get(spec.nameKey()), MasteryPalette.GOLD_BRIGHT);
    }

    /** 句子里提到的按钮名：「突破 ★1」橙、「升级 Lv4」金。 */
    private static Seg buttonSeg(boolean breakthrough, int level) {
        return breakthrough ? new Seg(I18n.get(K + "guide.btn.break", level), BREAK)
                : new Seg(I18n.get(K + "guide.btn.upgrade", level), MasteryPalette.GOLD_BRIGHT);
    }

    /**
     * 翻译一句话，参数各自带颜色：占位符先填成标记字符，再按标记切成几段。
     *
     * @param color 句子本身的颜色
     */
    private static List<Seg> segs(String key, int color, Seg... args) {
        Object[] marks = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            marks[i] = String.valueOf(MARK) + (char) ('a' + i);
        }
        String text = I18n.get(key, marks);
        List<Seg> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != MARK || i + 1 >= text.length()) {
                continue;
            }
            if (i > start) {
                out.add(new Seg(text.substring(start, i), color));
            }
            int index = text.charAt(i + 1) - 'a';
            if (index >= 0 && index < args.length) {
                out.add(args[index]);
            }
            i++;
            start = i + 1;
        }
        if (start < text.length()) {
            out.add(new Seg(text.substring(start), color));
        }
        return out;
    }

    /** 几段拼起来的宽度（含末尾 1 像素字距，和 {@link #fw} 同口径）。 */
    private float segWidth(List<Seg> segs) {
        float width = 0.0F;
        for (Seg seg : segs) {
            width += fw(seg.text());
        }
        return width;
    }

    /** 一段段接着画，超出 {@code maxWidth} 的那段截断加「..」，后面的不画。 */
    private void drawSegments(GuiGraphics guiGraphics, List<Seg> segs, int x, int y, float maxWidth) {
        float cursor = x;
        float limit = x + maxWidth;
        for (Seg seg : segs) {
            float width = fw(seg.text());
            if (cursor + width > limit + 1.0F) {
                String cut = ellipsis(seg.text(), limit - cursor);
                if (!cut.isEmpty()) {
                    drawAt(guiGraphics, Component.literal(cut), cursor, y, seg.color());
                }
                return;
            }
            drawAt(guiGraphics, Component.literal(seg.text()), cursor, y, seg.color());
            cursor += width;
        }
    }

    /**
     * 引导条：左边步骤（做完打勾、当前那步金底高亮），一条竖线，后面「下一步」那句，最右「收起」。
     * 放不下时先把做完 / 没做的步骤缩成只剩小方块，再换短句，最后截断。点那句话把列表滚到要点的地方。
     */
    private void drawGuideBar(GuiGraphics guiGraphics, Regions r, int mouseX, int mouseY) {
        Guide guide = this.guide;
        if (guide == null) {
            return;
        }
        int x = MARGIN;
        int y = r.barTop();
        int w = this.width - MARGIN * 2;
        guiGraphics.fill(x, y, x + w, y + BAR_HEIGHT, 0xBF130F09);
        frame(guiGraphics, x, y, w, BAR_HEIGHT, CARD_EDGE);
        // 字格竖直居中在框里：框内 14 高，字格 8 高，上下各空 3
        int row = y + (BAR_HEIGHT - 2 - 8) / 2 + 1;
        int closeRight = x + w - PAD;
        String close = I18n.get(K + "guide.collapse");
        String sep = "›";
        float sepWidth = 4 + (fw(sep) - 1.0F) + 4;

        // 0：步骤全写名字；1：只有当前步写名字；2：再换短句
        int level = 0;
        int[] kinds = new int[guide.steps().size()];
        float[] widths = new float[kinds.length];
        List<Seg> sentence = guide.sentence();
        for (; level < 3; level++) {
            float steps = 0.0F;
            for (int i = 0; i < kinds.length; i++) {
                GuideStep step = guide.steps().get(i);
                if (step.state() == 2) {
                    kinds[i] = 2;
                    widths[i] = (float) Math.ceil(fw(step.label()) + 16);
                } else if (level == 0) {
                    kinds[i] = 1;
                    widths[i] = BADGE + 3 + fw(step.label()) - 1.0F;
                } else {
                    kinds[i] = 0;
                    widths[i] = BADGE;
                }
                steps += widths[i] + (i > 0 ? sepWidth : 0.0F);
            }
            sentence = level < 2 ? guide.sentence() : guide.brief();
            float total = steps + 6 + 1 + 6 + segWidth(sentence) - 1.0F + NAME_VALUE_GAP + fw(close) - 1.0F;
            if (total <= w - PAD * 2 || level == 2) {
                break;
            }
        }

        float cx = x + PAD;
        for (int i = 0; i < kinds.length; i++) {
            GuideStep step = guide.steps().get(i);
            if (i > 0) {
                drawAt(guiGraphics, Component.literal(sep), cx + 4, row, TEXT_FAINT);
                cx += sepWidth;
            }
            String number = Integer.toString(i + 1);
            if (kinds[i] == 2) {
                int chipX = (int) Math.floor(cx);
                int chipWidth = (int) widths[i];
                guiGraphics.fill(chipX, y + 2, chipX + chipWidth, y + 14, 0x40E8C25A);
                frame(guiGraphics, chipX, y + 2, chipWidth, 12, MasteryPalette.GOLD);
                drawBadge(guiGraphics, chipX + 3, row, 2, number);
                guiGraphics.drawString(this.font, step.label(), chipX + 3 + BADGE + 3, row,
                        MasteryPalette.GOLD_BRIGHT, false);
                cx = chipX + chipWidth;
            } else {
                int badgeX = (int) Math.ceil(cx);
                drawBadge(guiGraphics, badgeX, row, step.state() == 1 ? 1 : 0, number);
                cx = badgeX + BADGE;
                if (kinds[i] == 1) {
                    guiGraphics.drawString(this.font, step.label(), badgeX + BADGE + 3, row,
                            step.state() == 1 ? MasteryPalette.TEXT_DIM : TEXT_FAINT, false);
                    cx = badgeX + BADGE + 3 + fw(step.label()) - 1.0F;
                }
            }
        }
        int dividerX = (int) Math.ceil(cx + 6);
        guiGraphics.fill(dividerX, y + 3, dividerX + 1, y + BAR_HEIGHT - 3,
                (MasteryPalette.EDGE & 0xFFFFFF) | 0x73000000);
        int sentenceX = dividerX + 1 + 6;
        float closeWidth = fw(close) - 1.0F;
        float room = closeRight - closeWidth - NAME_VALUE_GAP - sentenceX;
        int sentenceWidth = (int) Math.ceil(Math.min(room, segWidth(sentence) - 1.0F));
        if (guide.target() != null && sentenceWidth > 0) {
            boolean hover = inside(mouseX, mouseY, sentenceX - 2, y + 1, sentenceWidth + 4, BAR_HEIGHT - 2);
            if (hover) {
                guiGraphics.fill(sentenceX - 2, y + 2, sentenceX + sentenceWidth + 2, y + BAR_HEIGHT - 2,
                        0x14FFFFFF);
                showTextTooltip(guiGraphics, List.of(I18n.get(K + "guide.jump")), mouseX, mouseY);
            }
            this.hits.add(new Hit(sentenceX - 2, y + 1, sentenceWidth + 4, BAR_HEIGHT - 2, button -> {
                if (button != 0) {
                    return false;
                }
                playClick();
                requestFocus(guide.target());
                return true;
            }));
        }
        drawSegments(guiGraphics, sentence, sentenceX, row, room);

        int closeX = (int) Math.floor(closeRight - closeWidth);
        boolean onClose = inside(mouseX, mouseY, closeX - 3, y + 1, (int) Math.ceil(closeWidth) + 6, BAR_HEIGHT - 2);
        drawRight(guiGraphics, close, closeRight, row, onClose ? MasteryPalette.GOLD_BRIGHT : TEXT_FAINT);
        this.hits.add(new Hit(closeX - 3, y + 1, (int) Math.ceil(closeWidth) + 6, BAR_HEIGHT - 2, button -> {
            if (button != 0) {
                return false;
            }
            playClick();
            MasteryConfig.CLIENT.specGuideBar.set(false);
            MasteryConfig.CLIENT.specGuideBar.save();
            return true;
        }));
        if (onClose) {
            showTextTooltip(guiGraphics, List.of(I18n.get(K + "guide.collapse_tip")), mouseX, mouseY);
        }
    }

    /**
     * 8×8 小方块（切掉四个角）。
     *
     * @param kind 0 没做（暗底暗字）、1 做完（绿底打勾）、2 当前（金底深色字）、3 说明（暗底问号）
     */
    private void drawBadge(GuiGraphics guiGraphics, int x, int y, int kind, String label) {
        int fill = switch (kind) {
            case 1 -> 0x407FD06A;
            case 2 -> MasteryPalette.GOLD;
            case 3 -> (MasteryPalette.GOLD_DARK & 0xFFFFFF) | 0xCC000000;
            default -> (MasteryPalette.GOLD_DARK & 0xFFFFFF) | 0x8C000000;
        };
        guiGraphics.fill(x + 1, y, x + BADGE - 1, y + 1, fill);
        guiGraphics.fill(x, y + 1, x + BADGE, y + BADGE - 1, fill);
        guiGraphics.fill(x + 1, y + BADGE - 1, x + BADGE - 1, y + BADGE, fill);
        if (kind == 1) {
            for (int dy = 0; dy < CHECK.length; dy++) {
                for (int dx = 0; dx < CHECK[dy].length(); dx++) {
                    if (CHECK[dy].charAt(dx) == '#') {
                        guiGraphics.fill(x + dx, y + dy, x + dx + 1, y + dy + 1, GREEN);
                    }
                }
            }
            return;
        }
        int color = kind == 2 ? 0xFF0B0906 : kind == 0 ? TEXT_FAINT : MasteryPalette.TEXT;
        drawAt(guiGraphics, Component.literal(label), x + (BADGE - (fw(label) - 1.0F)) / 2.0F, y, color);
    }

    /**
     * 引导条要点的地方套两圈金框：外圈淡金，内圈亮金按 1.6 秒一呼吸。卡片、按钮套在外面 2～3 像素
     * （卡片间距 6、卡片内边距 5，都放得下）；C 版列表行一行挨一行，框画在行边上。
     */
    private void drawGuideRing(GuiGraphics guiGraphics) {
        Guide guide = this.guide;
        if (guide == null || guide.target() == null) {
            return;
        }
        Specialization target = guide.target();
        int[] rect;
        int[] clip;
        int outer = 3;
        if (this.regions.layout() == SpecScreenLayout.C) {
            if (target == this.detailSpec) {
                rect = guide.onButton() ? this.detailProgressRect : this.detailEquipRect;
                clip = this.detailClip;
            } else {
                rect = this.cardRects.get(target);
                clip = this.listClip;
                outer = 1;
            }
        } else {
            rect = guide.onButton() ? this.buttonRects.get(target) : this.cardRects.get(target);
            clip = this.listClip;
        }
        if (rect == null || clip == null) {
            return;
        }
        double phase = (Util.getMillis() % 1600L) / 1600.0D;
        float pulse = 0.55F + 0.45F * (float) Math.sin(phase * Math.PI * 2.0D);
        guiGraphics.enableScissor(clip[0], clip[1], clip[0] + clip[2], clip[1] + clip[3]);
        frame(guiGraphics, rect[0] - outer, rect[1] - outer, rect[2] + outer * 2, rect[3] + outer * 2,
                RenderUtil.withAlpha(MasteryPalette.GOLD, 0.35F));
        int inner = outer - 1;
        frame(guiGraphics, rect[0] - inner, rect[1] - inner, rect[2] + inner * 2, rect[3] + inner * 2,
                RenderUtil.withAlpha(MasteryPalette.GOLD_BRIGHT, pulse));
        guiGraphics.disableScissor();
    }

    /** 把列表滚到某个特化（C 版顺带在右栏打开它）；被筛选 / 搜索挡住了先清掉，位置下一帧量出来再滚。 */
    private void requestFocus(@Nullable Specialization target) {
        if (target == null) {
            return;
        }
        if (!filtered(ClientMasteryCache.snapshot()).contains(target)) {
            this.familyFilter = null;
            if (this.searchBox != null) {
                this.searchBox.setValue("");
            }
        }
        if (this.regions.layout() == SpecScreenLayout.C) {
            this.detailSpec = target;
            this.detailScroll = 0.0D;
        }
        this.pendingFocus = target;
    }

    private void applyPendingFocus() {
        Specialization target = this.pendingFocus;
        if (target == null) {
            return;
        }
        Integer y = this.contentY.get(target);
        if (y != null) {
            this.listScroll = Mth.clamp(y - 4.0D, 0.0D, this.listMaxScroll);
            this.pendingFocus = null;
        }
    }

    // ---------------------------------------------------------------- 分步遮罩

    private void startTour() {
        this.tourStep = 0;
        this.tourScrolledFor = -1;
        if (this.searchBox != null) {
            this.searchBox.setFocused(false);
        }
    }

    private void finishTour() {
        this.tourStep = -1;
        this.tourScrolledFor = -1;
        if (MasteryConfig.CLIENT.specGuideVersion.get() < GUIDE_VERSION) {
            MasteryConfig.CLIENT.specGuideVersion.set(GUIDE_VERSION);
            MasteryConfig.CLIENT.specGuideVersion.save();
        }
    }

    private void advanceTour() {
        playClick();
        if (this.tourStep >= TOUR_STEPS - 1) {
            finishTour();
        } else {
            this.tourStep++;
        }
    }

    /** 第 4 步讲哪张卡：引导条要点的那张，没有就列表里第一张。 */
    @Nullable
    private Specialization tourCard() {
        if (this.guide != null && this.guide.target() != null) {
            return this.guide.target();
        }
        List<Specialization> visible = filtered(ClientMasteryCache.snapshot());
        return visible.isEmpty() ? (this.specs.isEmpty() ? null : this.specs.get(0)) : visible.get(0);
    }

    /** 每进一步，把要照亮的那块滚进视野（左栏回到顶、卡片列表回到顶或滚到第 4 步那张卡）。 */
    private void applyTourScroll() {
        if (this.tourScrolledFor == this.tourStep) {
            return;
        }
        this.tourScrolledFor = this.tourStep;
        switch (this.tourStep) {
            case 0, 2 -> this.sideScroll = 0.0D;
            case 1 -> this.listScroll = 0.0D;
            default -> requestFocus(tourCard());
        }
    }

    /** 这一步照亮哪一块（已经裁到所在栏的可见范围）。 */
    @Nullable
    private int[] tourSpot() {
        boolean c = this.regions.layout() == SpecScreenLayout.C;
        return switch (this.tourStep) {
            case 0 -> c || this.regions.layout() == SpecScreenLayout.A ? intersect(this.slotsRect, this.sideClip)
                    : this.slotsRect;
            case 1 -> c ? this.listClip : intersect(this.firstRowRect, this.listClip);
            case 2 -> intersect(this.pointsRect, this.sideClip);
            default -> {
                Specialization card = tourCard();
                yield c ? intersect(this.detailRect, this.detailClip)
                        : intersect(card == null ? null : this.cardRects.get(card), this.listClip);
            }
        };
    }

    @Nullable
    private static int[] intersect(@Nullable int[] a, @Nullable int[] clip) {
        if (a == null || clip == null) {
            return a;
        }
        int left = Math.max(a[0], clip[0]);
        int top = Math.max(a[1], clip[1]);
        int right = Math.min(a[0] + a[2], clip[0] + clip[2]);
        int bottom = Math.min(a[1] + a[3], clip[1] + clip[3]);
        return right > left && bottom > top ? new int[]{left, top, right - left, bottom - top} : null;
    }

    /**
     * 画遮罩：照亮的那块四周压暗、描一圈亮金，旁边一个说明框（标题、页码、几段说明、跳过 / 下一步）。
     * 说明框先按这一步偏好的方向放（第 3 步点数框放右边，其余放下面），放不下依次试下、上、右、左，
     * 都放不下就放在屏幕中间；箭头落在说明框和照亮块之间 6 像素的空里。
     */
    private void drawTour(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, int mouseX, int mouseY) {
        int index = this.tourStep;
        int[] spot = tourSpot();
        int sx0;
        int sy0;
        int sx1;
        int sy1;
        if (spot == null) {
            sx0 = sx1 = this.width / 2;
            sy0 = sy1 = this.height / 2;
        } else {
            sx0 = Math.max(0, spot[0] - 3);
            sy0 = Math.max(0, spot[1] - 3);
            sx1 = Math.min(this.width, spot[0] + spot[2] + 3);
            sy1 = Math.min(this.height, spot[1] + spot[3] + 3);
        }
        guiGraphics.fill(0, 0, this.width, sy0, OVERLAY);
        guiGraphics.fill(0, sy1, this.width, this.height, OVERLAY);
        guiGraphics.fill(0, sy0, sx0, sy1, OVERLAY);
        guiGraphics.fill(sx1, sy0, this.width, sy1, OVERLAY);
        if (spot != null) {
            frame(guiGraphics, sx0, sy0, sx1 - sx0, sy1 - sy0, MasteryPalette.GOLD_BRIGHT);
        }

        int calloutWidth = Math.min(this.width >= C_MIN_WIDTH ? CALLOUT_WIDTH : CALLOUT_NARROW_WIDTH,
                this.width - 8);
        int textWidth = calloutWidth - 12;
        List<Seg> lines = new ArrayList<>();
        for (Seg para : tourParagraphs(index, snapshot)) {
            for (String line : TextWrap.wrap(this.font, para.text(), textWidth)) {
                lines.add(new Seg(line, para.color()));
            }
        }
        boolean last = index == TOUR_STEPS - 1;
        int calloutHeight = 6 + LINE + 3 + lines.size() * LINE + 4 + BUTTON_HEIGHT + 6;

        // 箭头指向：槽位那步指选中的槽，第 2 步指第一张卡，点数框指右边中间，第 4 步指照亮块顶边中间
        int anchorX = (sx0 + sx1) / 2;
        int anchorY = (sy0 + sy1) / 2;
        int alignX = sx0 + 3;
        if (index == 0 && this.selectedSlotRect != null) {
            anchorX = this.selectedSlotRect[0] + this.selectedSlotRect[2] / 2;
            alignX = this.selectedSlotRect[0];
        } else if (index == 1 && this.regions.layout() != SpecScreenLayout.C && this.firstRowRect != null) {
            anchorX = sx0 + 3 + Math.min(70, (sx1 - sx0) / 2);
        } else if (index == 2) {
            anchorX = sx1;
        }
        String[] order = index == 2 ? new String[]{"right", "below", "above", "left"}
                : new String[]{"below", "above", "right", "left"};
        String place = null;
        int cx = 0;
        int cy = 0;
        for (String kind : order) {
            switch (kind) {
                case "below" -> {
                    cx = Mth.clamp(alignX, 4, this.width - 4 - calloutWidth);
                    cy = sy1 + 6;
                }
                case "above" -> {
                    cx = Mth.clamp(alignX, 4, this.width - 4 - calloutWidth);
                    cy = sy0 - 6 - calloutHeight;
                }
                case "right" -> {
                    cx = sx1 + 6;
                    cy = Mth.clamp(anchorY - calloutHeight / 2, 4, Math.max(4, this.height - 4 - calloutHeight));
                }
                default -> {
                    cx = sx0 - 6 - calloutWidth;
                    cy = Mth.clamp(anchorY - calloutHeight / 2, 4, Math.max(4, this.height - 4 - calloutHeight));
                }
            }
            if (cx >= 4 && cx + calloutWidth <= this.width - 4 && cy >= 4 && cy + calloutHeight <= this.height - 4) {
                place = kind;
                break;
            }
        }
        if (place == null) {
            cx = (this.width - calloutWidth) / 2;
            cy = Math.max(4, (this.height - calloutHeight) / 2);
        }
        guiGraphics.fill(cx, cy, cx + calloutWidth, cy + calloutHeight, 0xF5130F09);
        frame(guiGraphics, cx, cy, calloutWidth, calloutHeight, MasteryPalette.GOLD);
        // 4 行的小三角，尖朝照亮的那块，尖离亮金框 2 像素
        if ("below".equals(place) || "above".equals(place)) {
            int px = Mth.clamp(anchorX, cx + 8, cx + calloutWidth - 9);
            for (int k = 0; k < 4; k++) {
                int rowY = "below".equals(place) ? cy - 4 + k : cy + calloutHeight + 3 - k;
                guiGraphics.fill(px - k, rowY, px + k + 1, rowY + 1, MasteryPalette.GOLD);
            }
        } else if (place != null) {
            int py = Mth.clamp(anchorY, cy + 8, cy + calloutHeight - 9);
            for (int k = 0; k < 4; k++) {
                int colX = "right".equals(place) ? cx - 4 + k : cx + calloutWidth + 3 - k;
                guiGraphics.fill(colX, py - k, colX + 1, py + k + 1, MasteryPalette.GOLD);
            }
        }

        String title = I18n.get(K + "tour." + (index + 1) + ".title");
        String page = (index + 1) + " / " + TOUR_STEPS;
        guiGraphics.drawString(this.font, ellipsis(title, textWidth - fw(page) - NAME_VALUE_GAP), cx + 6, cy + 6,
                MasteryPalette.GOLD_BRIGHT, false);
        drawRight(guiGraphics, page, cx + calloutWidth - 6, cy + 6, TEXT_FAINT);
        int ly = cy + 6 + LINE + 3;
        for (Seg line : lines) {
            guiGraphics.drawString(this.font, line.text(), cx + 6, ly, line.color(), false);
            ly += LINE;
        }

        int buttonY = cy + calloutHeight - 6 - BUTTON_HEIGHT;
        Component label = Component.translatable(K + (last ? "tour.done" : "tour.next"));
        int buttonWidth = this.font.width(label) + 14;
        int buttonX = cx + calloutWidth - 6 - buttonWidth;
        boolean onNext = inside(mouseX, mouseY, buttonX, buttonY, buttonWidth, BUTTON_HEIGHT);
        drawButton(guiGraphics, buttonX, buttonY, buttonWidth, BUTTON_HEIGHT, label, onNext, true, true);
        this.hits.add(new Hit(buttonX, buttonY, buttonWidth, BUTTON_HEIGHT, button -> {
            if (button != 0) {
                return false;
            }
            advanceTour();
            return true;
        }));
        int textY = buttonY + (BUTTON_HEIGHT - 8) / 2;
        if (!last) {
            String skip = I18n.get(K + "tour.skip");
            int skipWidth = this.font.width(skip);
            boolean onSkip = inside(mouseX, mouseY, cx + 4, buttonY, skipWidth + 4, BUTTON_HEIGHT);
            guiGraphics.drawString(this.font, skip, cx + 6, textY,
                    onSkip ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT_DIM, false);
            this.hits.add(new Hit(cx + 4, buttonY, skipWidth + 4, BUTTON_HEIGHT, button -> {
                if (button != 0) {
                    return false;
                }
                playClick();
                finishTour();
                return true;
            }));
        } else {
            guiGraphics.drawString(this.font, ellipsis(I18n.get(K + "tour.dont_show"),
                    buttonX - 4 - (cx + 6)), cx + 6, textY, TEXT_FAINT, false);
        }
    }

    /** 每一步的几段说明，数字按当前规则和这一页的来源 / 类型填。 */
    private List<Seg> tourParagraphs(int index, MasteryConfig.Snapshot snapshot) {
        SpecRules rules = snapshot.specRules;
        String c = this.regions.layout() == SpecScreenLayout.C ? "_c" : "";
        String pointName = SpecTerms.of(this.providerId, "point").getString();
        String source = ClientWeaponRegistry.sourceName(WeaponKeys.overallKey(this.providerId)).getString();
        String type = this.typeName.getString();
        List<Seg> out = new ArrayList<>();
        switch (index) {
            case 0 -> {
                out.add(new Seg(I18n.get(K + "tour.1.body1" + c), MasteryPalette.TEXT));
                out.add(new Seg(I18n.get(K + "tour.1.body2"), MasteryPalette.TEXT));
            }
            case 1 -> {
                int cooldown = snapshot.specSwitchCooldownSeconds;
                out.add(new Seg(cooldown > 0 ? I18n.get(K + "tour.2.body1" + c, duration(cooldown))
                        : I18n.get(K + "tour.2.body1_free" + c), MasteryPalette.TEXT));
                out.add(new Seg(I18n.get(K + "tour.2.body2" + c, pointName,
                        costRule(I18n.get(K + "points_tip.normal_range", rules.normalMax()), costs(rules, false))),
                        MasteryPalette.TEXT));
            }
            case 2 -> {
                String mod = I18n.get(K + "tour.3.mod", pointName, source, rules.modPointsAtMaxSource);
                if (rules.rankModPointMax > 0) {
                    mod += Plural.get(K + "tour.3.mod_rank", rules.rankModPointEvery, rules.rankModPointStart,
                            rules.rankModPointEvery, rules.rankModPointMax);
                }
                out.add(new Seg(mod + I18n.get(K + "tour.3.mod_end", source), MasteryPalette.TEXT));
                String brk = I18n.get(K + "tour.3.break", type, rules.breakthroughPointsAtMaxType);
                if (rules.maxedWeaponBreakthroughPointMax > 0) {
                    brk += I18n.get(K + "tour.3.break_maxed", rules.maxedWeaponBreakthroughPoint,
                            rules.maxedWeaponBreakthroughPointMax);
                }
                out.add(new Seg(brk + I18n.get(K + "tour.3.break_end", type), MasteryPalette.TEXT));
                out.add(new Seg(I18n.get(K + "tour.3.respec", duration(rules.respecCooldownSeconds)),
                        MasteryPalette.TEXT_DIM));
            }
            default -> {
                out.add(new Seg(I18n.get(K + "tour.4.body1" + c, rules.normalMax(),
                        costRule(I18n.get(K + "points_tip.break_range", rules.breakthroughMax()), costs(rules, true))),
                        MasteryPalette.TEXT));
                out.add(new Seg(I18n.get(K + "tour.4.body2"), MasteryPalette.TEXT));
                out.add(new Seg(I18n.get(K + "tour.4.body3"), TEXT_FAINT));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 小画法

    private void frame(GuiGraphics guiGraphics, int x, int y, int width, int height, int color) {
        guiGraphics.fill(x, y, x + width, y + 1, color);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, color);
        guiGraphics.fill(x, y + 1, x + 1, y + height - 1, color);
        guiGraphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /**
     * 等级方点：4×4，间隔 1，点亮的实心、没点亮的空心。
     *
     * @return 占的宽度
     */
    private int drawPips(GuiGraphics guiGraphics, int x, int y, int level, int total) {
        for (int i = 0; i < total; i++) {
            int px = x + i * 5;
            if (i < level) {
                guiGraphics.fill(px, y, px + 4, y + 4, MasteryPalette.GOLD);
            } else {
                frame(guiGraphics, px, y, 4, 4, MasteryPalette.GOLD_DARK);
            }
        }
        return Math.max(0, total * 5 - 1);
    }

    /**
     * 突破星：5×5 菱形，间隔 1，点亮的实心、没点亮的只画轮廓。
     *
     * @return 占的宽度
     */
    private int drawStars(GuiGraphics guiGraphics, int x, int y, int level, int total) {
        for (int i = 0; i < total; i++) {
            int px = x + i * 6;
            if (i < level) {
                guiGraphics.fill(px + 2, y, px + 3, y + 1, BREAK);
                guiGraphics.fill(px + 1, y + 1, px + 4, y + 2, BREAK);
                guiGraphics.fill(px, y + 2, px + 5, y + 3, BREAK);
                guiGraphics.fill(px + 1, y + 3, px + 4, y + 4, BREAK);
                guiGraphics.fill(px + 2, y + 4, px + 3, y + 5, BREAK);
            } else {
                int c = MasteryPalette.GOLD_DARK;
                guiGraphics.fill(px + 2, y, px + 3, y + 1, c);
                guiGraphics.fill(px + 1, y + 1, px + 2, y + 2, c);
                guiGraphics.fill(px + 3, y + 1, px + 4, y + 2, c);
                guiGraphics.fill(px, y + 2, px + 1, y + 3, c);
                guiGraphics.fill(px + 4, y + 2, px + 5, y + 3, c);
                guiGraphics.fill(px + 1, y + 3, px + 2, y + 4, c);
                guiGraphics.fill(px + 3, y + 3, px + 4, y + 4, c);
                guiGraphics.fill(px + 2, y + 4, px + 3, y + 5, c);
            }
        }
        return Math.max(0, total * 6 - 1);
    }

    /** 槽里的等级：普通槽画方点，突破槽画星。 */
    private int drawLevelMarks(GuiGraphics guiGraphics, SlotView view, int x, int y) {
        return view.breakthrough() ? drawStars(guiGraphics, x, y + 1, view.level(), rules().breakthroughMax())
                : drawPips(guiGraphics, x, y + 2, view.level(), rules().normalMax());
    }

    private int levelMarksWidth(SlotView view) {
        return view.breakthrough() ? rules().breakthroughMax() * 6 - 1 : rules().normalMax() * 5 - 1;
    }

    private static String levelText(boolean breakthrough, int level) {
        return breakthrough ? "★" + level : "Lv" + level;
    }

    /** 不取整的显示宽度（含末尾 1 像素字距）。中文字步进不是整数，右对齐要用它才不会差 1 个屏幕像素。 */
    private float fw(String text) {
        return this.font.getSplitter().stringWidth(text);
    }

    /**
     * 右对齐画字：文字的右边贴着 {@code right}（不含）。MC 的宽度含末尾 1 像素字距，起点 +1 之后左右留白相等。
     */
    private void drawRight(GuiGraphics guiGraphics, String text, int right, int y, int color) {
        drawAt(guiGraphics, Component.literal(text), right + 1 - fw(text), y, color);
    }

    private void drawRight(GuiGraphics guiGraphics, Component text, int right, int y, int color) {
        drawAt(guiGraphics, text, right + 1 - this.font.getSplitter().stringWidth(text), y, color);
    }

    private void drawAt(GuiGraphics guiGraphics, Component text, float x, int y, int color) {
        // 落到屏幕像素格上（往左取整），GUI 缩放 3 时半个 GUI 像素 = 1.5 屏幕像素，不对齐会把字形采样糊
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        float snapped = (float) (Math.floor(x * scale + 0.001D) / scale);
        int whole = Mth.floor(snapped);
        float fraction = snapped - whole;
        if (fraction < 0.01F) {
            guiGraphics.drawString(this.font, text, whole, y, color, false);
            return;
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(fraction, 0.0F, 0.0F);
        guiGraphics.drawString(this.font, text, whole, y, color, false);
        guiGraphics.pose().popPose();
    }

    private String ellipsis(String text, float maxWidth) {
        if (fw(text) <= maxWidth) {
            return text;
        }
        float room = maxWidth - fw("..");
        if (room <= 0) {
            return "";
        }
        return this.font.plainSubstrByWidth(text, (int) room) + "..";
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** 毫秒写成「23:41」（时:分，分钟向上取整）。 */
    private static String clock(long millis) {
        long minutes = (millis + 59_999L) / 60_000L;
        return String.format(Locale.ROOT, "%d:%02d", minutes / 60L, minutes % 60L);
    }

    // ======================================================================
    // 数据
    // ======================================================================

    private static SpecRules rules() {
        return ClientMasteryCache.snapshot().specRules;
    }

    /**
     * @return 当前显示的这一份配置的键：武器页且这件有独立配置时是武器键，否则是类型键
     */
    private String configKey() {
        return this.data.specConfigKey(this.typeKey, this.weaponKey);
    }

    /**
     * 某个类型下有几件武器用了独立配置（它们不受这个类型通用配置的影响）。
     *
     * @param data    专精数据
     * @param typeKey 类型键
     * @return 件数
     */
    public static int countOverrides(MasteryData data, String typeKey) {
        Set<String> weapons = new HashSet<>();
        for (String key : data.getSpecializationsView().keySet()) {
            String base = WeaponKeys.specBaseKey(key);
            if (!WeaponKeys.isValidWeaponKey(base) || weapons.contains(base)) {
                continue;
            }
            WeaponEntry entry = ClientWeaponRegistry.weaponByKey(base);
            String weaponType = entry != null ? entry.typeKey() : data.getWeaponTypeKey(base);
            if (typeKey.equals(weaponType)) {
                weapons.add(base);
            }
        }
        return weapons.size();
    }

    /** 某个槽里装着的特化（只认这个来源的、不是制式的），不管等级。 */
    @Nullable
    private Specialization specInSlot(int slot) {
        Specialization spec = Specialization.byKey(this.data.getSpecialization(configKey(), slot));
        if (spec == null || spec.isStandard() || !spec.sourceId().equals(this.providerId)) {
            return null;
        }
        return spec;
    }

    /** @return 这个特化装在当前配置的哪个槽，没装返回 0 */
    private int slotOf(Specialization spec) {
        for (int slot = 1; slot <= rules().slotCount; slot++) {
            if (specInSlot(slot) == spec) {
                return slot;
            }
        }
        return 0;
    }

    /**
     * 一个槽的显示数据。
     *
     * @param slot         槽位
     * @param breakthrough 突破槽
     * @param unlocked     开没开（能不能往里装）
     * @param spec         装着的特化
     * @param level        装着的特化在这个槽里的等级（Lv 或 ★）
     * @param active       装着的特化是不是真的在生效（等级 &gt; 0、没被前面的槽同系挡掉）
     * @param label        槽名
     * @param need         没开时差什么
     */
    private record SlotView(int slot, boolean breakthrough, boolean unlocked, @Nullable Specialization spec,
                            int level, boolean active, Component label, String need) {
    }

    private SlotView slotView(int slot, MasteryConfig.Snapshot snapshot) {
        SpecRules rules = snapshot.specRules;
        boolean unlocked = SpecializationEffects.isSlotUnlocked(this.data, this.typeKey, slot, snapshot);
        Specialization spec = specInSlot(slot);
        boolean breakthrough = rules.isBreakthroughSlot(slot)
                || (rules.singleSlot() && spec != null && this.data.getBreakthrough(this.typeKey, spec.key()) > 0);
        int level = spec == null ? 0 : SpecializationEffects.levelInSlot(this.data, this.typeKey, spec, slot, rules);
        boolean active = spec != null && level > 0 && clashingEarlier(spec, slot) == null;
        return new SlotView(slot, breakthrough, unlocked, spec, level, active,
                SpecTerms.slot(this.providerId, slot), unlocked ? "" : needText(slot, snapshot));
    }

    /**
     * 槽没开时差什么：槽自己的条件（类型 / 来源等级）没到写那条，到了就是武器门槛没过。
     */
    private String needText(int slot, MasteryConfig.Snapshot snapshot) {
        if (!SpecializationEffects.slotConditionMet(this.data, this.typeKey, slot, snapshot)) {
            int need = SpecializationEffects.slotNeedLevel(slot, snapshot);
            return switch (SpecializationEffects.slotNeed(slot)) {
                case TYPE -> I18n.get("gui.albionmastery.spec_page.need_type", need,
                        this.data.getTypeLevel(this.typeKey));
                case SOURCE -> I18n.get("gui.albionmastery.spec_page.need_source", need,
                        this.data.getOverallLevel(WeaponKeys.overallKey(this.providerId)));
                case NONE -> "";
            };
        }
        return I18n.get("gui.albionmastery.spec_page.need_weapon",
                SpecializationEffects.weaponGate(this.data, this.typeKey, snapshot), highestWeaponLevel());
    }

    /** 这个类型里练得最高的武器几级。 */
    private int highestWeaponLevel() {
        int best = 0;
        for (MasteryData.WeaponRecord record : this.data.getWeaponsView().values()) {
            if (this.typeKey.equals(record.typeKey)) {
                best = Math.max(best, record.level);
            }
        }
        return best;
    }

    /** 这一页对应的武器（或类型里有武器）过了槽 1 的门槛没有。 */
    private boolean gatePassed(MasteryConfig.Snapshot snapshot) {
        return this.weaponKey != null
                ? SpecializationEffects.weaponPassesGate(this.data, this.typeKey, this.weaponKey, snapshot)
                : SpecializationEffects.anyWeaponPassesGate(this.data, this.typeKey, snapshot);
    }

    /** 前面的槽里有没有和它同系（或者就是它）的，有就返回那个——这个槽不生效。 */
    @Nullable
    private Specialization clashingEarlier(Specialization spec, int slot) {
        for (int other = 1; other < slot; other++) {
            Specialization earlier = specInSlot(other);
            if (earlier != null && (earlier == spec || spec.conflictsWith(earlier))) {
                return earlier;
            }
        }
        return null;
    }

    /** 除了 {@code slot} 以外的槽里，和它同系的特化（不含它自己）。 */
    @Nullable
    private Specialization clashingSpec(Specialization spec, int slot) {
        for (int other = 1; other <= rules().slotCount; other++) {
            if (other == slot) {
                continue;
            }
            Specialization placed = specInSlot(other);
            if (placed != null && placed != spec && spec.conflictsWith(placed)) {
                return placed;
            }
        }
        return null;
    }

    /**
     * 按槽算合计数值，规则和服务端 {@code SpecializationEffects.forData} 一致（没开的槽不算，同系只吃前面那个，
     * 槽里等级为 0 不算），只是不看武器门槛——门槛没过时界面另外写一句提示。
     *
     * @param change 假设把某些槽换成别的（值为 null 表示清空），用来算「装上之后」的预览
     */
    private SpecializationValues combined(MasteryConfig.Snapshot snapshot, Map<Integer, Specialization> change) {
        SpecRules rules = snapshot.specRules;
        SpecializationValues result = null;
        List<Specialization> applied = new ArrayList<>();
        for (int slot = 1; slot <= rules.slotCount; slot++) {
            if (!SpecializationEffects.slotConditionMet(this.data, this.typeKey, slot, snapshot)) {
                continue;
            }
            Specialization spec = change.containsKey(slot) ? change.get(slot) : specInSlot(slot);
            if (spec == null) {
                continue;
            }
            boolean clash = false;
            for (Specialization other : applied) {
                if (other == spec || spec.conflictsWith(other)) {
                    clash = true;
                    break;
                }
            }
            if (clash) {
                continue;
            }
            boolean breakthrough = SpecializationEffects.usesBreakthrough(this.data, this.typeKey, spec, slot, rules);
            int level = breakthrough ? this.data.getBreakthrough(this.typeKey, spec.key())
                    : this.data.getSpecLevel(spec.key());
            if (level <= 0) {
                continue;
            }
            SpecializationValues values = snapshot.specValues(spec, breakthrough, level);
            result = result == null ? values : SpecializationValues.combine(result, values);
            applied.add(spec);
        }
        return result == null ? SpecializationValues.NEUTRAL : result;
    }

    private SpecDescriber.Entries entriesOf(SpecializationValues values) {
        return SpecDescriber.entries(values, this.providerId);
    }

    /** 某个特化某一版某一级拆好的效果，走缓存。 */
    private SpecDescriber.Entries entries(MasteryConfig.Snapshot snapshot, Specialization spec, boolean breakthrough,
                                          int level) {
        if (snapshot != this.entryCacheSnapshot) {
            this.entryCache.clear();
            this.entryCacheSnapshot = snapshot;
        }
        String key = spec.key() + (breakthrough ? "|b" : "|n") + level;
        SpecDescriber.Entries cached = this.entryCache.get(key);
        if (cached == null) {
            cached = SpecDescriber.entries(snapshot.specValues(spec, breakthrough, level), this.providerId);
            this.entryCache.put(key, cached);
        }
        return cached;
    }

    /**
     * 装进某个槽会被挡住的原因；装得上返回 null。服务端会再判一遍，这里只是让玩家提前知道。
     */
    @Nullable
    private Component equipBlocked(MasteryConfig.Snapshot snapshot, Specialization spec, int slot) {
        Component slotName = SpecTerms.slot(this.providerId, slot);
        if (!SpecializationEffects.isSlotUnlocked(this.data, this.typeKey, slot, snapshot)) {
            return Component.translatable("gui.albionmastery.spec_page.preview_locked", slotName,
                    needText(slot, snapshot));
        }
        int other = slotOf(spec);
        if (other != 0 && other != slot) {
            return Component.translatable("gui.albionmastery.spec_page.preview_duplicate",
                    SpecTerms.slot(this.providerId, other));
        }
        Specialization clash = clashingSpec(spec, slot);
        if (clash != null) {
            return Component.translatable("gui.albionmastery.spec_page.preview_conflict",
                    SpecTerms.slot(this.providerId, slotOf(clash)), Component.translatable(clash.nameKey()),
                    Component.translatable(spec.familyKey()), SpecTerms.of(this.providerId, "group"));
        }
        if (SpecializationEffects.levelInSlot(this.data, this.typeKey, spec, slot, snapshot.specRules) <= 0) {
            boolean breakthrough = SpecializationEffects.usesBreakthrough(this.data, this.typeKey, spec, slot,
                    snapshot.specRules);
            return breakthrough
                    ? Component.translatable("gui.albionmastery.spec_page.preview_need_break", slotName)
                    : Component.translatable("gui.albionmastery.spec_page.preview_need_level",
                    SpecTerms.of(this.providerId, "point"));
        }
        return null;
    }

    /**
     * 一张卡片的显示数据。
     *
     * @param inSlot       装在哪个槽，0 = 没装
     * @param breakthrough 显示的是突破版
     * @param level        显示的等级（预览时是 1）
     * @param preview      还没升到这一版，显示的是 1 级的预览
     * @param normalLevel  普通版等级
     * @param star         这个类型里的突破等级
     * @param levelText    等级那一行的文字
     * @param tag          右上角的状态，可为 null
     * @param tagFramed    右上角是带框的角标（可升级 / 可突破）
     * @param dimmed       名字压暗（还没解锁）
     */
    private record CardView(Specialization spec, int inSlot, boolean breakthrough, int level, boolean preview,
                            int normalLevel, int star, String levelText, @Nullable String tag, int tagColor,
                            boolean tagFramed, boolean dimmed, SpecDescriber.Entries entries,
                            @Nullable Progress progress) {
    }

    /**
     * 升级按钮。
     *
     * @param label        按钮文字
     * @param enabled      点数够不够
     * @param breakthrough 是突破（花突破点）还是升级（花改装点）
     */
    private record Progress(String label, boolean enabled, boolean breakthrough) {
    }

    /**
     * 卡片显示哪一版：装在某个槽里就按那个槽（突破槽显示 ★）；没装就按选中的槽是普通还是突破。
     * 那一版还是 0 级时显示 1 级的预览。
     */
    private CardView cardView(Specialization spec, MasteryConfig.Snapshot snapshot) {
        SpecRules rules = snapshot.specRules;
        int inSlot = slotOf(spec);
        int normal = this.data.getSpecLevel(spec.key());
        int star = this.data.getBreakthrough(this.typeKey, spec.key());
        boolean breakthrough = inSlot != 0
                ? SpecializationEffects.usesBreakthrough(this.data, this.typeKey, spec, inSlot, rules)
                : rules.singleSlot() ? star > 0 : rules.isBreakthroughSlot(this.selectedSlot);
        int level = breakthrough ? star : normal;
        boolean preview = level <= 0;
        int shownLevel = preview ? 1 : level;
        String levelText;
        if (breakthrough && normal < rules.normalMax()) {
            levelText = I18n.get("gui.albionmastery.spec_page.level_need_max", normal, rules.normalMax());
        } else if (preview) {
            levelText = I18n.get("gui.albionmastery.spec_page.level_preview", levelText(breakthrough, 1));
        } else {
            levelText = levelText(breakthrough, level);
        }
        Progress progress = progressFor(spec, snapshot);
        String tag = null;
        int tagColor = TEXT_FAINT;
        boolean framed = false;
        Specialization clash = inSlot != 0 || normal <= 0 ? null : clashingSpec(spec, 0);
        if (inSlot != 0) {
            tag = SpecTerms.slot(this.providerId, inSlot).getString();
            tagColor = MasteryPalette.GOLD;
        } else if (normal <= 0) {
            tag = I18n.get("gui.albionmastery.spec_page.tag_locked");
        } else if (clash != null) {
            tag = I18n.get("gui.albionmastery.panel.spec_tag_conflict",
                    SpecTerms.slot(this.providerId, slotOf(clash)).getString(),
                    SpecTerms.of(this.providerId, "group").getString());
        } else if (progress != null && progress.enabled()) {
            // 点数够、能往上走的卡打个角标，一眼看出现在能做什么
            framed = true;
            tag = I18n.get(progress.breakthrough() ? "gui.albionmastery.spec_page.tag_can_break"
                    : "gui.albionmastery.spec_page.tag_can_upgrade");
            tagColor = progress.breakthrough() ? BREAK : MasteryPalette.GOLD;
        }
        return new CardView(spec, inSlot, breakthrough, shownLevel, preview, normal, star, levelText, tag, tagColor,
                framed, preview, entries(snapshot, spec, breakthrough, shownLevel), progress);
    }

    /**
     * 升级按钮：普通版没满级是「升级 / 解锁」（花改装点），满级了是「突破」（花这个类型的突破点），都满了没有按钮。
     */
    @Nullable
    private Progress progressFor(Specialization spec, MasteryConfig.Snapshot snapshot) {
        if (this.viewOnly) {
            return null;
        }
        SpecRules rules = snapshot.specRules;
        String pointName = SpecTerms.of(this.providerId, "point").getString();
        int normal = this.data.getSpecLevel(spec.key());
        if (normal < rules.normalMax()) {
            int cost = rules.normalCost(normal + 1);
            boolean enabled = SpecPoints.modPointsAvailable(this.data, this.providerId, snapshot) >= cost;
            String label = normal == 0
                    ? I18n.get("gui.albionmastery.spec_page.unlock_button", cost, pointName)
                    : I18n.get("gui.albionmastery.spec_page.upgrade_button", normal + 1, cost, pointName);
            return new Progress(label, enabled, false);
        }
        int star = this.data.getBreakthrough(this.typeKey, spec.key());
        if (star < rules.breakthroughMax()) {
            int cost = rules.breakthroughCost(star + 1);
            boolean enabled = SpecPoints.breakthroughPointsAvailable(this.data, this.typeKey, snapshot) >= cost;
            return new Progress(I18n.get("gui.albionmastery.spec_page.break_button", star + 1, cost), enabled, true);
        }
        return null;
    }

    /** 按搜索框和分组筛选后的特化。 */
    private List<Specialization> filtered(MasteryConfig.Snapshot snapshot) {
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        boolean chips = this.regions.layout() != SpecScreenLayout.C;
        List<Specialization> visible = new ArrayList<>();
        for (Specialization spec : this.specs) {
            if (chips && this.familyFilter != null && !this.familyFilter.equals(spec.family())) {
                continue;
            }
            if (!query.isEmpty() && !searchText(snapshot, spec).contains(query)) {
                continue;
            }
            visible.add(spec);
        }
        return visible;
    }

    /**
     * 搜索用的纯文本：名字、分组名、每一项效果的名字，全部小写，一项一行。
     * 分行拼是为了关键字不会横跨两项凑出来。
     */
    private String searchText(MasteryConfig.Snapshot snapshot, Specialization spec) {
        StringBuilder text = new StringBuilder(I18n.get(spec.nameKey()));
        text.append('\n').append(I18n.get(spec.familyKey()));
        SpecDescriber.Entries entries = entries(snapshot, spec, true, snapshot.specRules.breakthroughMax());
        for (SpecDescriber.Entry entry : entries.gains()) {
            text.append('\n').append(entry.label());
        }
        for (SpecDescriber.Entry entry : entries.costs()) {
            text.append('\n').append(entry.label());
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private String familyKey(String family) {
        return "spec_family.albionmastery." + this.providerId + "." + family;
    }

    // ======================================================================
    // 请求
    // ======================================================================

    /**
     * 点卡片：已经装在选中的槽里就卸下，否则装进去。
     */
    private void toggleInSelectedSlot(Specialization spec) {
        if (this.viewOnly) {
            return;
        }
        sendSelect(this.selectedSlot, specInSlot(this.selectedSlot) == spec ? null : spec);
    }

    /**
     * @param spec null 表示清空这个槽（发制式）
     */
    private void sendSelect(int slot, @Nullable Specialization spec) {
        String key;
        if (spec == null) {
            Specialization standard = Specialization.standardOf(this.providerId);
            if (standard == null) {
                return;
            }
            key = standard.key();
        } else {
            key = spec.key();
        }
        // 武器页点卡片改的是这件武器的独立配置（第一次点会先把通用配置抄过来）
        NetworkHandler.sendToServer(new C2SSelectSpecializationPacket(this.typeKey, key, slot,
                this.weaponKey == null ? "" : this.weaponKey, false));
        this.lastRequestMs = Util.getMillis();
        playClick();
    }

    private void sendProgress(Specialization spec, boolean breakthrough) {
        NetworkHandler.sendToServer(new C2SSpecProgressPacket(breakthrough
                ? C2SSpecProgressPacket.Action.BREAKTHROUGH : C2SSpecProgressPacket.Action.UPGRADE,
                this.typeKey, spec.key()));
        this.lastRequestMs = Util.getMillis();
        playClick();
    }

    // ======================================================================
    // 输入
    // ======================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.tourStep >= 0) {
            // 遮罩期间 hits 里只有说明框的按钮，点别处什么都不做
            for (int i = this.hits.size() - 1; i >= 0; i--) {
                Hit hit = this.hits.get(i);
                if (hit.contains(mouseX, mouseY) && hit.click().on(button)) {
                    break;
                }
            }
            return true;
        }
        if (this.scaleKnob.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit hit = this.hits.get(i);
            if (hit.contains(mouseX, mouseY) && hit.click().on(button)) {
                if (this.searchBox != null) {
                    this.searchBox.setFocused(false);
                }
                return true;
            }
        }
        if (this.searchBox != null && this.searchBox.isFocused()) {
            this.searchBox.setFocused(false);
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.scaleKnob.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.tourStep >= 0 || this.scaleKnob.contains(mouseX, mouseY)) {
            return true;
        }
        Regions r = this.regions;
        if (mouseX < r.sideRight() + 6) {
            this.sideScroll = Mth.clamp(this.sideScroll - delta * SCROLL_STEP, 0.0D, this.sideMaxScroll);
        } else if (r.layout() == SpecScreenLayout.C && mouseX >= r.detailLeft() - 6) {
            this.detailScroll = Mth.clamp(this.detailScroll - delta * SCROLL_STEP, 0.0D, this.detailMaxScroll);
        } else {
            this.listScroll = Mth.clamp(this.listScroll - delta * SCROLL_STEP, 0.0D, this.listMaxScroll);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.tourStep >= 0) {
            // ESC 跳过引导（不关页面），回车 / 右方向键下一步
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                playClick();
                finishTour();
            } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                    || keyCode == GLFW.GLFW_KEY_RIGHT) {
                advanceTour();
            }
            return true;
        }
        if (this.scaleKnob.keyPressed(keyCode)) {
            return true;
        }
        boolean typing = this.searchBox != null && this.searchBox.isFocused();
        if (typing && keyCode != GLFW.GLFW_KEY_ESCAPE && this.searchBox.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && typing) {
            this.searchBox.setFocused(false);
            return true;
        }
        if (!typing && keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_4) {
            int slot = keyCode - GLFW.GLFW_KEY_1 + 1;
            if (slot <= rules().slotCount) {
                this.selectedSlot = slot;
                playClick();
                return true;
            }
        }
        if (!typing && KeyBindings.OPEN_MASTERY.matches(keyCode, scanCode)) {
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.tourStep >= 0) {
            return true;
        }
        if (this.searchBox != null && this.searchBox.isFocused()) {
            return this.searchBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void removed() {
        super.removed();
        // 换回原版那一档；接着打开的要是别的专精界面，它的 init 会再换过来，中间不出帧
        ScreenScale.restore(Minecraft.getInstance());
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void playClick() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /**
     * 本帧的版面边界。
     *
     * @param stripTop    B 版槽位条的顶
     * @param stripBottom B 版槽位条的底
     * @param detailLeft  C 版右栏的左边
     * @param barTop      引导条的顶；0 = 不显示引导条
     */
    private record Regions(SpecScreenLayout layout, int sideLeft, int sideTop, int sideRight, int sideBottom,
                           int mainLeft, int mainTop, int mainRight, int mainBottom,
                           int stripTop, int stripBottom, int detailLeft, int barTop) {
    }

    /**
     * 版面上的一块：分组标题（spec 为 null）或者一张卡片。坐标是内容坐标，相对列表左上角。
     */
    private record Block(@Nullable Specialization spec, int x, int y, int width, int height,
                         @Nullable String header) {
    }

    /** 点击处理：返回 true 表示处理了。 */
    private interface Click {
        boolean on(int button);
    }

    /**
     * 一块可点击区域（屏幕坐标）。
     */
    private record Hit(int x, int y, int width, int height, Click click) {

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.width
                    && mouseY >= this.y && mouseY < this.y + this.height;
        }
    }
}
