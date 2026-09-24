package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.compat.SweetMagicSupport;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 甜蜜魔法的伤害加成与归属。
 *
 * <p>两件事：
 * <ol>
 *     <li>施法窗口开着的时候生成的弹体、召唤物，身上打一个「这是哪张卷轴放的」标记——
 *         弹体本身只带着法杖，不带卷轴，不打标记的话命中时认不出该算谁的账；</li>
 *     <li>命中时按那张卷轴的专精和特化加伤害。</li>
 * </ol>
 *
 * <p>为什么挂在通用的受伤事件上而不是模组自己的伤害事件：领域、持续伤害、召唤物是直接造成伤害的，
 * 走的是各自的伤害类型，只有受伤事件能一网打尽。
 */
public final class SweetMagicCombatHandler {

    private SweetMagicCombatHandler() {
    }

    /**
     * 施法期间生成的弹体和召唤物，记上是哪张卷轴放的。
     *
     * @param event 实体加入世界事件
     */
    @SubscribeEvent
    public static void onProjectileSpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        Entity owner = entity instanceof Projectile projectile ? projectile.getOwner() : null;
        // 召唤物、领域实体放出来的二段弹体是在施法窗口关掉之后才生成的，
        // 窗口里问不到；但它们的主人身上还带着标记，顺着主人往下传就行
        ResolvedWeapon weapon = SweetMagicSupport.fromProjectile(owner);
        if (weapon == null) {
            weapon = SweetMagicSupport.castingWeapon(owner);
        }
        if (weapon == null) {
            return;
        }
        SweetMagicSupport.tagProjectile(entity, weapon);
    }

    /**
     * 魔法命中：按卷轴的专精和特化加伤害。
     *
     * @param event 受伤事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMagicHurt(LivingHurtEvent event) {
        float amount = event.getAmount();
        if (amount <= 0.0F || !SweetMagicSupport.isMagicDamage(event.getSource())) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target == player || !target.isAlive()) {
            return;
        }
        ResolvedWeapon weapon = SweetMagicSupport.weaponFor(player, event.getSource());
        if (weapon == null) {
            return;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }

        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        boolean giveXp = !(target instanceof Player) || MasteryConfig.COMMON.xpFromPlayerDamage.get();
        // 先按加成生效前的伤害发经验，避免「加成 → 更多经验 → 更多加成」的正反馈
        double perDamage = MasteryConfig.COMMON.xpPerDamage.get();
        if (giveXp && perDamage > 0.0D) {
            double raw = Math.min(amount * perDamage, MasteryConfig.COMMON.maxXpPerHit.get());
            MasteryHelper.awardXp(data, weapon, Math.max(1L, Math.round(raw)), snapshot);
        }

        MasteryHelper.Bonus bonus = MasteryHelper.computeBonus(data, weapon, snapshot);
        SpecializationValues spec = SpecializationEffects.forData(data, weapon, snapshot);
        double multiplier = bonus.multiplier() * spec.damage()
                * SpecializationEffects.conditionalMultiplier(spec, player, target, null)
                * SpecializationEffects.critMultiplier(spec, player.getRandom());
        if (Math.abs(multiplier - 1.0D) > 1.0E-6D) {
            event.setAmount((float) (amount * multiplier));
        }
    }
}
