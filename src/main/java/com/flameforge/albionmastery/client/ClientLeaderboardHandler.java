package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.client.screen.LeaderboardScreen;
import com.flameforge.albionmastery.client.screen.MasteryScreen;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.leaderboard.LeaderboardPeriod;
import com.flameforge.albionmastery.leaderboard.LeaderboardRow;
import com.flameforge.albionmastery.leaderboard.LeaderboardSort;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.network.S2CLeaderboardPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * 排行榜相关网络包在客户端的落点。
 *
 * <p>两种包都只在排行榜界面还开着的时候才处理：玩家点完刷新又把界面关了，
 * 回包晚到时不该突然弹一个界面出来。
 *
 * <p>某个人的整份数据是分片来的，这里负责把片攒起来，收齐了再解压、解析、开界面。
 */
public final class ClientLeaderboardHandler {

    /** 正在拼的那次传输的编号，-1 表示没有。同一时间只可能在等一个人的数据。 */
    private static int transferId = -1;

    /** 已经收到的片数。 */
    private static int received;

    /** 攒到一起的压缩字节。 */
    private static ByteArrayOutputStream pending;

    private ClientLeaderboardHandler() {
    }

    /**
     * 收到榜单，交给正开着的排行榜界面。
     *
     * @param status         回包状态
     * @param periodsEnabled 这个服务端有没有日 / 周 / 月榜
     * @param period         服务端按哪个时间范围算的
     * @param sort           服务端按哪种方式排的
     * @param rows           前若干名
     * @param selfRank       自己的名次，0 表示没上榜
     * @param selfRow        自己那一行，没上榜时为 null
     * @param total          榜上总人数
     */
    public static void acceptRows(S2CLeaderboardPacket.Status status, boolean periodsEnabled,
                                  LeaderboardPeriod period, LeaderboardSort sort,
                                  List<LeaderboardRow> rows, int selfRank,
                                  @Nullable LeaderboardRow selfRow, int total) {
        if (Minecraft.getInstance().screen instanceof LeaderboardScreen screen) {
            screen.onRowsReceived(status, periodsEnabled, period, sort, rows, selfRank, selfRow, total);
        }
    }

    /**
     * 收到某名玩家数据的一片。
     *
     * @param uuid       玩家 UUID
     * @param name       玩家名
     * @param found      服务端有没有这个人的数据
     * @param transfer   传输编号
     * @param chunkIndex 这一片的序号
     * @param chunkCount 总片数
     * @param chunk      这一片的字节
     */
    public static void acceptPlayerChunk(UUID uuid, String name, boolean found, int transfer,
                                         int chunkIndex, int chunkCount, byte[] chunk) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof LeaderboardScreen screen)) {
            reset();
            return;
        }
        if (!found) {
            reset();
            screen.onPlayerMissing(uuid);
            return;
        }
        // 新的一次传输：不管上一次拼到哪了，从头开始
        if (transfer != transferId || pending == null) {
            transferId = transfer;
            received = 0;
            pending = new ByteArrayOutputStream(chunk.length * chunkCount);
        }
        // 片错序或者重复只可能是包被动过手脚，整次作废
        if (chunkIndex != received) {
            AlbionMastery.LOGGER.warn("专精数据分片错序：期望 {} 收到 {}，丢弃本次传输", received, chunkIndex);
            reset();
            screen.onPlayerMissing(uuid);
            return;
        }
        pending.write(chunk, 0, chunk.length);
        received++;
        if (received < chunkCount) {
            return;
        }
        byte[] bytes = pending.toByteArray();
        reset();

        CompoundTag data;
        try {
            data = NbtIo.readCompressed(new ByteArrayInputStream(bytes));
        } catch (IOException exception) {
            AlbionMastery.LOGGER.error("解压玩家专精数据失败: {}", name, exception);
            screen.onPlayerMissing(uuid);
            return;
        }
        openPlayer(minecraft, screen, uuid, name, data);
    }

    /**
     * 数据收齐了，解析并打开他的罗盘（只读）。
     *
     * @param minecraft 客户端实例
     * @param screen    正开着的排行榜界面，作为上一层
     * @param uuid      玩家 UUID
     * @param name      玩家名
     * @param data      专精数据的 NBT
     */
    private static void openPlayer(Minecraft minecraft, LeaderboardScreen screen, UUID uuid,
                                   String name, CompoundTag data) {
        // 走和读档同一条路：键校验、流派校验都在 deserializeNBT 里，
        // 再按服务端下发的曲线把等级算出来，界面上的数字就和他本人看到的一样
        MasteryData parsed = new MasteryData();
        parsed.deserializeNBT(data);
        MasteryConfig.Snapshot snapshot = ClientMasteryCache.snapshot();
        parsed.recomputeLevels(snapshot.weaponCurve(), snapshot.typeCurve(), snapshot.overallCurve());
        parsed.clearDirty();
        screen.onPlayerLoaded(uuid);
        // 前十的人名字后面挂上徽章：点进全服第一的罗盘，标题上就写着「全服第一」
        MutableComponent owner = Component.literal(name);
        Component badge = screen.badgeOf(uuid);
        if (badge != null) {
            owner = owner.append(Component.literal(" \u00B7 ")).append(badge);
        }
        minecraft.setScreen(new MasteryScreen(parsed, owner, screen));
    }

    /**
     * 收到总榜前三易主的通知，弹一条和升级同款的横幅。
     *
     * @param newcomer      新上位的人
     * @param newcomerName  他的名字
     * @param displacedName 被挤下去的人的名字，没有则为 null
     * @param rank          名次，从 1 起
     */
    public static void acceptRankChange(UUID newcomer, String newcomerName, @Nullable String displacedName, int rank) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean self = minecraft.player != null && minecraft.player.getUUID().equals(newcomer);
        Component rankWord = Component.translatable("msg.albionmastery.leaderboard.rank_word_" + rank);
        Component detail;
        if (self) {
            detail = Component.translatable("msg.albionmastery.leaderboard.you_reached", rankWord);
        } else if (displacedName == null) {
            detail = Component.translatable("msg.albionmastery.leaderboard.reached", newcomerName, rankWord);
        } else {
            detail = Component.translatable("msg.albionmastery.leaderboard.replaced", newcomerName,
                    displacedName, rankWord);
        }
        // 标题就是榜单上那块徽章的字：「全服第一」
        Component title = Component.translatable("gui.albionmastery.leaderboard.badge_" + rank,
                Component.translatable(LeaderboardPeriod.ALL.labelKey()));
        MasteryToastManager.pushRank(title, detail, newcomer, newcomerName, self);
    }

    /**
     * 丢掉拼到一半的传输。
     */
    private static void reset() {
        transferId = -1;
        received = 0;
        pending = null;
    }
}
