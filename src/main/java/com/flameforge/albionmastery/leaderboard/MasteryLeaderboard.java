package com.flameforge.albionmastery.leaderboard;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.network.S2CRankChangePacket;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.saveddata.SavedData;

import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Iterator;

/**
 * 全局专精排行榜的数据仓库（服务端）。
 *
 * <p>玩家的专精数据挂在玩家实体的 Capability 上，人一下线实体就没了，
 * 想排「全局」而不是「此刻在线的这几个」，就得在别的地方留一份副本。
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

    /** 换期时存下上一期的前几名。 */
    private static final int HISTORY_TOP = 50;

    /** 换期时往服务端日志里写前几名。 */
    private static final int HISTORY_LOG_TOP = 10;

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
     * 这一轮第一次入榜的人。他们挤进前三不广播：榜单刚开启时老玩家陆续上线，
     * 会连着弹一串「某某成为了第一」，其实谁也没进步。不存盘，每次看完前三就清空。
     */
    private final Set<UUID> firstSeen = new HashSet<>();

    /**
     * 日 / 周 / 月三种范围上一次看到的期号，存盘。期号一变就说明换期了，先把上一期的榜存下来再说。
     * 没有这个键的（老存档、刚装上这个版本）第一次只记下当前期号、不存档：不知道上一期是哪期。
     */
    private final Map<LeaderboardPeriod, String> seenKeys = new EnumMap<>(LeaderboardPeriod.class);

    /** 存下来的往期榜单，每种范围一张表，新的在前。存盘。 */
    private final Map<LeaderboardPeriod, List<PastPeriod>> history = new EnumMap<>(LeaderboardPeriod.class);

    /**
     * 新建一份空榜单。
     */
    public MasteryLeaderboard() {
    }

    /**
     * 取当前服务端的榜单，没有就建一份。
     *
     * <p>挂在主世界上：主世界总是最先加载、最后卸载，放这里最稳。
     *
     * @param server 服务端
     * @return 榜单
     */
    public static MasteryLeaderboard get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(MasteryLeaderboard::new, MasteryLeaderboard::load), DATA_NAME);
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
     * 记一遍这名玩家，但不广播前三易主：管理员用指令或罗盘给自己满级测试时，
     * 不该向全服宣布「成为了第一」。前三的基准同时换成新的，之后也不会补播。
     *
     * @param player 在线玩家
     */
    public void updateQuietly(ServerPlayer player) {
        record(player);
        rememberTop(topRows(MasteryConfig.COMMON.leaderboardBroadcastSort.get(), BROADCAST_TOP));
        this.firstSeen.clear();
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
        // 必须赶在 rollBaselines 之前：这个人一记录，他的基线就滚到新的一期，上一期的起点就没了
        checkRollover(player.getServer());
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
        if (previous == null) {
            this.firstSeen.add(uuid);
        }
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
     * @param server 服务端
     */
    public void flushChanged(MinecraftServer server) {
        // 没人在线也要按时换期，存档的时刻才贴近期末；清理过期条目之前做，免得清掉上一期还榜上有名的人
        checkRollover(server);
        pruneExpired(server);
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
     * 清掉太久没上线的条目。
     *
     * <p>榜单把每个玩家的整份专精数据都存在主世界的存档里，只增不减——开服一年之后，
     * 几千个只来过一次的玩家还各占一份。保留期按配置来，在线的人永远不清。
     *
     * @param server 服务端
     */
    private void pruneExpired(MinecraftServer server) {
        int days = MasteryConfig.COMMON.leaderboardRetentionDays.get();
        if (days <= 0 || this.entries.isEmpty()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        PlayerList players = server.getPlayerList();
        int removed = 0;
        Iterator<Map.Entry<UUID, Entry>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Entry> mapEntry = iterator.next();
            if (mapEntry.getValue().updatedAt >= cutoff || players.getPlayer(mapEntry.getKey()) != null) {
                continue;
            }
            iterator.remove();
            removed++;
        }
        if (removed > 0) {
            setDirty();
            AlbionMastery.LOGGER.info("排行榜清理了 {} 条超过 {} 天没更新的记录", removed, days);
        }
    }

    // ======================================================================
    // 换期存档
    // ======================================================================

    /**
     * 看日 / 周 / 月有没有换期，换了就把上一期的最终榜单存下来。
     *
     * <p>基线是每个人记录时各自滚动的：换期之后谁先上线，谁的基线就先被新一期覆盖，
     * 上一期的起点跟着没了，事后再也排不出上一期的榜。所以要在第一个人滚动之前、
     * 趁所有人的基线都还是上一期的，把整张榜排一遍存起来。此时每个人的「当前值」
     * 都是他上一期最后一次记录的值（换期后还没记录过），排出来就是上一期的最终结果。
     *
     * @param server 服务端，写存档文件用；为 null 时只存进榜单数据、不写文件
     */
    private void checkRollover(@Nullable MinecraftServer server) {
        for (LeaderboardPeriod period : LeaderboardPeriod.values()) {
            if (!period.isDelta()) {
                continue;
            }
            String current = period.currentKey();
            String seen = this.seenKeys.get(period);
            if (current.equals(seen)) {
                continue;
            }
            this.seenKeys.put(period, current);
            setDirty();
            if (seen != null) {
                closePeriod(server, period, seen);
            }
        }
    }

    /**
     * 排出某一期的最终榜单，存进往期记录、写日志和存档文件。
     *
     * @param server 服务端，可为 null
     * @param period 时间范围
     * @param key    结束的那一期的期号
     */
    private void closePeriod(@Nullable MinecraftServer server, LeaderboardPeriod period, String key) {
        List<LeaderboardRow> rows = new ArrayList<>();
        for (Entry entry : this.entries.values()) {
            LeaderboardRow row = entry.deltaRow(period, key);
            if (row != null) {
                rows.add(row);
            }
        }
        LeaderboardSort sort = LeaderboardSort.defaultFor(period);
        rows.sort(sort.comparator());
        List<LeaderboardRow> top = List.copyOf(rows.subList(0, Math.min(HISTORY_TOP, rows.size())));
        PastPeriod past = new PastPeriod(period, key, System.currentTimeMillis(), rows.size(), top);

        List<PastPeriod> list = this.history.computeIfAbsent(period, ignored -> new ArrayList<>());
        list.removeIf(old -> old.key().equals(key));
        list.add(0, past);
        while (list.size() > historyLimit(period)) {
            list.remove(list.size() - 1);
        }
        setDirty();

        StringBuilder log = new StringBuilder();
        for (int i = 0; i < Math.min(HISTORY_LOG_TOP, top.size()); i++) {
            LeaderboardRow row = top.get(i);
            log.append(String.format(Locale.ROOT, "%n  %2d. %s +%,d", i + 1, row.name(), row.xpSum()));
        }
        AlbionMastery.LOGGER.info("排行榜 {} {}（{}）已结束，共 {} 人有进展，按本期经验排前 {}：{}", period.id(), key,
                period.describeKey(key), rows.size(), Math.min(HISTORY_LOG_TOP, top.size()), log);
        if (server != null) {
            writeHistoryFile(server, past);
        }
    }

    /**
     * 每种范围留几期：日榜一个月，周榜半年，月榜两年。一期最多 {@value #HISTORY_TOP} 行，加起来也就几百 KB。
     */
    private static int historyLimit(LeaderboardPeriod period) {
        return switch (period) {
            case DAY -> 31;
            case WEEK -> 26;
            default -> 24;
        };
    }

    /**
     * 往期榜单所在的目录：存档根目录下的 {@code albionmastery/leaderboard-history}。
     *
     * @param server 服务端
     * @return 目录路径（不保证已存在）
     */
    public static Path historyDirectory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(AlbionMastery.MOD_ID).resolve("leaderboard-history")
                .toAbsolutePath().normalize();
    }

    /**
     * 把一期的最终榜单写成文本文件，服主按期发奖时直接打开看，不用进游戏。
     * 写不出来只记日志：榜单数据里已经存了一份，指令照样查得到。
     */
    private static void writeHistoryFile(MinecraftServer server, PastPeriod past) {
        Path file = historyDirectory(server).resolve(past.period().id() + "-" + past.key() + ".txt");
        StringBuilder text = new StringBuilder();
        text.append(past.key()).append("（").append(past.period().describeKey(past.key())).append("）\n");
        text.append("存档时间：").append(Instant.ofEpochMilli(past.closedAt()).atZone(ZoneId.systemDefault())
                .toLocalDateTime().withNano(0).toString().replace('T', ' ')).append('\n');
        text.append("本期有进展的共 ").append(past.participants()).append(" 人，按本期经验排序，存前 ")
                .append(past.rows().size()).append(" 名\n\n");
        text.append("名次\t玩家\t本期经验\t本期升级\t本期满级\t本期新解锁\tUUID\n");
        for (int i = 0; i < past.rows().size(); i++) {
            LeaderboardRow row = past.rows().get(i);
            text.append(i + 1).append('\t').append(row.name()).append('\t')
                    .append(String.format(Locale.ROOT, "%,d", row.xpSum())).append('\t')
                    .append(row.levelSum()).append('\t').append(row.maxed()).append('\t')
                    .append(row.unlocked()).append('\t').append(row.uuid()).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            AlbionMastery.LOGGER.warn("往期排行榜写不出文件 {}", file, exception);
        }
    }

    /**
     * 某种范围存下来的往期榜单。
     *
     * @param period 时间范围
     * @return 新的在前；没有就是空表
     */
    public List<PastPeriod> history(LeaderboardPeriod period) {
        List<PastPeriod> list = this.history.get(period);
        return list == null ? List.of() : Collections.unmodifiableList(list);
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
     * 基线不动：等级上限一变，等级维度的「这段时间涨了多少」本来就说不清了，负数会被夹成 0，
     * 下一期自然就对了。经验是原始值，不受曲线影响，改了配置也照样准。
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
     * @param server 服务端
     */
    private void announceTopChanges(MinecraftServer server) {
        LeaderboardSort sort = MasteryConfig.COMMON.leaderboardBroadcastSort.get();
        List<LeaderboardRow> top = topRows(sort, BROADCAST_TOP);
        if (!MasteryConfig.COMMON.leaderboardBroadcastTopChanges.get()) {
            rememberTop(top);
            this.firstSeen.clear();
            return;
        }
        for (int i = 0; i < top.size(); i++) {
            LeaderboardRow newcomer = top.get(i);
            UUID before = i < this.lastTop.size() ? this.lastTop.get(i) : null;
            if (newcomer.uuid().equals(before)) {
                continue;
            }
            int oldIndex = this.lastTop.indexOf(newcomer.uuid());
            // 原来就在更靠前的位置，说明是被顶下来的，不算「成为」；第一次入榜的也不算
            if (oldIndex != -1 && oldIndex < i || this.firstSeen.contains(newcomer.uuid())) {
                continue;
            }
            String displaced = before == null ? null : this.lastTopNames.get(i);
            broadcast(server, newcomer, displaced, i + 1);
        }
        rememberTop(top);
        this.firstSeen.clear();
    }

    /**
     * 把「xxx 成为了第几名」发给所有在线玩家，客户端各自弹横幅：本人一句，其他人另一句。
     *
     * @param server    服务端
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

    /** UUID 至少输几位才按 UUID 开头去匹配：太短的话随便几个字母就能撞上一堆人。 */
    private static final int UUID_SEARCH_MIN = 8;

    /**
     * 按某种范围、某种排序取前若干名，并顺带告诉请求者自己排第几。
     *
     * <p>请求者自己不在前几名里也要能看到名次，所以整张榜先排完再截取。
     * 日 / 周 / 月榜只收这段时间有进展的人，一屏 0 没意义。
     *
     * <p>带了搜索词就在整张排好的榜里筛：名字包含它（不分大小写），或者 UUID 以它开头。
     * 名次照旧按整张榜算，搜出来的是第 237 名就显示 237，不会因为筛过就变成第 1。
     *
     * @param server 服务端，用来判断谁在线
     * @param period 时间范围
     * @param sort   排序方式
     * @param limit  最多返回多少行
     * @param self   请求者的 UUID
     * @param search 搜索词，空串表示不筛
     * @return 查询结果
     */
    public Result query(MinecraftServer server, LeaderboardPeriod period, LeaderboardSort sort,
                        int limit, UUID self, String search) {
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
        String query = search == null ? "" : search.trim();
        if (query.isEmpty()) {
            List<LeaderboardRow> top = all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
            return new Result(period, sort, top, null, selfRank, selfRow, all.size(), "", all.size());
        }

        String needle = query.toLowerCase(Locale.ROOT);
        // UUID 允许带或不带横线粘进来，统一去掉横线再比
        String hexNeedle = needle.replace("-", "");
        boolean byUuid = hexNeedle.length() >= UUID_SEARCH_MIN && isHex(hexNeedle);
        List<LeaderboardRow> hits = new ArrayList<>();
        int[] ranks = new int[Math.min(limit, all.size())];
        int matched = 0;
        for (int i = 0; i < all.size(); i++) {
            LeaderboardRow row = all.get(i);
            boolean hit = row.name().toLowerCase(Locale.ROOT).contains(needle)
                    || byUuid && row.uuid().toString().replace("-", "").startsWith(hexNeedle);
            if (!hit) {
                continue;
            }
            if (hits.size() < ranks.length) {
                ranks[hits.size()] = i + 1;
                hits.add(row);
            }
            matched++;
        }
        return new Result(period, sort, hits, Arrays.copyOf(ranks, hits.size()), selfRank, selfRow,
                all.size(), query, matched);
    }

    private static boolean isHex(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * 某种范围下的全部行（未排序）。
     *
     * @param server 服务端，传 null 则所有人都按离线算
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
     * @param server 服务端
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
     * @param server 服务端
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
        long xpSum = 0L;
        int unlocked = 0;
        int topLevel = 0;
        long topXp = 0L;
        for (MasteryData.WeaponRecord record : data.getWeaponsView().values()) {
            // 经验不看等级：刚开始练的武器还是 0 级，但它那几百点经验也是今天刷出来的
            xpSum += record.xp;
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
        return new LeaderboardRow(uuid, name, false, updatedAt, maxed, levelSum, xpSum, unlocked,
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
     * @param root     存盘 NBT
     * @param provider 注册表查询（榜单里没有要查注册表的东西，用不上）
     * @return 榜单
     */
    public static MasteryLeaderboard load(CompoundTag root, HolderLookup.Provider provider) {
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
        CompoundTag seen = root.getCompound("Seen");
        CompoundTag history = root.getCompound("History");
        for (LeaderboardPeriod period : LeaderboardPeriod.values()) {
            String key = period.nbtKey();
            if (key == null) {
                continue;
            }
            if (seen.contains(key, Tag.TAG_STRING)) {
                board.seenKeys.put(period, seen.getString(key));
            }
            ListTag pastList = history.getList(key, Tag.TAG_COMPOUND);
            if (!pastList.isEmpty()) {
                List<PastPeriod> periods = new ArrayList<>(pastList.size());
                for (int i = 0; i < pastList.size(); i++) {
                    periods.add(PastPeriod.read(period, pastList.getCompound(i)));
                }
                board.history.put(period, periods);
            }
        }
        // 读档时记下当前前三，重启后第一次写入不会把「已经是第一」误当成「刚成为第一」广播出去
        board.rememberTop(board.topRows(MasteryConfig.COMMON.leaderboardBroadcastSort.get(), BROADCAST_TOP));
        AlbionMastery.LOGGER.info("排行榜已读入 {} 名玩家", board.entries.size());
        return board;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider provider) {
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
        CompoundTag seen = new CompoundTag();
        for (Map.Entry<LeaderboardPeriod, String> mapEntry : this.seenKeys.entrySet()) {
            seen.putString(mapEntry.getKey().nbtKey(), mapEntry.getValue());
        }
        root.put("Seen", seen);
        CompoundTag history = new CompoundTag();
        for (Map.Entry<LeaderboardPeriod, List<PastPeriod>> mapEntry : this.history.entrySet()) {
            ListTag pastList = new ListTag();
            for (PastPeriod past : mapEntry.getValue()) {
                pastList.add(past.write());
            }
            history.put(mapEntry.getKey().nbtKey(), pastList);
        }
        root.put("History", history);
        return root;
    }

    /**
     * 存下来的一期榜单。
     *
     * <p>行里只有四个增量（经验、升级、满级、新解锁）和名字、UUID 是有意义的，其余字段都是 0：
     * 存档只为看名次和发奖，不用再拿它分平手。
     *
     * @param period       时间范围
     * @param key          期号
     * @param closedAt     存档时刻（毫秒）
     * @param participants 这一期有进展的总人数
     * @param rows         前若干名，已按 {@link LeaderboardSort#defaultFor} 排好
     */
    public record PastPeriod(LeaderboardPeriod period, String key, long closedAt, int participants,
                             List<LeaderboardRow> rows) {

        CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putString("K", this.key);
            tag.putLong("T", this.closedAt);
            tag.putInt("N", this.participants);
            ListTag list = new ListTag();
            for (LeaderboardRow row : this.rows) {
                CompoundTag rowTag = new CompoundTag();
                rowTag.putUUID("Id", row.uuid());
                rowTag.putString("Name", row.name());
                rowTag.putLong("X", row.xpSum());
                rowTag.putInt("L", row.levelSum());
                rowTag.putInt("M", row.maxed());
                rowTag.putInt("U", row.unlocked());
                list.add(rowTag);
            }
            tag.put("R", list);
            return tag;
        }

        static PastPeriod read(LeaderboardPeriod period, CompoundTag tag) {
            ListTag list = tag.getList("R", Tag.TAG_COMPOUND);
            List<LeaderboardRow> rows = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                CompoundTag rowTag = list.getCompound(i);
                if (!rowTag.hasUUID("Id")) {
                    continue;
                }
                rows.add(new LeaderboardRow(rowTag.getUUID("Id"), rowTag.getString("Name"), false, 0L,
                        rowTag.getInt("M"), rowTag.getInt("L"), rowTag.getLong("X"), rowTag.getInt("U"),
                        0, 0L, 0, 0, 0));
            }
            return new PastPeriod(period, tag.getString("K"), tag.getLong("T"), tag.getInt("N"), List.copyOf(rows));
        }
    }

    /**
     * 查询结果。
     *
     * @param period   时间范围
     * @param sort     排序方式
     * @param rows     前若干名；搜索时是匹配到的人里名次最高的若干个
     * @param ranks    rows 里每一行在整张榜上的名次；不搜索时为 null，名次就是下标 + 1
     * @param selfRank 请求者自己的名次，从 1 起；不在榜上为 0
     * @param selfRow  请求者自己那一行，不在榜上为 null
     * @param total    榜上总人数
     * @param query    这次搜的词，没搜为空串
     * @param matched  搜到多少人（可能比 rows 多）；不搜索时等于 total
     */
    public record Result(LeaderboardPeriod period, LeaderboardSort sort, List<LeaderboardRow> rows,
                         @Nullable int[] ranks, int selfRank, @Nullable LeaderboardRow selfRow, int total,
                         String query, int matched) {
    }

    /**
     * 一条基线：某一期开始时这个人的几个累计值。
     */
    private static final class Baseline {

        /**
         * 这条基线是加经验线之前存下的，不知道那时他有多少经验。
         * 这一期的经验增量按 0 算——直接拿总经验当增量的话，升级当天全服的日榜都是各自的全部身家。
         */
        static final long XP_UNKNOWN = -1L;

        /** 期号，和 {@link LeaderboardPeriod#currentKey()} 对得上才有效。 */
        final String periodKey;
        final int maxed;
        final int levelSum;
        final int unlocked;

        /** 所有武器的经验总和，或 {@link #XP_UNKNOWN}。 */
        final long xpSum;

        Baseline(String periodKey, int maxed, int levelSum, int unlocked, long xpSum) {
            this.periodKey = periodKey;
            this.maxed = maxed;
            this.levelSum = levelSum;
            this.unlocked = unlocked;
            this.xpSum = xpSum;
        }

        static Baseline of(String periodKey, LeaderboardRow row) {
            return new Baseline(periodKey, row.maxed(), row.levelSum(), row.unlocked(), row.xpSum());
        }

        /**
         * 等级那几项照旧，只把经验线换成给定值。
         *
         * @param newXpSum 新的经验线
         * @return 新的基线
         */
        Baseline withXp(long newXpSum) {
            return new Baseline(this.periodKey, this.maxed, this.levelSum, this.unlocked, newXpSum);
        }

        CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putString("K", this.periodKey);
            tag.putInt("M", this.maxed);
            tag.putInt("L", this.levelSum);
            tag.putInt("U", this.unlocked);
            // 经验线还没补上的（老存档，本人这一期还没被记过一次）就不写这个键，
            // 写出去再读回来会变成「这一期开头他有 0 点经验」，一整期的经验增量就成了他的全部身家
            if (this.xpSum != XP_UNKNOWN) {
                tag.putLong("X", this.xpSum);
            }
            return tag;
        }

        static Baseline read(CompoundTag tag) {
            // 没有 X 的是加经验线之前存下的基线；负数同理，当成不知道
            long raw = tag.contains("X") ? tag.getLong("X") : XP_UNKNOWN;
            return new Baseline(tag.getString("K"), Math.max(0, tag.getInt("M")),
                    Math.max(0, tag.getInt("L")), Math.max(0, tag.getInt("U")),
                    raw < 0L ? XP_UNKNOWN : raw);
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
                    // 加经验线之前存下的基线不知道这一期开头他有多少经验，只能从这一刻起算。
                    // 否则这条基线所属的整个期间（月榜能有小半个月）经验增量一直是 0，榜是空的。
                    // 等级那几项照旧从期初算，那些数还在
                    this.baselines.put(period, old.xpSum == Baseline.XP_UNKNOWN
                            ? old.withXp(this.row.xpSum()) : old);
                } else if (previous != null) {
                    this.baselines.put(period, Baseline.of(key, previous.row));
                } else {
                    // 第一次入榜取当前值：榜单刚开启（或存档丢了）时老玩家的全部历史不该算成「今天涨的」。
                    // 真正的新人第一次入榜时只有刚练出来的一点点，少算这一点无所谓
                    this.baselines.put(period, Baseline.of(key, this.row));
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
            long xpSum = base.xpSum == Baseline.XP_UNKNOWN
                    ? 0L : Math.max(0L, this.row.xpSum() - base.xpSum);
            // 刷了经验没升级也算有进展，这正是增量榜要看经验的原因
            if (maxed == 0 && levelSum == 0 && unlocked == 0 && xpSum == 0L) {
                return null;
            }
            return new LeaderboardRow(this.row.uuid(), this.row.name(), false, this.row.updatedAt(),
                    maxed, levelSum, xpSum, unlocked, this.row.topLevel(), this.row.topXp(),
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
