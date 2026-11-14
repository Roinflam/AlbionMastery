package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.SpecKnockback;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.compat.SlashBladeSupport;
import mods.flammpfeil.slashblade.entity.EntityAbstractSummonedSword;
import mods.flammpfeil.slashblade.event.SlashBladeEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import com.flameforge.albionmastery.event.damage.LivingHurtEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.damagesource.DamageSource;
import com.flameforge.albionmastery.mastery.spec.DamageAttribution;
import mods.flammpfeil.slashblade.entity.EntityDrive;
import mods.flammpfeil.slashblade.entity.EntityJudgementCut;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import mods.flammpfeil.slashblade.util.NBTHelper;
import mods.flammpfeil.slashblade.slasharts.SlashArts;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 拔刀剑战斗事件处理。
 *
 * <p>伤害加成统一在受伤阶段（本模组的 {@link LivingHurtEvent}）按直接来源分路算：平砍和斩击特效、
 * Drive / 审判斩、幻影剑各吃各的特化项，见 {@link #onBladeHurt}。拔刀剑的好几条伤害路径不发挥砍事件，
 * 只在受伤阶段才能一处覆盖全部来路，不需要写 Mixin。
 *
 * <p>另外三件事靠拔刀剑自己的事件：拔刀技发动时记时间窗（{@code PerformSlashArtEvent}），
 * 给时间窗里放出的召唤剑打标记，击杀加刀魂时按特化缩放（{@code AddProudSoulEvent}）。
 *
 * <p>方法签名里有拔刀剑的事件类，没装拔刀剑时不能让事件总线去反射它，所以不用
 * {@code @EventBusSubscriber}，由主类判断装了才手动注册到 {@code NeoForge.EVENT_BUS}。
 */
public final class SlashBladeCombatHandler {

    private SlashBladeCombatHandler() {
    }

    /**
     * 拔刀剑打出来的每一下伤害，统一在命中时按专精和特化缩放。
     *
     * <p>以前分三处算：挥砍事件（{@code DoSlashEvent}）里乘专精加成和与目标无关的部分，命中时补连击和看目标的部分，
     * 召唤剑另算一处。可拔刀剑有好几条伤害路径根本不发挥砍事件——召唤剑、Drive、审判斩、连段里直接判定的几招——
     * 它们要么拿不到专精加成，要么被当成召唤剑算。现在一律在命中时一处算全：
     * <ul>
     *     <li>召唤剑（幻影剑）：吃「幻影剑」那一项；</li>
     *     <li>Drive、审判斩这些拔刀技专属的实体：吃「刀身」和「SA」两项；</li>
     *     <li>挥砍本身和斩击特效：吃「刀身」，拔刀技的时间窗里再吃「SA」。</li>
     * </ul>
     * 专精加成、特化总伤害、看目标和看自己的条件、暴击、连击，所有来路都吃。
     *
     * <p>只认主手还是拔刀剑的时候：剑飞在半路玩家换成了枪，不能吃枪的特化。
     * 挥砍只认原版「玩家攻击」类型的伤害，法术、荆棘不会被当成刀砍的。
     *
     * @param event 伤害事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBladeHurt(LivingHurtEvent event) {
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        float amount = event.getAmount();
        if (amount <= 0.0F) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target == player || !target.isAlive()) {
            return;
        }
        Entity direct = source.getDirectEntity();
        boolean swing = DamageAttribution.isMeleeSwing(player, source);
        if (!swing && (direct == null || direct == player || !SlashBladeSupport.isBladeEntity(direct))) {
            return;
        }
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        ResolvedWeapon blade = WeaponResolver.resolve(player.getMainHandItem(), snapshot.splitMeleeByPack);
        if (blade == null || !WeaponKeys.PROVIDER_SLASHBLADE.equals(blade.providerId())) {
            return;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }
        MasteryHelper.Bonus bonus = MasteryHelper.computeBonus(data, blade, snapshot);
        SpecializationValues spec = SpecializationEffects.forData(data, blade, snapshot);

        double part;
        if (direct instanceof EntityDrive || direct instanceof EntityJudgementCut) {
            // Drive 继承自召唤剑，但它是拔刀技打出去的剑气，不是幻影剑
            part = spec.directDamage() * spec.saDamage();
        } else if (direct instanceof EntityAbstractSummonedSword sword) {
            // 拔刀技放出来的剑（附属包的几种拔刀技就是放一圈召唤剑）算拔刀技，平时的幻影剑算幻影剑
            part = sword.getPersistentData().getBoolean(TAG_SLASH_ART) ? spec.saDamage() : spec.summonedDamage();
        } else {
            part = spec.directDamage() * (isInSlashArt(player) ? spec.saDamage() : 1.0D);
        }
        double multiplier = bonus.multiplier() * spec.damage() * part
                * SpecializationEffects.conditionalMultiplier(spec, player, target, null)
                * SpecializationEffects.critMultiplier(spec, player.getRandom())
                * ComboTracker.hit(player, target, spec);
        if (Math.abs(multiplier - 1.0D) > 1.0E-6D) {
            event.setAmount((float) (amount * multiplier));
        }
        // 拔刀剑的击退不走原版那套：出手前先把「这一下怎么击退」写进目标身上，它自己的击退监听读这个值，
        // 多数招式写的是「不击退」并直接取消击退事件，我们挂在后面的监听根本收不到。
        // 所以有这个值就直接缩放它（不击退的招式缩放了还是不击退，这是拔刀剑连段的设计），没有才走通用那套
        CompoundTag persistent = target.getPersistentData();
        if (persistent.contains(KNOCKBACK_FACTOR)) {
            if (spec.knockback() != 1.0D) {
                Vec3 factor = NBTHelper.getVector3d(persistent, KNOCKBACK_FACTOR);
                NBTHelper.putVector3d(persistent, KNOCKBACK_FACTOR, factor.scale(spec.knockback()));
            }
        } else {
            SpecKnockback.mark(target, player.getUUID(), spec.knockback());
        }
    }

    /** 拔刀剑把「这一下怎么击退」存在目标身上的键。 */
    private static final String KNOCKBACK_FACTOR = "knockback_factor";

    /** 拔刀技时间窗里放出来的召唤剑身上打的标记。 */
    private static final String TAG_SLASH_ART = "albionmastery_slash_art";

    /**
     * 拔刀技时间窗里生成的召唤剑打上「拔刀技」标记。
     *
     * @param event 实体加入世界事件
     */
    @SubscribeEvent
    public static void onSummonedSwordSpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk()
                || !(event.getEntity() instanceof EntityAbstractSummonedSword sword)
                || !(sword.getShooter() instanceof ServerPlayer player)) {
            return;
        }
        if (isInSlashArt(player)) {
            sword.getPersistentData().putBoolean(TAG_SLASH_ART, true);
        }
    }

    /** 拔刀技开始后多少 tick 内的斩击算作拔刀技的一部分。 */
    private static final int SLASH_ART_WINDOW_TICKS = 30;

    /** 玩家 -&gt; 最近一次放拔刀技的世界 tick。只在服务端主线程读写。 */
    private static final Map<UUID, Long> SLASH_ART_TICKS = new HashMap<>();

    /**
     * 记下玩家放了拔刀技（SA）。
     *
     * <p>拔刀剑的 SA 由好几段斩击组成，每一段落到目标身上的伤害和普通连段看不出区别，
     * 分不出「这是普通连段还是 SA」。所以在 SA 发动时打个时间戳，之后一小段时间里的斩击都按 SA 算。
     *
     * @param event 拔刀技事件
     */
    @SubscribeEvent
    public static void onSlashArt(SlashBladeEvent.PerformSlashArtEvent event) {
        if (event.isCanceled() || !(event.getEntityLiving() instanceof ServerPlayer player)) {
            return;
        }
        // 蓄力不够时拔刀剑也会发这个事件（类型是失败、没有招式）。不挡的话，短按一下右键，
        // 之后一秒半里的普通斩击都能吃到拔刀技倍率，可以无限刷
        if (event.getType() == SlashArts.ArtsType.Fail || event.getComboState() == null
                || event.getComboState().equals(ComboStateRegistry.NONE.getId())) {
            return;
        }
        SLASH_ART_TICKS.put(player.getUUID(), player.level().getGameTime());
    }

    /**
     * @param player 玩家
     * @return 现在是不是在拔刀技的时间窗里
     */
    private static boolean isInSlashArt(ServerPlayer player) {
        Long start = SLASH_ART_TICKS.get(player.getUUID());
        if (start == null) {
            return false;
        }
        long elapsed = player.level().getGameTime() - start;
        if (elapsed < 0L || elapsed > SLASH_ART_WINDOW_TICKS) {
            SLASH_ART_TICKS.remove(player.getUUID());
            return false;
        }
        return true;
    }

    /** 正在掉经验、即将给刀加刀魂的那个玩家；刀魂事件本身不带玩家。 */
    private static ServerPlayer soulPlayer;

    /**
     * 刀魂是在生物掉经验时加的（拔刀剑的 {@code KillCounter} 监听掉经验事件，再发 {@code AddProudSoulEvent}），
     * 而刀魂事件里只有刀、没有人。所以抢在它前面记下是谁杀的，这一 tick 结束时清掉。
     *
     * <p>以前是在同一个掉经验事件的 LOWEST 里清：拔刀剑的监听也是 LOWEST，而且注册得比我们晚，
     * 同一优先级按注册先后执行，结果人总是在刀魂事件之前就被清掉，刀魂倍率从来没生效过。
     *
     * @param event 掉经验事件
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onXpDropStart(LivingExperienceDropEvent event) {
        soulPlayer = event.getAttackingPlayer() instanceof ServerPlayer player ? player : null;
    }

    /**
     * @param event 服务端 tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        soulPlayer = null;
    }

    /**
     * 刀魂获取：按特化倍率缩放这次加到刀上的刀魂数。带小数时按概率取整，长期平均正好是倍率。
     *
     * @param event 刀魂事件
     */
    @SubscribeEvent
    public static void onAddProudSoul(SlashBladeEvent.AddProudSoulEvent event) {
        ServerPlayer player = soulPlayer;
        if (player == null || player.getMainHandItem() != event.getBlade() || event.getNewCount() <= 0) {
            return;
        }
        ResolvedWeapon blade = SlashBladeSupport.resolve(event.getBlade());
        if (blade == null) {
            return;
        }
        double factor = SpecializationEffects.forWeapon(player, blade).soulGain();
        if (factor == 1.0D) {
            return;
        }
        double scaled = event.getNewCount() * factor;
        int whole = (int) Math.floor(scaled);
        if (player.getRandom().nextDouble() < scaled - whole) {
            whole++;
        }
        event.setNewCount(Math.max(0, whole));
    }

    /**
     * 玩家下线时清掉拔刀技时间戳。
     *
     * @param event 下线事件
     */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SLASH_ART_TICKS.remove(event.getEntity().getUUID());
    }
}
