package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.leaderboard.LeaderboardPeriod;
import com.flameforge.albionmastery.leaderboard.LeaderboardSort;
import com.flameforge.albionmastery.leaderboard.MasteryLeaderboard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端请求排行榜（C2S）。
 *
 * <p>只带时间范围和排序方式两个序号。返回多少行、多久能请求一次全由服务端配置说了算，
 * 客户端发得再勤也只会被限流丢掉。
 */
public class C2SRequestLeaderboardPacket {

    /** 时间范围的序号。 */
    private final int periodOrdinal;

    /** 排序方式的序号。 */
    private final int sortOrdinal;

    /**
     * 构造网络包。
     *
     * @param period 想要的时间范围
     * @param sort   想要的排序方式
     */
    public C2SRequestLeaderboardPacket(LeaderboardPeriod period, LeaderboardSort sort) {
        this.periodOrdinal = period.ordinal();
        this.sortOrdinal = sort.ordinal();
    }

    private C2SRequestLeaderboardPacket(int periodOrdinal, int sortOrdinal) {
        this.periodOrdinal = periodOrdinal;
        this.sortOrdinal = sortOrdinal;
    }

    /**
     * 写入缓冲区。
     *
     * @param buf 目标缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.periodOrdinal);
        buf.writeVarInt(this.sortOrdinal);
    }

    /**
     * 从缓冲区读取。
     *
     * @param buf 源缓冲区
     * @return 网络包
     */
    public static C2SRequestLeaderboardPacket decode(FriendlyByteBuf buf) {
        return new C2SRequestLeaderboardPacket(buf.readVarInt(), buf.readVarInt());
    }

    /**
     * 服务端处理：限流、建榜、回包。
     *
     * @param context 网络上下文
     */
    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            MinecraftServer server = player.serverLevel().getServer();
            if (!MasteryConfig.COMMON.leaderboardEnable.get()) {
                NetworkHandler.sendTo(player, S2CLeaderboardPacket.disabled());
                return;
            }
            LeaderboardPeriod period = LeaderboardPeriod.byOrdinal(this.periodOrdinal);
            LeaderboardSort sort = LeaderboardSort.byOrdinal(this.sortOrdinal);
            // 日 / 周 / 月榜只有专用服务端才开：单人和开局域网的世界一天开几次、开多久都说不准，
            // 「今天涨了多少」没什么意义。客户端会按回包里的标记把那排按钮藏起来
            boolean periodsEnabled = server.isDedicatedServer();
            if (!periodsEnabled) {
                period = LeaderboardPeriod.ALL;
            }
            MasteryLeaderboard board = MasteryLeaderboard.get(server);
            long cooldownMs = MasteryConfig.COMMON.leaderboardRequestCooldownSeconds.get() * 1000L;
            if (!board.tryAcquireRequest(player.getUUID(), MasteryLeaderboard.REQUEST_LIST, cooldownMs)) {
                NetworkHandler.sendTo(player, S2CLeaderboardPacket.tooFast());
                return;
            }
            // 先把请求者自己刷一遍：他刚打完的那波经验还没到定时刷新，不然看到的是几十秒前的自己
            board.update(player);
            MasteryLeaderboard.Result result = board.query(server, period, sort,
                    MasteryConfig.COMMON.leaderboardTopCount.get(), player.getUUID());
            NetworkHandler.sendTo(player, S2CLeaderboardPacket.ok(result, periodsEnabled));
        });
        ctx.setPacketHandled(true);
    }
}
