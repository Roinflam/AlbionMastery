package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.compat.IronsArtificeSupport;
import io.redspace.irons_artifice.api.ComposeShotEvent;
import io.redspace.irons_artifice.data.ComponentType;
import io.redspace.irons_artifice.data.ShotComponents;
import io.redspace.irons_artifice.data.Value;
import io.redspace.irons_artifice.data.ValueModifier;
import io.redspace.irons_artifice.gun.ShotProfile;
import io.redspace.irons_artifice.item.GunItem;
import io.redspace.irons_artifice.item.GunplayManager;
import io.redspace.irons_artifice.item.MagazineContents;
import io.redspace.irons_artifice.item.ReloadState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 铁枪（Iron's Arms 'n Artifice）的战斗事件处理。
 *
 * <p>全部走事件，一个 Mixin 都没有：
 * <ul>
 *     <li>射速、装填、后坐、散射、弹速、穿透、跳弹、追踪、人物反冲、耗弹概率改在铁枪自己的
 *     {@link ComposeShotEvent} 里。这个事件两端都发（客户端判断开火冷却、画准星、写物品提示，
 *     服务端真开火、开始装填），两端各用各的专精数据，开火节奏、准星和提示都跟着变，
 *     服务端不会把客户端「开快了」的那一枪丢掉。修饰是叠在铁枪的组件表上的，和改装件、帽子的修饰互不干扰。</li>
 *     <li>命中经验和伤害加成挂原版的受伤事件：子弹实体带着是哪把枪。改装件的爆炸、闪电、魂火
 *     直接来源也是子弹，只加伤害、不给命中经验。</li>
 *     <li>击杀补弹挂死亡事件，直接改枪上的弹匣 NBT。</li>
 * </ul>
 * 只认「射手是玩家」：厄械师、快枪手和被塞了枪的骷髅打出来的子弹一概不碰。
 *
 * <p>方法签名里有铁枪的事件类，没装铁枪时不能让事件总线去反射它，由主类判断装了才手动注册。
 */
public final class IronsArtificeCombatHandler {

    private IronsArtificeCombatHandler() {
    }

    /**
     * 组装这一枪：把特化的手感维度叠到铁枪的组件表上。
     *
     * @param event 组装事件
     */
    @SubscribeEvent
    public static void onComposeShot(ComposeShotEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ShotProfile profile = event.getShotProfile();
        // 客户端每 tick 都会组装一次，大多数时候没有生效的特化，先拿中性值短路掉
        SpecializationValues values = SpecializationEffects.forStack(player, profile.itemStack());
        if (values == SpecializationValues.NEUTRAL) {
            return;
        }
        multiply(profile, ShotComponents.FIRE_RATE, values.fireRate());
        // 铁枪的是「装填速度」，特化里写的是「装填时间」，取倒数
        if (values.reloadTime() > 0.0D) {
            multiply(profile, ShotComponents.RELOAD_SPEED_MULTIPLIER, 1.0D / values.reloadTime());
        }
        multiply(profile, ShotComponents.CAMERA_RECOIL_MULTIPLIER, values.recoil());
        // 移动、空中的额外散射铁枪另算，这里只改枪本身的散射
        multiply(profile, ShotComponents.SPREAD, values.hipSpread());
        multiply(profile, ShotComponents.BULLET_SPEED, values.bulletSpeed());
        multiply(profile, ShotComponents.KNOCKBACK, values.knockback());
        // 扣弹时由铁枪附魔子弹改装件的常驻监听按这个组件掷骰，音效和提示也是它的
        if (values.ammoSave() > 0.0D) {
            multiply(profile, ShotComponents.AMMO_CONSUME_CHANCE, 1.0D - values.ammoSave());
        }
        add(profile, ShotComponents.PIERCING, values.pierce());
        add(profile, ShotComponents.RICOCHET, values.ricochet());
        add(profile, ShotComponents.SEEKING, values.seeking());
        // 人物反冲用加法：每把枪多推 / 少推一样多。减到 0 以下铁枪自己按不推算
        add(profile, ShotComponents.CHARACTER_BLOWBACK, values.blowback());
    }

    private static void multiply(ShotProfile profile, ComponentType<Value> type, double factor) {
        if (Math.abs(factor - 1.0D) < 1.0E-9D) {
            return;
        }
        profile.modifyValue(type, new ValueModifier(factor - 1.0D, ValueModifier.Operation.MULTIPLY_TOTAL,
                ValueModifier.Type.NEUTRAL));
    }

    private static void add(ShotProfile profile, ComponentType<Value> type, double amount) {
        if (Math.abs(amount) < 1.0E-9D) {
            return;
        }
        profile.modifyValue(type, new ValueModifier(amount, ValueModifier.Operation.ADD, ValueModifier.Type.NEUTRAL));
    }

    /**
     * 子弹命中：发命中经验、乘伤害加成。
     *
     * <p>直接命中按「加成生效前」的伤害发经验，霰弹每颗弹丸、霜冻碎片各算一次（合起来就是总伤害），
     * 和 TACZ 一个口径。爆炸、闪电、魂火是从子弹的原始伤害算出来的另一次受伤，各自乘一遍，
     * 不会重复；它们不给命中经验，不然一发黑火药炸一群就刷出一大笔。
     *
     * @param event 受伤事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBulletHurt(LivingHurtEvent event) {
        DamageSource source = event.getSource();
        Entity direct = source.getDirectEntity();
        if (!IronsArtificeSupport.isBullet(direct) || event.getAmount() <= 0.0F) {
            return;
        }
        if (!(source.getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target == player || !target.isAlive()) {
            return;
        }
        ResolvedWeapon weapon = IronsArtificeSupport.fromBullet(direct);
        MasteryData data = weapon == null ? null : MasteryHelper.get(player);
        if (data == null) {
            return;
        }
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        boolean directHit = IronsArtificeSupport.isDirectHit(source);
        float baseAmount = event.getAmount();
        if (directHit) {
            awardXpForHit(player, data, weapon, target, baseAmount, snapshot);
        }

        MasteryHelper.Bonus bonus = MasteryHelper.computeBonus(data, weapon, snapshot);
        SpecializationValues spec = SpecializationEffects.forData(data, weapon, snapshot);
        double multiplier = bonus.multiplier() * spec.damage()
                * SpecializationEffects.conditionalMultiplier(spec, player, target, null)
                * SpecializationEffects.critMultiplier(spec, player.getRandom());
        if (directHit) {
            multiplier *= ComboTracker.hit(player, target, spec);
            if (spec.firstShotDamage() != 1.0D && IronsArtificeSupport.isFullMagazineShot(direct)) {
                multiplier *= spec.firstShotDamage();
            }
            if (spec.ricochetDamage() != 1.0D && IronsArtificeSupport.hasRicocheted(direct)) {
                multiplier *= spec.ricochetDamage();
            }
        } else {
            multiplier *= spec.effectDamage();
        }
        if (Math.abs(multiplier - 1.0D) > 1.0E-6D) {
            event.setAmount((float) (baseAmount * multiplier));
        }
    }

    /**
     * 按一次直接命中发经验。受伤事件里已经过了无敌帧和无敌目标的判断，这里再挡一遍盔甲架和不能伤害的玩家。
     *
     * @param player     射手
     * @param data       射手的专精数据
     * @param weapon     那把枪
     * @param hurt       被打中的实体
     * @param baseAmount 加成生效前的伤害
     * @param snapshot   数值配置快照
     */
    private static void awardXpForHit(ServerPlayer player, MasteryData data, ResolvedWeapon weapon,
                                      LivingEntity hurt, float baseAmount, MasteryConfig.Snapshot snapshot) {
        LivingEntity living = hurt;
        if (living instanceof ArmorStand || living.isInvulnerable()) {
            return;
        }
        if (living instanceof Player target && (target.getAbilities().invulnerable
                || !player.server.isPvpAllowed() || !MasteryConfig.COMMON.xpFromPlayerDamage.get())) {
            return;
        }
        double perDamage = MasteryConfig.COMMON.xpPerDamage.get();
        if (perDamage <= 0.0D) {
            return;
        }
        double raw = Math.min(baseAmount * perDamage, MasteryConfig.COMMON.maxXpPerHit.get());
        MasteryHelper.awardXp(data, weapon, Math.max(1L, Math.round(raw)), snapshot);
    }

    /**
     * 击杀补弹：按期望补进手上那把枪的弹匣。
     *
     * <p>应补 = 弹匣容量 × 比例（杀怪再乘 {@code killRefillMobRatio}），整数部分直接补，小数部分按概率补 1 发。
     * TACZ 那边是「至少补 1 发」，放到容量只有 1 的燧发枪上等于每杀一只就免装填，所以这里按期望算。
     * 补满时正在装填就把装填取消，和铁枪牛仔帽的瞬间装填一个做法；没补满的话装填照常完成，
     * 铁枪装完时按缺几发补几发，不会装超。
     *
     * @param event 死亡事件
     */
    @SubscribeEvent
    public static void onBulletKill(LivingDeathEvent event) {
        DamageSource source = event.getSource();
        Entity direct = source.getDirectEntity();
        if (event.isCanceled() || !IronsArtificeSupport.isBullet(direct)) {
            return;
        }
        if (!(source.getEntity() instanceof ServerPlayer player) || player.isSpectator()
                || event.getEntity() == player) {
            return;
        }
        ResolvedWeapon fired = IronsArtificeSupport.fromBullet(direct);
        ItemStack gun = fired == null ? null : findHeldGun(player, fired);
        if (gun == null || !(gun.getItem() instanceof GunItem gunItem)) {
            return;
        }
        SpecializationValues values = SpecializationEffects.forStack(player, gun);
        double ratio = values.killRefill();
        if (ratio <= 0.0D) {
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            ratio *= MasteryConfig.COMMON.killRefillMobRatio.get();
        }
        int capacity = gunItem.magazineCapacity();
        MagazineContents contents = MagazineContents.get(gun);
        if (contents == null || capacity <= 0 || contents.isFull(capacity) || ratio <= 0.0D) {
            return;
        }
        double expected = capacity * ratio;
        int refill = (int) Math.floor(expected);
        if (player.getRandom().nextDouble() < expected - refill) {
            refill++;
        }
        if (refill <= 0) {
            return;
        }
        int after = Math.min(capacity, contents.count() + refill);
        MagazineContents.set(gun, contents.with(after));
        if (after >= capacity && GunItem.isReloading(gun)) {
            ReloadState.remove(gun);
            GunplayManager.cancelGunAnimation(player, gun);
        }
    }

    /**
     * 在玩家手里找打出这颗子弹的那把枪（按物品认）。
     *
     * @param player 玩家
     * @param fired  子弹记着的枪
     * @return 那把枪；两只手都不是返回 null
     */
    private static ItemStack findHeldGun(ServerPlayer player, ResolvedWeapon fired) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            ResolvedWeapon held = IronsArtificeSupport.resolve(stack);
            if (held != null && held.weaponKey().equals(fired.weaponKey())) {
                return stack;
            }
        }
        return null;
    }
}
