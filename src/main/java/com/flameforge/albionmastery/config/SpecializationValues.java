package com.flameforge.albionmastery.config;

import net.minecraft.network.FriendlyByteBuf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * 一项专精特化的全部数值。
 *
 * <p>字段分三类。<b>倍率类</b>一律是「乘在原值上」，{@code 1.0} 表示不变；
 * 时间类（换弹、攻击间隔）小于 1 才是变快，这一点容易记反，配置注释里也写了一遍。
 * <b>阈值 / 比例类</b>是 0 到 1 的比例（血量线、暴击率、吸血比例、补弹比例）。
 * <b>层数类</b>（连击上限）是个整数，存成 double 只是为了和别的项走同一套读写。
 *
 * <p>一个特化通常只动其中两三项，其余保持中性值，所以这张表大部分时候是
 * 「一堆 1.0 里夹着几个数」——换来的是加特化不用改网络包结构。
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
 * @param comboMax             连击最多叠几层，0 表示这个特化没有连击
 * @param durability           武器耐久消耗倍率，小于 1 更耐用
 * @param manaCost             法术法力消耗倍率，小于 1 更省
 * @param spellCooldown        法术冷却时间倍率，小于 1 更快
 * @param spellLevel           法术等级加成，整数，0 表示不加
 * @param gunVolume            枪声音量倍率（消音器之类），小于 1 更安静
 * @param bulletSpeed          子弹初速倍率
 * @param recoil               后座倍率，小于 1 更稳
 * @param castTime             法术施法时间（前摇）倍率，小于 1 更快
 * @param drawSpeed            拉弓蓄力速度倍率，大于 1 拉满更快
 * @param doubleCast           法术双重施法概率，0 到 1：施放后自动免费再放一次
 * @param fireRate             枪械射速倍率，大于 1 更快（1.3 = 射速快三成）
 * @param adsTime              枪械开镜耗时倍率，小于 1 更快
 * @param hipSpread            枪械腰射（没完全开镜时）散布倍率，小于 1 更准
 * @param aimSpread            枪械开镜散布倍率，小于 1 更准
 * @param drawTime             枪械掏枪（切枪）耗时倍率，小于 1 更快
 * @param boltTime             枪械拉栓耗时倍率，小于 1 更快
 * @param sprintTime           枪械冲刺后恢复射击的耗时倍率，小于 1 更快
 * @param ammoSave             枪械每发子弹不消耗弹匣的概率，0 到 1
 * @param pierce               子弹 / 箭 / 魔艺投射物额外穿透的目标数，整数
 * @param knockback            击退倍率（近战、拔刀剑、弓箭），大于 1 打得更远
 * @param reach                近战攻击距离倍率（原版剑类）
 * @param backstabDamage       从目标背后打中时的额外倍率
 * @param damageTaken          手持该武器时自己受到的伤害倍率，小于 1 更硬
 * @param saDamage             拔刀剑拔刀技（SA）期间的斩击伤害倍率
 * @param soulGain             拔刀剑耀魂获取倍率
 * @param killHeal             击杀后回复自己最大生命的这个比例，0 到 1
 * @param frenzyStep           嗜战：每次击杀叠一层，每层伤害加成（0.08 = 每层 +8%）
 * @param frenzyMax            嗜战最多叠几层，0 表示没有嗜战
 * @param nearDamage           目标在近距离（6 格内）时的额外倍率
 * @param farDamage            目标在远距离（24 格外）时的额外倍率
 * @param igniteChance         命中时点燃目标的概率，0 到 1
 * @param slowChance           命中时让目标缓慢的概率，0 到 1
 * @param weakenChance         命中时让目标虚弱的概率，0 到 1
 * @param lastRoundDamage      枪械弹匣只剩最后三成时的额外倍率
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
                                   double durability,
                                   double manaCost,
                                   double spellCooldown,
                                   double spellLevel,
                                   double gunVolume,
                                   double bulletSpeed,
                                   double recoil,
                                   double castTime,
                                   double drawSpeed,
                                   double doubleCast,
                                   double fireRate,
                                   double adsTime,
                                   double hipSpread,
                                   double aimSpread,
                                   double drawTime,
                                   double boltTime,
                                   double sprintTime,
                                   double ammoSave,
                                   double pierce,
                                   double knockback,
                                   double reach,
                                   double backstabDamage,
                                   double damageTaken,
                                   double saDamage,
                                   double soulGain,
                                   double killHeal,
                                   double frenzyStep,
                                   double frenzyMax,
                                   double nearDamage,
                                   double farDamage,
                                   double igniteChance,
                                   double slowChance,
                                   double weakenChance,
                                   double lastRoundDamage) {

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
            "killRefill", "comboStep", "comboMax", "durability",
            "manaCost", "spellCooldown", "spellLevel",
            "gunVolume", "bulletSpeed", "recoil", "castTime", "drawSpeed", "doubleCast",
            "fireRate", "adsTime", "hipSpread", "aimSpread", "drawTime", "boltTime", "sprintTime",
            "ammoSave", "pierce",
            "knockback", "reach", "backstabDamage", "damageTaken", "saDamage", "soulGain",
            "killHeal", "frenzyStep", "frenzyMax", "nearDamage", "farDamage",
            "igniteChance", "slowChance", "weakenChance", "lastRoundDamage");

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
            case "critChance", "lifeSteal", "killRefill", "comboStep", "comboMax", "spellLevel", "doubleCast",
                    "ammoSave", "pierce", "killHeal", "frenzyStep", "frenzyMax",
                    "igniteChance", "slowChance", "weakenChance" -> 0.0D;
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
            case "manaCost" -> this.manaCost;
            case "spellCooldown" -> this.spellCooldown;
            case "spellLevel" -> this.spellLevel;
            case "gunVolume" -> this.gunVolume;
            case "bulletSpeed" -> this.bulletSpeed;
            case "recoil" -> this.recoil;
            case "castTime" -> this.castTime;
            case "drawSpeed" -> this.drawSpeed;
            case "doubleCast" -> this.doubleCast;
            case "fireRate" -> this.fireRate;
            case "adsTime" -> this.adsTime;
            case "hipSpread" -> this.hipSpread;
            case "aimSpread" -> this.aimSpread;
            case "drawTime" -> this.drawTime;
            case "boltTime" -> this.boltTime;
            case "sprintTime" -> this.sprintTime;
            case "ammoSave" -> this.ammoSave;
            case "pierce" -> this.pierce;
            case "knockback" -> this.knockback;
            case "reach" -> this.reach;
            case "backstabDamage" -> this.backstabDamage;
            case "damageTaken" -> this.damageTaken;
            case "saDamage" -> this.saDamage;
            case "soulGain" -> this.soulGain;
            case "killHeal" -> this.killHeal;
            case "frenzyStep" -> this.frenzyStep;
            case "frenzyMax" -> this.frenzyMax;
            case "nearDamage" -> this.nearDamage;
            case "farDamage" -> this.farDamage;
            case "igniteChance" -> this.igniteChance;
            case "slowChance" -> this.slowChance;
            case "weakenChance" -> this.weakenChance;
            case "lastRoundDamage" -> this.lastRoundDamage;
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
                lookup.applyAsDouble("durability"),
                lookup.applyAsDouble("manaCost"),
                lookup.applyAsDouble("spellCooldown"),
                lookup.applyAsDouble("spellLevel"),
                lookup.applyAsDouble("gunVolume"),
                lookup.applyAsDouble("bulletSpeed"),
                lookup.applyAsDouble("recoil"),
                lookup.applyAsDouble("castTime"),
                lookup.applyAsDouble("drawSpeed"),
                lookup.applyAsDouble("doubleCast"),
                lookup.applyAsDouble("fireRate"),
                lookup.applyAsDouble("adsTime"),
                lookup.applyAsDouble("hipSpread"),
                lookup.applyAsDouble("aimSpread"),
                lookup.applyAsDouble("drawTime"),
                lookup.applyAsDouble("boltTime"),
                lookup.applyAsDouble("sprintTime"),
                lookup.applyAsDouble("ammoSave"),
                lookup.applyAsDouble("pierce"),
                lookup.applyAsDouble("knockback"),
                lookup.applyAsDouble("reach"),
                lookup.applyAsDouble("backstabDamage"),
                lookup.applyAsDouble("damageTaken"),
                lookup.applyAsDouble("saDamage"),
                lookup.applyAsDouble("soulGain"),
                lookup.applyAsDouble("killHeal"),
                lookup.applyAsDouble("frenzyStep"),
                lookup.applyAsDouble("frenzyMax"),
                lookup.applyAsDouble("nearDamage"),
                lookup.applyAsDouble("farDamage"),
                lookup.applyAsDouble("igniteChance"),
                lookup.applyAsDouble("slowChance"),
                lookup.applyAsDouble("weakenChance"),
                lookup.applyAsDouble("lastRoundDamage"));
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
            case "reloadTime", "attackInterval", "magazine", "manaCost", "spellCooldown", "castTime",
                    "adsTime", "drawTime", "boltTime" -> 0.05D;
            // 射速再低也得能开枪；0.2 = 原射速的五分之一
            case "fireRate" -> 0.2D;
            // 弹速夹得紧：太快子弹一 tick 飞穿好几个区块，碰撞会漏
            case "bulletSpeed" -> 0.2D;
            // 移速倍率再低也得能走，否则玩家被钉在原地
            case "moveSpeed" -> 0.2D;
            // 暴击倍率低于 1 就不叫暴击了
            case "critDamage" -> 1.0D;
            // 攻击距离砍到一半已经很难用了，再低就摸不到人
            case "reach" -> 0.5D;
            // 受到伤害至少一成：不能做成无敌
            case "damageTaken" -> 0.1D;
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
                    "critChance", "lifeSteal", "killRefill", "doubleCast",
                    "killHeal", "frenzyStep", "igniteChance", "slowChance", "weakenChance" -> 1.0D;
            case "frenzyMax" -> 20.0D;
            case "reloadTime", "attackInterval", "magazine", "durability", "critDamage",
                    "manaCost", "spellCooldown" -> 20.0D;
            case "spellLevel" -> 10.0D;
            case "moveSpeed", "gunVolume", "recoil", "castTime",
                    "adsTime", "hipSpread", "aimSpread", "drawTime", "boltTime", "sprintTime" -> 5.0D;
            case "bulletSpeed" -> 2.5D;
            // 射速上限 3 倍：TACZ 客户端按 tick 开火，一秒最多 20 发，再高也打不出来
            case "fireRate" -> 3.0D;
            // 免耗弹封顶 90%：给到 100% 就是无限子弹，弹匣和换弹整套玩法都没了
            case "ammoSave" -> 0.9D;
            case "pierce" -> 10.0D;
            case "knockback", "damageTaken" -> 5.0D;
            // 攻击距离最多翻倍：再远就成了隔空砍人，服务端的距离校验也会拦
            case "reach" -> 2.0D;
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
     * 两个槽位的特化叠在一起。
     *
     * <p>规则按项的性质定：倍率类相乘（伤害 1.4 × 1.1 = 1.54）；概率、比例、每层加成和法术等级加成相加
     * （暴击率 15% + 10% = 25%，两个特化各 +1 级就是 +2 级）；层数上限和暴击倍率取大的，
     * 这两项相加或相乘都没道理（暴击倍率相乘会爆炸）；斩杀、背水的阈值只认真带这个机制的那一侧，
     * 见 {@link #mechanismThreshold}。
     *
     * @param first  第一槽
     * @param second 第二槽
     * @return 叠加后的数值
     */
    public static SpecializationValues combine(SpecializationValues first, SpecializationValues second) {
        return of(key -> {
            double a = first.value(key);
            double b = second.value(key);
            return switch (key) {
                // 概率、比例、每层加成：相加
                case "critChance", "lifeSteal", "killRefill", "comboStep", "spellLevel", "doubleCast",
                        "ammoSave", "pierce", "killHeal", "frenzyStep",
                        "igniteChance", "slowChance", "weakenChance" ->
                        clampOf(key, a + b);
                // 上限、暴击倍率：取大
                case "comboMax", "frenzyMax", "critDamage" -> Math.max(a, b);
                // 阈值：这几项的默认值不是中性的（背水下限默认 0.3、斩杀阈值默认 0.3），
                // 无脑取大的话，另一个槽随便装个和背水 / 斩杀毫无关系的特化，
                // 也会拿默认值去和真正带机制那一侧比大小，把人家的过渡区间改掉。
                // 只有真的带这个机制的那一侧说了算
                case "executeThreshold" ->
                        mechanismThreshold(first.executeDamage(), second.executeDamage(), a, b);
                case "desperationThreshold", "desperationFloor" ->
                        mechanismThreshold(first.desperationDamage(), second.desperationDamage(), a, b);
                // 其余全是倍率：相乘
                default -> clampOf(key, a * b);
            };
        });
    }

    /**
     * 合并一个「只有带对应机制才有意义」的阈值。
     *
     * <p>两边都带就取大（两个斩杀特化叠起来，按高的那条线走），只有一边带就听那一边的，
     * 谁都没带时取大——反正没有机制会读它。
     *
     * @param firstDamage  第一槽这个机制的伤害倍率，1.0 表示没有这个机制
     * @param secondDamage 第二槽同上
     * @param a            第一槽的阈值
     * @param b            第二槽的阈值
     * @return 合并后的阈值
     */
    private static double mechanismThreshold(double firstDamage, double secondDamage, double a, double b) {
        boolean firstHas = firstDamage != 1.0D;
        boolean secondHas = secondDamage != 1.0D;
        if (firstHas == secondHas) {
            return Math.max(a, b);
        }
        return firstHas ? a : b;
    }

    /**
     * @return 这个特化有没有嗜战（层数上限和每层加成都大于 0 才算）
     */
    public boolean hasFrenzy() {
        return this.frenzyMax >= 1.0D && this.frenzyStep > 0.0D;
    }

    /**
     * @return 这个特化有没有连击（层数上限和每层加成都大于 0 才算）
     */
    public boolean hasCombo() {
        return this.comboMax >= 1.0D && this.comboStep > 0.0D;
    }
}
