package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.FrenzyTracker;
import com.flameforge.albionmastery.mastery.spec.SpecKnockback;
import com.flameforge.albionmastery.mastery.spec.SpecMoveSpeed;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.mastery.spec.SpecializationManager;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.flameforge.albionmastery.event.damage.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import com.flameforge.albionmastery.event.damage.LivingHurtEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.Map;
import java.util.WeakHashMap;
import com.flameforge.albionmastery.mastery.spec.DamageAttribution;
import javax.annotation.Nullable;
import com.flameforge.albionmastery.mastery.spec.MeleeSwingTracker;


import net.neoforged.fml.common.EventBusSubscriber;
/**
 * 特化里那些<b>不是「乘在伤害上」</b>的效果，集中放在这里：
 * <ul>
 *     <li>吸血：按最终造成的伤害回血（{@link LivingDamageEvent}）；</li>
 *     <li>持有移速：主手换武器时重算移速修饰符（{@link LivingEquipmentChangeEvent}）；</li>
 *     <li>连击计数的定期清理与玩家下线清理；</li>
 *     <li>击退倍率（{@link LivingKnockBackEvent}）和手持武器时自己受到的伤害倍率；</li>
 *     <li>命中效果（点燃、缓慢、虚弱）；击杀回血、嗜战叠层（{@link LivingDeathEvent}）。</li>
 * </ul>
 *
 * <p>伤害倍率类的效果（暴击、连击、各种条件倍率）留在各来源自己的战斗处理器里，
 * 因为那些必须在伤害结算<b>之前</b>改数，而这里的几个都是结算<b>之后</b>才有意义的。
 * 击杀补弹要碰 TACZ 的类，放在 {@code GunCombatHandler} 里随 TACZ 一起按需注册。
 * 这个类只用原版 / NeoForge 的事件，不依赖任何可选模组，可以无条件自动注册。
 *
 * <p>全部只在服务端生效：吸血改的是血量、补弹改的是物品 NBT、移速改的是属性，
 * 这三样都会自动同步到客户端，客户端不需要再算一遍。
 */
@EventBusSubscriber(modid = AlbionMastery.MOD_ID)
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
     * <p>武器按伤害的来路认（见 {@link DamageAttribution}）：法术按法术、箭按射它的弓、子弹按开枪的枪、
     * 挥砍按主手的近战武器。
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
        // 横扫余伤 / 溅射不触发命中特效：一刀扫五个怪不该点燃五次、吸五份血
        if (DamageAttribution.isSplashHit(player, event.getSource(), target)) {
            return;
        }
        // 按伤害本身的来路认武器（法术、箭、子弹、挥砍），认不出的（荆棘、爆炸之类）不触发任何特化效果
        SpecializationValues values = valuesFor(player, event.getSource());
        if (values == null) {
            return;
        }
        applyOnHitEffects(player, target, values);
        if (values.lifeSteal() <= 0.0D) {
            return;
        }
        // 回血量按目标剩余血量封顶：一枪 200 伤害打在 20 血的怪身上，不该回 200 血的 10%
        float effective = Math.min(event.getAmount(), target.getHealth());
        float heal = (float) (effective * values.lifeSteal());
        if (heal > 0.0F && player.getHealth() < player.getMaxHealth()) {
            specHeal(player, heal);
        }
    }

    /**
     * 同一 tick 里谁已经对哪些目标掷过命中效果（霰弹十几颗弹丸不该掷十几次）。
     * 按「目标 + 攻击者」记：两个人同一 tick 打同一只怪，各掷各的。
     */
    private static final Map<LivingEntity, Roll> ROLLED = new WeakHashMap<>();

    /** 一次掷骰的记录。 */
    private record Roll(long tick, java.util.UUID attacker) {
    }

    /** 正在由特化效果回血：新生魔艺的结算窗口会放大窗口内的回血，吸血、击杀回血不该被它再放大一次。 */
    private static boolean specHealing;

    /**
     * 特化效果（吸血、击杀回血）回血，期间打上标记。
     *
     * @param player 玩家
     * @param amount 回血量
     */
    private static void specHeal(ServerPlayer player, float amount) {
        specHealing = true;
        try {
            player.heal(amount);
        } finally {
            specHealing = false;
        }
    }

    /**
     * @return 当前这次回血是不是特化效果回的
     */
    public static boolean isSpecHealing() {
        return specHealing;
    }

    /** 点燃持续秒数。 */
    private static final int IGNITE_SECONDS = 4;

    /** 缓慢持续 tick 数（缓慢 II）。 */
    private static final int SLOW_TICKS = 60;

    /** 虚弱持续 tick 数（虚弱 I）。 */
    private static final int WEAKEN_TICKS = 80;

    /**
     * 命中效果：按特化给的概率点燃 / 缓慢 / 虚弱目标。
     *
     * <p>同一 tick 对同一个目标只掷一次：霰弹的每颗弹丸、多段法术都是同一 tick 的多次命中，
     * 各掷一次的话「30% 点燃」在霰弹上就成了必定点燃。效果的来源记成攻击者，击杀归属不会乱。
     *
     * @param player 攻击者
     * @param target 目标
     * @param values 特化数值
     */
    private static void applyOnHitEffects(ServerPlayer player, LivingEntity target, SpecializationValues values) {
        if (values.igniteChance() <= 0.0D && values.slowChance() <= 0.0D && values.weakenChance() <= 0.0D) {
            return;
        }
        long now = player.level().getGameTime();
        Roll last = ROLLED.put(target, new Roll(now, player.getUUID()));
        if (last != null && last.tick() == now && last.attacker().equals(player.getUUID())) {
            return;
        }
        RandomSource random = player.getRandom();
        if (values.igniteChance() > 0.0D && random.nextDouble() < values.igniteChance()) {
            target.igniteForSeconds(IGNITE_SECONDS);
        }
        if (values.slowChance() > 0.0D && random.nextDouble() < values.slowChance()) {
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, SLOW_TICKS, 1), player);
        }
        if (values.weakenChance() > 0.0D && random.nextDouble() < values.weakenChance()) {
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKEN_TICKS, 0), player);
        }
    }

    /**
     * 击杀：按击杀用的那件武器的特化回血、叠嗜战。
     *
     * <p>武器的认法和吸血一样，见 {@link DamageAttribution}。
     *
     * @param event 死亡事件
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getSource().getEntity() instanceof ServerPlayer player)
                || player.isSpectator() || event.getEntity() == player) {
            return;
        }
        // 被横扫余伤带走的怪不算这一刀杀的：连杀回血和嗜战层数都按真正点中的那个目标算
        if (DamageAttribution.isSplashHit(player, event.getSource(), event.getEntity())) {
            return;
        }
        SpecializationValues values = valuesFor(player, event.getSource());
        if (values == null) {
            return;
        }
        if (values.killHeal() > 0.0D && player.getHealth() < player.getMaxHealth()) {
            specHeal(player, (float) (player.getMaxHealth() * values.killHeal()));
        }
        FrenzyTracker.onKill(player, values);
    }

    /**
     * 主手物品变了就重算移速修饰符。
     *
     * <p>这个事件在主手物品的数据有任何变化时都会触发（枪械开一枪弹药数变了也算），
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
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer() == null) {
            return;
        }
        long gameTime = event.getServer().overworld().getGameTime();
        ComboTracker.tick(gameTime);
        FrenzyTracker.tick(gameTime);
        SpecKnockback.clear();
        DamageAttribution.endArsDamage();
    }

    /**
     * 击退：按命中时登记的特化倍率缩放（登记在各来源的战斗处理器里做，见 {@link SpecKnockback}）。
     *
     * @param event 击退事件
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onKnockBack(LivingKnockBackEvent event) {
        if (event.isCanceled() || event.getEntity().level().isClientSide()) {
            return;
        }
        double factor = SpecKnockback.factorFor(event.getEntity());
        if (factor != 1.0D) {
            event.setStrength((float) (event.getStrength() * factor));
        }
    }

    /**
     * 受到伤害：手里拿着的武器所选特化带「挨打少掉血 / 多掉血」时，按倍率改自己受到的伤害。
     *
     * <p>用 {@code LivingHurtEvent}（护甲结算之前），和攻击方的加成走同一阶段，语义是「这一下打得没那么疼」，
     * 而不是「无视护甲再减一截」。优先级放低，让攻击方的加成先算完。
     *
     * @param event 受伤事件
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPlayerHurt(LivingHurtEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0.0F
                || !(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        SpecializationValues values = currentValues(player, player.getMainHandItem());
        if (values == null || values.damageTaken() == 1.0D) {
            return;
        }
        event.setAmount((float) (event.getAmount() * values.damageTaken()));
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
            FrenzyTracker.forget(player.getUUID());
            SpecMoveSpeed.remove(player);
        }
    }

    /**
     * 服务端关闭时整体清空，别让静态表攥着实体引用。
     *
     * @param event 关服事件
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ComboTracker.clear();
        FrenzyTracker.clear();
        MeleeSwingTracker.clear();
        // 切换冷却也是静态表：单人退出到标题界面时进程还在，不清的话下一个存档接着吃上一个的冷却
        SpecializationManager.clearAll();
    }

    /**
     * 取玩家手上这件武器当前生效的特化数值。
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
     * 取玩家对某件武器当前生效的特化数值。
     *
     * @param player 玩家
     * @param weapon 武器
     * @return 数值；没数据返回 null
     */
    @Nullable
    private static SpecializationValues valuesFor(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source) {
        // 魔艺法术用法术里各符文合并后的那一份，其余按伤害来路认出的武器
        SpecializationValues ars = DamageAttribution.arsValuesFor(player, source);
        if (ars != null) {
            return MasteryConfig.serverSnapshot().enableSpecialization ? ars : null;
        }
        ResolvedWeapon weapon = DamageAttribution.weaponFor(player, source);
        return weapon == null ? null : valuesOf(player, weapon);
    }

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
