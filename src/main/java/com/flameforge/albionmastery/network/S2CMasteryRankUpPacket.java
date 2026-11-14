package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.AMIds;
import com.flameforge.albionmastery.client.rank.RankCeremony;
import com.flameforge.albionmastery.mastery.rank.MasteryRank;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 段位晋升（S2C）：让客户端在屏幕正上方播晋升动画。
 *
 * <p>什么时候播由服务端决定（存档里记着播到了哪个段位），客户端只管演。
 * 段位经验本身不在包里：客户端从自己的专精缓存按快照里的每级段位经验现算，
 * 这个包总是紧跟在同步包后面发，缓存已经是新的了。
 */
public class S2CMasteryRankUpPacket implements CustomPacketPayload {

    /** 负载 ID。 */
    public static final CustomPacketPayload.Type<S2CMasteryRankUpPacket> TYPE =
            new CustomPacketPayload.Type<>(AMIds.of("rank_up"));

    private final int oldRank;
    private final int newRank;
    private final boolean initial;

    /**
     * @param oldRank 晋升前的段位
     * @param newRank 晋升后的段位
     * @param initial 是不是第一次评定（老玩家装上段位功能后的第一次登录）
     */
    public S2CMasteryRankUpPacket(int oldRank, int newRank, boolean initial) {
        this.oldRank = oldRank;
        this.newRank = newRank;
        this.initial = initial;
    }

    /**
     * 写入缓冲区。
     *
     * @param buf 目标缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.oldRank);
        buf.writeVarInt(this.newRank);
        buf.writeBoolean(this.initial);
    }

    /**
     * 从缓冲区读取。
     *
     * @param buf 源缓冲区
     * @return 网络包
     */
    public static S2CMasteryRankUpPacket decode(FriendlyByteBuf buf) {
        int oldRank = Math.max(0, Math.min(MasteryRank.MAX_RANK, buf.readVarInt()));
        int newRank = Math.max(0, Math.min(MasteryRank.MAX_RANK, buf.readVarInt()));
        boolean initial = buf.readBoolean();
        return new S2CMasteryRankUpPacket(oldRank, newRank, initial);
    }

    /**
     * 主线程处理：交给客户端排队播放。只会在客户端被调用。
     *
     * @param packet  网络包
     * @param context 网络上下文
     */
    public static void handle(S2CMasteryRankUpPacket packet, IPayloadContext context) {
        RankCeremony.enqueue(packet.oldRank, packet.newRank, packet.initial);
    }

    @Override
    public CustomPacketPayload.Type<S2CMasteryRankUpPacket> type() {
        return TYPE;
    }
}
