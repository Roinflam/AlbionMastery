package com.flameforge.albionmastery.compat.ftbquests;

import com.flameforge.albionmastery.AlbionMastery;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * FTB 任务联动的入口：多一种「专精段位」任务（{@code albionmastery:mastery_rank}）。
 *
 * <p>FTB 任务是可选依赖。这个类自己不引用任何 FTB 的类，对外的方法都先判 {@link #LOADED}
 * 再转给 {@link FtbQuestsBridge}；没装 FTB 任务时那边的类一个都不会被加载，其它地方随便调这里都安全。
 */
public final class FtbQuestsCompat {

    /** FTB 任务的模组 ID。 */
    public static final String MOD_ID = "ftbquests";

    /** FTB 任务装没装。 */
    public static final boolean LOADED = ModList.get().isLoaded(MOD_ID);

    private FtbQuestsCompat() {
    }

    /**
     * 模组构造时调用：装了 FTB 任务就登记任务类型，没装什么都不做。
     *
     * <p>登记本身推迟到通用初始化阶段的主线程任务里做：模组构造是多线程并行跑的，
     * FTB 任务自己也在构造函数里往同一张不加锁的类型表里登记，两边同时写有概率把表写坏。
     * 服务端要到服务器启动完才读任务文件，客户端进服才收到任务数据，这个时机足够早。
     *
     * @param modBus 本模组的事件总线（主类构造器拿到的那条）
     */
    public static void init(IEventBus modBus) {
        if (!LOADED) {
            return;
        }
        modBus.addListener(FtbQuestsCompat::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> FtbQuestsBridge.register());
    }

    /**
     * 玩家的段位经验变了，马上重查他所在队伍的「专精段位」任务。
     *
     * <p>只能在服务端主线程调用。没装 FTB 任务、任务文件还没载入时什么都不做。
     * 任务自己每 5 秒也会兜底查一次，这里只是让玩家升段后不用等。
     * 段位经验是在 FTB 自己处理任务的过程中变的（例如命令奖励加了经验），重查推迟到这个 tick 结束。
     *
     * @param player 段位经验变化的玩家
     */
    public static void recheck(ServerPlayer player) {
        if (!LOADED || player == null) {
            return;
        }
        try {
            FtbQuestsBridge.recheck(player);
        } catch (RuntimeException exception) {
            // 这一步挂在加经验的流程后面，别人模组里抛的异常不能把加经验本身打断
            AlbionMastery.LOGGER.warn("重查 FTB 任务的专精段位失败：{}", player.getScoreboardName(), exception);
        }
    }
}
