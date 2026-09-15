package com.flameforge.albionmastery.leaderboard;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * 排行榜上的一行：一名玩家的专精概况。
 *
 * <p>这里只放「排名和一眼看概况」需要的几个数字，不放整份专精数据——
 * 榜单一次发几十行，每行都带几百件武器的话一个包就上 MB 了。
 * 想看某个人的整个罗盘，再单独请求那个人的完整数据。
 *
 * @param uuid         玩家 UUID
 * @param name         玩家名（离线玩家用上次记录的名字）
 * @param online       此刻是否在线
 * @param updatedAt    这份概况最后一次刷新的时刻（毫秒时间戳）
 * @param maxed        满级武器数
 * @param levelSum     所有武器等级之和
 * @param unlocked     有等级的武器数
 * @param topLevel     练得最高的那件武器的等级，只用来给平手的人分先后
 * @param topXp        练得最高的那件武器的经验，同上
 * @param specCount    已选的流派数（制式不算）
 * @param typeMaxed    满级的武器类型（中圈）数
 * @param overallMaxed 满级的来源（内圈）数
 */
public record LeaderboardRow(UUID uuid,
                             String name,
                             boolean online,
                             long updatedAt,
                             int maxed,
                             int levelSum,
                             int unlocked,
                             int topLevel,
                             long topXp,
                             int specCount,
                             int typeMaxed,
                             int overallMaxed) {

    /** 玩家名的最大长度，正版名字不超过 16，留点余量给离线服的奇怪名字。 */
    private static final int MAX_NAME = 48;

    /**
     * 写入网络缓冲区。
     *
     * @param buf 目标缓冲区
     */
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(this.uuid);
        buf.writeUtf(this.name, MAX_NAME);
        buf.writeBoolean(this.online);
        buf.writeVarLong(this.updatedAt);
        buf.writeVarInt(this.maxed);
        buf.writeVarInt(this.levelSum);
        buf.writeVarInt(this.unlocked);
        buf.writeVarInt(this.topLevel);
        buf.writeVarLong(this.topXp);
        buf.writeVarInt(this.specCount);
        buf.writeVarInt(this.typeMaxed);
        buf.writeVarInt(this.overallMaxed);
    }

    /**
     * 从网络缓冲区读取，数字一律夹到非负。
     *
     * @param buf 源缓冲区
     * @return 一行数据
     */
    public static LeaderboardRow read(FriendlyByteBuf buf) {
        UUID uuid = buf.readUUID();
        String name = buf.readUtf(MAX_NAME);
        boolean online = buf.readBoolean();
        long updatedAt = Math.max(0L, buf.readVarLong());
        int maxed = Math.max(0, buf.readVarInt());
        int levelSum = Math.max(0, buf.readVarInt());
        int unlocked = Math.max(0, buf.readVarInt());
        int topLevel = Math.max(0, buf.readVarInt());
        long topXp = Math.max(0L, buf.readVarLong());
        int specCount = Math.max(0, buf.readVarInt());
        int typeMaxed = Math.max(0, buf.readVarInt());
        int overallMaxed = Math.max(0, buf.readVarInt());
        return new LeaderboardRow(uuid, name, online, updatedAt, maxed, levelSum, unlocked,
                topLevel, topXp, specCount, typeMaxed, overallMaxed);
    }

    /**
     * 换一个「在线」标记，其余原样。服务端建榜时在线状态是临时查出来的，不存盘。
     *
     * @param isOnline 是否在线
     * @return 新的一行
     */
    public LeaderboardRow withOnline(boolean isOnline) {
        if (isOnline == this.online) {
            return this;
        }
        return new LeaderboardRow(this.uuid, this.name, isOnline, this.updatedAt, this.maxed,
                this.levelSum, this.unlocked, this.topLevel, this.topXp,
                this.specCount, this.typeMaxed, this.overallMaxed);
    }
}
