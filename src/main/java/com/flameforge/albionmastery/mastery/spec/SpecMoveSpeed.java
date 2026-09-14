package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.UUID;

/**
 * 把流派的「持有时移速」做成玩家身上的一个移动速度属性修饰符；通用剑类的「攻击间隔」
 * 也在这里做成攻速属性修饰符（原版剑的攻击冷却就是攻速属性决定的）。
 *
 * <p>为什么挂在玩家身上而不是物品上：物品的属性修饰符是按物品算的、和玩家无关，
 * 而流派是玩家选的——同一把枪在两个人手里移速可能不一样。挂在玩家的属性实例上，
 * 主手换武器时重算一次就行。
 *
 * <p>为什么只在服务端做：{@code MOVEMENT_SPEED} 是会自动同步给客户端的属性，
 * 服务端加上修饰符，客户端的移动预测自然就跟着变了，不需要再发一遍包。
 *
 * <p>用固定 UUID 的修饰符，每次刷新先按 UUID 摘掉旧的再挂新的，永远不会叠加出两个。
 * 用 {@code MULTIPLY_TOTAL}：数值是「在最终速度上乘 (1 + x)」，和其它模组的加速减速
 * 相乘而不是相加，不会和别人打架。
 *
 * <p>什么时候要刷新：主手物品变了、流派切换了、登录 / 重生 / 换维度（属性实例是新的）。
 * 调用点都在 {@code SpecializationCombatHandler} 和 {@code CommonEventHandler} 里。
 */
public final class SpecMoveSpeed {

    /** 修饰符的固定 UUID，只用来认自己挂的那一个。 */
    private static final UUID MODIFIER_ID = UUID.fromString("7c1d3a2e-5b6f-4e8a-9d0c-1f2e3d4c5b6a");

    /** 修饰符名字，只在调试时看到。 */
    private static final String MODIFIER_NAME = "albionmastery:spec_move_speed";

    /** 攻速修饰符的固定 UUID。 */
    private static final UUID ATTACK_SPEED_ID = UUID.fromString("3e9f7a1c-2d4b-4c6e-8f0a-5b7d9e1f3a2c");

    /** 攻速修饰符名字。 */
    private static final String ATTACK_SPEED_NAME = "albionmastery:spec_attack_speed";

    private SpecMoveSpeed() {
    }

    /**
     * 按玩家当前主手武器和流派重算移速修饰符。
     *
     * <p>算出来是 1.0（或者手上不是可专精武器）就只摘不挂，属性上不留任何东西。
     *
     * @param player 玩家
     */
    public static void refresh(ServerPlayer player) {
        SpecializationValues values = currentValues(player);
        apply(player, Attributes.MOVEMENT_SPEED, MODIFIER_ID, MODIFIER_NAME, values.moveSpeed());
        // 通用近战武器走原版攻速属性：攻击间隔倍率 0.75 = 攻速 x(1/0.75)。
        // 近战附属有自己的间隔逻辑（MeleeAttackSpeedMixin），不走这条
        double attackSpeed = values.attackInterval() > 0.0D && currentIsVanillaMelee(player)
                ? 1.0D / values.attackInterval() : 1.0D;
        apply(player, Attributes.ATTACK_SPEED, ATTACK_SPEED_ID, ATTACK_SPEED_NAME, attackSpeed);
    }

    /**
     * 把某个属性上我们挂的修饰符更新成想要的倍率；倍率是 1 就只摘不挂。
     *
     * @param player     玩家
     * @param attribute  属性
     * @param id         修饰符 UUID
     * @param name       修饰符名字
     * @param multiplier 想要的倍率
     */
    private static void apply(ServerPlayer player, net.minecraft.world.entity.ai.attributes.Attribute attribute,
                              UUID id, String name, double multiplier) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier existing = instance.getModifier(id);
        double wanted = multiplier - 1.0D;

        // 已经挂着同样的值就什么都不做：这个方法在主手 NBT 每次变化时都会被叫到，
        // 枪械开一枪就变一次，不能每次都摘了重挂
        if (existing != null && Math.abs(existing.getAmount() - wanted) < 1.0E-9D) {
            return;
        }
        if (existing != null) {
            instance.removeModifier(id);
        }
        if (Math.abs(wanted) < 1.0E-9D) {
            return;
        }
        instance.addTransientModifier(new AttributeModifier(
                id, name, wanted, AttributeModifier.Operation.MULTIPLY_TOTAL));
        AlbionMastery.LOGGER.debug("玩家 {} 的流派修饰符 {} 更新为 x{}",
                player.getGameProfile().getName(), name, multiplier);
    }

    /**
     * 把玩家身上的修饰符摘掉（下线、关闭特化系统时用）。
     *
     * @param player 玩家
     */
    public static void remove(ServerPlayer player) {
        AttributeInstance move = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (move != null && move.getModifier(MODIFIER_ID) != null) {
            move.removeModifier(MODIFIER_ID);
        }
        AttributeInstance attack = player.getAttribute(Attributes.ATTACK_SPEED);
        if (attack != null && attack.getModifier(ATTACK_SPEED_ID) != null) {
            attack.removeModifier(ATTACK_SPEED_ID);
        }
    }

    /**
     * 算玩家现在该有的移速倍率。
     *
     * @param player 玩家
     * @return 倍率；手上不是可专精武器或没选流派时是 1.0
     */
    private static SpecializationValues currentValues(ServerPlayer player) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        if (!snapshot.enableSpecialization) {
            return SpecializationValues.NEUTRAL;
        }
        ResolvedWeapon weapon = WeaponResolver.resolve(player.getMainHandItem(), snapshot.splitMeleeByPack);
        if (weapon == null) {
            return SpecializationValues.NEUTRAL;
        }
        MasteryData data = MasteryHelper.get(player);
        return SpecializationEffects.forData(data, weapon, snapshot);
    }

    /**
     * 主手是不是通用近战武器。
     *
     * @param player 玩家
     * @return 是返回 true
     */
    private static boolean currentIsVanillaMelee(ServerPlayer player) {
        ResolvedWeapon weapon = WeaponResolver.resolve(player.getMainHandItem());
        return weapon != null && WeaponKeys.PROVIDER_VANILLA_MELEE.equals(weapon.providerId());
    }
}
