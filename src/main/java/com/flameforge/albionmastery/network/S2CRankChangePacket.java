package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.AMIds;
import com.flameforge.albionmastery.client.ClientLeaderboardHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 总榜前三易主的通知（S2C）。
 *
 * <p>不走聊天：客户端收到后弹一条和升级提示同款的横幅，带上位者的头像。
 * 本人和旁观者看到的文案不一样，由客户端按自己是不是那个人来选。
 */
public class S2CRankChangePacket implements CustomPacketPayload {

    /** 负载 ID。 */
    public static final CustomPacketPayload.Type<S2CRankChangePacket> TYPE =
            new CustomPacketPayload.Type<>(AMIds.of("rank_change"));

    /** 名字最大长度。 */
    private static final int MAX_NAME = 48;

    private final UUID newcomer;
    private final String newcomerName;
    @Nullable
    private final String displacedName;
    private final int rank;

    /**
     * 构造网络包。
     *
     * @param newcomer      新上位的人
     * @param newcomerName  他的名字
     * @param displacedName 被挤下去的人的名字，没有则为 null
     * @param rank          名次，从 1 起
     */
    public S2CRankChangePacket(UUID newcomer, String newcomerName, @Nullable String displacedName, int rank) {
        this.newcomer = newcomer;
        this.newcomerName = newcomerName;
        this.displacedName = displacedName;
        this.rank = rank;
    }

    /**
     * 写入缓冲区。
     *
     * @param buf 目标缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(this.newcomer);
        buf.writeUtf(this.newcomerName, MAX_NAME);
        buf.writeBoolean(this.displacedName != null);
        if (this.displacedName != null) {
            buf.writeUtf(this.displacedName, MAX_NAME);
        }
        buf.writeVarInt(this.rank);
    }

    /**
     * 从缓冲区读取。
     *
     * @param buf 源缓冲区
     * @return 网络包
     */
    public static S2CRankChangePacket decode(FriendlyByteBuf buf) {
        UUID newcomer = buf.readUUID();
        String newcomerName = buf.readUtf(MAX_NAME);
        String displacedName = buf.readBoolean() ? buf.readUtf(MAX_NAME) : null;
        int rank = Math.max(1, buf.readVarInt());
        return new S2CRankChangePacket(newcomer, newcomerName, displacedName, rank);
    }

    /**
     * 主线程处理：交给客户端弹横幅。只会在客户端被调用。
     *
     * @param packet  网络包
     * @param context 网络上下文
     */
    public static void handle(S2CRankChangePacket packet, IPayloadContext context) {
        ClientLeaderboardHandler.acceptRankChange(packet.newcomer, packet.newcomerName,
                packet.displacedName, packet.rank);
    }

    @Override
    public CustomPacketPayload.Type<S2CRankChangePacket> type() {
        return TYPE;
    }
}
