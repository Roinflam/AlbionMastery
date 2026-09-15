package com.flameforge.albionmastery.leaderboard;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.network.S2CRankChangePacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 全服专精排行榜的数据仓库（服务端）。
 *
 * <p>玩家的专精数据挂在玩家实体的 Capability 上，人一下线实体就没了，
 * 想排「全服」而不是「此刻在线的这几个」，就得在别的地方留一份副本。
 * 这里用一份世界级的 {@link SavedData}（存在主世界的 {@code data/} 目录下），
 * 按 UUID 记每个人的：
 * <ul>
 *     <li>最后一次记录时的名字和时间</li>
 *     <li>整份专精数据的 NBT（和玩家存档里那份格式完全一样）——看别人整个罗盘靠它</li>
 *     <li>一行概况（满级数、等级总和……）——总榜排名靠它，内存里现算，不存盘</li>
 *     <li>日 / 周 / 月三条基线——「这段时间涨了多少」靠它，存盘</li>
 * </ul>
 *
 * <p>写入时机：登录、登出各记一次；在线期间数据有变动的玩家先做个标记，
 * 每隔一段时间（配置 {@code leaderboard.updateIntervalSeconds}）统一刷一遍。
 * 不在每次涨经验时都写：一把霰弹枪一秒钟能让数据变几十次，每次都序列化整份数据太浪费，
 * 而排行榜晚个几十秒才更新没人在乎。
 *
 * <p>每次写完都看一眼总榜前三有没有易主，有就给所有在线玩家发一条通知（客户端弹横幅，不走聊天）。
 *
 * <p>所有方法都只能在服务端主线程调用。
 */
public final class MasteryLeaderboard extends SavedData {

    /** 存盘文件名（不带 .dat）。 */
    private static final String DATA_NAME = AlbionMastery.MOD_ID + "_leaderboard";

    /** 读档时最多接受多少个玩家条目，只用来挡坏掉的文件。 */
    private static final int MAX_LOADED_ENTRIES = 200_000;

    /** 广播看前几名。 */
    private static final int BROADCAST_TOP = 3;

    /** 限流用的请求种类：要榜单。 */
    public static final String REQUEST_LIST = "list";

    /** 限流用的请求种类：要某个人的整份数据。 */
    public static final String REQUEST_PLAYER = "player";

    /** UUID -> 条目。 */
    private final Map<UUID, Entry> entries = new HashMap<>();

    /** 在线且数据变过、等着下一次定时刷新写进榜单的玩家。不存盘。 */
    private final Set<UUID> pending = new HashSet<>();

    /** 「UUID|种类」 -> 上一次请求的时刻（毫秒），用来限流。不存盘。 */
    private final Map<String, Long> lastRequest = new HashMap<>();

    /** 上一次看到的总榜前三（UUID），广播靠它判断谁换了谁。不存盘，读档时按数据初始化。 */
    private final List<UUID> lastTop = new ArrayList<>(BROADCAST_TOP);

    /** 和 {@link #lastTop} 一一对应的名字：被挤下去的人可能已经不在榜上了，名字得单独留。 */
    private final List<String> lastTopNames = new ArrayList<>(BROADCAST_TOP);

    /**
     * 新建一份空榜单。
     */
    public MasteryLeaderboard() {
    }

    /**
     * 取当前服务器的榜单，没有就建一份。
     *
     * <p>挂在主世界上：主世界总是最先加载、最后卸载，放这里最稳。
     *
     * @param server 服务器
     * @return 榜单
     */
    public static MasteryLeaderboard get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(MasteryLeaderboard::load, MasteryLeaderboard::new, DATA_NAME);
    }

    // ======================================================================
    // 写入
    // ======================================================================

    /**
     * 用一名在线玩家此刻的数据刷新他的条目，然后看一眼前三有没有变。
     *
     * @param player 在线玩家
     */
    public void update(ServerPlayer player) {
        record(player);
        MinecraftServer server = player.getServer();
        if (server != null) {
            announceTopChanges(server);
        }
    }

    /**
     * 只写条目，不看前三。批量刷新时逐个调这个，最后统一看一次。
     *
     * <p>一点专精都没练过的玩家不入榜（也顺便把之前的条目清掉）：
     * 几百个只上线逛了一圈的人排在榜尾全是 0，没意义。
     *
     * @param player 在线玩家
     */
    private void record(ServerPlayer player) {
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }
        UUID uuid = player.getUUID();
        this.pending.remove(uuid);
        if (!hasAnyProgress(data)) {
            if (this.entries.remove(uuid) != null) {
                setDirty();
            }
            return;
        }
        long now = System.currentTimeMillis();
        String name = player.getGameProfile().getName();
        LeaderboardRow row = summarize(uuid, name, now, data, MasteryConfig.serverSnapshot());
        Entry previous = this.entries.get(uuid);
        Entry entry = new Entry(name, now, data.serializeNBT(), row);
        entry.rollBaselines(previous);
        this.entries.put(uuid, entry);
        setDirty();
    }

    /**
     * 标记某个在线玩家的数据变过了，下次定时刷新时重新记一遍。
     *
     * @param uuid 玩家 UUID
     */
    public void markChanged(UUID uuid) {
        this.pending.add(uuid);
    }

    /**
     * 把所有做过标记、且此刻还在线的玩家刷一遍，最后看一次前三。
     *
     * <p>已经下线的会在登出事件里单独记过，这里直接跳过。
     *
     * @param server 服务器
     */
    public void flushChanged(MinecraftServer server) {
        if (this.pending.isEmpty()) {
            return;
        }
        PlayerList players = server.getPlayerList();
        // 先拷一份再遍历：record 会从 pending 里移除，边遍历边删会抛并发修改异常
        for (UUID uuid : new ArrayList<>(this.pending)) {
            ServerPlayer player = players.getPlayer(uuid);
            if (player != null) {
                record(player);
            }
        }
        this.pending.clear();
        announceTopChanges(server);
    }

    /**
     * 玩家下线后清掉他的临时状态（限流记录、待刷新标记）。条目本身保留，那正是这份榜单存在的意义。
     *
     * @param uuid 玩家 UUID
     */
    public void forgetSession(UUID uuid) {
        this.pending.remove(uuid);
        this.lastRequest.remove(requestKey(uuid, REQUEST_LIST));
        this.lastRequest.remove(requestKey(uuid, REQUEST_PLAYER));
    }

    /**
     * 配置（等级上限、经验曲线）变了之后，把所有条目的概况按新曲线重算一遍。
     *
     * <p>要把每个人的 NBT 都反序列化一次，几百人的话是几十毫秒的事，只在配置重载时跑。
     * 基线不动：等级上限一变，「这段时间涨了多少」本来就说不清了，负数会被夹成 0，下一期自然就对了。
     */
    public void recomputeAll() {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        for (Map.Entry<UUID, Entry> mapEntry : this.entries.entrySet()) {
            Entry entry = mapEntry.getValue();
            MasteryData data = new MasteryData();
            data.deserializeNBT(entry.data);
            data.recomputeLevels(snapshot.weaponCurve(), snapshot.typeCurve(), snapshot.overallCurve());
            entry.row = summarize(mapEntry.getKey(), entry.name, entry.updatedAt, data, snapshot);
        }
        // 前三的基准也换成新算法下的，免得下一次写入时把「重算导致的名次变化」当成易主广播出去
        rememberTop(topRows(MasteryConfig.COMMON.leaderboardBroadcastSort.get(), BROADCAST_TOP));
        AlbionMastery.LOGGER.info("排行榜已按新配置重算 {} 名玩家的概况", this.entries.size());
    }

    // ======================================================================
    // 前三易主广播
    // ======================================================================

    /**
     * 对比上一次的总榜前三，有人挤进来就广播。
     *
     * <p>只广播「往上爬」的人：A 从第三冲到第一，第二名 B、第三名 C 都是被顶下去的，
     * 一条「A 替代 B 成为了第一」就够了，不用再来两条「B 替代 C」「C 替代 D」。
     *
     * @param server 服务器
     */
    private void announceTopChanges(MinecraftServer server) {
        LeaderboardSort sort = MasteryConfig.COMMON.leaderboardBroadcastSort.get();
        List<LeaderboardRow> top = topRows(sort, BROADCAST_TOP);
        if (!MasteryConfig.COMMON.leaderboardBroadcastTopChanges.get()) {
            rememberTop(top);
            return;
        }
        for (int i = 0; i < top.size(); i++) {
            LeaderboardRow newcomer = top.get(i);
            UUID before = i < this.lastTop.size() ? this.lastTop.get(i) : null;
            if (newcomer.uuid().equals(before)) {
                continue;
            }
            int oldIndex = this.lastTop.indexOf(newcomer.uuid());
            // 原来就在更靠前的位置，说明是被顶下来的，不算「成为」
            if (oldIndex != -1 && oldIndex < i) {
                continue;
            }
            String displaced = before == null ? null : this.lastTopNames.get(i);
            broadcast(server, newcomer, displaced, i + 1);
        }
        rememberTop(top);
    }

    /**
     * 把「xxx 成为了第几名」发给所有在线玩家，客户端各自弹横幅：本人一句，其他人另一句。
     *
     * @param server    服务器
     * @param newcomer  新上位的人
     * @param displaced 被挤下去的人的名字，没有则为 null
     * @param rank      名次，从 1 起
     */
    private static void broadcast(MinecraftServer server, LeaderboardRow newcomer,
                                  @Nullable String displaced, int rank) {
        S2CRankChangePacket packet = new S2CRankChangePacket(newcomer.uuid(), newcomer.name(), displaced, rank);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NetworkHandler.sendTo(player, packet);
        }
        AlbionMastery.LOGGER.info("排行榜前三变动：{} 成为第 {} 名{}", newcomer.name(), rank,
                displaced == null ? "" : "（原为 " + displaced + "）");
    }

    private void rememberTop(List<LeaderboardRow> top) {
        this.lastTop.clear();
        this.lastTopNames.clear();
        for (LeaderboardRow row : top) {
            this.lastTop.add(row.uuid());
            this.lastTopNames.add(row.name());
        }
    }

    // ======================================================================
    // 读取
    // ======================================================================

    /**
     * 限流：这名玩家现在能不能再发一次请求。
     *
     * <p>排一次榜要把所有条目拷一遍再排序，几千人的服上一个人狂点刷新就能把主线程堆满，
     * 所以两次请求之间至少要隔配置里的秒数。
     *
     * <p>「要榜单」和「要某个人的数据」分开计冷却：刷新完榜单马上点一个人进去看是很自然的操作，
     * 不该被上一次刷新的冷却挡住。
     *
     * @param uuid       玩家 UUID
     * @param kind       请求种类，{@link #REQUEST_LIST} 或 {@link #REQUEST_PLAYER}
     * @param cooldownMs 冷却毫秒数
     * @return 允许返回 true，并记下这次请求的时间
     */
    public boolean tryAcquireRequest(UUID uuid, String kind, long cooldownMs) {
        long now = System.currentTimeMillis();
        String key = requestKey(uuid, kind);
        Long last = this.lastRequest.get(key);
        if (last != null && now - last < cooldownMs) {
            return false;
        }
        this.lastRequest.put(key, now);
        return true;
    }

    private static String requestKey(UUID uuid, String kind) {
        return uuid + "|" + kind;
    }

    /**
     * 按某种范围、某种排序取前若干名，并顺带告诉请求者自己排第几。
     *
     * <p>请求者自己不在前几名里也要能看到名次，所以整张榜先排完再截取。
     * 日 / 周 / 月榜只收这段时间有进展的人，一屏 0 没意义。
     *
     * @param server 服务器，用来判断谁在线
     * @param period 时间范围
     * @param sort   排序方式
     * @param limit  最多返回多少行
     * @param self   请求者的 UUID
     * @return 查询结果
     */
    public Result query(MinecraftServer server, LeaderboardPeriod period, LeaderboardSort sort,
                        int limit, UUID self) {
        List<LeaderboardRow> all = allRows(server, period);
        all.sort(sort.comparator());

        int selfRank = 0;
        LeaderboardRow selfRow = null;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).uuid().equals(self)) {
                selfRank = i + 1;
                selfRow = all.get(i);
                break;
            }
        }
        List<LeaderboardRow> top = all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
        return new Result(period, sort, top, selfRank, selfRow, all.size());
    }

    /**
     * 某种范围下的全部行（未排序）。
     *
     * @param server 服务器，传 null 则所有人都按离线算
     * @param period 时间范围
     * @return 行列表，可以随意改
     */
    private List<LeaderboardRow> allRows(@Nullable MinecraftServer server, LeaderboardPeriod period) {
        PlayerList players = server == null ? null : server.getPlayerList();
        String periodKey = period.currentKey();
        List<LeaderboardRow> all = new ArrayList<>(this.entries.size());
        for (Map.Entry<UUID, Entry> mapEntry : this.entries.entrySet()) {
            Entry entry = mapEntry.getValue();
            LeaderboardRow row = period.isDelta() ? entry.deltaRow(period, periodKey) : entry.row;
            if (row == null) {
                continue;
            }
            boolean online = players != null && players.getPlayer(mapEntry.getKey()) != null;
            all.add(row.withOnline(online));
        }
        return all;
    }

    /**
     * 总榜前若干名，广播用。
     *
     * @param sort  排序方式
     * @param count 取几名
     * @return 前若干名
     */
    private List<LeaderboardRow> topRows(LeaderboardSort sort, int count) {
        List<LeaderboardRow> all = allRows(null, LeaderboardPeriod.ALL);
        all.sort(sort.comparator());
        return all.size() > count ? new ArrayList<>(all.subList(0, count)) : all;
    }

    /**
     * 取某名玩家的整份专精数据（NBT）。
     *
     * <p>人在线就直接从他实体上现拿，保证看到的是最新的；不在线就用榜单里存的副本。
     *
     * @param server 服务器
     * @param uuid   玩家 UUID
     * @return 专精数据的 NBT（独立副本，可以随意改）；从没记录过这个人时返回 null
     */
    @Nullable
    public CompoundTag dataOf(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            MasteryData live = MasteryHelper.get(online);
            if (live != null) {
                return live.serializeNBT();
            }
        }
        Entry entry = this.entries.get(uuid);
        return entry == null ? null : entry.data.copy();
    }

    /**
     * 取某名玩家记录在案的名字。
     *
     * @param server 服务器
     * @param uuid   玩家 UUID
     * @return 名字；没记录时返回 null
     */
    @Nullable
    public String nameOf(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        Entry entry = this.entries.get(uuid);
        return entry == null ? null : entry.name;
    }

    // ======================================================================
    // 概况统计
    // ======================================================================

    /**
     * 把一份专精数据浓缩成一行概况。
     *
     * <p>要求 {@code data} 里的等级已经算好（在线玩家的数据本来就是算好的，
     * 从 NBT 读出来的要先 {@code recomputeLevels}）。
     *
     * @param uuid      玩家 UUID
     * @param name      玩家名
     * @param updatedAt 记录时刻
     * @param data      专精数据
     * @param snapshot  配置快照
     * @return 一行概况（在线标记先填 false，查询时再补）
     */
    private static LeaderboardRow summarize(UUID uuid, String name, long updatedAt,
                                            MasteryData data, MasteryConfig.Snapshot snapshot) {
        int maxLevel = snapshot.maxLevel;
        int maxed = 0;
        int levelSum = 0;
        int unlocked = 0;
        int topLevel = 0;
        long topXp = 0L;
        for (MasteryData.WeaponRecord record : data.getWeaponsView().values()) {
            if (record.level > 0) {
                unlocked++;
                levelSum += record.level;
            }
            if (record.level >= maxLevel) {
                maxed++;
            }
            // 单件最高只用来给平手的人分先后，界面上不显示
            if (record.level > topLevel || (record.level == topLevel && record.xp > topXp)) {
                topLevel = record.level;
                topXp = record.xp;
            }
        }
        int typeMaxed = 0;
        for (MasteryData.Entry entry : data.getTypesView().values()) {
            if (entry.level >= maxLevel) {
                typeMaxed++;
            }
        }
        int overallMaxed = 0;
        for (MasteryData.Entry entry : data.getOverallsView().values()) {
            if (entry.level >= maxLevel) {
                overallMaxed++;
            }
        }
        int specCount = 0;
        for (String specKey : data.getSpecializationsView().values()) {
            Specialization spec = Specialization.byKey(specKey);
            if (spec != null && !spec.isStandard()) {
                specCount++;
            }
        }
        return new LeaderboardRow(uuid, name, false, updatedAt, maxed, levelSum, unlocked,
                topLevel, topXp, specCount, typeMaxed, overallMaxed);
    }

    /**
     * 这份数据里有没有任何一点经验。
     *
     * @param data 专精数据
     * @return 有返回 true
     */
    private static boolean hasAnyProgress(MasteryData data) {
        for (MasteryData.WeaponRecord record : data.getWeaponsView().values()) {
            if (record.xp > 0L) {
                return true;
            }
        }
        for (MasteryData.Entry entry : data.getTypesView().values()) {
            if (entry.xp > 0L) {
                return true;
            }
        }
        for (MasteryData.Entry entry : data.getOverallsView().values()) {
            if (entry.xp > 0L) {
                return true;
            }
        }
        return false;
    }

    // ======================================================================
    // 存盘
    // ======================================================================

    /**
     * 从存盘文件读出榜单。概况不存盘，读的时候按当前配置现算；基线照读。
     *
     * @param root 存盘 NBT
     * @return 榜单
     */
    public static MasteryLeaderboard load(CompoundTag root) {
        MasteryLeaderboard board = new MasteryLeaderboard();
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        ListTag list = root.getList("Players", Tag.TAG_COMPOUND);
        int count = Math.min(list.size(), MAX_LOADED_ENTRIES);
        int skipped = 0;
        for (int i = 0; i < count; i++) {
            CompoundTag tag = list.getCompound(i);
            if (!tag.hasUUID("Id")) {
                skipped++;
                continue;
            }
            UUID uuid = tag.getUUID("Id");
            String name = tag.getString("Name");
            long updatedAt = Math.max(0L, tag.getLong("Time"));
            CompoundTag data = tag.getCompound("Data");
            MasteryData parsed = new MasteryData();
            parsed.deserializeNBT(data);
            parsed.recomputeLevels(snapshot.weaponCurve(), snapshot.typeCurve(), snapshot.overallCurve());
            if (!hasAnyProgress(parsed)) {
                skipped++;
                continue;
            }
            Entry entry = new Entry(name, updatedAt, data, summarize(uuid, name, updatedAt, parsed, snapshot));
            entry.readBaselines(tag.getCompound("Base"));
            board.entries.put(uuid, entry);
        }
        if (skipped > 0) {
            AlbionMastery.LOGGER.warn("排行榜读档时跳过了 {} 条空的或坏掉的记录", skipped);
        }
        // 读档时记下当前前三，重启后第一次写入不会把「已经是第一」误当成「刚成为第一」广播出去
        board.rememberTop(board.topRows(MasteryConfig.COMMON.leaderboardBroadcastSort.get(), BROADCAST_TOP));
        AlbionMastery.LOGGER.info("排行榜已读入 {} 名玩家", board.entries.size());
        return board;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> mapEntry : this.entries.entrySet()) {
            Entry entry = mapEntry.getValue();
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", mapEntry.getKey());
            tag.putString("Name", entry.name);
            tag.putLong("Time", entry.updatedAt);
            tag.put("Data", entry.data);
            tag.put("Base", entry.writeBaselines());
            list.add(tag);
        }
        root.put("Players", list);
        return root;
    }

    /**
     * 查询结果。
     *
     * @param period   时间范围
     * @param sort     排序方式
     * @param rows     前若干名
     * @param selfRank 请求者自己的名次，从 1 起；不在榜上为 0
     * @param selfRow  请求者自己那一行，不在榜上为 null
     * @param total    榜上总人数
     */
    public record Result(LeaderboardPeriod period, LeaderboardSort sort, List<LeaderboardRow> rows,
                         int selfRank, @Nullable LeaderboardRow selfRow, int total) {
    }

    /**
     * 一条基线：某一期开始时这个人的三个累计值。
     */
    private static final class Baseline {

        /** 期号，和 {@link LeaderboardPeriod#currentKey()} 对得上才有效。 */
        final String periodKey;
        final int maxed;
        final int levelSum;
        final int unlocked;

        Baseline(String periodKey, int maxed, int levelSum, int unlocked) {
            this.periodKey = periodKey;
            this.maxed = maxed;
            this.levelSum = levelSum;
            this.unlocked = unlocked;
        }

        static Baseline of(String periodKey, LeaderboardRow row) {
            return new Baseline(periodKey, row.maxed(), row.levelSum(), row.unlocked());
        }

        CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putString("K", this.periodKey);
            tag.putInt("M", this.maxed);
            tag.putInt("L", this.levelSum);
            tag.putInt("U", this.unlocked);
            return tag;
        }

        static Baseline read(CompoundTag tag) {
            return new Baseline(tag.getString("K"), Math.max(0, tag.getInt("M")),
                    Math.max(0, tag.getInt("L")), Math.max(0, tag.getInt("U")));
        }
    }

    /**
     * 一名玩家的条目。
     */
    private static final class Entry {

        /** 最后一次记录时的名字。 */
        final String name;

        /** 最后一次记录的时刻。 */
        final long updatedAt;

        /** 整份专精数据。 */
        final CompoundTag data;

        /** 概况，配置重载时会被重算。 */
        LeaderboardRow row;

        /** 日 / 周 / 月三条基线，没有的期就没有键。 */
        final Map<LeaderboardPeriod, Baseline> baselines = new EnumMap<>(LeaderboardPeriod.class);

        Entry(String name, long updatedAt, CompoundTag data, LeaderboardRow row) {
            this.name = name;
            this.updatedAt = updatedAt;
            this.data = data;
            this.row = row;
        }

        /**
         * 从上一条记录继承基线，过期的按上一条记录的值重打。
         *
         * <p>基线取「上一条记录」而不是「这一条」：上一条是他上一期结束时的样子
         * （下线之后数据不会变），这一条已经包含这一期的进展了。
         * 从没记录过的新人基线全 0，第一天的进展全算数。
         *
         * @param previous 上一条记录，可为 null
         */
        void rollBaselines(@Nullable Entry previous) {
            for (LeaderboardPeriod period : LeaderboardPeriod.values()) {
                if (!period.isDelta()) {
                    continue;
                }
                String key = period.currentKey();
                Baseline old = previous == null ? null : previous.baselines.get(period);
                if (old != null && old.periodKey.equals(key)) {
                    this.baselines.put(period, old);
                } else if (previous != null) {
                    this.baselines.put(period, Baseline.of(key, previous.row));
                } else {
                    this.baselines.put(period, new Baseline(key, 0, 0, 0));
                }
            }
        }

        /**
         * 这段时间涨了多少，做成一行。
         *
         * <p>基线不是这一期的（这一期还没记录过）就说明他这期没动过，返回 null 不上榜。
         * 单件最高那两项保留当前值，只用来分平手。
         *
         * @param period    时间范围
         * @param periodKey 当前期号
         * @return 增量行；这一期没进展返回 null
         */
        @Nullable
        LeaderboardRow deltaRow(LeaderboardPeriod period, String periodKey) {
            Baseline base = this.baselines.get(period);
            if (base == null || !base.periodKey.equals(periodKey)) {
                return null;
            }
            // 管理员清过数据的话会出负数，夹成 0
            int maxed = Math.max(0, this.row.maxed() - base.maxed);
            int levelSum = Math.max(0, this.row.levelSum() - base.levelSum);
            int unlocked = Math.max(0, this.row.unlocked() - base.unlocked);
            if (maxed == 0 && levelSum == 0 && unlocked == 0) {
                return null;
            }
            return new LeaderboardRow(this.row.uuid(), this.row.name(), false, this.row.updatedAt(),
                    maxed, levelSum, unlocked, this.row.topLevel(), this.row.topXp(),
                    this.row.specCount(), this.row.typeMaxed(), this.row.overallMaxed());
        }

        CompoundTag writeBaselines() {
            CompoundTag tag = new CompoundTag();
            for (Map.Entry<LeaderboardPeriod, Baseline> mapEntry : this.baselines.entrySet()) {
                String key = mapEntry.getKey().nbtKey();
                if (key != null) {
                    tag.put(key, mapEntry.getValue().write());
                }
            }
            return tag;
        }

        void readBaselines(CompoundTag tag) {
            for (LeaderboardPeriod period : LeaderboardPeriod.values()) {
                String key = period.nbtKey();
                if (key != null && tag.contains(key)) {
                    this.baselines.put(period, Baseline.read(tag.getCompound(key)));
                }
            }
        }
    }
}
