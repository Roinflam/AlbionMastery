// 路径：src/main/java/com/flameforge/albionmastery/network/NetworkHandler.java
package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.AlbionMastery;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 网络通道注册。
 *
 * <p>数据流基本是单向的：服务端为唯一权威，客户端只接收专精数据用于界面显示。
 * C2S 包只有三种：切换特化（只带一个流派键，解锁条件与冷却全部由服务端重新校验）、
 * 要排行榜、要某个人的专精数据（后两种服务端会限流）。客户端说什么都不作数。
 */
public final class NetworkHandler {

    /**
     * 协议版本，客户端与服务端不一致时 Forge 会直接拒绝连接。
     *
     * <p>加了排行榜的四个包之后升到 2：老版本客户端收到不认识的包 ID 会直接断线报错，
     * 不如在进服时就明确提示版本不匹配。
     */
    private static final String PROTOCOL_VERSION = "3";

    /** 主通道。 */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AlbionMastery.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    /** 包 ID 自增计数器。 */
    private static int packetId = 0;

    private NetworkHandler() {
    }

    /**
     * 注册所有网络包，在模组构造阶段调用。
     */
    public static void register() {
        CHANNEL.messageBuilder(S2CSyncMasteryPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CSyncMasteryPacket::encode)
                .decoder(S2CSyncMasteryPacket::decode)
                .consumerMainThread(S2CSyncMasteryPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CUpdateMasteryPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CUpdateMasteryPacket::encode)
                .decoder(S2CUpdateMasteryPacket::decode)
                .consumerMainThread(S2CUpdateMasteryPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SSelectSpecializationPacket.class, packetId++,
                        NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SSelectSpecializationPacket::encode)
                .decoder(C2SSelectSpecializationPacket::decode)
                .consumerMainThread(C2SSelectSpecializationPacket::handle)
                .add();

        // 排行榜：两问两答，外加一条前三易主的通知。新包一律追加在末尾，前面的 ID 不动
        CHANNEL.messageBuilder(C2SRequestLeaderboardPacket.class, packetId++,
                        NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SRequestLeaderboardPacket::encode)
                .decoder(C2SRequestLeaderboardPacket::decode)
                .consumerMainThread(C2SRequestLeaderboardPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CLeaderboardPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CLeaderboardPacket::encode)
                .decoder(S2CLeaderboardPacket::decode)
                .consumerMainThread(S2CLeaderboardPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SRequestPlayerMasteryPacket.class, packetId++,
                        NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SRequestPlayerMasteryPacket::encode)
                .decoder(C2SRequestPlayerMasteryPacket::decode)
                .consumerMainThread(C2SRequestPlayerMasteryPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CPlayerMasteryPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CPlayerMasteryPacket::encode)
                .decoder(S2CPlayerMasteryPacket::decode)
                .consumerMainThread(S2CPlayerMasteryPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CRankChangePacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CRankChangePacket::encode)
                .decoder(S2CRankChangePacket::decode)
                .consumerMainThread(S2CRankChangePacket::handle)
                .add();
    }

    /**
     * 向服务端发包，只能在客户端调用。
     *
     * @param message 网络包
     */
    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }

    /**
     * 向指定玩家发包。
     *
     * @param player  目标玩家
     * @param message 网络包
     */
    public static void sendTo(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}


