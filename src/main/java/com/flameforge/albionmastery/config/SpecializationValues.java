package com.flameforge.albionmastery.config;

import net.minecraft.network.FriendlyByteBuf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * 一个专精特化（流派）的全部数值。
 *
 * <p>字段分三类。<b>倍率类</b>一律是「乘在原值上」，{@code 1.0} 表示不变；
 * 时间类（换弹、攻击间隔）小于 1 才是变快，这一点容易记反，配置注释里也写了一遍。
 * <b>阈值 / 比例类</b>是 0 到 1 的比例（血量线、暴击率、吸血比例、补弹比例）。
 * <b>层数类</b>（连击上限）是个整数，存成 double 只是为了和别的项走同一套读写。
 *
 * <p>一个流派通常只动其中两三项，其余保持中性值，所以这张表大部分时候是
 * 「一堆 1.0 里夹着几个数」——换来的是加流派不用改网络包结构。
 *
 * <p>字段多了之后，读写和取值都走 {@link #KEYS} 这张顺序表，不再一个个手写：
 * 加一个维度只需要动 {@code KEYS}、{@link #value}、{@link #of}、{@link #defaultOf} 和
 * {@link #minOf} / {@link #maxOf} 几处，漏改任何一处编译期就会报出来，
 * 不会出现「网络包写了六个读了五个」这种错位。
 * 新项<b>只能追加在 {@code KEYS} 末尾</b>：中间插会让老客户端把后面所有项错位读掉。
 *
 * @param magazine             弹匣容量倍率
 * @param reloadTime           换弹耗时倍率，小于 1 更快
 * @param damage               通用伤害倍率
 * @param attackInterval       近战攻击间隔倍率，小于 1 更快
 * @param summonedDamage       拔刀剑幻影剑（召唤剑）伤害倍率
 * @param directDamage         拔刀剑刀身直接攻击伤害倍率
 * @param vsPlayerDamage       目标是玩家时的额外倍率
 * @param vsMobDamage          目标不是玩家时的额外倍率
 * @param armoredDamage        目标有护甲时的额外倍率
 * @param unarmoredDamage      目标没有护甲时的额外倍率
 * @param headShotDamage       爆头时的额外倍率（枪械）
 * @param bodyShotDamage       非爆头时的额外倍率（枪械）
 * @param executeDamage        目标血量低于阈值时的额外倍率
 * @param executeThreshold     上面那个阈值，目标当前血量占最大血量的比例
 * @param freshDamage          目标接近满血时的额外倍率
 * @param desperationDamage    自己血量见底时能吃到的最高额外倍率，按血量线性过渡
 * @param desperationThreshold 自己血量低于这个比例才开始吃 desperation
 * @param desperationFloor     自己血量低到这个比例就吃满，再低也不会更高
 * @param critChance           暴击概率，0 到 1
 * @param critDamage           暴击时的额外倍率
 * @param lifeSteal            按最终造成伤害的这个比例回血，0 到 1
 * @param moveSpeed            手持该类武器时的移动速度倍率
 * @param xpGain               用该类武器获得专精经验的倍率
 * @param killRefill           枪械击杀后立刻补回弹匣容量的这个比例，0 到 1
 * @param comboStep            连续命中同一目标时每层增加的伤害比例（0.05 = 每层 +5%）
 * @param comboMax             连击最多叠几层，0 表示这个流派没有连击
 * @param durability           武器耐久消耗倍率，小于 1 更耐用
 */
public record SpecializationValues(double magazine,
                                   double reloadTime,
                                   double damage,
                                   double attackInterval,
                                   double summonedDamage,
                                   double directDamage,
                                   double vsPlayerDamage,
                                   double vsMobDamage,
                                   double armoredDamage,
                                   double unarmoredDamage,
                                   double headShotDamage,
                                   double bodyShotDamage,
                                   double executeDamage,
                                   double executeThreshold,
                                   double freshDamage,
                                   double desperationDamage,
                                   double desperationThreshold,
                                   double desperationFloor,
                                   double critChance,
                                   double critDamage,
                                   double lifeSteal,
                                   double moveSpeed,
                                   double xpGain,
                                   double killRefill,
                                   double comboStep,
                                   double comboMax,
                                   double durability) {

    /** 全部维度的项名，同时也是网络传输的顺序。改动顺序会破坏兼容性，新项只能追加在末尾。 */
    public static final List<String> KEYS = List.of(
            "magazine", "reloadTime", "damage", "attackInterval",
            "summonedDamage", "directDamage",
            "vsPlayerDamage", "vsMobDamage",
            "armoredDamage", "unarmoredDamage",
            "headShotDamage", "bodyShotDamage",
            "executeDamage", "executeThreshold",
            "freshDamage", "desperationDamage", "desperationThreshold", "desperationFloor",
            "critChance", "critDamage", "lifeSteal", "moveSpeed", "xpGain",
            "killRefill", "comboStep", "comboMax", "durability");

    /** 什么都不改的中性值，用于「制式」以及查不到数值时的兜底。 */
    public static final SpecializationValues NEUTRAL = of(SpecializationValues::defaultOf);

    /**
     * 取某个维度的默认值。
     *
     * <p>倍率类默认 1（不变），概率 / 比例 / 层数类默认 0（不触发），阈值类各有各的默认，
     * 不能一律给 1——阈值给 1 等于「任何血量都算低血」，那条件就永远成立了。
     *
     * @param key 项名
     * @return 默认值
     */
    public static double defaultOf(String key) {
        return switch (key) {
            case "executeThreshold" -> 0.3D;
            case "desperationThreshold" -> 0.5D;
            case "desperationFloor" -> 0.3D;
            case "critChance", "lifeSteal", "killRefill", "comboStep", "comboMax" -> 0.0D;
            case "critDamage" -> 1.5D;
            default -> 1.0D;
        };
    }

    /**
     * 按项名取值。
     *
     * @param key 项名
     * @return 数值；项名不认识时返回它的默认值
     */
    public double value(String key) {
        return switch (key) {
            case "magazine" -> this.magazine;
            case "reloadTime" -> this.reloadTime;
            case "damage" -> this.damage;
            case "attackInterval" -> this.attackInterval;
            case "summonedDamage" -> this.summonedDamage;
            case "directDamage" -> this.directDamage;
            case "vsPlayerDamage" -> this.vsPlayerDamage;
            case "vsMobDamage" -> this.vsMobDamage;
            case "armoredDamage" -> this.armoredDamage;
            case "unarmoredDamage" -> this.unarmoredDamage;
            case "headShotDamage" -> this.headShotDamage;
            case "bodyShotDamage" -> this.bodyShotDamage;
            case "executeDamage" -> this.executeDamage;
            case "executeThreshold" -> this.executeThreshold;
            case "freshDamage" -> this.freshDamage;
            case "desperationDamage" -> this.desperationDamage;
            case "desperationThreshold" -> this.desperationThreshold;
            case "desperationFloor" -> this.desperationFloor;
            case "critChance" -> this.critChance;
            case "critDamage" -> this.critDamage;
            case "lifeSteal" -> this.lifeSteal;
            case "moveSpeed" -> this.moveSpeed;
            case "xpGain" -> this.xpGain;
            case "killRefill" -> this.killRefill;
            case "comboStep" -> this.comboStep;
            case "comboMax" -> this.comboMax;
            case "durability" -> this.durability;
            default -> defaultOf(key);
        };
    }

    /**
     * 按项名逐个取值来构造。
     *
     * @param lookup 项名 -&gt; 数值
     * @return 数值
     */
    public static SpecializationValues of(ToDoubleFunction<String> lookup) {
        return new SpecializationValues(
                lookup.applyAsDouble("magazine"),
                lookup.applyAsDouble("reloadTime"),
                lookup.applyAsDouble("damage"),
                lookup.applyAsDouble("attackInterval"),
                lookup.applyAsDouble("summonedDamage"),
                lookup.applyAsDouble("directDamage"),
                lookup.applyAsDouble("vsPlayerDamage"),
                lookup.applyAsDouble("vsMobDamage"),
                lookup.applyAsDouble("armoredDamage"),
                lookup.applyAsDouble("unarmoredDamage"),
                lookup.applyAsDouble("headShotDamage"),
                lookup.applyAsDouble("bodyShotDamage"),
                lookup.applyAsDouble("executeDamage"),
                lookup.applyAsDouble("executeThreshold"),
                lookup.applyAsDouble("freshDamage"),
                lookup.applyAsDouble("desperationDamage"),
                lookup.applyAsDouble("desperationThreshold"),
                lookup.applyAsDouble("desperationFloor"),
                lookup.applyAsDouble("critChance"),
                lookup.applyAsDouble("critDamage"),
                lookup.applyAsDouble("lifeSteal"),
                lookup.applyAsDouble("moveSpeed"),
                lookup.applyAsDouble("xpGain"),
                lookup.applyAsDouble("killRefill"),
                lookup.applyAsDouble("comboStep"),
                lookup.applyAsDouble("comboMax"),
                lookup.applyAsDouble("durability"));
    }

    /**
     * 写入网络缓冲区。
     *
     * @param buf 目标缓冲区
     */
    public void write(FriendlyByteBuf buf) {
        for (String key : KEYS) {
            buf.writeDouble(value(key));
        }
    }

    /**
     * 从网络缓冲区读取，所有数值都会被夹到合法区间。
     *
     * <p>夹取是必须的：这份数据虽然来自服务端，但缓冲区里出现异常值（比如换弹倍率 0）
     * 会让后面的除法或循环出问题，宁可在入口处挡掉。
     *
     * @param buf 源缓冲区
     * @return 数值
     */
    public static SpecializationValues read(FriendlyByteBuf buf) {
        Map<String, Double> values = new HashMap<>();
        for (String key : KEYS) {
            values.put(key, clampOf(key, buf.readDouble()));
        }
        return of(key -> values.getOrDefault(key, defaultOf(key)));
    }

    /**
     * 某个项允许的最小值，配置文件的取值区间和网络包的夹取都用它，两边不会各写一套。
     *
     * @param key 项名
     * @return 最小值
     */
    public static double minOf(String key) {
        return switch (key) {
            // 时间类和容量不能是 0，否则「间隔为零」会变成无限连击、容量 0 会永远装不满
            case "reloadTime", "attackInterval", "magazine" -> 0.05D;
            // 移速倍率再低也得能走，否则玩家被钉在原地
            case "moveSpeed" -> 0.2D;
            // 暴击倍率低于 1 就不叫暴击了
            case "critDamage" -> 1.0D;
            default -> 0.0D;
        };
    }

    /**
     * 某个项允许的最大值。
     *
     * @param key 项名
     * @return 最大值
     */
    public static double maxOf(String key) {
        return switch (key) {
            // 血量比例、概率、回血比例、补弹比例都是 0~1
            case "executeThreshold", "desperationThreshold", "desperationFloor",
                    "critChance", "lifeSteal", "killRefill" -> 1.0D;
            case "reloadTime", "attackInterval", "magazine", "durability", "critDamage" -> 20.0D;
            case "moveSpeed" -> 5.0D;
            case "comboStep" -> 10.0D;
            default -> 100.0D;
        };
    }

    /**
     * 按项名把数值夹到合法区间。
     *
     * @param key   项名
     * @param value 原始值
     * @return 夹取后的值
     */
    public static double clampOf(String key, double value) {
        if (Double.isNaN(value)) {
            return defaultOf(key);
        }
        return Math.max(minOf(key), Math.min(maxOf(key), value));
    }

    /**
     * @return 这个流派有没有连击（层数上限和每层加成都大于 0 才算）
     */
    public boolean hasCombo() {
        return this.comboMax >= 1.0D && this.comboStep > 0.0D;
    }
}
