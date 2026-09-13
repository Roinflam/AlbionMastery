package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 近战伤害处理。
 *
 * <p>lrtactical 的近战伤害是这样算出来的：
 * {@code MeleeItem.attack()} 取玩家的 {@code Attributes.ATTACK_DAMAGE} 属性值，
 * 乘上这一击的 {@code factor}，再交给 {@code IMeleeWeapon.performAttack()}，
 * 后者附加附魔与暴击加成后调用 {@code entity.hurt(source, amount)}。
 *
 * <p>它没有暴露任何服务端伤害事件（{@code MeleePreAttackEvent} 是纯客户端的，
 * 只带 {@code AbstractClientPlayer}），所以这里挂 Forge 的 {@link LivingHurtEvent}，
 * 靠「伤害的直接来源就是玩家本人」来锁定近战命中——弹丸、投掷物、爆炸的
 * {@code getDirectEntity()} 都不是玩家，因此枪械弹丸伤害不会被这里重复放大
 * （那部分由 {@link GunCombatHandler} 负责）。
 *
 * <p>选择 {@code LivingHurtEvent} 而不是 {@code LivingDamageEvent}，是为了让加成在护甲
 * 减伤之前生效，语义上等同于「这把武器的伤害变高了」，而不是「无视护甲额外补伤害」。
 *
 * <p>击杀奖励不在这里发，统一由 {@link KillRewardHandler} 按生物实际掉落的经验值处理。
 */
@Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID)
public final class MeleeCombatHandler {

    private MeleeCombatHandler() {
    }

    /**
     * 玩家直接命中造成伤害时，按专精等级放大伤害并发放经验。
     *
     * @param event 受伤事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingHurt(LivingHurtEvent event) {
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        // 关键守卫：只处理玩家本体直接造成的伤害
        if (source.getDirectEntity() != player) {
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
        if (target instanceof Player && !MasteryConfig.COMMON.xpFromPlayerDamage.get()) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        ResolvedWeapon weapon = WeaponResolver.resolve(stack);
        if (weapon == null) {
            return;
        }
        // 拔刀剑的伤害由 SlashBladeCombatHandler 在 DoSlashEvent 里处理。
        // 它的挥砍大多经由 EntitySlashEffect 等实体命中，本来就走不到这里，
        // 但普通左键攻击有可能走到，不排除掉就会双倍加成
        if (WeaponKeys.PROVIDER_SLASHBLADE.equals(weapon.providerId())) {
            return;
        }

        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }

        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();

        // 先按加成生效前的伤害发经验，避免加成 -> 经验 -> 更多加成的正反馈
        double perDamage = MasteryConfig.COMMON.xpPerDamage.get();
        if (perDamage > 0.0D) {
            double raw = Math.min(amount * perDamage, MasteryConfig.COMMON.maxXpPerHit.get());
            MasteryHelper.awardXp(data, weapon, Math.max(1L, Math.round(raw)), snapshot);
        }

        MasteryHelper.Bonus bonus = MasteryHelper.computeBonus(data, weapon, snapshot);
        SpecializationValues spec = SpecializationEffects.forData(data, weapon.typeKey(), snapshot);

        // 特化的伤害倍率独立于专精加成之外再乘一层：专精是练出来的，特化是换来的。
        // 「看情况」的那些条件（有没有护甲、目标残不残血、自己血多少）统一由
        // conditionalMultiplier 算，三个来源共用一份逻辑，不会这边支持那边漏
        double multiplier = bonus.multiplier() * spec.damage()
                * SpecializationEffects.conditionalMultiplier(spec, player, target, null)
                // 暴击独立掷一层，连击按连续命中同一目标叠层
                * SpecializationEffects.critMultiplier(spec, player.getRandom())
                * ComboTracker.hit(player, target, spec);
        if (Math.abs(multiplier - 1.0D) > 1.0E-6D) {
            event.setAmount((float) (amount * multiplier));
        }

    }
}
