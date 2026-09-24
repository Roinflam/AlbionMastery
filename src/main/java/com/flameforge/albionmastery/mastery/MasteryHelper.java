// 路径：src/main/java/com/flameforge/albionmastery/mastery/MasteryHelper.java
package com.flameforge.albionmastery.mastery;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;

/**
 * 专精加成计算与经验发放的核心逻辑。
 *
 * <p>双端共用：服务端拿它算实际伤害与发经验，客户端拿它算界面上显示的加成。
 * 所有方法都显式接收 {@link MasteryData} 与 {@link MasteryConfig.Snapshot}，
 * 不去内部取「当前端」的数据，这样两端算出来的结果必然一致。
 */
public final class MasteryHelper {

    private MasteryHelper() {
    }

    /**
     * 取出玩家的专精数据。
     *
     * @param player 玩家，可为 null
     * @return 专精数据；Capability 不可用时返回 null
     */
    @Nullable
    public static MasteryData get(@Nullable Player player) {
        if (player == null) {
            return null;
        }
        return player.getCapability(MasteryCapability.MASTERY).orElse(null);
    }

    /**
     * 计算一件武器的伤害加成明细。
     *
     * <p>加成由四部分叠加：
     * <ul>
     *     <li>总体专精：每级为该来源下所有武器提供加成（默认 +0.5%）</li>
     *     <li>类型专精：每级为该类型下所有武器提供加成（默认 +1%）</li>
     *     <li>本武器专精：每级只作用于自身（默认 +2%）</li>
     *     <li>同类型联动：该类型下<b>所有</b>武器的等级之和（<b>含本武器自己</b>），
     *         每级少量作用于本武器（默认 +0.1%）</li>
     * </ul>
     *
     * @param data     玩家专精数据，可为 null（视为全 0 级）
     * @param weapon   武器定位
     * @param snapshot 生效中的数值配置
     * @return 加成明细
     */
    public static Bonus computeBonus(@Nullable MasteryData data, ResolvedWeapon weapon,
                                     MasteryConfig.Snapshot snapshot) {
        if (data == null) {
            return new Bonus(0, 0, 0, 0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, false);
        }

        int overallLevel = data.getOverallLevel(weapon.overallKey());
        int typeLevel = data.getTypeLevel(weapon.typeKey());
        int weaponLevel = data.getWeaponLevel(weapon.weaponKey());
        // 该类型下所有武器的等级之和，含本武器自己——自己练出来的等级也该算进联动里
        int typeLevelSum = data.getTypeLevelSum(weapon.typeKey());

        // 不同武器体系的基础强度跨度非常大：原版剑和高阶模组枪 / 法术拿同一套专精百分比，
        // 要么原版完全没感觉，要么模组武器直接失控。先按来源取得专精倍率，再统一作用到三层
        // （总体 / 类型 / 单武器）以及同类型联动。这个倍率只属于“专精等级加成”，不碰特化。
        double sourceMultiplier = snapshot.masteryDamageMultiplier(weapon.providerId());
        double fromOverall = overallLevel * snapshot.overallDamagePerLevel * sourceMultiplier;
        double fromType = typeLevel * snapshot.typeDamagePerLevel * sourceMultiplier;
        double fromSelf = weaponLevel * snapshot.weaponDamagePerLevel * sourceMultiplier;
        double fromOthers = typeLevelSum * snapshot.weaponCrossDamagePerLevel * sourceMultiplier;
        double raw = fromOverall + fromType + fromSelf + fromOthers;

        boolean capped = raw > snapshot.maxTotalDamageBonus;
        double total = capped ? snapshot.maxTotalDamageBonus : raw;

        return new Bonus(overallLevel, typeLevel, weaponLevel, typeLevelSum,
                fromOverall, fromType, fromSelf, fromOthers, total, capped);
    }

    /**
     * 给玩家发放一次专精经验，三层同时入账。
     *
     * <p>三层拿到的<b>经验数量完全相同</b>，难度差异全在各自的等级曲线上：
     * 类型与总体的升级所需经验分别是单件武器的若干倍。这样「类型是 3 倍肝度」是个确定的数，
     * 不会因为玩家换了几把武器就变——之前用「按比例少发经验」的做法就有这个问题，
     * 用的武器越多，类型反而升得越快。
     *
     * <p>只应在服务端调用。
     *
     * @param data     玩家专精数据
     * @param weapon   武器定位
     * @param amount   本次发放的经验
     * @param snapshot 生效中的数值配置
     * @return 是否有任意一级发生变化
     */
    public static boolean awardXp(MasteryData data, ResolvedWeapon weapon, long amount,
                                  MasteryConfig.Snapshot snapshot) {
        return awardXp(data, weapon, amount, snapshot, true);
    }

    /**
     * 发放专精经验，可指定要不要走学习点倍率。
     *
     * <p>打怪、命中这类「玩」出来的经验要乘学习点；管理员用指令直接给的不乘——
     * 指令里写多少就该进多少，再被一个看不见的倍率放大只会让人算不明白。
     *
     * @param weapon     武器
     * @param data       玩家数据
     * @param amount     经验值
     * @param snapshot   配置快照
     * @param applyStudy 是否应用学习点倍率
     * @return 是否有任意一层升级了
     */
    public static boolean awardXp(MasteryData data, ResolvedWeapon weapon, long amount,
                                  MasteryConfig.Snapshot snapshot, boolean applyStudy) {
        return awardXp(data, weapon, amount, snapshot, applyStudy, null);
    }

    /**
     * 同上，外加一张「这一批里类型和总体已经记过哪些」的表。
     *
     * <p>一次击杀要发给好几件武器时（一个法术的几个符文、主副手）传同一张表进来：
     * 武器各记各的，类型和总体同一个键只记第一次，总体经验不会因为手里武器多就翻倍。
     *
     * @param credited 已记过的类型 / 总体键，调用方共用；传 null 表示不去重
     */
    public static boolean awardXp(MasteryData data, ResolvedWeapon weapon, long amount,
                                  MasteryConfig.Snapshot snapshot, boolean applyStudy,
                                  @Nullable java.util.Set<String> credited) {
        if (amount <= 0L) {
            return false;
        }
        if (applyStudy) {
            // 特化的经验倍率和管理员设的学习倍率是两回事，分别乘：前者是玩家自己选的，后者是管理员给的
            double specGain = SpecializationEffects.forData(data, weapon, snapshot).xpGain();
            if (specGain != 1.0D) {
                amount = Math.max(1L, roundRandomly(amount * specGain));
            }
            double multiplier = data.getStudyMultiplier();
            if (multiplier != 1.0D) {
                // 保底 1：倍率 0.5 时不该让每次击杀都变成 0 经验
                amount = Math.max(1L, roundRandomly(amount * multiplier));
            }
            // 倍率被设成 0 的玩家就是不涨经验，这是管理员的本意，直接返回
            if (multiplier <= 0.0D) {
                return false;
            }
        }
        boolean levelUp = data.addWeaponXp(weapon.weaponKey(), weapon.typeKey(), amount,
                snapshot.weaponCurve());
        if (credited == null || credited.add("t" + weapon.typeKey())) {
            levelUp |= data.addTypeXp(weapon.typeKey(), amount, snapshot.typeCurve());
        }
        if (credited == null || credited.add("o" + weapon.overallKey())) {
            levelUp |= data.addOverallXp(weapon.overallKey(), amount, snapshot.overallCurve());
        }
        return levelUp;
    }

    /**
     * 按概率取整：2.3 有三成概率取 3，其余取 2，长期平均正好是 2.3。
     *
     * <p>向下取整的话，一次只给 1 点经验的击杀乘 1.2 永远还是 1，经验加成的特化等于没用。
     */
    private static long roundRandomly(double value) {
        long whole = (long) Math.floor(value);
        return java.util.concurrent.ThreadLocalRandom.current().nextDouble() < value - whole ? whole + 1L : whole;
    }

    /**
     * 伤害加成明细。所有 {@code from*} 与 {@code total} 都是「加成比例」，
     * 即 0.35 表示 +35%，最终伤害倍率是 {@code 1 + total}。
     *
     * @param overallLevel  总体专精等级
     * @param typeLevel     类型专精等级
     * @param weaponLevel   本武器专精等级
     * @param typeLevelSum  该类型下所有武器的等级之和（含本武器）
     * @param fromOverall   来自总体专精的加成
     * @param fromType      来自类型专精的加成
     * @param fromSelf      来自本武器专精的加成
     * @param fromOthers    来自同类型联动的加成
     * @param total         夹取上限之后的总加成
     * @param capped        是否触发了上限
     */
    public record Bonus(int overallLevel,
                        int typeLevel,
                        int weaponLevel,
                        int typeLevelSum,
                        double fromOverall,
                        double fromType,
                        double fromSelf,
                        double fromOthers,
                        double total,
                        boolean capped) {

        /**
         * @return 最终伤害倍率
         */
        public double multiplier() {
            return 1.0D + this.total;
        }
    }
}


