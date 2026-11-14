package com.flameforge.albionmastery.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络通道注册。
 *
 * <p>数据流基本是单向的：服务端为唯一权威，客户端只接收专精数据用于界面显示。
 * C2S 包只有三种：切换特化（只带一个特化键，解锁条件与冷却全部由服务端重新校验）、
 * 要排行榜、要某个人的专精数据（后两种服务端会限流）。客户端说什么都不作数。
 *
 * <p>1.21.1：每个包有自己的负载 ID（{@code albionmastery:sync_mastery} 这种），不再按注册顺序编号；
 * 登记挂在 MOD 总线的 {@link RegisterPayloadHandlersEvent} 上。处理器默认在主线程执行，
 * 等同于原来的 {@code consumerMainThread}。全部包都不是可选的，对面没装、版本串不同都在握手时被拒，
 * 提示文字由 NeoForge 给出。
 */
public final class NetworkHandler {

    /**
     * 协议版本，客户端与服务端不一致时 NeoForge 会直接拒绝连接。
     *
     * <p>版本 4 的配置快照增加法术范围保护开关，且升级经验曲线翻倍。
     * 版本 5 排行榜请求 / 回包加了搜索字段，新增罗盘管理操作包。
     * 版本 10 配置快照末尾加了每级段位经验，新增段位晋升包，排行榜排序多了段位。
     * 版本 11 特化改成 4 槽 + 分级：配置快照换成新的养成规则并加单件武器倍率，全量同步加特化等级 / 突破 / 退点冷却，
     * 新增升级 / 突破 / 重置请求，选特化的槽位放到 1～4。
     * 版本 12 接入铁枪（Iron's Arms 'n Artifice）：特化数值表末尾追加弹射、追踪、人物反冲、满膛首发、
     * 弹射后命中、改装件附带伤害六项，旧端读同步包会错位。
     * 两端必须一起更新，避免旧客户端误读数据包或显示错误等级。
     *
     * <p>1.21.1 写成「基线.修订」：基线对齐 1.20.1 分支的协议号，两边一起改包格式时基线加一、修订归零；
     * 只有这个分支改包时只加修订。
     */
    private static final String PROTOCOL_VERSION = "12.0";

    private NetworkHandler() {
    }

    /**
     * 把登记挂到 MOD 总线上，在模组构造阶段调用。
     *
     * @param modBus 本模组的 MOD 总线
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(NetworkHandler::onRegisterPayloads);
    }

    /**
     * 登记全部网络包，双端都会触发。顺序照 1.20.1 的包 ID 排，方便对照。
     */
    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToClient(S2CSyncMasteryPacket.TYPE,
                StreamCodec.ofMember(S2CSyncMasteryPacket::encode, S2CSyncMasteryPacket::decode),
                S2CSyncMasteryPacket::handle);

        registrar.playToClient(S2CUpdateMasteryPacket.TYPE,
                StreamCodec.ofMember(S2CUpdateMasteryPacket::encode, S2CUpdateMasteryPacket::decode),
                S2CUpdateMasteryPacket::handle);

        registrar.playToServer(C2SSelectSpecializationPacket.TYPE,
                StreamCodec.ofMember(C2SSelectSpecializationPacket::encode, C2SSelectSpecializationPacket::decode),
                C2SSelectSpecializationPacket::handle);

        // 排行榜：两问两答，外加一条前三易主的通知
        registrar.playToServer(C2SRequestLeaderboardPacket.TYPE,
                StreamCodec.ofMember(C2SRequestLeaderboardPacket::encode, C2SRequestLeaderboardPacket::decode),
                C2SRequestLeaderboardPacket::handle);

        registrar.playToClient(S2CLeaderboardPacket.TYPE,
                StreamCodec.ofMember(S2CLeaderboardPacket::encode, S2CLeaderboardPacket::decode),
                S2CLeaderboardPacket::handle);

        registrar.playToServer(C2SRequestPlayerMasteryPacket.TYPE,
                StreamCodec.ofMember(C2SRequestPlayerMasteryPacket::encode, C2SRequestPlayerMasteryPacket::decode),
                C2SRequestPlayerMasteryPacket::handle);

        registrar.playToClient(S2CPlayerMasteryPacket.TYPE,
                StreamCodec.ofMember(S2CPlayerMasteryPacket::encode, S2CPlayerMasteryPacket::decode),
                S2CPlayerMasteryPacket::handle);

        registrar.playToClient(S2CRankChangePacket.TYPE,
                StreamCodec.ofMember(S2CRankChangePacket::encode, S2CRankChangePacket::decode),
                S2CRankChangePacket::handle);

        // 罗盘里的管理按钮
        registrar.playToServer(C2SAdminActionPacket.TYPE,
                StreamCodec.ofMember(C2SAdminActionPacket::encode, C2SAdminActionPacket::decode),
                C2SAdminActionPacket::handle);

        // 段位晋升动画
        registrar.playToClient(S2CMasteryRankUpPacket.TYPE,
                StreamCodec.ofMember(S2CMasteryRankUpPacket::encode, S2CMasteryRankUpPacket::decode),
                S2CMasteryRankUpPacket::handle);

        // 特化升级 / 突破 / 重置
        registrar.playToServer(C2SSpecProgressPacket.TYPE,
                StreamCodec.ofMember(C2SSpecProgressPacket::encode, C2SSpecProgressPacket::decode),
                C2SSpecProgressPacket::handle);
    }

    /**
     * 向服务端发包，只能在客户端调用。
     *
     * @param message 网络包
     */
    public static void sendToServer(CustomPacketPayload message) {
        PacketDistributor.sendToServer(message);
    }

    /**
     * 向指定玩家发包。
     *
     * <p>NeoForge 发包前会查对面有没有协商过这个包，没有就抛异常；别的模组用普通 {@link ServerPlayer}
     * 加假连接做的假人也会走登录、重生这些同步，所以先判一下，收不了的直接跳过。
     * 1.20.1 的 SimpleChannel 不查这个，照发不误。
     *
     * @param player  目标玩家
     * @param message 网络包
     */
    public static void sendTo(ServerPlayer player, CustomPacketPayload message) {
        if (canReceive(player, message)) {
            PacketDistributor.sendToPlayer(player, message);
        }
    }

    private static boolean canReceive(ServerPlayer player, CustomPacketPayload message) {
        if (player instanceof FakePlayer || player.connection == null) {
            return false;
        }
        try {
            return player.connection.hasChannel(message.type());
        } catch (RuntimeException e) {
            // 自己拼的假连接可能连协商信息都没有，按收不了处理
            return false;
        }
    }
}
