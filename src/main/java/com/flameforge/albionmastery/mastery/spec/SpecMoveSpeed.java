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
import net.minecraftforge.common.ForgeMod;

import java.util.UUID;
import com.flameforge.albionmastery.weapon.compat.BowSupport;
import net.minecraft.world.item.ItemStack;

/**
 * 把特化的「持有时移速」做成玩家身上的一个移动速度属性修饰符；吃原版属性的近战
 * （见 {@link com.flameforge.albionmastery.weapon.WeaponKeys#VANILLA_ATTRIBUTE_MELEE_PROVIDERS}）
 * 的「攻击间隔」和「攻击距离」也在这里做成攻速、实体交互距离属性修饰符
 * （原版的攻击冷却和够不够得着就是这两个属性决定的）。
 *
 * <p>为什么挂在玩家身上而不是物品上：物品的属性修饰符是按物品算的、和玩家无关，
 * 而特化是玩家选的——同一把枪在两个人手里移速可能不一样。挂在玩家的属性实例上，
 * 主手换武器时重算一次就行。
 *
 * <p>为什么只在服务端做：{@code MOVEMENT_SPEED} 是会自动同步给客户端的属性，
 * 服务端加上修饰符，客户端的移动预测自然就跟着变了，不需要再发一遍包。
 *
 * <p>用固定 UUID 的修饰符，每次刷新先按 UUID 摘掉旧的再挂新的，永远不会叠加出两个。
 * 用 {@code MULTIPLY_TOTAL}：数值是「在最终速度上乘 (1 + x)」，和其它模组的加速减速
 * 相乘而不是相加，不会和别人打架。
 *
 * <p>什么时候要刷新：主手物品变了、特化切换了、登录 / 重生 / 换维度（属性实例是新的）。
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

    /** 攻击距离修饰符的固定 UUID。 */
    private static final UUID REACH_ID = UUID.fromString("5a8c2e4f-7b1d-4f3a-9c6e-2d8b4a0f6e13");

    /** 攻击距离修饰符名字。 */
    private static final String REACH_NAME = "albionmastery:spec_entity_reach";

    /** 拉弓倍速修饰符的固定 UUID。 */
    private static final UUID DRAW_SPEED_ID = UUID.fromString("b4f1c6a9-3e27-4d58-8a1b-6c9d0e5f7a24");

    /** 拉弓倍速修饰符名字。 */
    private static final String DRAW_SPEED_NAME = "albionmastery:visual_draw_speed";

    /** 近战倍速修饰符的固定 UUID。 */
    private static final UUID MELEE_SPEED_ID = UUID.fromString("1d6e8b03-5a4c-42f7-9e1d-83b5c7a0f6d2");

    /** 近战倍速修饰符名字。 */
    private static final String MELEE_SPEED_NAME = "albionmastery:visual_melee_speed";

    /** 换弹倍速修饰符的固定 UUID。 */
    private static final UUID RELOAD_SPEED_ID = UUID.fromString("8c2a5f16-9b70-4e3d-a45c-0f1e6d8b2937");

    /** 换弹倍速修饰符名字。 */
    private static final String RELOAD_SPEED_NAME = "albionmastery:visual_reload_speed";

    /** 施法倍速修饰符的固定 UUID。 */
    private static final UUID CAST_SPEED_ID = UUID.fromString("6b3d9e42-0c85-4a17-b2f6-7d4e1a8c30b5");

    /** 施法倍速修饰符名字。 */
    private static final String CAST_SPEED_NAME = "albionmastery:visual_cast_speed";

    private SpecMoveSpeed() {
    }

    /**
     * 按玩家当前主手武器和特化重算移速修饰符。
     *
     * <p>算出来是 1.0（或者手上不是可专精武器）就只摘不挂，属性上不留任何东西。
     *
     * @param player 玩家
     */
    public static void refresh(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        ResolvedWeapon weapon = currentWeapon(player, main);
        SpecializationValues values = currentValues(player, weapon);
        String provider = weapon == null ? "" : weapon.providerId();
        apply(player, Attributes.MOVEMENT_SPEED, MODIFIER_ID, MODIFIER_NAME, values.moveSpeed());
        // 吃原版攻速属性的近战走这条：攻击间隔倍率 0.75 = 攻速 x(1/0.75)。
        // 哪些来源吃这套属性由 WeaponKeys 那张表说了算，新增来源只要登记一次，
        // 不会再出现「匠魂被漏在外面、攻速特化是空操作」那种改了一半的情况
        boolean vanillaMelee = WeaponKeys.usesVanillaMeleeAttributes(provider);
        apply(player, Attributes.ATTACK_SPEED, ATTACK_SPEED_ID, ATTACK_SPEED_NAME,
                vanillaMelee ? speedOf(values.attackInterval()) : 1.0D);
        // 攻击距离同理只给吃原版属性的那几个来源：近战附属用自己的判定盒，
        // 拔刀剑有自己的斩击范围，都不看这个属性。
        // Forge 的实体交互距离属性会同步给客户端，准星选中实体的距离跟着变
        apply(player, ForgeMod.ENTITY_REACH.get(), REACH_ID, REACH_NAME,
                vanillaMelee ? values.reach() : 1.0D);

        // 下面三个是「画面该放多快」，会同步给所有看得见这个玩家的客户端（见 SpecVisualAttributes）。
        // 拉弓那条同时也是拉弦计时本身的倍速，所以两端读的是同一个数
        apply(player, SpecVisualAttributes.DRAW_SPEED.get(), DRAW_SPEED_ID, DRAW_SPEED_NAME,
                BowSupport.isBowOrCrossbow(main) ? positive(values.drawSpeed()) : 1.0D);
        apply(player, SpecVisualAttributes.MELEE_SPEED.get(), MELEE_SPEED_ID, MELEE_SPEED_NAME,
                WeaponKeys.PROVIDER_LRTACTICAL_MELEE.equals(provider)
                        ? speedOf(values.attackInterval()) : 1.0D);
        apply(player, SpecVisualAttributes.RELOAD_SPEED.get(), RELOAD_SPEED_ID, RELOAD_SPEED_NAME,
                WeaponKeys.PROVIDER_TACZ_GUN.equals(provider) ? speedOf(values.reloadTime()) : 1.0D);
        // 正在拉的那把弓说了算：主手是剑、副手在拉弓时，上面那条会把倍速按主手写成 1.0，
        // 而刷新随时可能被别的事（涨经验、物品耐久变化）触发，拉到一半就变回原速
        if (player.isUsingItem()) {
            refreshDrawSpeed(player, player.getUseItem());
        }
    }

    /**
     * 正在使用的弓弩单独刷一次拉弓倍速。
     *
     * <p>副手也能拉弓，而上面那条是按主手算的。开始使用的那一刻按真正在用的那把重挂一次，
     * 拉弦计时和所有人看到的画面就都按这把弓的特化走。
     *
     * @param player 玩家
     * @param using  正在使用的物品
     */
    public static void refreshDrawSpeed(ServerPlayer player, ItemStack using) {
        if (!BowSupport.isBowOrCrossbow(using)) {
            return;
        }
        SpecializationValues values = currentValues(player, currentWeapon(player, using));
        apply(player, SpecVisualAttributes.DRAW_SPEED.get(), DRAW_SPEED_ID, DRAW_SPEED_NAME,
                positive(values.drawSpeed()));
    }

    /**
     * 开始施法时按这个法术的施法时间特化刷新施法倍速。
     *
     * <p>施法前摇的动作按这个倍速播。为什么不在 {@link #refresh} 里按主手算：
     * 法术可能是副手的卷轴放的，每个法术的配置也各不相同，只有开始施法的那一刻才知道是哪个。
     *
     * @param player     玩家
     * @param timeFactor 施法时间倍率；1.0 表示不变
     */
    public static void refreshCastSpeed(ServerPlayer player, double timeFactor) {
        apply(player, SpecVisualAttributes.CAST_SPEED.get(), CAST_SPEED_ID, CAST_SPEED_NAME, speedOf(timeFactor));
    }

    /**
     * 耗时倍率换成播放倍速：0.8 倍耗时 = 1.25 倍速。
     *
     * @param timeFactor 耗时倍率
     * @return 倍速；倍率不合理时返回 1.0
     */
    private static double speedOf(double timeFactor) {
        return timeFactor > 0.0D && Double.isFinite(timeFactor) ? 1.0D / timeFactor : 1.0D;
    }

    /**
     * 挡掉不合理的倍速。
     *
     * @param value 倍速
     * @return 合理的倍速
     */
    private static double positive(double value) {
        return value > 0.0D && Double.isFinite(value) ? value : 1.0D;
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
        AlbionMastery.LOGGER.debug("玩家 {} 的特化修饰符 {} 更新为 x{}",
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
        AttributeInstance reach = player.getAttribute(ForgeMod.ENTITY_REACH.get());
        if (reach != null && reach.getModifier(REACH_ID) != null) {
            reach.removeModifier(REACH_ID);
        }
        removeOne(player, SpecVisualAttributes.DRAW_SPEED.get(), DRAW_SPEED_ID);
        removeOne(player, SpecVisualAttributes.MELEE_SPEED.get(), MELEE_SPEED_ID);
        removeOne(player, SpecVisualAttributes.RELOAD_SPEED.get(), RELOAD_SPEED_ID);
        removeOne(player, SpecVisualAttributes.CAST_SPEED.get(), CAST_SPEED_ID);
    }

    /**
     * 摘掉某个属性上我们挂的修饰符。
     *
     * @param player    玩家
     * @param attribute 属性
     * @param id        修饰符 UUID
     */
    private static void removeOne(ServerPlayer player,
                                  net.minecraft.world.entity.ai.attributes.Attribute attribute, UUID id) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null && instance.getModifier(id) != null) {
            instance.removeModifier(id);
        }
    }

    /**
     * 算玩家现在该有的移速倍率。
     *
     * @param player 玩家
     * @return 倍率；手上不是可专精武器或没选特化时是 1.0
     */
    private static SpecializationValues currentValues(ServerPlayer player,
                                                      @javax.annotation.Nullable ResolvedWeapon weapon) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        if (!snapshot.enableSpecialization || weapon == null) {
            return SpecializationValues.NEUTRAL;
        }
        MasteryData data = MasteryHelper.get(player);
        return SpecializationEffects.forData(data, weapon, snapshot);
    }

    /**
     * 这件物品解析成哪把武器。
     *
     * @param player 玩家
     * @param stack  物品
     * @return 武器；解析不出来或者特化系统关着返回 null
     */
    @javax.annotation.Nullable
    private static ResolvedWeapon currentWeapon(ServerPlayer player, ItemStack stack) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        if (!snapshot.enableSpecialization) {
            return null;
        }
        return WeaponResolver.resolve(stack, snapshot.splitMeleeByPack);
    }
}
