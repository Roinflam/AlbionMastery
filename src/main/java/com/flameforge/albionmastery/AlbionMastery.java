package com.flameforge.albionmastery;

import com.flameforge.albionmastery.event.CommonEventHandler;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.event.ArsCombatHandler;
import com.flameforge.albionmastery.event.GunCombatHandler;
import com.flameforge.albionmastery.event.SlashBladeCombatHandler;
import com.flameforge.albionmastery.event.SpellCombatHandler;
import com.flameforge.albionmastery.leaderboard.MasteryLeaderboard;
import com.flameforge.albionmastery.mastery.DimensionXpBonus;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.flameforge.albionmastery.mastery.spec.SpecVisualAttributes;
import com.flameforge.albionmastery.event.SweetMagicCombatHandler;
import com.flameforge.albionmastery.compat.ftbquests.FtbQuestsCompat;

/**
 * 武器专精系统主类。
 *
 * <p>当前阶段只接入 TACZ 枪械（含全部枪包）：
 * <ul>
 *     <li>启动后自动枚举所有已安装枪包中的枪械，按枪包命名空间归为「大类」</li>
 *     <li>大类专精每级为该大类全部武器提供伤害加成</li>
 *     <li>单件武器专精每级为自身提供更高加成，并为同大类其它武器提供少量加成</li>
 *     <li>按 B 打开由中心向外扩散的专精节点界面</li>
 * </ul>
 *
 * <p>近战附属（lrtactical）与拔刀剑将以相同的 Provider 机制接入，
 * 见 {@code client.weapon.IWeaponProvider}。
 */
@Mod(AlbionMastery.MOD_ID)
public class AlbionMastery {

    /** 模组 ID。 */
    public static final String MOD_ID = "albionmastery";

    /** 日志。 */
    public static final Logger LOGGER = LoggerFactory.getLogger("AlbionMastery");

    /**
     * 模组构造：注册配置与网络通道。
     */
    public AlbionMastery() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MasteryConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, MasteryConfig.CLIENT_SPEC);

        // 模组列表里的配置按钮（Cloth Config 界面）；界面类引用客户端类，专用服务端不加载
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> com.flameforge.albionmastery.client.screen.MasteryConfigScreen::register);

        modBus.addListener(this::onConfigLoading);
        modBus.addListener(this::onConfigReloading);

        SpecVisualAttributes.register(modBus);
        NetworkHandler.register();
        // 四个来源全是可选依赖。这几个处理器的方法签名里有对应模组的事件类，
        // 没装时不能让事件总线去反射它们，所以逐个判断装了才注册；
        // 没装的来源，它的节点不会出现在树上，相关功能也一概不启用
        if (WeaponResolver.isTaczLoaded()) {
            MinecraftForge.EVENT_BUS.register(GunCombatHandler.class);
        }
        if (WeaponResolver.isSlashBladeLoaded()) {
            MinecraftForge.EVENT_BUS.register(SlashBladeCombatHandler.class);
        }
        if (WeaponResolver.isIronsLoaded()) {
            MinecraftForge.EVENT_BUS.register(SpellCombatHandler.class);
        }
        if (WeaponResolver.isArsLoaded()) {
            MinecraftForge.EVENT_BUS.register(ArsCombatHandler.class);
        }
        if (WeaponResolver.isSweetMagicLoaded()) {
            MinecraftForge.EVENT_BUS.register(SweetMagicCombatHandler.class);
        }
        if (WeaponResolver.isIronsArtificeLoaded()) {
            MinecraftForge.EVENT_BUS.register(com.flameforge.albionmastery.event.IronsArtificeCombatHandler.class);
        }
        // FTB 任务同样可选：装了就多一种「专精段位」任务，没装时入口里自己判断、不碰 FTB 的类
        FtbQuestsCompat.init();
    }

    /**
     * 配置首次加载完成，刷新服务端数值快照。
     *
     * @param event 配置加载事件
     */
    private void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getType() == ModConfig.Type.COMMON) {
            // 先把旧版本的特化数值换成新默认值，再刷新快照
            MasteryConfig.migrateSpecBalance();
            MasteryConfig.refreshServerSnapshot();
            DimensionXpBonus.reload();
        }
    }

    /**
     * 配置被重载，刷新服务端数值快照。
     *
     * <p>注意：等级曲线改变后，已有玩家的等级需要重算。这一步由
     * {@code CommonEventHandler} 在下一次访问玩家数据时兜底完成，
     * 或由管理员重进服务端触发全量同步。
     *
     * @param event 配置重载事件
     */
    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() == ModConfig.Type.COMMON) {
            // 等级上限或曲线变了，排行榜上存的「满级数」这些概况就不对了，得按新曲线重算。
            // 配置重载事件是文件监视线程发出来的，不在主线程，SavedData 不能在这里直接碰，
            // 用 server.execute 丢回主线程去做
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                MasteryConfig.migrateSpecBalance();
                MasteryConfig.refreshServerSnapshot();
                DimensionXpBonus.reload();
            } else {
                server.execute(() -> {
                    // 换快照也放到主线程，和下面的全量同步紧挨着做：在监视线程上先换掉的话，
                    // 中间插进来的一次增量冲刷会按新数值算出段位晋升，当成打怪练出来的
                    MasteryConfig.migrateSpecBalance();
                    MasteryConfig.refreshServerSnapshot();
                    DimensionXpBonus.reload();
                    // 补录表、拆分口径都可能变了，服务端那份武器目录得跟着作废，
                    // 不然管理指令还按旧口径写键（`/albionmastery config set` 那条路也会清）
                    WeaponCatalog.invalidate();
                    // 在线玩家按新曲线重算等级、拿到新的配置快照：伤害结算读的是缓存的等级，
                    // 不重算的话改了等级上限或经验曲线要等重登才生效，客户端也一直拿着旧快照
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        CommonEventHandler.syncFull(player);
                    }
                    if (!MasteryConfig.COMMON.leaderboardEnable.get()) {
                        return;
                    }
                    try {
                        MasteryLeaderboard.get(server).recomputeAll();
                    } catch (Exception exception) {
                        LOGGER.error("配置重载后重算排行榜失败", exception);
                    }
                });
            }
        }
    }
}
