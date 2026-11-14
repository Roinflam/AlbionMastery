package com.flameforge.albionmastery.compat.ftbquests;

import dev.architectury.hooks.level.entity.PlayerHooks;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.TaskTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 真正调用 FTB 任务接口的地方。
 *
 * <p>只能经 {@link FtbQuestsCompat} 在确认装了 FTB 任务之后调用：这个类的签名和方法体里全是 FTB 的类，
 * 没装 FTB 任务时一加载就会找不到类。
 */
final class FtbQuestsBridge {

    private FtbQuestsBridge() {
    }

    /**
     * 登记「专精段位」任务类型。
     *
     * <p>图标是懒取的，只有客户端画界面时才会走进 lambda，服务端登记时不会碰到客户端的类。
     */
    static void register() {
        MasteryRankTask.type = TaskTypes.register(MasteryRankTask.TYPE_ID, MasteryRankTask::new,
                () -> RankEmblemIcon.of(MasteryRankTask.TYPE_ICON_RANK));
    }

    /**
     * 立即重查玩家所在队伍的全部「专精段位」任务。
     *
     * <p>照 FTB 自己切换维度后重查维度任务的写法：跳过假人和被锁定的队伍，
     * 在玩家上下文里对未完成、已可开始的任务逐个提交。
     * 已经在 FTB 的玩家上下文里（任务奖励引起的）就推迟到这个 tick 结束，见 {@link #deferRecheck}。
     *
     * @param player 玩家
     */
    static void recheck(ServerPlayer player) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null || PlayerHooks.isFake(player)) {
            return;
        }
        // 正处在 FTB 的玩家上下文里，多半是任务奖励里的命令改了专精等级、同步后一路调到这里。
        // 外层上下文可能是另一名玩家，不能借他的上下文提交这个队伍的任务，所以推迟到外层退出之后。
        if (file.getCurrentPlayer() != null) {
            deferRecheck(player);
            return;
        }
        // 刚登录、FTB 队伍还没建好时拿不到队伍数据，交给 FTB 自己的登录检查
        TeamData data = file.getOrCreateTeamData(player);
        if (data == null || data.isLocked()) {
            return;
        }
        List<MasteryRankTask> tasks = file.collect(MasteryRankTask.class);
        if (tasks.isEmpty()) {
            return;
        }
        file.withPlayerContext(player, () -> {
            for (MasteryRankTask task : tasks) {
                if (!data.isCompleted(task) && data.canStartTasks(task.getQuest())) {
                    task.submitTask(data, player);
                }
            }
        });
    }

    /**
     * 排进服务端的任务队列，等这个 tick 跑完、外层的 FTB 上下文退出后再重查。
     *
     * <p>到时按 UUID 重新取玩家：已经下线就不查，死亡重生过就换成新的玩家对象。
     * 到时仍在别人的上下文里（照理不会）也不再往后推，留给 5 秒一次的兜底，免得在任务队列里反复续命。
     *
     * @param player 玩家
     */
    private static void deferRecheck(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        UUID uuid = player.getUUID();
        server.tell(new TickTask(server.getTickCount(), () -> {
            ServerPlayer current = server.getPlayerList().getPlayer(uuid);
            ServerQuestFile file = ServerQuestFile.INSTANCE;
            if (current != null && file != null && file.getCurrentPlayer() == null) {
                FtbQuestsCompat.recheck(current);
            }
        }));
    }
}
