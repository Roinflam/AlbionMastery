package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.leaderboard.MasteryLeaderboard;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端请求某名玩家的整份专精数据（C2S），用来在本地画出他的罗盘。
 *
 * <p>目标用 UUID 指定，服务端不认识这个人时回一个「没找到」，客户端提示一下就完事。
 */
public class C2SRequestPlayerMasteryPacket {

    /** 目标玩家的 UUID。 */
    private final UUID target;

    /**
     * 构造网络包。
     *
     * @param target 目标玩家的 UUID
     */
    public C2SRequestPlayerMasteryPacket(UUID target) {
        this.target = target;
    }

    /**
     * 写入缓冲区。
     *
     * @param buf 目标缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(this.target);
    }

    /**
     * 从缓冲区读取。
     *
     * @param buf 源缓冲区
     * @return 网络包
     */
    public static C2SRequestPlayerMasteryPacket decode(FriendlyByteBuf buf) {
        return new C2SRequestPlayerMasteryPacket(buf.readUUID());
    }

    /**
     * 服务端处理：限流、取数据、回包。
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
            if (!MasteryConfig.COMMON.leaderboardEnable.get()) {
                // 排行榜没开就不该有人发这个包，多半是客户端版本不一致，当没找到处理
                NetworkHandler.sendTo(player, S2CPlayerMasteryPacket.notFound(this.target));
                return;
            }
            MinecraftServer server = player.serverLevel().getServer();
            MasteryLeaderboard board = MasteryLeaderboard.get(server);
            long cooldownMs = MasteryConfig.COMMON.leaderboardRequestCooldownSeconds.get() * 1000L;
            if (!board.tryAcquireRequest(player.getUUID(), MasteryLeaderboard.REQUEST_PLAYER, cooldownMs)) {
                // 服务端的冷却可以配得比客户端长，被拒时得回一声，不然客户端会一直显示「读取中」直到超时
                NetworkHandler.sendTo(player, S2CPlayerMasteryPacket.tooFast(this.target));
                return;
            }
            CompoundTag data = board.dataOf(server, this.target);
            String name = board.nameOf(server, this.target);
            if (data == null || name == null) {
                NetworkHandler.sendTo(player, S2CPlayerMasteryPacket.notFound(this.target));
                return;
            }
            // 学习点是管理员给某个人单独设的倍率，不是他练出来的东西，别给别人看
            data.remove("Study");
            // 压缩后分片，一片一个包，顺序发出去
            for (S2CPlayerMasteryPacket packet : S2CPlayerMasteryPacket.of(this.target, name, data)) {
                NetworkHandler.sendTo(player, packet);
            }
        });
        ctx.setPacketHandled(true);
    }
}
