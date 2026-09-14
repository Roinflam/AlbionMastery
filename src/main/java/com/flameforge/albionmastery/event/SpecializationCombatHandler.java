package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.SpecMoveSpeed;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


/**
 * 流派里那些<b>不是「乘在伤害上」</b>的效果，集中放在这里：
 * <ul>
 *     <li>吸血：按最终造成的伤害回血（{@link LivingDamageEvent}）；</li>
 *     <li>持有移速：主手换武器时重算移速修饰符（{@link LivingEquipmentChangeEvent}）；</li>
 *     <li>连击计数的定期清理与玩家下线清理。</li>
 * </ul>
 *
 * <p>伤害倍率类的效果（暴击、连击、各种条件倍率）留在各来源自己的战斗处理器里，
 * 因为那些必须在伤害结算<b>之前</b>改数，而这里的几个都是结算<b>之后</b>才有意义的。
 * 击杀补弹要碰 TACZ 的类，放在 {@code GunCombatHandler} 里随 TACZ 一起按需注册。
 * 这个类只用原版 / Forge 的事件，不依赖任何可选模组，可以无条件自动注册。
 *
 * <p>全部只在服务端生效：吸血改的是血量、补弹改的是物品 NBT、移速改的是属性，
 * 这三样都会自动同步到客户端，客户端不需要再算一遍。
 */
@Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID)
public final class SpecializationCombatHandler {

    private SpecializationCombatHandler() {
    }

    /**
     * 吸血：造成伤害后按比例回血。
     *
     * <p>用 {@code LivingDamageEvent} 而不是 {@code LivingHurtEvent}：前者的数值是
     * 护甲、抗性都扣完之后真正会从血条上减掉的那份，吸血按这个算才不会出现
     * 「打满甲目标只掉 1 血却回了 5 血」。优先级放最低，让别的模组先改完伤害。
     *
     * <p>武器按攻击者主手认：子弹飞出去之后玩家可能已经换了武器，这种边界情况按新武器算，
     * 无伤大雅，比为每颗子弹记一份武器快照划算得多。
     *
     * @param event 伤害事件
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDamage(LivingDamageEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0.0F) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target == player || !target.isAlive()) {
            return;
        }
        // 法术伤害按打出伤害的那个法术查，别按手里的书（一本书里几个学派都可能有）
        ResolvedWeapon weapon = null;
        if (WeaponResolver.isIronsLoaded()) {
            weapon = IronsSpellsSupport.resolveDamage(event.getSource());
        }
        SpecializationValues values = weapon != null
                ? valuesOf(player, weapon) : currentValues(player, player.getMainHandItem());
        if (values == null || values.lifeSteal() <= 0.0D) {
            return;
        }
        // 回血量按目标剩余血量封顶：一枪 200 伤害打在 20 血的怪身上，不该回 200 血的 10%
        float effective = Math.min(event.getAmount(), target.getHealth());
        float heal = (float) (effective * values.lifeSteal());
        if (heal > 0.0F && player.getHealth() < player.getMaxHealth()) {
            player.heal(heal);
        }
    }

    /**
     * 主手物品变了就重算移速修饰符。
     *
     * <p>这个事件在主手物品的 NBT 有任何变化时都会触发（枪械开一枪弹药数变了也算），
     * 所以重算那边做了「值没变就不动属性」的判断，这里不用再节流。
     *
     * @param event 装备变化事件
     */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.MAINHAND) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            SpecMoveSpeed.refresh(player);
        }
    }

    /**
     * 定期清理过期的连击记录。
     *
     * @param event 服务端 tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }
        ComboTracker.tick(event.getServer().overworld().getGameTime());
    }

    /**
     * 玩家下线：清掉他的连击记录和移速修饰符。
     *
     * @param event 下线事件
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ComboTracker.forget(player.getUUID());
            SpecMoveSpeed.remove(player);
        }
    }

    /**
     * 服务器关闭时整体清空，别让静态表攥着实体引用。
     *
     * @param event 关服事件
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ComboTracker.clear();
    }

    /**
     * 取玩家手上这件武器当前生效的流派数值。
     *
     * @param player 玩家
     * @param stack  武器
     * @return 数值；不是可专精武器或没数据时返回 null
     */
    private static SpecializationValues currentValues(ServerPlayer player, ItemStack stack) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        if (!snapshot.enableSpecialization) {
            return null;
        }
        ResolvedWeapon weapon = WeaponResolver.resolve(stack, snapshot.splitMeleeByPack);
        if (weapon == null) {
            return null;
        }
        return valuesOf(player, weapon);
    }

    /**
     * 取玩家对某件武器当前生效的流派数值。
     *
     * @param player 玩家
     * @param weapon 武器
     * @return 数值；没数据返回 null
     */
    private static SpecializationValues valuesOf(ServerPlayer player, ResolvedWeapon weapon) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        if (!snapshot.enableSpecialization) {
            return null;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return null;
        }
        return SpecializationEffects.forData(data, weapon, snapshot);
    }
}
