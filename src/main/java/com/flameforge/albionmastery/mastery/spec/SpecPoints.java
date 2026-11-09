package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecRules;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.weapon.WeaponKeys;

import java.util.Map;

/**
 * 改装点和突破点的账：有多少、花了多少、还剩多少。
 *
 * <p>点数<b>不存档</b>，每次按当前等级现算，存档里只记「各特化升到了几级」。
 * 所以老存档的等级天然就折算成了点数，服主改配置后点数也跟着变；
 * 改少了导致「花的比有的多」时，登录时由 {@link SpecMigration} 把那一份整份退掉。
 *
 * <p>两端通用：服务端判定升级，客户端界面显示，都走这里，数字不会对不上。
 */
public final class SpecPoints {

    private SpecPoints() {
    }

    /**
     * 某个来源一共有多少改装点：来源等级给的 + 段位给的。
     *
     * @param data       玩家数据
     * @param providerId 来源 ID
     * @param snapshot   配置快照
     * @return 点数
     */
    public static int modPointsTotal(MasteryData data, String providerId, MasteryConfig.Snapshot snapshot) {
        SpecRules rules = snapshot.specRules;
        int level = data.getOverallLevel(WeaponKeys.overallKey(providerId));
        return rules.modPointsFromLevel(level, snapshot.maxLevel)
                + rules.modPointsFromRank(SpecializationEffects.rankOf(data, snapshot));
    }

    /**
     * 某个来源已经花掉的改装点（这个来源所有特化的普通版等级折算）。
     *
     * @param data       玩家数据
     * @param providerId 来源 ID
     * @param rules      规则
     * @return 点数
     */
    public static int modPointsSpent(MasteryData data, String providerId, SpecRules rules) {
        int spent = 0;
        for (Specialization spec : Specialization.forSource(providerId)) {
            if (!spec.isStandard()) {
                spent += rules.normalSpent(data.getSpecLevel(spec.key()));
            }
        }
        return spent;
    }

    /**
     * @return 某个来源还剩多少改装点（花超了是负数）
     */
    public static int modPointsAvailable(MasteryData data, String providerId, MasteryConfig.Snapshot snapshot) {
        return modPointsTotal(data, providerId, snapshot) - modPointsSpent(data, providerId, snapshot.specRules);
    }

    /**
     * 某个类型一共有多少突破点：类型等级给的 + 满级武器给的。
     *
     * @param data     玩家数据
     * @param typeKey  类型键
     * @param snapshot 配置快照
     * @return 点数
     */
    public static int breakthroughPointsTotal(MasteryData data, String typeKey, MasteryConfig.Snapshot snapshot) {
        SpecRules rules = snapshot.specRules;
        return rules.breakthroughPointsFromLevel(data.getTypeLevel(typeKey), snapshot.maxLevel)
                + rules.breakthroughPointsFromMaxed(data.getMaxedWeaponCount(typeKey));
    }

    /**
     * 某个类型已经花掉的突破点。
     *
     * @param data    玩家数据
     * @param typeKey 类型键
     * @param rules   规则
     * @return 点数
     */
    public static int breakthroughPointsSpent(MasteryData data, String typeKey, SpecRules rules) {
        Map<String, Integer> perType = data.getBreakthroughsView().get(typeKey);
        if (perType == null) {
            return 0;
        }
        int spent = 0;
        for (int star : perType.values()) {
            spent += rules.breakthroughSpent(star);
        }
        return spent;
    }

    /**
     * @return 某个类型还剩多少突破点（花超了是负数）
     */
    public static int breakthroughPointsAvailable(MasteryData data, String typeKey,
                                                  MasteryConfig.Snapshot snapshot) {
        return breakthroughPointsTotal(data, typeKey, snapshot)
                - breakthroughPointsSpent(data, typeKey, snapshot.specRules);
    }

    /**
     * 某一池重置还要等多少毫秒。冷却按池各记各的，没重置过的池没有冷却。
     *
     * @param data  玩家数据
     * @param pool  点数池：来源 ID（改装点）或类型键（突破点）
     * @param rules 规则
     * @param now   现在（毫秒）
     * @return 剩余毫秒，0 = 现在就能重置
     */
    public static long respecRemainingMillis(MasteryData data, String pool, SpecRules rules, long now) {
        long at = data.getRespecAt(pool);
        if (at <= 0L) {
            return 0L;
        }
        long cooldown = rules.respecCooldownSeconds * 1000L;
        long elapsed = now - at;
        // 系统时间被往回调过时 elapsed 是负数：按冷却已过算，别把玩家永久锁死
        if (elapsed < 0L || elapsed >= cooldown) {
            return 0L;
        }
        return cooldown - elapsed;
    }
}
