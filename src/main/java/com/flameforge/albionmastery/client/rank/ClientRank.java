package com.flameforge.albionmastery.client.rank;

import com.flameforge.albionmastery.client.ClientMasteryCache;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.rank.MasteryRank;

import javax.annotation.Nullable;

/**
 * 客户端算段位。
 *
 * <p>每级段位经验取服务端下发的快照，不读本机配置：看别人罗盘、看排行榜时，
 * 两边必须按同一个数换算，否则同一个人在不同客户端上段位不一样。
 */
public final class ClientRank {

    private ClientRank() {
    }

    /**
     * @return 服务端规定的每级段位经验
     */
    public static int xpPerLevel() {
        return ClientMasteryCache.snapshot().rankXpPerWeaponLevel;
    }

    /**
     * @return 本机玩家的段位经验；还没收到同步时是 0
     */
    public static long selfRankXp() {
        return ClientMasteryCache.isSynced() ? rankXpOf(ClientMasteryCache.data()) : 0L;
    }

    /**
     * @return 本机玩家的段位
     */
    public static int selfRank() {
        return MasteryRank.rankFor(selfRankXp());
    }

    /**
     * 任意一份专精数据的段位经验，用于看别人的罗盘。
     *
     * @param data 专精数据（等级已按快照曲线算好）
     * @return 段位经验
     */
    public static long rankXpOf(@Nullable MasteryData data) {
        return MasteryRank.rankXpOf(data, xpPerLevel());
    }

    /**
     * 由武器等级之和换算段位经验，用于排行榜行。
     *
     * @param levelSum 全部武器等级之和
     * @return 段位经验
     */
    public static long rankXpForLevelSum(long levelSum) {
        return MasteryRank.rankXpForLevelSum(levelSum, xpPerLevel());
    }
}
