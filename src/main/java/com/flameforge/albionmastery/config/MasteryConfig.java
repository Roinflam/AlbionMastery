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
            Map.entry("reloadTime", "换弹耗时倍率（2.0 = 要花两倍时间，0.5 = 只要一半时间）"),
            Map.entry("damage", "通用伤害倍率"),
            Map.entry("attackInterval", "近战攻击间隔倍率（0.6 = 出手快四成，1.6 = 慢六成）"),
            Map.entry("summonedDamage", "拔刀剑幻影剑（召唤剑）伤害倍率"),
            Map.entry("directDamage", "拔刀剑刀身直接攻击伤害倍率"),
            Map.entry("vsPlayerDamage", "目标是玩家时的额外倍率"),
            Map.entry("vsMobDamage", "目标不是玩家时的额外倍率"),
            Map.entry("armoredDamage", "目标有护甲时的额外倍率"),
            Map.entry("unarmoredDamage", "目标没有护甲时的额外倍率"),
            Map.entry("headShotDamage", "爆头时的额外倍率"),
            Map.entry("bodyShotDamage", "没爆头时的额外倍率"),
            Map.entry("executeDamage", "目标残血时的额外倍率"),
            Map.entry("executeThreshold", "残血的判定线，目标血量低于最大值的这个比例才算"),
            Map.entry("freshDamage", "目标接近满血时的额外倍率"),
            Map.entry("desperationDamage", "自己血量见底时能吃到的最高额外倍率，按血量线性过渡"),
            Map.entry("desperationThreshold", "自己血量低于这个比例才开始吃上面那条"),
            Map.entry("desperationFloor", "自己血量低到这个比例就吃满，再低也不会更高"),
            Map.entry("critChance", "暴击概率（0.15 = 15%）"),
            Map.entry("critDamage", "暴击时的额外倍率（1.5 = 暴击打 1.5 倍）"),
            Map.entry("lifeSteal", "吸血比例：按最终造成伤害的这个比例回血（0.1 = 10%）"),
            Map.entry("moveSpeed", "手持该类武器时的移动速度倍率（1.1 = 快一成，0.9 = 慢一成）"),
            Map.entry("xpGain", "用该类武器获得专精经验的倍率"),
            Map.entry("killRefill", "枪械击杀后立刻补回弹匣容量的这个比例（0.5 = 补半个弹匣）"),
            Map.entry("comboStep", "连击每层增加的伤害比例（0.05 = 每层 +5%），第一下不吃"),
            Map.entry("comboMax", "连击最多叠几层"),
            Map.entry("durability", "武器耐久消耗倍率（0.5 = 只扣一半，1.5 = 多扣五成）"),
            Map.entry("manaCost", "法术法力消耗倍率（0.7 = 省三成）"),
            Map.entry("spellCooldown", "法术冷却时间倍率（0.7 = 快三成）"),
            Map.entry("spellLevel", "法术等级加成，整数；开启 preventMasterySpellRangeScaling 时暂停此项，避免等级间接扩大范围"),
            Map.entry("gunVolume", "枪声音量倍率（0.3 = 只剩三成响，传播距离也跟着缩）"),
            Map.entry("bulletSpeed", "子弹初速倍率（1.2 = 快两成；上限 2.5，太快会穿墙漏判）"),
            Map.entry("recoil", "后座倍率（0.7 = 只剩七成后座）"),
            Map.entry("castTime", "法术施法时间倍率（0.8 = 前摇快两成）"),
            Map.entry("drawSpeed", "拉弓蓄力速度倍率（1.3 = 拉满快三成），只对弓有效"),
            Map.entry("doubleCast", "法术双重施法概率（1.0 = 每次施放后必定自动免费再放一次，不扣法力不算冷却）"),
            Map.entry("fireRate", "枪械射速倍率（1.3 = 射速快三成）。上限 3；最快 1200 发/分，TACZ 按 tick 开火，再快打不出来。点射的连发间隔也跟着变"),
            Map.entry("adsTime", "枪械开镜耗时倍率（0.7 = 开镜快三成，1.3 = 慢三成）"),
            Map.entry("hipSpread", "枪械腰射散布倍率（0.5 = 散布减半）。没完全开镜时开的枪都算腰射"),
            Map.entry("aimSpread", "枪械开镜散布倍率（0.5 = 散布减半）。瞄准进度满了才算开镜"),
            Map.entry("drawTime", "枪械掏枪耗时倍率（0.5 = 切到这把枪后一半时间就能开火）"),
            Map.entry("boltTime", "枪械拉栓耗时倍率（0.5 = 拉栓快一倍），只对栓动 / 泵动枪有效"),
            Map.entry("sprintTime", "枪械冲刺后恢复射击的耗时倍率（0.5 = 停跑后一半时间就能开火）"),
            Map.entry("ammoSave", "省弹概率（0.3 = 30%），上限 0.9。枪械：这一发不扣弹匣，背包直读和栓动枪不生效。"
                    + "弓弩：只有这一箭确实从背包里扣掉了才会按概率退还，无限附魔、创造模式、模组的无限弓和箭袋饰品都不退"),
            Map.entry("pierce", "子弹 / 箭 / 魔艺投射物额外穿透的目标数，整数（1 = 多打穿一个目标）。爆炸弹不生效"),
            Map.entry("knockback", "击退倍率（1.5 = 击退远一半，0.5 = 只剩一半），对近战、拔刀剑、弓箭有效"),
            Map.entry("reach", "攻击距离倍率（1.2 = 远两成），上限 2。只对吃原版交互距离属性的近战有效（当前是 "
                    + WeaponKeys.vanillaAttributeProviderList() + "）"),
            Map.entry("backstabDamage", "从目标背后打中时的额外倍率（目标身后约 120° 的扇形）"),
            Map.entry("damageTaken", "手持该武器时自己受到的伤害倍率（0.8 = 少掉两成血），最低 0.1"),
            Map.entry("saDamage", "拔刀剑 SA 伤害倍率：SA 发动后 1.5 秒内的斩击都算"),
            Map.entry("soulGain", "拔刀剑耀魂获取倍率（1.5 = 多拿一半耀魂）"),
            Map.entry("killHeal", "击杀后回复自己最大生命值的这个比例（0.1 = 回一成血）"),
            Map.entry("frenzyStep", "嗜战：每次击杀叠一层，每层伤害加成（0.08 = 每层 +8%），8 秒内不再击杀就清零"),
            Map.entry("frenzyMax", "嗜战最多叠几层"),
            Map.entry("nearDamage", "目标在 6 格内时的伤害倍率"),
            Map.entry("farDamage", "目标在 24 格外时的伤害倍率"),
            Map.entry("igniteChance", "命中时点燃目标 4 秒的概率（同一 tick 对同一目标只掷一次，霰弹不会必中）"),
            Map.entry("slowChance", "命中时让目标缓慢 II 3 秒的概率"),
            Map.entry("weakenChance", "命中时让目标虚弱 4 秒的概率"),
            Map.entry("lastRoundDamage", "枪械弹匣只剩最后三成子弹时的伤害倍率"));

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
                COMMON.specSlot1Percent.get(),
                COMMON.specSlot2Percent.get(),
                collectSpecValues(),
                COMMON.preventMasterySpellRangeScaling.get(),
                List.copyOf(COMMON.extraWeapons.get()),
                List.copyOf(COMMON.notWeapons.get()),
                COMMON.spellMasteryManaShare.get(),
                COMMON.spellMasteryCooldownShare.get(),
                COMMON.spellMasteryReductionCap.get(),
                COMMON.rankXpPerWeaponLevel.get()
        );
        // 武器补录表两端各自按同一份配置刷新：服务端读自己的配置，客户端读同步过来的快照
        WeaponOverrides.applyFromConfig(serverSnapshot.extraWeapons, serverSnapshot.notWeapons);
        AlbionMastery.LOGGER.info("Mastery configuration refreshed: independent source multipliers, spell range protection={}",
                serverSnapshot.preventMasterySpellRangeScaling);
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
                    // 等级会影响范围、方块数量等多种公式，不能作为通用加成继续叠加。
                    // 只屏蔽运行快照中的该项，原配置不删除；关闭范围保护后可恢复。
                    key -> COMMON.preventMasterySpellRangeScaling.get() && "spellLevel".equals(key)
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
        /** 是否禁止专精和特化强度扩大已适配法术范围，默认开启。 */
        public final ForgeConfigSpec.BooleanValue preventMasterySpellRangeScaling;
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
        public final ForgeConfigSpec.IntValue specSlot1Percent;
        public final ForgeConfigSpec.IntValue specSlot2Percent;
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
            builder.comment("武器专精系统 - 玩法数值（以服务端配置为准，登录时会下发给客户端用于界面显示）")
                    .push("mastery");

            this.maxLevel = builder
                    .comment("专精等级上限（大类与单件武器共用同一个上限）")
                    .defineInRange("maxLevel", 100, 1, 1000);

            this.overallDamagePerLevel = builder
                    .comment("总体专精每 1 级，为该来源下所有武器提供的伤害加成（0.005 = 0.5%）")
                    .defineInRange("overallDamagePerLevel", 0.005D, 0.0D, 1.0D);

            this.typeDamagePerLevel = builder
                    .comment("武器类型专精每 1 级，为该类型下所有武器提供的伤害加成（0.01 = 1%）")
                    .defineInRange("typeDamagePerLevel", 0.01D, 0.0D, 1.0D);

            this.weaponDamagePerLevel = builder
                    .comment("单件武器专精每 1 级，为这件武器自身提供的伤害加成（0.02 = 2%）")
                    .defineInRange("weaponDamagePerLevel", 0.02D, 0.0D, 1.0D);

            this.weaponCrossDamagePerLevel = builder
                    .comment("单件武器专精每 1 级，为同类型下其它武器提供的伤害加成（0.001 = 0.1%）")
                    .defineInRange("weaponCrossDamagePerLevel", 0.001D, 0.0D, 1.0D);

            this.maxTotalDamageBonus = builder
                    .comment("单件武器可获得的伤害加成总上限（100.0 = 最多 +10000%），只是兜底防止数值失控")
                    .defineInRange("maxTotalDamageBonus", 100.0D, 0.0D, 1000.0D);

            builder.comment("不同武器来源的专精伤害倍率。",
                            "这里乘的是专精本身给出的伤害加成：总体（内圈）、类型（中圈）、单件武器（外圈）以及同类型联动都会一起乘。",
                            "一个来源一个键，互相不影响，默认全是 1.0 = 完全保持专精原本的强度。",
                            "以后模组新增来源，这里会自动多出一行，不会去蹭别的来源的倍率。",
                            "更新模组不会覆盖已经改过的值。")
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

            this.preventMasterySpellRangeScaling = builder
                    .comment("禁止专精与特化强度放大铁魔法及 Geomancy Plus 已适配法术的范围、距离和挖掘尺寸。",
                            "保留装备、本体配置提供的强度，以及专精的伤害、治疗、时长加成。",
                            "开启时暂停特化 spellLevel 加成，防止法术等级间接扩大范围；客户端使用服务端同步值。",
                            "false 可恢复旧范围行为；修改后建议重启服务端并让客户端重新连接。",
                            "Prevent mastery from increasing supported spell ranges; disables specialization spell-level bonuses.")
                    .define("preventMasterySpellRangeScaling", true);

            this.xpBase = builder
                    .comment("经验曲线基数：升到第 L 级所需经验 = round(xpBase * L ^ xpExponent)。",
                            "默认基数由 40 调整为 80，新配置的升级需求为原默认的两倍，",
                            "已有 xpBase 配置原值保留；旧服若需要经验翻倍，请自行将原基数乘以 2。")
                    .defineInRange("xpBase", 80.0D, 0.1D, 1.0E9D);

            this.xpExponent = builder
                    .comment("经验曲线指数，越大后期越肝。",
                            "默认 1.0 是线性：单级需求随目标等级线性增长。",
                            "1.25 会让满级总量翻 2.8 倍，后期明显更肝。")
                    .defineInRange("xpExponent", 1.0D, 0.0D, 5.0D);

            this.xpPerDamage = builder
                    .comment("每造成 1 点伤害获得的专精经验（按加成生效前的基础伤害计算，避免正反馈）。",
                            "默认 0：专精经验只来自击杀时生物实际掉落的经验值，",
                            "打木桩、打高血量低价值的怪不会刷出经验。想恢复「按伤害给」就调大它。")
                    .defineInRange("xpPerDamage", 0.0D, 0.0D, 1000.0D);

            this.xpPerKill = builder
                    .comment("每次击杀固定获得的专精经验，与生物强弱无关。",
                            "默认 0：完全按生物实际掉落的经验值折算。")
                    .defineInRange("xpPerKill", 0.0D, 0.0D, 100000.0D);

            this.xpPerMobExperience = builder
                    .comment("被杀生物每掉落 1 点经验值，额外获得的专精经验。",
                            "打凋灵和打鸡给的专精经验差距会拉开。设为 0 则退化为固定击杀奖励。")
                    .defineInRange("xpPerMobExperience", 8.0D, 0.0D, 1000.0D);
            this.xpFromOriginalMobExperience = builder
                    .comment("折算专精经验时用生物「原本」掉落的经验值（true），还是其它模组都改完之后实际掉的数（false）。",
                            "莱特兰-恶意会按怪物等级把掉落经验放大几倍到几十倍，金龟这类附魔也会加成，",
                            "取实际掉落值的话专精经验会跟着一起膨胀。默认 true，只按原版数值算")
                    .define("xpFromOriginalMobExperience", true);
            this.npcFallbackMobExperience = builder
                    .comment("击杀自定义 NPC（CustomNPCs）时，优先读取 NPC 编辑页「掉落」里设的经验（最小 / 最大的平均）；",
                            "只有读不到 CustomNPCs 数据时才使用这里的兜底值。NPC 明确把最小 / 最大都设为 0 时就是 0，不会套兜底。",
                            "没装 CustomNPCs 时这一项没有作用，本模组不依赖它")
                    .defineInRange("npcFallbackMobExperience", 0, 0, 100000);
            this.npcExperienceMultiplier = builder
                    .comment("CustomNPCs 的掉落经验参与专精经验折算前额外乘的倍率。默认 0.1 = 只取原配置经验的 1/10。",
                            "NPC 原始经验大于 1 且该倍率大于 0 时，折算后的经验最低按 1 计算，避免小经验 NPC 乘完直接接近 0。",
                            "如果 NPC 的最小 / 最大经验都为 0，则仍然是 0，不会被最低 1 抬起来。")
                    .defineInRange("npcExperienceMultiplier", 0.1D, 0.0D, 100.0D);

            this.typeXpMultiplier = builder
                    .comment("武器类型专精的升级所需经验倍率，以单件武器为基准。",
                            "3.0 表示把一个类型练满，需要的经验是把一把武器练满的 3 倍。",
                            "类型与单件武器每次都拿到同样多的经验，难度差异全在这个倍率上，",
                            "这样「几倍肝度」是个确定的数，不会因为玩家换了几把武器就变。")
                    .defineInRange("typeXpMultiplier", 3.0D, 0.1D, 100.0D);

            this.overallXpMultiplier = builder
                    .comment("总体（模组）专精的升级所需经验倍率，以单件武器为基准")
                    .defineInRange("overallXpMultiplier", 10.0D, 0.1D, 100.0D);

            this.maxXpPerHit = builder
                    .comment("单次命中可获得的经验上限，防止被超高伤害配置刷经验")
                    .defineInRange("maxXpPerHit", 200.0D, 1.0D, 1.0E9D);

            this.offhandXpRatio = builder
                    .comment("副手武器获得的专精经验比例。",
                            "主手是全额，副手默认半额——挂一把不用的枪在副手跟着满级总归有点白嫖。",
                            "两只手拿同一件武器时只按主手发一次，不会叠加。")
                    .defineInRange("offhandXpRatio", 0.5D, 0.0D, 1.0D);
            this.multiWeaponXpRatio = builder
                    .comment("一件物品里装着多件武器时（铁魔法的魔法书装了好几个法术），",
                            "每件武器拿到的击杀经验按这个比例打折。1.0 = 每个法术都拿全额")
                    .defineInRange("multiWeaponXpRatio", 1.0D, 0.0D, 1.0D);
            this.spellMasteryCooldownShare = builder
                    .comment("铁魔法：专精加成有多大比例同时折算成冷却缩减。",
                            "例：加成 +40%、比例 0.3 → 冷却缩短 12%。",
                            "传送、净化这类没有数值的法术只能靠这个和法力折扣体现专精，设 0 就没有")
                    .defineInRange("spellMasteryCooldownShare", 0.3D, 0.0D, 1.0D);
            this.spellMasteryManaShare = builder
                    .comment("铁魔法：专精加成有多大比例同时折算成法力消耗折扣，算法同上")
                    .defineInRange("spellMasteryManaShare", 0.3D, 0.0D, 1.0D);
            this.spellMasteryReductionCap = builder
                    .comment("上面两项折算出来的缩减最多到多少（0.5 = 最多减半），防止高加成把冷却和法力压到没有")
                    .defineInRange("spellMasteryReductionCap", 0.5D, 0.0D, 0.9D);
            this.spellEffectDurationSpells = builder
                    .comment("铁魔法：这些法术上的效果（增益 / 减益）持续时间额外按专精倍率延长。",
                            "铁魔法本体的效果时长几乎都是从「法术强度」算出来的，强度涨了时长自然涨，不用列在这里；",
                            "列在这里的是时长只按法术等级写死、不看强度的那些（多来自附属），",
                            "列进去的法术不会被重复放大。写法术 ID，如 gtbcs_geomancy_plus:tremor_step")
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
                    .comment("上面那条规则里永远不碰的效果 ID。ID 以 _cast / _loop 结尾、或含 casting / resource 的",
                            "施法状态类效果已经写死跳过，不用列；这里放额外要排除的")
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
                            "判定方式是「维度 ID 的命名空间不是 minecraft」，",
                            "装什么维度模组都自动生效，不需要逐个登记。")
                    .defineInRange("moddedDimensionXpMultiplier", 2.0D, 0.0D, 100.0D);

            this.dimensionXpMultipliers = builder
                    .comment("单独指定某些维度的倍率，优先级高于上面所有规则。",
                            "每行格式：维度ID=倍率，例如 twilightforest:twilight_forest=1.8")
                    .defineList("dimensionXpMultipliers", List.of(),
                            entry -> entry instanceof String);

            this.shareFalloff = builder
                    .comment("附近玩家共享击杀经验时，每人拿到的比例，按参与人数查表。",
                            "第一项是只有 1 人时（100%），第二项是 2 人时每人拿多少，以此类推；",
                            "人数超过表长就一直用最后一项。默认值参考 LoL 的共享经验曲线：",
                            "人越多每人拿得越少，但队伍总收益是上升的。",
                            "只有主副手拿着「还没练满」的可专精武器的玩家才算进人数。")
                    .defineList("shareFalloff",
                            List.of(1.0D, 0.725D, 0.633D, 0.575D, 0.55D),
                            value -> value instanceof Number number
                                    && number.doubleValue() >= 0.0D
                                    && number.doubleValue() <= 1.0D);

            this.nearbyShareRadius = builder
                    .comment("经验共享的判定半径（方块），只在击杀者所在维度内查找")
                    .defineInRange("nearbyShareRadius", 32.0D, 0.0D, 256.0D);

            this.xpFromPlayerDamage = builder
                    .comment("是否允许对玩家造成伤害时获得专精经验（PVP 服建议保持 true，纯 PVE 服可关掉防止互刷）")
                    .define("xpFromPlayerDamage", true);

            this.syncIntervalTicks = builder
                    .comment("专精数据变化后同步给客户端的最小间隔（tick），用于合并高射速武器产生的大量小改动")
                    .defineInRange("syncIntervalTicks", 20, 1, 200);

            this.splitMeleeByPack = builder
                    .comment("近战附属（lrtactical）的武器类型是否按刀包拆分。",
                            "开启：每个刀包各算一个武器类型，专精树上多出一层，和拔刀剑的附属包一致。",
                            "关闭：所有近战武器共用一个「近战」类型（1.0.0 的旧行为）。",
                            "改动会让近战「类型层」的经验换一套键，等于类型层从头开始；",
                            "武器层与总体层的等级不受影响。")
                    .define("splitMeleeByPack", true);

            builder.comment("专精特化：每个武器类型（中圈）有主、副两栏，练到门槛后各装一项，两项效果叠加。",
                            "不同武器叫法不同：枪和弓叫改装、刀叫改造、剑叫专长、拔刀剑叫修习、铁魔法叫铭刻、魔艺叫改良。",
                            "每一项都有得有失，每个来源的第一项（原厂配置 / 无专长 / 无修习等）是什么都不改的默认值。",
                            "倍率一律是「乘在原值上」，时间类小于 1 表示更快。")
                    .push("specialization");
            this.enableSpecialization = builder
                    .comment("是否启用特化系统。关闭后已选的特化不生效，但选择仍然保留在存档里")
                    .define("enable", true);
            this.specSwitchCooldownSeconds = builder
                    .comment("切换特化的冷却时间（秒），默认 1 小时。",
                            "冷却只记在内存里，服务端重启后清零")
                    .defineInRange("switchCooldownSeconds", 3600, 0, 604800);
            this.specSlot1Percent = builder
                    .comment("第一特化槽在进度多少时解锁（百分比，按等级上限算）。",
                            "选特化要求本类型（中圈）达到这个进度；某件武器要吃到特化效果，它自己（外圈）也得达到")
                    .defineInRange("slot1UnlockPercent", 50, 1, 100);
            this.specSlot2Percent = builder
                    .comment("第二特化槽在进度多少时解锁，规则同上。两个槽的效果叠加：",
                            "倍率相乘、概率和比例相加、层数和等级取大的")
                    .defineInRange("slot2UnlockPercent", 100, 1, 100);
            this.autoUpdateSpecBalance = builder
                    .comment("模组更新重新平衡了特化数值时，是否自动把下面各特化的数值换成新版默认值。",
                            "Forge 配置只会补上缺的项，已经写在文件里的旧数值不会被新默认值覆盖，",
                            "不开这个的话，升级后老服务器上的特化还是旧数值（说明文字却会按旧数值显示，不会错）。",
                            "自己改过特化数值、想保留的服主请关掉")
                    .define("autoUpdateSpecBalance", true);
            this.specBalanceVersion = builder
                    .comment("特化数值的版本号，由模组自己维护，不用手动改。",
                            "小于当前版本且上面那项开着时，下次启动会把全部特化数值重置为新默认值，然后写成当前版本")
                    .defineInRange("specBalanceVersion", 0, 0, Integer.MAX_VALUE);
            this.debugLogging = builder
                    .comment("排查用：把特化效果的关键路径（双重施法排队 / 补放、法力折扣等）打成 INFO 日志。",
                            "默认关闭；只有排查问题时才打开，人多的时候会刷屏")
                    .define("debugLogging", false);
            this.doubleCastTimeFactor = builder
                    .comment("双重施法补放的那一次，施法时间（引导 / 前摇）乘这个系数。0.5 = 减半")
                    .defineInRange("doubleCastTimeFactor", 0.5D, 0.05D, 1.0D);
            this.doubleCastExcludedSpells = builder
                    .comment("永远不双重施法的法术 ID。持续引导类（CONTINUOUS）和类名带 Summon 的召唤类已经写死排除，",
                            "这里放额外要排的，比如召唤亡灵这种类名里没有 Summon 的")
                    .defineListAllowEmpty("doubleCastExcludedSpells", List.of(
                            "irons_spellbooks:raise_dead",
                            "irons_spellbooks:raise_hell"),
                            value -> value instanceof String);
            this.extraWeapons = builder
                    .comment("补录成武器的物品，格式「物品id=大类」，大类可填 sword / axe / trident / other / bow / crossbow，",
                            "只写物品 id 不写大类时按 other（其它近战）算。",
                            "模组自己写基类、不继承原版武器类的武器，本体已经内置了一份常见的（灾变、诡厄、额外植物学、血肉重塑），",
                            "这里只放内置表没收到的。例：\"examplemod:big_sword=sword\"")
                    .defineListAllowEmpty("extraWeapons", List.of(), value -> value instanceof String);
            this.notWeapons = builder
                    .comment("明确不算武器的物品 id：被误收进专精树的东西写在这里就会被踢掉，优先级高于上面的补录表和物品类判定")
                    .defineListAllowEmpty("notWeapons", List.of(), value -> value instanceof String);
            this.killRefillMobRatio = builder
                    .comment("击杀补弹：特化里写的补弹比例按「杀玩家」算，杀怪物时再乘这个系数。",
                            "默认 0.2：弹药带杀玩家补 50%，杀怪只补 10%，割草不至于永远不用换弹")
                    .defineInRange("killRefillMobRatio", 0.2D, 0.0D, 1.0D);
            this.comboWindowTicks = builder
                    .comment("连击的时间窗（tick）：两次命中同一目标间隔超过这个时间，连击层数清零。默认 3 秒")
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

            builder.pop();

            builder.comment("全局专精排行榜：在专精界面左下角进入，能看全局谁练得最狠、点进去看他整个罗盘和选的特化。",
                            "榜单数据存在主世界 data/albionmastery_leaderboard.dat 里，下线的玩家也在榜上。",
                            "单人世界同样可用（开局域网联机时就是一份小榜单）。")
                    .push("leaderboard");
            this.leaderboardEnable = builder
                    .comment("总开关。关掉后客户端请求一律回「未开启」，已经存下的榜单数据不会删")
                    .define("enable", true);
            this.leaderboardTopCount = builder
                    .comment("榜单最多显示前多少名。请求者自己不在前几名里也能看到自己的名次和数据")
                    .defineInRange("topCount", 100, 5, 500);
            this.leaderboardUpdateIntervalSeconds = builder
                    .comment("在线玩家的榜单数据多久刷新一次（秒）。登录、登出、玩家自己打开榜单时另外会立刻刷新，",
                            "所以这个值只影响「别人看到你」的延迟，调大点省性能")
                    .defineInRange("updateIntervalSeconds", 30, 5, 600);
            this.leaderboardRetentionDays = builder
                    .comment("多久没上线的玩家从榜单里清掉（天）。0 = 永不清理。",
                            "榜单会把每个玩家的整份专精数据存在主世界的存档里，开服久了只增不减，",
                            "长期运行的服务器建议留个上限。在线玩家永远不会被清")
                    .defineInRange("retentionDays", 90, 0, 3650);
            this.leaderboardRequestCooldownSeconds = builder
                    .comment("同一名玩家两次请求榜单之间至少隔几秒（要榜单、看某个人分别计）。0 = 不限")
                    .defineInRange("requestCooldownSeconds", 2, 0, 60);
            this.leaderboardBroadcastTopChanges = builder
                    .comment("总榜前三名易主时给所有在线玩家弹一条和升级同款的横幅（本人看到的是「你成为了…」，",
                            "别人看到的是「xxx 替代 yyy 成为了…」），不走聊天。只看总榜，日榜周榜月榜的变动不通知")
                    .define("broadcastTopChanges", true);
            this.leaderboardBroadcastSort = builder
                    .comment("广播按哪种排序算前三：MAXED 满级数，LEVEL_SUM 等级总和，UNLOCKED 已解锁数，XP 经验总和，",
                            "RANK 段位（和 LEVEL_SUM 同序，段位经验就是等级总和乘每级段位经验）")
                    .defineEnum("broadcastSort", LeaderboardSort.MAXED);
            builder.pop();

            builder.comment("段位：账号级的总进度，规则照搬星际战甲的精通段位。",
                            "段位经验只来自外圈（单件武器）：每件武器每升一级给固定的段位经验，类型和总体两圈不算。",
                            "门槛与星际战甲一致：1~30 段累计 2500 × 段位²（30 段至崇宗师 = 2,250,000），",
                            "之后每 147,500 升一个传奇段位。段位按武器等级现算：调低等级上限、或调高经验曲线（xpBase / xpExponent）时段位会跟着降；",
                            "反过来段位会升，重载配置后可能一次触发多段晋升")
                    .push("rank");
            this.rankXpPerWeaponLevel = builder
                    .comment("每件武器每升一级给多少段位经验。星际战甲是武器 100、战甲 200。",
                            "按默认的等级上限 100，一件满级武器给 10,000，225 件满级武器到至崇宗师")
                    .defineInRange("xpPerWeaponLevel", 100, 1, 100000);
            this.rankUpBroadcast = builder
                    .comment("晋升段位时在聊天栏通告全服（关掉后只有本人收到一行记录；屏幕上方的晋升动画不受影响）")
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

        Client(ForgeConfigSpec.Builder builder) {
            builder.comment("武器专精系统 - 客户端表现")
                    .push("client");

            this.renderItemIcons = builder
                    .comment("专精界面节点是否渲染真实物品图标。",
                            "TACZ 枪械使用自定义 3D 渲染器，同屏节点较多时开销明显；",
                            "关闭后会退化为纯色节点 + 文字，帧数更稳。")
                    .define("renderItemIcons", true);

            this.defaultZoom = builder
                    .comment("专精界面打开时的缩放倍数，相对于「整张图刚好放进屏幕」的自动值。",
                            "1.0 = 打开就能看到全貌；调大则打开时更靠近中心。")
                    .defineInRange("defaultZoom", 1.0D, 0.3D, 4.0D);

            this.showMasteryTooltip = builder
                    .comment("是否在武器物品的提示文本里追加一行专精摘要（等级与总加成）")
                    .define("showMasteryTooltip", true);

            this.equalSourceWedges = builder
                    .comment("专精树的来源扇形是否平分整圈：false = 按武器数量按比例分，武器多的来源占更大的角度（默认）；",
                            "true = 每个来源占的角度一样大，武器多的来源在自己那片里排得密一点")
                    .define("equalSourceWedges", false);

            this.detailedTooltip = builder
                    .comment("提示文本里是否展开加成来源拆解（大类 / 本武器 / 同类联动），默认只显示一行摘要")
                    .define("detailedTooltip", false);

            this.showLevelUpToast = builder
                    .comment("升级时在屏幕正上方弹出一条小提示")
                    .define("showLevelUpToast", true);

            this.levelUpSound = builder
                    .comment("升级时播放一声轻微的提示音（音量 0.3、音调 1.7，不打断战斗）")
                    .define("levelUpSound", true);

            this.overrideDamageDisplay = builder
                    .comment("是否把 TACZ 枪械提示里显示的伤害直接替换为加成后的最终数值。",
                            "关闭后提示里显示的是未计专精加成的原始伤害。")
                    .define("overrideDamageDisplay", true);

            this.rankUpAnimation = builder
                    .comment("晋升段位时在屏幕正上方播放晋升动画（关掉后只剩聊天栏那一行）")
                    .define("rankUpAnimation", true);

            this.rankUpSound = builder
                    .comment("晋升动画是否带音效")
                    .define("rankUpSound", true);

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
        /** 服务端同步的范围保护总开关。 */
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

        /** 第一特化槽的解锁进度（百分比）。 */
        public final int specSlot1Percent;

        /** 第二特化槽的解锁进度（百分比）。 */
        public final int specSlot2Percent;

        /** 特化键 -&gt; 数值。缺项按中性处理。 */
        private final Map<String, SpecializationValues> specValues;

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
                        int specSlot1Percent, int specSlot2Percent,
                        Map<String, SpecializationValues> specValues,
                        boolean preventMasterySpellRangeScaling,
                        List<String> extraWeapons, List<String> notWeapons,
                        double spellMasteryManaShare, double spellMasteryCooldownShare,
                        double spellMasteryReductionCap, int rankXpPerWeaponLevel) {
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
            this.specSlot1Percent = Math.max(1, Math.min(100, specSlot1Percent));
            this.specSlot2Percent = Math.max(1, Math.min(100, specSlot2Percent));
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
         * 取一个特化的数值。
         *
         * @param spec 特化
         * @return 数值；表里没有（比如制式、或者服务端版本更老不认识这个特化）时返回中性值
         */
        /**
         * 某个特化槽要求的等级（外圈和中圈都得到）。
         *
         * @param slot 槽位，1 或 2
         * @return 等级门槛
         */
        public int specSlotLevel(int slot) {
            int percent = slot >= 2 ? this.specSlot2Percent : this.specSlot1Percent;
            return Math.max(1, (int) Math.ceil(this.maxLevel * percent / 100.0D));
        }

        public SpecializationValues specValues(Specialization spec) {
            return this.specValues.getOrDefault(spec.key(), SpecializationValues.NEUTRAL);
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
                    true, true, 3600, 50, 100, Map.of(), true, List.of(), List.of(),
                    0.3D, 0.3D, 0.5D, 100);
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
            buf.writeVarInt(this.specSlot1Percent);
            buf.writeVarInt(this.specSlot2Percent);
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
            int slot1 = Math.max(1, Math.min(100, buf.readVarInt()));
            int slot2 = Math.max(1, Math.min(100, buf.readVarInt()));
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
            return new Snapshot(maxLevel, overallDmg, typeDmg, wpnDmg, crossDmg,
                    maxBonus, sourceMultipliers,
                    xpBase, xpExp, typeMul, overallMul, netherMul, endMul, moddedMul,
                    splitMelee, enableSpec, specCooldown, slot1, slot2, specValues, preventRangeScaling,
                    extraWeapons, notWeapons, manaShare, cooldownShare, reductionCap, rankXpPerLevel);
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
