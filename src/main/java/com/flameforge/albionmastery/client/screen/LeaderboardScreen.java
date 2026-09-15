package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.client.KeyBindings;
import com.flameforge.albionmastery.leaderboard.LeaderboardPeriod;
import com.flameforge.albionmastery.leaderboard.LeaderboardRow;
import com.flameforge.albionmastery.leaderboard.LeaderboardSort;
import com.flameforge.albionmastery.network.C2SRequestLeaderboardPacket;
import com.flameforge.albionmastery.network.C2SRequestPlayerMasteryPacket;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.network.S2CLeaderboardPacket;
import com.flameforge.albionmastery.client.PlayerSkins;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 全服专精排行榜界面。
 *
 * <p>从专精界面左下角进来。上面是时间范围（总榜 / 日 / 周 / 月，只有专用服务端才有这排）
 * 和排序方式两排按钮，中间是榜单，底下是自己的名次。
 * 点任意一行就向服务端要那个人的整份专精数据，回来后用同一个 {@link MasteryScreen} 画他的罗盘（只读）。
 *
 * <p>数据全部来自服务端：这个界面自己不排、不算，只负责把收到的东西画出来。
 * 界面开着的时候才处理回包，关了之后晚到的包一律丢掉。
 */
public class LeaderboardScreen extends Screen {

    /** 顶栏高度，和专精界面一致。 */
    private static final int HEADER_HEIGHT = 20;

    /** 第一排按钮的顶边。有时间范围那排时它是时间范围，没有时就是排序方式。 */
    private static final int PERIOD_TABS_TOP = HEADER_HEIGHT + 6;

    /** 按钮的高度。 */
    private static final int TAB_HEIGHT = 16;

    /** 两排按钮之间的间距。 */
    private static final int TAB_ROW_GAP = 4;

    /** 按钮之间的间距。 */
    private static final int TAB_GAP = 4;

    /** 最后一排按钮到榜单顶边的间距。 */
    private static final int LIST_GAP = 8;

    /** 底栏高度（放返回按钮和名次）。 */
    private static final int FOOTER_HEIGHT = 34;

    /** 榜单最大宽度，再宽一行字就拉得太散。 */
    private static final int LIST_MAX_WIDTH = 480;

    /** 每一行的高度：两行字 + 上下留白。 */
    private static final int ROW_HEIGHT = 34;

    /** 名次六边形的半径。 */
    private static final float BADGE_RADIUS = 9.0F;

    /** 六边形中心到行左边缘的距离。 */
    private static final int BADGE_INSET = 16;

    /** 头像左边缘相对行左边缘的偏移，以及头像的边长。要躲开前三名徽章绕圈的星点，别贴太近。 */
    private static final int FACE_INSET = 37;
    private static final int FACE_SIZE = 12;

    /** 名字那一栏的左边缘相对行左边缘的偏移。 */
    private static final int NAME_INSET = FACE_INSET + FACE_SIZE + 5;

    /** 第二行小字的缩放。 */
    private static final float SMALL_SCALE = 0.75F;

    /** 前三名徽章外面绕圈的星点数量。 */
    private static final int TOP_STARS = 4;

    /** 滚轮一格滚多少像素。 */
    private static final double SCROLL_STEP = 24.0D;

    /** 按钮尺寸，和专精界面的重置视角按钮一致。 */
    private static final int BUTTON_WIDTH = 74;
    private static final int BUTTON_HEIGHT = 17;

    /** 客户端自己的请求冷却（毫秒）。服务端还有一道，这里只是别让玩家白点。 */
    private static final long CLIENT_COOLDOWN_MS = 2000L;

    /** 等回包最多等多久（毫秒），超过就当服务端没理，把「加载中」撤掉。 */
    private static final long REQUEST_TIMEOUT_MS = 8000L;

    /** 提示文字显示多久（毫秒）。 */
    private static final long NOTICE_MS = 3000L;

    /** 前几名算「前十」，有徽章。 */
    private static final int TOP_BADGE_RANK = 10;

    /** 上一层界面（通常是自己的专精界面），ESC 回到它。 */
    @Nullable
    private final Screen parent;

    /** 当前的时间范围。 */
    private LeaderboardPeriod period = LeaderboardPeriod.ALL;

    /**
     * 服务端有没有日 / 周 / 月榜。只有专用服务端才有，由回包告知；
     * 没收到回包之前当没有，单人世界那排按钮从头到尾不会出现。
     */
    private boolean periodsEnabled;

    /** 当前的排序方式。 */
    private LeaderboardSort sort = LeaderboardSort.MAXED;

    /** 服务端发来的前若干名。 */
    private List<LeaderboardRow> rows = List.of();

    /** 自己的名次，0 表示没上榜。 */
    private int selfRank;

    /** 自己那一行，没上榜时为 null。 */
    @Nullable
    private LeaderboardRow selfRow;

    /** 榜上总人数。 */
    private int total;

    /** 界面状态。 */
    private State state = State.LOADING;

    /** 是否已经发过第一次请求，init 会在窗口大小变化时反复调用，别每次都发。 */
    private boolean requestedOnce;

    /** 上一次要榜单的时刻。 */
    private long lastListRequestMs;

    /** 上一次要某人数据的时刻。 */
    private long lastPlayerRequestMs;

    /** 正在等回包的那次请求发出的时刻，0 表示没在等。 */
    private long waitingSinceMs;

    /** 正在等他数据回来的那个玩家，null 表示没在等。 */
    @Nullable
    private UUID loadingPlayer;

    /** 底部提示文字及其过期时刻。 */
    @Nullable
    private Component notice;
    private long noticeUntilMs;

    /** 榜单滚动量（像素，向下为正）。 */
    private double scroll;

    /** 本帧鼠标位置，画行的时候做悬停高亮。 */
    private int mouseXNow;
    private int mouseYNow;

    /**
     * 构造界面。
     *
     * @param parent 上一层界面，ESC 回到它；传 null 则直接关闭
     */
    public LeaderboardScreen(@Nullable Screen parent) {
        super(Component.translatable("gui.albionmastery.leaderboard.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!this.requestedOnce) {
            this.requestedOnce = true;
            // 第一次不受客户端冷却限制，打开就该立刻去要
            this.lastListRequestMs = 0L;
            requestList(this.period, this.sort);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // 返回上一层而不是直接关掉：从专精界面进来的，退出去还应该在专精界面
        if (this.parent != null) {
            Minecraft.getInstance().setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    // ======================================================================
    // 网络
    // ======================================================================

    /**
     * 向服务端要一份榜单。
     *
     * @param wantedPeriod 时间范围
     * @param wantedSort   排序方式
     */
    private void requestList(LeaderboardPeriod wantedPeriod, LeaderboardSort wantedSort) {
        long now = Util.getMillis();
        if (now - this.lastListRequestMs < CLIENT_COOLDOWN_MS) {
            showNotice(Component.translatable("gui.albionmastery.leaderboard.too_fast"));
            return;
        }
        this.lastListRequestMs = now;
        this.waitingSinceMs = now;
        if (this.rows.isEmpty()) {
            this.state = State.LOADING;
        }
        NetworkHandler.sendToServer(new C2SRequestLeaderboardPacket(wantedPeriod, wantedSort));
    }

    /**
     * 向服务端要某名玩家的整份数据。
     *
     * @param row 点中的那一行
     */
    private void requestPlayer(LeaderboardRow row) {
        long now = Util.getMillis();
        if (this.loadingPlayer != null || now - this.lastPlayerRequestMs < CLIENT_COOLDOWN_MS) {
            showNotice(Component.translatable("gui.albionmastery.leaderboard.too_fast"));
            return;
        }
        this.lastPlayerRequestMs = now;
        this.loadingPlayer = row.uuid();
        NetworkHandler.sendToServer(new C2SRequestPlayerMasteryPacket(row.uuid()));
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /**
     * 服务端回了榜单。
     *
     * @param status     回包状态
     * @param hasPeriods 服务端有没有日 / 周 / 月榜
     * @param newPeriod  服务端按哪个时间范围算的
     * @param newSort   服务端按哪种方式排的
     * @param newRows   前若干名
     * @param rank      自己的名次
     * @param self      自己那一行
     * @param newTotal  榜上总人数
     */
    public void onRowsReceived(S2CLeaderboardPacket.Status status, boolean hasPeriods,
                               LeaderboardPeriod newPeriod, LeaderboardSort newSort,
                               List<LeaderboardRow> newRows, int rank,
                               @Nullable LeaderboardRow self, int newTotal) {
        this.waitingSinceMs = 0L;
        switch (status) {
            case OK -> {
                if (newSort != this.sort || newPeriod != this.period) {
                    this.scroll = 0.0D;
                }
                this.periodsEnabled = hasPeriods;
                this.period = newPeriod;
                this.sort = newSort;
                this.rows = new ArrayList<>(newRows);
                this.selfRank = rank;
                this.selfRow = self;
                this.total = newTotal;
                this.state = this.rows.isEmpty() ? State.EMPTY : State.READY;
            }
            case DISABLED -> {
                this.rows = List.of();
                this.state = State.DISABLED;
            }
            case TOO_FAST -> showNotice(Component.translatable("gui.albionmastery.leaderboard.too_fast"));
        }
    }

    /**
     * 某名玩家的数据已经回来、他的罗盘马上要打开了。
     *
     * @param uuid 玩家 UUID
     */
    public void onPlayerLoaded(UUID uuid) {
        if (uuid.equals(this.loadingPlayer)) {
            this.loadingPlayer = null;
        }
    }

    /**
     * 服务端没有这名玩家的数据。
     *
     * @param uuid 玩家 UUID
     */
    public void onPlayerMissing(UUID uuid) {
        if (uuid.equals(this.loadingPlayer)) {
            this.loadingPlayer = null;
        }
        showNotice(Component.translatable("gui.albionmastery.leaderboard.player_missing"));
    }

    private void showNotice(Component text) {
        this.notice = text;
        this.noticeUntilMs = Util.getMillis() + NOTICE_MS;
    }

    // ======================================================================
    // 布局
    // ======================================================================

    /**
     * @return 排序按钮那一排的顶边：有时间范围那排就排在它下面，没有就顶上去
     */
    private int sortTabsTop() {
        return this.periodsEnabled ? PERIOD_TABS_TOP + TAB_HEIGHT + TAB_ROW_GAP : PERIOD_TABS_TOP;
    }

    /**
     * @return 榜单顶边
     */
    private int listTop() {
        return sortTabsTop() + TAB_HEIGHT + LIST_GAP;
    }

    private int listWidth() {
        return Math.min(LIST_MAX_WIDTH, this.width - 32);
    }

    private int listLeft() {
        return (this.width - listWidth()) / 2;
    }

    private int listRight() {
        return listLeft() + listWidth();
    }

    private int listBottom() {
        return this.height - FOOTER_HEIGHT - 6;
    }

    /**
     * 自己不在前几名里时，底下钉一行自己的，榜单可视区要相应让出来。
     *
     * @return 是否要钉一行
     */
    private boolean hasStickySelf() {
        return this.selfRow != null && this.selfRank > this.rows.size();
    }

    /**
     * @return 榜单可滚动区域的底边
     */
    private int scrollBottom() {
        return hasStickySelf() ? listBottom() - ROW_HEIGHT - 8 : listBottom();
    }

    private int contentHeight() {
        return this.rows.size() * ROW_HEIGHT;
    }

    private int viewportHeight() {
        return Math.max(1, scrollBottom() - listTop());
    }

    private double maxScroll() {
        return Math.max(0.0D, contentHeight() - viewportHeight());
    }

    /**
     * @return 排序方式列表，按枚举顺序
     */
    private List<LeaderboardSort> visibleSorts() {
        return List.of(LeaderboardSort.values());
    }

    /**
     * 一排按钮的横向范围。
     *
     * @param labels 每个按钮的文字
     * @return 数组，每项是 {左, 右}，顺序同输入
     */
    private int[][] tabBounds(List<Component> labels) {
        int[][] bounds = new int[labels.size()][2];
        int cursor = listLeft();
        for (int i = 0; i < labels.size(); i++) {
            int width = this.font.width(labels.get(i)) + 14;
            bounds[i][0] = cursor;
            bounds[i][1] = cursor + width;
            cursor += width + TAB_GAP;
        }
        return bounds;
    }

    private List<Component> periodLabels() {
        List<Component> labels = new ArrayList<>();
        for (LeaderboardPeriod value : LeaderboardPeriod.values()) {
            labels.add(Component.translatable(value.nameKey()));
        }
        return labels;
    }

    private List<Component> sortLabels() {
        List<Component> labels = new ArrayList<>();
        for (LeaderboardSort value : visibleSorts()) {
            labels.add(Component.translatable(value.nameKey()));
        }
        return labels;
    }

    private int refreshButtonX() {
        return this.width - BUTTON_WIDTH - 12;
    }

    private int backButtonX() {
        return 12;
    }

    private int footerButtonY() {
        return this.height - BUTTON_HEIGHT - 12;
    }

    private boolean isOn(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ======================================================================
    // 渲染
    // ======================================================================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.mouseXNow = mouseX;
        this.mouseYNow = mouseY;
        long now = Util.getMillis();
        tickTimeouts(now);

        guiGraphics.fill(0, 0, this.width, this.height, MasteryPalette.BG);
        drawHeader(guiGraphics, now);
        drawTabs(guiGraphics, mouseX, mouseY);
        drawList(guiGraphics, now);
        drawFooter(guiGraphics, mouseX, mouseY, now);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * 等太久没回包就把「加载中」撤掉，别让玩家对着转圈。
     *
     * @param now 当前时刻
     */
    private void tickTimeouts(long now) {
        if (this.waitingSinceMs != 0L && now - this.waitingSinceMs > REQUEST_TIMEOUT_MS) {
            this.waitingSinceMs = 0L;
            if (this.state == State.LOADING) {
                this.state = State.TIMEOUT;
            } else {
                showNotice(Component.translatable("gui.albionmastery.leaderboard.timeout"));
            }
        }
        if (this.loadingPlayer != null && now - this.lastPlayerRequestMs > REQUEST_TIMEOUT_MS) {
            this.loadingPlayer = null;
            showNotice(Component.translatable("gui.albionmastery.leaderboard.timeout"));
        }
    }

    /**
     * 顶栏：标题、总人数、自己的名次。
     *
     * @param guiGraphics 绘图上下文
     * @param now         当前时刻
     */
    private void drawHeader(GuiGraphics guiGraphics, long now) {
        guiGraphics.fill(0, 0, this.width, HEADER_HEIGHT, MasteryPalette.PANEL);
        guiGraphics.fill(0, HEADER_HEIGHT - 1, this.width, HEADER_HEIGHT, MasteryPalette.EDGE);

        int textY = (HEADER_HEIGHT - this.font.lineHeight) / 2 + 1;
        int cursor = 10;
        guiGraphics.drawString(this.font, this.title, cursor, textY, MasteryPalette.GOLD_BRIGHT, false);
        cursor += this.font.width(this.title) + 10;

        if (this.state == State.READY || this.state == State.EMPTY) {
            cursor = drawHeaderChip(guiGraphics,
                    Component.translatable("gui.albionmastery.leaderboard.header_total", this.total),
                    cursor, textY, MasteryPalette.TEXT);
            Component mine = this.selfRank > 0
                    ? Component.translatable("gui.albionmastery.leaderboard.header_rank", this.selfRank)
                    : Component.translatable("gui.albionmastery.leaderboard.header_unranked");
            drawHeaderChip(guiGraphics, mine, cursor, textY,
                    this.selfRank > 0 ? MasteryPalette.GOLD : MasteryPalette.TEXT_DIM);
        }

        // 右端：正在等回包时给一个会动的提示
        if (this.waitingSinceMs != 0L) {
            int dots = (int) ((now / 350L) % 4L);
            String text = Component.translatable("gui.albionmastery.leaderboard.loading").getString()
                    + ".".repeat(dots);
            guiGraphics.drawString(this.font, text, this.width - 10 - this.font.width(text), textY,
                    MasteryPalette.TEXT_DIM, false);
        }
    }

    private int drawHeaderChip(GuiGraphics guiGraphics, Component text, int x, int y, int color) {
        guiGraphics.drawString(this.font, "\u00B7", x, y, MasteryPalette.GOLD_DARK, false);
        int textX = x + this.font.width("\u00B7") + 6;
        guiGraphics.drawString(this.font, text, textX, y, color, false);
        return textX + this.font.width(text) + 10;
    }

    /**
     * 两排按钮：上面是时间范围，下面是排序方式，时间范围那排的右端放操作提示。
     *
     * @param guiGraphics 绘图上下文
     * @param mouseX      鼠标 X
     * @param mouseY      鼠标 Y
     */
    private void drawTabs(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 第一排按钮右端放操作提示；哪一排是第一排取决于有没有时间范围
        int firstRowRight;
        if (this.periodsEnabled) {
            List<Component> periodLabels = periodLabels();
            int[][] periodBounds = tabBounds(periodLabels);
            LeaderboardPeriod[] periods = LeaderboardPeriod.values();
            for (int i = 0; i < periods.length; i++) {
                drawTab(guiGraphics, periodLabels.get(i), periodBounds[i][0], periodBounds[i][1],
                        PERIOD_TABS_TOP, periods[i] == this.period, mouseX, mouseY);
            }
            firstRowRight = periodBounds[periodBounds.length - 1][1];
        } else {
            firstRowRight = 0;
        }

        List<LeaderboardSort> sorts = visibleSorts();
        List<Component> sortLabels = sortLabels();
        int[][] sortBounds = tabBounds(sortLabels);
        int sortTop = sortTabsTop();
        for (int i = 0; i < sorts.size(); i++) {
            drawTab(guiGraphics, sortLabels.get(i), sortBounds[i][0], sortBounds[i][1],
                    sortTop, sorts.get(i) == this.sort, mouseX, mouseY);
        }
        if (!this.periodsEnabled) {
            firstRowRight = sortBounds[sortBounds.length - 1][1];
        }

        // 字号 0.75；窗口窄到放不下就不画，别压在按钮上
        Component hint = Component.translatable("gui.albionmastery.leaderboard.hint");
        int hintWidth = (int) (this.font.width(hint) * SMALL_SCALE);
        int hintX = listRight() - hintWidth;
        if (hintX > firstRowRight + 10) {
            drawScaled(guiGraphics, hint, hintX, PERIOD_TABS_TOP + (TAB_HEIGHT - 6) / 2 + 1, SMALL_SCALE,
                    MasteryPalette.TEXT_DIM);
        }
    }

    /**
     * 画一个标签按钮。
     *
     * @param guiGraphics 绘图上下文
     * @param label       文字
     * @param left        左边缘
     * @param right       右边缘
     * @param top         顶边
     * @param active      是不是当前选中的
     * @param mouseX      鼠标 X
     * @param mouseY      鼠标 Y
     */
    private void drawTab(GuiGraphics guiGraphics, Component label, int left, int right, int top,
                         boolean active, int mouseX, int mouseY) {
        boolean hover = isOn(mouseX, mouseY, left, top, right - left, TAB_HEIGHT);
        int fill = active ? 0x40E8C25A : (hover ? 0xF01E1810 : 0xC0130F09);
        int border = active ? MasteryPalette.GOLD_BRIGHT : (hover ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK);
        drawFramedBox(guiGraphics, left, top, right - left, TAB_HEIGHT, fill, border);
        guiGraphics.drawCenteredString(this.font, label, (left + right) / 2,
                top + (TAB_HEIGHT - 8) / 2 + 1,
                active ? MasteryPalette.GOLD_BRIGHT : (hover ? MasteryPalette.TEXT : MasteryPalette.TEXT_DIM));
    }

    /**
     * 榜单本体。
     *
     * @param guiGraphics 绘图上下文
     * @param now         当前时刻
     */
    private void drawList(GuiGraphics guiGraphics, long now) {
        int left = listLeft();
        int right = listRight();
        int top = listTop();
        int bottom = listBottom();

        guiGraphics.fill(left - 4, top - 4, right + 4, bottom + 4, MasteryPalette.PANEL);
        guiGraphics.fill(left - 4, top - 4, right + 4, top - 3, MasteryPalette.EDGE);
        guiGraphics.fill(left - 4, bottom + 3, right + 4, bottom + 4, MasteryPalette.EDGE);
        guiGraphics.fill(left - 4, top - 4, left - 3, bottom + 4, MasteryPalette.EDGE);
        guiGraphics.fill(right + 3, top - 4, right + 4, bottom + 4, MasteryPalette.EDGE);

        if (this.state != State.READY) {
            drawCenteredState(guiGraphics, top, bottom, now);
            return;
        }

        this.scroll = Mth.clamp(this.scroll, 0.0D, maxScroll());
        int scrollBottom = scrollBottom();

        guiGraphics.enableScissor(left - 4, top, right + 4, scrollBottom);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, (float) -this.scroll, 0.0F);
        int y = top;
        for (int i = 0; i < this.rows.size(); i++) {
            int rank = i + 1;
            int screenTop = y - (int) Math.round(this.scroll);
            // 屏幕外的行不画，一百行全画一遍每帧要几百次文字绘制
            if (screenTop + ROW_HEIGHT >= top && screenTop <= scrollBottom) {
                drawRow(guiGraphics, this.rows.get(i), rank, left, y, right - left, now,
                        this.mouseYNow + this.scroll);
            }
            y += ROW_HEIGHT;
        }
        guiGraphics.pose().popPose();
        guiGraphics.disableScissor();

        if (maxScroll() > 0.0D) {
            drawScrollbar(guiGraphics, right + 1, top, scrollBottom);
        }

        if (hasStickySelf() && this.selfRow != null) {
            // 分隔：一条线加三个点，表示中间还隔着很多人
            int sepY = scrollBottom + 3;
            guiGraphics.fill(left, sepY, right, sepY + 1, MasteryPalette.EDGE);
            guiGraphics.drawCenteredString(this.font, "\u00B7 \u00B7 \u00B7", (left + right) / 2,
                    sepY - 3, MasteryPalette.TEXT_DIM);
            drawRow(guiGraphics, this.selfRow, this.selfRank, left, bottom - ROW_HEIGHT,
                    right - left, now, this.mouseYNow);
        }
    }

    /**
     * 榜单没内容时在中间画一句状态。
     *
     * @param guiGraphics 绘图上下文
     * @param top         榜单顶边
     * @param bottom      榜单底边
     * @param now         当前时刻
     */
    private void drawCenteredState(GuiGraphics guiGraphics, int top, int bottom, long now) {
        Component text;
        int color;
        switch (this.state) {
            case LOADING -> {
                int dots = (int) ((now / 350L) % 4L);
                text = Component.literal(Component.translatable("gui.albionmastery.leaderboard.loading")
                        .getString() + ".".repeat(dots));
                color = MasteryPalette.TEXT_DIM;
            }
            case DISABLED -> {
                text = Component.translatable("gui.albionmastery.leaderboard.disabled");
                color = MasteryPalette.WARN;
            }
            case EMPTY -> {
                text = Component.translatable(this.period.isDelta()
                        ? "gui.albionmastery.leaderboard.empty_period"
                        : "gui.albionmastery.leaderboard.empty");
                color = MasteryPalette.TEXT_DIM;
            }
            case TIMEOUT -> {
                text = Component.translatable("gui.albionmastery.leaderboard.timeout");
                color = MasteryPalette.WARN;
            }
            default -> {
                return;
            }
        }
        guiGraphics.drawCenteredString(this.font, text, this.width / 2, (top + bottom) / 2 - 4, color);
    }

    /**
     * 画一行。
     *
     * <p>前三名和前十名各有一套装饰：名次六边形的填色、外圈的呼吸光环和绕圈的星点，
     * 名字旁边再挂一块「全服第一」这样的铭牌。自己那一行不管排第几都压一层金底，
     * 一眼能在一百行里找到自己。
     *
     * @param guiGraphics 绘图上下文
     * @param row         数据
     * @param rank        名次，从 1 起
     * @param x           行左边缘
     * @param y           行顶边（内容坐标）
     * @param width       行宽
     * @param now         当前时刻
     * @param mouseY      鼠标 Y（已换算到和 y 同一坐标系）
     */
    private void drawRow(GuiGraphics guiGraphics, LeaderboardRow row, int rank,
                         int x, int y, int width, long now, double mouseY) {
        boolean self = isSelf(row);
        boolean hover = this.mouseXNow >= x && this.mouseXNow < x + width
                && mouseY >= y && mouseY < y + ROW_HEIGHT;
        boolean top3 = rank <= 3;
        boolean top10 = rank <= TOP_BADGE_RANK;

        // 底色：前三名一层淡金，自己一层更明显的金 + 左侧金条，悬停再叠一层白
        if (top3) {
            guiGraphics.fill(x, y, x + width, y + ROW_HEIGHT, 0x18FFE9A8);
        }
        if (self) {
            guiGraphics.fill(x, y, x + width, y + ROW_HEIGHT, 0x30E8C25A);
            guiGraphics.fill(x - 3, y, x - 1, y + ROW_HEIGHT, MasteryPalette.GOLD);
        }
        if (hover) {
            guiGraphics.fill(x, y, x + width, y + ROW_HEIGHT, 0x1AFFFFFF);
        }
        guiGraphics.fill(x, y + ROW_HEIGHT - 1, x + width, y + ROW_HEIGHT, 0x406B5223);

        drawRankBadge(guiGraphics, rank, x + BADGE_INSET, y + ROW_HEIGHT / 2.0F, now);
        drawFace(guiGraphics, row, x + FACE_INSET, y + (ROW_HEIGHT - FACE_SIZE) / 2);

        // 第一行：名字 + 徽章 + 在线状态
        int nameX = x + NAME_INSET;
        int line1 = y + 5;
        int nameColor = self ? MasteryPalette.GOLD_BRIGHT
                : (top3 ? MasteryPalette.GOLD_BRIGHT : (top10 ? MasteryPalette.GOLD : MasteryPalette.TEXT));
        int headlineWidth = this.font.width(String.valueOf(this.sort.headline(row))) + 4;
        int nameBudget = width - NAME_INSET - headlineWidth - 8;
        String name = trimToWidth(row.name(), Math.max(30, nameBudget / 2));
        guiGraphics.drawString(this.font, name, nameX, line1, nameColor, false);
        int cursor = nameX + this.font.width(name) + 5;

        if (top10) {
            cursor = drawPlaque(guiGraphics, rankBadgeText(rank), cursor, line1 - 1,
                    top3 ? rankColor(rank) : MasteryPalette.GOLD, top3) + 4;
        }
        if (self) {
            cursor = drawPlaque(guiGraphics, Component.translatable("gui.albionmastery.leaderboard.you"),
                    cursor, line1 - 1, MasteryPalette.GOLD, false) + 4;
        }

        // 在线 / 多久前 / 读取中
        Component status;
        int statusColor;
        if (row.uuid().equals(this.loadingPlayer)) {
            status = Component.translatable("gui.albionmastery.leaderboard.opening");
            statusColor = MasteryPalette.GOLD;
        } else if (row.online()) {
            status = Component.translatable("gui.albionmastery.leaderboard.online");
            statusColor = 0xFF7BD37B;
        } else {
            status = timeAgo(row.updatedAt());
            statusColor = MasteryPalette.TEXT_DIM;
        }
        // 小字缩到 0.75 之后比名字矮，往上提 1 像素让它和名字、铭牌的视觉中线对齐
        if (cursor + this.font.width(status) * SMALL_SCALE < x + width - headlineWidth - 6) {
            drawScaled(guiGraphics, status, cursor, line1 + 1, SMALL_SCALE, statusColor);
        }

        // 右侧：这种排序方式看的那个数，大字；底下一行小字说明是什么
        String headline = String.valueOf(this.sort.headline(row));
        int headlineX = x + width - 6 - this.font.width(headline);
        guiGraphics.drawString(this.font, headline, headlineX, line1,
                top3 ? rankColor(rank) : MasteryPalette.GOLD_BRIGHT, false);
        Component headlineLabel = Component.translatable(this.sort.nameKey());
        drawScaled(guiGraphics, headlineLabel,
                (int) (x + width - 6 - this.font.width(headlineLabel) * SMALL_SCALE),
                line1 + 13, SMALL_SCALE, MasteryPalette.TEXT_DIM);

        // 第二行：其余概况，一行小字，超出就截
        Component stats = Component.translatable("gui.albionmastery.leaderboard.row_stats",
                row.maxed(), row.levelSum(), row.unlocked(), row.specCount());
        String line2 = stats.getString();
        int statsBudget = (int) ((width - NAME_INSET - headlineWidth - 40) / SMALL_SCALE);
        drawScaled(guiGraphics, Component.literal(trimToWidth(line2, statsBudget)),
                nameX, line1 + 13, SMALL_SCALE, MasteryPalette.TEXT_DIM);
    }

    /**
     * 名次六边形。
     *
     * <p>前三名本色粗边 + 本色数字，外圈两层错开呼吸的光环加绕圈星点，第一名比二三名多两颗星、转得更快；
     * 四到十名金边金字加一圈呼吸边；其余暗边暗字。
     *
     * @param guiGraphics 绘图上下文
     * @param rank        名次
     * @param cx          中心 X
     * @param cy          中心 Y
     * @param now         当前时刻
     */
    private void drawRankBadge(GuiGraphics guiGraphics, int rank, float cx, float cy, long now) {
        boolean top3 = rank <= 3;
        boolean top10 = rank <= TOP_BADGE_RANK;
        // 底一律是暗的，名次高低靠描边和数字的亮度分：前三名用各自那档金，前十用主金，其余暗金。
        // 早先前三名是亮底黑字，黑数字在一屏金色里像个洞，改掉了
        int fill = top10 ? MasteryPalette.NODE_FILL : MasteryPalette.NODE_FILL_LOCKED;
        int outline = top3 ? rankColor(rank) : (top10 ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK);

        guiGraphics.flush();
        Matrix4f matrix = guiGraphics.pose().last().pose();
        BufferBuilder buffer = RenderUtil.begin();
        float[][] hex = RenderUtil.hexagon(cx, cy, BADGE_RADIUS);
        RenderUtil.polygon(buffer, matrix, RenderUtil.hexagon(cx, cy, BADGE_RADIUS + 2.0F),
                MasteryPalette.NODE_BACKPLATE);
        RenderUtil.polygon(buffer, matrix, hex, fill);
        if (top3) {
            // 前三名底上再压一层本色的淡光，让六边形本身也亮一点，但不至于把数字淹掉
            RenderUtil.polygon(buffer, matrix, hex, RenderUtil.withAlpha(outline, 0.18F));
        }
        RenderUtil.polygonOutline(buffer, matrix, hex, top3 ? 1.8F : 1.2F, outline);

        if (top10) {
            // 呼吸相位按名次错开，一列徽章不会齐刷刷一起闪
            float breathe = 0.5F + 0.5F * (float) Math.sin(now / 600.0D + rank * 0.9D);
            RenderUtil.polygonOutline(buffer, matrix,
                    RenderUtil.hexagon(cx, cy, BADGE_RADIUS + 3.0F + breathe * 1.5F), 1.0F,
                    RenderUtil.withAlpha(outline, 0.25F + 0.4F * breathe));
            if (top3) {
                RenderUtil.polygonOutline(buffer, matrix,
                        RenderUtil.hexagon(cx, cy, BADGE_RADIUS + 6.5F + (1.0F - breathe) * 2.0F), 1.0F,
                        RenderUtil.withAlpha(rankColor(rank), 0.12F + 0.25F * (1.0F - breathe)));
                int stars = rank == 1 ? TOP_STARS + 2 : TOP_STARS;
                double spin = now / (rank == 1 ? 1400.0D : 2000.0D) + rank;
                float star = rank == 1 ? 1.7F : 1.4F;
                for (int i = 0; i < stars; i++) {
                    double angle = spin + i * (Math.PI * 2.0D / stars);
                    float px = cx + (float) (Math.cos(angle) * (BADGE_RADIUS + 5.5F));
                    float py = cy + (float) (Math.sin(angle) * (BADGE_RADIUS + 5.5F));
                    RenderUtil.polygon(buffer, matrix, RenderUtil.hexagon(px, py, star),
                            RenderUtil.withAlpha(MasteryPalette.GOLD_WHITE, 0.45F + 0.45F * breathe));
                }
            }
        }
        RenderUtil.end();

        // 数字和描边同色；三位数缩一点免得撑出六边形
        String text = String.valueOf(rank);
        float scale = text.length() >= 3 ? 0.7F : 1.0F;
        int color = top3 ? rankColor(rank) : (top10 ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT_DIM);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(cx, cy - 4.0F * scale, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawCenteredString(this.font, text, 0, 0, color);
        guiGraphics.pose().popPose();
    }

    /**
     * 前三名各自的颜色：白金、亮金、金——整套界面只用金色系，三档亮度就是三个名次。
     *
     * @param rank 名次
     * @return 颜色
     */
    private static int rankColor(int rank) {
        return switch (rank) {
            case 1 -> MasteryPalette.GOLD_WHITE;
            case 2 -> MasteryPalette.GOLD_BRIGHT;
            default -> MasteryPalette.GOLD;
        };
    }

    /**
     * 徽章文字：「全服第一」「日榜第一」「周榜前十」这样，前缀跟着时间范围走。
     *
     * @param rank 名次
     * @return 文字
     */
    private Component rankBadgeText(int rank) {
        Component label = Component.translatable(this.period.labelKey());
        return switch (rank) {
            case 1 -> Component.translatable("gui.albionmastery.leaderboard.badge_1", label);
            case 2 -> Component.translatable("gui.albionmastery.leaderboard.badge_2", label);
            case 3 -> Component.translatable("gui.albionmastery.leaderboard.badge_3", label);
            default -> Component.translatable("gui.albionmastery.leaderboard.badge_top10", label);
        };
    }

    /**
     * 某名玩家在当前这张榜上配的徽章文字，前十以外没有。
     *
     * <p>公开出来是给「查看他人罗盘」的标题用的：点进全服第一的罗盘，标题上就该写着全服第一。
     *
     * @param uuid 玩家 UUID
     * @return 徽章文字；不在前十返回 null
     */
    @Nullable
    public Component badgeOf(UUID uuid) {
        int rank = rankOf(uuid);
        if (rank <= 0 || rank > TOP_BADGE_RANK) {
            return null;
        }
        return rankBadgeText(rank);
    }

    /**
     * 榜单里某名玩家的名次。
     *
     * @param uuid 玩家 UUID
     * @return 名次，从 1 起；不在收到的榜单里返回 0
     */
    public int rankOf(UUID uuid) {
        for (int i = 0; i < this.rows.size(); i++) {
            if (this.rows.get(i).uuid().equals(uuid)) {
                return i + 1;
            }
        }
        if (this.selfRow != null && this.selfRow.uuid().equals(uuid)) {
            return this.selfRank;
        }
        return 0;
    }

    /**
     * 名字旁边的小铭牌：暗底、本色描边、本色文字，和专精界面的等级铭牌一个路子。
     *
     * <p>不用亮底黑字：整套界面全是深底金字，突然冒一块白底黑字像贴了张标签，而且黑字在这里显得脏。
     *
     * @param guiGraphics 绘图上下文
     * @param text        文字
     * @param x           左边缘
     * @param y           顶边
     * @param color       主色
     * @param emphasized  true 底色再叠一层本色的淡光（前三名用），false 普通
     * @return 铭牌右边缘
     */
    private int drawPlaque(GuiGraphics guiGraphics, Component text, int x, int y, int color, boolean emphasized) {
        int textWidth = (int) Math.ceil(this.font.width(text) * SMALL_SCALE);
        int boxWidth = textWidth + 6;
        int boxHeight = 10;
        guiGraphics.fill(x - 1, y - 1, x + boxWidth + 1, y + boxHeight + 1, 0xFF0A0806);
        guiGraphics.fill(x, y, x + boxWidth, y + boxHeight, 0xFF1E1810);
        if (emphasized) {
            guiGraphics.fill(x, y, x + boxWidth, y + boxHeight, RenderUtil.withAlpha(color, 0.22F));
        }
        guiGraphics.fill(x, y, x + boxWidth, y + 1, color);
        guiGraphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, RenderUtil.withAlpha(color, 0.6F));
        drawScaled(guiGraphics, text, x + 3, y + 2, SMALL_SCALE, color);
        return x + boxWidth;
    }

    /**
     * 把毫秒时间戳写成「多久前」。
     *
     * @param updatedAt 记录时刻（服务端的墙钟毫秒时间戳）
     * @return 文本
     */
    private static Component timeAgo(long updatedAt) {
        // Util.getMillis 是游戏内的单调时钟，和服务端发来的墙钟时间不是一回事，这里得用墙钟
        long wall = System.currentTimeMillis();
        long seconds = Math.max(0L, (wall - updatedAt) / 1000L);
        if (updatedAt <= 0L) {
            return Component.translatable("gui.albionmastery.leaderboard.offline");
        }
        if (seconds < 90L) {
            return Component.translatable("gui.albionmastery.leaderboard.time_now");
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return Component.translatable("gui.albionmastery.leaderboard.time_minutes", minutes);
        }
        long hours = minutes / 60L;
        if (hours < 48L) {
            return Component.translatable("gui.albionmastery.leaderboard.time_hours", hours);
        }
        return Component.translatable("gui.albionmastery.leaderboard.time_days", hours / 24L);
    }

    /**
     * 画玩家头像（脸 + 帽子层）。
     *
     * @param guiGraphics 绘图上下文
     * @param row         数据
     * @param x           左边缘
     * @param y           顶边
     */
    private static void drawFace(GuiGraphics guiGraphics, LeaderboardRow row, int x, int y) {
        guiGraphics.fill(x - 1, y - 1, x + FACE_SIZE + 1, y + FACE_SIZE + 1, MasteryPalette.NODE_BACKPLATE);
        PlayerFaceRenderer.draw(guiGraphics, PlayerSkins.skinOf(row.uuid(), row.name()), x, y, FACE_SIZE);
    }

    private void drawScrollbar(GuiGraphics guiGraphics, int x, int top, int bottom) {
        int trackHeight = Math.max(8, bottom - top);
        int viewport = viewportHeight();
        int content = contentHeight();
        int thumbHeight = Mth.clamp((int) ((long) trackHeight * viewport / Math.max(1, content)), 12, trackHeight);
        int thumbTop = top + (int) Math.round((trackHeight - thumbHeight)
                * Mth.clamp(this.scroll / Math.max(1.0D, maxScroll()), 0.0D, 1.0D));
        guiGraphics.fill(x, top, x + 2, bottom, 0x30000000 | (MasteryPalette.EDGE & 0xFFFFFF));
        guiGraphics.fill(x, thumbTop, x + 2, thumbTop + thumbHeight, MasteryPalette.GOLD);
    }

    /**
     * 底栏：左边返回、中间自己的名次（或提示）、右边刷新。
     *
     * @param guiGraphics 绘图上下文
     * @param mouseX      鼠标 X
     * @param mouseY      鼠标 Y
     * @param now         当前时刻
     */
    private void drawFooter(GuiGraphics guiGraphics, int mouseX, int mouseY, long now) {
        drawButton(guiGraphics, backButtonX(), footerButtonY(),
                Component.translatable("gui.albionmastery.leaderboard.back"),
                isOn(mouseX, mouseY, backButtonX(), footerButtonY(), BUTTON_WIDTH, BUTTON_HEIGHT), false);

        boolean cooling = now - this.lastListRequestMs < CLIENT_COOLDOWN_MS;
        drawButton(guiGraphics, refreshButtonX(), footerButtonY(),
                Component.translatable("gui.albionmastery.leaderboard.refresh"),
                !cooling && isOn(mouseX, mouseY, refreshButtonX(), footerButtonY(), BUTTON_WIDTH, BUTTON_HEIGHT),
                cooling);

        int textY = footerButtonY() + (BUTTON_HEIGHT - 8) / 2 + 1;
        if (this.notice != null && now < this.noticeUntilMs) {
            guiGraphics.drawCenteredString(this.font, this.notice, this.width / 2, textY, MasteryPalette.WARN);
            return;
        }
        if (this.state == State.READY) {
            Component line;
            if (this.selfRank > 0) {
                line = Component.translatable("gui.albionmastery.leaderboard.footer_rank",
                        this.selfRank, this.total);
            } else {
                line = Component.translatable(this.period.isDelta()
                        ? "gui.albionmastery.leaderboard.footer_unranked_period"
                        : "gui.albionmastery.leaderboard.footer_unranked");
            }
            guiGraphics.drawCenteredString(this.font, line, this.width / 2, textY,
                    this.selfRank > 0 ? MasteryPalette.GOLD : MasteryPalette.TEXT_DIM);
        }
    }

    // ======================================================================
    // 绘图小工具
    // ======================================================================

    private void drawFramedBox(GuiGraphics guiGraphics, int x, int y, int w, int h, int fill, int border) {
        guiGraphics.fill(x, y, x + w, y + h, fill);
        guiGraphics.fill(x, y, x + w, y + 1, border);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, border);
        guiGraphics.fill(x, y, x + 1, y + h, border);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, border);
    }

    /**
     * 和专精界面同款的描边按钮。
     *
     * @param guiGraphics 绘图上下文
     * @param x           左边缘
     * @param y           顶边
     * @param label       文字
     * @param hover       是否悬停
     * @param disabled    是否置灰（冷却中）
     */
    private void drawButton(GuiGraphics guiGraphics, int x, int y, Component label, boolean hover, boolean disabled) {
        int fill = hover ? 0xF01E1810 : 0xC0130F09;
        int border = disabled ? MasteryPalette.GOLD_DARK : (hover ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK);
        drawFramedBox(guiGraphics, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, fill, border);
        guiGraphics.drawCenteredString(this.font, label, x + BUTTON_WIDTH / 2, y + (BUTTON_HEIGHT - 8) / 2 + 1,
                disabled ? MasteryPalette.TEXT_DIM : (hover ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT));
    }

    private void drawScaled(GuiGraphics guiGraphics, Component text, int x, int y, float scale, int color) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawString(this.font, text, 0, 0, color, false);
        guiGraphics.pose().popPose();
    }

    private String trimToWidth(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(4, maxWidth - this.font.width(".."))) + "..";
    }

    private boolean isSelf(LeaderboardRow row) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.getUUID().equals(row.uuid());
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
        if (isOn(mouseX, mouseY, backButtonX(), footerButtonY(), BUTTON_WIDTH, BUTTON_HEIGHT)) {
            onClose();
            return true;
        }
        if (isOn(mouseX, mouseY, refreshButtonX(), footerButtonY(), BUTTON_WIDTH, BUTTON_HEIGHT)) {
            requestList(this.period, this.sort);
            return true;
        }
        if (this.periodsEnabled) {
            LeaderboardPeriod[] periods = LeaderboardPeriod.values();
            int[][] periodBounds = tabBounds(periodLabels());
            for (int i = 0; i < periods.length; i++) {
                if (isOn(mouseX, mouseY, periodBounds[i][0], PERIOD_TABS_TOP,
                        periodBounds[i][1] - periodBounds[i][0], TAB_HEIGHT)) {
                    if (periods[i] != this.period) {
                        // 先把按钮切过去，回包再把数据换掉；等待期间旧数据照常显示
                        this.period = periods[i];
                        this.scroll = 0.0D;
                        requestList(this.period, this.sort);
                    }
                    return true;
                }
            }
        }
        List<LeaderboardSort> sorts = visibleSorts();
        int[][] sortBounds = tabBounds(sortLabels());
        int sortTop = sortTabsTop();
        for (int i = 0; i < sorts.size(); i++) {
            if (isOn(mouseX, mouseY, sortBounds[i][0], sortTop,
                    sortBounds[i][1] - sortBounds[i][0], TAB_HEIGHT)) {
                if (sorts.get(i) != this.sort) {
                    this.sort = sorts.get(i);
                    this.scroll = 0.0D;
                    requestList(this.period, this.sort);
                }
                return true;
            }
        }
        LeaderboardRow hit = rowAt(mouseX, mouseY);
        if (hit != null) {
            requestPlayer(hit);
            return true;
        }
        return false;
    }

    /**
     * 找出鼠标点中的那一行，钉在底下的自己那一行也算。
     *
     * @param mouseX 鼠标 X
     * @param mouseY 鼠标 Y
     * @return 点中的行；没点中返回 null
     */
    @Nullable
    private LeaderboardRow rowAt(double mouseX, double mouseY) {
        if (this.state != State.READY || mouseX < listLeft() || mouseX >= listRight()) {
            return null;
        }
        if (hasStickySelf() && this.selfRow != null
                && mouseY >= listBottom() - ROW_HEIGHT && mouseY < listBottom()) {
            return this.selfRow;
        }
        int top = listTop();
        if (mouseY < top || mouseY >= scrollBottom()) {
            return null;
        }
        int index = (int) ((mouseY - top + this.scroll) / ROW_HEIGHT);
        return index >= 0 && index < this.rows.size() ? this.rows.get(index) : null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.state == State.READY) {
            this.scroll = Mth.clamp(this.scroll - delta * SCROLL_STEP, 0.0D, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (KeyBindings.OPEN_MASTERY.matches(keyCode, scanCode)) {
            // 按开界面那个键是「我不看了」，整叠界面一起关
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 界面状态。
     */
    private enum State {
        /** 第一次请求还没回。 */
        LOADING,
        /** 有数据。 */
        READY,
        /** 服务端回了空榜。 */
        EMPTY,
        /** 服务端没开排行榜。 */
        DISABLED,
        /** 等回包超时。 */
        TIMEOUT
    }
}
