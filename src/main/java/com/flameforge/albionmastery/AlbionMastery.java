package com.flameforge.albionmastery;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.event.ArsCombatHandler;
import com.flameforge.albionmastery.event.GunCombatHandler;
import com.flameforge.albionmastery.event.SlashBladeCombatHandler;
import com.flameforge.albionmastery.event.SpellCombatHandler;
import com.flameforge.albionmastery.mastery.DimensionXpBonus;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        modBus.addListener(this::onConfigLoading);
        modBus.addListener(this::onConfigReloading);

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
    }

    /**
     * 配置首次加载完成，刷新服务端数值快照。
     *
     * @param event 配置加载事件
     */
    private void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getType() == ModConfig.Type.COMMON) {
            MasteryConfig.refreshServerSnapshot();
            DimensionXpBonus.reload();
        }
    }

    /**
     * 配置被重载，刷新服务端数值快照。
     *
     * <p>注意：等级曲线改变后，已有玩家的等级需要重算。这一步由
     * {@code CommonEventHandler} 在下一次访问玩家数据时兜底完成，
     * 或由管理员重进服务器触发全量同步。
     *
     * @param event 配置重载事件
     */
    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() == ModConfig.Type.COMMON) {
            MasteryConfig.refreshServerSnapshot();
            DimensionXpBonus.reload();
        }
    }
}
