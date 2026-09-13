package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.xjqsh.lrtactical.capability.CombatProperties;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 把近战特化的攻速改动接进 lrtactical 的出手流程。
 *
 * <p>补的是什么：{@code CombatProperties.preAttack} 是每次挥击的入口，它在这里向武器要两个数——
 * 攻击间隔（{@code getAttackCoolDown}，决定多久能再挥一次）和出手延迟
 * （{@code getAttackDelay}，决定挥出去多久判定命中）。
 *
 * <p><b>两个都要按同一个倍率缩放</b>。只改间隔不改延迟的话，疾风流派把间隔压到 0.6 倍之后，
 * 有可能出现「间隔比延迟还短」——上一刀的判定还没落地，下一刀已经能出手了，
 * 动画和判定会脱节。按同比例缩放则保持原有的节奏关系。
 *
 * <p>武器接口上的这两个方法本身<b>拿不到玩家</b>（{@code getAttackCoolDown} 连参数里都没有），
 * 而特化是按玩家算的，所以只能挑调用点下手：{@code CombatProperties} 自带一个
 * {@code entity} 字段就是持有者，正好够用。
 *
 * <p><b>调用点的 owner 必须写 {@code IMeleeWeapon}</b>：{@code getAttackCoolDown} 虽然声明在
 * {@code ICustomItem} 里，但 {@code preAttack} 里是通过 {@code IMeleeWeapon} 类型的变量调的，
 * 字节码里这条 invokeinterface 的 owner 就是 {@code IMeleeWeapon}。Mixin 按 owner 精确匹配，
 * 写成 {@code ICustomItem} 一个调用点都对不上，再加上 {@code require = 0}，
 * 结果就是攻速特化悄无声息地不生效（lrtactical 0.4.1 实测如此）。
 *
 * <p>目标是第三方模组的类，不参与 SRG 映射，所以 {@code remap = false}；
 * 注入不上就是攻速特化不生效，用 {@code require = 0} 让模组照常启动。
 */
@Mixin(value = CombatProperties.class, remap = false)
public abstract class MeleeAttackSpeedMixin {

    @Shadow
    @Final
    private Player entity;

    /**
     * 缩放两次攻击之间的间隔。
     *
     * @param original 原始间隔（tick）
     * @return 应用特化之后的间隔，至少 1 tick
     */
    @ModifyExpressionValue(
            method = "preAttack",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/xjqsh/lrtactical/api/item/IMeleeWeapon;getAttackCoolDown"
            ),
            require = 0
    )
    private int albionmastery$scaleCoolDown(int original) {
        return scale(original);
    }

    /**
     * 按同一倍率缩放出手到判定的延迟。
     *
     * @param original 原始延迟（tick）
     * @return 应用特化之后的延迟，至少 1 tick
     */
    @ModifyExpressionValue(
            method = "preAttack",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/xjqsh/lrtactical/api/item/IMeleeWeapon;getAttackDelay"
            ),
            require = 0
    )
    private int albionmastery$scaleDelay(int original) {
        return scale(original);
    }

    /**
     * 按当前生效的近战特化缩放一个 tick 数。
     *
     * @param original 原始 tick 数
     * @return 缩放后的 tick 数，至少 1
     */
    private int scale(int original) {
        // 用主手那把：preAttack 本身不带物品，而近战一定是主手挥出去的
        SpecializationValues values = SpecializationEffects.forStack(
                this.entity, this.entity.getMainHandItem());
        if (values.attackInterval() == 1.0D) {
            return original;
        }
        // 至少留 1 tick：0 会让上层的「冷却结束了吗」判断永远为真，等于无限连击
        return Math.max(1, (int) Math.round(original * values.attackInterval()));
    }
}
