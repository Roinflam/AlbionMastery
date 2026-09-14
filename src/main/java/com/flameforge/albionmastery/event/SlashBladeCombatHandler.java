package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
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
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 拔刀剑战斗事件处理。
 *
 * <p>伤害加成走 {@code SlashBladeEvent.DoSlashEvent}——它带 {@code getUser()}、
 * {@code getDamage()} 和 {@code setDamage()}，是拔刀剑官方提供的注入点，
 * 和 TACZ 的 {@code EntityHurtByGunEvent.Pre} 地位相同，不需要写 Mixin。
 *
 * <p>为什么不用 {@code UpdateAttackEvent}：那个事件只有刀和刀的状态，<b>拿不到是谁在用</b>，
 * 没法知道该套用哪个玩家的专精，和原版的 {@code ItemAttributeModifierEvent} 是同一个毛病。
 * 它只适合做客户端显示，实际伤害必须走 {@code DoSlashEvent}。
 *
 * <p>普通挥砍与拔刀技（{@code CircleSlash}、{@code SakuraEnd} 等）都从
 * {@code AttackManager} / {@code AttackHelper} 触发这个事件，所以两者都能覆盖到。
 *
 * <p>本类直接引用拔刀剑的类型，靠 {@code mods.toml} 里的可选依赖 + 事件类不存在时
 * Forge 不会注册该监听来保证缺模组时不报错。
 *
 * <p>方法签名里有拔刀剑的事件类，没装拔刀剑时不能让事件总线去反射它，所以不用
 * {@code @Mod.EventBusSubscriber}，由主类判断装了才手动注册。
 */
public final class SlashBladeCombatHandler {

    private SlashBladeCombatHandler() {
    }

    /**
     * 挥砍结算前：按专精等级放大伤害。
     *
     * @param event 挥砍事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDoSlash(SlashBladeEvent.DoSlashEvent event) {
        LivingEntity user = event.getUser();
        if (!(user instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        double damage = event.getDamage();
        if (damage <= 0.0D) {
            return;
        }
        ResolvedWeapon weapon = SlashBladeSupport.resolve(event.getBlade());
        if (weapon == null) {
            return;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }

        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        MasteryHelper.Bonus bonus = MasteryHelper.computeBonus(data, weapon, snapshot);
        // 这里是刀身直接砍出去的斩击，对应「刀身 / 幻影剑」两个流派里的「直接攻击」那一半
        SpecializationValues spec = SpecializationEffects.forData(
                data, weapon.typeKey(), snapshot);
        // 斩击事件里拿不到具体被砍中的目标（一次挥砍可能扫到好几个），
        // 所以只算和目标无关的那部分条件，比如「自己血越少打得越狠」
        double multiplier = bonus.multiplier() * spec.damage() * spec.directDamage()
                * SpecializationEffects.conditionalMultiplier(spec, player, null, null)
                // 暴击按「一次挥砍」掷，扫到的所有目标同吃；连击要知道打中了谁，放在下面的命中事件里
                * SpecializationEffects.critMultiplier(spec, player.getRandom());
        if (Math.abs(multiplier - 1.0D) > 1.0E-6D) {
            event.setDamage(damage * multiplier);
        }
    }

    /**
     * 幻影剑（召唤剑）命中时按特化调整伤害。
     *
     * <p>为什么不用 {@code SummonedSwordOnHitEntityEvent}：那个事件只给了剑和目标，
     * 没给伤害的读写口，而且它相对于伤害实际结算的先后顺序不好保证。
     * 这里改用通用的 {@code LivingHurtEvent}，靠「直接伤害来源是不是召唤剑实体」来认，
     * 认出来之后 {@code source.getEntity()} 就是放剑的那个玩家。
     *
     * <p>这条路径和 {@code MeleeCombatHandler} 不会打架：那边只处理
     * 「直接伤害来源就是玩家本人」的情况，召唤剑走不到那里。
     *
     * @param event 伤害事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onSummonedSwordHurt(LivingHurtEvent event) {
        if (!(event.getSource().getDirectEntity() instanceof EntityAbstractSummonedSword)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        float amount = event.getAmount();
        if (amount <= 0.0F) {
            return;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }

        // 召唤剑是玩家放出去的，用他主手那把刀的类型来查流派
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        ResolvedWeapon blade = WeaponResolver.resolve(
                player.getMainHandItem(), snapshot.splitMeleeByPack);
        if (blade == null) {
            return;
        }
        SpecializationValues spec = SpecializationEffects.forData(
                data, blade.typeKey(), snapshot);
        double multiplier = spec.damage() * spec.summonedDamage()
                * SpecializationEffects.conditionalMultiplier(spec, player,
                        event.getEntity(), null);
        if (Math.abs(multiplier - 1.0D) <= 1.0E-6D) {
            return;
        }
        // 只乘特化倍率，不再叠专精加成：召唤剑的基础伤害是斩击算出来的，
        // 斩击那一步在 DoSlashEvent 里已经吃过一次专精加成了，这里再乘一次就是双倍
        event.setAmount((float) (amount * multiplier));
    }

    /**
     * 拔刀剑的连击：连续砍中同一个目标，每一下多一层伤害。
     *
     * <p>为什么不放在 {@code DoSlashEvent} 里：那个事件是「挥出去之前」触发的，
     * 不知道会砍中谁，而连击必须按目标记。所以挂在命中事件上，只认攻击者主手是拔刀剑、
     * 且直接伤害来源是玩家本人或拔刀剑自己的实体（斩击特效、召唤剑等）的那些伤害。
     * 这里<b>只</b>乘连击倍率，别的倍率在挥砍事件和召唤剑事件里已经乘过了，再乘就是双倍。
     *
     * @param event 伤害事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBladeHitCombo(LivingHurtEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
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
        if (!isBladeSource(player, event.getSource().getDirectEntity())) {
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
        SpecializationValues spec = SpecializationEffects.forData(data, blade, snapshot);
        if (!spec.hasCombo()) {
            return;
        }
        double multiplier = ComboTracker.hit(player, target, spec);
        if (Math.abs(multiplier - 1.0D) > 1.0E-6D) {
            event.setAmount((float) (amount * multiplier));
        }
    }

    /**
     * 判断一次伤害的直接来源算不算「拔刀剑打出来的」。
     *
     * <p>玩家本人（普通挥砍）算；拔刀剑注册的实体（斩击特效、各种召唤剑）算；
     * 附属包继承 {@code EntityAbstractSummonedSword} 的召唤剑也算。
     * 手里拿着刀却用弓射出去的箭不算，否则弓箭也能叠刀的连击。
     *
     * @param player 攻击者
     * @param direct 直接造成伤害的实体，可能为 null
     * @return 算拔刀剑来源返回 true
     */
    private static boolean isBladeSource(ServerPlayer player, Entity direct) {
        if (direct == null) {
            return false;
        }
        if (direct == player || direct instanceof EntityAbstractSummonedSword) {
            return true;
        }
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(direct.getType());
        return typeId != null && WeaponResolver.SLASHBLADE_MOD_ID.equals(typeId.getNamespace());
    }
}
