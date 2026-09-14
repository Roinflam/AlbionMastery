package com.flameforge.albionmastery.config;

import com.flameforge.albionmastery.mastery.LevelCurve;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 武器专精系统的配置。
 *
 * <p>分为两部分：
 * <ul>
 *     <li>COMMON：影响玩法的数值（等级上限、加成系数、经验曲线），以服务端为准</li>
 *     <li>CLIENT：纯客户端表现（是否渲染物品图标等）</li>
 * </ul>
 *
 * <p>由于 COMMON 配置在专用服务器与客户端上是两份独立文件，为了保证界面显示的加成
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
     * <p>放一张表而不是在每个流派里各写一遍：同一个项名在不同流派下含义完全一样，
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
            Map.entry("spellLevel", "法术等级加成，整数（1 = 施放时按高一级算）"),
            Map.entry("gunVolume", "枪声音量倍率（0.3 = 只剩三成响，传播距离也跟着缩）"),
            Map.entry("bulletSpeed", "子弹初速倍率（1.2 = 快两成；上限 2.5，太快会穿墙漏判）"),
            Map.entry("recoil", "后座倍率（0.7 = 只剩七成后座）"),
            Map.entry("castTime", "法术施法时间倍率（0.8 = 前摇快两成）"),
            Map.entry("drawSpeed", "拉弓蓄力速度倍率（1.3 = 拉满快三成），只对弓有效"),
            Map.entry("doubleCast", "法术双重施法概率（1.0 = 每次施放后必定自动免费再放一次，不扣法力不算冷却）"));

    /**
     * 每个流派的数值项，由 {@link #defineSpec} 在构建配置时填入。
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
                collectSpecValues()
        );
    }

    /**
     * 把配置文件里的流派数值读成一张普通的表。
     *
     * <p>只在刷新快照时调用一次，之后所有读取都走快照，不再碰 {@code ConfigValue}。
     *
     * @return 流派键 -&gt; 数值
     */
    private static Map<String, SpecializationValues> collectSpecValues() {
        Map<String, SpecializationValues> values = new HashMap<>();
        for (Map.Entry<Specialization, SpecEntry> entry : SPEC_ENTRIES.entrySet()) {
            SpecEntry config = entry.getValue();
            // 这个流派没定义的维度取中性默认值，所以不用管每个流派各自关心哪几项
            values.put(entry.getKey().key(), SpecializationValues.of(
                    key -> config.get(key, SpecializationValues.defaultOf(key))));
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

        public final ForgeConfigSpec.IntValue maxLevel;
        public final ForgeConfigSpec.DoubleValue overallDamagePerLevel;
        public final ForgeConfigSpec.DoubleValue typeDamagePerLevel;
        public final ForgeConfigSpec.DoubleValue weaponDamagePerLevel;
        public final ForgeConfigSpec.DoubleValue weaponCrossDamagePerLevel;
        public final ForgeConfigSpec.DoubleValue maxTotalDamageBonus;
        public final ForgeConfigSpec.DoubleValue xpBase;
        public final ForgeConfigSpec.DoubleValue xpExponent;
        public final ForgeConfigSpec.DoubleValue xpPerDamage;
        public final ForgeConfigSpec.DoubleValue xpPerKill;
        public final ForgeConfigSpec.BooleanValue xpFromOriginalMobExperience;
        public final ForgeConfigSpec.IntValue npcFallbackMobExperience;
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
        public final ForgeConfigSpec.IntValue specSlot1Percent;
        public final ForgeConfigSpec.IntValue specSlot2Percent;

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
                    .comment("单件武器可获得的伤害加成总上限（10.0 = 最多 +1000%），用于兜底防止数值失控")
                    .defineInRange("maxTotalDamageBonus", 10.0D, 0.0D, 1000.0D);

            this.xpBase = builder
                    .comment("经验曲线基数：升到第 L 级所需经验 = xpBase * L ^ xpExponent。",
                            "默认 40 正好是一只僵尸的产出（掉 5 经验 × xpPerMobExperience 8），",
                            "也就是「打完第一只怪刚好升到 1 级」。这是全局肝度旋钮。")
                    .defineInRange("xpBase", 40.0D, 0.1D, 1.0E9D);

            this.xpExponent = builder
                    .comment("经验曲线指数，越大后期越肝。",
                            "默认 1.0 是线性：升到第 L 级正好需要 L 只僵尸的量，很好估算。",
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
                    .comment("击杀自定义 NPC（CustomNPCs）时，专精经验按 NPC 编辑页「掉落」里设的经验（最小 / 最大的平均）折算；",
                            "NPC 没设（是 0）或者读不到时，改用这里的兜底值。0 = 那种 NPC 不给专精经验。",
                            "没装 CustomNPCs 时这一项没有作用，本模组不依赖它")
                    .defineInRange("npcFallbackMobExperience", 0, 0, 100000);

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

            builder.comment("专精特化（流派）：某个来源的总体专精练满之后，",
                            "可以给这个来源选一个流派，效果作用于该来源下的所有武器。",
                            "每个流派都是有得有失的，「制式」是什么都不改的默认值。",
                            "倍率一律是「乘在原值上」，时间类小于 1 表示更快。")
                    .push("specialization");
            this.enableSpecialization = builder
                    .comment("是否启用特化系统。关闭后已选的流派不生效，但选择仍然保留在存档里")
                    .define("enable", true);
            this.specSwitchCooldownSeconds = builder
                    .comment("切换流派的冷却时间（秒），默认 1 小时。",
                            "冷却只记在内存里，服务器重启后清零")
                    .defineInRange("switchCooldownSeconds", 3600, 0, 604800);
            this.specSlot1Percent = builder
                    .comment("第一流派槽在进度多少时解锁（百分比，按等级上限算）。",
                            "选流派要求本类型（中圈）达到这个进度；某件武器要吃到流派效果，它自己（外圈）也得达到")
                    .defineInRange("slot1UnlockPercent", 50, 1, 100);
            this.specSlot2Percent = builder
                    .comment("第二流派槽在进度多少时解锁，规则同上。两个槽的效果叠加：",
                            "倍率相乘、概率和比例相加、层数和等级取大的")
                    .defineInRange("slot2UnlockPercent", 100, 1, 100);
            this.debugLogging = builder
                    .comment("排查用：把流派效果的关键路径（双重施法排队 / 补放、法力折扣等）打成 INFO 日志。",
                            "开发期默认开着，正式上线改成 false")
                    .define("debugLogging", true);
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
            this.killRefillMobRatio = builder
                    .comment("击杀补弹：流派里写的补弹比例按「杀玩家」算，杀怪物时再乘这个系数。",
                            "默认 0.2：弹药带杀玩家补 50%，杀怪只补 10%，割草不至于永远不用换弹")
                    .defineInRange("killRefillMobRatio", 0.2D, 0.0D, 1.0D);
            this.comboWindowTicks = builder
                    .comment("连击的时间窗（tick）：两次命中同一目标间隔超过这个时间，连击层数清零。默认 3 秒")
                    .defineInRange("comboWindowTicks", 60, 1, 1200);

            // 每个流派一个小节，只暴露它真正用到的数值项。
            // 每个流派都是「两三样好处 + 一两样代价」，好处里必须有一样是别的流派没有的
            defineSpec(builder, Specialization.GUN_EXTENDED_MAG, values -> {
                values.put("magazine", 2.0D);
                values.put("killRefill", 0.15D);
                values.put("reloadTime", 1.8D);
                values.put("moveSpeed", 0.95D);
            });
            defineSpec(builder, Specialization.GUN_COMPACT_MAG, values -> {
                values.put("reloadTime", 0.5D);
                values.put("moveSpeed", 1.08D);
                values.put("magazine", 0.5D);
            });
            defineSpec(builder, Specialization.GUN_HEAVY_AMMO, values -> {
                values.put("damage", 2.5D);
                values.put("critChance", 0.05D);
                values.put("critDamage", 2.0D);
                values.put("magazine", 0.5D);
                values.put("reloadTime", 2.0D);
                values.put("moveSpeed", 0.9D);
                values.put("recoil", 1.25D);
            });
            defineSpec(builder, Specialization.GUN_MATCH_BARREL, values -> {
                values.put("headShotDamage", 2.2D);
                values.put("critChance", 0.15D);
                values.put("critDamage", 1.5D);
                values.put("bodyShotDamage", 0.7D);
                values.put("bulletSpeed", 1.15D);
            });
            defineSpec(builder, Specialization.GUN_HOLLOW_POINT, values -> {
                values.put("executeDamage", 2.2D);
                values.put("executeThreshold", 0.35D);
                values.put("killRefill", 0.25D);
                values.put("freshDamage", 0.75D);
            });
            defineSpec(builder, Specialization.GUN_BANDOLIER, values -> {
                values.put("killRefill", 0.5D);
                values.put("xpGain", 1.5D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.GUN_SIPHON_ROUNDS, values -> {
                values.put("lifeSteal", 0.1D);
                values.put("damage", 0.85D);
                values.put("magazine", 0.85D);
            });
            defineSpec(builder, Specialization.GUN_LIGHT_FRAME, values -> {
                values.put("moveSpeed", 1.15D);
                values.put("reloadTime", 0.8D);
                values.put("damage", 0.85D);
                values.put("magazine", 0.8D);
                values.put("recoil", 1.2D);
            });
            defineSpec(builder, Specialization.GUN_TRACER_ROUNDS, values -> {
                values.put("comboStep", 0.04D);
                values.put("comboMax", 10.0D);
                values.put("damage", 0.8D);
            });
            defineSpec(builder, Specialization.GUN_ARMOR_PIERCING, values -> {
                values.put("armoredDamage", 1.8D);
                values.put("unarmoredDamage", 0.8D);
            });
            defineSpec(builder, Specialization.GUN_SOFT_POINT, values -> {
                values.put("unarmoredDamage", 1.5D);
                values.put("armoredDamage", 0.7D);
            });
            defineSpec(builder, Specialization.GUN_SUPPRESSOR, values -> {
                values.put("critChance", 0.12D);
                values.put("critDamage", 1.4D);
                values.put("damage", 0.9D);
                values.put("gunVolume", 0.3D);
            });
            defineSpec(builder, Specialization.GUN_MUZZLE_BRAKE, values -> {
                values.put("comboStep", 0.03D);
                values.put("comboMax", 8.0D);
                values.put("reloadTime", 0.9D);
                values.put("damage", 0.9D);
                values.put("recoil", 0.6D);
            });
            defineSpec(builder, Specialization.GUN_TACTICAL_GRIP, values -> {
                values.put("moveSpeed", 1.1D);
                values.put("reloadTime", 0.9D);
                values.put("magazine", 0.85D);
                values.put("recoil", 0.8D);
            });
            defineSpec(builder, Specialization.GUN_JUNGLE_CLIP, values -> {
                values.put("reloadTime", 0.7D);
                values.put("magazine", 1.2D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.GUN_HOT_LOAD, values -> {
                values.put("damage", 1.25D);
                values.put("critDamage", 1.4D);
                values.put("magazine", 0.8D);
                values.put("reloadTime", 1.2D);
                values.put("bulletSpeed", 1.2D);
                values.put("critChance", 0.1D);
            });
            defineSpec(builder, Specialization.GUN_DRUM_MAGAZINE, values -> {
                values.put("magazine", 2.5D);
                values.put("reloadTime", 2.2D);
                values.put("moveSpeed", 0.9D);
            });
            defineSpec(builder, Specialization.GUN_VETERAN_MANUAL, values -> {
                values.put("xpGain", 1.8D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.GUN_FIELD_DRESSING, values -> {
                values.put("lifeSteal", 0.06D);
                values.put("killRefill", 0.2D);
                values.put("damage", 0.9D);
            });

            defineSpec(builder, Specialization.MELEE_WRAPPED_GRIP, values -> {
                values.put("attackInterval", 0.6D);
                values.put("moveSpeed", 1.1D);
                values.put("damage", 0.65D);
            });
            defineSpec(builder, Specialization.MELEE_WEIGHTED_HEAD, values -> {
                values.put("damage", 1.6D);
                values.put("critChance", 0.15D);
                values.put("critDamage", 1.8D);
                values.put("attackInterval", 1.6D);
                values.put("durability", 1.5D);
            });
            defineSpec(builder, Specialization.MELEE_HARDENED_TIP, values -> {
                values.put("armoredDamage", 1.9D);
                values.put("durability", 0.7D);
                values.put("unarmoredDamage", 0.75D);
            });
            defineSpec(builder, Specialization.MELEE_SERRATED_EDGE, values -> {
                values.put("executeDamage", 2.5D);
                values.put("executeThreshold", 0.3D);
                values.put("lifeSteal", 0.05D);
                values.put("freshDamage", 0.7D);
            });
            defineSpec(builder, Specialization.MELEE_BLOOD_GROOVE, values -> {
                values.put("lifeSteal", 0.15D);
                values.put("damage", 0.8D);
            });
            defineSpec(builder, Specialization.MELEE_KEEN_EDGE, values -> {
                values.put("comboStep", 0.08D);
                values.put("comboMax", 6.0D);
                values.put("damage", 0.8D);
            });
            defineSpec(builder, Specialization.MELEE_WHETSTONE, values -> {
                values.put("xpGain", 1.6D);
                values.put("durability", 0.5D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.MELEE_RECURVE_BLADE, values -> {
                values.put("critChance", 0.2D);
                values.put("critDamage", 1.5D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.MELEE_BALANCED_POMMEL, values -> {
                values.put("attackInterval", 0.9D);
                values.put("damage", 1.05D);
                values.put("durability", 1.4D);
            });
            defineSpec(builder, Specialization.MELEE_TRENCH_KNIFE, values -> {
                values.put("vsPlayerDamage", 1.5D);
                values.put("vsMobDamage", 0.85D);
            });
            defineSpec(builder, Specialization.MELEE_HUNTING_KNIFE, values -> {
                values.put("vsMobDamage", 1.3D);
                values.put("xpGain", 1.2D);
                values.put("vsPlayerDamage", 0.8D);
                values.put("durability", 1.25D);
            });
            defineSpec(builder, Specialization.MELEE_FORGED_STEEL, values -> {
                values.put("durability", 0.4D);
                values.put("damage", 1.1D);
                values.put("attackInterval", 1.15D);
            });
            defineSpec(builder, Specialization.MELEE_BLEEDER, values -> {
                values.put("executeDamage", 1.8D);
                values.put("executeThreshold", 0.4D);
                values.put("comboStep", 0.04D);
                values.put("comboMax", 6.0D);
                values.put("freshDamage", 0.85D);
            });
            defineSpec(builder, Specialization.MELEE_SHORT_GRIP, values -> {
                values.put("moveSpeed", 1.12D);
                values.put("damage", 0.85D);
            });

            defineSpec(builder, Specialization.BLADE_PHANTOM, values -> {
                values.put("summonedDamage", 2.0D);
                values.put("moveSpeed", 1.05D);
                values.put("directDamage", 0.6D);
            });
            defineSpec(builder, Specialization.BLADE_POLISH, values -> {
                values.put("directDamage", 1.5D);
                values.put("critChance", 0.15D);
                values.put("critDamage", 1.6D);
                values.put("summonedDamage", 0.5D);
            });
            defineSpec(builder, Specialization.BLADE_HAISUI, values -> {
                values.put("desperationDamage", 2.2D);
                values.put("desperationThreshold", 0.5D);
                values.put("desperationFloor", 0.3D);
                values.put("lifeSteal", 0.08D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.BLADE_SHINKEN, values -> {
                values.put("vsPlayerDamage", 1.6D);
                values.put("durability", 0.75D);
                values.put("vsMobDamage", 0.8D);
            });
            defineSpec(builder, Specialization.BLADE_SOUL_EATER, values -> {
                values.put("lifeSteal", 0.15D);
                values.put("damage", 0.8D);
            });
            defineSpec(builder, Specialization.BLADE_COMBO, values -> {
                values.put("comboStep", 0.08D);
                values.put("comboMax", 6.0D);
                values.put("damage", 0.8D);
            });
            defineSpec(builder, Specialization.BLADE_SHUGYO, values -> {
                values.put("xpGain", 1.6D);
                values.put("durability", 0.5D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.BLADE_IAI, values -> {
                values.put("freshDamage", 1.6D);
                values.put("critChance", 0.1D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.BLADE_MIKIRI, values -> {
                values.put("critChance", 0.25D);
                values.put("critDamage", 1.5D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.BLADE_ONIKOKU, values -> {
                values.put("summonedDamage", 1.5D);
                values.put("lifeSteal", 0.08D);
                values.put("directDamage", 0.8D);
            });
            defineSpec(builder, Specialization.BLADE_ZANTETSU, values -> {
                values.put("armoredDamage", 1.9D);
                values.put("unarmoredDamage", 0.8D);
            });
            defineSpec(builder, Specialization.BLADE_MUSOU, values -> {
                values.put("vsMobDamage", 1.3D);
                values.put("comboStep", 0.05D);
                values.put("comboMax", 8.0D);
                values.put("vsPlayerDamage", 0.8D);
            });
            defineSpec(builder, Specialization.BLADE_SHINSOKU, values -> {
                values.put("moveSpeed", 1.15D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.BLADE_MEITO, values -> {
                values.put("durability", 0.4D);
                values.put("damage", 1.1D);
                values.put("summonedDamage", 0.8D);
            });

            defineSpec(builder, Specialization.SPELL_FOCUS_CRYSTAL, values -> {
                values.put("damage", 1.35D);
                values.put("critChance", 0.1D);
                values.put("critDamage", 1.5D);
                values.put("manaCost", 1.3D);
            });
            defineSpec(builder, Specialization.SPELL_COOLDOWN_RUNE, values -> {
                values.put("spellCooldown", 0.7D);
                values.put("damage", 0.85D);
                values.put("castTime", 0.9D);
            });
            defineSpec(builder, Specialization.SPELL_THRIFTY_INK, values -> {
                values.put("manaCost", 0.65D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.SPELL_ARCANE_SEAL, values -> {
                values.put("spellLevel", 1.0D);
                values.put("manaCost", 1.3D);
                values.put("spellCooldown", 1.15D);
            });
            defineSpec(builder, Specialization.SPELL_SOUL_SIGIL, values -> {
                values.put("lifeSteal", 0.12D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.SPELL_CHAIN_BOOKMARK, values -> {
                values.put("comboStep", 0.06D);
                values.put("comboMax", 8.0D);
                values.put("damage", 0.8D);
            });
            defineSpec(builder, Specialization.SPELL_STUDY_NOTES, values -> {
                values.put("xpGain", 1.6D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.SPELL_SEARING_INK, values -> {
                values.put("damage", 1.2D);
                values.put("critChance", 0.1D);
                values.put("manaCost", 1.15D);
            });
            defineSpec(builder, Specialization.SPELL_SHORTHAND_RUNE, values -> {
                values.put("spellCooldown", 0.85D);
                values.put("manaCost", 0.85D);
                values.put("damage", 0.85D);
                values.put("castTime", 0.8D);
            });
            defineSpec(builder, Specialization.SPELL_HUNTER_CHAPTER, values -> {
                values.put("vsMobDamage", 1.3D);
                values.put("vsPlayerDamage", 0.8D);
                values.put("manaCost", 1.15D);
            });
            defineSpec(builder, Specialization.SPELL_DUEL_CHAPTER, values -> {
                values.put("vsPlayerDamage", 1.4D);
                values.put("vsMobDamage", 0.85D);
            });
            defineSpec(builder, Specialization.SPELL_EXECUTION_SEAL, values -> {
                values.put("executeDamage", 2.0D);
                values.put("executeThreshold", 0.3D);
                values.put("freshDamage", 0.8D);
            });
            defineSpec(builder, Specialization.SPELL_SUNDER_SEAL, values -> {
                values.put("armoredDamage", 1.7D);
                values.put("unarmoredDamage", 0.85D);
            });
            defineSpec(builder, Specialization.SPELL_DOUBLE_BINDING, values -> {
                values.put("spellLevel", 1.0D);
                values.put("damage", 0.9D);
                values.put("spellCooldown", 1.3D);
                values.put("castTime", 1.2D);
            });
            defineSpec(builder, Specialization.SPELL_ECHO_CRYSTAL, values -> {
                values.put("doubleCast", 1.0D);
                values.put("damage", 0.75D);
                values.put("manaCost", 1.5D);
                values.put("spellCooldown", 1.5D);
            });

            defineSpec(builder, Specialization.GLYPH_SOURCE_GEM, values -> {
                values.put("damage", 1.35D);
                values.put("critChance", 0.1D);
                values.put("critDamage", 1.5D);
                values.put("manaCost", 1.3D);
            });
            defineSpec(builder, Specialization.GLYPH_THRIFTY_QUILL, values -> {
                values.put("manaCost", 0.65D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.GLYPH_SOUL_RIBBON, values -> {
                values.put("lifeSteal", 0.12D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.GLYPH_CHAIN_RUNE, values -> {
                values.put("comboStep", 0.06D);
                values.put("comboMax", 8.0D);
                values.put("damage", 0.8D);
            });
            defineSpec(builder, Specialization.GLYPH_STUDY_NOTES, values -> {
                values.put("xpGain", 1.6D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.GLYPH_SEARING_INK, values -> {
                values.put("damage", 1.2D);
                values.put("critChance", 0.1D);
                values.put("manaCost", 1.15D);
            });
            defineSpec(builder, Specialization.GLYPH_HUNTER_PAGE, values -> {
                values.put("vsMobDamage", 1.3D);
                values.put("vsPlayerDamage", 0.8D);
                values.put("manaCost", 1.15D);
            });
            defineSpec(builder, Specialization.GLYPH_DUEL_PAGE, values -> {
                values.put("vsPlayerDamage", 1.4D);
                values.put("vsMobDamage", 0.85D);
            });
            defineSpec(builder, Specialization.GLYPH_EXECUTE_RUNE, values -> {
                values.put("executeDamage", 2.0D);
                values.put("executeThreshold", 0.3D);
                values.put("freshDamage", 0.8D);
            });
            defineSpec(builder, Specialization.GLYPH_SUNDER_RUNE, values -> {
                values.put("armoredDamage", 1.7D);
                values.put("unarmoredDamage", 0.85D);
            });

            defineSpec(builder, Specialization.SWORD_TEMPERED_EDGE, values -> {
                values.put("damage", 1.4D);
                values.put("critChance", 0.15D);
                values.put("critDamage", 1.6D);
                values.put("attackInterval", 1.25D);
                values.put("durability", 1.4D);
            });
            defineSpec(builder, Specialization.SWORD_LIGHT_BLADE, values -> {
                values.put("attackInterval", 0.75D);
                values.put("moveSpeed", 1.08D);
                values.put("damage", 0.75D);
            });
            defineSpec(builder, Specialization.SWORD_FULLER, values -> {
                values.put("lifeSteal", 0.15D);
                values.put("damage", 0.8D);
            });
            defineSpec(builder, Specialization.SWORD_SERRATED, values -> {
                values.put("executeDamage", 2.2D);
                values.put("executeThreshold", 0.3D);
                values.put("lifeSteal", 0.05D);
                values.put("freshDamage", 0.75D);
            });
            defineSpec(builder, Specialization.SWORD_ARMOR_PIERCER, values -> {
                values.put("armoredDamage", 1.8D);
                values.put("durability", 0.7D);
                values.put("unarmoredDamage", 0.8D);
            });
            defineSpec(builder, Specialization.SWORD_KEEN_EDGE, values -> {
                values.put("comboStep", 0.08D);
                values.put("comboMax", 6.0D);
                values.put("damage", 0.8D);
            });
            defineSpec(builder, Specialization.SWORD_WHETSTONE, values -> {
                values.put("xpGain", 1.6D);
                values.put("durability", 0.5D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.SWORD_FINE_STEEL, values -> {
                values.put("damage", 1.15D);
                values.put("durability", 0.7D);
                values.put("attackInterval", 1.1D);
            });
            defineSpec(builder, Specialization.SWORD_BALANCED_POMMEL, values -> {
                values.put("attackInterval", 0.9D);
                values.put("damage", 1.05D);
                values.put("durability", 1.4D);
            });
            defineSpec(builder, Specialization.SWORD_DUELIST, values -> {
                values.put("vsPlayerDamage", 1.5D);
                values.put("vsMobDamage", 0.85D);
            });
            defineSpec(builder, Specialization.SWORD_HUNTING_SWORD, values -> {
                values.put("vsMobDamage", 1.3D);
                values.put("xpGain", 1.2D);
                values.put("vsPlayerDamage", 0.8D);
                values.put("durability", 1.25D);
            });
            defineSpec(builder, Specialization.SWORD_RAPIER_POINT, values -> {
                values.put("critChance", 0.2D);
                values.put("critDamage", 1.5D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.SWORD_BLEEDER, values -> {
                values.put("executeDamage", 1.8D);
                values.put("executeThreshold", 0.4D);
                values.put("comboStep", 0.04D);
                values.put("comboMax", 6.0D);
                values.put("freshDamage", 0.85D);
            });
            defineSpec(builder, Specialization.SWORD_QUICK_SCABBARD, values -> {
                values.put("moveSpeed", 1.12D);
                values.put("damage", 0.85D);
            });

            defineSpec(builder, Specialization.BOW_HEAVY_LIMBS, values -> {
                values.put("damage", 1.35D);
                values.put("moveSpeed", 0.9D);
                values.put("drawSpeed", 0.9D);
            });
            defineSpec(builder, Specialization.BOW_FINE_STRING, values -> {
                values.put("critChance", 0.2D);
                values.put("critDamage", 1.5D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.BOW_HUNTER_QUIVER, values -> {
                values.put("vsMobDamage", 1.3D);
                values.put("xpGain", 1.2D);
                values.put("vsPlayerDamage", 0.8D);
                values.put("drawSpeed", 0.9D);
            });
            defineSpec(builder, Specialization.BOW_WAR_QUIVER, values -> {
                values.put("vsPlayerDamage", 1.4D);
                values.put("vsMobDamage", 0.85D);
            });
            defineSpec(builder, Specialization.BOW_BARBED_HEADS, values -> {
                values.put("executeDamage", 1.8D);
                values.put("executeThreshold", 0.4D);
                values.put("lifeSteal", 0.05D);
                values.put("freshDamage", 0.85D);
            });
            defineSpec(builder, Specialization.BOW_BODKIN_HEADS, values -> {
                values.put("armoredDamage", 1.8D);
                values.put("unarmoredDamage", 0.8D);
            });
            defineSpec(builder, Specialization.BOW_BOWSTRING_WAX, values -> {
                values.put("durability", 0.5D);
                values.put("xpGain", 1.4D);
                values.put("damage", 0.9D);
            });
            defineSpec(builder, Specialization.BOW_LIGHT_SHAFTS, values -> {
                values.put("bulletSpeed", 1.25D);
                values.put("drawSpeed", 1.3D);
                values.put("moveSpeed", 1.05D);
                values.put("damage", 0.85D);
            });
            defineSpec(builder, Specialization.BOW_RAPID_SHAFTS, values -> {
                values.put("comboStep", 0.05D);
                values.put("comboMax", 8.0D);
                values.put("drawSpeed", 1.15D);
                values.put("damage", 0.85D);
            });

            builder.pop();
            builder.pop();
        }
    }


    /**
     * 定义一个流派用到的数值项。
     *
     * <p>只暴露这个流派真正会用的项：把二十几个维度全列出来、其中二十个写着 1.0，
     * 服主打开配置文件只会一脸茫然。
     *
     * @param builder  配置构建器
     * @param spec     流派
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
     * 一个流派在配置文件里的数值项。
     *
     * <p>用「项名 -&gt; 配置值」的表而不是固定字段：不同来源的流派关心的维度完全不同，
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
         * @param fallback 这个流派没定义该项时的取值
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

        /** 切换流派的冷却秒数。 */
        public final int specSwitchCooldownSeconds;

        /** 第一流派槽的解锁进度（百分比）。 */
        public final int specSlot1Percent;

        /** 第二流派槽的解锁进度（百分比）。 */
        public final int specSlot2Percent;

        /** 流派键 -&gt; 数值。缺项按中性处理。 */
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
         * @param xpBase                    经验曲线基数
         * @param xpExponent                经验曲线指数
         * @param typeXpMultiplier          类型升级经验倍率
         * @param overallXpMultiplier       总体升级经验倍率
         * @param netherXpMultiplier        下界经验倍率
         * @param endXpMultiplier           末地经验倍率
         * @param moddedDimensionXpMultiplier 模组维度经验倍率
         * @param splitMeleeByPack          近战附属是否按刀包拆分类型
         * @param enableSpecialization      是否启用特化系统
         * @param specSwitchCooldownSeconds 切换流派的冷却秒数
         * @param specValues                流派数值表
         */
        public Snapshot(int maxLevel, double overallDamagePerLevel, double typeDamagePerLevel,
                        double weaponDamagePerLevel, double weaponCrossDamagePerLevel,
                        double maxTotalDamageBonus, double xpBase, double xpExponent,
                        double typeXpMultiplier, double overallXpMultiplier,
                        double netherXpMultiplier, double endXpMultiplier,
                        double moddedDimensionXpMultiplier, boolean splitMeleeByPack,
                        boolean enableSpecialization, int specSwitchCooldownSeconds,
                        int specSlot1Percent, int specSlot2Percent,
                        Map<String, SpecializationValues> specValues) {
            this.maxLevel = Math.max(1, maxLevel);
            this.overallDamagePerLevel = overallDamagePerLevel;
            this.typeDamagePerLevel = typeDamagePerLevel;
            this.weaponDamagePerLevel = weaponDamagePerLevel;
            this.weaponCrossDamagePerLevel = weaponCrossDamagePerLevel;
            this.maxTotalDamageBonus = maxTotalDamageBonus;
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
            // 指数相同、只放大基数，等于把每一级的需求整体乘上倍率
            this.weaponCurve = new LevelCurve(this.maxLevel, xpBase, xpExponent);
            this.typeCurve = new LevelCurve(this.maxLevel, xpBase * this.typeXpMultiplier, xpExponent);
            this.overallCurve = new LevelCurve(this.maxLevel, xpBase * this.overallXpMultiplier, xpExponent);
        }

        /**
         * 取一个流派的数值。
         *
         * @param spec 流派
         * @return 数值；表里没有（比如制式、或者服务端版本更老不认识这个流派）时返回中性值
         */
        /**
         * 某个流派槽要求的等级（外圈和中圈都得到）。
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
            return new Snapshot(100, 0.005D, 0.01D, 0.02D, 0.001D, 10.0D, 40.0D, 1.0D,
                    3.0D, 10.0D, 1.5D, 2.0D, 2.0D, true, true, 3600, 50, 100, Map.of());
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
            buf.writeVarInt(this.specValues.size());
            for (Map.Entry<String, SpecializationValues> entry : this.specValues.entrySet()) {
                buf.writeUtf(entry.getKey(), 128);
                entry.getValue().write(buf);
            }
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
            // 条数按枚举总量封顶，异常包不至于让客户端在这里分配一张巨表
            int specCount = Math.max(0, Math.min(Specialization.values().length, buf.readVarInt()));
            Map<String, SpecializationValues> specValues = new HashMap<>();
            for (int i = 0; i < specCount; i++) {
                String key = buf.readUtf(128);
                specValues.put(key, SpecializationValues.read(buf));
            }
            return new Snapshot(maxLevel, overallDmg, typeDmg, wpnDmg, crossDmg,
                    maxBonus, xpBase, xpExp, typeMul, overallMul, netherMul, endMul, moddedMul,
                    splitMelee, enableSpec, specCooldown, slot1, slot2, specValues);
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
