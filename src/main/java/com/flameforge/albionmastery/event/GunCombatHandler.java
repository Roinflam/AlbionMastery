package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.compat.TaczSupport;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

/**
 * TACZ 枪械战斗事件处理。
 *
 * <p>伤害加成走 {@code EntityHurtByGunEvent.Pre}，直接改基础伤害；这是 TACZ 官方提供的
 * 注入点，不需要 Mixin，也不会和其它模组的伤害修改打架。
 *
 * <p>事件在两端都会触发（客户端那次用于表现层预测），因此所有逻辑都严格限制在
 * {@link LogicalSide#SERVER}，避免加成被算两次。
 *
 * <p>击杀奖励不在这里发，统一由 {@link KillRewardHandler} 按生物实际掉落的经验值处理。
 */
@Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID)
public final class GunCombatHandler {

    private GunCombatHandler() {
    }

    /**
     * 枪械命中前：按专精等级放大基础伤害，并按加成生效前的伤害发放经验。
     *
     * @param event 命中前事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityHurtByGun(EntityHurtByGunEvent.Pre event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }
        LivingEntity attacker = event.getAttacker();
        if (!(attacker instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        ResourceLocation gunId = event.getGunId();
        if (gunId == null || gunId.getPath().isEmpty()) {
            return;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }

        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        ResolvedWeapon weapon = ResolvedWeapon.of(
                WeaponKeys.PROVIDER_TACZ_GUN, gunId, TaczSupport.gunType(gunId));

        // 先按「加成生效前」的基础伤害发经验，避免加成 -> 经验 -> 更多加成的正反馈
        float baseAmount = event.getBaseAmount();
        awardXpForHit(player, data, weapon, event.getHurtEntity(), baseAmount, snapshot);

        if (baseAmount > 0.0F) {
            MasteryHelper.Bonus bonus = MasteryHelper.computeBonus(data, weapon, snapshot);
            // 特化的伤害倍率是乘在专精加成之外的独立一层：
            // 专精加成是「练出来的」，特化是「换来的」，两者相乘而不是相加，
            // 否则重型弹药那 3 倍会被高等级玩家的专精加成稀释成聊胜于无
            SpecializationValues spec = SpecializationEffects.forData(
                    data, weapon.typeKey(), snapshot);
            // 爆头与否只有枪械知道，所以这里把它传进去；近战和拔刀剑传 null 跳过这一项
            LivingEntity target = event.getHurtEntity() instanceof LivingEntity living ? living : null;
            double multiplier = bonus.multiplier() * spec.damage()
                    * SpecializationEffects.conditionalMultiplier(
                            spec, player, target, event.isHeadShot())
                    // 暴击是独立掷的一层；连击按「连续打中同一个目标」叠层，霰弹每颗弹丸各算一次命中
                    * SpecializationEffects.critMultiplier(spec, player.getRandom());
            if (target != null) {
                multiplier *= ComboTracker.hit(player, target, spec);
            }
            if (Math.abs(multiplier - 1.0D) > 1.0E-6D) {
                event.setBaseAmount((float) (baseAmount * multiplier));
            }
        }
    }


    /**
     * 按一次命中发放经验。
     *
     * @param player     攻击者
     * @param data       攻击者的专精数据
     * @param weapon     武器定位
     * @param hurtEntity 被命中的实体
     * @param baseAmount 加成生效前的基础伤害
     * @param snapshot   数值配置快照
     */
    private static void awardXpForHit(ServerPlayer player, MasteryData data, ResolvedWeapon weapon,
                                      Entity hurtEntity, float baseAmount,
                                      MasteryConfig.Snapshot snapshot) {
        if (baseAmount <= 0.0F) {
            return;
        }
        // 只有打到活体才给经验，打靶子 / 载具不给，防止刷经验
        if (!(hurtEntity instanceof LivingEntity living) || !living.isAlive() || living == player) {
            return;
        }
        if (living instanceof Player && !MasteryConfig.COMMON.xpFromPlayerDamage.get()) {
            return;
        }
        double perDamage = MasteryConfig.COMMON.xpPerDamage.get();
        if (perDamage <= 0.0D) {
            return;
        }
        // 命中经验受单次上限约束，防止被超高伤害的自定义枪包刷爆
        double raw = Math.min(baseAmount * perDamage, MasteryConfig.COMMON.maxXpPerHit.get());
        MasteryHelper.awardXp(data, weapon, Math.max(1L, Math.round(raw)), snapshot);
    }
}
