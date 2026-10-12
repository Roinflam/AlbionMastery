package com.flameforge.albionmastery.api;

import com.flameforge.albionmastery.mastery.rank.MasteryRank;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * 玩家的段位经验变了（只在服务端、{@code MinecraftForge.EVENT_BUS} 上发）。
 *
 * <p>段位经验只在武器升级（或被管理员改等级、服主改曲线）时变化，所以这个事件很稀疏，
 * 监听方可以放心地在里面做查询任务、发奖励之类的事。段位没变、只是段位经验变了也会发，
 * 只关心晋升的用 {@link #isRankUp()} 过滤。
 *
 * <p>登录时只建立基线，不发事件；离线期间发生的变化（例如服主调了等级上限）不会补发。
 * 不可取消。
 */
public class MasteryRankChangedEvent extends PlayerEvent {

    private final int oldRank;
    private final int newRank;
    private final long oldRankXp;
    private final long newRankXp;
    private final boolean fromGameplay;

    /**
     * @param player       玩家
     * @param oldRankXp    变化前的段位经验
     * @param newRankXp    变化后的段位经验
     * @param fromGameplay 是不是打怪练出来的（false = 管理指令、改配置等）
     */
    public MasteryRankChangedEvent(ServerPlayer player, long oldRankXp, long newRankXp, boolean fromGameplay) {
        super(player);
        this.oldRankXp = oldRankXp;
        this.newRankXp = newRankXp;
        this.oldRank = MasteryRank.rankFor(oldRankXp);
        this.newRank = MasteryRank.rankFor(newRankXp);
        this.fromGameplay = fromGameplay;
    }

    @Override
    public ServerPlayer getEntity() {
        return (ServerPlayer) super.getEntity();
    }

    /**
     * @return 变化前的段位
     */
    public int getOldRank() {
        return this.oldRank;
    }

    /**
     * @return 变化后的段位
     */
    public int getNewRank() {
        return this.newRank;
    }

    /**
     * @return 变化前的段位经验
     */
    public long getOldRankXp() {
        return this.oldRankXp;
    }

    /**
     * @return 变化后的段位经验
     */
    public long getNewRankXp() {
        return this.newRankXp;
    }

    /**
     * @return 段位升了
     */
    public boolean isRankUp() {
        return this.newRank > this.oldRank;
    }

    /**
     * @return 是不是打怪练出来的；管理指令、管理界面、服主改配置引起的变化返回 false
     */
    public boolean isFromGameplay() {
        return this.fromGameplay;
    }
}
