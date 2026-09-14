package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;

/**
 * 查询某个玩家当前生效的特化数值。
 *
 * <p><b>两端的数据不在同一个地方，这点是这个类存在的全部理由。</b>
 * 服务端的专精数据挂在玩家的 Capability 上；客户端的 Capability 是<b>空的</b>——
 * 服务端从来没往那里同步过，客户端那份数据在 {@code ClientMasteryCache} 里。
 * 早先这里两端都去读 Capability，结果就是客户端永远查不到特化：
 * 换弹动画、攻速预判全都按原始数值跑，服务端却按特化后的数值算，看起来就像「特化没生效」。
 *
 * <p>所以这里按端分流：逻辑端走 Capability，客户端走同步缓存。
 * 客户端那条路径用 {@link DistExecutor} 隔开，专用服务端不会加载到任何客户端类。
 */
public final class SpecializationEffects {

    private SpecializationEffects() {
    }

    /**
     * 取某个玩家手上这件武器当前生效的数值。
     *
     * <p>这个方法会被 Mixin 在两端同时调用，端的判断在里面做掉，调用方不用操心。
     *
     * @param entity 实体，不是玩家或为空时返回中性值
     * @param stack  武器物品
     * @return 生效数值，永不为 null
     */
    public static SpecializationValues forStack(@Nullable LivingEntity entity,
                                                @Nullable ItemStack stack) {
        if (!(entity instanceof Player player) || stack == null || stack.isEmpty()) {
            return SpecializationValues.NEUTRAL;
        }
        boolean client = player.level().isClientSide();
        MasteryConfig.Snapshot snapshot = client ? clientSnapshot() : MasteryConfig.serverSnapshot();
        ResolvedWeapon weapon = WeaponResolver.resolve(stack, snapshot.splitMeleeByPack);
        if (weapon == null) {
            return SpecializationValues.NEUTRAL;
        }
        MasteryData data = client ? clientData() : MasteryHelper.get(player);
        return forData(data, weapon, snapshot);
    }

    /**
     * 从客户端同步缓存取数据。
     *
     * @return 客户端数据；不在客户端时返回 null
     */
    /**
     * 按已经解析好的武器取当前生效的数值，两端通用。
     *
     * <p>法术不是物品：施法、冷却、法术等级这些事件里只有法术本身，没有物品堆可解析，
     * 走这个入口。取数据的方式和 {@link #forStack} 一样，客户端读缓存、服务端读 Capability。
     *
     * @param entity 玩家
     * @param weapon 武器
     * @return 数值；不是玩家或没数据时是中性值
     */
    public static SpecializationValues forWeapon(@Nullable LivingEntity entity,
                                                 @Nullable ResolvedWeapon weapon) {
        if (!(entity instanceof Player player) || weapon == null) {
            return SpecializationValues.NEUTRAL;
        }
        boolean client = player.level().isClientSide();
        MasteryConfig.Snapshot snapshot = client ? clientSnapshot() : MasteryConfig.serverSnapshot();
        MasteryData data = client ? clientData() : MasteryHelper.get(player);
        return forData(data, weapon, snapshot);
    }

    /**
     * 按已经解析好的武器算专精加成明细，两端通用。
     *
     * @param entity 玩家
     * @param weapon 武器
     * @return 加成明细；不是玩家或没数据时返回 null
     */
    @Nullable
    public static MasteryHelper.Bonus bonusFor(@Nullable LivingEntity entity,
                                               @Nullable ResolvedWeapon weapon) {
        if (!(entity instanceof Player player) || weapon == null) {
            return null;
        }
        boolean client = player.level().isClientSide();
        MasteryConfig.Snapshot snapshot = client ? clientSnapshot() : MasteryConfig.serverSnapshot();
        MasteryData data = client ? clientData() : MasteryHelper.get(player);
        if (data == null) {
            return null;
        }
        return MasteryHelper.computeBonus(data, weapon, snapshot);
    }

    @Nullable
    private static MasteryData clientData() {
        return DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                () -> () -> com.flameforge.albionmastery.client.ClientMasteryCache.data());
    }

    /**
     * 从客户端同步缓存取配置快照。
     *
     * @return 服务端下发的快照；拿不到时退回默认值
     */
    private static MasteryConfig.Snapshot clientSnapshot() {
        MasteryConfig.Snapshot snapshot = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                () -> () -> com.flameforge.albionmastery.client.ClientMasteryCache.snapshot());
        return snapshot == null ? MasteryConfig.Snapshot.createDefault() : snapshot;
    }

    /**
     * 取某个<b>武器类型</b>上生效的数值。
     *
     * <p>特化是按类型选的，不是按来源：枪械底下的步枪和霰弹枪玩法差得远，
     * 让它们共用一个流派没有意义。所以这里的键是类型键。
     *
     * @param data     专精数据，可为 null
     * @param typeKey  类型键
     * @param snapshot 配置快照
     * @return 生效数值，永不为 null
     */
    public static SpecializationValues forData(@Nullable MasteryData data, String typeKey,
                                               MasteryConfig.Snapshot snapshot) {
        return forData(data, typeKey, null, snapshot);
    }

    /**
     * 按武器算当前生效的数值（门槛看武器所属的类型和来源）。
     *
     * @param data     玩家数据
     * @param weapon   武器
     * @param snapshot 配置快照
     * @return 两个槽叠加后的数值
     */
    public static SpecializationValues forData(@Nullable MasteryData data, ResolvedWeapon weapon,
                                               MasteryConfig.Snapshot snapshot) {
        return forData(data, weapon.typeKey(), weapon.weaponKey(), snapshot);
    }

    /**
     * 两个槽位各自看门槛，过了的叠在一起。
     *
     * <p>门槛只看中圈（类型）和内圈（来源），{@code weaponKey} 目前不参与判断，留着是为了以后要按武器限制时不用改调用点。
     *
     * @param data      玩家数据
     * @param typeKey   类型键
     * @param weaponKey 武器键，可为 null
     * @param snapshot  配置快照
     * @return 叠加后的数值
     */
    public static SpecializationValues forData(@Nullable MasteryData data, String typeKey,
                                               @Nullable String weaponKey,
                                               MasteryConfig.Snapshot snapshot) {
        if (data == null || !snapshot.enableSpecialization) {
            return SpecializationValues.NEUTRAL;
        }
        SpecializationValues result = null;
        for (int slot = 1; slot <= WeaponKeys.SPEC_SLOTS; slot++) {
            if (!isSlotActive(data, typeKey, weaponKey, slot, snapshot)) {
                continue;
            }
            Specialization spec = Specialization.byKey(data.getSpecialization(typeKey, slot));
            // 流派归属的来源必须和这个类型键的来源对得上，
            // 否则就是存档里留下的脏数据（比如类型被重新分过组）
            if (spec == null || spec.isStandard()
                    || !spec.sourceId().equals(WeaponKeys.providerOf(typeKey))) {
                continue;
            }
            SpecializationValues values = snapshot.specValues(spec);
            result = result == null ? values : SpecializationValues.combine(result, values);
        }
        return result == null ? SpecializationValues.NEUTRAL : result;
    }

    /**
     * 某个槽位对某件武器此刻能不能生效：武器所属的类型（中圈）和来源（内圈）都达到这个槽的等级门槛。
     *
     * @param data      玩家数据
     * @param typeKey   类型键
     * @param weaponKey 武器键，可为 null（只看类型）
     * @param slot      槽位
     * @param snapshot  配置快照
     * @return 能生效返回 true
     */
    public static boolean isSlotActive(MasteryData data, String typeKey, @Nullable String weaponKey,
                                       int slot, MasteryConfig.Snapshot snapshot) {
        // 只看中圈（类型）和内圈（来源），外圈（单件武器）不设门槛：
        // 类型和来源练到了，这一类下面的每件武器都吃到流派
        return isSlotUnlocked(data, typeKey, slot, snapshot);
    }

    /**
     * 某个槽位能不能选了：本类型（中圈）和所属来源（内圈）都达到门槛。
     *
     * @param data     玩家数据
     * @param typeKey  类型键
     * @param slot     槽位
     * @param snapshot 配置快照
     * @return 可以选返回 true
     */
    public static boolean isSlotUnlocked(@Nullable MasteryData data, String typeKey, int slot,
                                         MasteryConfig.Snapshot snapshot) {
        if (data == null || !snapshot.enableSpecialization) {
            return false;
        }
        String providerId = WeaponKeys.providerOf(typeKey);
        if (providerId == null) {
            return false;
        }
        int need = snapshot.specSlotLevel(slot);
        return data.getTypeLevel(typeKey) >= need
                && data.getOverallLevel(WeaponKeys.overallKey(providerId)) >= need;
    }

    /**
     * 目标血量超过这个比例就算「接近满血」。
     *
     * <p>不用严格的 100%：怪物身上挂个中毒、掉一格血就不算满血了，那条件几乎永远不成立。
     */
    private static final double FRESH_RATIO = 0.95D;

    /**
     * 把所有「看情况」的倍率算成一个数。
     *
     * <p>这些条件放在一起算，是为了三个来源的战斗处理器只调一次就够，不用各写一遍，
     * 也不会出现「枪械支持残血斩杀、近战忘了加」这种漏。用不到的维度是中性值，乘上去等于没乘。
     *
     * @param values   当前生效的特化数值
     * @param attacker 攻击者，可为 null
     * @param target   目标，可为 null
     * @param headShot 是否爆头；不适用（近战、拔刀剑）时传 null
     * @return 综合倍率，永远是正数
     */
    public static double conditionalMultiplier(SpecializationValues values,
                                               @Nullable LivingEntity attacker,
                                               @Nullable LivingEntity target,
                                               @Nullable Boolean headShot) {
        double multiplier = 1.0D;

        if (target != null) {
            multiplier *= target instanceof Player ? values.vsPlayerDamage() : values.vsMobDamage();
            // 有没有护甲，直接看护甲值。护甲为 0 的目标吃不到「贯甲」的好处，
            // 这正是那个流派的代价，不是漏算
            multiplier *= target.getArmorValue() > 0.0F
                    ? values.armoredDamage() : values.unarmoredDamage();

            double ratio = healthRatio(target);
            if (ratio <= values.executeThreshold()) {
                multiplier *= values.executeDamage();
            } else if (ratio >= FRESH_RATIO) {
                multiplier *= values.freshDamage();
            }
        }

        if (headShot != null) {
            multiplier *= headShot ? values.headShotDamage() : values.bodyShotDamage();
        }

        if (attacker != null && values.desperationDamage() != 1.0D) {
            double ratio = healthRatio(attacker);
            double threshold = values.desperationThreshold();
            if (threshold > 0.0D && ratio < threshold) {
                // 在「开始生效」和「吃满」两条血线之间线性过渡：
                // 做成一刀切的话玩家会在阈值上下反复横跳，手感很怪；
                // 而让它一直插值到 0 血才吃满，等于加成只在快死的那一瞬间有意义
                double floor = Math.min(values.desperationFloor(), threshold);
                double span = threshold - floor;
                double t = span <= 0.0D ? 1.0D
                        : Math.max(0.0D, Math.min(1.0D, (threshold - ratio) / span));
                multiplier *= 1.0D + (values.desperationDamage() - 1.0D) * t;
            }
        }
        return Math.max(0.0D, multiplier);
    }

    /**
     * 掷一次暴击。
     *
     * <p>暴击和「看情况」的那些倍率是独立的一层：先算条件倍率，再乘暴击，
     * 三个来源的战斗处理器各自在改伤害之前调一次。概率为 0 的流派直接返回 1.0，不消耗随机数。
     *
     * @param values 当前生效的特化数值
     * @param random 随机源，用攻击者自己的 {@code getRandom()}
     * @return 暴击了返回暴击倍率，否则 1.0
     */
    public static double critMultiplier(SpecializationValues values, RandomSource random) {
        double chance = values.critChance();
        if (chance <= 0.0D || values.critDamage() <= 1.0D) {
            return 1.0D;
        }
        return random.nextDouble() < chance ? values.critDamage() : 1.0D;
    }

    /**
     * 算一个实体的血量比例。
     *
     * @param entity 实体
     * @return 0 到 1 之间；最大血量异常时按满血算
     */
    private static double healthRatio(LivingEntity entity) {
        float max = entity.getMaxHealth();
        if (max <= 0.0F) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, entity.getHealth() / max));
    }

    /**
     * 判断某个武器类型的特化是否已经解锁。
     *
     * <p>两个条件都要满足：该来源的<b>总体专精</b>满级，<b>并且</b>这个类型自己也满级。
     * 前者代表「你把这个模组玩透了」，后者代表「你把这一类武器玩透了」，
     * 缺一个都不该拿到只属于这一类的流派。
     *
     * <p>上限取配置里的 maxLevel，调低了解锁也跟着变早，不是写死的 100 级。
     *
     * @param data     专精数据，可为 null
     * @param typeKey  类型键
     * @param snapshot 配置快照
     * @return 已解锁返回 true
     */
    public static boolean isUnlocked(@Nullable MasteryData data, String typeKey,
                                     MasteryConfig.Snapshot snapshot) {
        if (data == null || !snapshot.enableSpecialization) {
            return false;
        }
        // 「解锁」= 第一槽可以选了
        return isSlotUnlocked(data, typeKey, 1, snapshot);
    }
}
