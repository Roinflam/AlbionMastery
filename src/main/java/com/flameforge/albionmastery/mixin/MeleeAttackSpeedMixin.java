package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
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
 * <p><b>两个都要按同一个倍率缩放</b>。只改间隔不改延迟的话，疾风特化把间隔压到 0.6 倍之后，
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
 * <p><b>{@code method} 必须写全描述符</b>：0.4.3 的 {@code preAttack} 有两个重载，先声明的
 * 3 参版 {@code (MeleeAction, Vec3, Vec3)} 只是补上连段序号转调 4 参版，真正取冷却、延迟的是
 * 4 参版 {@code (MeleeAction, int, Vec3, Vec3)}。只写方法名会选中 3 参版，三个注入器一个都打不上。
 * 三处调用在 4 参版里各只有一次，所以 {@code expect = 1}。
 *
 * <p>注入不上就是攻速特化不生效，用 {@code require = 0} 让模组照常启动；lrtactical 没装时
 * {@code AlbionMixinPlugin} 整个跳过这个 Mixin。
 */
@Mixin(CombatProperties.class)
public abstract class MeleeAttackSpeedMixin {

    /** 注入目标：真正处理出手的 4 参 {@code preAttack}。 */
    private static final String ALBIONMASTERY$PRE_ATTACK = "preAttack(Lme/xjqsh/lrtactical/api/melee/MeleeAction;I"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Z";

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
            method = ALBIONMASTERY$PRE_ATTACK,
            at = @At(
                    value = "INVOKE",
                    target = "Lme/xjqsh/lrtactical/api/item/IMeleeWeapon;getAttackCoolDown"
                            + "(Lnet/minecraft/world/item/ItemStack;Lme/xjqsh/lrtactical/api/melee/MeleeAction;I)I"
            ),
            require = 0,
            expect = 1
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
            method = ALBIONMASTERY$PRE_ATTACK,
            at = @At(
                    value = "INVOKE",
                    target = "Lme/xjqsh/lrtactical/api/item/IMeleeWeapon;getAttackDelay"
                            + "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;"
                            + "Lme/xjqsh/lrtactical/api/melee/MeleeAction;I)I"
            ),
            require = 0,
            expect = 1
    )
    private int albionmastery$scaleDelay(int original) {
        return scale(original);
    }

    /**
     * 突进延迟：有些刀的配置里带一段向前的突进，按原本的 tick 偏移触发。
     * 挥砍变快了突进还在老位置，就会出现「刀已经收了人才冲出去」。
     *
     * @param original 原延迟
     * @return 缩放后的延迟
     */
    @ModifyExpressionValue(
            method = ALBIONMASTERY$PRE_ATTACK,
            at = @At(value = "INVOKE",
                    target = "Lme/xjqsh/lrtactical/item/melee/CombatData$MeleeMovement;getDelay()I"),
            require = 0,
            expect = 1
    )
    private int albionmastery$scaleMoveDelay(int original) {
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
        // 原来就是 0（出手即判定、没有冷却）的不去动：强行改成 1 反而把立即命中推迟了一 tick
        if (values.attackInterval() == 1.0D || original <= 0) {
            return original;
        }
        // 整数 tick 按倍率的方向取整：变快往下取、变慢往上取。四舍五入的话 0.95 倍在 10 tick 上是 9.5，
        // 又回到 10，等于没效果。至少留 1 tick：0 会让「冷却结束了吗」永远为真，等于无限连击
        double scaled = original * values.attackInterval();
        int rounded = values.attackInterval() < 1.0D ? (int) Math.floor(scaled) : (int) Math.ceil(scaled);
        return Math.max(1, rounded);
    }
}
