package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.BowSupport;
import com.flameforge.albionmastery.weapon.compat.IronsArtificeSupport;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import com.flameforge.albionmastery.weapon.compat.SlashBladeSupport;
import com.flameforge.albionmastery.weapon.compat.TaczSupport;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;

import javax.annotation.Nullable;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.weapon.compat.ArsNouveauSupport;
import net.minecraft.world.entity.LivingEntity;

/**
 * 一次伤害算在哪件武器头上。吸血、命中特效、击杀回血、嗜战这些「命中后」的特化效果都靠它认武器。
 *
 * <p>以前一律按攻击者当时的主手算，漏洞很多：主手拿枪放寒冰射线，法术伤害会被当成枪打的再乘一遍枪的特化；
 * 穿着荆棘甲拿什么武器，荆棘反伤就吃什么武器的特化；三叉戟扔出去以后主手是空的，三叉戟的特化全都不触发。
 * 现在按伤害本身的来路认：
 * <ol>
 *     <li>铁枪子弹：子弹带着开火那一刻的整份组装结果，里面就是那把枪；改装件的爆炸、闪电、魂火也算这颗子弹的；</li>
 *     <li>铁魔法的法术伤害：伤害源里带着是哪个法术；</li>
 *     <li>新生魔艺的法术伤害：结算期间由 {@code ArsCombatHandler} 登记的那个法术（主效果符文）；</li>
 *     <li>箭和三叉戟：射出去时记在实体上的武器；</li>
 *     <li>TACZ 子弹：子弹自己带着枪的 ID，开枪后换了武器也不会认错；</li>
 *     <li>玩家亲手挥出去的一下：伤害类型必须是原版的「玩家攻击」，而且主手得是近战武器；</li>
 *     <li>拔刀剑的斩击特效、召唤剑、Drive、审判斩：主手还得是拔刀剑（剑飞在半路换成枪，不吃枪的特化）。</li>
 * </ol>
 * 都不是（荆棘、爆炸、着火、魔法伤害类型的杂项）就不算任何武器的，特化效果一概不触发。
 */
public final class DamageAttribution {

    /** 魔艺正在结算伤害的施法者，以及那个法术对应的武器；只在服务端主线程读写。 */
    @Nullable
    private static ServerPlayer arsCaster;
    @Nullable
    private static ResolvedWeapon arsWeapon;
    @Nullable
    private static SpecializationValues arsValues;

    private DamageAttribution() {
    }

    /**
     * 魔艺法术伤害开始结算（{@code SpellDamageEvent.Pre}）。
     *
     * @param caster 施法者
     * @param weapon 法术主效果对应的武器，可为 null
     * @param values 法术里各符文合并后的特化数值
     */
    public static void beginArsDamage(ServerPlayer caster, @Nullable ResolvedWeapon weapon,
                                      SpecializationValues values) {
        arsCaster = caster;
        arsWeapon = weapon;
        arsValues = values;
    }

    /**
     * 魔艺法术伤害结算结束（{@code SpellDamageEvent.Post}），或者每 tick 末尾兜底清一次
     * ——别的模组取消了伤害事件时 Post 不会来。
     */
    public static void endArsDamage() {
        arsCaster = null;
        arsWeapon = null;
        arsValues = null;
    }

    /**
     * 新生魔艺法术伤害的特化数值：法术里各符文合并后的那一份，不是单个符文的。
     *
     * @param player 攻击者
     * @param source 伤害源
     * @return 数值；这次不是魔艺法术伤害返回 null
     */
    @Nullable
    public static SpecializationValues arsValuesFor(ServerPlayer player, DamageSource source) {
        return isArsDamage(player, source) ? arsValues : null;
    }

    /**
     * 登记的魔艺结算窗口对不对得上这次伤害：同一个施法者，而且伤害源确实是魔艺的法术伤害。
     * 只看窗口的话，魔艺那边伤害被挡掉时不发结束事件，窗口会一直开到 tick 结束，
     * 同一 tick 里的箭、子弹、挥砍都会被错认成法术。
     */
    private static boolean isArsDamage(ServerPlayer player, DamageSource source) {
        if (arsCaster != player || !WeaponResolver.isArsLoaded()) {
            return false;
        }
        boolean spell = false;
        spell = ArsNouveauSupport.isSpellDamage(source);
        return spell;
    }

    /**
     * 这一下伤害算哪件武器。
     *
     * @param player 攻击者
     * @param source 伤害源
     * @return 武器；不算任何武器时返回 null
     */
    @Nullable
    public static ResolvedWeapon weaponFor(ServerPlayer player, DamageSource source) {
        // 铁枪放最前：电击弹芯的闪电是原版的间接魔法伤害，直接来源是子弹，
        // 先认走就不会让别的来源按伤害类型去猜
        if (WeaponResolver.isIronsArtificeLoaded()) {
            ResolvedWeapon gun = IronsArtificeSupport.fromBullet(source.getDirectEntity());
            if (gun != null) {
                return gun;
            }
        }
        if (WeaponResolver.isIronsLoaded()) {
            ResolvedWeapon spell = IronsSpellsSupport.resolveDamage(source);
            if (spell != null) {
                return spell;
            }
        }
        if (arsWeapon != null && isArsDamage(player, source)) {
            return arsWeapon;
        }
        Entity direct = source.getDirectEntity();
        if (direct instanceof AbstractArrow arrow) {
            return BowSupport.fromArrow(arrow);
        }
        if (WeaponResolver.isTaczLoaded()) {
            ResolvedWeapon gun = TaczSupport.fromBullet(direct);
            if (gun != null) {
                return gun;
            }
        }
        if (isMeleeSwing(player, source)) {
            ResolvedWeapon held = WeaponResolver.resolve(player.getMainHandItem());
            return held != null && isMeleeProvider(held.providerId()) ? held : null;
        }
        if (direct != null && direct != player && WeaponResolver.isSlashBladeLoaded()
                && SlashBladeSupport.isBladeEntity(direct)) {
            ResolvedWeapon held = WeaponResolver.resolve(player.getMainHandItem());
            return held != null && WeaponKeys.PROVIDER_SLASHBLADE.equals(held.providerId()) ? held : null;
        }
        return null;
    }

    /**
     * 是不是玩家亲手挥出去的一下：直接来源是他本人，伤害类型是原版的「玩家攻击」。
     *
     * <p>铁魔法很多法术、新生魔艺的全部伤害、荆棘反伤，直接来源也是玩家本人，但伤害类型都是各自的，
     * 靠这一条就能和真正的近战分开。
     *
     * @param player 攻击者
     * @param source 伤害源
     * @return 是近战挥砍返回 true
     */
    public static boolean isMeleeSwing(ServerPlayer player, DamageSource source) {
        return source.getDirectEntity() == player && source.is(DamageTypes.PLAYER_ATTACK);
    }

    /**
     * 这一下是不是「扫到 / 溅到」旁边的目标，而不是玩家真正点中的那个。
     *
     * <p>原版横扫和不少模组的溅射、连锁，用的伤害源和亲手打的那一下完全一样，
     * 只能靠「这一 tick 玩家点的到底是谁」来分（见 {@link MeleeSwingTracker}）。
     * 不分的话，每个被扫到的目标都会被当成一次完整挥砍：伤害倍率再乘一遍、
     * 经验再发一份、吸血和命中特效各触发一次——原版横扫本来只有 1 点余伤。
     *
     * @param player 玩家
     * @param source 伤害源
     * @param target 这次伤害的目标
     * @return 是余伤 / 溅射返回 true
     */
    public static boolean isSplashHit(ServerPlayer player, DamageSource source, LivingEntity target) {
        return isMeleeSwing(player, source) && !MeleeSwingTracker.isDirectTarget(player, target);
    }

    private static boolean isMeleeProvider(String providerId) {
        return WeaponKeys.isMeleeProvider(providerId);
    }
}
