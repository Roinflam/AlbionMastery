package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.command.MasteryCommand;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryCapability;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryExperienceHook;
import com.flameforge.albionmastery.mastery.spec.SpecMoveSpeed;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.network.S2CSyncMasteryPacket;
import com.flameforge.albionmastery.network.S2CUpdateMasteryPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 通用（双端）事件处理。
 *
 * <p>负责：
 * <ul>
 *     <li>注册并挂载专精 Capability</li>
 *     <li>死亡重生 / 切维度时继承与重新同步专精数据</li>
 *     <li>把战斗中产生的大量小改动合并成定时增量同步</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID)
public final class CommonEventHandler {

    /** Capability 挂载时使用的 ID。 */
    private static final ResourceLocation CAP_ID = new ResourceLocation(AlbionMastery.MOD_ID, "mastery");

    /** 同步节流用的 tick 计数器。 */
    private static int tickCounter = 0;

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
        data.clearDirty();

        if (!weapons.isEmpty() || !types.isEmpty() || !overalls.isEmpty()) {
            NetworkHandler.sendTo(player, new S2CUpdateMasteryPacket(weapons, types, overalls));
        }
    }
}
