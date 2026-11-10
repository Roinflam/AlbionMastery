package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.client.KeyBindings;
import com.flameforge.albionmastery.client.rank.ClientRank;
import com.flameforge.albionmastery.client.rank.RankEmblem;
import com.flameforge.albionmastery.leaderboard.LeaderboardPeriod;
import com.flameforge.albionmastery.leaderboard.LeaderboardRow;
import com.flameforge.albionmastery.leaderboard.LeaderboardSort;
import com.flameforge.albionmastery.mastery.rank.MasteryRank;
import com.flameforge.albionmastery.network.C2SRequestLeaderboardPacket;
import com.flameforge.albionmastery.network.C2SRequestPlayerMasteryPacket;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.network.S2CLeaderboardPacket;
import com.flameforge.albionmastery.client.PlayerSkins;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
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
import java.util.Locale;
import java.util.UUID;

/**
 * 全局专精排行榜界面。
 *
 * <p>从专精界面左下角进来。上面是时间范围（总榜 / 日 / 周 / 月，只有专用服务端才有这排）
 * 和排序方式两排按钮，中间是榜单，底下是自己的名次，顶栏右端是搜索框。
 * 点任意一行就向服务端要那个人的整份专精数据，回来后用同一个 {@link MasteryScreen} 画他的罗盘（只读）。
 *
 * <p>搜索交给服务端做：在整张榜里按名字或 UUID 筛，名次照整张榜算，
 * 所以前一百名以外的人也能搜到，搜出来显示的还是他的真实名次。
 *
 * <p>总榜默认按段位排，每行右侧画段位徽章、段位名和到下一段的进度；日 / 周 / 月榜没有段位这个按钮。
 *
 * <p>数据全部来自服务端：这个界面自己不排，只负责把收到的东西画出来。
 * 唯一现算的是段位：行里带着等级之和，按服务端下发的每级段位经验换算，和对方自己看到的一致。
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

    /** 搜索框的宽度上下限，窗口越宽框越宽。 */
    private static final int SEARCH_MIN_WIDTH = 90;
    private static final int SEARCH_MAX_WIDTH = 150;

    /** 搜索框外框的高度，放在顶栏里上下居中。 */
    private static final int SEARCH_HEIGHT = 14;

    /** 右端清空按钮「×」占的宽度。 */
    private static final int SEARCH_CLEAR_WIDTH = 12;

    /** 停手多久自动搜（毫秒）。服务端对要榜单有限流，边打边搜会被挡。 */
    private static final long SEARCH_DEBOUNCE_MS = 600L;

    /** 段位视图里段位徽章的边长，和两行字的总高度差不多。 */
    private static final float RANK_EMBLEM_SIZE = 16.0F;

    /** 段位徽章到右侧段位名那一栏的间距。 */
    private static final int RANK_EMBLEM_GAP = 5;

    /** 段位名那一栏第二行的进度条至少多长，再短就看不出进度了，干脆不画。 */
    private static final int RANK_BAR_MIN_WIDTH = 16;

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
    private LeaderboardSort sort = LeaderboardSort.defaultFor(LeaderboardPeriod.ALL);

    /** 服务端发来的前若干名。 */
    private List<LeaderboardRow> rows = List.of();

    /** 自己的名次，0 表示没上榜。 */
    private int selfRank;

    /** 自己那一行，没上榜时为 null。 */
    @Nullable
    private LeaderboardRow selfRow;

    /** 榜上总人数。 */
    private int total;

    /** 每一行在整张榜上的名次；看整张榜时为 null，名次就是下标 + 1。 */
    @Nullable
    private int[] ranks;

    /** 当前显示的这批行是按什么搜出来的，空串表示整张榜。 */
    private String activeQuery = "";

    /** 搜到多少人，可能比收到的行多。 */
    private int matched;

    /** 顶栏右端的搜索框。 */
    @Nullable
    private EditBox searchBox;

    /** 最近一次发出去的搜索词，自动搜索靠它判断「这个已经搜过了」。 */
    private String sentQuery = "";

    /** 自动搜索的触发时刻，0 表示没有待搜的。 */
    private long searchDueMs;

    /** 延后补发榜单请求的时刻，0 表示没有。冷却中点的按钮、被服务端限流的请求都靠它补上。 */
    private long retryDueMs;

    /** 被服务端限流后的补发间隔，每被拒一次翻倍，收到正常回包就复原。 */
    private long retryDelayMs = CLIENT_COOLDOWN_MS;

    /**
     * 当前显示的这批行是按哪个时间范围、哪种排序算的。
     *
     * <p>和按钮上选中的 {@link #period} / {@link #sort} 分开存：点了按钮到回包之间，
     * 行还是旧的那批，右侧大数字、徽章文字都得跟着行走，不能跟着按钮走。
     */
    private LeaderboardPeriod shownPeriod = LeaderboardPeriod.ALL;
    private LeaderboardSort shownSort = LeaderboardSort.defaultFor(LeaderboardPeriod.ALL);

    /**
     * 段位视图右侧段位名那一栏的宽度，-1 表示要重算。
     *
     * <p>按收到的这批行里最宽的段位名定，整列对齐：徽章排成一竖列，扫一眼就能比出谁高谁低。
     * 换了一批行、窗口缩放时作废。
     */
    private int rankColumnWidth = -1;

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
        // 专精界面共用自己的界面缩放：先换档，宽高按换完的那一档重算（窗口改大小、改档也走这里）
        int[] scaled = ScreenScale.apply(this.minecraft);
        this.width = scaled[0];
        this.height = scaled[1];
        this.rankColumnWidth = -1;
        // 窗口缩放会重新 init，框里的字和焦点都得接着用
        String previous = this.searchBox == null ? "" : this.searchBox.getValue();
        boolean wasFocused = this.searchBox != null && this.searchBox.isFocused();
        this.searchBox = new EditBox(this.font, searchLeft() + 5, searchTop() + 3,
                searchWidth() - 5 - SEARCH_CLEAR_WIDTH, SEARCH_HEIGHT - 6,
                Component.translatable("gui.albionmastery.leaderboard.search"));
        this.searchBox.setHint(Component.translatable("gui.albionmastery.leaderboard.search")
                .withStyle(ChatFormatting.DARK_GRAY));
        this.searchBox.setBordered(false);
        this.searchBox.setTextColor(MasteryPalette.TEXT & 0xFFFFFF);
        this.searchBox.setMaxLength(C2SRequestLeaderboardPacket.MAX_QUERY);
        this.searchBox.setValue(previous);
        // 先填旧值再挂回调，不然缩放一下窗口就会被当成「改了搜索词」
        this.searchBox.setResponder(text -> this.searchDueMs = Util.getMillis() + SEARCH_DEBOUNCE_MS);
        addRenderableWidget(this.searchBox);
        if (wasFocused) {
            setFocused(this.searchBox);
        }
        if (!this.requestedOnce) {
            this.requestedOnce = true;
            // 第一次不受客户端冷却限制，打开就该立刻去要
            this.lastListRequestMs = 0L;
            requestList(this.period, this.sort);
        }
    }

    @Override
    public void removed() {
        super.removed();
        // 换回原版那一档；接着打开的要是别的专精界面，它的 init 会再换过来，中间不出帧
        ScreenScale.restore(Minecraft.getInstance());
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
            // 冷却中不丢请求：记下来，冷却一过自动补发。补发时取那一刻选中的范围、排序和搜索词，
            // 所以连点几个按钮只会发最后那一次
            this.retryDueMs = Math.max(this.retryDueMs, this.lastListRequestMs + CLIENT_COOLDOWN_MS);
            return;
        }
        this.retryDueMs = 0L;
        this.lastListRequestMs = now;
        this.waitingSinceMs = now;
        if (this.rows.isEmpty()) {
            this.state = State.LOADING;
        }
        // 切榜、刷新也按框里当前的词来：搜着「abc」切到周榜，看到的还是周榜里的「abc」
        this.sentQuery = currentQuery();
        this.searchDueMs = 0L;
        NetworkHandler.sendToServer(new C2SRequestLeaderboardPacket(wantedPeriod, wantedSort, this.sentQuery));
    }

    /**
     * @return 搜索框里现在的词，去掉首尾空白
     */
    private String currentQuery() {
        return this.searchBox == null ? this.sentQuery : this.searchBox.getValue().trim();
    }

    /**
     * 马上按框里的词搜。还在冷却就排到冷却结束自动发，不弹「点太快了」。
     */
    private void submitSearch() {
        this.searchDueMs = Util.getMillis();
        tickSearch(this.searchDueMs);
    }

    /**
     * 到点了就把待搜的词发出去。
     *
     * @param now 当前时刻
     */
    private void tickSearch(long now) {
        if (this.searchDueMs == 0L || now < this.searchDueMs) {
            return;
        }
        if (currentQuery().equals(this.sentQuery)) {
            this.searchDueMs = 0L;
            return;
        }
        if (now - this.lastListRequestMs < CLIENT_COOLDOWN_MS) {
            return;
        }
        this.scroll = 0.0D;
        requestList(this.period, this.sort);
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
     * @param newRows   前若干名（搜索时是匹配到的）
     * @param newRanks  每一行在整张榜上的名次，null 表示就是下标 + 1
     * @param rank      自己的名次
     * @param self      自己那一行
     * @param newTotal  榜上总人数
     * @param query     这批行是按什么搜出来的，空串表示整张榜
     * @param newMatched 搜到多少人
     */
    public void onRowsReceived(S2CLeaderboardPacket.Status status, boolean hasPeriods,
                               LeaderboardPeriod newPeriod, LeaderboardSort newSort,
                               List<LeaderboardRow> newRows, @Nullable int[] newRanks, int rank,
                               @Nullable LeaderboardRow self, int newTotal, String query, int newMatched) {
        this.waitingSinceMs = 0L;
        this.rankColumnWidth = -1;
        switch (status) {
            case OK -> {
                if (newSort != this.shownSort || newPeriod != this.shownPeriod || !query.equals(this.activeQuery)) {
                    this.scroll = 0.0D;
                }
                this.retryDelayMs = CLIENT_COOLDOWN_MS;
                this.shownPeriod = newPeriod;
                this.shownSort = newSort;
                this.ranks = newRanks != null && newRanks.length == newRows.size() ? newRanks : null;
                this.activeQuery = query;
                this.matched = newMatched;
                this.periodsEnabled = hasPeriods;
                // 按钮只在服务端没有日 / 周 / 月榜时被拉回总榜；别的时候以玩家最后点的为准，
                // 否则回包晚到会把玩家刚点的按钮又跳回去
                if (!hasPeriods) {
                    this.period = LeaderboardPeriod.ALL;
                }
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
            case TOO_FAST -> {
                // 被服务端限流（服务端的冷却可能配得比客户端长）：过一会儿自动补发，越拒越等得久。
                // 第一次打开就被拒时界面还停在「加载中」，不补发的话会一直停在那里
                this.sentQuery = this.activeQuery;
                this.retryDueMs = Util.getMillis() + this.retryDelayMs;
                this.retryDelayMs = Math.min(this.retryDelayMs * 2L, 16_000L);
            }
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
     * 看某人的请求被服务端限流了（服务端的冷却可能比客户端长）。
     *
     * @param uuid 玩家 UUID
     */
    public void onPlayerTooFast(UUID uuid) {
        if (uuid.equals(this.loadingPlayer)) {
            this.loadingPlayer = null;
        }
        showNotice(Component.translatable("gui.albionmastery.leaderboard.too_fast"));
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
        // 搜索时只看搜出来的人；自己要是匹配，本来就在列表里
        return this.activeQuery.isEmpty() && this.selfRow != null && this.selfRank > this.rows.size();
    }

    /**
     * 列表里第 i 行在整张榜上的名次。
     *
     * @param index 行下标
     * @return 名次，从 1 起
     */
    private int rankAt(int index) {
        return this.ranks != null && index < this.ranks.length ? this.ranks[index] : index + 1;
    }

    private int searchWidth() {
        return Mth.clamp(this.width / 4, SEARCH_MIN_WIDTH, SEARCH_MAX_WIDTH);
    }

    private int searchLeft() {
        return this.width - 10 - searchWidth();
    }

    private int searchTop() {
        return (HEADER_HEIGHT - SEARCH_HEIGHT) / 2;
    }

    /**
     * @return 清空按钮「×」的左边缘
     */
    private int searchClearLeft() {
        return searchLeft() + searchWidth() - SEARCH_CLEAR_WIDTH;
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
     * 排序按钮从左到右的顺序。
     *
     * <p>每个时间范围的默认排序放最前：总榜是「段位」，日 / 周 / 月榜是「经验」，各自是那张榜的主角。
     * 其余按枚举顺序；这个范围下没意义的（增量榜的段位）不出按钮。
     * 换的只是按钮的摆放，枚举本身的顺序不能动——序号要进网络包。
     *
     * @return 这个时间范围下的排序方式，顺序即按钮顺序
     */
    private List<LeaderboardSort> visibleSorts() {
        LeaderboardSort lead = LeaderboardSort.defaultFor(this.period);
        List<LeaderboardSort> ordered = new ArrayList<>();
        ordered.add(lead);
        for (LeaderboardSort value : LeaderboardSort.values()) {
            if (value != lead && value.supports(this.period)) {
                ordered.add(value);
            }
        }
        return ordered;
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

    /**
     * 换时间范围的时候排序跟着换：一直用着默认排序的就换成新范围的默认，手动选过的就留着他选的。
     *
     * <p>总榜默认看段位，日 / 周 / 月榜默认看经验——点进日榜想知道的是「今天谁刷得最多」，
     * 而不是「今天谁凑巧卡在升级线上」。新范围下用不了的排序（段位到了增量榜）也换成新范围的默认。
     *
     * @param current 当前排序
     * @param from    原来的时间范围
     * @param to      要切到的时间范围
     * @return 切过去之后该选中的排序
     */
    private static LeaderboardSort carrySort(LeaderboardSort current, LeaderboardPeriod from,
                                             LeaderboardPeriod to) {
        LeaderboardSort next = current == LeaderboardSort.defaultFor(from)
                ? LeaderboardSort.defaultFor(to) : current;
        return next.supports(to) ? next : LeaderboardSort.defaultFor(to);
    }

    /**
     * @return 当前显示的这批行要不要按段位画（段位排序且是总榜）
     */
    private boolean isRankView() {
        return this.shownSort == LeaderboardSort.RANK && this.shownSort.supports(this.shownPeriod);
    }

    /**
     * 一行的段位经验：等级之和按服务端下发的每级段位经验换算。
     *
     * @param row 一行数据
     * @return 段位经验
     */
    private static long rankXpOf(LeaderboardRow row) {
        return ClientRank.rankXpForLevelSum(row.levelSum());
    }

    /**
     * 段位名下面那行小字：「段位 18」。传奇段位的名字里已经带着序号（「传奇 3」），
     * 再写一遍「段位 33」反而让人困惑，只写「段位」两个字说明上面是什么。
     *
     * @param masteryRank 段位
     * @return 文字
     */
    private static Component masteryRankLabel(int masteryRank) {
        if (MasteryRank.isLegendary(masteryRank)) {
            return Component.translatable(LeaderboardSort.RANK.nameKey());
        }
        return Component.translatable("gui.albionmastery.leaderboard.mastery_rank_number", masteryRank);
    }

    /**
     * 段位名那一栏的宽度：取当前这批行里最宽的那个，窗口窄时封顶，放不下的段位名截短。
     *
     * @return 宽度（像素）
     */
    private int rankColumnWidth() {
        if (this.rankColumnWidth < 0) {
            int widest = 0;
            for (LeaderboardRow row : this.rows) {
                widest = Math.max(widest, rankCellWidth(row));
            }
            if (this.selfRow != null) {
                widest = Math.max(widest, rankCellWidth(this.selfRow));
            }
            this.rankColumnWidth = widest;
        }
        // 封顶四分之一行宽，别把名字那一栏挤没了
        return Math.min(this.rankColumnWidth, listWidth() / 4);
    }

    /**
     * 一行的段位名那一栏至少要多宽：段位名，或者「进度条 + 段位 N」，取宽的。
     *
     * @param row 一行数据
     * @return 宽度（像素）
     */
    private int rankCellWidth(LeaderboardRow row) {
        int masteryRank = MasteryRank.rankFor(rankXpOf(row));
        int nameWidth = this.font.width(MasteryRank.name(masteryRank));
        int labelWidth = (int) Math.ceil(this.font.width(masteryRankLabel(masteryRank)) * SMALL_SCALE);
        return Math.max(nameWidth, labelWidth + 3 + RANK_BAR_MIN_WIDTH);
    }

    /**
     * 段位视图右侧整块（徽章 + 段位名一栏）占的宽度，不含行右侧的 6 像素留白。
     * 徽章左边再多留几像素，名字后面的铭牌、状态不至于贴着徽章。
     *
     * @return 宽度（像素）
     */
    private int rankBlockWidth() {
        return rankColumnWidth() + RANK_EMBLEM_GAP + (int) Math.ceil(RANK_EMBLEM_SIZE) + 6;
    }

    /**
     * 段位视图第二行小字：「段位经验 812,400 · 距下一段 47,600」，到顶了只写段位经验。
     *
     * @param rankXp      段位经验
     * @param masteryRank 段位
     * @return 文字
     */
    private static Component rankStatsText(long rankXp, int masteryRank) {
        if (masteryRank >= MasteryRank.MAX_RANK) {
            return Component.translatable("gui.albionmastery.leaderboard.row_mastery_rank_max", grouped(rankXp));
        }
        long toNext = Math.max(0L, MasteryRank.xpForRank(masteryRank + 1) - rankXp);
        return Component.translatable("gui.albionmastery.leaderboard.row_mastery_rank",
                grouped(rankXp), grouped(toNext));
    }

    /**
     * 千分位写法，段位经验动辄七位数，不分组数不清。和 {@link #oneDecimal} 一样用 {@link Locale#ROOT}，
     * 分隔符固定是逗号。
     *
     * @param value 数值
     * @return 文本
     */
    private static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /**
     * 每行右侧那个大数字。
     *
     * <p>满级数、等级总和这些都是两三位的整数，原样显示；经验动辄七八位，
     * 写全了既占掉名字的位置也看不出量级，缩成 12.3K / 4.5M。
     * 段位视图右侧画的是徽章和段位名，不走这里（见 {@link #drawRankCell}）。
     *
     * @param row 一行数据
     * @return 大字文本
     */
    private String headlineText(LeaderboardRow row) {
        long value = this.shownSort.headline(row);
        return this.shownSort == LeaderboardSort.XP ? compactXp(value) : String.valueOf(value);
    }

    /**
     * 经验的紧凑写法：一万以内写全，往上按千、百万、十亿缩，保留一位小数。
     *
     * @param value 经验值
     * @return 最多六个字符的文本
     */
    private static String compactXp(long value) {
        if (value < 10_000L) {
            return String.valueOf(value);
        }
        if (value < 1_000_000L) {
            return oneDecimal(value / 1_000.0D) + "K";
        }
        if (value < 1_000_000_000L) {
            return oneDecimal(value / 1_000_000.0D) + "M";
        }
        return oneDecimal(value / 1_000_000_000.0D) + "B";
    }

    /**
     * 保留一位小数，整数就不写那个 .0。
     *
     * <p>用 {@link Locale#ROOT}：有些区域的小数点是逗号，"1,2M" 会被当成两个数。
     *
     * @param value 数值
     * @return 文本
     */
    private static String oneDecimal(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
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
        tickSearch(now);
        if (this.retryDueMs != 0L && now >= this.retryDueMs) {
            this.retryDueMs = 0L;
            requestList(this.period, this.sort);
        }

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

        // 概况小字不能压到搜索框上，放不下的就不画
        int limit = searchLeft() - 10;
        if (this.state == State.READY || this.state == State.EMPTY) {
            Component count = this.activeQuery.isEmpty()
                    ? Plural.component("gui.albionmastery.leaderboard.header_total", this.total, this.total)
                    : Component.translatable("gui.albionmastery.leaderboard.header_search",
                            this.activeQuery, this.matched);
            cursor = drawHeaderChip(guiGraphics, count, cursor, textY,
                    this.activeQuery.isEmpty() ? MasteryPalette.TEXT : MasteryPalette.GOLD_BRIGHT, limit);
            Component mine = this.selfRank > 0
                    ? Component.translatable("gui.albionmastery.leaderboard.header_rank", this.selfRank)
                    : Component.translatable("gui.albionmastery.leaderboard.header_unranked");
            cursor = drawHeaderChip(guiGraphics, mine, cursor, textY,
                    this.selfRank > 0 ? MasteryPalette.GOLD : MasteryPalette.TEXT_DIM, limit);
        }

        drawSearchFrame(guiGraphics, textY);

        // 正在等回包时在搜索框左边给一个会动的提示，地方不够就不画
        if (this.waitingSinceMs != 0L) {
            int dots = (int) ((now / 350L) % 4L);
            String text = Component.translatable("gui.albionmastery.leaderboard.loading").getString()
                    + ".".repeat(dots);
            int textX = limit - this.font.width(text.substring(0, text.length() - dots) + "...");
            if (textX >= cursor) {
                guiGraphics.drawString(this.font, text, textX, textY, MasteryPalette.TEXT_DIM, false);
            }
        }
    }

    private int drawHeaderChip(GuiGraphics guiGraphics, Component text, int x, int y, int color, int limit) {
        int textX = x + this.font.width("\u00B7") + 6;
        if (textX + this.font.width(text) > limit) {
            return x;
        }
        guiGraphics.drawString(this.font, "\u00B7", x, y, MasteryPalette.GOLD_DARK, false);
        guiGraphics.drawString(this.font, text, textX, y, color, false);
        return textX + this.font.width(text) + 10;
    }

    /**
     * 搜索框的外框和清空按钮：原版的灰边黑底和这套配色不搭，自己画，和特化页的搜索框一个样子。
     *
     * @param guiGraphics 绘图上下文
     * @param textY       顶栏文字的基线
     */
    private void drawSearchFrame(GuiGraphics guiGraphics, int textY) {
        boolean focused = this.searchBox != null && this.searchBox.isFocused();
        boolean searching = !this.activeQuery.isEmpty();
        int border = focused || searching ? MasteryPalette.GOLD : MasteryPalette.GOLD_DARK;
        drawFramedBox(guiGraphics, searchLeft(), searchTop(), searchWidth(), SEARCH_HEIGHT, 0xC0130F09, border);
        if (this.searchBox != null && !this.searchBox.getValue().isEmpty()) {
            boolean hover = isOn(this.mouseXNow, this.mouseYNow, searchClearLeft(), searchTop(),
                    SEARCH_CLEAR_WIDTH, SEARCH_HEIGHT);
            guiGraphics.drawCenteredString(this.font, "\u00D7", searchClearLeft() + SEARCH_CLEAR_WIDTH / 2 - 1,
                    textY, hover ? MasteryPalette.GOLD_BRIGHT : MasteryPalette.TEXT_DIM);
        }
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
                top + (TAB_HEIGHT - 8) / 2,
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
            int rank = rankAt(i);
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
                if (!this.activeQuery.isEmpty()) {
                    text = Component.translatable("gui.albionmastery.leaderboard.search_empty", this.activeQuery);
                } else {
                    text = Component.translatable(this.shownPeriod.isDelta()
                            ? "gui.albionmastery.leaderboard.empty_period"
                            : "gui.albionmastery.leaderboard.empty");
                }
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
     * 名字旁边再挂一块「全局第一」这样的铭牌。自己那一行不管排第几都压一层金底，
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
        // 两行字（名字一行、0.75 倍的小字一行）整体放在行高中间，和头像、名次六边形的中心对齐
        int line1 = y + 7;
        int nameColor = self ? MasteryPalette.GOLD_BRIGHT
                : (top3 ? MasteryPalette.GOLD_BRIGHT : (top10 ? MasteryPalette.GOLD : MasteryPalette.TEXT));
        // 右侧那一块占多宽：普通视图是一个大数字，段位视图是徽章加段位名一栏
        boolean rankView = isRankView();
        int headlineWidth = rankView ? rankBlockWidth() : this.font.width(headlineText(row)) + 4;
        int nameBudget = width - NAME_INSET - headlineWidth - 8;
        String name = trimToWidth(row.name(), Math.max(30, nameBudget / 2));
        guiGraphics.drawString(this.font, name, nameX, line1, nameColor, false);
        int cursor = nameX + this.font.width(name) + 5;
        // 名字后面的铭牌、状态一律不能越过这条线，窄窗口下放不下的铭牌宁可不画也别压到右侧
        int rightLimit = x + width - headlineWidth - 6;

        if (top10) {
            Component badge = rankBadgeText(rank);
            if (cursor + plaqueWidth(badge) <= rightLimit) {
                cursor = drawPlaque(guiGraphics, badge, cursor, line1 - 1,
                        top3 ? rankColor(rank) : MasteryPalette.GOLD, top3) + 4;
            }
        }
        if (self) {
            Component you = Component.translatable("gui.albionmastery.leaderboard.you");
            if (cursor + plaqueWidth(you) <= rightLimit) {
                cursor = drawPlaque(guiGraphics, you, cursor, line1 - 1, MasteryPalette.GOLD, false) + 4;
            }
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
        if (cursor + this.font.width(status) * SMALL_SCALE < rightLimit) {
            drawScaled(guiGraphics, status, cursor, line1 + 1, SMALL_SCALE, statusColor);
        }

        if (rankView) {
            long rankXp = rankXpOf(row);
            int masteryRank = MasteryRank.rankFor(rankXp);
            drawRankCell(guiGraphics, masteryRank, rankXp, x, y, width, line1);
            // 第二行：段位经验和离下一段还差多少。右侧没有排序名那行小字，预算只让出徽章那一块
            int rankStatsBudget = (int) ((width - NAME_INSET - headlineWidth - 10) / SMALL_SCALE);
            drawScaled(guiGraphics,
                    Component.literal(trimToWidth(rankStatsText(rankXp, masteryRank).getString(), rankStatsBudget)),
                    nameX, line1 + 13, SMALL_SCALE, MasteryPalette.TEXT_DIM);
            return;
        }

        // 右侧：这种排序方式看的那个数，大字；底下一行小字说明是什么
        String headline = headlineText(row);
        int headlineX = x + width - 6 - this.font.width(headline);
        guiGraphics.drawString(this.font, headline, headlineX, line1,
                top3 ? rankColor(rank) : MasteryPalette.GOLD_BRIGHT, false);
        Component headlineLabel = Component.translatable(this.shownSort.nameKey());
        drawScaled(guiGraphics, headlineLabel,
                (int) (x + width - 6 - this.font.width(headlineLabel) * SMALL_SCALE),
                line1 + 13, SMALL_SCALE, MasteryPalette.TEXT_DIM);

        // 第二行：其余概况，一行小字，超出就截
        // 增量榜里特化数、满级类型这些还是当前值而不是增量，跟着一起显示会让人以为特化也是今天点的，
        // 所以换一套只讲「这段时间涨了什么」的文案
        Component stats = this.shownPeriod.isDelta()
                ? Component.translatable("gui.albionmastery.leaderboard.row_stats_period",
                        compactXp(row.xpSum()), row.levelSum(), row.maxed(), row.unlocked())
                : Component.translatable("gui.albionmastery.leaderboard.row_stats",
                        row.maxed(), row.levelSum(), row.unlocked(), row.specCount());
        String line2 = stats.getString();
        int statsBudget = (int) ((width - NAME_INSET - headlineWidth - 40) / SMALL_SCALE);
        drawScaled(guiGraphics, Component.literal(trimToWidth(line2, statsBudget)),
                nameX, line1 + 13, SMALL_SCALE, MasteryPalette.TEXT_DIM);
    }

    /**
     * 段位视图一行的右侧：段位徽章 + 段位名一栏。
     *
     * <p>段位名一栏右对齐、宽度整列统一（{@link #rankColumnWidth}），徽章贴在这一栏左边、上下居中，
     * 一列徽章排得齐齐的。栏里第一行是段位名，用段位本色；第二行左边一根细进度条（本段位内攒了多少），
     * 右边小字「段位 N」。名次高低已经由左边的六边形管着，这里的颜色只跟段位走，不跟名次走。
     *
     * @param guiGraphics 绘图上下文
     * @param masteryRank 段位
     * @param rankXp      段位经验
     * @param x           行左边缘
     * @param y           行顶边
     * @param width       行宽
     * @param line1       第一行字的 Y
     */
    private void drawRankCell(GuiGraphics guiGraphics, int masteryRank, long rankXp,
                              int x, int y, int width, int line1) {
        int column = rankColumnWidth();
        int right = x + width - 6;
        int columnLeft = right - column;
        int color = RankEmblem.primaryColor(masteryRank);

        RankEmblem.draw(guiGraphics, masteryRank, columnLeft - RANK_EMBLEM_GAP - RANK_EMBLEM_SIZE / 2.0F,
                y + ROW_HEIGHT / 2.0F, RANK_EMBLEM_SIZE, 1.0F);

        String name = trimToWidth(MasteryRank.name(masteryRank).getString(), column);
        guiGraphics.drawString(this.font, name, right - this.font.width(name), line1, color, false);

        Component label = masteryRankLabel(masteryRank);
        int labelX = right - (int) Math.ceil(this.font.width(label) * SMALL_SCALE);
        drawScaled(guiGraphics, label, labelX, line1 + 13, SMALL_SCALE, MasteryPalette.TEXT_DIM);

        // 进度条和小字的视觉中线对齐，两像素高；窗口太窄、栏被封顶时放不下就不画
        int barRight = labelX - 3;
        if (barRight - columnLeft >= RANK_BAR_MIN_WIDTH) {
            int barTop = line1 + 15;
            guiGraphics.fill(columnLeft, barTop, barRight, barTop + 2,
                    RenderUtil.withAlpha(MasteryPalette.EDGE, 0.55F));
            int filled = Math.round((barRight - columnLeft) * MasteryRank.progress(rankXp));
            if (filled > 0) {
                guiGraphics.fill(columnLeft, barTop, columnLeft + filled, barTop + 2, color);
            }
        }
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
        // 前三名不用亮底黑字：黑数字在一屏金色里像个洞
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
     * 徽章文字：「全局第一」「日榜第一」「周榜前十」这样，前缀跟着时间范围走。
     *
     * @param rank 名次
     * @return 文字
     */
    private Component rankBadgeText(int rank) {
        Component label = Component.translatable(this.shownPeriod.labelKey());
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
     * <p>公开出来是给「查看他人罗盘」的标题用的：点进全局第一的罗盘，标题上就该写着全局第一。
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
                return rankAt(i);
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
     * 铭牌画出来有多宽，和 {@link #drawPlaque} 的算法一致，画之前先量一下放不放得下。
     *
     * @param text 文字
     * @return 宽度（像素）
     */
    private int plaqueWidth(Component text) {
        return (int) Math.ceil(this.font.width(text) * SMALL_SCALE) + 6;
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
        if (this.state == State.READY && !this.activeQuery.isEmpty() && this.matched > this.rows.size()) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.albionmastery.leaderboard.footer_search_more",
                            this.matched, this.rows.size()),
                    this.width / 2, textY, MasteryPalette.TEXT_DIM);
            return;
        }
        if (this.state == State.READY) {
            Component line;
            if (this.selfRank > 0) {
                line = Component.translatable("gui.albionmastery.leaderboard.footer_rank",
                        this.selfRank, this.total);
            } else {
                line = Component.translatable(this.shownPeriod.isDelta()
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
        boolean onSearch = isOn(mouseX, mouseY, searchLeft(), searchTop(), searchWidth(), SEARCH_HEIGHT);
        if (this.searchBox != null) {
            if (!onSearch && this.searchBox.isFocused()) {
                this.searchBox.setFocused(false);
                setFocused(null);
            }
            if (onSearch) {
                if (!this.searchBox.getValue().isEmpty() && mouseX >= searchClearLeft()) {
                    // 清空就是回到整张榜，不用等防抖
                    this.searchBox.setValue("");
                    submitSearch();
                    return true;
                }
                // 整个外框都算搜索框，别让玩家瞄那一行 8 像素高的字
                setFocused(this.searchBox);
                this.searchBox.mouseClicked(mouseX, mouseY, button);
                return true;
            }
        }
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
                        LeaderboardPeriod before = this.period;
                        this.period = periods[i];
                        this.sort = carrySort(this.sort, before, this.period);
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
        boolean typing = this.searchBox != null && this.searchBox.isFocused();
        if (typing) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                submitSearch();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.searchBox.setFocused(false);
                setFocused(null);
                return true;
            }
            if (this.searchBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        } else if (this.searchBox != null && keyCode == GLFW.GLFW_KEY_F && Screen.hasControlDown()) {
            setFocused(this.searchBox);
            return true;
        }
        if (!typing && KeyBindings.OPEN_MASTERY.matches(keyCode, scanCode)) {
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

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.searchBox != null && this.searchBox.isFocused()) {
            return this.searchBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
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
