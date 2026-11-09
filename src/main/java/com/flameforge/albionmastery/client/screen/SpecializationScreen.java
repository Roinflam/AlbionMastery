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
 */
public class SpecializationScreen extends Screen {

    private static final int HEADER_HEIGHT = 20;
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
    private Regions regions = new Regions(SpecScreenLayout.B, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

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
        switch (layout) {
            case B -> {
                stripBottom = stripTop + stripRows() * (SLOT_TILE_HEIGHT + 4) - 4;
                sideTop = stripBottom + 8;
                sideWidth = this.width < C_MIN_WIDTH ? 120 : 150;
            }
            case C -> sideWidth = Mth.clamp(this.width * 26 / 100, 140, 190);
            default -> sideWidth = Mth.clamp(this.width * 30 / 100, 150, 210);
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
                mainLeft, sideTop, mainRight, footerTop, stripTop, stripBottom, detailLeft);
        if (this.searchBox != null) {
            this.searchBox.setX(mainLeft + 4);
            this.searchBox.setY(sideTop + 3);
            this.searchBox.setWidth(mainRight - mainLeft - 8);
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
        if (this.armedPool != null && Util.getMillis() > this.armedUntil) {
            this.armedPool = null;
        }
        if (this.selectedSlot > rules().slotCount) {
            this.selectedSlot = 1;
        }
        Regions r = this.regions;

        // 主区先画（要知道鼠标停在哪张卡上），左栏的预览再按它画
        drawSearchFrame(guiGraphics, r);
        if (r.layout() == SpecScreenLayout.C) {
            drawList(guiGraphics, snapshot, r, mouseX, mouseY);
            drawDetail(guiGraphics, snapshot, r, mouseX, mouseY);
            guiGraphics.fill(r.mainRight() + 6, r.mainTop(), r.mainRight() + 7, r.sideBottom(), 0x596B5223);
        } else {
            int chipsBottom = drawChips(guiGraphics, mouseX, mouseY, r.mainLeft(), r.mainRight(),
                    r.mainTop() + SEARCH_HEIGHT + 4);
            drawCardGrid(guiGraphics, snapshot, r, chipsBottom + 6, mouseX, mouseY);
        }
        if (r.layout() == SpecScreenLayout.B) {
            drawStrip(guiGraphics, snapshot, r, mouseX, mouseY);
        }
        guiGraphics.fill(r.sideRight() + 6, r.sideTop(), r.sideRight() + 7, r.sideBottom(), 0x596B5223);
        drawSide(guiGraphics, snapshot, r, mouseX, mouseY);
        drawHeader(guiGraphics, mouseX, mouseY);
        drawFooter(guiGraphics, r);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (this.pendingTooltip != null) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0F, 0.0F, 400.0F);
            this.pendingTooltip.run();
            guiGraphics.pose().popPose();
        }
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

        int cursor = MARGIN;
        int titleRoom = layoutX - 8 - cursor;
        String title = ellipsis(this.title.getString(), titleRoom);
        guiGraphics.drawString(this.font, title, cursor, textY, MasteryPalette.GOLD_BRIGHT, false);
        // 和标题里的「 · 」同样的间距
        cursor += this.font.width(title) + this.font.width(" ");
        String source = "· " + I18n.get("albionmastery.source." + this.providerId);
        int extra = this.viewOnly ? this.font.width(I18n.get("gui.albionmastery.spec_page.view_only")) + 8 : 0;
        if (cursor + this.font.width(source) + extra < layoutX - 8) {
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
        // 文字按自身宽度居中，奇偶差的 1 像素统一往左
        int textX = x + (width - this.font.width(text) + 1) / 2;
        guiGraphics.drawString(this.font, text, textX, y + (height - 8) / 2, color, false);
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
            drawSlotTile(guiGraphics, view, x, y, width, hover);
            addSlotHit(view, x, y, width, SLOT_TILE_HEIGHT);
        }
    }

    /**
     * B 版的一个槽：顶上一道色条（普通金、突破橙），槽名、种类，装着的特化和等级；没开的写差什么。
     */
    private void drawSlotTile(GuiGraphics guiGraphics, SlotView view, int x, int y, int width, boolean hover) {
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
        String tag = slotTag(view, selected);
        int tagColor = selected ? MasteryPalette.GOLD : view.breakthrough() ? BREAK : MasteryPalette.GOLD;
        drawRight(guiGraphics, tag, x + width - PAD, y + 6, tagColor);
        guiGraphics.drawString(this.font, ellipsis(view.label().getString(), inner - fw(tag) - NAME_VALUE_GAP),
                x + PAD, y + 6, view.unlocked() ? MasteryPalette.TEXT_DIM : TEXT_FAINT, false);
        if (!view.unlocked()) {
            List<String> lines = splitNeed(view.need(), inner);
            for (int i = 0; i < lines.size(); i++) {
                guiGraphics.drawString(this.font, lines.get(i), x + PAD, y + 18 + i * LINE, TEXT_FAINT, false);
            }
            return;
        }
        if (view.spec() == null) {
            guiGraphics.drawString(this.font, I18n.get("gui.albionmastery.spec_page.slot_empty"), x + PAD, y + 18,
                    TEXT_FAINT, false);
            return;
        }
        guiGraphics.drawString(this.font, ellipsis(slotSpecName(view), inner), x + PAD, y + 18,
                selected ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT, false);
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
    private int drawSlotRow(GuiGraphics guiGraphics, SlotView view, int x, int y, int width, boolean hover) {
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
        String tag = slotTag(view, selected);
        drawRight(guiGraphics, tag, right, y + 3, selected ? MasteryPalette.GOLD
                : view.breakthrough() ? BREAK : MasteryPalette.GOLD);
        guiGraphics.drawString(this.font, ellipsis(view.label().getString(), right - left - fw(tag) - NAME_VALUE_GAP),
                left, y + 3, view.unlocked() ? MasteryPalette.TEXT_DIM : TEXT_FAINT, false);
        int lineY = y + 14;
        if (!view.unlocked()) {
            guiGraphics.drawString(this.font, ellipsis(view.need(), right - left), left, lineY, TEXT_FAINT, false);
            return y + SLOT_ROW_HEIGHT;
        }
        if (view.spec() == null) {
            guiGraphics.drawString(this.font, I18n.get("gui.albionmastery.spec_page.slot_empty"), left, lineY,
                    TEXT_FAINT, false);
            return y + SLOT_ROW_HEIGHT;
        }
        int marksWidth = levelMarksWidth(view);
        drawLevelMarks(guiGraphics, view, right - marksWidth, lineY);
        int nameColor = !view.active() ? MasteryPalette.WARN
                : selected ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT;
        guiGraphics.drawString(this.font, ellipsis(slotSpecName(view), right - left - marksWidth - NAME_VALUE_GAP),
                left, lineY, nameColor, false);
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

    /**
     * 「类型 100 级解锁（现在 81）」放不下一行时在「（」前断开，不在数字中间折行。
     */
    private List<String> splitNeed(String need, int width) {
        if (fw(need) <= width) {
            return List.of(need);
        }
        int cut = need.indexOf('（');
        if (cut < 0) {
            cut = need.indexOf('(');
        }
        if (cut <= 0) {
            return List.of(ellipsis(need, width));
        }
        return List.of(ellipsis(need.substring(0, cut).trim(), width), ellipsis(need.substring(cut), width));
    }

    // ---------------------------------------------------------------- 左栏

    /**
     * 左栏：现在改的是哪一份配置、（武器页）这把武器过没过门槛、点数、（A、C）槽位、合计效果，底部两个重置按钮。
     * 合计效果按剩下的高度决定列几项，放不下写「+N 项」；固定部分本身放不下时左栏可以滚动。
     */
    private void drawSide(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, Regions r,
                          int mouseX, int mouseY) {
        int left = r.sideLeft();
        int right = r.sideRight();
        int width = right - left;
        int top = r.sideTop();
        int buttonsTop = this.viewOnly ? r.sideBottom() : r.sideBottom() - respecHeight(width);
        int viewBottom = buttonsTop - (this.viewOnly ? 0 : 6);

        guiGraphics.enableScissor(left, top, right, viewBottom);
        int start = top - (int) Math.round(this.sideScroll);
        int cursor = start;
        cursor = drawModeBlock(guiGraphics, left, cursor, width, mouseX, mouseY, top, viewBottom);
        if (this.weaponKey != null) {
            cursor = drawWeaponGate(guiGraphics, snapshot, left, cursor, width);
        }
        cursor = drawPoints(guiGraphics, snapshot, left, cursor, width, r.layout() != SpecScreenLayout.B);
        if (r.layout() != SpecScreenLayout.B) {
            cursor += 4;
            for (int slot = 1; slot <= rules().slotCount; slot++) {
                SlotView view = slotView(slot, snapshot);
                boolean visible = cursor + SLOT_ROW_HEIGHT > top && cursor < viewBottom;
                boolean hover = visible && inside(mouseX, mouseY, left, cursor, width, SLOT_ROW_HEIGHT)
                        && mouseY >= top && mouseY < viewBottom;
                drawSlotRow(guiGraphics, view, left, cursor, width, hover);
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
            drawRespecButtons(guiGraphics, snapshot, left, buttonsTop, width, mouseX, mouseY);
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
                detail = Component.translatable("gui.albionmastery.spec_page.mode_type_overrides", overrides);
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
            guiGraphics.drawString(this.font, ellipsis(I18n.get("gui.albionmastery.spec_page.weapon_gate_rank",
                    reduction), width), x, cursor, TEXT_FAINT, false);
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
                           boolean sideBySide) {
        String overallKey = WeaponKeys.overallKey(this.providerId);
        int modAvailable = SpecPoints.modPointsAvailable(this.data, this.providerId, snapshot);
        int modTotal = SpecPoints.modPointsTotal(this.data, this.providerId, snapshot);
        int breakAvailable = SpecPoints.breakthroughPointsAvailable(this.data, this.typeKey, snapshot);
        int breakTotal = SpecPoints.breakthroughPointsTotal(this.data, this.typeKey, snapshot);
        String modLabel = SpecTerms.of(this.providerId, "point").getString();
        String breakLabel = I18n.get("gui.albionmastery.spec_page.break_points");
        String modSource = I18n.get("gui.albionmastery.spec_page.points_source",
                ClientWeaponRegistry.sourceName(overallKey).getString(), this.data.getOverallLevel(overallKey));
        String breakSource = I18n.get("gui.albionmastery.spec_page.points_source",
                this.typeName.getString(), this.data.getTypeLevel(this.typeKey));
        String modShort = I18n.get("gui.albionmastery.spec_page.points_level", this.data.getOverallLevel(overallKey));
        String breakShort = I18n.get("gui.albionmastery.spec_page.points_level", this.data.getTypeLevel(this.typeKey));
        if (sideBySide) {
            int boxWidth = (width - 4) / 2;
            drawPointsBox(guiGraphics, x, y, boxWidth, modLabel, modAvailable, modTotal, modSource, modShort,
                    MasteryPalette.GOLD);
            drawPointsBox(guiGraphics, x + width - boxWidth, y, boxWidth, breakLabel, breakAvailable, breakTotal,
                    breakSource, breakShort, BREAK);
            return y + POINTS_BOX_HEIGHT + 2;
        }
        drawPointsBox(guiGraphics, x, y, width, modLabel, modAvailable, modTotal, modSource, modShort,
                MasteryPalette.GOLD);
        drawPointsBox(guiGraphics, x, y + POINTS_BOX_HEIGHT + 4, width, breakLabel, breakAvailable, breakTotal,
                breakSource, breakShort, BREAK);
        return y + POINTS_BOX_HEIGHT * 2 + 4 + 2;
    }

    private void drawPointsBox(GuiGraphics guiGraphics, int x, int y, int width, String label, int available,
                               int total, String source, String sourceShort, int color) {
        guiGraphics.fill(x, y, x + width, y + POINTS_BOX_HEIGHT, 0xB3130F09);
        frame(guiGraphics, x, y, width, POINTS_BOX_HEIGHT, CARD_EDGE);
        int inner = width - PAD * 2;
        // 右上角放得下整句就写「枪械 85 级」，放不下只写「85 级」，再放不下不写
        String shown = fw(label) + NAME_VALUE_GAP + fw(source) <= inner ? source
                : fw(label) + NAME_VALUE_GAP + fw(sourceShort) <= inner ? sourceShort : "";
        guiGraphics.drawString(this.font, ellipsis(label, inner - (shown.isEmpty() ? 0 : fw(shown) + NAME_VALUE_GAP)),
                x + PAD, y + 3, MasteryPalette.TEXT_DIM, false);
        if (!shown.isEmpty()) {
            drawRight(guiGraphics, shown, x + width - PAD, y + 3, TEXT_FAINT);
        }
        String left = Integer.toString(available);
        guiGraphics.drawString(this.font, left, x + PAD, y + 13, available < 0 ? MasteryPalette.WARN : color, false);
        guiGraphics.drawString(this.font, "/ " + total, x + PAD + this.font.width(left) + 2, y + 13,
                MasteryPalette.TEXT_DIM, false);
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
        return this.font.width(cooldown) <= half;
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
            String label = remaining > 0L
                    ? I18n.get("gui.albionmastery.spec_page.respec_cooldown", base, clock(remaining))
                    : armed ? I18n.get("gui.albionmastery.spec_page.respec_confirm") : base;
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

        guiGraphics.enableScissor(left, listTop, r.mainRight(), listBottom);
        if (visible.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, Component.translatable("gui.albionmastery.spec_page.no_match",
                    SpecTerms.of(this.providerId, "spec")), (left + r.mainRight()) / 2, listTop + 20,
                    MasteryPalette.TEXT_DIM);
        }
        boolean mouseInList = mouseY >= listTop && mouseY < listBottom;
        for (Block block : blocks) {
            int top = offset + block.y();
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
        int tagWidth = card.tag() == null ? 0 : (int) Math.ceil(fw(card.tag()));
        String name = I18n.get(card.spec().nameKey());
        int nameColor = equipped ? MasteryPalette.GOLD_BRIGHT : card.dimmed() ? MasteryPalette.TEXT_DIM
                : MasteryPalette.TEXT;
        guiGraphics.drawString(this.font, ellipsis(name, inner - (tagWidth > 0 ? tagWidth + NAME_VALUE_GAP + 2 : 0)),
                x + PAD, y + PAD, nameColor, false);
        if (card.tag() != null) {
            drawRight(guiGraphics, card.tag(), x + width - PAD, y + PAD, card.tagColor());
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
        guiGraphics.enableScissor(left, top, right, bottom);
        int start = top - (int) Math.round(this.detailScroll);
        int cursor = start;

        String tag = card.tag();
        int tagWidth = tag == null ? 0 : (int) Math.ceil(fw(tag)) + NAME_VALUE_GAP;
        guiGraphics.drawString(this.font, ellipsis(I18n.get(spec.nameKey()), width - tagWidth), left, cursor,
                MasteryPalette.GOLD_BRIGHT, false);
        if (tag != null) {
            drawRight(guiGraphics, tag, right, cursor, card.tagColor());
        }
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
        cursor += 4;

        // 两个按钮：升级 / 突破、装进选中的槽 / 卸下
        if (!this.viewOnly) {
            int buttonWidth = (width - 4) / 2;
            int buttonHeight = SIDE_BUTTON_HEIGHT;
            Progress progress = card.progress();
            boolean visible = cursor + buttonHeight > top && cursor < bottom;
            String progressLabel = progress == null ? I18n.get("gui.albionmastery.spec_page.maxed") : progress.label();
            boolean progressEnabled = progress != null && progress.enabled();
            boolean hoverProgress = visible && progressEnabled && mouseY >= top && mouseY < bottom
                    && inside(mouseX, mouseY, left, cursor, buttonWidth, buttonHeight);
            drawButton(guiGraphics, left, cursor, buttonWidth, buttonHeight, Component.literal(progressLabel),
                    hoverProgress, progressEnabled, progressEnabled && progress.breakthrough());
            if (visible && progressEnabled) {
                int hitTop = Math.max(cursor, top);
                this.hits.add(new Hit(left, hitTop, buttonWidth, Math.min(cursor + buttonHeight, bottom) - hitTop,
                        button -> {
                            if (button != 0) {
                                return false;
                            }
                            sendProgress(spec, progress.breakthrough());
                            return true;
                        }));
            }
            int equipX = left + width - buttonWidth;
            int inSlot = slotOf(spec);
            boolean removing = inSlot == this.selectedSlot;
            Component blocked = removing ? null : equipBlocked(snapshot, spec, this.selectedSlot);
            String equipLabel = removing
                    ? I18n.get("gui.albionmastery.spec_page.unequip_button",
                    SpecTerms.slot(this.providerId, inSlot).getString())
                    : I18n.get("gui.albionmastery.spec_page.equip_button",
                    SpecTerms.slot(this.providerId, this.selectedSlot).getString());
            boolean equipEnabled = blocked == null;
            boolean hoverEquip = visible && equipEnabled && mouseY >= top && mouseY < bottom
                    && inside(mouseX, mouseY, equipX, cursor, buttonWidth, buttonHeight);
            drawButton(guiGraphics, equipX, cursor, buttonWidth, buttonHeight, Component.literal(equipLabel),
                    hoverEquip, equipEnabled, false);
            if (visible && equipEnabled) {
                int hitTop = Math.max(cursor, top);
                this.hits.add(new Hit(equipX, hitTop, buttonWidth, Math.min(cursor + buttonHeight, bottom) - hitTop,
                        button -> {
                            if (button != 0) {
                                return false;
                            }
                            toggleInSelectedSlot(spec);
                            return true;
                        }));
            }
            cursor += buttonHeight + 4;
            if (blocked != null) {
                for (String line : TextWrap.wrap(this.font, blocked.getString(), width)) {
                    guiGraphics.drawString(this.font, line, left, cursor, MasteryPalette.WARN, false);
                    cursor += LINE;
                }
            }
            cursor += 4;

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
        int viewport = bottom - top;
        this.detailMaxScroll = Math.max(0.0D, content - viewport);
        this.detailScroll = Mth.clamp(this.detailScroll, 0.0D, this.detailMaxScroll);
        if (this.detailMaxScroll > 0.0D) {
            drawScrollbar(guiGraphics, right + 2, top, bottom, viewport, content, this.detailScroll);
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
            int y = Math.min(mouseY + 12, Math.max(2, this.height - 4 - boxHeight));
            panel(graphics, x, y, boxWidth, boxHeight);
            int cy = y + 5;
            for (String line : lines) {
                graphics.drawString(this.font, line, x + 6, cy, MasteryPalette.TEXT, false);
                cy += LINE;
            }
        };
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
        String hint = I18n.get(r.layout() == SpecScreenLayout.C ? "gui.albionmastery.spec_page.hint_c"
                : "gui.albionmastery.spec_page.hint");
        String text = ellipsis(hint, right - left);
        guiGraphics.drawString(this.font, text, left, y, TEXT_FAINT, false);
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
        int whole = Mth.floor(x);
        float fraction = x - whole;
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
     * @param dimmed       名字压暗（还没解锁）
     */
    private record CardView(Specialization spec, int inSlot, boolean breakthrough, int level, boolean preview,
                            int normalLevel, int star, String levelText, @Nullable String tag, int tagColor,
                            boolean dimmed, SpecDescriber.Entries entries, @Nullable Progress progress) {
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
            levelText = I18n.get("gui.albionmastery.spec_page.level_need_max", normal);
        } else if (preview) {
            levelText = I18n.get("gui.albionmastery.spec_page.level_preview", levelText(breakthrough, 1));
        } else {
            levelText = levelText(breakthrough, level);
        }
        String tag = null;
        int tagColor = TEXT_FAINT;
        if (inSlot != 0) {
            tag = SpecTerms.slot(this.providerId, inSlot).getString();
            tagColor = MasteryPalette.GOLD;
        } else if (normal <= 0) {
            tag = I18n.get("gui.albionmastery.spec_page.tag_locked");
        } else {
            Specialization clash = clashingSpec(spec, 0);
            if (clash != null) {
                tag = I18n.get("gui.albionmastery.panel.spec_tag_conflict",
                        SpecTerms.slot(this.providerId, slotOf(clash)).getString(),
                        SpecTerms.of(this.providerId, "group").getString());
            }
        }
        return new CardView(spec, inSlot, breakthrough, shownLevel, preview, normal, star, levelText, tag, tagColor,
                preview, entries(snapshot, spec, breakthrough, shownLevel), progressFor(spec, snapshot));
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
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
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
        if (this.searchBox != null && this.searchBox.isFocused()) {
            return this.searchBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
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
     */
    private record Regions(SpecScreenLayout layout, int sideLeft, int sideTop, int sideRight, int sideBottom,
                           int mainLeft, int mainTop, int mainRight, int mainBottom,
                           int stripTop, int stripBottom, int detailLeft) {
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
