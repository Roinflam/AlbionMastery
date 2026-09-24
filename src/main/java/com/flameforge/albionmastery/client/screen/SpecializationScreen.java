package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.client.ClientMasteryCache;
import com.flameforge.albionmastery.client.KeyBindings;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.spec.SpecTerms;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.mixin.GuiOverlayAccessor;
import com.flameforge.albionmastery.network.C2SSelectSpecializationPacket;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.flameforge.albionmastery.client.weapon.ClientWeaponRegistry;
import com.flameforge.albionmastery.client.weapon.WeaponEntry;

/**
 * 一个武器类型的特化页面。
 *
 * <p>以前特化全塞在专精罗盘左侧那条 186 像素宽的详情面板里，枪械二十几个特化从上排到下，
 * 找一个要滚好几屏，也看不出「装上之后合起来是什么效果」。这里单独一整页：
 * <ul>
 *     <li>左栏：两个槽现在装的是什么、合计效果；鼠标停在某张卡片上时，合计效果换成「装上之后」的预览，
 *         变了的行亮起来，没了的行划掉；</li>
 *     <li>右边：搜索框（名字、分组、效果文字都能搜，比如搜「射速」）、分组筛选，下面是按分组排好的卡片网格。</li>
 * </ul>
 *
 * <p>操作和以前一样：左键装进第一特化、右键装进第二特化；点「制式」卡片就是清空那个槽。
 * 服务端的结果提示（成功、冷却、同组冲突）本来发在物品栏上方，被这个不透明的界面挡住了，
 * 这里在底部原样再显示一遍。
 *
 * <p>看别人的罗盘时同样能打开，只能看不能点。
 */
public class SpecializationScreen extends Screen {

    private static final int HEADER_HEIGHT = 20;
    private static final int MARGIN = 8;
    private static final int LINE = 10;
    private static final int CARD_MIN_WIDTH = 158;
    private static final int CARD_GAP = 5;
    private static final int CARD_PAD = 5;
    private static final int BULLET_WIDTH = 8;
    private static final int SEARCH_HEIGHT = 14;
    private static final int CHIP_HEIGHT = 12;
    private static final int FOOTER_HEIGHT = 12;
    private static final int BACK_WIDTH = 52;
    private static final double SCROLL_STEP = 22.0D;

    /** 选了特化之后多久之内，底部显示服务端发来的结果提示。 */
    private static final long FEEDBACK_WINDOW_MS = 6000L;

    private final MasteryData data;
    private final String typeKey;
    private final String providerId;
    private final Component typeName;
    private final Screen parent;
    private final boolean viewOnly;
    private final List<Specialization> specs;

    /** 武器键：null 表示改的是类型的通用配置（中圈），否则是这件武器的独立配置（外圈）。 */
    @Nullable
    private final String weaponKey;

    /** 左栏顶上那块的按钮（恢复通用配置 / 改通用配置），屏幕坐标，每帧重建。 */
    private final List<Hit<String>> sideHits = new ArrayList<>();

    /**
     * 卡片文字的缓存：得失两句按当前卡宽拆好的行、卡片高度、搜索用的纯文本。
     *
     * <p>一屏二三十张卡，每帧都重新生成说明再拆行，光文字排版就要做上百次；
     * 这些东西只随配置快照和卡宽变，所以两者任一变了才整体作废。
     */
    private final Map<Specialization, CardText> cardCache = new HashMap<>();
    @Nullable
    private MasteryConfig.Snapshot cardCacheSnapshot;
    private int cardCacheWidth = -1;

    @Nullable
    private EditBox searchBox;

    /** 当前只看哪一组；null 表示全部。 */
    @Nullable
    private String familyFilter;

    private double listScroll;
    private double listMaxScroll;
    private double sideScroll;
    private double sideMaxScroll;

    /** 本帧画出来的卡片和筛选按钮，点击时对照（屏幕坐标，已经扣过滚动）。 */
    private final List<Hit<Specialization>> cardHits = new ArrayList<>();
    private final List<Hit<String>> chipHits = new ArrayList<>();

    @Nullable
    private Specialization hoveredSpec;

    /** 最近一次发出选择请求的时刻，用来决定要不要显示动作栏提示。 */
    private long lastSelectMs = -FEEDBACK_WINDOW_MS;

    /** 本帧布局用到的几条边界。 */
    private int sideRight;
    private int mainLeft;
    private int mainRight;
    private int listTop;
    private int listBottom;

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
        this.specs = Specialization.forSource(this.providerId);
    }

    @Override
    protected void init() {
        computeLayout();
        // 换语言、缩放窗口都会走 init，缓存的文字得重来
        this.cardCache.clear();
        String previous = this.searchBox == null ? "" : this.searchBox.getValue();
        this.searchBox = new EditBox(this.font, this.mainLeft + 5, HEADER_HEIGHT + 9,
                this.mainRight - this.mainLeft - 10, SEARCH_HEIGHT - 6,
                Component.translatable("gui.albionmastery.spec_page.search"));
        this.searchBox.setHint(Component.translatable("gui.albionmastery.spec_page.search")
                .withStyle(ChatFormatting.DARK_GRAY));
        this.searchBox.setBordered(false);
        this.searchBox.setTextColor(MasteryPalette.TEXT & 0xFFFFFF);
        this.searchBox.setMaxLength(32);
        this.searchBox.setValue(previous);
        this.searchBox.setResponder(text -> this.listScroll = 0.0D);
        addRenderableWidget(this.searchBox);
    }

    /**
     * 算左栏、右边列表的边界。窗口缩放时 {@code init} 会重新调，这里跟着重算。
     */
    private void computeLayout() {
        int sideWidth = Mth.clamp(this.width * 30 / 100, 150, 210);
        this.sideRight = MARGIN + sideWidth;
        this.mainLeft = this.sideRight + MARGIN + 2;
        this.mainRight = this.width - MARGIN;
        this.listBottom = this.height - MARGIN - FOOTER_HEIGHT;
    }

    // ======================================================================
    // 绘制
    // ======================================================================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        computeLayout();
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        guiGraphics.fill(0, 0, this.width, this.height, MasteryPalette.BG);

        this.cardHits.clear();
        this.chipHits.clear();
        this.sideHits.clear();

        // 右边先画（要知道鼠标停在哪张卡上），左栏的预览再按它画
        int chipsBottom = drawChips(guiGraphics, mouseX, mouseY, HEADER_HEIGHT + 6 + SEARCH_HEIGHT + 5);
        this.listTop = chipsBottom + 4;
        this.hoveredSpec = null;
        drawCardList(guiGraphics, snapshot, mouseX, mouseY);
        drawSidebar(guiGraphics, snapshot, mouseX, mouseY);

        drawHeader(guiGraphics, mouseX, mouseY);
        drawSearchFrame(guiGraphics);
        drawFooter(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * 顶栏：标题、来源名、右端返回按钮。
     */
    private void drawHeader(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.fill(0, 0, this.width, HEADER_HEIGHT, MasteryPalette.PANEL);
        guiGraphics.fill(0, HEADER_HEIGHT - 1, this.width, HEADER_HEIGHT, MasteryPalette.EDGE);
        int textY = (HEADER_HEIGHT - this.font.lineHeight) / 2 + 1;
        int cursor = 10;
        guiGraphics.drawString(this.font, this.title, cursor, textY, MasteryPalette.GOLD_BRIGHT, false);
        cursor += this.font.width(this.title) + 8;
        Component source = Component.translatable("albionmastery.source." + this.providerId);
        guiGraphics.drawString(this.font, Component.literal("· ").append(source), cursor, textY,
                MasteryPalette.TEXT_DIM, false);
        if (this.viewOnly) {
            Component hint = Component.translatable("gui.albionmastery.spec_page.view_only");
            guiGraphics.drawString(this.font, hint, this.width - BACK_WIDTH - 16 - this.font.width(hint), textY,
                    MasteryPalette.WARN, false);
        }
        drawButton(guiGraphics, backX(), 2, BACK_WIDTH, HEADER_HEIGHT - 4,
                Component.translatable("gui.albionmastery.spec_page.back"), isOnBack(mouseX, mouseY));
    }

    private int backX() {
        return this.width - BACK_WIDTH - 6;
    }

    private boolean isOnBack(double mouseX, double mouseY) {
        return mouseX >= backX() && mouseX < backX() + BACK_WIDTH && mouseY >= 2 && mouseY < HEADER_HEIGHT - 2;
    }

    /**
     * 画一个描边按钮，和罗盘界面左下角那两个同一个样子。
     */
    private void drawButton(GuiGraphics guiGraphics, int x, int y, int width, int height, Component label,
                            boolean hover) {
        guiGraphics.fill(x, y, x + width, y + height, hover ? 0xF01E1810 : 0xC0130F09);
        int border = hover ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK;
        guiGraphics.fill(x, y, x + width, y + 1, border);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, border);
        guiGraphics.fill(x, y, x + 1, y + height, border);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, border);
        guiGraphics.drawCenteredString(this.font, label, x + width / 2, y + (height - 8) / 2,
                hover ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT);
    }

    /**
     * 搜索框的外框：原版的灰边黑底和这套配色不搭，自己画。
     */
    private void drawSearchFrame(GuiGraphics guiGraphics) {
        int top = HEADER_HEIGHT + 6;
        boolean focused = this.searchBox != null && this.searchBox.isFocused();
        guiGraphics.fill(this.mainLeft, top, this.mainRight, top + SEARCH_HEIGHT, MasteryPalette.PANEL);
        int border = focused ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK;
        guiGraphics.fill(this.mainLeft, top, this.mainRight, top + 1, border);
        guiGraphics.fill(this.mainLeft, top + SEARCH_HEIGHT - 1, this.mainRight, top + SEARCH_HEIGHT, border);
        guiGraphics.fill(this.mainLeft, top, this.mainLeft + 1, top + SEARCH_HEIGHT, border);
        guiGraphics.fill(this.mainRight - 1, top, this.mainRight, top + SEARCH_HEIGHT, border);
    }

    /**
     * 分组筛选：「全部」加上每个分组一个小按钮，放不下自动换行。
     *
     * @return 筛选区的底边
     */
    private int drawChips(GuiGraphics guiGraphics, int mouseX, int mouseY, int top) {
        int x = this.mainLeft;
        int y = top;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Specialization spec : this.specs) {
            if (!spec.isStandard()) {
                counts.merge(spec.family(), 1, Integer::sum);
            }
        }
        List<String> families = new ArrayList<>();
        families.add(null);
        families.addAll(counts.keySet());
        for (String family : families) {
            Component label = family == null
                    ? Component.translatable("gui.albionmastery.spec_page.all", this.specs.size() - 1)
                    : Component.translatable("gui.albionmastery.spec_page.family_chip",
                            Component.translatable(familyKey(family)), counts.get(family));
            int width = this.font.width(label) + 10;
            if (x + width > this.mainRight && x > this.mainLeft) {
                x = this.mainLeft;
                y += CHIP_HEIGHT + 3;
            }
            boolean active = family == null ? this.familyFilter == null : family.equals(this.familyFilter);
            boolean hover = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + CHIP_HEIGHT;
            guiGraphics.fill(x, y, x + width, y + CHIP_HEIGHT,
                    active ? 0x40E8C25A : hover ? 0x22FFFFFF : 0x80130F09);
            int border = active ? MasteryPalette.GOLD : hover ? MasteryPalette.GOLD_DARK : 0xFF3A2E18;
            guiGraphics.fill(x, y + CHIP_HEIGHT - 1, x + width, y + CHIP_HEIGHT, border);
            guiGraphics.drawString(this.font, label, x + 5, y + 2,
                    active ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT, false);
            this.chipHits.add(new Hit<>(family, x, y, width, CHIP_HEIGHT));
            x += width + 3;
        }
        return y + CHIP_HEIGHT;
    }

    /**
     * 右边的卡片网格：按分组分段，每段一个小标题，卡片按列数排；一行的高度取这一行里最高的卡片。
     */
    private void drawCardList(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, int mouseX, int mouseY) {
        int width = this.mainRight - this.mainLeft - 6;
        int columns = Mth.clamp((width + CARD_GAP) / (CARD_MIN_WIDTH + CARD_GAP), 1, 3);
        int cardWidth = (width - (columns - 1) * CARD_GAP) / columns;
        int textWidth = cardTextWidth(cardWidth);

        // 先排版：算出每个分组标题和每张卡片在内容坐标里的位置
        List<Block> blocks = new ArrayList<>();
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        int cursor = 0;
        String currentFamily = null;
        List<Specialization> row = new ArrayList<>();
        List<Specialization> visible = new ArrayList<>();
        for (Specialization spec : this.specs) {
            if (this.familyFilter != null && !this.familyFilter.equals(spec.family())) {
                continue;
            }
            if (!query.isEmpty() && !cardText(spec, snapshot, textWidth).searchText().contains(query)) {
                continue;
            }
            visible.add(spec);
        }
        for (int i = 0; i < visible.size(); i++) {
            Specialization spec = visible.get(i);
            if (i == 0 || !spec.family().equals(currentFamily)) {
                cursor = flushRow(blocks, row, cursor, cardWidth, columns, snapshot);
                currentFamily = spec.family();
                if (!spec.isStandard()) {
                    blocks.add(new Block(null, 0, cursor, width, LINE + 3,
                            Component.translatable(spec.familyKey())));
                    cursor += LINE + 4;
                }
            }
            row.add(spec);
            if (row.size() == columns) {
                cursor = flushRow(blocks, row, cursor, cardWidth, columns, snapshot);
            }
        }
        cursor = flushRow(blocks, row, cursor, cardWidth, columns, snapshot);

        int viewport = Math.max(1, this.listBottom - this.listTop);
        this.listMaxScroll = Math.max(0.0D, cursor - viewport);
        this.listScroll = Mth.clamp(this.listScroll, 0.0D, this.listMaxScroll);
        int offset = this.listTop - (int) Math.round(this.listScroll);

        guiGraphics.enableScissor(this.mainLeft, this.listTop, this.mainRight, this.listBottom);
        if (visible.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, Component.translatable("gui.albionmastery.spec_page.no_match",
                            SpecTerms.of(this.providerId, "spec")),
                    (this.mainLeft + this.mainRight) / 2, this.listTop + 20, MasteryPalette.TEXT_DIM);
        }
        boolean mouseInList = mouseY >= this.listTop && mouseY < this.listBottom;
        for (Block block : blocks) {
            int top = offset + block.y();
            if (top + block.height() < this.listTop || top > this.listBottom) {
                continue;
            }
            int left = this.mainLeft + block.x();
            if (block.spec() == null) {
                drawFamilyHeader(guiGraphics, block.header(), left, top, block.width());
                continue;
            }
            boolean hover = mouseInList && mouseX >= left && mouseX < left + block.width()
                    && mouseY >= top && mouseY < top + block.height();
            if (hover) {
                this.hoveredSpec = block.spec();
            }
            drawCard(guiGraphics, snapshot, block.spec(), left, top, block.width(), block.height(), hover);
            this.cardHits.add(new Hit<>(block.spec(), left, Math.max(top, this.listTop), block.width(),
                    Math.min(top + block.height(), this.listBottom) - Math.max(top, this.listTop)));
        }
        guiGraphics.disableScissor();

        if (this.listMaxScroll > 0.0D) {
            drawScrollbar(guiGraphics, this.mainRight - 2, this.listTop, this.listBottom, viewport, cursor,
                    this.listScroll);
        }
    }

    /**
     * 把攒着的一行卡片落到版面上。
     *
     * @return 下一行的起始 Y
     */
    private int flushRow(List<Block> blocks, List<Specialization> row, int cursor, int cardWidth, int columns,
                         MasteryConfig.Snapshot snapshot) {
        if (row.isEmpty()) {
            return cursor;
        }
        int height = 0;
        for (Specialization spec : row) {
            height = Math.max(height, cardHeight(spec, snapshot, cardWidth));
        }
        for (int i = 0; i < row.size(); i++) {
            blocks.add(new Block(row.get(i), i * (cardWidth + CARD_GAP), cursor, cardWidth, height, null));
        }
        row.clear();
        return cursor + height + CARD_GAP;
    }

    /**
     * 搜索用的纯文本：名字、分组名、每一条得失说明，全部小写，一条一行。
     * 分行拼是为了关键字不会横跨两条说明凑出来。
     */
    private String searchText(Specialization spec, SpecDescriber.Lines lines) {
        StringBuilder text = new StringBuilder(Component.translatable(spec.nameKey()).getString());
        if (!spec.isStandard()) {
            text.append('\n').append(Component.translatable(spec.familyKey()).getString());
        }
        if (lines != null) {
            for (Component line : lines.gains()) {
                text.append('\n').append(line.getString());
            }
            for (Component line : lines.costs()) {
                text.append('\n').append(line.getString());
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private void drawFamilyHeader(GuiGraphics guiGraphics, Component name, int x, int y, int width) {
        guiGraphics.drawString(this.font, name, x, y + 1, MasteryPalette.GOLD, false);
        int lineLeft = x + this.font.width(name) + 5;
        if (lineLeft < x + width) {
            guiGraphics.fill(lineLeft, y + 5, x + width, y + 6, 0x406B5223);
        }
    }

    private static int cardTextWidth(int cardWidth) {
        return cardWidth - CARD_PAD * 2 - BULLET_WIDTH;
    }

    /**
     * 卡片的得失两句（制式只有一句说明），按卡宽拆好行，走缓存。
     */
    private CardText cardText(Specialization spec, MasteryConfig.Snapshot snapshot, int textWidth) {
        if (snapshot != this.cardCacheSnapshot || textWidth != this.cardCacheWidth) {
            this.cardCache.clear();
            this.cardCacheSnapshot = snapshot;
            this.cardCacheWidth = textWidth;
        }
        CardText cached = this.cardCache.get(spec);
        if (cached == null) {
            cached = buildCardText(spec, snapshot, textWidth);
            this.cardCache.put(spec, cached);
        }
        return cached;
    }

    private CardText buildCardText(Specialization spec, MasteryConfig.Snapshot snapshot, int textWidth) {
        Component gain;
        Component cost = null;
        SpecDescriber.Lines lines = null;
        if (spec.isStandard()) {
            gain = Component.translatable(spec.gainKey());
        } else {
            lines = SpecDescriber.describe(snapshot.specValues(spec), this.providerId);
            gain = lines.gainLine() == null ? Component.translatable("gui.albionmastery.spec_desc.none")
                    : lines.gainLine();
            cost = lines.costLine();
        }
        List<String> gainLines = TextWrap.wrap(this.font, gain.getString(), textWidth);
        List<String> costLines = cost == null ? List.of()
                : TextWrap.wrap(this.font, cost.getString(), textWidth);
        int height = CARD_PAD + LINE + 3 + (gainLines.size() + costLines.size()) * LINE + CARD_PAD - 1;
        return new CardText(gainLines, costLines, height, searchText(spec, lines));
    }

    private int cardHeight(Specialization spec, MasteryConfig.Snapshot snapshot, int width) {
        return cardText(spec, snapshot, cardTextWidth(width)).height();
    }

    /**
     * 画一张特化卡片。
     */
    private void drawCard(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, Specialization spec,
                          int x, int y, int width, int height, boolean hover) {
        SlotState state = slotState(snapshot);
        int inSlot = spec.key().equals(state.key(1)) ? 1 : spec.key().equals(state.key(2)) ? 2 : 0;
        boolean standardActive = spec.isStandard() && state.key(1) == null && state.key(2) == null;
        boolean selected = inSlot != 0 || standardActive;
        int conflictSlot = 0;
        if (!selected && !spec.isStandard()) {
            if (spec.conflictsWith(state.spec(1))) {
                conflictSlot = 1;
            } else if (spec.conflictsWith(state.spec(2))) {
                conflictSlot = 2;
            }
        }

        int background = selected ? 0x38E8C25A : hover ? 0x2AFFFFFF : 0xB0130F09;
        guiGraphics.fill(x, y, x + width, y + height, background);
        int border = selected ? MasteryPalette.GOLD : hover ? MasteryPalette.GOLD_DARK : 0xFF2C2314;
        guiGraphics.fill(x, y, x + width, y + 1, border);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, border);
        guiGraphics.fill(x, y, x + 1, y + height, border);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, border);
        if (selected) {
            guiGraphics.fill(x + 1, y + 1, x + 3, y + height - 1, MasteryPalette.GOLD);
        }

        // 右上角的状态：装在哪个槽 / 和哪个槽同组
        Component tag = null;
        if (inSlot != 0) {
            tag = slotTag(snapshot, state, inSlot);
        } else if (standardActive) {
            tag = Component.translatable("gui.albionmastery.panel.spec_active").withStyle(ChatFormatting.GOLD);
        } else if (conflictSlot != 0) {
            tag = Component.translatable("gui.albionmastery.panel.spec_tag_conflict",
                    SpecTerms.slot(this.providerId, conflictSlot), SpecTerms.of(this.providerId, "group"))
                    .withStyle(ChatFormatting.GRAY);
        }
        int tagWidth = tag == null ? 0 : this.font.width(tag);
        Component name = Component.translatable(spec.nameKey());
        int nameColor = selected ? MasteryPalette.GOLD_BRIGHT
                : conflictSlot != 0 ? MasteryPalette.TEXT_DIM : MasteryPalette.TEXT;
        int nameMax = width - CARD_PAD * 2 - (tagWidth > 0 ? tagWidth + 6 : 0);
        guiGraphics.drawString(this.font, this.font.plainSubstrByWidth(name.getString(), Math.max(20, nameMax)),
                x + CARD_PAD, y + CARD_PAD, nameColor, false);
        if (tag != null) {
            guiGraphics.drawString(this.font, tag, x + width - CARD_PAD - tagWidth, y + CARD_PAD,
                    MasteryPalette.GOLD, false);
        }

        CardText text = cardText(spec, snapshot, cardTextWidth(width));
        int cursor = y + CARD_PAD + LINE + 3;
        cursor = drawBulleted(guiGraphics, spec.isStandard() ? "·" : "+", MasteryPalette.PROGRESS, text.gainLines(),
                x + CARD_PAD, cursor, MasteryPalette.TEXT_DIM);
        if (!text.costLines().isEmpty()) {
            drawBulleted(guiGraphics, "−", MasteryPalette.WARN, text.costLines(), x + CARD_PAD, cursor,
                    MasteryPalette.TEXT_DIM);
        }
    }

    /**
     * 行首一个符号，后面自动换行的文字，换行后和第一行文字左对齐。
     *
     * @return 结束后的 Y
     */
    private int drawBulleted(GuiGraphics guiGraphics, String bullet, int bulletColor, Component text,
                             int x, int y, int width, int color) {
        return drawBulleted(guiGraphics, bullet, bulletColor,
                TextWrap.wrap(this.font, text.getString(), width), x, y, color);
    }

    /**
     * 同上，文字已经拆好行了。
     *
     * @return 结束后的 Y
     */
    private int drawBulleted(GuiGraphics guiGraphics, String bullet, int bulletColor,
                             List<String> lines, int x, int y, int color) {
        guiGraphics.drawString(this.font, bullet, x, y, bulletColor, false);
        for (String line : lines) {
            guiGraphics.drawString(this.font, line, x + BULLET_WIDTH, y, color, false);
            y += LINE;
        }
        return y;
    }

    /**
     * 左栏：两个槽、合计效果（或悬停预览）、没解锁时的进度。内容超出时可以单独滚动。
     */
    private void drawSidebar(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, int mouseX, int mouseY) {
        int left = MARGIN;
        int right = this.sideRight;
        int top = HEADER_HEIGHT + 6;
        int bottom = this.listBottom;
        guiGraphics.fill(left, top, right, bottom, MasteryPalette.PANEL);
        guiGraphics.fill(right - 1, top, right, bottom, MasteryPalette.EDGE);

        int x = left + 6;
        int width = right - left - 12;
        guiGraphics.enableScissor(left, top, right, bottom);
        int start = top + 6 - (int) Math.round(this.sideScroll);
        int cursor = start;

        cursor = drawModeBlock(guiGraphics, x, cursor, width, mouseX, mouseY, top, bottom);

        SlotState state = slotState(snapshot);
        for (int slot = 1; slot <= WeaponKeys.SPEC_SLOTS; slot++) {
            cursor = drawSlotBox(guiGraphics, snapshot, state, slot, x, cursor, width) + 4;
        }

        cursor += 4;
        cursor = drawEffects(guiGraphics, snapshot, state, x, cursor, width);

        if (!SpecializationEffects.isSlotUnlocked(this.data, this.typeKey, 1, snapshot)) {
            cursor = drawRequirements(guiGraphics, snapshot, x, cursor + 8, width);
        }
        if (!this.viewOnly) {
            cursor += 8;
            for (String line : TextWrap.wrap(this.font,
                    Component.translatable("gui.albionmastery.spec_page.hint",
                            SpecTerms.slot(this.providerId, 1), SpecTerms.slot(this.providerId, 2))
                            .getString(), width)) {
                guiGraphics.drawString(this.font, line, x, cursor, 0xFF6F634C, false);
                cursor += LINE;
            }
        }
        guiGraphics.disableScissor();

        int content = cursor - start + 6;
        int viewport = bottom - top;
        this.sideMaxScroll = Math.max(0.0D, content - viewport);
        this.sideScroll = Mth.clamp(this.sideScroll, 0.0D, this.sideMaxScroll);
        if (this.sideMaxScroll > 0.0D) {
            drawScrollbar(guiGraphics, right - 4, top, bottom, viewport, content, this.sideScroll);
        }
    }

    /**
     * 左栏最上面一块：现在改的是哪一份配置，外加一个切换按钮。
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
            cursor += 3;
            int height = 14;
            boolean visible = cursor + height > viewTop && cursor < viewBottom;
            boolean hover = visible && mouseX >= x && mouseX < x + width && mouseY >= Math.max(cursor, viewTop)
                    && mouseY < Math.min(cursor + height, viewBottom);
            drawButton(guiGraphics, x, cursor, width, height, actionLabel, hover);
            if (visible) {
                int hitTop = Math.max(cursor, viewTop);
                this.sideHits.add(new Hit<>(action, x, hitTop, width, Math.min(cursor + height, viewBottom) - hitTop));
            }
            cursor += height;
        }
        guiGraphics.fill(x, cursor + 4, x + width, cursor + 5, 0x406B5223);
        return cursor + 9;
    }

    /**
     * 一个槽的小框：槽名 + 状态，下面是装着的特化名。
     *
     * @return 结束后的 Y
     */
    private int drawSlotBox(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, SlotState state, int slot,
                            int x, int y, int width) {
        int height = LINE * 2 + 8;
        boolean unlocked = SpecializationEffects.isSlotUnlocked(this.data, this.typeKey, slot, snapshot);
        guiGraphics.fill(x, y, x + width, y + height, 0x60000000);
        guiGraphics.fill(x, y, x + 2, y + height, unlocked ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK);
        Component slotName = SpecTerms.slot(this.providerId, slot);
        guiGraphics.drawString(this.font, slotName, x + 6, y + 4, MasteryPalette.TEXT_DIM, false);

        Component status;
        if (!unlocked) {
            status = Component.translatable("gui.albionmastery.panel.spec_slot_locked", snapshot.specSlotLevel(slot))
                    .withStyle(ChatFormatting.GRAY);
        } else if (state.spec(slot) == null) {
            status = Component.translatable("gui.albionmastery.spec_page.slot_empty").withStyle(ChatFormatting.GRAY);
        } else {
            status = slotTag(snapshot, state, slot);
        }
        // 侧栏在 1080p、GUI 缩放 4 档时只有一百三十来像素，长状态（「和主改装同部位，未生效」）会压到左边的槽名上，
        // 放不下就截短
        int statusRoom = width - 10 - this.font.width(slotName) - 6;
        FormattedCharSequence statusText = status.getVisualOrderText();
        if (this.font.width(status) > statusRoom) {
            String cut = this.font.plainSubstrByWidth(status.getString(), Math.max(8, statusRoom - this.font.width("..")));
            statusText = Component.literal(cut + "..").withStyle(status.getStyle()).getVisualOrderText();
        }
        guiGraphics.drawString(this.font, statusText, x + width - 4 - this.font.width(statusText), y + 4,
                MasteryPalette.GOLD, false);

        Specialization spec = state.spec(slot);
        Component name = spec == null
                ? Component.translatable("spec.albionmastery." + this.providerId + ".standard")
                : Component.translatable(spec.nameKey()).append(Component.literal("  ")
                        .append(Component.translatable(spec.familyKey()).withStyle(ChatFormatting.DARK_GRAY)));
        guiGraphics.drawString(this.font, name, x + 6, y + 4 + LINE + 1,
                spec == null ? MasteryPalette.TEXT_DIM : MasteryPalette.GOLD_BRIGHT, false);
        return y + height;
    }

    /**
     * 装在某个槽里的特化的状态标签：生效中 / 等级不够未生效 / 和另一槽同组未生效。
     */
    private Component slotTag(MasteryConfig.Snapshot snapshot, SlotState state, int slot) {
        if (slot == 2 && state.spec(2) != null && state.spec(2).conflictsWith(state.spec(1))) {
            return Component.translatable("gui.albionmastery.panel.spec_tag_conflict_inactive",
                            SpecTerms.slot(this.providerId, 2), SpecTerms.slot(this.providerId, 1),
                            SpecTerms.of(this.providerId, "group"))
                    .withStyle(ChatFormatting.RED);
        }
        boolean active = SpecializationEffects.isSlotActive(this.data, this.typeKey, null, slot, snapshot);
        return Component.translatable(active
                        ? "gui.albionmastery.panel.spec_tag_active" : "gui.albionmastery.panel.spec_tag_inactive",
                SpecTerms.slot(this.providerId, slot),
                snapshot.specSlotLevel(slot)).withStyle(active ? ChatFormatting.GOLD : ChatFormatting.GRAY);
    }

    /**
     * 合计效果；鼠标停在卡片上时换成「装上之后」的预览。预览里变了的行亮起来，被替换掉的行划掉列在最后。
     *
     * @return 结束后的 Y
     */
    private int drawEffects(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, SlotState state,
                            int x, int y, int width) {
        SpecializationValues current = combined(snapshot, state.spec(1), state.spec(2));
        SpecDescriber.Lines currentLines = SpecDescriber.describe(current, this.providerId);

        Specialization candidate = this.viewOnly ? null : this.hoveredSpec;
        int slot = hasShiftDown() ? 2 : 1;
        Component title = candidate == null
                ? Component.translatable("gui.albionmastery.spec_page.total")
                : Component.translatable("gui.albionmastery.spec_page.preview",
                        SpecTerms.slot(this.providerId, slot));
        int cursor = drawSection(guiGraphics, title, x, y, width);

        if (candidate != null) {
            Component blocked = previewBlocked(snapshot, state, candidate, slot);
            if (blocked != null) {
                for (String line : TextWrap.wrap(this.font, blocked.getString(), width)) {
                    guiGraphics.drawString(this.font, line, x, cursor, MasteryPalette.WARN, false);
                    cursor += LINE;
                }
                return cursor;
            }
            Specialization first = slot == 1 ? nullIfStandard(candidate) : state.spec(1);
            Specialization second = slot == 2 ? nullIfStandard(candidate) : state.spec(2);
            SpecDescriber.Lines preview = SpecDescriber.describe(combined(snapshot, first, second), this.providerId);
            Set<String> before = texts(currentLines);
            Set<String> after = texts(preview);
            cursor = drawEffectLines(guiGraphics, preview, before, x, cursor, width);
            // 被换掉之后不再有的效果，划掉列出来，一眼看出失去了什么
            for (Component line : currentLines.gains()) {
                if (!after.contains(line.getString())) {
                    cursor = drawBulleted(guiGraphics, "+", 0xFF5A5040,
                            line.copy().withStyle(ChatFormatting.STRIKETHROUGH), x, cursor, width - BULLET_WIDTH,
                            0xFF5A5040);
                }
            }
            for (Component line : currentLines.costs()) {
                if (!after.contains(line.getString())) {
                    cursor = drawBulleted(guiGraphics, "−", 0xFF5A5040,
                            line.copy().withStyle(ChatFormatting.STRIKETHROUGH), x, cursor, width - BULLET_WIDTH,
                            0xFF5A5040);
                }
            }
            return cursor;
        }
        return drawEffectLines(guiGraphics, currentLines, null, x, cursor, width);
    }

    /**
     * 画一组得失；给了 {@code before} 时，不在 {@code before} 里的行（新出现 / 数值变了）用亮色。
     */
    private int drawEffectLines(GuiGraphics guiGraphics, SpecDescriber.Lines lines, @Nullable Set<String> before,
                                int x, int y, int width) {
        if (lines.isEmpty()) {
            guiGraphics.drawString(this.font, Component.translatable("gui.albionmastery.spec_page.no_effect"),
                    x, y, MasteryPalette.TEXT_DIM, false);
            return y + LINE;
        }
        for (Component line : lines.gains()) {
            boolean changed = before != null && !before.contains(line.getString());
            y = drawBulleted(guiGraphics, "+", MasteryPalette.PROGRESS, line, x, y, width - BULLET_WIDTH,
                    changed ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT_DIM);
        }
        for (Component line : lines.costs()) {
            boolean changed = before != null && !before.contains(line.getString());
            y = drawBulleted(guiGraphics, "−", MasteryPalette.WARN, line, x, y, width - BULLET_WIDTH,
                    changed ? 0xFFFFB27A : MasteryPalette.TEXT_DIM);
        }
        return y;
    }

    /**
     * 预览装不上的原因；装得上返回 null。
     */
    @Nullable
    private Component previewBlocked(MasteryConfig.Snapshot snapshot, SlotState state, Specialization candidate,
                                     int slot) {
        Component slotName = SpecTerms.slot(this.providerId, slot);
        if (!SpecializationEffects.isSlotUnlocked(this.data, this.typeKey, slot, snapshot)) {
            return Component.translatable("gui.albionmastery.spec_page.preview_locked", slotName,
                    snapshot.specSlotLevel(slot));
        }
        int other = slot == 1 ? 2 : 1;
        Specialization otherSpec = state.spec(other);
        if (!candidate.isStandard() && otherSpec == candidate) {
            return Component.translatable("gui.albionmastery.spec_page.preview_duplicate",
                    SpecTerms.slot(this.providerId, other));
        }
        if (candidate.conflictsWith(otherSpec)) {
            return Component.translatable("gui.albionmastery.spec_page.preview_conflict",
                    SpecTerms.slot(this.providerId, other),
                    Component.translatable(otherSpec.nameKey()), Component.translatable(candidate.familyKey()),
                    SpecTerms.of(this.providerId, "group"));
        }
        return null;
    }

    /**
     * 解锁进度：没解锁第一特化时，列出本类型和来源总体各差多少级。
     */
    private int drawRequirements(GuiGraphics guiGraphics, MasteryConfig.Snapshot snapshot, int x, int y, int width) {
        int need = snapshot.specSlotLevel(1);
        int cursor = drawSection(guiGraphics, Component.translatable("gui.albionmastery.spec_page.unlock"),
                x, y, width);
        cursor = drawBar(guiGraphics, Component.translatable("gui.albionmastery.panel.spec_need_overall"),
                this.data.getOverallLevel(WeaponKeys.overallKey(this.providerId)), need, x, cursor, width);
        return drawBar(guiGraphics, Component.translatable("gui.albionmastery.panel.spec_need_type"),
                this.data.getTypeLevel(this.typeKey), need, x, cursor, width);
    }

    private int drawBar(GuiGraphics guiGraphics, Component label, int level, int need, int x, int y, int width) {
        boolean done = level >= need;
        Component value = Component.literal(level + " / " + need);
        guiGraphics.drawString(this.font, label, x, y, MasteryPalette.TEXT_DIM, false);
        guiGraphics.drawString(this.font, value, x + width - this.font.width(value), y,
                done ? 0xFF7FD06A : MasteryPalette.TEXT, false);
        int barTop = y + LINE;
        guiGraphics.fill(x, barTop, x + width, barTop + 3, 0xFF17120A);
        int filled = (int) Math.round(width * Mth.clamp((double) level / Math.max(1, need), 0.0D, 1.0D));
        if (filled > 0) {
            guiGraphics.fill(x, barTop, x + filled, barTop + 3, done ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.PROGRESS);
        }
        return barTop + 7;
    }

    private int drawSection(GuiGraphics guiGraphics, Component title, int x, int y, int width) {
        guiGraphics.drawString(this.font, title, x, y, MasteryPalette.GOLD, false);
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
     * 底部：刚点过特化时，把服务端发来的动作栏提示（成功 / 冷却 / 同组冲突 / 未解锁）画出来。
     */
    private void drawFooter(GuiGraphics guiGraphics) {
        if (Util.getMillis() - this.lastSelectMs > FEEDBACK_WINDOW_MS) {
            return;
        }
        GuiOverlayAccessor gui = (GuiOverlayAccessor) Minecraft.getInstance().gui;
        Component message = gui.albionmastery$getOverlayMessage();
        int time = gui.albionmastery$getOverlayMessageTime();
        if (message == null || time <= 0) {
            return;
        }
        float alpha = Mth.clamp(time / 10.0F, 0.0F, 1.0F);
        int y = this.height - MARGIN - FOOTER_HEIGHT + 3;
        int color = RenderUtil.withAlpha(MasteryPalette.TEXT, alpha);
        guiGraphics.drawCenteredString(this.font, message, (this.mainLeft + this.mainRight) / 2, y, color);
    }

    // ======================================================================
    // 数据
    // ======================================================================

    /**
     * 两个槽现在各装着什么（槽没解锁、特化不属于这个来源的都算空）。
     */
    private SlotState slotState(MasteryConfig.Snapshot snapshot) {
        Specialization first = specInSlot(1);
        Specialization second = specInSlot(2);
        return new SlotState(first, second);
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

    @Nullable
    private Specialization specInSlot(int slot) {
        Specialization spec = Specialization.byKey(this.data.getSpecialization(configKey(), slot));
        if (spec == null || spec.isStandard() || !spec.sourceId().equals(this.providerId)) {
            return null;
        }
        return spec;
    }

    /**
     * 按两个槽算合计数值，规则和服务端一致：没解锁的槽不算，第二槽和第一槽同组时不算。
     */
    private SpecializationValues combined(MasteryConfig.Snapshot snapshot, @Nullable Specialization first,
                                          @Nullable Specialization second) {
        SpecializationValues result = SpecializationValues.NEUTRAL;
        Specialization applied = null;
        Specialization[] chosen = {first, second};
        for (int slot = 1; slot <= WeaponKeys.SPEC_SLOTS; slot++) {
            Specialization spec = chosen[slot - 1];
            if (spec == null || !SpecializationEffects.isSlotActive(this.data, this.typeKey, this.weaponKey, slot,
                    snapshot) || spec.conflictsWith(applied)) {
                continue;
            }
            SpecializationValues values = snapshot.specValues(spec);
            result = applied == null ? values : SpecializationValues.combine(result, values);
            applied = spec;
        }
        return result;
    }

    @Nullable
    private static Specialization nullIfStandard(Specialization spec) {
        return spec.isStandard() ? null : spec;
    }

    private static Set<String> texts(SpecDescriber.Lines lines) {
        Set<String> set = new HashSet<>();
        for (Component line : lines.gains()) {
            set.add(line.getString());
        }
        for (Component line : lines.costs()) {
            set.add(line.getString());
        }
        return set;
    }

    private String familyKey(String family) {
        return "spec_family.albionmastery." + this.providerId + "." + family;
    }

    // ======================================================================
    // 输入
    // ======================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && isOnBack(mouseX, mouseY)) {
            playClick();
            onClose();
            return true;
        }
        for (Hit<String> hit : this.sideHits) {
            if (button == 0 && hit.contains(mouseX, mouseY)) {
                playClick();
                if ("follow".equals(hit.value()) && this.weaponKey != null) {
                    NetworkHandler.sendToServer(new C2SSelectSpecializationPacket(this.typeKey, "", 1,
                            this.weaponKey, true));
                    this.lastSelectMs = Util.getMillis();
                } else if ("edit_type".equals(hit.value())) {
                    Minecraft.getInstance().setScreen(new SpecializationScreen(this.data, this.typeKey, this.typeName,
                            null, null, this, this.viewOnly));
                }
                return true;
            }
        }
        for (Hit<String> hit : this.chipHits) {
            if (button == 0 && hit.contains(mouseX, mouseY)) {
                this.familyFilter = hit.value();
                this.listScroll = 0.0D;
                playClick();
                return true;
            }
        }
        if (mouseY >= this.listTop && mouseY < this.listBottom) {
            for (Hit<Specialization> hit : this.cardHits) {
                if (hit.contains(mouseX, mouseY) && (button == 0 || button == 1)) {
                    if (!this.viewOnly) {
                        // 左键第一特化、右键第二特化；解锁、冷却、同组全由服务端再判一遍
                        int slot = button == 1 ? 2 : 1;
                        // 武器页点卡片改的是这件武器的独立配置（第一次点会先把通用配置抄过来）
                        NetworkHandler.sendToServer(new C2SSelectSpecializationPacket(this.typeKey,
                                hit.value().key(), slot, this.weaponKey == null ? "" : this.weaponKey, false));
                        this.lastSelectMs = Util.getMillis();
                        playClick();
                    }
                    return true;
                }
            }
        }
        if (this.searchBox != null && this.searchBox.isFocused()) {
            this.searchBox.setFocused(false);
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < this.sideRight) {
            this.sideScroll = Mth.clamp(this.sideScroll - delta * SCROLL_STEP, 0.0D, this.sideMaxScroll);
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
     * 两个槽的当前特化。
     */
    private record SlotState(@Nullable Specialization first, @Nullable Specialization second) {

        @Nullable
        Specialization spec(int slot) {
            return slot == 1 ? this.first : this.second;
        }

        @Nullable
        String key(int slot) {
            Specialization spec = spec(slot);
            return spec == null ? null : spec.key();
        }
    }

    /**
     * 一张卡片缓存下来的文字。
     *
     * @param gainLines  好处那句拆好的行
     * @param costLines  代价那句拆好的行，没有代价为空
     * @param height     卡片高度
     * @param searchText 搜索用的小写纯文本
     */
    private record CardText(List<String> gainLines, List<String> costLines,
                            int height, String searchText) {
    }

    /**
     * 版面上的一块：分组标题（spec 为 null）或者一张卡片。坐标是内容坐标，相对列表左上角。
     */
    private record Block(@Nullable Specialization spec, int x, int y, int width, int height,
                         @Nullable Component header) {
    }

    /**
     * 一块可点击区域（屏幕坐标）。
     */
    private record Hit<T>(@Nullable T value, int x, int y, int width, int height) {

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.width
                    && mouseY >= this.y && mouseY < this.y + this.height;
        }
    }
}
