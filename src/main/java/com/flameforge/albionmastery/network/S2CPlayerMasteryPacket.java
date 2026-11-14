package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.AMIds;
import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.client.ClientLeaderboardHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 某名玩家的整份专精数据（S2C），分片发送。
 *
 * <p>原版给单个包定了 1 MB 的死上限（{@code ClientboundCustomPayloadPacket}），Forge 不会自动拆，
 * 超了服务端直接抛异常。一个人几千件武器的记录逼近这个数是有可能的，所以这里做两件事：
 * <ol>
 *     <li>先把 NBT 用 gzip 压一遍——键名高度重复，压完通常只剩原来的一两成；</li>
 *     <li>再按 {@value #CHUNK_SIZE} 字节切片，一片一个包，客户端收齐后拼回来解压。</li>
 * </ol>
 * 同一条通道上的包按发送顺序到达，片的顺序天然有保证，包里带序号只是为了校验和防错。
 *
 * <p>客户端收到后走和读档同一个 {@code deserializeNBT}，键校验、特化校验完全是同一套。
 */
public class S2CPlayerMasteryPacket implements CustomPacketPayload {

    /** 负载 ID。 */
    public static final CustomPacketPayload.Type<S2CPlayerMasteryPacket> TYPE =
            new CustomPacketPayload.Type<>(AMIds.of("player_mastery"));

    /** 名字最大长度。 */
    private static final int MAX_NAME = 48;

    /**
     * 每片的字节数。留够余量：1 MB 上限还要装包头、UUID、名字这些，
     * 而且 Forge 的通道 ID 也占几十字节。
     */
    private static final int CHUNK_SIZE = 900_000;

    /** 单片最多接受的字节数，只用来挡异常包。 */
    private static final int MAX_CHUNK = CHUNK_SIZE + 1024;

    /** 一次传输最多多少片：64 片 × 900 KB ≈ 55 MB 压缩后的数据，正常数据远到不了这个数。 */
    private static final int MAX_CHUNKS = 64;

    /** 传输编号自增，客户端靠它区分「这一片属于哪一次传输」。 */
    private static int transferCounter = 0;

    /** 「请求太频繁」回包用的传输号，正常传输号从 0 往上数，碰不上。 */
    public static final int TRANSFER_TOO_FAST = -1;

    private final UUID uuid;
    private final String name;
    private final boolean found;
    private final int transferId;
    private final int chunkIndex;
    private final int chunkCount;
    private final byte[] chunk;

    private S2CPlayerMasteryPacket(UUID uuid, String name, boolean found, int transferId,
                                   int chunkIndex, int chunkCount, byte[] chunk) {
        this.uuid = uuid;
        this.name = name;
        this.found = found;
        this.transferId = transferId;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.chunk = chunk;
    }

    /**
     * 把一份数据压缩、切片，做成一串包。
     *
     * @param uuid 玩家 UUID
     * @param name 玩家名
     * @param data 专精数据的 NBT
     * @return 按顺序发送的包列表；压缩失败时返回一个「没找到」的包，让客户端给个提示而不是干等
     */
    public static List<S2CPlayerMasteryPacket> of(UUID uuid, String name, CompoundTag data) {
        byte[] bytes;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            NbtIo.writeCompressed(data, out);
            bytes = out.toByteArray();
        } catch (IOException exception) {
            AlbionMastery.LOGGER.error("压缩玩家专精数据失败: {}", name, exception);
            return List.of(notFound(uuid));
        }
        int count = Math.max(1, (bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        if (count > MAX_CHUNKS) {
            // 压缩后还有几十 MB 只可能是数据坏了，不发，免得把客户端撑爆
            AlbionMastery.LOGGER.error("玩家 {} 的专精数据压缩后仍有 {} 字节，超出上限，不发送", name, bytes.length);
            return List.of(notFound(uuid));
        }
        int transferId = transferCounter++;
        List<S2CPlayerMasteryPacket> packets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from = i * CHUNK_SIZE;
            int to = Math.min(bytes.length, from + CHUNK_SIZE);
            packets.add(new S2CPlayerMasteryPacket(uuid, name, true, transferId, i, count,
                    Arrays.copyOfRange(bytes, from, to)));
        }
        return packets;
    }

    /**
     * 「没找到这个人」的回包。
     *
     * @param uuid 玩家 UUID
     * @return 网络包
     */
    /**
     * @param uuid 请求的玩家
     * @return 「请求太频繁」的回包
     */
    public static S2CPlayerMasteryPacket tooFast(UUID uuid) {
        return new S2CPlayerMasteryPacket(uuid, "", false, TRANSFER_TOO_FAST, 0, 1, new byte[0]);
    }

    public static S2CPlayerMasteryPacket notFound(UUID uuid) {
        return new S2CPlayerMasteryPacket(uuid, "", false, 0, 0, 1, new byte[0]);
    }

    /**
     * 写入缓冲区。
     *
     * @param buf 目标缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(this.uuid);
        buf.writeUtf(this.name, MAX_NAME);
        buf.writeBoolean(this.found);
        buf.writeVarInt(this.transferId);
        buf.writeVarInt(this.chunkIndex);
        buf.writeVarInt(this.chunkCount);
        buf.writeByteArray(this.chunk);
    }

    /**
     * 从缓冲区读取。
     *
     * @param buf 源缓冲区
     * @return 网络包
     */
    public static S2CPlayerMasteryPacket decode(FriendlyByteBuf buf) {
        UUID uuid = buf.readUUID();
        String name = buf.readUtf(MAX_NAME);
        boolean found = buf.readBoolean();
        int transferId = buf.readVarInt();
        int chunkIndex = buf.readVarInt();
        int chunkCount = buf.readVarInt();
        if (chunkCount < 1 || chunkCount > MAX_CHUNKS || chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("AlbionMastery: 专精数据分片序号非法 "
                    + chunkIndex + "/" + chunkCount);
        }
        byte[] chunk = buf.readByteArray(MAX_CHUNK);
        return new S2CPlayerMasteryPacket(uuid, name, found, transferId, chunkIndex, chunkCount, chunk);
    }

    /**
     * 主线程处理：交给客户端拼片。只会在客户端被调用。
     *
     * @param packet  网络包
     * @param context 网络上下文
     */
    public static void handle(S2CPlayerMasteryPacket packet, IPayloadContext context) {
        ClientLeaderboardHandler.acceptPlayerChunk(packet.uuid, packet.name, packet.found,
                packet.transferId, packet.chunkIndex, packet.chunkCount, packet.chunk);
    }

    @Override
    public CustomPacketPayload.Type<S2CPlayerMasteryPacket> type() {
        return TYPE;
    }
}
