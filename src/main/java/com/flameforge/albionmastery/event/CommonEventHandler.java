package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.command.MasteryCommand;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.leaderboard.MasteryLeaderboard;
import com.flameforge.albionmastery.mastery.MasteryCapability;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryExperienceHook;
import com.flameforge.albionmastery.mastery.spec.SpecMoveSpeed;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.network.S2CSyncMasteryPacket;
import com.flameforge.albionmastery.network.S2CUpdateMasteryPacket;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;

import java.util.HashMap;
import java.util.Map;
import com.flameforge.albionmastery.mastery.spec.SpecVisualAttributes;
import com.flameforge.albionmastery.weapon.compat.BowSupport;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import com.flameforge.albionmastery.weapon.compat.SweetMagicSupport;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.mastery.spec.MeleeSwingTracker;
import net.minecraftforge.event.entity.player.AttackEntityEvent;

/**
 * 通用（双端）事件处理。
 *
 * <p>负责：
 * <ul>
 *     <li>注册并挂载专精 Capability</li>
 *     <li>死亡重生 / 切维度时继承与重新同步专精数据</li>
 *     <li>把战斗中产生的大量小改动合并成定时增量同步</li>
 *     <li>在登录 / 登出 / 定时刷新时把玩家数据记进全局排行榜</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID)
public final class CommonEventHandler {

    /** Capability 挂载时使用的 ID。 */
    private static final ResourceLocation CAP_ID = new ResourceLocation(AlbionMastery.MOD_ID, "mastery");

    /** 同步节流用的 tick 计数器。 */
    private static int tickCounter = 0;

    /** 排行榜定时刷新用的 tick 计数器。 */
    private static int leaderboardTickCounter = 0;

    private CommonEventHandler() {
    }

    /**
     * MOD 总线上的事件处理。
     *
     * <p>{@code RegisterCapabilitiesEvent} 是在 MOD 总线上触发的，与本类其余
     * FORGE 总线事件不同，所以单独拆成一个订阅类。
     */
    @Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {

        private ModBus() {
        }

        /**
         * 注册专精 Capability。
         *
         * @param event 注册事件
         */
        @SubscribeEvent
        public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
            event.register(MasteryData.class);
        }

        /**
         * 给玩家加上「动作该放多快」的几个属性。
         *
         * @param event 属性补充事件
         */
        @SubscribeEvent
        public static void onAttributeModification(EntityAttributeModificationEvent event) {
            SpecVisualAttributes.onAttributeModification(event);
        }
    }

    /**
     * 开始拉弓 / 上弦：按真正在用的那把弓刷新拉弓倍速。
     *
     * <p>上面那条按主手算，副手也能拉弓，所以开始使用的那一刻再按这把弓重挂一次。
     *
     * @param event 开始使用事件
     */
    @SubscribeEvent
    public static void onUseItemStart(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SpecMoveSpeed.refreshDrawSpeed(player, event.getItem());
        }
    }

    /**
     * 停止拉弓：倍速按主手重算回去。
     *
     * @param event 停止使用事件
     */
    @SubscribeEvent
    public static void onUseItemStop(LivingEntityUseItemEvent.Stop event) {
        if (event.getEntity() instanceof ServerPlayer player
                && BowSupport.isBowOrCrossbow(event.getItem())) {
            SpecMoveSpeed.refresh(player);
        }
    }

    /**
     * 每 tick 末尾兜底清一次甜蜜魔法的施法窗口。
     *
     * <p>窗口本该在施法方法返回时关掉，可一旦施法链路里有谁抛了异常，那一步就被跳过了，
     * 窗口会一直开着：之后所有人的法力消耗、冷却都按这个人的特化算，他射的箭还会被写上魔法归属。
     * 窗口的生命周期本来就不跨 tick，每 tick 末清一次是完全安全的兜底。
     *
     * @param event tick 事件
     */
    @SubscribeEvent
    public static void onServerTickEndSweetMagic(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && WeaponResolver.isSweetMagicLoaded()) {
            SweetMagicSupport.endCast();
        }
    }

    /**
     * 把专精 Capability 挂到玩家实体上。
     *
     * @param event 挂载事件
     */
    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            event.addCapability(CAP_ID, new MasteryCapability.Provider());
        }
    }

    /**
     * 注册调试 / 管理指令。
     *
     * @param event 指令注册事件
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MasteryCommand.register(event.getDispatcher());
    }

    /**
     * 数据包重载（{@code /reload}）后丢掉服务端武器清单的缓存：拔刀剑的定义就在数据包里。
     *
     * @param event 数据包同步事件，重载完成后会对全体玩家触发一次
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            WeaponCatalog.invalidate();
        }
    }

    /**
     * 服务端关闭时丢掉武器清单缓存，别让单人世界退出后还攥着上一个服务端。
     *
     * @param event 服务端已关闭事件
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WeaponCatalog.invalidate();
    }

    /**
     * 玩家死亡重生 / 跨维度传送时，把旧实体上的专精数据搬到新实体上。
     *
     * @param event 克隆事件
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player original = event.getOriginal();
        // 必须先 revive 才能读取到旧实体上的 Capability
        original.reviveCaps();
        MasteryData oldData = MasteryHelper.get(original);
        MasteryData newData = MasteryHelper.get(event.getEntity());
        if (oldData != null && newData != null) {
            newData.copyFrom(oldData);
        }
        original.invalidateCaps();
    }

    /**
     * 玩家登录：重算等级并做一次全量同步。
     *
     * @param event 登录事件
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncFull(player);
            // 移速修饰符不存盘，登录后按当前主手重挂一次
            SpecMoveSpeed.refresh(player);
            // 登录就记一笔：名字可能改过，而且没上榜的老玩家第一次装上本版本也得进榜
            recordLeaderboard(player);
        }
    }

    /**
     * 玩家登出：把最后的数据记进排行榜，并清掉他的临时状态。
     *
     * <p>这一步是「下线的人也在榜上」的关键：实体马上就没了，现在不记就得等他下次上线。
     *
     * @param event 登出事件
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            recordLeaderboard(player);
            MeleeSwingTracker.forget(player);
        }
    }

    /**
     * 记下这一 tick 玩家真正点的是谁。
     *
     * <p>这个事件只对玩家亲手点中的那个目标触发，横扫余伤和模组的溅射都不会触发它，
     * 正好用来把「一次挥砍」和「顺带扫到的」分开（见 {@link MeleeSwingTracker}）。
     *
     * @param event 攻击事件
     */
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MeleeSwingTracker.mark(player, event.getTarget());
        }
    }

    /**
     * 立刻把一名玩家记进排行榜并清掉他的临时状态（限流、待刷新标记），出错只记日志不影响正常游戏。
     *
     * <p>总开关关着就什么都不做，连存盘文件都不会创建。
     *
     * @param player 玩家
     */
    private static void recordLeaderboard(ServerPlayer player) {
        if (!MasteryConfig.COMMON.leaderboardEnable.get()) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        try {
            MasteryLeaderboard board = MasteryLeaderboard.get(server);
            board.update(player);
            board.forgetSession(player.getUUID());
        } catch (Exception exception) {
            // 排行榜是附加功能，它出问题不能把登录 / 登出流程一起带崩
            AlbionMastery.LOGGER.error("写入排行榜失败: {}", player.getGameProfile().getName(), exception);
        }
    }

    /**
     * 玩家重生：重新同步。
     *
     * @param event 重生事件
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncFull(player);
            // 重生和换维度都会得到一个新的玩家实体，属性上的修饰符跟着没了，要重挂
            SpecMoveSpeed.refresh(player);
        }
    }

    /**
     * 玩家切维度：重新同步。
     *
     * @param event 切维度事件
     */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncFull(player);
            SpecMoveSpeed.refresh(player);
        }
    }

    /**
     * 服务端 tick 末尾统一冲刷增量同步。
     *
     * <p>放在 END 阶段、且按配置的间隔节流，是为了把霰弹枪 / 高射速武器在同一 tick 内
     * 产生的几十次经验变化合并成一个包，避免网络抖动。
     *
     * @param event tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 兜底发放没被 Mixin 接手的击杀奖励。放在节流之前每 tick 都跑，
        // 暂存表基本永远是空的，也就不会攥着过期的玩家引用
        MasteryExperienceHook.flushPending();

        int interval = MasteryConfig.COMMON.syncIntervalTicks.get();
        if (++tickCounter < interval) {
            return;
        }
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            flushDirty(player);
        }

        // 排行榜的定时刷新走另一个更慢的计数器：它要序列化整份数据，没必要跟同步包一个节奏
        if (MasteryConfig.COMMON.leaderboardEnable.get()) {
            int boardInterval = MasteryConfig.COMMON.leaderboardUpdateIntervalSeconds.get() * 20;
            leaderboardTickCounter += interval;
            if (leaderboardTickCounter >= boardInterval) {
                leaderboardTickCounter = 0;
                try {
                    MasteryLeaderboard.get(server).flushChanged(server);
                } catch (Exception exception) {
                    AlbionMastery.LOGGER.error("排行榜定时刷新失败", exception);
                }
            }
        }
    }

    /**
     * 对单个玩家做一次全量同步（会先按当前曲线重算等级）。
     *
     * @param player 目标玩家
     */
    public static void syncFull(ServerPlayer player) {
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        data.recomputeLevels(snapshot.weaponCurve(), snapshot.typeCurve(), snapshot.overallCurve());
        data.clearDirty();
        NetworkHandler.sendTo(player, S2CSyncMasteryPacket.of(data, snapshot));
        markLeaderboardChanged(player);
        // 等级、特化或配置变了，特化槽可能刚解锁 / 刚失效：挂在玩家属性上的移速、攻速、攻击距离跟着重算，
        // 不然要等换一次主手才生效
        SpecMoveSpeed.refresh(player);
    }

    /**
     * 给排行榜打个「这个人的数据变过」的标记，下次定时刷新时记进去。
     *
     * <p>只是往一个集合里塞个 UUID，热路径上也不心疼。
     *
     * @param player 玩家
     */
    private static void markLeaderboardChanged(ServerPlayer player) {
        if (!MasteryConfig.COMMON.leaderboardEnable.get()) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server != null) {
            MasteryLeaderboard.get(server).markChanged(player.getUUID());
        }
    }

    /**
     * 冲刷单个玩家的增量改动。
     *
     * @param player 目标玩家
     */
    private static void flushDirty(ServerPlayer player) {
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }
        if (data.isNeedFullSync()) {
            syncFull(player);
            return;
        }
        if (!data.hasDirty()) {
            return;
        }

        Map<String, S2CSyncMasteryPacket.WeaponSync> weapons = new HashMap<>();
        for (String key : data.getDirtyWeapons()) {
            String typeKey = data.getWeaponTypeKey(key);
            weapons.put(key, new S2CSyncMasteryPacket.WeaponSync(
                    data.getWeaponXp(key), typeKey == null ? "" : typeKey));
        }
        Map<String, Long> types = new HashMap<>();
        for (String key : data.getDirtyTypes()) {
            types.put(key, data.getTypeXp(key));
        }
        Map<String, Long> overalls = new HashMap<>();
        for (String key : data.getDirtyOveralls()) {
            overalls.put(key, data.getOverallXp(key));
        }
        // 类型或来源的等级涨了，可能正好跨过特化槽的门槛；值没变时 refresh 什么都不做
        boolean slotsMayChange = !types.isEmpty() || !overalls.isEmpty();
        data.clearDirty();
        if (slotsMayChange) {
            SpecMoveSpeed.refresh(player);
        }

        if (!weapons.isEmpty() || !types.isEmpty() || !overalls.isEmpty()) {
            NetworkHandler.sendTo(player, new S2CUpdateMasteryPacket(weapons, types, overalls));
            markLeaderboardChanged(player);
        }
    }
}
