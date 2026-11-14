package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.AMIds;
import com.flameforge.albionmastery.client.ClientLeaderboardHandler;
import com.flameforge.albionmastery.leaderboard.LeaderboardPeriod;
import com.flameforge.albionmastery.leaderboard.LeaderboardRow;
import com.flameforge.albionmastery.leaderboard.LeaderboardSort;
import com.flameforge.albionmastery.leaderboard.MasteryLeaderboard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 排行榜回包（S2C）。
 *
 * <p>正常情况下带前若干名和请求者自己的名次；服务端没开排行榜或者请求太频繁时只带一个状态，
 * 客户端据此显示提示，不会一直停在「加载中」。
 */
public class S2CLeaderboardPacket implements CustomPacketPayload {

    /** 负载 ID。 */
    public static final CustomPacketPayload.Type<S2CLeaderboardPacket> TYPE =
            new CustomPacketPayload.Type<>(AMIds.of("leaderboard"));

    /** 单个包最多接受多少行，只用来挡异常包（和配置 topCount 的上限一致）。 */
    private static final int MAX_ROWS = 500;

    /**
     * 回包状态。
     */
    public enum Status {
        /** 正常，带数据。 */
        OK,
        /** 服务端没开排行榜。 */
        DISABLED,
        /** 请求太频繁，被限流了。 */
        TOO_FAST
    }

    private final Status status;
    private final boolean periodsEnabled;
    private final LeaderboardPeriod period;
    private final LeaderboardSort sort;
    private final List<LeaderboardRow> rows;
    /** 每一行在整张榜上的名次，只有搜索时才带；null 表示名次就是下标 + 1。 */
    @Nullable
    private final int[] ranks;
    private final int selfRank;
    @Nullable
    private final LeaderboardRow selfRow;
    private final int total;
    /** 这批行是按什么搜出来的，空串表示整张榜。 */
    private final String query;
    /** 搜到多少人，可能比 rows 多。 */
    private final int matched;

    private S2CLeaderboardPacket(Status status, boolean periodsEnabled, LeaderboardPeriod period,
                                 LeaderboardSort sort, List<LeaderboardRow> rows, @Nullable int[] ranks,
                                 int selfRank, @Nullable LeaderboardRow selfRow, int total,
                                 String query, int matched) {
        this.status = status;
        this.periodsEnabled = periodsEnabled;
        this.period = period;
        this.sort = sort;
        this.rows = rows;
        this.ranks = ranks;
        this.selfRank = selfRank;
        this.selfRow = selfRow;
        this.total = total;
        this.query = query;
        this.matched = matched;
    }

    /**
     * 正常回包。
     *
     * @param result         查询结果
     * @param periodsEnabled 这个服务端有没有日 / 周 / 月榜（只有专用服务端才有）
     * @return 网络包
     */
    public static S2CLeaderboardPacket ok(MasteryLeaderboard.Result result, boolean periodsEnabled) {
        return new S2CLeaderboardPacket(Status.OK, periodsEnabled, result.period(), result.sort(),
                result.rows(), result.ranks(), result.selfRank(), result.selfRow(), result.total(),
                result.query(), result.matched());
    }

    /**
     * @return 「服务端没开排行榜」的回包
     */
    public static S2CLeaderboardPacket disabled() {
        return new S2CLeaderboardPacket(Status.DISABLED, false, LeaderboardPeriod.ALL,
                LeaderboardSort.MAXED, List.of(), null, 0, null, 0, "", 0);
    }

    /**
     * @return 「请求太频繁」的回包
     */
    public static S2CLeaderboardPacket tooFast() {
        return new S2CLeaderboardPacket(Status.TOO_FAST, false, LeaderboardPeriod.ALL,
                LeaderboardSort.MAXED, List.of(), null, 0, null, 0, "", 0);
    }

    /**
     * 写入缓冲区。
     *
     * @param buf 目标缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(this.status);
        buf.writeBoolean(this.periodsEnabled);
        buf.writeEnum(this.period);
        buf.writeEnum(this.sort);
        buf.writeVarInt(this.rows.size());
        for (LeaderboardRow row : this.rows) {
            row.write(buf);
        }
        buf.writeVarInt(this.selfRank);
        buf.writeBoolean(this.selfRow != null);
        if (this.selfRow != null) {
            this.selfRow.write(buf);
        }
        buf.writeVarInt(this.total);
        buf.writeUtf(this.query, C2SRequestLeaderboardPacket.MAX_QUERY);
        buf.writeVarInt(this.matched);
        buf.writeBoolean(this.ranks != null);
        if (this.ranks != null) {
            // 和 rows 一一对应，长度不用单独写
            for (int rank : this.ranks) {
                buf.writeVarInt(rank);
            }
        }
    }

    /**
     * 从缓冲区读取。
     *
     * @param buf 源缓冲区
     * @return 网络包
     */
    public static S2CLeaderboardPacket decode(FriendlyByteBuf buf) {
        Status status = buf.readEnum(Status.class);
        boolean periodsEnabled = buf.readBoolean();
        LeaderboardPeriod period = buf.readEnum(LeaderboardPeriod.class);
        LeaderboardSort sort = buf.readEnum(LeaderboardSort.class);
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ROWS) {
            throw new IllegalArgumentException("AlbionMastery: 排行榜包行数非法 " + size);
        }
        List<LeaderboardRow> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(LeaderboardRow.read(buf));
        }
        int selfRank = Math.max(0, buf.readVarInt());
        LeaderboardRow selfRow = buf.readBoolean() ? LeaderboardRow.read(buf) : null;
        int total = Math.max(0, buf.readVarInt());
        String query = buf.readUtf(C2SRequestLeaderboardPacket.MAX_QUERY);
        int matched = Math.max(0, buf.readVarInt());
        int[] ranks = null;
        if (buf.readBoolean()) {
            ranks = new int[size];
            for (int i = 0; i < size; i++) {
                ranks[i] = Math.max(1, buf.readVarInt());
            }
        }
        return new S2CLeaderboardPacket(status, periodsEnabled, period, sort, rows, ranks, selfRank, selfRow,
                total, query, matched);
    }

    /**
     * 主线程处理：交给客户端的排行榜界面。只会在客户端被调用。
     *
     * @param packet  网络包
     * @param context 网络上下文
     */
    public static void handle(S2CLeaderboardPacket packet, IPayloadContext context) {
        ClientLeaderboardHandler.acceptRows(packet.status, packet.periodsEnabled,
                packet.period, packet.sort, packet.rows, packet.ranks, packet.selfRank, packet.selfRow,
                packet.total, packet.query, packet.matched);
    }

    @Override
    public CustomPacketPayload.Type<S2CLeaderboardPacket> type() {
        return TYPE;
    }
}
