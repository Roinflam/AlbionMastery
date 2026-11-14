package com.flameforge.albionmastery.mastery.rank;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.api.MasteryRankChangedEvent;
import com.flameforge.albionmastery.compat.ftbquests.FtbQuestsCompat;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.network.S2CMasteryRankUpPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端段位判定：每次把专精数据同步给客户端之后算一次段位，变了就通知各方。
 *
 * <ul>
 *     <li>段位经验变了：发 {@link MasteryRankChangedEvent}，让 FTB 任务立刻重查；</li>
 *     <li>段位比存档里记的「已播过」高：给本人发晋升动画，打怪练上去的再通告全服。</li>
 * </ul>
 *
 * <p>挂在同步之后而不是加经验的地方：经验的入口有击杀、命中、指令、管理界面、配置重载好几条，
 * 但最后都要走增量或全量同步，这里一处就能全部接住；同步本来就按间隔节流，算段位的开销也跟着摊薄。
 * 晋升包紧跟在同步包后面发，客户端播动画时缓存里已经是新的段位经验。
 *
 * <p>只在服务端主线程调用。
 */
public final class MasteryRankTracker {

    /**
     * 本次上线以来每名玩家上次算出的段位经验，用来判断「变没变」。
     * 不存盘：登录那一次只建基线，不算变化。
     */
    private static final Map<UUID, Long> LAST_RANK_XP = new HashMap<>();

    private MasteryRankTracker() {
    }

    /**
     * 同步完成后算一次段位。数据里的等级缓存必须已经是新的（增量路径由加经验时维护，全量路径刚重算过）。
     *
     * <p>上次判定以来有武器靠打怪升了级，这次的晋升就算打怪练出来的，通告全服；
     * 管理指令、管理界面、配置重载引起的只告诉本人。
     *
     * @param player 玩家
     * @param data   他的专精数据
     */
    public static void evaluate(ServerPlayer player, MasteryData data) {
        boolean fromGameplay = data.consumeGameplayWeaponLevelUp();
        long rankXp = MasteryRank.rankXpOf(data, MasteryConfig.serverSnapshot().rankXpPerWeaponLevel);
        int rank = MasteryRank.rankFor(rankXp);

        Long last = LAST_RANK_XP.put(player.getUUID(), rankXp);
        if (last == null || last != rankXp) {
            if (last != null) {
                postChanged(player, last, rankXp, fromGameplay);
            }
            // 登录那次也查一遍：FTB 自己的登录检查不一定排在本模组之后
            recheckQuests(player);
        }

        int seen = data.getRankSeen();
        if (seen < 0) {
            // 老存档第一次遇到段位功能：评定一次，有段位就播「段位评定」，不通告
            data.setRankSeen(rank);
            if (rank > 0) {
                NetworkHandler.sendTo(player, new S2CMasteryRankUpPacket(0, rank, true));
                player.sendSystemMessage(Component.translatable("msg.albionmastery.rank.initial",
                        rankName(rank), rank, String.format(Locale.ROOT, "%,d", rankXp)));
            }
        } else if (rank > seen) {
            data.setRankSeen(rank);
            NetworkHandler.sendTo(player, new S2CMasteryRankUpPacket(seen, rank, false));
            announce(player, rank, fromGameplay);
        } else if (rank < seen) {
            // 掉段（服主调低了等级上限、管理员清了数据）不提示，只把记录压下来，重新练上去时照样播
            data.setRankSeen(rank);
        }
    }

    /**
     * 给本人或全服发一行晋升记录。
     *
     * @param player       玩家
     * @param rank         新段位
     * @param fromGameplay 是不是打怪练出来的
     */
    private static void announce(ServerPlayer player, int rank, boolean fromGameplay) {
        MinecraftServer server = player.getServer();
        if (fromGameplay && server != null && MasteryConfig.COMMON.rankUpBroadcast.get()) {
            server.getPlayerList().broadcastSystemMessage(Component.translatable(
                    "msg.albionmastery.rank.broadcast", player.getDisplayName(), rankName(rank), rank), false);
        } else {
            player.sendSystemMessage(Component.translatable("msg.albionmastery.rank.self", rankName(rank), rank));
        }
    }

    /**
     * @param rank 段位
     * @return 按金属色染好的段位名
     */
    private static MutableComponent rankName(int rank) {
        return MasteryRank.name(rank).copy()
                .withStyle(style -> style.withColor(TextColor.fromRgb(MasteryRank.chatColor(rank))).withBold(true));
    }

    /**
     * 发段位变化事件。监听方出错只记日志：段位是附加功能，不能把同步流程一起带崩。
     */
    private static void postChanged(ServerPlayer player, long oldRankXp, long newRankXp, boolean fromGameplay) {
        try {
            NeoForge.EVENT_BUS.post(new MasteryRankChangedEvent(player, oldRankXp, newRankXp, fromGameplay));
        } catch (Exception exception) {
            AlbionMastery.LOGGER.error("段位变化事件的监听方出错: {}", player.getGameProfile().getName(), exception);
        }
    }

    /**
     * 让 FTB 任务按新的段位重查一遍，没装 FTB Quests 时什么都不做。
     */
    private static void recheckQuests(ServerPlayer player) {
        try {
            FtbQuestsCompat.recheck(player);
        } catch (Exception exception) {
            AlbionMastery.LOGGER.error("FTB 任务重查段位失败: {}", player.getGameProfile().getName(), exception);
        }
    }

    /**
     * 这名玩家最近一次同步时算出的段位经验，给需要频繁读段位的地方（任务模组的定时检查）省掉重算。
     *
     * <p>最多落后一个同步间隔；变化后的那次同步会立刻通知任务重查，用的就是新值。
     *
     * @param uuid 玩家 UUID
     * @return 段位经验；这次上线还没判定过时返回 null
     */
    @Nullable
    public static Long cachedRankXp(UUID uuid) {
        return LAST_RANK_XP.get(uuid);
    }

    /**
     * 玩家下线：丢掉他的基线，下次上线重新建。
     *
     * @param player 玩家
     */
    public static void forget(ServerPlayer player) {
        LAST_RANK_XP.remove(player.getUUID());
    }

    /**
     * 服务端关闭：清空全部基线（单人存档退出再进是同一个进程，不清会带到下一个存档里）。
     */
    public static void clear() {
        LAST_RANK_XP.clear();
    }
}
