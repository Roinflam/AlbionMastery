package com.flameforge.albionmastery.config;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.leaderboard.LeaderboardSort;
import com.flameforge.albionmastery.mastery.LevelCurve;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.flameforge.albionmastery.weapon.compat.WeaponOverrides;
import java.util.ArrayList;

/**
 * 武器专精系统的配置。
 *
 * <p>分为两部分：
 * <ul>
 *     <li>COMMON：影响玩法的数值（等级上限、加成系数、经验曲线），以服务端为准</li>
 *     <li>CLIENT：纯客户端表现（是否渲染物品图标等）</li>
 * </ul>
 *
 * <p>由于 COMMON 配置在专用服务端与客户端上是两份独立文件，为了保证界面显示的加成
 * 与服务端实际结算的加成一致，登录时会由服务端把 {@link Snapshot} 下发给客户端，
 * 客户端界面只使用快照里的数值，不读自己的配置文件。
 */
public final class MasteryConfig {

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    /**
     * 每个数值项在配置文件里的说明。
     *
     * <p>放一张表而不是在每个特化里各写一遍：同一个项名在不同特化下含义完全一样，
     * 分散写迟早写歪，改一处忘一处。
     *
     * <p>和 {@link #SPEC_ENTRIES} 一样，<b>必须声明在下面那个静态初始化块之前</b>。
     * Java 按书写顺序初始化静态成员，写在后面的话，静态块里构造 {@code Common} 时
     * 这张表还是 null，游戏在模组构造阶段就直接崩，报的还是「表是 null」这种看不出因果的错。
     */
    private static final Map<String, String> SPEC_VALUE_COMMENTS = Map.ofEntries(
            Map.entry("magazine", "弹匣容量倍率（2.0 = 翻倍）"),
            Map.entry("reloadTime", "换弹耗时倍率（2.0 = 耗时翻倍，0.5 = 耗时减半）"),
            Map.entry("damage", "通用伤害倍率"),
            Map.entry("attackInterval", "近战攻击间隔倍率（0.6 = 出手快四成，1.6 = 慢六成）"),
            Map.entry("summonedDamage", "拔刀剑幻影剑（召唤剑）伤害倍率"),
            Map.entry("directDamage", "拔刀剑刀身直接攻击伤害倍率"),
            Map.entry("vsPlayerDamage", "目标是玩家时的额外倍率"),
            Map.entry("vsMobDamage", "目标不是玩家时的额外倍率"),
            Map.entry("armoredDamage", "目标有护甲时的额外倍率"),
            Map.entry("unarmoredDamage", "目标无护甲时的额外倍率"),
            Map.entry("headShotDamage", "爆头时的额外倍率"),
            Map.entry("bodyShotDamage", "未爆头时的额外倍率"),
            Map.entry("executeDamage", "目标残血时的额外倍率"),
            Map.entry("executeThreshold", "残血判定线，以目标最大生命值的比例计"),
            Map.entry("freshDamage", "目标接近满血时的额外倍率"),
            Map.entry("desperationDamage", "自身生命值极低时的最高额外倍率，按生命值线性过渡"),
            Map.entry("desperationThreshold", "自身生命值低于此比例时开始获得背水加成"),
            Map.entry("desperationFloor", "自身生命值降到此比例时背水加成达到最高"),
            Map.entry("critChance", "暴击概率（0.15 = 15%）"),
            Map.entry("critDamage", "暴击时的额外倍率（1.5 = 伤害为 1.5 倍）"),
            Map.entry("lifeSteal", "吸血比例：按最终伤害的此比例回复生命（0.1 = 10%）"),
            Map.entry("moveSpeed", "手持该类武器时的移动速度倍率（1.1 = 快一成，0.9 = 慢一成）"),
            Map.entry("xpGain", "用该类武器获得专精经验的倍率"),
            Map.entry("killRefill", "枪械击杀后立即补回弹匣容量的比例（0.5 = 补半个弹匣）"),
            Map.entry("comboStep", "连击每层增加的伤害比例（0.05 = 每层 +5%），首击不计"),
            Map.entry("comboMax", "连击最多叠几层"),
            Map.entry("durability", "武器耐久消耗倍率（0.5 = 只扣一半，1.5 = 多扣五成）"),
            Map.entry("manaCost", "法术法力消耗倍率（0.7 = 省三成）"),
            Map.entry("spellCooldown", "法术冷却时间倍率（0.7 = 快三成）"),
            Map.entry("spellLevel", "法术等级加成。已停用，此项不生效"),
            Map.entry("gunVolume", "枪声音量倍率（0.3 = 只剩三成响，传播距离也跟着缩）"),
            Map.entry("bulletSpeed", "子弹初速倍率（1.2 = 快两成）。上限 2.5，过快易穿墙漏判"),
            Map.entry("recoil", "后坐倍率（0.7 = 后坐降为七成）"),
            Map.entry("castTime", "法术施法时间倍率（0.8 = 前摇快两成）"),
            Map.entry("drawSpeed", "拉弓蓄力速度倍率（1.3 = 拉满快三成），只对弓有效"),
            Map.entry("doubleCast", "法术双重施法概率（1.0 = 每次施放后必定再施放一次，不消耗法力，不计冷却）"),
            Map.entry("fireRate", "枪械射速倍率（1.3 = 射速快三成）。上限 3，最快 1200 发/分，TACZ 按 tick 开火，无法更快。点射的连发间隔随之变化"),
            Map.entry("adsTime", "枪械开镜耗时倍率（0.7 = 开镜快三成，1.3 = 慢三成）"),
            Map.entry("hipSpread", "枪械腰射散布倍率（0.5 = 散布减半）。未完全开镜时的射击均算腰射"),
            Map.entry("aimSpread", "枪械开镜散布倍率（0.5 = 散布减半）。瞄准进度满了才算开镜"),
            Map.entry("drawTime", "枪械拔枪耗时倍率（0.5 = 切枪后可开火的时间减半）"),
            Map.entry("boltTime", "枪械拉栓耗时倍率（0.5 = 拉栓快一倍），只对栓动 / 泵动枪有效"),
            Map.entry("sprintTime", "枪械冲刺后恢复射击的耗时倍率（0.5 = 停止冲刺后等待时间减半）"),
            Map.entry("ammoSave", "省弹概率（0.3 = 30%），上限 0.9。枪械：这一发不扣弹匣，背包直读和栓动枪不生效。"
                    + "弓弩：只有这一箭确实从背包里扣掉了才会按概率退还，无限附魔、创造模式、模组的无限弓和箭袋饰品都不退"),
            Map.entry("pierce", "子弹 / 箭 / 魔艺投射物额外穿透的目标数，整数（1 = 多穿透一个目标）。对爆炸弹无效"),
            Map.entry("knockback", "击退倍率（1.5 = 击退远一半，0.5 = 只剩一半），对近战、拔刀剑、弓箭有效"),
            Map.entry("reach", "攻击距离倍率（1.2 = 远两成），上限 2。只对吃原版交互距离属性的近战有效（当前是 "
                    + WeaponKeys.vanillaAttributeProviderList() + "）"),
            Map.entry("backstabDamage", "从目标背后命中时的额外倍率（目标身后约 120° 的扇形）"),
            Map.entry("damageTaken", "手持该武器时自身受到的伤害倍率（0.8 = 减伤两成），最低 0.1"),
            Map.entry("saDamage", "拔刀剑 SA 伤害倍率：SA 发动后 1.5 秒内的斩击都算"),
            Map.entry("soulGain", "拔刀剑耀魂获取倍率（1.5 = 多获得一半）"),
            Map.entry("killHeal", "击杀后回复自身最大生命值的此比例（0.1 = 回复一成）"),
            Map.entry("frenzyStep", "嗜战：每次击杀叠一层，每层伤害加成（0.08 = 每层 +8%），8 秒内未击杀则清零"),
            Map.entry("frenzyMax", "嗜战最多叠几层"),
            Map.entry("nearDamage", "目标在 6 格内时的伤害倍率"),
            Map.entry("farDamage", "目标在 24 格外时的伤害倍率"),
            Map.entry("igniteChance", "命中时点燃目标 4 秒的概率。同一 tick 对同一目标只判定一次，霰弹并非必定点燃"),
            Map.entry("slowChance", "命中时使目标缓慢 II 3 秒的概率"),
            Map.entry("weakenChance", "命中时使目标虚弱 4 秒的概率"),
            Map.entry("lastRoundDamage", "枪械弹匣只剩最后三成子弹时的伤害倍率"),
            Map.entry("ricochet", "铁枪子弹在方块上额外弹射的次数，整数（2 = 多弹两次），与弹射子弹改装件叠加"),
            Map.entry("seeking", "铁枪子弹的追踪力度，加在枪本身的追踪上（一个追踪火药改装件为 0.1），上限 1"),
            Map.entry("blowback", "铁枪开枪时人物后退的力度，叠加在枪本身的人物反冲上（燧发枪自带 0.5）。负数为减少，合计最低为 0"),
            Map.entry("firstShotDamage", "铁枪满弹匣时第一发的伤害倍率，单发枪每发均算首发，只作用于子弹直接命中"),
            Map.entry("ricochetDamage", "铁枪子弹在方块上弹射过之后再命中的伤害倍率"),
            Map.entry("effectDamage", "铁枪改装件附带伤害的倍率：黑火药装药的爆炸、电击弹芯的闪电、魂火硬币的灵魂伤害"));

    /**
     * 每个特化的数值项，由 {@link #defineSpec} 在构建配置时填入。
     *
     * <p>必须声明在下面那个静态初始化块<b>之前</b>：Java 按书写顺序初始化静态成员，
     * 声明写在后面的话，构造 {@code Common} 时这张表还是 null，直接 NPE。
     */
    private static final Map<Specialization, SpecEntry> SPEC_ENTRIES =
            new EnumMap<>(Specialization.class);

    static {
        Pair<Common, ForgeConfigSpec> commonPair = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = commonPair.getLeft();
        COMMON_SPEC = commonPair.getRight();

        Pair<Client, ForgeConfigSpec> clientPair = new ForgeConfigSpec.Builder().configure(Client::new);
        CLIENT = clientPair.getLeft();
        CLIENT_SPEC = clientPair.getRight();
    }

    /**
     * 特化数值的版本号。特化默认值重新平衡时加一：老配置里记的版本比它小，
     * 启动时就会把全部特化数值重置成新默认值（服主可以用 autoUpdateSpecBalance 关掉）。
     *
     * <p>1：初版；2：枪械手感维度 + 全来源特化重做（系别、背刺、击退、攻击距离、拔刀技、刀魂、弓箭穿透省箭爆头）。
     */
    public static final int SPEC_BALANCE_VERSION = 2;

    /** 服务端当前生效的配置快照，配置加载 / 重载后刷新。 */
    private static volatile Snapshot serverSnapshot = Snapshot.createDefault();

    private MasteryConfig() {
    }

    /**
     * 从 Forge 配置重新生成服务端快照，在 {@code ModConfigEvent.Loading / Reloading} 时调用。
     */
    public static void refreshServerSnapshot() {
        serverSnapshot = new Snapshot(
                COMMON.maxLevel.get(),
                COMMON.overallDamagePerLevel.get(),
                COMMON.typeDamagePerLevel.get(),
                COMMON.weaponDamagePerLevel.get(),
                COMMON.weaponCrossDamagePerLevel.get(),
                COMMON.maxTotalDamageBonus.get(),
                COMMON.sourceMultiplierValues(),
                COMMON.xpBase.get(),
                COMMON.xpExponent.get(),
                COMMON.typeXpMultiplier.get(),
                COMMON.overallXpMultiplier.get(),
                COMMON.netherXpMultiplier.get(),
                COMMON.endXpMultiplier.get(),
                COMMON.moddedDimensionXpMultiplier.get(),
                COMMON.splitMeleeByPack.get(),
                COMMON.enableSpecialization.get(),
                COMMON.specSwitchCooldownSeconds.get(),
                buildSpecRules(),
                collectSpecValues(),
                true,
                List.copyOf(COMMON.extraWeapons.get()),
                List.copyOf(COMMON.notWeapons.get()),
                COMMON.spellMasteryManaShare.get(),
                COMMON.spellMasteryCooldownShare.get(),
                COMMON.spellMasteryReductionCap.get(),
                COMMON.rankXpPerWeaponLevel.get(),
                parseWeaponMultipliers(COMMON.weaponXpMultipliers.get()),
                parseWeaponMultipliers(COMMON.weaponDamageMultipliers.get())
        );
        // 武器补录表两端各自按同一份配置刷新：服务端读自己的配置，客户端读同步过来的快照
        WeaponOverrides.applyFromConfig(serverSnapshot.extraWeapons, serverSnapshot.notWeapons);
        AlbionMastery.LOGGER.info("Mastery configuration refreshed: independent source multipliers");
    }

    /**
     * 把配置里的特化养成规则读成一份不可变的 {@link SpecRules}。
     *
     * @return 规则
     */
    private static SpecRules buildSpecRules() {
        return new SpecRules(
                COMMON.specSlotCount.get(),
                COMMON.specSlot1WeaponPercent.get(),
                COMMON.specSlot2TypePercent.get(),
                COMMON.specSlot3SourcePercent.get(),
                COMMON.specSlot4TypePercent.get(),
                COMMON.specTypeBypassPercent.get(),
                SpecRules.parseScale(COMMON.specNormalLevelScale.get()),
                SpecRules.parseScale(COMMON.specBreakthroughLevelScale.get()),
                SpecRules.parseCost(COMMON.specNormalLevelCost.get()),
                SpecRules.parseCost(COMMON.specBreakthroughLevelCost.get()),
                COMMON.specModPointsAtMaxSource.get(),
                COMMON.specBreakthroughPointsAtMaxType.get(),
                COMMON.specRankModPointStart.get(),
                COMMON.specRankModPointEvery.get(),
                COMMON.specRankModPointMax.get(),
                COMMON.specMaxedWeaponBreakthroughPoint.get(),
                COMMON.specMaxedWeaponBreakthroughPointMax.get(),
                COMMON.specRankReductionStart.get(),
                COMMON.specRankReductionEvery.get(),
                COMMON.specRankReductionPercentPerStep.get(),
                COMMON.specRankReductionMaxPercent.get(),
                COMMON.specRankReductionMaxedWeaponsInType.get(),
                COMMON.specRespecCooldownSeconds.get());
    }

    /**
     * 单件武器倍率表的一行写得对不对：「武器ID=倍率」，倍率是 0～100 的数。
     *
     * @param value 配置值
     * @return 合法返回 true
     */
    public static boolean validWeaponMultiplier(Object value) {
        return value instanceof String text && parseWeaponMultiplier(text) != null;
    }

    /**
     * 解析一行「武器ID=倍率」。
     *
     * @param line 一行
     * @return 键和倍率；写错时返回 null
     */
    @javax.annotation.Nullable
    private static Map.Entry<String, Double> parseWeaponMultiplier(String line) {
        int eq = line.lastIndexOf('=');
        if (eq <= 0 || eq >= line.length() - 1) {
            return null;
        }
        String key = line.substring(0, eq).trim();
        if (key.isEmpty() || key.indexOf(':') < 0 || key.length() > 256) {
            return null;
        }
        try {
            double multiplier = Double.parseDouble(line.substring(eq + 1).trim());
            if (Double.isNaN(multiplier) || multiplier < 0.0D || multiplier > 100.0D) {
                return null;
            }
            return Map.entry(key, multiplier);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 把单件武器倍率表读成「键 → 倍率」，写错的行跳过。
     *
     * @param lines 配置里的行
     * @return 键是武器 ID 或完整武器键
     */
    private static Map<String, Double> parseWeaponMultipliers(List<? extends String> lines) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String line : lines) {
            Map.Entry<String, Double> entry = line == null ? null : parseWeaponMultiplier(line);
            if (entry != null) {
                out.put(entry.getKey(), entry.getValue());
            } else if (line != null && !line.isBlank()) {
                AlbionMastery.LOGGER.warn("[albionmastery] 单件武器倍率写错了，跳过：{}", line);
            }
        }
        return out;
    }

    /**
     * 特化数值跟随模组版本更新：配置里记的版本比 {@link #SPEC_BALANCE_VERSION} 小、且服主没关自动更新时，
     * 把每个特化的每一项都设回默认值，再写上当前版本并保存。
     *
     * <p>在配置加载事件里、刷新快照之前调用。保存文件会再触发一次重载事件，那时版本已经对上，不会重复重置。
     *
     * @return 这次有没有重置
     */
    public static synchronized boolean migrateSpecBalance() {
        // 加锁：Forge 补配置时会写一次文件，文件监视线程随即触发重载，和启动线程的加载撞在一起；
        // 两边各跑一遍结果一样（都是改回默认值），锁住只是免得交错着改、日志也看得清
        int from = COMMON.specBalanceVersion.get();
        if (!COMMON.autoUpdateSpecBalance.get() || from >= SPEC_BALANCE_VERSION) {
            return false;
        }
        int changed = 0;
        for (SpecEntry entry : SPEC_ENTRIES.values()) {
            for (ForgeConfigSpec.DoubleValue value : entry.values().values()) {
                if (Double.compare(value.get(), value.getDefault()) != 0) {
                    value.set(value.getDefault());
                    changed++;
                }
            }
        }
        COMMON.specBalanceVersion.set(SPEC_BALANCE_VERSION);
        COMMON_SPEC.save();
        AlbionMastery.LOGGER.info("特化数值从版本 {} 更新到 {}：{} 项改回新版默认值", from, SPEC_BALANCE_VERSION, changed);
        return true;
    }

    /**
     * 把配置文件里的特化数值读成一张普通的表。
     *
     * <p>只在刷新快照时调用一次，之后所有读取都走快照，不再碰 {@code ConfigValue}。
     *
     * @return 特化键 -&gt; 数值
     */
    private static Map<String, SpecializationValues> collectSpecValues() {
        Map<String, SpecializationValues> values = new HashMap<>();
        for (Map.Entry<Specialization, SpecEntry> entry : SPEC_ENTRIES.entrySet()) {
            SpecEntry config = entry.getValue();
            // 这个特化没定义的维度取中性默认值，所以不用管每个特化各自关心哪几项
            values.put(entry.getKey().key(), SpecializationValues.of(
                    // 法术等级会进到范围、实体数量、方块数量等各种公式里，专精不再加等级：
                    // 运行快照里这一项恒为 0，配置文件里的旧值留着不删，读进来也不生效
                    key -> "spellLevel".equals(key)
                            ? 0.0D : config.get(key, SpecializationValues.defaultOf(key))));
        }
        return values;
    }

    /**
     * @return 服务端当前生效的配置快照，永不为 {@code null}
     */
    public static Snapshot serverSnapshot() {
        return serverSnapshot;
    }

    /**
     * COMMON 配置项（玩法数值）。
     */
    public static final class Common {

        /** 来源 ID → 配置里那行注释怎么写。漏了不影响功能，只是注释里退化成裸的来源 ID。 */
        private static final Map<String, String> SOURCE_NOTES = sourceNotes();

        /**
         * 各来源的中文说明。
         *
         * @return 来源 ID → 说明
         */
        private static Map<String, String> sourceNotes() {
            Map<String, String> notes = new HashMap<>();
            notes.put(WeaponKeys.PROVIDER_VANILLA_MELEE, "通用近战武器（所有模组里的剑 / 斧 / 三叉戟，以及补录进来的近战）");
            notes.put(WeaponKeys.PROVIDER_BOW, "通用弓弩（所有模组里的弓和弩）");
            notes.put(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "lrtactical 近战武器");
            notes.put(WeaponKeys.PROVIDER_TACZ_GUN, "TACZ 枪械");
            notes.put(WeaponKeys.PROVIDER_SLASHBLADE, "拔刀剑");
            notes.put(WeaponKeys.PROVIDER_IRONS_SPELLS, "Iron's Spells 'n Spellbooks（铁魔法）");
            notes.put(WeaponKeys.PROVIDER_ARS_NOUVEAU, "Ars Nouveau（新生魔艺）");
            notes.put(WeaponKeys.PROVIDER_TCONSTRUCT, "匠魂全家（Tinkers' Construct 及其附属）");
            notes.put(WeaponKeys.PROVIDER_SWEETMAGIC, "甜蜜魔法的魔法卷轴");
            notes.put(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "Iron's Arms 'n Artifice（铁枪）的火器");
            return Map.copyOf(notes);
        }

        /**
         * 来源 ID 转配置键名：{@code irons_spellbooks} → {@code ironsSpellbooks}。
         *
         * <p>按规则生成而不是一个个写死，新增来源才不会漏掉自己的那一行。
         *
         * @param providerId 来源 ID
         * @return 配置键名
         */
        private static String sourceConfigKey(String providerId) {
            StringBuilder key = new StringBuilder(providerId.length());
            boolean upper = false;
            for (int i = 0; i < providerId.length(); i++) {
                char c = providerId.charAt(i);
                if (c == '_') {
                    upper = true;
                    continue;
                }
                key.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            return key.toString();
        }

        /**
         * 把各来源的倍率读成一张普通的表，给快照和网络同步用。
         *
         * @return 来源 ID → 倍率
         */
        public Map<String, Double> sourceMultiplierValues() {
            Map<String, Double> values = new LinkedHashMap<>();
            this.sourceMasteryMultipliers.forEach((providerId, value) -> values.put(providerId, value.get()));
            return values;
        }

        public final ForgeConfigSpec.IntValue maxLevel;
        public final ForgeConfigSpec.DoubleValue overallDamagePerLevel;
        public final ForgeConfigSpec.DoubleValue typeDamagePerLevel;
        public final ForgeConfigSpec.DoubleValue weaponDamagePerLevel;
        public final ForgeConfigSpec.DoubleValue weaponCrossDamagePerLevel;
        public final ForgeConfigSpec.DoubleValue maxTotalDamageBonus;
        /**
         * 每个武器来源各自的专精伤害总倍率：来源 ID → 配置值。
         *
         * <p>按 {@link WeaponKeys#ALL_PROVIDERS} 生成，一个来源一个键，谁也不蹭谁的。
         * 以后加来源只要往那张表里加一行，这里自动多一个默认 1.0 的键。
         */
        public final Map<String, ForgeConfigSpec.DoubleValue> sourceMasteryMultipliers;
        public final ForgeConfigSpec.DoubleValue xpBase;
        public final ForgeConfigSpec.DoubleValue xpExponent;
        public final ForgeConfigSpec.DoubleValue xpPerDamage;
        public final ForgeConfigSpec.DoubleValue xpPerKill;
        public final ForgeConfigSpec.BooleanValue xpFromOriginalMobExperience;
        public final ForgeConfigSpec.IntValue npcFallbackMobExperience;
        /** CustomNPCs 掉落经验参与专精经验折算前的额外倍率。 */
        public final ForgeConfigSpec.DoubleValue npcExperienceMultiplier;
        public final ForgeConfigSpec.DoubleValue xpPerMobExperience;
        public final ForgeConfigSpec.DoubleValue typeXpMultiplier;
        public final ForgeConfigSpec.DoubleValue overallXpMultiplier;
        public final ForgeConfigSpec.DoubleValue maxXpPerHit;
        public final ForgeConfigSpec.DoubleValue netherXpMultiplier;
        public final ForgeConfigSpec.DoubleValue endXpMultiplier;
        public final ForgeConfigSpec.DoubleValue moddedDimensionXpMultiplier;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> dimensionXpMultipliers;
        public final ForgeConfigSpec.DoubleValue offhandXpRatio;
        public final ForgeConfigSpec.DoubleValue multiWeaponXpRatio;
        public final ForgeConfigSpec.DoubleValue spellMasteryCooldownShare;
        public final ForgeConfigSpec.DoubleValue spellMasteryManaShare;
        public final ForgeConfigSpec.DoubleValue spellMasteryReductionCap;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> spellEffectDurationSpells;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> spellEffectDurationIgnoredEffects;
        public final ForgeConfigSpec.ConfigValue<List<? extends Double>> shareFalloff;
        public final ForgeConfigSpec.DoubleValue nearbyShareRadius;
        public final ForgeConfigSpec.BooleanValue xpFromPlayerDamage;
        public final ForgeConfigSpec.IntValue syncIntervalTicks;
        public final ForgeConfigSpec.BooleanValue splitMeleeByPack;
        public final ForgeConfigSpec.BooleanValue enableSpecialization;
        public final ForgeConfigSpec.IntValue specSwitchCooldownSeconds;
        public final ForgeConfigSpec.IntValue comboWindowTicks;
        public final ForgeConfigSpec.DoubleValue killRefillMobRatio;
        public final ForgeConfigSpec.BooleanValue debugLogging;
        public final ForgeConfigSpec.DoubleValue doubleCastTimeFactor;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> doubleCastExcludedSpells;

        /** 补录成武器的物品。 */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> extraWeapons;

        /** 明确不算武器的物品。 */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> notWeapons;
        public final ForgeConfigSpec.IntValue specSlotCount;
        public final ForgeConfigSpec.IntValue specSlot1WeaponPercent;
        public final ForgeConfigSpec.IntValue specSlot2TypePercent;
        public final ForgeConfigSpec.IntValue specSlot3SourcePercent;
        public final ForgeConfigSpec.IntValue specSlot4TypePercent;
        public final ForgeConfigSpec.IntValue specTypeBypassPercent;
        public final ForgeConfigSpec.ConfigValue<String> specNormalLevelScale;
        public final ForgeConfigSpec.ConfigValue<String> specBreakthroughLevelScale;
        public final ForgeConfigSpec.ConfigValue<String> specNormalLevelCost;
        public final ForgeConfigSpec.ConfigValue<String> specBreakthroughLevelCost;
        public final ForgeConfigSpec.IntValue specModPointsAtMaxSource;
        public final ForgeConfigSpec.IntValue specRankModPointStart;
        public final ForgeConfigSpec.IntValue specRankModPointEvery;
        public final ForgeConfigSpec.IntValue specRankModPointMax;
        public final ForgeConfigSpec.IntValue specBreakthroughPointsAtMaxType;
        public final ForgeConfigSpec.IntValue specMaxedWeaponBreakthroughPoint;
        public final ForgeConfigSpec.IntValue specMaxedWeaponBreakthroughPointMax;
        public final ForgeConfigSpec.IntValue specRankReductionStart;
        public final ForgeConfigSpec.IntValue specRankReductionEvery;
        public final ForgeConfigSpec.IntValue specRankReductionPercentPerStep;
        public final ForgeConfigSpec.IntValue specRankReductionMaxPercent;
        public final ForgeConfigSpec.IntValue specRankReductionMaxedWeaponsInType;
        public final ForgeConfigSpec.IntValue specRespecCooldownSeconds;
        /** 单件武器的专精经验倍率，「武器 ID=倍率」。 */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> weaponXpMultipliers;
        /** 单件武器的专精伤害加成倍率，「武器 ID=倍率」。 */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> weaponDamageMultipliers;
        public final ForgeConfigSpec.BooleanValue autoUpdateSpecBalance;
        public final ForgeConfigSpec.IntValue specBalanceVersion;
        public final ForgeConfigSpec.BooleanValue leaderboardEnable;
        public final ForgeConfigSpec.IntValue leaderboardTopCount;
        public final ForgeConfigSpec.IntValue leaderboardUpdateIntervalSeconds;

        /** 榜单条目保留天数。 */
        public final ForgeConfigSpec.IntValue leaderboardRetentionDays;
        public final ForgeConfigSpec.IntValue leaderboardRequestCooldownSeconds;
        public final ForgeConfigSpec.BooleanValue leaderboardBroadcastTopChanges;
        public final ForgeConfigSpec.EnumValue<LeaderboardSort> leaderboardBroadcastSort;

        /** 每件武器每升一级给多少段位经验。 */
        public final ForgeConfigSpec.IntValue rankXpPerWeaponLevel;
        /** 晋升段位时在聊天栏通告全服。 */
        public final ForgeConfigSpec.BooleanValue rankUpBroadcast;

        Common(ForgeConfigSpec.Builder builder) {
            builder.comment("武器专精系统 · 玩法数值。以服务端配置为准，登录时下发给客户端用于界面显示")
                    .push("mastery");

            this.maxLevel = builder
                    .comment("专精等级上限，类型与单件武器共用同一上限")
                    .defineInRange("maxLevel", 100, 1, 1000);

            this.overallDamagePerLevel = builder
                    .comment("总体专精每 1 级，为该来源下所有武器提供的伤害加成（0.005 = 0.5%）")
                    .defineInRange("overallDamagePerLevel", 0.005D, 0.0D, 1.0D);

            this.typeDamagePerLevel = builder
                    .comment("武器类型专精每 1 级，为该类型下所有武器提供的伤害加成（0.01 = 1%）")
                    .defineInRange("typeDamagePerLevel", 0.01D, 0.0D, 1.0D);

            this.weaponDamagePerLevel = builder
                    .comment("单件武器专精每 1 级，为该武器自身提供的伤害加成（0.02 = 2%）")
                    .defineInRange("weaponDamagePerLevel", 0.02D, 0.0D, 1.0D);

            this.weaponCrossDamagePerLevel = builder
                    .comment("单件武器专精每 1 级，为同类型下其它武器提供的伤害加成（0.001 = 0.1%）")
                    .defineInRange("weaponCrossDamagePerLevel", 0.001D, 0.0D, 1.0D);

            this.maxTotalDamageBonus = builder
                    .comment("单件武器可获得的伤害加成总上限（100.0 = 最多 +10000%），用于防止数值失控")
                    .defineInRange("maxTotalDamageBonus", 100.0D, 0.0D, 1000.0D);

            builder.comment("不同武器来源的专精伤害倍率。",
                            "此倍率作用于专精本身的伤害加成：总体（内圈）、类型（中圈）、单件武器（外圈）及同类联动均一同乘算。",
                            "每个来源一个键，互不影响，默认均为 1.0 = 保持专精原本的强度。",
                            "模组新增来源时，此处会自动增加一项，不沿用其他来源的倍率。",
                            "更新模组不会覆盖已修改的值。")
                    .push("sourceDamageMultiplier");
            Map<String, ForgeConfigSpec.DoubleValue> multipliers = new LinkedHashMap<>();
            for (String providerId : WeaponKeys.ALL_PROVIDERS) {
                String note = SOURCE_NOTES.getOrDefault(providerId, providerId);
                multipliers.put(providerId, builder
                        .comment(note + "的专精伤害倍率（来源 ID：" + providerId + "）")
                        .defineInRange(sourceConfigKey(providerId), 1.0D, 0.0D, 100.0D));
            }
            this.sourceMasteryMultipliers = Collections.unmodifiableMap(multipliers);
            builder.pop();

            this.weaponDamageMultipliers = builder
                    .comment("单件武器的专精伤害加成倍率，一行一条「武器ID=倍率」，乘在专精加成上，与上方的来源倍率叠乘。",
                            "武器 ID：普通物品写物品 ID；TACZ 枪写枪的 ID（如 tacz:ak47）；铁魔法写法术 ID；拔刀剑写刀的 ID。",
                            "同一 ID 在两个来源中重复时，可写完整武器键（如 tacz_gun/tacz:ak47）。",
                            "手持武器执行 /albionmastery info，第一行即武器键，点击可复制。例：\"tacz:m95=0.8\"")
                    .defineListAllowEmpty("weaponDamageMultipliers", List.of(), MasteryConfig::validWeaponMultiplier);

            this.xpBase = builder
                    .comment("经验曲线基数：升到第 L 级所需经验 = round(xpBase * L ^ xpExponent)。",
                            "默认值已由 40 改为 80，新配置的升级需求为原来的两倍。",
                            "已有配置保留原值；旧服如需同样调整，请自行将原值乘以 2。")
                    .defineInRange("xpBase", 80.0D, 0.1D, 1.0E9D);

            this.xpExponent = builder
                    .comment("经验曲线指数，越大后期升级越慢。",
                            "默认 1.0 为线性：单级需求随目标等级线性增长。",
                            "设为 1.25 时满级总经验约为默认的 2.8 倍。")
                    .defineInRange("xpExponent", 1.0D, 0.0D, 5.0D);

            this.xpPerDamage = builder
                    .comment("每造成 1 点伤害获得的专精经验，按加成生效前的基础伤害计算。",
                            "默认 0：专精经验只来自击杀时生物实际掉落的经验值，",
                            "攻击木桩或高生命值、低价值的怪物不会获得经验。需要按伤害给予经验时调大此值。")
                    .defineInRange("xpPerDamage", 0.0D, 0.0D, 1000.0D);

            this.xpPerKill = builder
                    .comment("每次击杀固定获得的专精经验，与生物强弱无关。",
                            "默认 0：完全按生物实际掉落的经验值折算。")
                    .defineInRange("xpPerKill", 0.0D, 0.0D, 100000.0D);

            this.xpPerMobExperience = builder
                    .comment("被击杀生物每掉落 1 点经验值，额外获得的专精经验。",
                            "击杀凋灵与鸡所得的专精经验差距随之拉大。设为 0 时仅有固定击杀奖励。")
                    .defineInRange("xpPerMobExperience", 8.0D, 0.0D, 1000.0D);
            this.xpFromOriginalMobExperience = builder
                    .comment("折算专精经验时使用生物原本的掉落经验（true），还是经其他模组修改后的实际掉落经验（false）。",
                            "莱特兰-恶意会按怪物等级将掉落经验放大数倍至数十倍，金龟等附魔也有加成，",
                            "使用实际掉落值时专精经验会随之膨胀。默认 true，只按原版数值计算")
                    .define("xpFromOriginalMobExperience", true);
            this.npcFallbackMobExperience = builder
                    .comment("击杀自定义 NPC（CustomNPCs）时，优先读取 NPC 编辑页「掉落」中设置的经验（最小 / 最大的平均值）；",
                            "读不到 CustomNPCs 数据时才使用此兜底值。NPC 的最小 / 最大经验都设为 0 时结果为 0，不使用兜底值。",
                            "未安装 CustomNPCs 时此项无效，本模组不依赖该模组")
                    .defineInRange("npcFallbackMobExperience", 0, 0, 100000);
            this.npcExperienceMultiplier = builder
                    .comment("CustomNPCs 的掉落经验参与专精经验折算前额外乘的倍率。默认 0.1 = 只取原配置经验的 1/10。",
                            "NPC 原始经验大于 1 且该倍率大于 0 时，折算后的经验最低按 1 计算。",
                            "NPC 的最小 / 最大经验都为 0 时结果仍为 0，不受最低值影响。")
                    .defineInRange("npcExperienceMultiplier", 0.1D, 0.0D, 100.0D);

            this.typeXpMultiplier = builder
                    .comment("武器类型专精升级所需经验的倍率，以单件武器为基准。",
                            "3.0 表示练满一个类型所需经验为练满一件武器的 3 倍。",
                            "类型与单件武器每次获得的经验相同，进度差别只取决于此倍率，",
                            "与玩家使用的武器数量无关。")
                    .defineInRange("typeXpMultiplier", 3.0D, 0.1D, 100.0D);

            this.overallXpMultiplier = builder
                    .comment("总体（来源）专精的升级所需经验倍率，以单件武器为基准")
                    .defineInRange("overallXpMultiplier", 10.0D, 0.1D, 100.0D);

            this.weaponXpMultipliers = builder
                    .comment("单件武器的专精经验倍率，一行一条「武器ID=倍率」：使用该武器击杀所得经验乘此倍率，",
                            "类型和来源同样按此倍率计算。武器 ID 的写法见 weaponDamageMultipliers。",
                            "例：\"tacz:ak47=0.5\"、\"minecraft:netherite_sword=1.5\"、\"irons_spellbooks:fireball=2\"")
                    .defineListAllowEmpty("weaponXpMultipliers", List.of(), MasteryConfig::validWeaponMultiplier);

            this.maxXpPerHit = builder
                    .comment("单次命中获得的经验上限，防止超高伤害导致经验过多")
                    .defineInRange("maxXpPerHit", 200.0D, 1.0D, 1.0E9D);

            this.offhandXpRatio = builder
                    .comment("副手武器获得的专精经验比例。",
                            "主手为全额，副手默认半额，以免副手闲置的武器也随之练满。",
                            "双手持同一件武器时只按主手计算一次，不叠加。")
                    .defineInRange("offhandXpRatio", 0.5D, 0.0D, 1.0D);
            this.multiWeaponXpRatio = builder
                    .comment("一件物品包含多件武器时（如铁魔法的魔法书装有多个法术），",
                            "每件武器获得的击杀经验乘以此比例。1.0 = 每个法术都获得全额")
                    .defineInRange("multiWeaponXpRatio", 1.0D, 0.0D, 1.0D);
            this.spellMasteryCooldownShare = builder
                    .comment("铁魔法：专精加成按此比例同时折算为冷却缩减。",
                            "例：加成 +40%、比例 0.3 → 冷却缩短 12%。",
                            "传送、净化等无数值的法术只能通过此项和法力折扣体现专精，设为 0 时不生效")
                    .defineInRange("spellMasteryCooldownShare", 0.3D, 0.0D, 1.0D);
            this.spellMasteryManaShare = builder
                    .comment("铁魔法：专精加成有多大比例同时折算成法力消耗折扣，算法同上")
                    .defineInRange("spellMasteryManaShare", 0.3D, 0.0D, 1.0D);
            this.spellMasteryReductionCap = builder
                    .comment("以上两项折算出的缩减上限（0.5 = 最多减半），防止高加成使冷却和法力消耗趋近于零")
                    .defineInRange("spellMasteryReductionCap", 0.5D, 0.0D, 0.9D);
            this.spellEffectDurationSpells = builder
                    .comment("铁魔法：所列法术施加的效果（增益 / 减益）持续时间额外按专精倍率延长。",
                            "铁魔法本体的效果时长几乎都由法术强度计算，随强度增长，无需列出；",
                            "此处只列时长按法术等级固定、与强度无关的法术，多来自附属模组。",
                            "所列法术不会被重复放大。填写法术 ID，如 gtbcs_geomancy_plus:tremor_step")
                    .defineListAllowEmpty("spellEffectDurationSpells", List.of(
                            "gtbcs_geomancy_plus:tremor_step",
                            "gtbcs_geomancy_plus:seismic_surf",
                            "gtbcs_geomancy_plus:solar_storm",
                            "gtbcs_geomancy_plus:solar_beam",
                            "gtbcs_geomancy_plus:petrivise",
                            "gtbcs_geomancy_plus:earthshatter",
                            "irons_spellbooks:echoing_strikes"),
                            value -> value instanceof String);
            this.spellEffectDurationIgnoredEffects = builder
                    .comment("延长效果时长时始终排除的效果 ID。ID 以 _cast / _loop 结尾或含 casting / resource 的",
                            "施法状态类效果已自动排除，无需列出；此处只填额外排除的效果")
                    .defineListAllowEmpty("spellEffectDurationIgnoredEffects", List.of(),
                            value -> value instanceof String);

            this.netherXpMultiplier = builder
                    .comment("在下界击杀时的专精经验倍率")
                    .defineInRange("netherXpMultiplier", 1.5D, 0.0D, 100.0D);

            this.endXpMultiplier = builder
                    .comment("在末地击杀时的专精经验倍率")
                    .defineInRange("endXpMultiplier", 2.0D, 0.0D, 100.0D);

            this.moddedDimensionXpMultiplier = builder
                    .comment("在模组维度击杀时的专精经验倍率。",
                            "判定方式为维度 ID 的命名空间不是 minecraft，",
                            "对所有维度模组自动生效，无需逐个登记。")
                    .defineInRange("moddedDimensionXpMultiplier", 2.0D, 0.0D, 100.0D);

            this.dimensionXpMultipliers = builder
                    .comment("单独指定部分维度的倍率，优先级高于以上所有规则。",
                            "每行格式：维度ID=倍率，例如 twilightforest:twilight_forest=1.8")
                    .defineList("dimensionXpMultipliers", List.of(),
                            entry -> entry instanceof String);

            this.shareFalloff = builder
                    .comment("附近玩家共享击杀经验时每人获得的比例，按参与人数查表。",
                            "第一项为 1 人时（100%），第二项为 2 人时每人的比例，以此类推；",
                            "人数超过表长时沿用最后一项。",
                            "默认值下人数越多每人所得越少，但队伍总收益上升。",
                            "只有主副手持有未练满的可专精武器的玩家计入人数。")
                    .defineList("shareFalloff",
                            List.of(1.0D, 0.725D, 0.633D, 0.575D, 0.55D),
                            value -> value instanceof Number number
                                    && number.doubleValue() >= 0.0D
                                    && number.doubleValue() <= 1.0D);

            this.nearbyShareRadius = builder
                    .comment("经验共享的判定半径（方块），只在击杀者所在维度内查找")
                    .defineInRange("nearbyShareRadius", 32.0D, 0.0D, 256.0D);

            this.xpFromPlayerDamage = builder
                    .comment("伤害玩家时是否获得专精经验。PVP 服建议开启，纯 PVE 服可关闭，避免玩家互相攻击获得经验")
                    .define("xpFromPlayerDamage", true);

            this.syncIntervalTicks = builder
                    .comment("专精数据变化后同步给客户端的最小间隔（tick），用于合并高射速武器产生的大量小改动")
                    .defineInRange("syncIntervalTicks", 20, 1, 200);

            this.splitMeleeByPack = builder
                    .comment("近战附属（lrtactical）的武器类型是否按刀包拆分。",
                            "开启：每个刀包各为一个武器类型，专精罗盘多出一层，与拔刀剑附属包的处理一致。",
                            "关闭：所有近战武器共用一个「近战」类型，与 1.0.0 相同。",
                            "切换后近战的类型层等级从零开始；",
                            "武器层与总体层的等级不受影响。")
                    .define("splitMeleeByPack", true);

            builder.comment("专精特化：每个武器类型（中圈）最多 4 个槽，依次为普通、突破、普通、突破；武器达到门槛后生效，各槽效果叠加。",
                            "特化用改装点升级（Lv1～Lv5），普通版满级后用突破点突破（★1～★5），下方各特化小节中的数值为参考值。",
                            "各来源叫法不同：枪和弓称改装，刀称改造，剑称专长，拔刀剑称修习，铁魔法称铭刻，魔艺称改良。",
                            "各来源的第一项（原厂配置、无专长、无修习等）为不做任何改动的默认值，其余各项有得有失。",
                            "倍率均乘在原值上，时间类小于 1 表示更快。")
                    .push("specialization");
            this.enableSpecialization = builder
                    .comment("是否启用特化系统。关闭后已选特化不生效，但仍保留在存档中")
                    .define("enable", true);
            this.specSwitchCooldownSeconds = builder
                    .comment("切换特化的冷却时间（秒），默认 1 小时。",
                            "冷却记录在内存中，服务端重启后清零")
                    .defineInRange("switchCooldownSeconds", 3600, 0, 604800);
            this.specSlotCount = builder
                    .comment("每个武器类型开放的特化槽数（1～4），按普通、突破、普通、突破的顺序取前几个。",
                            "普通槽装普通版（Lv1～Lv5），突破槽装突破版（★1～★5）。同一套配置中不能装两个同类别的特化。",
                            "只开 1 个槽时没有突破槽，该槽直接使用特化当前最强的一版。各槽效果叠加：",
                            "倍率相乘，概率和比例相加，层数取最大值")
                    .defineInRange("slotCount", 4, 1, 4);
            this.specSlot1WeaponPercent = builder
                    .comment("槽 1：武器等级达到上限的此百分比后，所属类型的特化才对该武器生效。段位可减免此门槛，见下方 rankReduction*。",
                            "此门槛按武器分别计算，所有槽都需先满足；类型达到 typeMasteryBypassPercent 后，整个类型免除此门槛")
                    .defineInRange("slot1WeaponPercent", 100, 1, 100);
            this.specSlot2TypePercent = builder
                    .comment("槽 2（突破槽）：类型要到等级上限的百分之几才开。0 = 不要求")
                    .defineInRange("slot2TypePercent", 70, 0, 100);
            this.specSlot3SourcePercent = builder
                    .comment("槽 3（普通槽）：来源（内圈）要到等级上限的百分之几才开。0 = 不要求")
                    .defineInRange("slot3SourcePercent", 40, 0, 100);
            this.specSlot4TypePercent = builder
                    .comment("槽 4（突破槽）：类型要到等级上限的百分之几才开。0 = 不要求")
                    .defineInRange("slot4TypePercent", 100, 0, 100);
            this.specTypeBypassPercent = builder
                    .comment("类型等级达到上限的该百分比后，该类型下所有武器免除槽 1 的武器门槛。0 = 不豁免")
                    .defineInRange("typeMasteryBypassPercent", 100, 0, 100);
            this.specNormalLevelScale = builder
                    .comment("普通版每一级的系数，用逗号隔开，填几个数即分几级（最多 10 级）。",
                            "下方各特化小节中的数值为参考值：系数 0.2 表示从原值向参考值变化两成，正面与负面效果按同一系数增长。",
                            "默认普通版满级为参考值的 0.8 倍")
                    .define("normalLevelScale", "0.2, 0.35, 0.5, 0.65, 0.8", SpecRules::validList);
            this.specBreakthroughLevelScale = builder
                    .comment("突破版每一级的系数，规则同上。普通版满级后才能突破，默认突破满级为参考值的 1.2 倍")
                    .define("breakthroughLevelScale", "0.88, 0.96, 1.04, 1.12, 1.2", SpecRules::validList);
            this.specNormalLevelCost = builder
                    .comment("普通版每升一级所需改装点数，用逗号隔开；条数少于级数时沿用最后一项")
                    .define("normalLevelCost", "1, 1, 1, 1, 1", SpecRules::validList);
            this.specBreakthroughLevelCost = builder
                    .comment("突破版每升一级所需突破点数，规则同上")
                    .define("breakthroughLevelCost", "1, 1, 1, 1, 1", SpecRules::validList);
            this.specModPointsAtMaxSource = builder
                    .comment("改装点（各来源叫法不同，如改造点、专长点、修习点）按来源等级发放，各来源分别计算：",
                            "来源满级时为此数值，中间等级线性折算并向下取整。默认 20 = 每 5 级 1 点。",
                            "普通版等级在同一来源内共用：在步枪中升到 Lv3 的特化，在冲锋枪中也是 Lv3")
                    .defineInRange("modPointsAtMaxSource", 20, 0, 1000);
            this.specRankModPointStart = builder
                    .comment("段位加改装点：起始段位，每个来源 +1")
                    .defineInRange("rankModPointStart", 3, 1, 100000);
            this.specRankModPointEvery = builder
                    .comment("段位加改装点：此后每几段再 +1")
                    .defineInRange("rankModPointEvery", 3, 1, 100000);
            this.specRankModPointMax = builder
                    .comment("段位加改装点：最多加几点。0 = 段位不加改装点")
                    .defineInRange("rankModPointMax", 10, 0, 1000);
            this.specBreakthroughPointsAtMaxType = builder
                    .comment("突破点按类型等级发放，各类型分别计算：类型满级时为此数值，中间线性折算并向下取整。",
                            "突破按类型分别计算：在步枪中突破的特化，在冲锋枪中需用冲锋枪的突破点另行突破")
                    .defineInRange("breakthroughPointsAtMaxType", 10, 0, 1000);
            this.specMaxedWeaponBreakthroughPoint = builder
                    .comment("类型中每有一件满级武器额外发放的突破点。0 = 不发放")
                    .defineInRange("maxedWeaponBreakthroughPoint", 1, 0, 100);
            this.specMaxedWeaponBreakthroughPointMax = builder
                    .comment("满级武器额外发放突破点的上限")
                    .defineInRange("maxedWeaponBreakthroughPointMax", 5, 0, 1000);
            this.specRankReductionStart = builder
                    .comment("段位减免槽 1 武器门槛的起始段位")
                    .defineInRange("rankReductionStart", 3, 1, 100000);
            this.specRankReductionEvery = builder
                    .comment("段位减免：此后每几段再减一档")
                    .defineInRange("rankReductionEvery", 2, 1, 100000);
            this.specRankReductionPercentPerStep = builder
                    .comment("段位减免：每档减去等级上限的百分比（默认 5 = 上限 100 时每档减 5 级）。0 = 不减免")
                    .defineInRange("rankReductionPercentPerStep", 5, 0, 100);
            this.specRankReductionMaxPercent = builder
                    .comment("段位减免：最多减去等级上限的百分比")
                    .defineInRange("rankReductionMaxPercent", 50, 0, 100);
            this.specRankReductionMaxedWeaponsInType = builder
                    .comment("段位减免的前提：该武器所属类型中已有的满级武器数。0 = 不要求")
                    .defineInRange("rankReductionMaxedWeaponsInType", 1, 0, 1000);
            this.specRespecCooldownSeconds = builder
                    .comment("重置改装点 / 突破点的冷却（秒），默认 24 小时，记录在玩家存档中，重启不清零。",
                            "旧存档完成改版迁移后，首次重置不计冷却")
                    .defineInRange("respecCooldownSeconds", 86400, 0, 2592000);
            this.autoUpdateSpecBalance = builder
                    .comment("模组更新调整特化数值时，是否自动将下方各特化的数值替换为新版默认值。",
                            "Forge 配置只补充缺少的项，文件中已有的旧数值不会被新默认值覆盖；",
                            "关闭此项时，升级后旧服务器上的特化仍为旧数值，说明文字也按旧数值显示。",
                            "改过特化数值且希望保留的服主请关闭此项")
                    .define("autoUpdateSpecBalance", true);
            this.specBalanceVersion = builder
                    .comment("特化数值的版本号，由模组维护，无需手动修改。",
                            "小于当前版本且上一项开启时，下次启动会将全部特化数值重置为新默认值，并写入当前版本")
                    .defineInRange("specBalanceVersion", 0, 0, Integer.MAX_VALUE);
            this.debugLogging = builder
                    .comment("排查问题用：将特化效果的关键路径（双重施法排队 / 补放、法力折扣等）写入 INFO 日志。",
                            "默认关闭；玩家多时日志量大")
                    .define("debugLogging", false);
            this.doubleCastTimeFactor = builder
                    .comment("双重施法额外施放时，施法时间（引导 / 前摇）乘以此系数。0.5 = 减半")
                    .defineInRange("doubleCastTimeFactor", 0.5D, 0.05D, 1.0D);
            this.doubleCastExcludedSpells = builder
                    .comment("始终不触发双重施法的法术 ID。持续引导类（CONTINUOUS）和类名含 Summon 的召唤类已自动排除，",
                            "此处填写额外排除的法术，如类名不含 Summon 的召唤亡灵")
                    .defineListAllowEmpty("doubleCastExcludedSpells", List.of(
                            "irons_spellbooks:raise_dead",
                            "irons_spellbooks:raise_hell"),
                            value -> value instanceof String);
            this.extraWeapons = builder
                    .comment("补录为武器的物品，格式「物品id=类型」，类型可填 sword / axe / trident / other / bow / crossbow，",
                            "只写物品 id 不写类型时按 other（其他近战）计算。",
                            "不继承原版武器类的模组武器需在此补录，常见的（灾变、诡厄、额外植物学、血肉重塑）已内置，",
                            "此处只填内置表未收录的。例：\"examplemod:big_sword=sword\"")
                    .defineListAllowEmpty("extraWeapons", List.of(), value -> value instanceof String);
            this.notWeapons = builder
                    .comment("明确不算武器的物品 id：误收入专精罗盘的物品填在此处即被移除，优先级高于上方的补录表和物品类判定")
                    .defineListAllowEmpty("notWeapons", List.of(), value -> value instanceof String);
            this.killRefillMobRatio = builder
                    .comment("击杀补弹：特化中的补弹比例按击杀玩家计算，击杀怪物时再乘此系数。",
                            "默认 0.2：弹药带击杀玩家补 50%，击杀怪物只补 10%，以免连续击杀时无需换弹")
                    .defineInRange("killRefillMobRatio", 0.2D, 0.0D, 1.0D);
            this.comboWindowTicks = builder
                    .comment("连击的时间窗（tick）：两次命中同一目标间隔超过此时间，连击层数清零。默认 3 秒")
                    .defineInRange("comboWindowTicks", 60, 1, 1200);

            // 每个特化一个小节，只暴露它真正用到的数值项。
            // 设计规则：
            //   1. 每个特化「两三样好处 + 一两样代价」，好处里至少有一样在本来源里是它独有的；
            //   2. 同一来源里按系别（枪械是改装部位）分组，两个槽不能装同系的，所以同系之间可以是同一方向的强弱取舍；
            //   3. 各来源都有「对人 / 对怪 / 破甲 / 残血 / 吸血 / 连击 / 练级」这些通用路子，但一定配上本来源的专属维度，
            //      不做数字一模一样的换皮（枪械配弹速散布，刀配背刺，剑配距离击退，拔刀剑配拔刀技刀魂，法术配冷却前摇法力，
            //      弓配拉弓爆头省箭，魔艺配投射物）。
            // 改了这里的默认值，要把上面的 SPEC_BALANCE_VERSION 加一，老配置里的特化数值才会跟着更新。

            // ── 枪械 · 弹匣 ──
            defineSpec(builder, Specialization.GUN_EXTENDED_MAG, values -> {
                values.put("magazine", 1.8D);
                values.put("killRefill", 0.15D);
                values.put("reloadTime", 1.4D);
                values.put("adsTime", 1.1D);
            });
            defineSpec(builder, Specialization.GUN_COMPACT_MAG, values -> {
                values.put("reloadTime", 0.5D);
                values.put("drawTime", 0.8D);
                values.put("moveSpeed", 1.05D);
                values.put("magazine", 0.6D);
            });
            defineSpec(builder, Specialization.GUN_JUNGLE_CLIP, values -> {
                values.put("reloadTime", 0.65D);
                values.put("magazine", 1.15D);
                values.put("adsTime", 1.15D);
                values.put("moveSpeed", 0.95D);
            });
            defineSpec(builder, Specialization.GUN_DRUM_MAGAZINE, values -> {
                values.put("magazine", 2.5D);
                values.put("recoil", 0.9D);
                values.put("reloadTime", 2.0D);
                values.put("adsTime", 1.25D);
                values.put("drawTime", 1.3D);
                values.put("moveSpeed", 0.9D);
            });
            defineSpec(builder, Specialization.GUN_BOTTOM_LOAD, values -> {
                values.put("lastRoundDamage", 1.6D);
                values.put("magazine", 0.9D);
            });
            // ── 枪械 · 弹药 ──
            defineSpec(builder, Specialization.GUN_HEAVY_AMMO, values -> {
                values.put("damage", 2.2D);
                values.put("magazine", 0.5D);
                values.put("reloadTime", 1.6D);
                values.put("recoil", 1.35D);
                values.put("fireRate", 0.85D);
            });
            defineSpec(builder, Specialization.GUN_HOLLOW_POINT, values -> {
                values.put("executeDamage", 2.2D);
                values.put("executeThreshold", 0.35D);
                values.put("freshDamage", 0.75D);
                values.put("armoredDamage", 0.85D);
            });
            defineSpec(builder, Specialization.GUN_SIPHON_ROUNDS, values -> {
                values.put("lifeSteal", 0.1D);
                values.put("damage", 0.85D);
                values.put("magazine", 0.85D);
            });
            defineSpec(builder, Specialization.GUN_TRACER_ROUNDS, values -> {
                values.put("comboStep", 0.04D);
                values.put("comboMax", 10.0D);
                values.put("hipSpread", 0.85D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.GUN_ARMOR_PIERCING, values -> {
                values.put("armoredDamage", 1.8D);
                values.put("bulletSpeed", 1.1D);
                values.put("unarmoredDamage", 0.8D);
            });
            defineSpec(builder, Specialization.GUN_SOFT_POINT, values -> {
                values.put("unarmoredDamage", 1.4D);
                values.put("bodyShotDamage", 1.15D);
                values.put("armoredDamage", 0.7D);
            });
            defineSpec(builder, Specialization.GUN_HOT_LOAD, values -> {
                values.put("damage", 1.25D);
                values.put("bulletSpeed", 1.25D);
                values.put("recoil", 1.3D);
                values.put("reloadTime", 1.15D);
            });
            defineSpec(builder, Specialization.GUN_PENETRATOR, values -> {
                values.put("pierce", 2.0D);
                values.put("bulletSpeed", 1.2D);
                values.put("damage", 0.85D);
                values.put("magazine", 0.85D);
            });
            defineSpec(builder, Specialization.GUN_INCENDIARY, values -> {
                values.put("igniteChance", 0.3D);
                values.put("damage", 0.9D);
                values.put("bulletSpeed", 0.9D);
            });
            // ── 枪械 · 枪口 ──
            defineSpec(builder, Specialization.GUN_MATCH_BARREL, values -> {
                values.put("headShotDamage", 2.0D);
                values.put("aimSpread", 0.7D);
                values.put("bulletSpeed", 1.15D);
                values.put("bodyShotDamage", 0.75D);
            });
            defineSpec(builder, Specialization.GUN_SUPPRESSOR, values -> {
                values.put("gunVolume", 0.3D);
                values.put("critChance", 0.12D);
                values.put("critDamage", 1.5D);
                values.put("damage", 0.9D);
                // 亚音速弹：消音器的老搭档，安静的代价是飞得慢
                values.put("bulletSpeed", 0.85D);
            });
            defineSpec(builder, Specialization.GUN_MUZZLE_BRAKE, values -> {
                values.put("recoil", 0.6D);
                values.put("hipSpread", 0.8D);
                values.put("gunVolume", 1.5D);
                values.put("damage", 0.95D);
            });
            // ── 枪械 · 枪机 ──
            defineSpec(builder, Specialization.GUN_RAPID_CYCLE, values -> {
                values.put("fireRate", 1.3D);
                values.put("recoil", 1.3D);
                values.put("hipSpread", 1.25D);
                values.put("aimSpread", 1.15D);
            });
            defineSpec(builder, Specialization.GUN_HEAVY_BUFFER, values -> {
                values.put("damage", 1.3D);
                values.put("recoil", 0.7D);
                values.put("fireRate", 0.75D);
            });
            defineSpec(builder, Specialization.GUN_STRAIGHT_PULL, values -> {
                values.put("boltTime", 0.5D);
                values.put("reloadTime", 0.85D);
                values.put("recoil", 1.15D);
            });
            // ── 枪械 · 枪身 ──
            defineSpec(builder, Specialization.GUN_TACTICAL_GRIP, values -> {
                values.put("recoil", 0.8D);
                values.put("adsTime", 0.85D);
                values.put("hipSpread", 0.8D);
                values.put("drawTime", 1.15D);
            });
            defineSpec(builder, Specialization.GUN_MARKSMAN_STOCK, values -> {
                values.put("aimSpread", 0.4D);
                values.put("adsTime", 0.8D);
                values.put("recoil", 0.85D);
                values.put("hipSpread", 1.5D);
                values.put("moveSpeed", 0.93D);
            });
            defineSpec(builder, Specialization.GUN_LIGHT_FRAME, values -> {
                values.put("moveSpeed", 1.12D);
                values.put("sprintTime", 0.6D);
                values.put("drawTime", 0.85D);
                values.put("recoil", 1.2D);
                values.put("damage", 0.9D);
            });
            // ── 枪械 · 训练 ──
            defineSpec(builder, Specialization.GUN_POINT_SHOOTING, values -> {
                values.put("hipSpread", 0.4D);
                values.put("moveSpeed", 1.05D);
                values.put("adsTime", 1.35D);
                values.put("aimSpread", 1.25D);
            });
            defineSpec(builder, Specialization.GUN_VETERAN_MANUAL, values -> {
                values.put("xpGain", 1.8D);
                values.put("reloadTime", 0.9D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.GUN_AMMO_DISCIPLINE, values -> {
                values.put("ammoSave", 0.3D);
                values.put("aimSpread", 0.85D);
                values.put("fireRate", 0.85D);
            });
            defineSpec(builder, Specialization.GUN_BREACHER, values -> {
                values.put("nearDamage", 1.5D);
                values.put("moveSpeed", 1.05D);
                values.put("farDamage", 0.7D);
            });
            defineSpec(builder, Specialization.GUN_LONGSHOT, values -> {
                values.put("farDamage", 1.4D);
                values.put("aimSpread", 0.9D);
                values.put("nearDamage", 0.8D);
                values.put("adsTime", 1.1D);
            });
            // ── 枪械 · 装具 ──
            defineSpec(builder, Specialization.GUN_BANDOLIER, values -> {
                values.put("killRefill", 0.5D);
                values.put("reloadTime", 0.9D);
                values.put("moveSpeed", 0.95D);
            });
            defineSpec(builder, Specialization.GUN_FIELD_DRESSING, values -> {
                values.put("damageTaken", 0.8D);
                values.put("moveSpeed", 0.92D);
                values.put("sprintTime", 1.2D);
            });
            defineSpec(builder, Specialization.GUN_QUICKDRAW, values -> {
                values.put("drawTime", 0.3D);
                values.put("sprintTime", 0.7D);
                values.put("damage", 0.93D);
            });
            defineSpec(builder, Specialization.GUN_ADRENALINE, values -> {
                values.put("frenzyStep", 0.08D);
                values.put("frenzyMax", 5.0D);
                values.put("killHeal", 0.05D);
                values.put("damageTaken", 1.1D);
            });

            // ── 近战刀包 · 握柄 ──
            defineSpec(builder, Specialization.MELEE_WRAPPED_GRIP, values -> {
                values.put("attackInterval", 0.65D);
                values.put("moveSpeed", 1.08D);
                values.put("damage", 0.7D);
            });
            defineSpec(builder, Specialization.MELEE_SHORT_GRIP, values -> {
                values.put("backstabDamage", 1.6D);
                values.put("moveSpeed", 1.08D);
                values.put("damage", 0.88D);
                values.put("knockback", 0.5D);
            });
            defineSpec(builder, Specialization.MELEE_BALANCED_POMMEL, values -> {
                values.put("attackInterval", 0.85D);
                values.put("knockback", 1.3D);
                values.put("durability", 1.4D);
            });
            // ── 近战刀包 · 刀身 ──
            defineSpec(builder, Specialization.MELEE_WEIGHTED_HEAD, values -> {
                values.put("damage", 1.45D);
                values.put("knockback", 1.6D);
                values.put("critChance", 0.1D);
                values.put("critDamage", 1.6D);
                values.put("attackInterval", 1.5D);
                values.put("durability", 1.3D);
            });
            defineSpec(builder, Specialization.MELEE_HARDENED_TIP, values -> {
                values.put("armoredDamage", 1.8D);
                values.put("durability", 0.7D);
                values.put("unarmoredDamage", 0.8D);
            });
            defineSpec(builder, Specialization.MELEE_FORGED_STEEL, values -> {
                values.put("durability", 0.35D);
                values.put("damage", 1.1D);
                values.put("attackInterval", 1.15D);
            });
            // ── 近战刀包 · 刃口 ──
            defineSpec(builder, Specialization.MELEE_SERRATED_EDGE, values -> {
                values.put("executeDamage", 2.3D);
                values.put("executeThreshold", 0.3D);
                values.put("lifeSteal", 0.05D);
                values.put("freshDamage", 0.75D);
            });
            defineSpec(builder, Specialization.MELEE_KEEN_EDGE, values -> {
                values.put("comboStep", 0.07D);
                values.put("comboMax", 6.0D);
                values.put("attackInterval", 0.95D);
                values.put("damage", 0.82D);
            });
            defineSpec(builder, Specialization.MELEE_RECURVE_BLADE, values -> {
                values.put("critChance", 0.2D);
                values.put("critDamage", 1.6D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.MELEE_BLOOD_GROOVE, values -> {
                values.put("lifeSteal", 0.13D);
                values.put("durability", 0.85D);
                values.put("damage", 0.82D);
            });
            defineSpec(builder, Specialization.MELEE_VENOM_EDGE, values -> {
                values.put("weakenChance", 0.35D);
                values.put("slowChance", 0.2D);
                values.put("damage", 0.9D);
            });
            // ── 近战刀包 · 战法 ──
            defineSpec(builder, Specialization.MELEE_BLEEDER, values -> {
                values.put("freshDamage", 1.5D);
                values.put("backstabDamage", 1.25D);
                values.put("executeDamage", 0.8D);
                values.put("executeThreshold", 0.3D);
            });
            defineSpec(builder, Specialization.MELEE_TRENCH_KNIFE, values -> {
                values.put("vsPlayerDamage", 1.45D);
                values.put("moveSpeed", 1.05D);
                values.put("vsMobDamage", 0.85D);
            });
            defineSpec(builder, Specialization.MELEE_HUNTING_KNIFE, values -> {
                values.put("vsMobDamage", 1.3D);
                values.put("backstabDamage", 1.15D);
                values.put("vsPlayerDamage", 0.8D);
            });
            defineSpec(builder, Specialization.MELEE_BLOODLUST, values -> {
                values.put("frenzyStep", 0.1D);
                values.put("frenzyMax", 3.0D);
                values.put("moveSpeed", 1.05D);
                values.put("durability", 1.2D);
            });
            // ── 近战刀包 · 保养 ──
            defineSpec(builder, Specialization.MELEE_WHETSTONE, values -> {
                values.put("xpGain", 1.6D);
                values.put("durability", 0.5D);
                values.put("damage", 0.85D);
            });

            // ── 拔刀剑 · 刀装 ──
            defineSpec(builder, Specialization.BLADE_POLISH, values -> {
                values.put("directDamage", 1.5D);
                values.put("summonedDamage", 0.55D);
            });
            defineSpec(builder, Specialization.BLADE_MEITO, values -> {
                values.put("saDamage", 1.5D);
                values.put("durability", 0.5D);
                values.put("summonedDamage", 0.8D);
            });
            defineSpec(builder, Specialization.BLADE_ZANTETSU, values -> {
                values.put("armoredDamage", 1.8D);
                values.put("knockback", 1.3D);
                values.put("unarmoredDamage", 0.8D);
            });
            defineSpec(builder, Specialization.BLADE_RAIKIRI, values -> {
                values.put("slowChance", 0.35D);
                values.put("saDamage", 1.1D);
                values.put("summonedDamage", 0.85D);
            });
            // ── 拔刀剑 · 幻影 ──
            defineSpec(builder, Specialization.BLADE_PHANTOM, values -> {
                values.put("summonedDamage", 1.9D);
                values.put("moveSpeed", 1.05D);
                values.put("directDamage", 0.6D);
            });
            defineSpec(builder, Specialization.BLADE_ONIKOKU, values -> {
                values.put("summonedDamage", 1.4D);
                values.put("soulGain", 1.5D);
                values.put("directDamage", 0.8D);
            });
            // ── 拔刀剑 · 剑技 ──
            defineSpec(builder, Specialization.BLADE_IAI, values -> {
                values.put("freshDamage", 1.7D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.BLADE_MIKIRI, values -> {
                values.put("critChance", 0.25D);
                values.put("critDamage", 1.6D);
                values.put("damageTaken", 0.92D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.BLADE_COMBO, values -> {
                values.put("comboStep", 0.07D);
                values.put("comboMax", 7.0D);
                values.put("saDamage", 1.1D);
                values.put("damage", 0.82D);
            });
            defineSpec(builder, Specialization.BLADE_MUSOU, values -> {
                values.put("vsMobDamage", 1.25D);
                values.put("knockback", 1.2D);
                values.put("moveSpeed", 1.05D);
                values.put("vsPlayerDamage", 0.8D);
            });
            // ── 拔刀剑 · 心法 ──
            defineSpec(builder, Specialization.BLADE_HAISUI, values -> {
                values.put("desperationDamage", 2.2D);
                values.put("desperationThreshold", 0.5D);
                values.put("desperationFloor", 0.3D);
                values.put("lifeSteal", 0.06D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.BLADE_SHINKEN, values -> {
                values.put("vsPlayerDamage", 1.5D);
                values.put("durability", 0.75D);
                values.put("vsMobDamage", 0.8D);
            });
            defineSpec(builder, Specialization.BLADE_SHUGYO, values -> {
                values.put("xpGain", 1.6D);
                values.put("soulGain", 1.3D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.BLADE_SHINSOKU, values -> {
                values.put("moveSpeed", 1.15D);
                values.put("damageTaken", 0.92D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.BLADE_SOUL_EATER, values -> {
                values.put("lifeSteal", 0.14D);
                values.put("soulGain", 1.2D);
                values.put("damage", 0.82D);
            });
            defineSpec(builder, Specialization.BLADE_SHURA, values -> {
                values.put("frenzyStep", 0.1D);
                values.put("frenzyMax", 5.0D);
                values.put("damageTaken", 1.15D);
            });

            // ── 铁魔法 · 媒介 ──
            defineSpec(builder, Specialization.SPELL_FOCUS_CRYSTAL, values -> {
                values.put("damage", 1.35D);
                values.put("critChance", 0.1D);
                values.put("critDamage", 1.5D);
                values.put("manaCost", 1.3D);
            });
            defineSpec(builder, Specialization.SPELL_ECHO_CRYSTAL, values -> {
                values.put("doubleCast", 1.0D);
                values.put("damage", 0.75D);
                values.put("manaCost", 1.5D);
                values.put("spellCooldown", 1.5D);
            });
            // ── 铁魔法 · 墨水 ──
            defineSpec(builder, Specialization.SPELL_THRIFTY_INK, values -> {
                values.put("manaCost", 0.65D);
                values.put("damage", 0.9D);
                values.put("spellCooldown", 1.05D);
            });
            defineSpec(builder, Specialization.SPELL_SEARING_INK, values -> {
                values.put("freshDamage", 1.45D);
                values.put("damage", 1.08D);
                values.put("manaCost", 1.15D);
            });
            // ── 铁魔法 · 符文 ──
            defineSpec(builder, Specialization.SPELL_COOLDOWN_RUNE, values -> {
                values.put("spellCooldown", 0.72D);
                values.put("damage", 0.88D);
            });
            defineSpec(builder, Specialization.SPELL_SHORTHAND_RUNE, values -> {
                values.put("castTime", 0.65D);
                values.put("manaCost", 0.92D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.SPELL_DOUBLE_BINDING, values -> {
                // 冷却周转收益，不增加法术实体数量或法术等级
                values.put("spellCooldown", 0.55D);
                values.put("manaCost", 1.25D);
                values.put("castTime", 1.15D);
            });
            // ── 铁魔法 · 章节 ──
            defineSpec(builder, Specialization.SPELL_HUNTER_CHAPTER, values -> {
                values.put("vsMobDamage", 1.3D);
                values.put("xpGain", 1.15D);
                values.put("vsPlayerDamage", 0.8D);
            });
            defineSpec(builder, Specialization.SPELL_DUEL_CHAPTER, values -> {
                values.put("vsPlayerDamage", 1.4D);
                values.put("castTime", 0.9D);
                values.put("vsMobDamage", 0.85D);
            });
            defineSpec(builder, Specialization.SPELL_STUDY_NOTES, values -> {
                values.put("xpGain", 1.6D);
                values.put("spellCooldown", 0.95D);
                values.put("damage", 0.88D);
            });
            defineSpec(builder, Specialization.SPELL_CHAIN_BOOKMARK, values -> {
                values.put("comboStep", 0.06D);
                values.put("comboMax", 8.0D);
                values.put("castTime", 0.92D);
                values.put("damage", 0.82D);
            });
            defineSpec(builder, Specialization.SPELL_FAR_CHAPTER, values -> {
                values.put("farDamage", 1.35D);
                values.put("nearDamage", 0.8D);
                values.put("castTime", 1.1D);
            });
            // ── 铁魔法 · 咒印 ──
            defineSpec(builder, Specialization.SPELL_SOUL_SIGIL, values -> {
                values.put("lifeSteal", 0.12D);
                values.put("damage", 0.85D);
                values.put("manaCost", 1.1D);
            });
            defineSpec(builder, Specialization.SPELL_EXECUTION_SEAL, values -> {
                values.put("executeDamage", 2.0D);
                values.put("executeThreshold", 0.3D);
                values.put("manaCost", 0.9D);
                values.put("freshDamage", 0.8D);
            });
            defineSpec(builder, Specialization.SPELL_SUNDER_SEAL, values -> {
                values.put("armoredDamage", 1.7D);
                values.put("unarmoredDamage", 0.85D);
                values.put("castTime", 1.08D);
            });
            defineSpec(builder, Specialization.SPELL_ARCANE_SEAL, values -> {
                // 血契：以血换力。不走法术等级，避免间接扩大范围
                values.put("desperationDamage", 2.0D);
                values.put("desperationThreshold", 0.5D);
                values.put("desperationFloor", 0.25D);
                values.put("manaCost", 0.85D);
                values.put("damage", 0.88D);
            });
            defineSpec(builder, Specialization.SPELL_SOUL_HARVEST, values -> {
                values.put("killHeal", 0.08D);
                values.put("frenzyStep", 0.06D);
                values.put("frenzyMax", 5.0D);
                values.put("manaCost", 1.15D);
            });

            // ── 新生魔艺 · 催化 ──
            defineSpec(builder, Specialization.GLYPH_SOURCE_GEM, values -> {
                values.put("damage", 1.4D);
                values.put("bulletSpeed", 1.1D);
                values.put("manaCost", 1.35D);
            });
            defineSpec(builder, Specialization.GLYPH_SEARING_INK, values -> {
                values.put("critChance", 0.18D);
                values.put("critDamage", 1.6D);
                values.put("manaCost", 1.2D);
            });
            // ── 新生魔艺 · 书写 ──
            defineSpec(builder, Specialization.GLYPH_THRIFTY_QUILL, values -> {
                values.put("manaCost", 0.6D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.GLYPH_STUDY_NOTES, values -> {
                values.put("xpGain", 1.6D);
                values.put("manaCost", 0.9D);
                values.put("damage", 0.85D);
            });
            // ── 新生魔艺 · 书带 ──
            defineSpec(builder, Specialization.GLYPH_SOUL_RIBBON, values -> {
                values.put("lifeSteal", 0.14D);
                values.put("damage", 0.9D);
                values.put("bulletSpeed", 0.85D);
            });
            defineSpec(builder, Specialization.GLYPH_CHAIN_RUNE, values -> {
                values.put("comboStep", 0.05D);
                values.put("comboMax", 8.0D);
                values.put("manaCost", 0.95D);
                values.put("damage", 0.82D);
            });
            // ── 新生魔艺 · 书页 ──
            defineSpec(builder, Specialization.GLYPH_HUNTER_PAGE, values -> {
                values.put("vsMobDamage", 1.3D);
                values.put("manaCost", 0.9D);
                values.put("vsPlayerDamage", 0.8D);
            });
            defineSpec(builder, Specialization.GLYPH_DUEL_PAGE, values -> {
                values.put("vsPlayerDamage", 1.4D);
                values.put("bulletSpeed", 1.1D);
                values.put("vsMobDamage", 0.85D);
            });
            defineSpec(builder, Specialization.GLYPH_FAR_PAGE, values -> {
                values.put("farDamage", 1.35D);
                values.put("bulletSpeed", 1.1D);
                values.put("nearDamage", 0.8D);
            });
            // ── 新生魔艺 · 刻印 ──
            defineSpec(builder, Specialization.GLYPH_EXECUTE_RUNE, values -> {
                values.put("executeDamage", 1.9D);
                values.put("executeThreshold", 0.35D);
                values.put("bulletSpeed", 1.15D);
                values.put("freshDamage", 0.8D);
            });
            defineSpec(builder, Specialization.GLYPH_SUNDER_RUNE, values -> {
                values.put("armoredDamage", 1.7D);
                values.put("pierce", 1.0D);
                values.put("unarmoredDamage", 0.85D);
            });
            defineSpec(builder, Specialization.GLYPH_SOURCE_SURGE, values -> {
                values.put("frenzyStep", 0.08D);
                values.put("frenzyMax", 4.0D);
                values.put("bulletSpeed", 1.1D);
                values.put("damage", 0.92D);
            });
            // ── 新生魔艺 · 形态 ──
            defineSpec(builder, Specialization.GLYPH_SWIFT_FORM, values -> {
                values.put("bulletSpeed", 1.45D);
                values.put("damage", 0.92D);
            });
            defineSpec(builder, Specialization.GLYPH_PIERCING_FORM, values -> {
                values.put("pierce", 2.0D);
                values.put("damage", 0.88D);
                values.put("manaCost", 1.1D);
            });

            // ── 原版剑类 · 剑身 ──
            defineSpec(builder, Specialization.SWORD_TEMPERED_EDGE, values -> {
                values.put("damage", 1.35D);
                values.put("knockback", 1.4D);
                values.put("reach", 1.05D);
                values.put("attackInterval", 1.25D);
            });
            defineSpec(builder, Specialization.SWORD_LIGHT_BLADE, values -> {
                values.put("attackInterval", 0.75D);
                values.put("moveSpeed", 1.08D);
                values.put("damage", 0.78D);
                values.put("reach", 0.9D);
            });
            defineSpec(builder, Specialization.SWORD_FINE_STEEL, values -> {
                values.put("reach", 1.2D);
                values.put("durability", 0.7D);
                values.put("attackInterval", 1.15D);
            });
            // ── 原版剑类 · 剑刃 ──
            defineSpec(builder, Specialization.SWORD_SERRATED, values -> {
                values.put("executeDamage", 2.0D);
                values.put("executeThreshold", 0.3D);
                values.put("knockback", 1.2D);
                values.put("freshDamage", 0.8D);
            });
            defineSpec(builder, Specialization.SWORD_ARMOR_PIERCER, values -> {
                values.put("armoredDamage", 1.75D);
                values.put("reach", 1.05D);
                values.put("unarmoredDamage", 0.8D);
            });
            defineSpec(builder, Specialization.SWORD_KEEN_EDGE, values -> {
                values.put("comboStep", 0.06D);
                values.put("comboMax", 8.0D);
                values.put("reach", 1.05D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.SWORD_FULLER, values -> {
                values.put("lifeSteal", 0.12D);
                values.put("attackInterval", 0.95D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.SWORD_RAPIER_POINT, values -> {
                values.put("critChance", 0.2D);
                values.put("critDamage", 1.5D);
                values.put("reach", 1.1D);
                values.put("damage", 0.88D);
            });
            defineSpec(builder, Specialization.SWORD_FLAME_EDGE, values -> {
                values.put("igniteChance", 0.4D);
                values.put("damage", 1.05D);
                values.put("durability", 1.3D);
            });
            // ── 原版剑类 · 剑柄 ──
            defineSpec(builder, Specialization.SWORD_BALANCED_POMMEL, values -> {
                values.put("attackInterval", 0.85D);
                values.put("damage", 1.05D);
                values.put("knockback", 1.1D);
                values.put("durability", 1.4D);
            });
            defineSpec(builder, Specialization.SWORD_QUICK_SCABBARD, values -> {
                values.put("moveSpeed", 1.12D);
                values.put("freshDamage", 1.3D);
                values.put("damage", 0.9D);
            });
            // ── 原版剑类 · 剑术 ──
            defineSpec(builder, Specialization.SWORD_DUELIST, values -> {
                values.put("vsPlayerDamage", 1.4D);
                values.put("damageTaken", 0.9D);
                values.put("vsMobDamage", 0.85D);
            });
            defineSpec(builder, Specialization.SWORD_HUNTING_SWORD, values -> {
                values.put("vsMobDamage", 1.3D);
                values.put("knockback", 1.3D);
                values.put("vsPlayerDamage", 0.8D);
            });
            defineSpec(builder, Specialization.SWORD_BLEEDER, values -> {
                values.put("damageTaken", 0.8D);
                values.put("knockback", 1.25D);
                values.put("damage", 0.85D);
                values.put("moveSpeed", 0.95D);
            });
            defineSpec(builder, Specialization.SWORD_WHETSTONE, values -> {
                values.put("xpGain", 1.7D);
                values.put("attackInterval", 0.95D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.SWORD_TRIUMPH, values -> {
                values.put("killHeal", 0.12D);
                values.put("frenzyStep", 0.05D);
                values.put("frenzyMax", 3.0D);
                values.put("attackInterval", 1.1D);
            });

            // ── 弓弩 · 弓臂 ──
            defineSpec(builder, Specialization.BOW_HEAVY_LIMBS, values -> {
                values.put("damage", 1.3D);
                values.put("knockback", 1.5D);
                values.put("drawSpeed", 0.85D);
                values.put("moveSpeed", 0.93D);
            });
            defineSpec(builder, Specialization.BOW_RECURVE_LIMBS, values -> {
                values.put("drawSpeed", 1.3D);
                values.put("bulletSpeed", 1.1D);
                values.put("damage", 0.88D);
            });
            // ── 弓弩 · 弓弦 ──
            defineSpec(builder, Specialization.BOW_FINE_STRING, values -> {
                values.put("headShotDamage", 1.8D);
                values.put("bulletSpeed", 1.1D);
                values.put("bodyShotDamage", 0.85D);
            });
            defineSpec(builder, Specialization.BOW_BOWSTRING_WAX, values -> {
                values.put("xpGain", 1.5D);
                values.put("drawSpeed", 1.05D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.BOW_EAGLE_EYE, values -> {
                values.put("farDamage", 1.45D);
                values.put("headShotDamage", 1.1D);
                values.put("nearDamage", 0.75D);
            });
            // ── 弓弩 · 箭头 ──
            defineSpec(builder, Specialization.BOW_BARBED_HEADS, values -> {
                values.put("executeDamage", 1.9D);
                values.put("executeThreshold", 0.4D);
                values.put("freshDamage", 0.85D);
                values.put("drawSpeed", 0.95D);
            });
            defineSpec(builder, Specialization.BOW_BODKIN_HEADS, values -> {
                values.put("armoredDamage", 1.8D);
                values.put("unarmoredDamage", 0.8D);
                values.put("drawSpeed", 0.92D);
            });
            defineSpec(builder, Specialization.BOW_BROADHEAD_HEADS, values -> {
                values.put("unarmoredDamage", 1.35D);
                values.put("critChance", 0.15D);
                values.put("critDamage", 1.5D);
                values.put("armoredDamage", 0.75D);
            });
            defineSpec(builder, Specialization.BOW_FIRE_ARROWS, values -> {
                values.put("igniteChance", 0.5D);
                values.put("damage", 0.9D);
            });
            // ── 弓弩 · 箭杆 ──
            defineSpec(builder, Specialization.BOW_LIGHT_SHAFTS, values -> {
                values.put("bulletSpeed", 1.3D);
                values.put("drawSpeed", 1.15D);
                values.put("moveSpeed", 1.05D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.BOW_RAPID_SHAFTS, values -> {
                values.put("comboStep", 0.05D);
                values.put("comboMax", 8.0D);
                values.put("drawSpeed", 1.1D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.BOW_PIERCE_SHAFTS, values -> {
                values.put("pierce", 2.0D);
                values.put("bulletSpeed", 1.1D);
                values.put("damage", 0.88D);
                values.put("drawSpeed", 0.9D);
            });
            defineSpec(builder, Specialization.BOW_FROST_SHAFTS, values -> {
                values.put("slowChance", 0.45D);
                values.put("bulletSpeed", 0.9D);
                values.put("damage", 0.95D);
            });
            // ── 弓弩 · 箭囊 ──
            defineSpec(builder, Specialization.BOW_HUNTER_QUIVER, values -> {
                values.put("vsMobDamage", 1.3D);
                values.put("ammoSave", 0.15D);
                values.put("vsPlayerDamage", 0.8D);
            });
            defineSpec(builder, Specialization.BOW_WAR_QUIVER, values -> {
                values.put("vsPlayerDamage", 1.4D);
                values.put("drawSpeed", 1.1D);
                values.put("vsMobDamage", 0.85D);
            });
            defineSpec(builder, Specialization.BOW_RECOVERY_QUIVER, values -> {
                values.put("ammoSave", 0.4D);
                values.put("damage", 0.93D);
            });

            // ── 匠魂 · 锻头 ──
            defineSpec(builder, Specialization.TINKER_HEAVY_HEAD, values -> {
                values.put("damage", 1.35D);
                values.put("knockback", 1.35D);
                values.put("attackInterval", 1.22D);
            });
            defineSpec(builder, Specialization.TINKER_KEEN_HEAD, values -> {
                values.put("critChance", 0.2D);
                values.put("critDamage", 1.5D);
                values.put("damage", 0.88D);
            });
            defineSpec(builder, Specialization.TINKER_BROAD_HEAD, values -> {
                values.put("reach", 1.18D);
                values.put("damage", 1.05D);
                values.put("attackInterval", 1.12D);
            });
            // ── 匠魂 · 手柄 ──
            defineSpec(builder, Specialization.TINKER_LIGHT_HANDLE, values -> {
                values.put("attackInterval", 0.75D);
                values.put("moveSpeed", 1.08D);
                values.put("damage", 0.8D);
            });
            defineSpec(builder, Specialization.TINKER_LONG_HANDLE, values -> {
                values.put("reach", 1.22D);
                values.put("knockback", 1.15D);
                values.put("attackInterval", 1.1D);
            });
            defineSpec(builder, Specialization.TINKER_GRIPPED_HANDLE, values -> {
                values.put("comboStep", 0.06D);
                values.put("comboMax", 8.0D);
                values.put("damage", 0.88D);
            });
            // ── 匠魂 · 镶嵌 ──
            defineSpec(builder, Specialization.TINKER_SOUL_BINDING, values -> {
                values.put("lifeSteal", 0.12D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.TINKER_STURDY_BINDING, values -> {
                values.put("durability", 0.55D);
                values.put("damageTaken", 0.95D);
                values.put("attackInterval", 1.05D);
            });
            defineSpec(builder, Specialization.TINKER_EMBER_BINDING, values -> {
                values.put("igniteChance", 0.4D);
                values.put("damage", 1.05D);
                values.put("durability", 1.3D);
            });
            // ── 匠魂 · 锻打 ──
            defineSpec(builder, Specialization.TINKER_ARMOR_BREAK, values -> {
                values.put("armoredDamage", 1.75D);
                values.put("unarmoredDamage", 0.82D);
            });
            defineSpec(builder, Specialization.TINKER_EXECUTION, values -> {
                values.put("executeDamage", 2.0D);
                values.put("executeThreshold", 0.3D);
                values.put("freshDamage", 0.82D);
            });
            defineSpec(builder, Specialization.TINKER_HUNT, values -> {
                values.put("vsMobDamage", 1.2D);
                values.put("vsPlayerDamage", 0.85D);
                values.put("xpGain", 1.1D);
            });
            defineSpec(builder, Specialization.TINKER_FRENZY, values -> {
                values.put("frenzyStep", 0.07D);
                values.put("frenzyMax", 6.0D);
                values.put("damageTaken", 1.08D);
            });

            // ── 甜蜜魔法 · 杖芯 ──
            defineSpec(builder, Specialization.SM_DENSE_CORE, values -> {
                values.put("damage", 1.3D);
                values.put("manaCost", 1.25D);
            });
            defineSpec(builder, Specialization.SM_EFFICIENT_CORE, values -> {
                values.put("manaCost", 0.7D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.SM_RESONANT_CORE, values -> {
                values.put("critChance", 0.18D);
                values.put("critDamage", 1.5D);
                values.put("manaCost", 1.1D);
            });
            // ── 甜蜜魔法 · 术式 ──
            defineSpec(builder, Specialization.SM_RAPID_FORMULA, values -> {
                values.put("spellCooldown", 0.7D);
                values.put("damage", 0.88D);
            });
            defineSpec(builder, Specialization.SM_HEAVY_FORMULA, values -> {
                values.put("damage", 1.35D);
                values.put("spellCooldown", 1.3D);
            });
            defineSpec(builder, Specialization.SM_STEADY_FORMULA, values -> {
                values.put("damage", 1.1D);
                values.put("manaCost", 0.92D);
                values.put("spellCooldown", 1.1D);
            });
            // ── 甜蜜魔法 · 媒介 ──
            defineSpec(builder, Specialization.SM_SIPHON_MEDIUM, values -> {
                values.put("lifeSteal", 0.1D);
                values.put("manaCost", 1.1D);
            });
            defineSpec(builder, Specialization.SM_EMBER_MEDIUM, values -> {
                values.put("igniteChance", 0.4D);
                values.put("damage", 1.05D);
            });
            defineSpec(builder, Specialization.SM_FROST_MEDIUM, values -> {
                values.put("slowChance", 0.4D);
                values.put("damage", 1.03D);
            });
            defineSpec(builder, Specialization.SM_CURSE_MEDIUM, values -> {
                values.put("weakenChance", 0.35D);
                values.put("damage", 1.05D);
            });
            // ── 甜蜜魔法 · 修习 ──
            defineSpec(builder, Specialization.SM_HUNT, values -> {
                values.put("vsMobDamage", 1.2D);
                values.put("vsPlayerDamage", 0.85D);
                values.put("xpGain", 1.1D);
            });
            defineSpec(builder, Specialization.SM_DUEL, values -> {
                values.put("vsPlayerDamage", 1.25D);
                values.put("vsMobDamage", 0.9D);
            });
            defineSpec(builder, Specialization.SM_PIERCE, values -> {
                values.put("armoredDamage", 1.6D);
                values.put("unarmoredDamage", 0.85D);
            });
            defineSpec(builder, Specialization.SM_SCHOLAR, values -> {
                values.put("xpGain", 1.35D);
                values.put("damage", 0.9D);
            });

            // ── 铁枪 · 火药 ──
            defineSpec(builder, Specialization.IA_HEAVY_CHARGE, values -> {
                values.put("damage", 1.35D);
                values.put("blowback", 0.3D);
                values.put("recoil", 1.25D);
                values.put("reloadTime", 1.15D);
            });
            defineSpec(builder, Specialization.IA_VOLATILE_POWDER, values -> {
                values.put("effectDamage", 1.8D);
                values.put("igniteChance", 0.15D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.IA_QUICK_POWDER, values -> {
                values.put("fireRate", 1.3D);
                values.put("reloadTime", 0.9D);
                values.put("damage", 0.88D);
                values.put("bulletSpeed", 0.9D);
            });
            // ── 铁枪 · 弹丸 ──
            defineSpec(builder, Specialization.IA_SKIPPING_BALL, values -> {
                values.put("ricochet", 2.0D);
                values.put("ricochetDamage", 1.5D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.IA_HOMING_BALL, values -> {
                values.put("seeking", 0.12D);
                values.put("bulletSpeed", 0.75D);
                values.put("damage", 0.92D);
            });
            defineSpec(builder, Specialization.IA_HARDENED_BALL, values -> {
                values.put("pierce", 1.0D);
                values.put("armoredDamage", 1.35D);
                values.put("unarmoredDamage", 0.85D);
                values.put("bulletSpeed", 1.1D);
            });
            // ── 铁枪 · 枪机 / 枪托 ──
            defineSpec(builder, Specialization.IA_FANNING_HAMMER, values -> {
                values.put("fireRate", 1.4D);
                values.put("hipSpread", 1.3D);
                values.put("damage", 0.92D);
            });
            defineSpec(builder, Specialization.IA_WHEELLOCK, values -> {
                values.put("recoil", 0.65D);
                values.put("hipSpread", 0.8D);
                values.put("fireRate", 0.88D);
            });
            defineSpec(builder, Specialization.IA_PADDED_STOCK, values -> {
                values.put("blowback", -0.5D);
                values.put("recoil", 0.75D);
                values.put("damage", 0.92D);
            });
            // ── 铁枪 · 装填 ──
            defineSpec(builder, Specialization.IA_PAPER_CARTRIDGE, values -> {
                values.put("reloadTime", 0.7D);
                values.put("damage", 0.85D);
                values.put("hipSpread", 1.1D);
            });
            defineSpec(builder, Specialization.IA_TAMPED_CHARGE, values -> {
                values.put("firstShotDamage", 1.5D);
                values.put("reloadTime", 1.2D);
            });
            defineSpec(builder, Specialization.IA_THRIFTY_LOADING, values -> {
                values.put("ammoSave", 0.28D);
                values.put("damage", 0.88D);
                values.put("bulletSpeed", 0.9D);
            });
            // ── 铁枪 · 枪术 ──
            defineSpec(builder, Specialization.IA_QUICKSHOT, values -> {
                values.put("nearDamage", 1.35D);
                values.put("fireRate", 1.15D);
                values.put("farDamage", 0.75D);
            });
            defineSpec(builder, Specialization.IA_LINE_VOLLEY, values -> {
                values.put("farDamage", 1.35D);
                values.put("hipSpread", 0.8D);
                values.put("moveSpeed", 0.92D);
            });
            defineSpec(builder, Specialization.IA_BOUNTY_HUNTER, values -> {
                values.put("killRefill", 0.5D);
                values.put("frenzyStep", 0.06D);
                values.put("frenzyMax", 4.0D);
                values.put("damageTaken", 1.1D);
            });

            builder.pop();

            builder.comment("全局专精排行榜：从专精界面左下角进入，可查看全服专精排名，点击玩家可查看其罗盘和所选特化。",
                            "榜单数据存在主世界 data/albionmastery_leaderboard.dat 中，离线玩家也在榜上。",
                            "单人世界同样可用，开放局域网时即为一份小榜单。")
                    .push("leaderboard");
            this.leaderboardEnable = builder
                    .comment("总开关。关闭后客户端请求一律返回「未开启」，已保存的榜单数据不删除")
                    .define("enable", true);
            this.leaderboardTopCount = builder
                    .comment("榜单最多显示的名次数。请求者不在其中时也能看到自己的名次和数据")
                    .defineInRange("topCount", 100, 5, 500);
            this.leaderboardUpdateIntervalSeconds = builder
                    .comment("在线玩家榜单数据的刷新间隔（秒）。玩家登录、登出或打开榜单时会另外立即刷新，",
                            "因此此值只影响其他玩家看到的数据延迟，调大可降低开销")
                    .defineInRange("updateIntervalSeconds", 30, 5, 600);
            this.leaderboardRetentionDays = builder
                    .comment("离线超过此天数的玩家从榜单中移除。0 = 永不清理。",
                            "榜单会将每名玩家的完整专精数据保存在主世界存档中，只增不减，",
                            "长期运行的服务器建议设置此值。在线玩家不会被移除")
                    .defineInRange("retentionDays", 90, 0, 3650);
            this.leaderboardRequestCooldownSeconds = builder
                    .comment("同一玩家两次请求榜单的最小间隔（秒），榜单与玩家详情分别计时。0 = 不限")
                    .defineInRange("requestCooldownSeconds", 2, 0, 60);
            this.leaderboardBroadcastTopChanges = builder
                    .comment("总榜前三名易主时向所有在线玩家显示与升级提示相同的横幅（本人看到「你成为…」，",
                            "其他玩家看到「xxx 取代 yyy，成为…」），不发聊天消息。只统计总榜，日、周、月榜的变动不通知")
                    .define("broadcastTopChanges", true);
            this.leaderboardBroadcastSort = builder
                    .comment("广播按哪种排序计算前三：MAXED 满级数，LEVEL_SUM 等级总和，UNLOCKED 已解锁数，XP 经验总和，",
                            "RANK 段位（与 LEVEL_SUM 同序，段位经验即等级总和乘每级段位经验）")
                    .defineEnum("broadcastSort", LeaderboardSort.MAXED);
            builder.pop();

            builder.comment("段位：账号级的总进度，规则沿用星际战甲的精通段位。",
                            "段位经验只来自外圈（单件武器）：每件武器每升一级获得固定段位经验，类型和总体两圈不计。",
                            "门槛与星际战甲一致：1～30 段累计 2500 × 段位²（30 段至崇宗师 = 2,250,000），",
                            "之后每 147,500 升一个传奇段位。段位按武器等级实时计算：调低等级上限或调高经验曲线（xpBase / xpExponent）时段位随之下降；",
                            "反之段位上升，重载配置后可能一次触发多段晋升")
                    .push("rank");
            this.rankXpPerWeaponLevel = builder
                    .comment("每件武器每升一级的段位经验。星际战甲为武器 100、战甲 200。",
                            "按默认等级上限 100，一件满级武器提供 10,000，225 件满级武器达到至崇宗师")
                    .defineInRange("xpPerWeaponLevel", 100, 1, 100000);
            this.rankUpBroadcast = builder
                    .comment("晋升段位时在聊天栏通告全服。关闭后只有本人收到一行记录，屏幕上方的晋升动画不受影响")
                    .define("broadcastRankUp", true);
            builder.pop();

            builder.pop();
        }
    }


    /**
     * 定义一个特化用到的数值项。
     *
     * <p>只暴露这个特化真正会用的项：把二十几个维度全列出来、其中二十个写着 1.0，
     * 服主打开配置文件只会一脸茫然。
     *
     * @param builder  配置构建器
     * @param spec     特化
     * @param defaults 往里面塞「项名 -&gt; 默认值」
     */
    private static void defineSpec(ForgeConfigSpec.Builder builder, Specialization spec,
                                   Consumer<Map<String, Double>> defaults) {
        Map<String, Double> wanted = new LinkedHashMap<>();
        defaults.accept(wanted);

        builder.push(spec.configSection());
        Map<String, ForgeConfigSpec.DoubleValue> values = new HashMap<>();
        for (Map.Entry<String, Double> entry : wanted.entrySet()) {
            String key = entry.getKey();
            // 上下界和网络包的夹取用同一张表（SpecializationValues.minOf / maxOf），
            // 两边不会各写一套然后对不上
            values.put(key, builder
                    .comment(SPEC_VALUE_COMMENTS.getOrDefault(key, key))
                    .defineInRange(key, entry.getValue(),
                            SpecializationValues.minOf(key), SpecializationValues.maxOf(key)));
        }
        builder.pop();
        SPEC_ENTRIES.put(spec, new SpecEntry(values));
    }

    /**
     * 取某个特化在配置文件里的数值项，管理指令改数值时用。
     *
     * @param spec 特化
     * @return 数值项；制式之类没有数值的特化返回 null
     */
    @javax.annotation.Nullable
    public static SpecEntry specEntry(Specialization spec) {
        return SPEC_ENTRIES.get(spec);
    }

    /**
     * 一个特化在配置文件里的数值项。
     *
     * <p>用「项名 -&gt; 配置值」的表而不是固定字段：不同来源的特化关心的维度完全不同，
     * 硬写成一个大 record 会留下一堆用不上的 null。
     *
     * <p>存的是 {@code ConfigValue} 而不是数字：配置在模组构造阶段还没加载完，
     * 这时候 {@code get()} 会抛异常，真正取值统一在 {@link #refreshServerSnapshot()} 里做。
     *
     * @param values 项名 -&gt; 配置值
     */
    public record SpecEntry(Map<String, ForgeConfigSpec.DoubleValue> values) {

        /**
         * 取一项数值。
         *
         * @param key      项名
         * @param fallback 这个特化没定义该项时的取值
         * @return 数值
         */
        public double get(String key, double fallback) {
            ForgeConfigSpec.DoubleValue value = this.values.get(key);
            return value == null ? fallback : value.get();
        }
    }

    /**
     * CLIENT 配置项（纯表现）。
     */
    public static final class Client {

        public final ForgeConfigSpec.BooleanValue renderItemIcons;
        public final ForgeConfigSpec.DoubleValue defaultZoom;
        public final ForgeConfigSpec.BooleanValue showMasteryTooltip;
        public final ForgeConfigSpec.BooleanValue equalSourceWedges;
        public final ForgeConfigSpec.BooleanValue detailedTooltip;
        public final ForgeConfigSpec.BooleanValue showLevelUpToast;
        public final ForgeConfigSpec.BooleanValue levelUpSound;
        public final ForgeConfigSpec.BooleanValue overrideDamageDisplay;
        public final ForgeConfigSpec.BooleanValue rankUpAnimation;
        public final ForgeConfigSpec.BooleanValue rankUpSound;
        public final ForgeConfigSpec.EnumValue<SpecScreenLayout> specScreenLayout;
        public final ForgeConfigSpec.BooleanValue specGuideBar;
        public final ForgeConfigSpec.IntValue specGuideVersion;
        public final ForgeConfigSpec.ConfigValue<String> screenScale;

        Client(ForgeConfigSpec.Builder builder) {
            builder.comment("武器专精系统 · 客户端表现")
                    .push("client");

            this.renderItemIcons = builder
                    .comment("专精界面节点是否渲染真实物品图标。",
                            "TACZ 枪械使用自定义 3D 渲染器，同屏节点较多时开销明显；",
                            "关闭后会退化为纯色节点 + 文字，帧数更稳。")
                    .define("renderItemIcons", true);

            this.defaultZoom = builder
                    .comment("专精界面打开时的缩放倍数，相对于整张图恰好占满屏幕时的自动值。",
                            "1.0 = 打开时显示全貌；调大则打开时更靠近中心。")
                    .defineInRange("defaultZoom", 1.0D, 0.3D, 4.0D);

            this.showMasteryTooltip = builder
                    .comment("是否在武器物品的提示文本里追加一行专精摘要（等级与总加成）")
                    .define("showMasteryTooltip", true);

            this.equalSourceWedges = builder
                    .comment("专精罗盘的来源扇形是否平分整圈：false = 按武器数量比例分配，武器多的来源角度更大（默认）；",
                            "true = 每个来源角度相同，武器多的来源在扇形内排列更密")
                    .define("equalSourceWedges", false);

            this.detailedTooltip = builder
                    .comment("提示文本中是否展开加成来源（类型 / 本武器 / 同类联动），默认只显示一行摘要")
                    .define("detailedTooltip", false);

            this.showLevelUpToast = builder
                    .comment("升级时在屏幕正上方弹出一条小提示")
                    .define("showLevelUpToast", true);

            this.levelUpSound = builder
                    .comment("升级时播放轻微的提示音（音量 0.3、音调 1.7）")
                    .define("levelUpSound", true);

            this.overrideDamageDisplay = builder
                    .comment("是否将 TACZ 枪械提示中的伤害替换为加成后的最终数值。",
                            "关闭后提示中显示未计专精加成的原始伤害。")
                    .define("overrideDamageDisplay", true);

            this.rankUpAnimation = builder
                    .comment("晋升段位时在屏幕正上方播放晋升动画，关闭后只保留聊天栏的记录")
                    .define("rankUpAnimation", true);

            this.rankUpSound = builder
                    .comment("晋升动画是否带音效")
                    .define("rankUpSound", true);

            this.specScreenLayout = builder
                    .comment("特化页布局：A = 左栏列出槽位，卡片两列；B = 槽位横排在顶部，卡片三列；",
                            "C = 列表 + 详情，右栏为所选特化的各级数值表。也可用特化页顶栏的按钮切换，切换结果写回此处。",
                            "窗口过窄、无法显示 C 的三栏时按 B 显示，此处保存的值不变。")
                    .defineEnum("specScreenLayout", SpecScreenLayout.B);

            this.specGuideBar = builder
                    .comment("特化页槽位下方的引导条：按选中的槽列出操作步骤，并提示下一步的位置。",
                            "点击引导条右侧的「收起」会将此项改为 false；通过顶栏的「?」重看引导时会重新开启。")
                    .define("specGuideBar", true);

            this.specGuideVersion = builder
                    .comment("已读的特化页上手引导版本。小于当前版本时，第一次打开特化页会从头显示引导；设为 0 可重新查看。")
                    .defineInRange("specGuideVersion", 0, 0, 1000);

            // 校验写在这里而不是调客户端的 ScreenScale：配置类两端都加载，专用服上不能碰客户端类
            this.screenScale = builder
                    .comment("专精界面（罗盘、特化页、段位之路、排行榜）独立的界面缩放，只取整数倍，不影响其他界面。",
                            "auto = 按显示器尺寸自动选择一档（不超过原版界面尺寸）；auto+1 / auto-1 = 在自动档基础上大 / 小一档；",
                            "填 1～8 则固定使用该档。也可用界面左下角的放大镜按钮调整，调整结果写回此处。")
                    .define("screenScale", "auto",
                            v -> v instanceof String text && text.trim().matches("auto(?:[+-][1-8])?|[1-8]"));

            builder.pop();
        }
    }

    /**
     * 玩法数值快照。不可变，可写入网络包下发给客户端。
     */
    public static final class Snapshot {

        public final int maxLevel;
        public final double overallDamagePerLevel;
        public final double typeDamagePerLevel;
        public final double weaponDamagePerLevel;
        public final double weaponCrossDamagePerLevel;
        public final double maxTotalDamageBonus;
        /** 每个武器来源各自的专精伤害倍率：来源 ID → 倍率。表里没有的按 1.0 算。 */
        public final Map<String, Double> sourceMasteryMultipliers;
        /** 法术专精折算成法力折扣的比例。 */
        public final double spellMasteryManaShare;
        /** 法术专精折算成冷却折扣的比例。 */
        public final double spellMasteryCooldownShare;
        /** 法术专精折扣的上限。 */
        public final double spellMasteryReductionCap;
        /** 每件武器每升一级给多少段位经验。进快照是为了让客户端看别人罗盘、看排行榜时自己算段位。 */
        public final int rankXpPerWeaponLevel;
        /** 法术范围保护，现在恒为 true；字段留着是为了不改同步包格式。 */
        public final boolean preventMasterySpellRangeScaling;
        /** 补录成武器的物品，格式「物品id=大类」。 */
        public final List<String> extraWeapons;
        /** 明确不算武器的物品 id。 */
        public final List<String> notWeapons;
        public final double xpBase;
        public final double xpExponent;
        public final double typeXpMultiplier;
        public final double overallXpMultiplier;
        public final double netherXpMultiplier;
        public final double endXpMultiplier;
        public final double moddedDimensionXpMultiplier;

        /**
         * 近战附属的武器类型是否按刀包拆分。
         *
         * <p>放在快照里而不是各端各读各的配置：服务端按这个值算类型键、客户端按这个值建树，
         * 两边口径必须完全一致，否则界面上的类型节点会永远是 0 级。
         */
        public final boolean splitMeleeByPack;

        /** 是否启用特化系统。 */
        public final boolean enableSpecialization;

        /** 切换特化的冷却秒数。 */
        public final int specSwitchCooldownSeconds;

        /** 特化的养成规则：槽数、门槛、每级系数和价格、点数、段位减免、退点冷却。 */
        public final SpecRules specRules;

        /** 单件武器的专精经验倍率：武器 ID 或完整武器键 → 倍率。 */
        public final Map<String, Double> weaponXpMultipliers;

        /** 单件武器的专精伤害加成倍率：武器 ID 或完整武器键 → 倍率。 */
        public final Map<String, Double> weaponDamageMultipliers;

        /** 特化键 -&gt; 数值。缺项按中性处理。 */
        private final Map<String, SpecializationValues> specValues;

        /** 特化键 -&gt; 每一级缩放后的数值（普通版在前、突破版在后），用到才算。 */
        private final Map<String, SpecializationValues[]> scaledCache = new ConcurrentHashMap<>();

        /** 单件武器的等级曲线。 */
        private final LevelCurve weaponCurve;

        /** 武器类型的等级曲线，所需经验是单件武器的 {@code typeXpMultiplier} 倍。 */
        private final LevelCurve typeCurve;

        /** 总体的等级曲线，所需经验是单件武器的 {@code overallXpMultiplier} 倍。 */
        private final LevelCurve overallCurve;

        /**
         * 构造快照。
         *
         * @param maxLevel                  等级上限
         * @param overallDamagePerLevel     总体每级伤害加成
         * @param typeDamagePerLevel        类型每级伤害加成
         * @param weaponDamagePerLevel      单件武器每级自身伤害加成
         * @param weaponCrossDamagePerLevel 单件武器每级对同类型其它武器的伤害加成
         * @param maxTotalDamageBonus       伤害加成总上限
         * @param sourceMasteryMultipliers  各武器来源的专精伤害倍率，来源 ID → 倍率
         * @param xpBase                    经验曲线基数
         * @param xpExponent                经验曲线指数
         * @param typeXpMultiplier          类型升级经验倍率
         * @param overallXpMultiplier       总体升级经验倍率
         * @param netherXpMultiplier        下界经验倍率
         * @param endXpMultiplier           末地经验倍率
         * @param moddedDimensionXpMultiplier 模组维度经验倍率
         * @param splitMeleeByPack          近战附属是否按刀包拆分类型
         * @param enableSpecialization      是否启用特化系统
         * @param specSwitchCooldownSeconds 切换特化的冷却秒数
         * @param preventMasterySpellRangeScaling 是否启用法术范围保护
         * @param specValues                特化数值表
         * @param extraWeapons              补录成武器的物品
         * @param notWeapons                明确不算武器的物品
         * @param spellMasteryManaShare     法术专精折算成法力折扣的比例
         * @param spellMasteryCooldownShare 法术专精折算成冷却折扣的比例
         * @param spellMasteryReductionCap  法术专精折扣的上限
         * @param rankXpPerWeaponLevel      每件武器每升一级给多少段位经验
         */
        public Snapshot(int maxLevel, double overallDamagePerLevel, double typeDamagePerLevel,
                        double weaponDamagePerLevel, double weaponCrossDamagePerLevel,
                        double maxTotalDamageBonus,
                        Map<String, Double> sourceMasteryMultipliers,
                        double xpBase, double xpExponent,
                        double typeXpMultiplier, double overallXpMultiplier,
                        double netherXpMultiplier, double endXpMultiplier,
                        double moddedDimensionXpMultiplier, boolean splitMeleeByPack,
                        boolean enableSpecialization, int specSwitchCooldownSeconds,
                        SpecRules specRules,
                        Map<String, SpecializationValues> specValues,
                        boolean preventMasterySpellRangeScaling,
                        List<String> extraWeapons, List<String> notWeapons,
                        double spellMasteryManaShare, double spellMasteryCooldownShare,
                        double spellMasteryReductionCap, int rankXpPerWeaponLevel,
                        Map<String, Double> weaponXpMultipliers, Map<String, Double> weaponDamageMultipliers) {
            this.extraWeapons = extraWeapons == null ? List.of() : List.copyOf(extraWeapons);
            this.notWeapons = notWeapons == null ? List.of() : List.copyOf(notWeapons);
            this.maxLevel = Math.max(1, maxLevel);
            this.overallDamagePerLevel = overallDamagePerLevel;
            this.typeDamagePerLevel = typeDamagePerLevel;
            this.weaponDamagePerLevel = weaponDamagePerLevel;
            this.weaponCrossDamagePerLevel = weaponCrossDamagePerLevel;
            this.maxTotalDamageBonus = maxTotalDamageBonus;
            // 每个来源读自己那一行，互不影响；表里没有的来源在取值时按 1.0 算
            Map<String, Double> sourceMultipliers = new LinkedHashMap<>();
            if (sourceMasteryMultipliers != null) {
                sourceMasteryMultipliers.forEach((providerId, value) -> {
                    if (providerId != null && value != null) {
                        sourceMultipliers.put(providerId, clamp(value, 0.0D, 100.0D));
                    }
                });
            }
            this.sourceMasteryMultipliers = Collections.unmodifiableMap(sourceMultipliers);
            // 这三项决定法术的实际法力 / 冷却，客户端要按同一个数显示，所以进快照而不是各读各的配置
            this.spellMasteryManaShare = clamp(spellMasteryManaShare, 0.0D, 1.0D);
            this.spellMasteryCooldownShare = clamp(spellMasteryCooldownShare, 0.0D, 1.0D);
            this.spellMasteryReductionCap = clamp(spellMasteryReductionCap, 0.0D, 0.9D);
            this.rankXpPerWeaponLevel = Math.max(1, Math.min(100000, rankXpPerWeaponLevel));
            this.xpBase = xpBase;
            this.xpExponent = xpExponent;
            this.typeXpMultiplier = Math.max(0.1D, typeXpMultiplier);
            this.overallXpMultiplier = Math.max(0.1D, overallXpMultiplier);
            this.netherXpMultiplier = netherXpMultiplier;
            this.endXpMultiplier = endXpMultiplier;
            this.moddedDimensionXpMultiplier = moddedDimensionXpMultiplier;
            this.splitMeleeByPack = splitMeleeByPack;
            this.enableSpecialization = enableSpecialization;
            this.specSwitchCooldownSeconds = Math.max(0, specSwitchCooldownSeconds);
            this.specRules = specRules == null ? SpecRules.createDefault() : specRules;
            this.weaponXpMultipliers = weaponXpMultipliers == null ? Map.of() : Map.copyOf(weaponXpMultipliers);
            this.weaponDamageMultipliers = weaponDamageMultipliers == null
                    ? Map.of() : Map.copyOf(weaponDamageMultipliers);
            this.specValues = Map.copyOf(specValues);
            this.preventMasterySpellRangeScaling = preventMasterySpellRangeScaling;
            // 指数相同、只放大基数，等于把每一级的需求整体乘上倍率
            this.weaponCurve = new LevelCurve(this.maxLevel, xpBase, xpExponent);
            this.typeCurve = new LevelCurve(this.maxLevel, xpBase * this.typeXpMultiplier, xpExponent);
            this.overallCurve = new LevelCurve(this.maxLevel, xpBase * this.overallXpMultiplier, xpExponent);
        }

        /**
         * 按武器来源取得专精伤害倍率。
         *
         * <p>这个倍率只放大专精等级本身算出来的加成，不碰特化 / 特化的独立倍率。
         * 未知来源按 1.0 处理，新增 Provider 即使忘了补配置也不会莫名变强或变弱。
         *
         * @param providerId 武器来源 ID
         * @return 该来源的专精伤害倍率
         */
        public double masteryDamageMultiplier(String providerId) {
            if (providerId == null) {
                return 1.0D;
            }
            Double multiplier = this.sourceMasteryMultipliers.get(providerId);
            if (multiplier == null) {
                warnUnknownSource(providerId);
                return 1.0D;
            }
            return multiplier;
        }

        /**
         * 撞到一个没有独立倍率配置的来源，吼一次。
         *
         * <p>新增来源忘了登记进 {@link WeaponKeys#ALL_PROVIDERS} 时，表现是「这个模组的武器
         * 怎么调都不动」——数值上完全正常（按 1.0 走），只是服主没有那一行可以改。
         * 不吼一声的话这种漏登记能在服里躺很久。
         *
         * <p>每个来源只打一次，这是伤害结算的热路径，不能每次命中都写日志。
         *
         * @param providerId 来源 ID
         */
        private void warnUnknownSource(String providerId) {
            // 配置还没加载完时快照是空的，那不是漏登记，别误报
            if (this.sourceMasteryMultipliers.isEmpty() || !WARNED_SOURCES.add(providerId)) {
                return;
            }
            AlbionMastery.LOGGER.warn("[albionmastery] 来源「{}」没有自己的专精伤害倍率配置，按 1.0 算。"
                    + "把它加进 WeaponKeys.ALL_PROVIDERS，配置里就会多出对应的一行。", providerId);
        }

        /**
         * 取一个特化的参考值（配置里写的数）。
         *
         * @param spec 特化
         * @return 数值；表里没有（比如制式、或者服务端版本更老不认识这个特化）时返回中性值
         */
        public SpecializationValues specValues(Specialization spec) {
            return this.specValues.getOrDefault(spec.key(), SpecializationValues.NEUTRAL);
        }

        /**
         * 某个特化在某一级的实际数值。
         *
         * <p>开火、换弹、每 tick 的手感修正都会走到这里，按级缓存，同一份快照里每个特化每一级只算一次。
         *
         * @param spec         特化
         * @param breakthrough true 取突破版（★），false 取普通版（Lv）
         * @param level        等级，1 起；0 及以下返回中性值
         * @return 数值
         */
        public SpecializationValues specValues(Specialization spec, boolean breakthrough, int level) {
            int max = breakthrough ? this.specRules.breakthroughMax() : this.specRules.normalMax();
            if (level <= 0 || spec.isStandard()) {
                return SpecializationValues.NEUTRAL;
            }
            int clamped = Math.min(level, max);
            SpecializationValues[] levels = this.scaledCache.computeIfAbsent(spec.key(), key -> {
                SpecializationValues reference = specValues(spec);
                int normal = this.specRules.normalMax();
                SpecializationValues[] built = new SpecializationValues[normal + this.specRules.breakthroughMax()];
                for (int i = 0; i < built.length; i++) {
                    double scale = i < normal ? this.specRules.normalScale(i + 1)
                            : this.specRules.breakthroughScale(i - normal + 1);
                    built[i] = SpecializationValues.scaled(reference, scale);
                }
                return built;
            });
            return levels[(breakthrough ? this.specRules.normalMax() : 0) + clamped - 1];
        }

        /**
         * 单件武器的专精经验倍率：先按完整武器键查，再按武器 ID 查，都没有是 1.0。
         *
         * @param weaponKey 武器键
         * @return 倍率
         */
        public double weaponXpMultiplier(String weaponKey) {
            return lookupWeapon(this.weaponXpMultipliers, weaponKey);
        }

        /**
         * 单件武器的专精伤害加成倍率，查法同上。
         *
         * @param weaponKey 武器键
         * @return 倍率
         */
        public double weaponDamageMultiplier(String weaponKey) {
            return lookupWeapon(this.weaponDamageMultipliers, weaponKey);
        }

        private static double lookupWeapon(Map<String, Double> table, String weaponKey) {
            if (table.isEmpty() || weaponKey == null) {
                return 1.0D;
            }
            Double exact = table.get(weaponKey);
            if (exact != null) {
                return exact;
            }
            int slash = weaponKey.indexOf(WeaponKeys.WEAPON_SEPARATOR);
            Double byId = slash >= 0 ? table.get(weaponKey.substring(slash + 1)) : null;
            return byId == null ? 1.0D : byId;
        }

        /**
         * @return 单件武器的等级曲线
         */
        public LevelCurve weaponCurve() {
            return this.weaponCurve;
        }

        /**
         * @return 武器类型的等级曲线
         */
        public LevelCurve typeCurve() {
            return this.typeCurve;
        }

        /**
         * @return 总体的等级曲线
         */
        public LevelCurve overallCurve() {
            return this.overallCurve;
        }

        /**
         * 生成一份默认快照，用于配置尚未加载完成时的兜底。
         *
         * @return 默认快照
         */
        public static Snapshot createDefault() {
            return new Snapshot(100, 0.005D, 0.01D, 0.02D, 0.001D, 100.0D,
                    Map.of(),
                    80.0D, 1.0D, 3.0D, 10.0D, 1.5D, 2.0D, 2.0D,
                    true, true, 3600, SpecRules.createDefault(), Map.of(), true, List.of(), List.of(),
                    0.3D, 0.3D, 0.5D, 100, Map.of(), Map.of());
        }

        /**
         * 写入网络缓冲区。
         *
         * @param buf 目标缓冲区
         */
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(this.maxLevel);
            buf.writeDouble(this.overallDamagePerLevel);
            buf.writeDouble(this.typeDamagePerLevel);
            buf.writeDouble(this.weaponDamagePerLevel);
            buf.writeDouble(this.weaponCrossDamagePerLevel);
            buf.writeDouble(this.maxTotalDamageBonus);
            buf.writeDouble(this.xpBase);
            buf.writeDouble(this.xpExponent);
            buf.writeDouble(this.typeXpMultiplier);
            buf.writeDouble(this.overallXpMultiplier);
            buf.writeDouble(this.netherXpMultiplier);
            buf.writeDouble(this.endXpMultiplier);
            buf.writeDouble(this.moddedDimensionXpMultiplier);
            buf.writeBoolean(this.splitMeleeByPack);
            buf.writeBoolean(this.enableSpecialization);
            buf.writeVarInt(this.specSwitchCooldownSeconds);
            this.specRules.write(buf);
            // 各来源的专精伤害倍率。客户端面板必须和服务端实际结算完全一致，所以整张表照发。
            // 写成「条数 + 键值对」而不是固定几个 double：以后加来源，包结构一个字都不用改
            buf.writeVarInt(this.sourceMasteryMultipliers.size());
            for (Map.Entry<String, Double> entry : this.sourceMasteryMultipliers.entrySet()) {
                buf.writeUtf(entry.getKey(), 64);
                buf.writeDouble(entry.getValue());
            }
            buf.writeVarInt(this.specValues.size());
            for (Map.Entry<String, SpecializationValues> entry : this.specValues.entrySet()) {
                buf.writeUtf(entry.getKey(), 128);
                entry.getValue().write(buf);
            }
            buf.writeBoolean(this.preventMasterySpellRangeScaling);
            // 追加在最后：老版本的包读到这里就结束，不影响前面的字段
            writeStrings(buf, this.extraWeapons);
            writeStrings(buf, this.notWeapons);
            buf.writeDouble(this.spellMasteryManaShare);
            buf.writeDouble(this.spellMasteryCooldownShare);
            buf.writeDouble(this.spellMasteryReductionCap);
            buf.writeVarInt(this.rankXpPerWeaponLevel);
            writeMultipliers(buf, this.weaponXpMultipliers);
            writeMultipliers(buf, this.weaponDamageMultipliers);
        }

        private static void writeMultipliers(FriendlyByteBuf buf, Map<String, Double> table) {
            List<Map.Entry<String, Double>> entries = new ArrayList<>();
            for (Map.Entry<String, Double> entry : table.entrySet()) {
                if (entry.getKey().length() <= 256 && entries.size() < MAX_STRING_ENTRIES) {
                    entries.add(entry);
                }
            }
            buf.writeVarInt(entries.size());
            for (Map.Entry<String, Double> entry : entries) {
                buf.writeUtf(entry.getKey(), 256);
                buf.writeDouble(entry.getValue());
            }
        }

        private static Map<String, Double> readMultipliers(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_STRING_ENTRIES) {
                throw new IllegalArgumentException("AlbionMastery: 同步包里的武器倍率条数非法 " + count);
            }
            Map<String, Double> table = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String key = buf.readUtf(256);
                table.put(key, clamp(buf.readDouble(), 0.0D, 100.0D));
            }
            return table;
        }

        /**
         * 从网络缓冲区读取，读取后所有数值都会被夹到合法区间，防止异常数据导致除零 / 溢出。
         *
         * @param buf 源缓冲区
         * @return 反序列化出的快照
         */
        public static Snapshot read(FriendlyByteBuf buf) {
            int maxLevel = Math.max(1, Math.min(1000, buf.readVarInt()));
            double overallDmg = clamp(buf.readDouble(), 0.0D, 1.0D);
            double typeDmg = clamp(buf.readDouble(), 0.0D, 1.0D);
            double wpnDmg = clamp(buf.readDouble(), 0.0D, 1.0D);
            double crossDmg = clamp(buf.readDouble(), 0.0D, 1.0D);
            double maxBonus = clamp(buf.readDouble(), 0.0D, 1000.0D);
            double xpBase = clamp(buf.readDouble(), 0.1D, 1.0E9D);
            double xpExp = clamp(buf.readDouble(), 0.0D, 5.0D);
            double typeMul = clamp(buf.readDouble(), 0.1D, 100.0D);
            double overallMul = clamp(buf.readDouble(), 0.1D, 100.0D);
            double netherMul = clamp(buf.readDouble(), 0.0D, 100.0D);
            double endMul = clamp(buf.readDouble(), 0.0D, 100.0D);
            double moddedMul = clamp(buf.readDouble(), 0.0D, 100.0D);
            boolean splitMelee = buf.readBoolean();
            boolean enableSpec = buf.readBoolean();
            int specCooldown = Math.max(0, Math.min(604800, buf.readVarInt()));
            SpecRules specRules = SpecRules.read(buf);
            // 同上，条数非法直接判包坏掉，别裁剪
            int sourceCount = buf.readVarInt();
            if (sourceCount < 0 || sourceCount > MAX_SOURCE_ENTRIES) {
                throw new IllegalArgumentException("AlbionMastery: 同步包里的来源倍率条数非法 " + sourceCount);
            }
            Map<String, Double> sourceMultipliers = new LinkedHashMap<>();
            for (int i = 0; i < sourceCount; i++) {
                String providerId = buf.readUtf(64);
                sourceMultipliers.put(providerId, clamp(buf.readDouble(), 0.0D, 100.0D));
            }
            // 条数按枚举总量封顶，异常包不至于让客户端在这里分配一张巨表
            int specCount = Math.max(0, Math.min(Specialization.values().length, buf.readVarInt()));
            Map<String, SpecializationValues> specValues = new HashMap<>();
            for (int i = 0; i < specCount; i++) {
                String key = buf.readUtf(128);
                specValues.put(key, SpecializationValues.read(buf));
            }
            boolean preventRangeScaling = buf.readBoolean();
            List<String> extraWeapons = readStrings(buf);
            List<String> notWeapons = readStrings(buf);
            double manaShare = clamp(buf.readDouble(), 0.0D, 1.0D);
            double cooldownShare = clamp(buf.readDouble(), 0.0D, 1.0D);
            double reductionCap = clamp(buf.readDouble(), 0.0D, 0.9D);
            int rankXpPerLevel = Math.max(1, Math.min(100000, buf.readVarInt()));
            Map<String, Double> weaponXp = readMultipliers(buf);
            Map<String, Double> weaponDamage = readMultipliers(buf);
            return new Snapshot(maxLevel, overallDmg, typeDmg, wpnDmg, crossDmg,
                    maxBonus, sourceMultipliers,
                    xpBase, xpExp, typeMul, overallMul, netherMul, endMul, moddedMul,
                    splitMelee, enableSpec, specCooldown, specRules, specValues, preventRangeScaling,
                    extraWeapons, notWeapons, manaShare, cooldownShare, reductionCap, rankXpPerLevel,
                    weaponXp, weaponDamage);
        }

        /** 已经吼过「没有独立倍率配置」的来源，每个只吼一次。 */
        private static final Set<String> WARNED_SOURCES = ConcurrentHashMap.newKeySet();

        /** 一份物品清单最多同步多少条。写端超了会截断并告警，读端读到超限的条数直接判包非法。 */
        private static final int MAX_STRING_ENTRIES = 4096;

        /** 来源倍率最多同步多少条。 */
        private static final int MAX_SOURCE_ENTRIES = 256;

        /**
         * 写一串字符串。
         *
         * <p>太长的条目直接跳过而不是写进去：{@code writeUtf} 超长会抛异常，
         * 那一抛整个全量同步包就编码失败，所有人登录后拿不到任何专精数据、界面全是 0 级。
         * 服主把一串物品 id 粘成一行是很容易发生的事，不能让它把整个模组打瘫。
         *
         * @param buf     目标缓冲区
         * @param entries 条目
         */
        private static void writeStrings(FriendlyByteBuf buf, List<String> entries) {
            List<String> valid = new ArrayList<>(entries.size());
            for (String entry : entries) {
                if (entry != null && entry.length() <= 256) {
                    valid.add(entry);
                }
            }
            if (valid.size() > MAX_STRING_ENTRIES) {
                // 截断在写端做，两端的条数才对得上；读端遇到超限只能判包非法
                AlbionMastery.LOGGER.warn("[albionmastery] 配置里的物品清单有 {} 条，只有前 {} 条会同步给客户端",
                        valid.size(), MAX_STRING_ENTRIES);
                valid = valid.subList(0, MAX_STRING_ENTRIES);
            }
            buf.writeVarInt(valid.size());
            for (String entry : valid) {
                buf.writeUtf(entry, 256);
            }
        }

        /**
         * 读一串字符串。
         *
         * @param buf 源缓冲区
         * @return 读出来的列表
         */
        private static List<String> readStrings(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            // 条数是长度前缀，只能「认」或者「炸」：悄悄改小的话，多出来的那几条字节还留在包里，
            // 后面每一个字段都从错误的位置开始读，最后整包解码失败，症状是所有人一进服就掉线
            if (count < 0 || count > MAX_STRING_ENTRIES) {
                throw new IllegalArgumentException("AlbionMastery: 同步包里的物品清单条数非法 " + count);
            }
            List<String> out = new java.util.ArrayList<>(Math.min(count, 64));
            for (int i = 0; i < count; i++) {
                out.add(buf.readUtf(256));
            }
            return List.copyOf(out);
        }

        /**
         * 数值夹取工具，同时把 NaN 归一到下界。
         *
         * @param value 原始值
         * @param min   下界
         * @param max   上界
         * @return 夹取后的值
         */
        private static double clamp(double value, double min, double max) {
            if (Double.isNaN(value)) {
                return min;
            }
            return Math.max(min, Math.min(max, value));
        }
    }
}
