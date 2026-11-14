package com.flameforge.albionmastery.api;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.rank.MasteryRank;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * 段位的对外接口，给任务模组、KubeJS 脚本、其它模组读玩家段位用。
 *
 * <p>只在服务端用：段位以服务端的专精数据为准，客户端没有别人的数据。
 * 段位变化时会在 {@code NeoForge.EVENT_BUS} 上发 {@link MasteryRankChangedEvent}。
 *
 * <p>门槛与星际战甲一致，见 {@link MasteryRank}。
 */
public final class MasteryRankApi {

    private MasteryRankApi() {
    }

    /**
     * 玩家当前的段位经验。
     *
     * @param player 玩家，null 返回 0
     * @return 段位经验
     */
    public static long getRankXp(@Nullable ServerPlayer player) {
        if (player == null) {
            return 0L;
        }
        // 按经验现算等级，不读缓存：别的模组可能在本模组做全量同步之前（登录事件里）就来查
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        return MasteryRank.rankXpOf(MasteryHelper.get(player), snapshot.weaponCurve(),
                snapshot.rankXpPerWeaponLevel);
    }

    /**
     * 玩家当前的段位：0 = 无等级，1~30 = 启蒙者 … 至崇宗师，31 起是传奇 1、传奇 2 ……
     *
     * @param player 玩家，null 返回 0
     * @return 段位
     */
    public static int getRank(@Nullable ServerPlayer player) {
        return MasteryRank.rankFor(getRankXp(player));
    }

    /**
     * 达到某个段位所需的累计段位经验。
     *
     * @param rank 段位
     * @return 累计段位经验
     */
    public static long xpForRank(int rank) {
        return MasteryRank.xpForRank(rank);
    }

    /**
     * 段位经验对应的段位。
     *
     * @param rankXp 段位经验
     * @return 段位
     */
    public static int rankFor(long rankXp) {
        return MasteryRank.rankFor(rankXp);
    }

    /**
     * 段位名称（可翻译），例如「黄金苍鹰」「传奇 2」。
     *
     * @param rank 段位
     * @return 名称
     */
    public static Component rankName(int rank) {
        return MasteryRank.name(rank);
    }

    /**
     * @return 服务端配置的每级段位经验
     */
    public static int xpPerWeaponLevel() {
        return MasteryConfig.serverSnapshot().rankXpPerWeaponLevel;
    }
}
