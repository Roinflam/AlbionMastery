package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.config.SpecializationValues;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把一组特化数值翻译成「得到什么 / 付出什么」两串说明。
 *
 * <p>以前每个特化的得失是语言文件里手写的一句话，数字写死在文本里。服主在配置里把重型弹药的伤害
 * 从 2.5 改成 2.0，界面上照样写着「2.5 倍」；给特化加一个维度，也得记得回去改两份语言文件。
 * 现在直接按服务端下发的数值生成：显示的永远是实际生效的数，加维度只要在这里登记一行。
 *
 * <p>规则：
 * <ul>
 *     <li>纯倍率项：变好的进「得」，变差的进「失」。时间、散布、后座、耗费这类项<b>越小越好</b>，
 *         登记时标出来；</li>
 *     <li>几个互相依赖的项合成一句：暴击率 + 暴击倍率、残血斩杀的倍率 + 血线、背水三项、连击两项；</li>
 *     <li>概率 / 比例 / 层数类只有「给了」才写，都是好处。</li>
 * </ul>
 * 同一个维度在不同来源上用各自模组的叫法：先找 {@code spec_stat.<来源>.<项>}，没有再用通用的
 * {@code spec_stat.<项>}。比如「伤害」在铁魔法和魔艺上叫「法术强度」、「弹速」在弓上叫「箭速」，
 * 拔刀剑的特殊攻击沿用它自己的叫法「SA」。
 * 倍率小于 2 写成百分比（+25%、−30%），2 倍及以上写成「×2.5」，比「+150%」好读。
 */
public final class SpecDescriber {

    /** 语言键前缀：单项的名字。 */
    private static final String STAT = "gui.albionmastery.spec_stat.";

    /** 语言键前缀：合成句。 */
    private static final String DESC = "gui.albionmastery.spec_desc.";

    /** 语言键前缀：合成句拆开后每一项的短名字。 */
    private static final String SHORT = "gui.albionmastery.spec_short.";

    /** 越小越好的倍率项。 */
    private static final Set<String> LOWER_IS_BETTER = Set.of(
            "reloadTime", "attackInterval", "durability", "manaCost", "spellCooldown", "castTime",
            "gunVolume", "recoil", "adsTime", "hipSpread", "aimSpread", "drawTime", "boltTime", "sprintTime",
            "damageTaken");

    /**
     * 按显示顺序排的纯倍率项。
     *
     * <p>不直接遍历 {@link SpecializationValues#KEYS}：那张表的顺序是网络协议，只能往后追加，
     * 新加的枪械项全挤在最后；这里按「伤害 → 条件伤害 → 弹药 → 手感 → 其他」排，读起来有条理。
     */
    private static final List<String> MULTIPLIERS = List.of(
            "damage", "summonedDamage", "directDamage", "saDamage",
            "vsPlayerDamage", "vsMobDamage", "armoredDamage", "unarmoredDamage",
            "headShotDamage", "bodyShotDamage", "firstShotDamage", "ricochetDamage", "effectDamage",
            "fireRate", "magazine", "reloadTime", "bulletSpeed",
            "recoil", "adsTime", "hipSpread", "aimSpread", "drawTime", "boltTime", "sprintTime",
            "attackInterval", "reach", "knockback", "drawSpeed", "castTime", "spellCooldown", "manaCost",
            "damageTaken", "moveSpeed", "gunVolume", "durability", "soulGain", "xpGain");

    /** 浮点比较容差。 */
    private static final double EPSILON = 1.0E-6D;

    private SpecDescriber() {
    }

    /**
     * 生成得失两串说明。
     *
     * @param values 特化数值
     * @return 得失说明，每一项是一小句
     */
    public static Lines describe(SpecializationValues values, String sourceId) {
        List<Component> gains = new ArrayList<>();
        List<Component> costs = new ArrayList<>();

        for (String key : MULTIPLIERS) {
            double value = values.value(key);
            if (isNeutral(value, 1.0D)) {
                continue;
            }
            boolean better = LOWER_IS_BETTER.contains(key) ? value < 1.0D : value > 1.0D;
            Component line = Component.translatable(localized(STAT, sourceId, key))
                    .append(" " + formatMultiplier(value));
            (better ? gains : costs).add(line);
        }

        if (values.critChance() > EPSILON && values.critDamage() > 1.0D + EPSILON) {
            gains.add(Component.translatable(localized(DESC, sourceId, "crit"),
                    formatPercent(values.critChance()), "×" + formatNumber(values.critDamage())));
        }
        addConditional(gains, costs, sourceId, "near", values.nearDamage());
        addConditional(gains, costs, sourceId, "far", values.farDamage());
        addConditional(gains, costs, sourceId, "last_round", values.lastRoundDamage());
        if (!isNeutral(values.executeDamage(), 1.0D)) {
            (values.executeDamage() > 1.0D ? gains : costs).add(Component.translatable(DESC + "execute",
                    formatPercent(values.executeThreshold()), formatMultiplier(values.executeDamage())));
        }
        if (!isNeutral(values.backstabDamage(), 1.0D)) {
            (values.backstabDamage() > 1.0D ? gains : costs).add(Component.translatable(DESC + "backstab",
                    formatMultiplier(values.backstabDamage())));
        }
        if (!isNeutral(values.freshDamage(), 1.0D)) {
            (values.freshDamage() > 1.0D ? gains : costs).add(Component.translatable(DESC + "fresh",
                    formatMultiplier(values.freshDamage())));
        }
        if (!isNeutral(values.desperationDamage(), 1.0D)) {
            double floor = Math.min(values.desperationFloor(), values.desperationThreshold());
            (values.desperationDamage() > 1.0D ? gains : costs).add(Component.translatable(DESC + "desperation",
                    formatPercent(values.desperationThreshold()), formatPercent(floor),
                    formatMultiplier(values.desperationDamage())));
        }
        if (values.hasCombo()) {
            gains.add(Component.translatable(DESC + "combo",
                    formatPercent(values.comboStep()), (int) Math.round(values.comboMax())));
        }
        if (values.hasFrenzy()) {
            gains.add(Component.translatable(DESC + "frenzy",
                    formatPercent(values.frenzyStep()), (int) Math.round(values.frenzyMax())));
        }
        if (values.killHeal() > EPSILON) {
            gains.add(Component.translatable(DESC + "kill_heal", formatPercent(values.killHeal())));
        }
        addChance(gains, sourceId, "ignite", values.igniteChance());
        addChance(gains, sourceId, "slow", values.slowChance());
        addChance(gains, sourceId, "weaken", values.weakenChance());
        if (values.lifeSteal() > EPSILON) {
            gains.add(Component.translatable(DESC + "life_steal", formatPercent(values.lifeSteal())));
        }
        if (values.killRefill() > EPSILON) {
            gains.add(Component.translatable(DESC + "kill_refill", formatPercent(values.killRefill())));
        }
        if (values.ammoSave() > EPSILON) {
            gains.add(Component.translatable(localized(DESC, sourceId, "ammo_save"), formatPercent(values.ammoSave())));
        }
        int pierce = (int) Math.round(values.pierce());
        if (pierce > 0) {
            gains.add(Component.translatable(localized(DESC, sourceId, "pierce"), pierce));
        }
        int ricochet = (int) Math.round(values.ricochet());
        if (ricochet > 0) {
            gains.add(Component.translatable(localized(DESC, sourceId, "ricochet"), ricochet));
        }
        if (!isNeutral(values.seeking(), 0.0D)) {
            (values.seeking() > 0.0D ? gains : costs).add(Component.translatable(localized(DESC, sourceId, "seeking"),
                    formatSigned(values.seeking())));
        }
        if (!isNeutral(values.blowback(), 0.0D)) {
            (values.blowback() < 0.0D ? gains : costs).add(Component.translatable(localized(DESC, sourceId, "blowback"),
                    formatSigned(values.blowback())));
        }
        int spellLevel = (int) Math.round(values.spellLevel());
        if (spellLevel > 0) {
            gains.add(Component.translatable(DESC + "spell_level", spellLevel));
        }
        if (values.doubleCast() > EPSILON) {
            gains.add(Component.translatable(DESC + "double_cast", formatPercent(values.doubleCast())));
        }
        return new Lines(gains, costs);
    }

    /**
     * 拆成「名字 + 数值」一项一行，给卡片和合计效果的表格排法用（名字靠左、数值右对齐成一列）。
     *
     * <p>纯倍率项直接是「伤害 / +25%」；合成句拆成几项短名字，比如暴击拆成「暴击率 / 35%」「暴击伤害 / ×2」，
     * 斩杀只留「斩杀伤害 / +40%」，血线这类机制参数放进 {@link Entry#full()} 的整句里，悬停时再看。
     * 项的顺序和 {@link #describe} 一致。
     *
     * @param values   特化数值
     * @param sourceId 来源 ID
     * @return 得失两组
     */
    public static Entries entries(SpecializationValues values, String sourceId) {
        List<Entry> gains = new ArrayList<>();
        List<Entry> costs = new ArrayList<>();
        for (String key : MULTIPLIERS) {
            double value = values.value(key);
            if (isNeutral(value, 1.0D)) {
                continue;
            }
            boolean better = LOWER_IS_BETTER.contains(key) ? value < 1.0D : value > 1.0D;
            (better ? gains : costs).add(new Entry(I18n.get(localized(STAT, sourceId, key)),
                    formatMultiplier(value), null));
        }
        if (values.critChance() > EPSILON && values.critDamage() > 1.0D + EPSILON) {
            Component full = Component.translatable(localized(DESC, sourceId, "crit"),
                    formatPercent(values.critChance()), "×" + formatNumber(values.critDamage()));
            gains.add(new Entry(shortName(sourceId, "crit_chance"), formatPercent(values.critChance()), full));
            gains.add(new Entry(shortName(sourceId, "crit_damage"), "×" + formatNumber(values.critDamage()), full));
        }
        addConditionalEntry(gains, costs, sourceId, "near", values.nearDamage());
        addConditionalEntry(gains, costs, sourceId, "far", values.farDamage());
        addConditionalEntry(gains, costs, sourceId, "last_round", values.lastRoundDamage());
        if (!isNeutral(values.executeDamage(), 1.0D)) {
            (values.executeDamage() > 1.0D ? gains : costs).add(new Entry(shortName(sourceId, "execute"),
                    formatMultiplier(values.executeDamage()), Component.translatable(DESC + "execute",
                    formatPercent(values.executeThreshold()), formatMultiplier(values.executeDamage()))));
        }
        if (!isNeutral(values.backstabDamage(), 1.0D)) {
            (values.backstabDamage() > 1.0D ? gains : costs).add(new Entry(shortName(sourceId, "backstab"),
                    formatMultiplier(values.backstabDamage()), Component.translatable(DESC + "backstab",
                    formatMultiplier(values.backstabDamage()))));
        }
        if (!isNeutral(values.freshDamage(), 1.0D)) {
            (values.freshDamage() > 1.0D ? gains : costs).add(new Entry(shortName(sourceId, "fresh"),
                    formatMultiplier(values.freshDamage()), Component.translatable(DESC + "fresh",
                    formatMultiplier(values.freshDamage()))));
        }
        if (!isNeutral(values.desperationDamage(), 1.0D)) {
            double floor = Math.min(values.desperationFloor(), values.desperationThreshold());
            (values.desperationDamage() > 1.0D ? gains : costs).add(new Entry(shortName(sourceId, "desperation"),
                    formatMultiplier(values.desperationDamage()), Component.translatable(DESC + "desperation",
                    formatPercent(values.desperationThreshold()), formatPercent(floor),
                    formatMultiplier(values.desperationDamage()))));
        }
        if (values.hasCombo()) {
            Component full = Component.translatable(DESC + "combo",
                    formatPercent(values.comboStep()), (int) Math.round(values.comboMax()));
            gains.add(new Entry(shortName(sourceId, "combo_step"), "+" + formatPercent(values.comboStep()), full));
            gains.add(new Entry(shortName(sourceId, "combo_max"), stacks(values.comboMax()), full));
        }
        if (values.hasFrenzy()) {
            Component full = Component.translatable(DESC + "frenzy",
                    formatPercent(values.frenzyStep()), (int) Math.round(values.frenzyMax()));
            gains.add(new Entry(shortName(sourceId, "frenzy_step"), "+" + formatPercent(values.frenzyStep()), full));
            gains.add(new Entry(shortName(sourceId, "frenzy_max"), stacks(values.frenzyMax()), full));
        }
        addRatioEntry(gains, sourceId, "kill_heal", values.killHeal());
        addRatioEntry(gains, sourceId, "ignite", values.igniteChance());
        addRatioEntry(gains, sourceId, "slow", values.slowChance());
        addRatioEntry(gains, sourceId, "weaken", values.weakenChance());
        addRatioEntry(gains, sourceId, "life_steal", values.lifeSteal());
        addRatioEntry(gains, sourceId, "kill_refill", values.killRefill());
        addRatioEntry(gains, sourceId, "ammo_save", values.ammoSave());
        int pierce = (int) Math.round(values.pierce());
        if (pierce > 0) {
            gains.add(new Entry(shortName(sourceId, "pierce"), "+" + pierce,
                    Component.translatable(localized(DESC, sourceId, "pierce"), pierce)));
        }
        int ricochet = (int) Math.round(values.ricochet());
        if (ricochet > 0) {
            gains.add(new Entry(shortName(sourceId, "ricochet"), "+" + ricochet,
                    Component.translatable(localized(DESC, sourceId, "ricochet"), ricochet)));
        }
        // 追踪力度、人物反冲是加在枪自己的数上的，写成带符号的数而不是百分比；反冲是越小越好
        if (!isNeutral(values.seeking(), 0.0D)) {
            (values.seeking() > 0.0D ? gains : costs).add(new Entry(shortName(sourceId, "seeking"),
                    formatSigned(values.seeking()),
                    Component.translatable(localized(DESC, sourceId, "seeking"), formatSigned(values.seeking()))));
        }
        if (!isNeutral(values.blowback(), 0.0D)) {
            (values.blowback() < 0.0D ? gains : costs).add(new Entry(shortName(sourceId, "blowback"),
                    formatSigned(values.blowback()),
                    Component.translatable(localized(DESC, sourceId, "blowback"), formatSigned(values.blowback()))));
        }
        int spellLevel = (int) Math.round(values.spellLevel());
        if (spellLevel > 0) {
            gains.add(new Entry(shortName(sourceId, "spell_level"), "+" + spellLevel, null));
        }
        addRatioEntry(gains, sourceId, "double_cast", values.doubleCast());
        return new Entries(gains, costs);
    }

    private static void addConditionalEntry(List<Entry> gains, List<Entry> costs, String sourceId, String key,
                                            double multiplier) {
        if (isNeutral(multiplier, 1.0D)) {
            return;
        }
        (multiplier > 1.0D ? gains : costs).add(new Entry(shortName(sourceId, key), formatMultiplier(multiplier),
                Component.translatable(localized(DESC, sourceId, key), formatMultiplier(multiplier))));
    }

    /**
     * 概率 / 比例类：给了才写，整句也放进去（点燃几秒、补弹打不打折这些只在整句里有）。
     */
    private static void addRatioEntry(List<Entry> gains, String sourceId, String key, double ratio) {
        if (ratio > EPSILON) {
            gains.add(new Entry(shortName(sourceId, key), formatPercent(ratio),
                    Component.translatable(localized(DESC, sourceId, key), formatPercent(ratio))));
        }
    }

    private static String shortName(String sourceId, String key) {
        return I18n.get(localized(SHORT, sourceId, key));
    }

    private static String stacks(double count) {
        return I18n.get(SHORT + "stacks", (int) Math.round(count));
    }

    /**
     * 有条件的倍率（近距离、远距离、弹匣最后几发）：变好进「得」，变差进「失」。
     */
    private static void addConditional(List<Component> gains, List<Component> costs, String sourceId,
                                       String key, double multiplier) {
        if (isNeutral(multiplier, 1.0D)) {
            return;
        }
        (multiplier > 1.0D ? gains : costs).add(Component.translatable(localized(DESC, sourceId, key),
                formatMultiplier(multiplier)));
    }

    /**
     * 命中时按概率触发的效果，给了才写。
     */
    private static void addChance(List<Component> gains, String sourceId, String key, double chance) {
        if (chance > EPSILON) {
            gains.add(Component.translatable(localized(DESC, sourceId, key), formatPercent(chance)));
        }
    }

    /**
     * 找一个键的来源专属写法（{@code 前缀.来源.项}），没有就用通用的 {@code 前缀.项}。
     *
     * @param prefix   语言键前缀
     * @param sourceId 来源 ID
     * @param key      项名
     * @return 语言键
     */
    private static String localized(String prefix, String sourceId, String key) {
        String specific = prefix + sourceId + "." + key;
        return I18n.exists(specific) ? specific : prefix + key;
    }

    /**
     * 倍率写成人话：小于 2 倍写百分比变化，2 倍及以上写「×n」。
     *
     * @param multiplier 倍率
     * @return 如「+25%」「−30%」「×2.5」
     */
    public static String formatMultiplier(double multiplier) {
        if (multiplier >= 2.0D - EPSILON) {
            return "×" + formatNumber(multiplier);
        }
        double percent = (multiplier - 1.0D) * 100.0D;
        return (percent >= 0.0D ? "+" : "−") + formatNumber(Math.abs(percent)) + "%";
    }

    /**
     * 加法项（追踪力度、人物反冲）写成带符号的数，保留两位小数：
     * 这两项的量级在 0.1 上下，只留一位的话 Lv1 到 Lv5 全显示成同一个数。
     *
     * @param amount 数
     * @return 如「+0.14」「−0.60」
     */
    public static String formatSigned(double amount) {
        return (amount >= 0.0D ? "+" : "−") + String.format(Locale.ROOT, "%.2f", Math.abs(amount));
    }

    /**
     * 0~1 的比例写成百分数。
     *
     * @param ratio 比例
     * @return 如「35%」
     */
    public static String formatPercent(double ratio) {
        return formatNumber(ratio * 100.0D) + "%";
    }

    /**
     * 最多保留一位小数，整数不带小数点。
     *
     * @param value 数
     * @return 文本
     */
    private static String formatNumber(double value) {
        double rounded = Math.round(value * 10.0D) / 10.0D;
        if (Math.abs(rounded - Math.rint(rounded)) < EPSILON) {
            return Long.toString(Math.round(rounded));
        }
        return String.format(Locale.ROOT, "%.1f", rounded);
    }

    private static boolean isNeutral(double value, double neutral) {
        return Math.abs(value - neutral) <= EPSILON;
    }

    /**
     * 一项效果：名字、数值，合成句拆出来的项另带整句。
     *
     * @param label 名字（已经翻译好）
     * @param value 数值文字，如「+25%」「×2」「5 层」
     * @param full  合成句整句，纯倍率项为 null
     */
    public record Entry(String label, String value, @javax.annotation.Nullable Component full) {
    }

    /**
     * 按项拆好的得失。
     *
     * @param gains 得到的
     * @param costs 付出的
     */
    public record Entries(List<Entry> gains, List<Entry> costs) {

        /** 什么效果都没有。 */
        public static final Entries EMPTY = new Entries(List.of(), List.of());

        public boolean isEmpty() {
            return this.gains.isEmpty() && this.costs.isEmpty();
        }

        public int size() {
            return this.gains.size() + this.costs.size();
        }
    }

    /**
     * 生成结果。
     *
     * @param gains 得到的
     * @param costs 付出的
     */
    public record Lines(List<Component> gains, List<Component> costs) {

        /**
         * @return 什么都不改（数值全是中性值，比如服主把某个特化配成了全 1）
         */
        public boolean isEmpty() {
            return this.gains.isEmpty() && this.costs.isEmpty();
        }

        /**
         * 把「得」连成一句。
         *
         * @return 一句话；没有时返回 null
         */
        public Component gainLine() {
            return join(this.gains);
        }

        /**
         * 把「失」连成一句。
         *
         * @return 一句话；没有时返回 null
         */
        public Component costLine() {
            return join(this.costs);
        }

        private static Component join(List<Component> parts) {
            if (parts.isEmpty()) {
                return null;
            }
            MutableComponent line = Component.empty();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    line.append(Component.translatable(DESC + "separator"));
                }
                line.append(parts.get(i));
            }
            return line;
        }
    }
}
