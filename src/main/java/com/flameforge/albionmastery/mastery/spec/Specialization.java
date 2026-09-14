package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.weapon.WeaponKeys;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 专精特化（流派）。
 *
 * <p>玩家把某个来源的<b>总体专精</b>练满之后，可以给这个来源选一个流派，
 * 效果作用于该来源下的所有武器。每个流派都是有得有失的：拿到什么就一定要付出什么，
 * 唯一的例外是「制式」——它什么都不改，也是默认值。
 *
 * <p>枚举名与 {@link #id} 是存进玩家存档的东西，<b>改名等于让老玩家的选择失效</b>，
 * 加新流派可以，改已有的 id 不行。
 */
public enum Specialization {

    /** 枪械 · 制式配置：不做任何改动。 */
    GUN_STANDARD(WeaponKeys.PROVIDER_TACZ_GUN, "standard"),

    /** 枪械 · 扩容弹匣：弹匣变大、击杀小补弹，换弹变慢、稍微拖脚。 */
    GUN_EXTENDED_MAG(WeaponKeys.PROVIDER_TACZ_GUN, "extended_mag"),

    /** 枪械 · 便携弹匣：换弹变快、跑得快，弹匣变小。 */
    GUN_COMPACT_MAG(WeaponKeys.PROVIDER_TACZ_GUN, "compact_mag"),

    /** 枪械 · 重型弹药：伤害暴涨、偶尔暴击，弹匣、换弹、移速全面劣化。 */
    GUN_HEAVY_AMMO(WeaponKeys.PROVIDER_TACZ_GUN, "heavy_ammo"),

    /** 枪械 · 精密枪管：爆头翻倍、带暴击，打身上反而不如从前。 */
    GUN_MATCH_BARREL(WeaponKeys.PROVIDER_TACZ_GUN, "match_barrel"),

    /** 枪械 · 空尖弹：残血目标伤害暴涨，击杀顺手补弹；对满血目标偏软。 */
    GUN_HOLLOW_POINT(WeaponKeys.PROVIDER_TACZ_GUN, "hollow_point"),

    /** 枪械 · 弹药带：击杀大量补弹、经验更多，代价是伤害略低。 */
    GUN_BANDOLIER(WeaponKeys.PROVIDER_TACZ_GUN, "bandolier"),

    /** 枪械 · 汲血弹：打中就回血，伤害和弹匣都缩水。 */
    GUN_SIPHON_ROUNDS(WeaponKeys.PROVIDER_TACZ_GUN, "siphon_rounds"),

    /** 枪械 · 轻量化枪身：跑得快、换弹快，伤害和弹匣都缩水。 */
    GUN_LIGHT_FRAME(WeaponKeys.PROVIDER_TACZ_GUN, "light_frame"),

    /** 枪械 · 曳光弹：连续命中同一目标越打越狠，首发偏软。 */
    GUN_TRACER_ROUNDS(WeaponKeys.PROVIDER_TACZ_GUN, "tracer_rounds"),

    /** 枪械 · 穿甲弹：专打披甲目标，对无甲目标偏软。 */
    GUN_ARMOR_PIERCING(WeaponKeys.PROVIDER_TACZ_GUN, "armor_piercing"),

    /** 枪械 · 软尖弹：专打无甲目标，打护甲吃亏。 */
    GUN_SOFT_POINT(WeaponKeys.PROVIDER_TACZ_GUN, "soft_point"),

    /** 枪械 · 消音器：冷枪暴击，代价是基础伤害略低。 */
    GUN_SUPPRESSOR(WeaponKeys.PROVIDER_TACZ_GUN, "suppressor"),

    /** 枪械 · 枪口制退器：压得住枪，连射越打越准。 */
    GUN_MUZZLE_BRAKE(WeaponKeys.PROVIDER_TACZ_GUN, "muzzle_brake"),

    /** 枪械 · 战术握把：机动更好，弹匣略小。 */
    GUN_TACTICAL_GRIP(WeaponKeys.PROVIDER_TACZ_GUN, "tactical_grip"),

    /** 枪械 · 双联弹匣：换弹快、弹匣大，伤害略低。 */
    GUN_JUNGLE_CLIP(WeaponKeys.PROVIDER_TACZ_GUN, "jungle_clip"),

    /** 枪械 · 高压装药：单发更狠，弹匣和换弹都吃亏。 */
    GUN_HOT_LOAD(WeaponKeys.PROVIDER_TACZ_GUN, "hot_load"),

    /** 枪械 · 弹鼓：一梭子打到底，换弹和机动都重。 */
    GUN_DRUM_MAGAZINE(WeaponKeys.PROVIDER_TACZ_GUN, "drum_magazine"),

    /** 枪械 · 老兵手册：练得快，打得略轻。 */
    GUN_VETERAN_MANUAL(WeaponKeys.PROVIDER_TACZ_GUN, "veteran_manual"),

    /** 枪械 · 战地绷带：边打边回、边杀边补。 */
    GUN_FIELD_DRESSING(WeaponKeys.PROVIDER_TACZ_GUN, "field_dressing"),

    /** 近战 · 制式。 */
    MELEE_STANDARD(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "standard"),

    /** 近战 · 缠柄：出手快、跑得快，单次伤害低。 */
    MELEE_WRAPPED_GRIP(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "wrapped_grip"),

    /** 近战 · 加重刀头：伤害高、带暴击，出手慢、费耐久。 */
    MELEE_WEIGHTED_HEAD(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "weighted_head"),

    /** 近战 · 淬硬尖锋：专打披甲目标、省耐久，对没有护甲的目标反而更弱。 */
    MELEE_HARDENED_TIP(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "hardened_tip"),

    /** 近战 · 锯齿刃：残血目标一刀带走并小幅回血，满血目标砍不动。 */
    MELEE_SERRATED_EDGE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "serrated_edge"),

    /** 近战 · 血槽：砍中就回血，代价是伤害偏低。 */
    MELEE_BLOOD_GROOVE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "blood_groove"),

    /** 近战 · 开刃：连续砍同一目标伤害递增，首刀偏软。 */
    MELEE_KEEN_EDGE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "keen_edge"),

    /** 近战 · 磨石：经验更多、武器更耐用，代价是伤害略低。 */
    MELEE_WHETSTONE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "whetstone"),

    /** 近战 · 反曲刀刃：一刀见血靠运气，基础略软。 */
    MELEE_RECURVE_BLADE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "recurve_blade"),

    /** 近战 · 平衡配重：又快又狠，费刀。 */
    MELEE_BALANCED_POMMEL(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "balanced_pommel"),

    /** 近战 · 战壕刀：对人狠，对怪软。 */
    MELEE_TRENCH_KNIFE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "trench_knife"),

    /** 近战 · 猎刀：打怪快、练得快，打人不行。 */
    MELEE_HUNTING_KNIFE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "hunting_knife"),

    /** 近战 · 重锻钢：耐用又有力，出手慢。 */
    MELEE_FORGED_STEEL(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "forged_steel"),

    /** 近战 · 放血刃：越砍越狠，收尾快。 */
    MELEE_BLEEDER(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "bleeder"),

    /** 近战 · 短柄：轻便好跑，砍得轻。 */
    MELEE_SHORT_GRIP(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "short_grip"),

    /** 拔刀剑 · 制式。 */
    BLADE_STANDARD(WeaponKeys.PROVIDER_SLASHBLADE, "standard"),

    /** 拔刀剑 · 幻影剑：召唤剑伤害翻倍、略快，刀身直接攻击削弱。 */
    BLADE_PHANTOM(WeaponKeys.PROVIDER_SLASHBLADE, "phantom"),

    /** 拔刀剑 · 研磨：刀身直接攻击强化、带暴击，召唤剑削弱。 */
    BLADE_POLISH(WeaponKeys.PROVIDER_SLASHBLADE, "polish"),

    /** 拔刀剑 · 背水：自己血越少打得越狠、砍中回血，代价是平时软一截。 */
    BLADE_HAISUI(WeaponKeys.PROVIDER_SLASHBLADE, "haisui"),

    /** 拔刀剑 · 真剑：对玩家强、刀更耐用，对怪物弱，PVP 服专用。 */
    BLADE_SHINKEN(WeaponKeys.PROVIDER_SLASHBLADE, "shinken"),

    /** 拔刀剑 · 噬魂：砍中就回血，代价是伤害偏低。 */
    BLADE_SOUL_EATER(WeaponKeys.PROVIDER_SLASHBLADE, "soul_eater"),

    /** 拔刀剑 · 连斩：连续砍同一目标伤害递增，首刀偏软。 */
    BLADE_COMBO(WeaponKeys.PROVIDER_SLASHBLADE, "combo"),

    /** 拔刀剑 · 修行：经验更多、刀更耐用，代价是伤害略低。 */
    BLADE_SHUGYO(WeaponKeys.PROVIDER_SLASHBLADE, "shugyo"),

    /** 拔刀剑 · 居合：第一刀最狠。 */
    BLADE_IAI(WeaponKeys.PROVIDER_SLASHBLADE, "iai"),

    /** 拔刀剑 · 见切：看破破绽，暴击极高。 */
    BLADE_MIKIRI(WeaponKeys.PROVIDER_SLASHBLADE, "mikiri"),

    /** 拔刀剑 · 鬼哭：幻影剑吸魂，刀身偏软。 */
    BLADE_ONIKOKU(WeaponKeys.PROVIDER_SLASHBLADE, "onikoku"),

    /** 拔刀剑 · 斩铁：专破重甲。 */
    BLADE_ZANTETSU(WeaponKeys.PROVIDER_SLASHBLADE, "zantetsu"),

    /** 拔刀剑 · 无双：割草越割越快，对人不行。 */
    BLADE_MUSOU(WeaponKeys.PROVIDER_SLASHBLADE, "musou"),

    /** 拔刀剑 · 神速：身法快，刀轻。 */
    BLADE_SHINSOKU(WeaponKeys.PROVIDER_SLASHBLADE, "shinsoku"),

    /** 拔刀剑 · 名刀：刀身好，不靠幻影。 */
    BLADE_MEITO(WeaponKeys.PROVIDER_SLASHBLADE, "meito"),

    /** 法术 · 制式：什么都不改。 */
    SPELL_STANDARD(WeaponKeys.PROVIDER_IRONS_SPELLS, "standard"),

    /** 法术 · 聚能水晶：伤害更高、带暴击，法力更费。 */
    SPELL_FOCUS_CRYSTAL(WeaponKeys.PROVIDER_IRONS_SPELLS, "focus_crystal"),

    /** 法术 · 冷却符文：冷却更短，伤害略低。 */
    SPELL_COOLDOWN_RUNE(WeaponKeys.PROVIDER_IRONS_SPELLS, "cooldown_rune"),

    /** 法术 · 节流墨水：法力更省，伤害略低。 */
    SPELL_THRIFTY_INK(WeaponKeys.PROVIDER_IRONS_SPELLS, "thrifty_ink"),

    /** 法术 · 高阶咒印：法术按高一级施放，法力和冷却都变贵。 */
    SPELL_ARCANE_SEAL(WeaponKeys.PROVIDER_IRONS_SPELLS, "arcane_seal"),

    /** 法术 · 吸魂纹章：造成伤害回血，伤害略低。 */
    SPELL_SOUL_SIGIL(WeaponKeys.PROVIDER_IRONS_SPELLS, "soul_sigil"),

    /** 法术 · 连咏书签：连续命中同一目标伤害递增，首发偏软。 */
    SPELL_CHAIN_BOOKMARK(WeaponKeys.PROVIDER_IRONS_SPELLS, "chain_bookmark"),

    /** 法术 · 研习笔记：经验更多，伤害略低。 */
    SPELL_STUDY_NOTES(WeaponKeys.PROVIDER_IRONS_SPELLS, "study_notes"),

    /** 法术 · 灼热墨水：更狠一点，更费一点。 */
    SPELL_SEARING_INK(WeaponKeys.PROVIDER_IRONS_SPELLS, "searing_ink"),

    /** 法术 · 速记符文：又省又快，威力略低。 */
    SPELL_SHORTHAND_RUNE(WeaponKeys.PROVIDER_IRONS_SPELLS, "shorthand_rune"),

    /** 法术 · 猎魔章节：打怪专用。 */
    SPELL_HUNTER_CHAPTER(WeaponKeys.PROVIDER_IRONS_SPELLS, "hunter_chapter"),

    /** 法术 · 决斗章节：对人专用。 */
    SPELL_DUEL_CHAPTER(WeaponKeys.PROVIDER_IRONS_SPELLS, "duel_chapter"),

    /** 法术 · 处决咒印：残血收割。 */
    SPELL_EXECUTION_SEAL(WeaponKeys.PROVIDER_IRONS_SPELLS, "execution_seal"),

    /** 法术 · 破甲咒印：专破重甲。 */
    SPELL_SUNDER_SEAL(WeaponKeys.PROVIDER_IRONS_SPELLS, "sunder_seal"),

    /** 法术 · 双重装帧：等级换冷却。 */
    SPELL_DOUBLE_BINDING(WeaponKeys.PROVIDER_IRONS_SPELLS, "double_binding"),

    /** 法术 · 回响水晶：每次施放必定免费再放一次，代价是强度、法力和冷却。 */
    SPELL_ECHO_CRYSTAL(WeaponKeys.PROVIDER_IRONS_SPELLS, "echo_crystal"),

    /** 符文 · 制式：什么都不改。 */
    GLYPH_STANDARD(WeaponKeys.PROVIDER_ARS_NOUVEAU, "standard"),

    /** 符文 · 魔源结晶：强度更高、带暴击，法力更费。 */
    GLYPH_SOURCE_GEM(WeaponKeys.PROVIDER_ARS_NOUVEAU, "source_gem"),

    /** 符文 · 节流羽笔：法力更省，强度略低。 */
    GLYPH_THRIFTY_QUILL(WeaponKeys.PROVIDER_ARS_NOUVEAU, "thrifty_quill"),

    /** 符文 · 噬魂书带：造成伤害回血，强度略低。 */
    GLYPH_SOUL_RIBBON(WeaponKeys.PROVIDER_ARS_NOUVEAU, "soul_ribbon"),

    /** 符文 · 连锁符文：连续命中同一目标伤害递增，首发偏软。 */
    GLYPH_CHAIN_RUNE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "chain_rune"),

    /** 符文 · 研习笔记：经验更多，强度略低。 */
    GLYPH_STUDY_NOTES(WeaponKeys.PROVIDER_ARS_NOUVEAU, "study_notes"),

    /** 符文 · 灼热墨水：更狠一点，更费一点。 */
    GLYPH_SEARING_INK(WeaponKeys.PROVIDER_ARS_NOUVEAU, "searing_ink"),

    /** 符文 · 猎魔书页：打怪专用。 */
    GLYPH_HUNTER_PAGE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "hunter_page"),

    /** 符文 · 决斗书页：对人专用。 */
    GLYPH_DUEL_PAGE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "duel_page"),

    /** 符文 · 处决符文：残血收割。 */
    GLYPH_EXECUTE_RUNE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "execute_rune"),

    /** 符文 · 破甲符文：专破重甲。 */
    GLYPH_SUNDER_RUNE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "sunder_rune"),

    /** 剑 · 制式：什么都不改。 */
    SWORD_STANDARD(WeaponKeys.PROVIDER_VANILLA_MELEE, "standard"),

    /** 剑 · 淬火刃：伤害高、带暴击，出手慢、费耐久。 */
    SWORD_TEMPERED_EDGE(WeaponKeys.PROVIDER_VANILLA_MELEE, "tempered_edge"),

    /** 剑 · 轻剑身：出手快、跑得快，伤害低。 */
    SWORD_LIGHT_BLADE(WeaponKeys.PROVIDER_VANILLA_MELEE, "light_blade"),

    /** 剑 · 血槽：砍中就回血，伤害偏低。 */
    SWORD_FULLER(WeaponKeys.PROVIDER_VANILLA_MELEE, "fuller"),

    /** 剑 · 锯齿刃：残血目标一刀带走并小幅回血，满血目标砍不动。 */
    SWORD_SERRATED(WeaponKeys.PROVIDER_VANILLA_MELEE, "serrated"),

    /** 剑 · 破甲尖：专打披甲目标、省耐久，对没有护甲的目标反而更弱。 */
    SWORD_ARMOR_PIERCER(WeaponKeys.PROVIDER_VANILLA_MELEE, "armor_piercer"),

    /** 剑 · 开刃：连续砍同一目标伤害递增，首刀偏软。 */
    SWORD_KEEN_EDGE(WeaponKeys.PROVIDER_VANILLA_MELEE, "keen_edge"),

    /** 剑 · 磨石：经验更多、更耐用，伤害略低。 */
    SWORD_WHETSTONE(WeaponKeys.PROVIDER_VANILLA_MELEE, "whetstone"),

    /** 剑 · 精钢剑身：好钢，略沉。 */
    SWORD_FINE_STEEL(WeaponKeys.PROVIDER_VANILLA_MELEE, "fine_steel"),

    /** 剑 · 平衡配重：又快又狠，费剑。 */
    SWORD_BALANCED_POMMEL(WeaponKeys.PROVIDER_VANILLA_MELEE, "balanced_pommel"),

    /** 剑 · 决斗剑：对人狠，对怪软。 */
    SWORD_DUELIST(WeaponKeys.PROVIDER_VANILLA_MELEE, "duelist"),

    /** 剑 · 猎剑：打怪快、练得快。 */
    SWORD_HUNTING_SWORD(WeaponKeys.PROVIDER_VANILLA_MELEE, "hunting_sword"),

    /** 剑 · 刺剑尖：一击入要害。 */
    SWORD_RAPIER_POINT(WeaponKeys.PROVIDER_VANILLA_MELEE, "rapier_point"),

    /** 剑 · 放血刃：越砍越狠，收尾快。 */
    SWORD_BLEEDER(WeaponKeys.PROVIDER_VANILLA_MELEE, "bleeder"),

    /** 剑 · 疾行剑鞘：轻便好跑，砍得轻。 */
    SWORD_QUICK_SCABBARD(WeaponKeys.PROVIDER_VANILLA_MELEE, "quick_scabbard"),

    /** 弓弩 · 制式：什么都不改。 */
    BOW_STANDARD(WeaponKeys.PROVIDER_BOW, "standard"),
    /** 弓弩 · 加重弓臂：劲大，沉。 */
    BOW_HEAVY_LIMBS(WeaponKeys.PROVIDER_BOW, "heavy_limbs"),
    /** 弓弩 · 精制弓弦：准，靠运气。 */
    BOW_FINE_STRING(WeaponKeys.PROVIDER_BOW, "fine_string"),
    /** 弓弩 · 猎手箭囊：打猎用。 */
    BOW_HUNTER_QUIVER(WeaponKeys.PROVIDER_BOW, "hunter_quiver"),
    /** 弓弩 · 战场箭囊：打人用。 */
    BOW_WAR_QUIVER(WeaponKeys.PROVIDER_BOW, "war_quiver"),
    /** 弓弩 · 倒钩箭头：残血收割。 */
    BOW_BARBED_HEADS(WeaponKeys.PROVIDER_BOW, "barbed_heads"),
    /** 弓弩 · 破甲箭头：专破重甲。 */
    BOW_BODKIN_HEADS(WeaponKeys.PROVIDER_BOW, "bodkin_heads"),
    /** 弓弩 · 弓弦蜡：耐用，练得快。 */
    BOW_BOWSTRING_WAX(WeaponKeys.PROVIDER_BOW, "bowstring_wax"),
    /** 弓弩 · 轻质箭杆：又快又飘。 */
    BOW_LIGHT_SHAFTS(WeaponKeys.PROVIDER_BOW, "light_shafts"),
    /** 弓弩 · 速射箭杆：连发越射越准。 */
    BOW_RAPID_SHAFTS(WeaponKeys.PROVIDER_BOW, "rapid_shafts");

    /** 所属来源 ID，同时也是该来源的总体专精键。 */
    private final String sourceId;

    /** 流派标识，在同一个来源内唯一。 */
    private final String id;

    /** 存档与网络传输用的完整键。 */
    private final String key;

    Specialization(String sourceId, String id) {
        this.sourceId = sourceId;
        this.id = id;
        this.key = sourceId + "|" + id;
    }

    /**
     * @return 所属来源 ID
     */
    public String sourceId() {
        return this.sourceId;
    }

    /**
     * @return 流派标识
     */
    public String id() {
        return this.id;
    }

    /**
     * @return 存档与网络用的完整键
     */
    public String key() {
        return this.key;
    }

    /**
     * @return 是否是「什么都不改」的制式
     */
    public boolean isStandard() {
        return "standard".equals(this.id);
    }

    /**
     * @return 名称的语言键
     */
    public String nameKey() {
        return "spec.albionmastery." + this.sourceId + "." + this.id;
    }

    /**
     * @return 「得到什么」的语言键；制式用它写一句「不做任何改动」
     */
    public String gainKey() {
        return "spec.albionmastery." + this.sourceId + "." + this.id + ".gain";
    }

    /**
     * @return 「付出什么」的语言键。制式没有代价，不会用到这个键
     */
    public String costKey() {
        return "spec.albionmastery." + this.sourceId + "." + this.id + ".cost";
    }

    /**
     * @return 配置文件里这个流派的小节名
     */
    public String configSection() {
        return this.sourceId + "_" + this.id;
    }

    /**
     * 列出某个来源下的全部流派，制式排在第一个。
     *
     * @param sourceId 来源 ID
     * @return 流派列表，来源不认识时返回空列表
     */
    public static List<Specialization> forSource(String sourceId) {
        List<Specialization> list = new ArrayList<>(4);
        for (Specialization spec : values()) {
            if (spec.sourceId.equals(sourceId)) {
                list.add(spec);
            }
        }
        return list;
    }

    /**
     * 取某个来源的默认流派（制式）。
     *
     * @param sourceId 来源 ID
     * @return 制式流派；来源不认识时返回 null
     */
    @Nullable
    public static Specialization standardOf(String sourceId) {
        for (Specialization spec : values()) {
            if (spec.sourceId.equals(sourceId) && spec.isStandard()) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 按完整键查流派。
     *
     * @param key 完整键
     * @return 流派；查不到时返回 null
     */
    @Nullable
    public static Specialization byKey(@Nullable String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (Specialization spec : values()) {
            if (spec.key.equals(key)) {
                return spec;
            }
        }
        return null;
    }
}
