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
 * 唯一的 C2S 包是切换特化的请求，它只带一个流派键，解锁条件与冷却全部由服务端重新校验，
 * 客户端说什么都不作数。
 */
public final class NetworkHandler {

    /** 协议版本，客户端与服务端不一致时 Forge 会直接拒绝连接。 */
    private static final String PROTOCOL_VERSION = "1";

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
