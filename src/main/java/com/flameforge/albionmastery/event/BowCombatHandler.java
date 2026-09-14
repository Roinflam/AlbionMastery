package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.BowSupport;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.ArrowLooseEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 弓弩的专精效果。只用原版 / Forge 事件，不依赖任何模组。
 *
 * <ul>
 *     <li>箭出生时（{@code EntityJoinLevelEvent}）：记下是哪把弓射的，顺便按流派缩放箭速；</li>
 *     <li>箭命中时（{@code LivingHurtEvent}，直接来源是箭）：按记下的那把弓乘专精、流派、条件、暴击、连击；</li>
 *     <li>拉弓松手时（{@code ArrowLooseEvent}）：按流派缩放蓄力值，等于拉满更快。弩的上弦时间是静态方法算的，
 *         拿不到玩家，这条只对弓生效。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID)
public final class BowCombatHandler {

    private BowCombatHandler() {
    }

    /**
     * 箭（含投出去的三叉戟）出生：记武器、缩放箭速。
     *
     * @param event 实体加入世界事件
     */
    @SubscribeEvent
    public static void onArrowSpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        if (!(arrow.getOwner() instanceof ServerPlayer player)) {
            return;
        }
        ResolvedWeapon weapon = heldBow(player);
        if (weapon == null && arrow instanceof ThrownTrident) {
            // 投出去的三叉戟：按主手（投掷的一瞬间三叉戟还在手上）
            weapon = WeaponResolver.resolve(player.getMainHandItem());
        }
        if (weapon == null) {
            return;
        }
        BowSupport.tagArrow(arrow, weapon);

        // 箭速沿用枪械的「子弹初速」那一项
        SpecializationValues spec = SpecializationEffects.forWeapon(player, weapon);
        double speed = spec.bulletSpeed();
        if (speed != 1.0D) {
            Vec3 motion = arrow.getDeltaMovement();
            arrow.setDeltaMovement(motion.scale(speed));
        }
    }

    /**
     * 拉弓松手：按流派缩放蓄力值。
     *
     * @param event 松手事件
     */
    @SubscribeEvent
    public static void onArrowLoose(ArrowLooseEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ResolvedWeapon weapon = BowSupport.resolve(event.getBow());
        if (weapon == null) {
            return;
        }
        double draw = SpecializationEffects.forWeapon(player, weapon).drawSpeed();
        if (draw != 1.0D && event.getCharge() > 0) {
            event.setCharge(Math.max(1, (int) Math.round(event.getCharge() * draw)));
        }
    }

    /**
     * 箭命中：按射它的那把弓乘倍率。
     *
     * @param event 伤害事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onArrowHit(LivingHurtEvent event) {
        if (!(event.getSource().getDirectEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target == player || event.getAmount() <= 0.0F) {
            return;
        }
        ResolvedWeapon weapon = BowSupport.fromArrow(arrow);
        if (weapon == null) {
            return;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        MasteryHelper.Bonus bonus = MasteryHelper.computeBonus(data, weapon, snapshot);
        SpecializationValues spec = SpecializationEffects.forData(data, weapon, snapshot);
        double multiplier = bonus.multiplier() * spec.damage()
                * SpecializationEffects.conditionalMultiplier(spec, player, target, null)
                * SpecializationEffects.critMultiplier(spec, player.getRandom())
                * ComboTracker.hit(player, target, spec);
        if (Math.abs(multiplier - 1.0D) > 1.0E-6D) {
            event.setAmount((float) (event.getAmount() * multiplier));
        }
    }

    /**
     * 玩家手里的弓或弩，主手优先。
     *
     * @param player 玩家
     * @return 武器；两手都没有返回 null
     */
    private static ResolvedWeapon heldBow(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            ResolvedWeapon weapon = BowSupport.resolve(stack);
            if (weapon != null) {
                return weapon;
            }
        }
        return null;
    }
}
