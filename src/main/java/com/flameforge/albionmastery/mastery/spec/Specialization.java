package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.weapon.WeaponKeys;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 专精特化。
 *
 * <p>玩家把某个来源的<b>总体专精</b>练满之后，可以给这个来源选一个特化，
 * 效果作用于该来源下的所有武器。每个特化都是有得有失的：拿到什么就一定要付出什么，
 * 唯一的例外是「制式」——它什么都不改，也是默认值。
 *
 * <p>枚举名与 {@link #id} 是存进玩家存档的东西，<b>改名等于让老玩家的选择失效</b>，
 * 加新特化可以，改已有的 id 不行（显示名在语言文件里，可以随便改）。
 *
 * <p>每个特化属于一个系别（{@link #family}），两个槽不能装同系的特化；同一来源内按系别排列，
 * 界面上也按这个顺序分组显示。
 */
public enum Specialization {

    // ───────────── 枪械：按改装部位分系，两个槽只能各装一个部位 ─────────────

    /** 枪械 · 制式配置：不做任何改动。 */
    GUN_STANDARD(WeaponKeys.PROVIDER_TACZ_GUN, "standard", ""),

    /** 弹匣 · 扩容弹匣：装得多、击杀小补弹，换弹和开镜变慢。 */
    GUN_EXTENDED_MAG(WeaponKeys.PROVIDER_TACZ_GUN, "extended_mag", "magazine"),
    /** 弹匣 · 便携弹匣：换弹、掏枪快，弹匣小。 */
    GUN_COMPACT_MAG(WeaponKeys.PROVIDER_TACZ_GUN, "compact_mag", "magazine"),
    /** 弹匣 · 双联弹匣：换弹快、弹匣略大，枪更沉。 */
    GUN_JUNGLE_CLIP(WeaponKeys.PROVIDER_TACZ_GUN, "jungle_clip", "magazine"),
    /** 弹匣 · 弹鼓：一梭子打到底，换弹、掏枪、开镜、机动都重。 */
    GUN_DRUM_MAGAZINE(WeaponKeys.PROVIDER_TACZ_GUN, "drum_magazine", "magazine"),
    /** 弹匣 · 压底重弹：弹匣最后几发是重弹，越打到后面越狠。 */
    GUN_BOTTOM_LOAD(WeaponKeys.PROVIDER_TACZ_GUN, "bottom_load", "magazine"),

    /** 弹药 · 重型弹药：单发伤害翻倍，弹匣、换弹、后座、射速全面劣化。 */
    GUN_HEAVY_AMMO(WeaponKeys.PROVIDER_TACZ_GUN, "heavy_ammo", "ammo"),
    /** 弹药 · 空尖弹：残血收割，打满血目标偏软。 */
    GUN_HOLLOW_POINT(WeaponKeys.PROVIDER_TACZ_GUN, "hollow_point", "ammo"),
    /** 弹药 · 汲血弹：打中就回血，伤害和弹匣缩水。 */
    GUN_SIPHON_ROUNDS(WeaponKeys.PROVIDER_TACZ_GUN, "siphon_rounds", "ammo"),
    /** 弹药 · 曳光弹：连续命中越打越狠，腰射更好修正。 */
    GUN_TRACER_ROUNDS(WeaponKeys.PROVIDER_TACZ_GUN, "tracer_rounds", "ammo"),
    /** 弹药 · 穿甲弹：专打披甲目标。 */
    GUN_ARMOR_PIERCING(WeaponKeys.PROVIDER_TACZ_GUN, "armor_piercing", "ammo"),
    /** 弹药 · 软尖弹：专打无甲目标，打身体更疼。 */
    GUN_SOFT_POINT(WeaponKeys.PROVIDER_TACZ_GUN, "soft_point", "ammo"),
    /** 弹药 · 高压装药：更狠更快，更难压。 */
    GUN_HOT_LOAD(WeaponKeys.PROVIDER_TACZ_GUN, "hot_load", "ammo"),
    /** 弹药 · 钨芯贯穿弹：一发打穿好几个，单发偏软。 */
    GUN_PENETRATOR(WeaponKeys.PROVIDER_TACZ_GUN, "penetrator", "ammo"),
    /** 弹药 · 燃烧弹：打中有几率点燃。 */
    GUN_INCENDIARY(WeaponKeys.PROVIDER_TACZ_GUN, "incendiary", "ammo"),

    /** 枪口 · 精密枪管：爆头和开镜精度，打身上吃亏。 */
    GUN_MATCH_BARREL(WeaponKeys.PROVIDER_TACZ_GUN, "match_barrel", "muzzle"),
    /** 枪口 · 消音器：安静、冷枪暴击，亚音速弹飞得慢。 */
    GUN_SUPPRESSOR(WeaponKeys.PROVIDER_TACZ_GUN, "suppressor", "muzzle"),
    /** 枪口 · 枪口制退器：压枪、腰射稳，很吵。 */
    GUN_MUZZLE_BRAKE(WeaponKeys.PROVIDER_TACZ_GUN, "muzzle_brake", "muzzle"),

    /** 枪机 · 高速枪机：射速拉满，更难压。 */
    GUN_RAPID_CYCLE(WeaponKeys.PROVIDER_TACZ_GUN, "rapid_cycle", "action"),
    /** 枪机 · 重型缓冲器：打得慢，每发更狠更稳。 */
    GUN_HEAVY_BUFFER(WeaponKeys.PROVIDER_TACZ_GUN, "heavy_buffer", "action"),
    /** 枪机 · 直拉枪机：栓动枪拉栓快一倍。 */
    GUN_STRAIGHT_PULL(WeaponKeys.PROVIDER_TACZ_GUN, "straight_pull", "action"),

    /** 枪身 · 战术握把：端枪稳、开镜快。 */
    GUN_TACTICAL_GRIP(WeaponKeys.PROVIDER_TACZ_GUN, "tactical_grip", "furniture"),
    /** 枪身 · 精确射手枪托：开镜又快又准，腰射和机动吃亏。 */
    GUN_MARKSMAN_STOCK(WeaponKeys.PROVIDER_TACZ_GUN, "marksman_stock", "furniture"),
    /** 枪身 · 轻量化枪身：跑得快、冲刺转射击快，枪飘、伤害低。 */
    GUN_LIGHT_FRAME(WeaponKeys.PROVIDER_TACZ_GUN, "light_frame", "furniture"),

    /** 训练 · 腰射训练：不开镜也准，开镜反而笨拙。 */
    GUN_POINT_SHOOTING(WeaponKeys.PROVIDER_TACZ_GUN, "point_shooting", "training"),
    /** 训练 · 老兵手册：练得快、换弹熟练。 */
    GUN_VETERAN_MANUAL(WeaponKeys.PROVIDER_TACZ_GUN, "veteran_manual", "training"),
    /** 训练 · 弹药纪律：打得省，打得慢。 */
    GUN_AMMO_DISCIPLINE(WeaponKeys.PROVIDER_TACZ_GUN, "ammo_discipline", "training"),
    /** 训练 · 破门战术：贴脸打得狠，远了打不动。 */
    GUN_BREACHER(WeaponKeys.PROVIDER_TACZ_GUN, "breacher", "training"),
    /** 训练 · 远距射术：越远越狠，近身吃亏。 */
    GUN_LONGSHOT(WeaponKeys.PROVIDER_TACZ_GUN, "longshot", "training"),

    /** 装具 · 弹药带：击杀大量补弹。 */
    GUN_BANDOLIER(WeaponKeys.PROVIDER_TACZ_GUN, "bandolier", "kit"),
    /** 装具 · 防弹插板（原「战地绷带」，id 不变）：挨打少掉血，跑不动。 */
    GUN_FIELD_DRESSING(WeaponKeys.PROVIDER_TACZ_GUN, "field_dressing", "kit"),
    /** 装具 · 快拔枪套：掏枪、冲刺转射击都快。 */
    GUN_QUICKDRAW(WeaponKeys.PROVIDER_TACZ_GUN, "quickdraw", "kit"),
    /** 装具 · 肾上腺素：连杀越打越狠、杀一个回一口血，挨打更疼。 */
    GUN_ADRENALINE(WeaponKeys.PROVIDER_TACZ_GUN, "adrenaline", "kit"),

    // ───────────── 近战刀包：握柄 / 刀身 / 刃口 / 战法 / 保养 ─────────────

    /** 近战 · 制式。 */
    MELEE_STANDARD(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "standard", ""),

    /** 握柄 · 缠柄：出手最快，单刀最轻。 */
    MELEE_WRAPPED_GRIP(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "wrapped_grip", "handle"),
    /** 握柄 · 反握短柄：背刺专精。 */
    MELEE_SHORT_GRIP(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "short_grip", "handle"),
    /** 握柄 · 平衡配重：快一点、推得开，费刀。 */
    MELEE_BALANCED_POMMEL(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "balanced_pommel", "handle"),

    /** 刀身 · 加重刀头：又重又狠，出手慢。 */
    MELEE_WEIGHTED_HEAD(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "weighted_head", "blade"),
    /** 刀身 · 淬硬尖锋：专打披甲目标。 */
    MELEE_HARDENED_TIP(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "hardened_tip", "blade"),
    /** 刀身 · 重锻钢：几乎不磨损。 */
    MELEE_FORGED_STEEL(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "forged_steel", "blade"),

    /** 刃口 · 锯齿刃：残血收割。 */
    MELEE_SERRATED_EDGE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "serrated_edge", "edge"),
    /** 刃口 · 开刃：连击。 */
    MELEE_KEEN_EDGE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "keen_edge", "edge"),
    /** 刃口 · 反曲刀刃：暴击。 */
    MELEE_RECURVE_BLADE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "recurve_blade", "edge"),
    /** 刃口 · 血槽：吸血。 */
    MELEE_BLOOD_GROOVE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "blood_groove", "edge"),
    /** 刃口 · 淬毒刃：砍中让目标虚弱、缓慢。 */
    MELEE_VENOM_EDGE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "venom_edge", "edge"),

    /** 战法 · 偷袭刀法（原「放血刃」，id 不变）：先手、背后下刀。 */
    MELEE_BLEEDER(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "bleeder", "tactic"),
    /** 战法 · 战壕刀：对人。 */
    MELEE_TRENCH_KNIFE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "trench_knife", "tactic"),
    /** 战法 · 猎刀：对怪。 */
    MELEE_HUNTING_KNIFE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "hunting_knife", "tactic"),
    /** 战法 · 嗜血本能：连杀叠伤害，费刀。 */
    MELEE_BLOODLUST(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "bloodlust", "tactic"),

    /** 保养 · 磨石：练得快、耐用。 */
    MELEE_WHETSTONE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "whetstone", "care"),

    // ───────────── 拔刀剑：刀装 / 幻影 / 剑技 / 心法 ─────────────

    /** 拔刀剑 · 制式。 */
    BLADE_STANDARD(WeaponKeys.PROVIDER_SLASHBLADE, "standard", ""),

    /** 刀装 · 研磨：刀身直斩强化，幻影剑削弱。 */
    BLADE_POLISH(WeaponKeys.PROVIDER_SLASHBLADE, "polish", "fitting"),
    /** 刀装 · 名刀：拔刀技强化、刀更耐用。 */
    BLADE_MEITO(WeaponKeys.PROVIDER_SLASHBLADE, "meito", "fitting"),
    /** 刀装 · 斩铁：专破重甲。 */
    BLADE_ZANTETSU(WeaponKeys.PROVIDER_SLASHBLADE, "zantetsu", "fitting"),
    /** 刀装 · 雷切：斩中让目标麻痹（缓慢），SA 更强，幻影剑偏弱。 */
    BLADE_RAIKIRI(WeaponKeys.PROVIDER_SLASHBLADE, "raikiri", "fitting"),

    /** 幻影 · 幻影剑：召唤剑伤害翻倍，刀身削弱。 */
    BLADE_PHANTOM(WeaponKeys.PROVIDER_SLASHBLADE, "phantom", "phantom"),
    /** 幻影 · 鬼哭：召唤剑强化、刀魂获取多。 */
    BLADE_ONIKOKU(WeaponKeys.PROVIDER_SLASHBLADE, "onikoku", "phantom"),

    /** 剑技 · 居合：第一刀最狠。 */
    BLADE_IAI(WeaponKeys.PROVIDER_SLASHBLADE, "iai", "art"),
    /** 剑技 · 见切：看破破绽，暴击高、挨打少。 */
    BLADE_MIKIRI(WeaponKeys.PROVIDER_SLASHBLADE, "mikiri", "art"),
    /** 剑技 · 连斩：连击。 */
    BLADE_COMBO(WeaponKeys.PROVIDER_SLASHBLADE, "combo", "art"),
    /** 剑技 · 无双：割草。 */
    BLADE_MUSOU(WeaponKeys.PROVIDER_SLASHBLADE, "musou", "art"),

    /** 心法 · 背水：血越少越狠。 */
    BLADE_HAISUI(WeaponKeys.PROVIDER_SLASHBLADE, "haisui", "mind"),
    /** 心法 · 真剑：对人。 */
    BLADE_SHINKEN(WeaponKeys.PROVIDER_SLASHBLADE, "shinken", "mind"),
    /** 心法 · 修行：练得快、刀魂多。 */
    BLADE_SHUGYO(WeaponKeys.PROVIDER_SLASHBLADE, "shugyo", "mind"),
    /** 心法 · 神速：身法快、难打中。 */
    BLADE_SHINSOKU(WeaponKeys.PROVIDER_SLASHBLADE, "shinsoku", "mind"),
    /** 心法 · 噬魂：吸血。 */
    BLADE_SOUL_EATER(WeaponKeys.PROVIDER_SLASHBLADE, "soul_eater", "mind"),
    /** 心法 · 修罗：杀得越多越狠，挨打更疼。 */
    BLADE_SHURA(WeaponKeys.PROVIDER_SLASHBLADE, "shura", "mind"),

    // ───────────── 铁魔法：媒介 / 墨水 / 符文 / 章节 / 咒印 ─────────────

    /** 法术 · 制式：什么都不改。 */
    SPELL_STANDARD(WeaponKeys.PROVIDER_IRONS_SPELLS, "standard", ""),

    /** 媒介 · 聚能水晶：更狠、带暴击，更费蓝。 */
    SPELL_FOCUS_CRYSTAL(WeaponKeys.PROVIDER_IRONS_SPELLS, "focus_crystal", "focus"),
    /** 媒介 · 回响水晶：必定免费再放一次，代价是强度、法力和冷却。 */
    SPELL_ECHO_CRYSTAL(WeaponKeys.PROVIDER_IRONS_SPELLS, "echo_crystal", "focus"),

    /** 墨水 · 节流墨水：省蓝。 */
    SPELL_THRIFTY_INK(WeaponKeys.PROVIDER_IRONS_SPELLS, "thrifty_ink", "ink"),
    /** 墨水 · 灼热墨水：开场第一发最狠。 */
    SPELL_SEARING_INK(WeaponKeys.PROVIDER_IRONS_SPELLS, "searing_ink", "ink"),

    /** 符文 · 冷却符文：冷却短。 */
    SPELL_COOLDOWN_RUNE(WeaponKeys.PROVIDER_IRONS_SPELLS, "cooldown_rune", "rune"),
    /** 符文 · 速记符文：前摇短。 */
    SPELL_SHORTHAND_RUNE(WeaponKeys.PROVIDER_IRONS_SPELLS, "shorthand_rune", "rune"),
    /** 符文 · 双重装帧：冷却极短，费蓝、前摇长。 */
    SPELL_DOUBLE_BINDING(WeaponKeys.PROVIDER_IRONS_SPELLS, "double_binding", "rune"),

    /** 章节 · 猎魔章节：打怪。 */
    SPELL_HUNTER_CHAPTER(WeaponKeys.PROVIDER_IRONS_SPELLS, "hunter_chapter", "chapter"),
    /** 章节 · 决斗章节：对人、出手快。 */
    SPELL_DUEL_CHAPTER(WeaponKeys.PROVIDER_IRONS_SPELLS, "duel_chapter", "chapter"),
    /** 章节 · 研习笔记：练得快。 */
    SPELL_STUDY_NOTES(WeaponKeys.PROVIDER_IRONS_SPELLS, "study_notes", "chapter"),
    /** 章节 · 连咏书签：连击。 */
    SPELL_CHAIN_BOOKMARK(WeaponKeys.PROVIDER_IRONS_SPELLS, "chain_bookmark", "chapter"),
    /** 章节 · 远咏章节：远处的目标吃得更狠，吟唱略长。 */
    SPELL_FAR_CHAPTER(WeaponKeys.PROVIDER_IRONS_SPELLS, "far_chapter", "chapter"),

    /** 咒印 · 吸魂纹章：吸血。 */
    SPELL_SOUL_SIGIL(WeaponKeys.PROVIDER_IRONS_SPELLS, "soul_sigil", "seal"),
    /** 咒印 · 处决咒印：残血收割。 */
    SPELL_EXECUTION_SEAL(WeaponKeys.PROVIDER_IRONS_SPELLS, "execution_seal", "seal"),
    /** 咒印 · 破甲咒印：专破重甲。 */
    SPELL_SUNDER_SEAL(WeaponKeys.PROVIDER_IRONS_SPELLS, "sunder_seal", "seal"),
    /** 咒印 · 血契咒印（原「高阶咒印」，id 不变）：血越少越狠、省蓝。 */
    SPELL_ARCANE_SEAL(WeaponKeys.PROVIDER_IRONS_SPELLS, "arcane_seal", "seal"),
    /** 咒印 · 灵魂收割：击杀回血、叠嗜战，更费法力。 */
    SPELL_SOUL_HARVEST(WeaponKeys.PROVIDER_IRONS_SPELLS, "soul_harvest", "seal"),

    // ───────────── 新生魔艺：催化 / 书写 / 书带 / 书页 / 刻印 / 形态 ─────────────

    /** 符文 · 制式：什么都不改。 */
    GLYPH_STANDARD(WeaponKeys.PROVIDER_ARS_NOUVEAU, "standard", ""),

    /** 催化 · 魔源结晶：更狠、带暴击，更费蓝。 */
    GLYPH_SOURCE_GEM(WeaponKeys.PROVIDER_ARS_NOUVEAU, "source_gem", "catalyst"),
    /** 催化 · 过载符文（原「灼热墨水」，id 不变）：高暴击，更费蓝。 */
    GLYPH_SEARING_INK(WeaponKeys.PROVIDER_ARS_NOUVEAU, "searing_ink", "catalyst"),

    /** 书写 · 节流羽笔：省蓝。 */
    GLYPH_THRIFTY_QUILL(WeaponKeys.PROVIDER_ARS_NOUVEAU, "thrifty_quill", "quill"),
    /** 书写 · 学徒手记（原「研习笔记」，id 不变）：练得快、略省蓝。 */
    GLYPH_STUDY_NOTES(WeaponKeys.PROVIDER_ARS_NOUVEAU, "study_notes", "quill"),

    /** 书带 · 噬魂书带：吸血。 */
    GLYPH_SOUL_RIBBON(WeaponKeys.PROVIDER_ARS_NOUVEAU, "soul_ribbon", "ribbon"),
    /** 书带 · 连锁符文：连击。 */
    GLYPH_CHAIN_RUNE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "chain_rune", "ribbon"),

    /** 书页 · 猎魔书页：打怪。 */
    GLYPH_HUNTER_PAGE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "hunter_page", "page"),
    /** 书页 · 决斗书页：对人。 */
    GLYPH_DUEL_PAGE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "duel_page", "page"),
    /** 书页 · 远射书页：远处的目标吃得更狠，投射物更快。 */
    GLYPH_FAR_PAGE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "far_page", "page"),

    /** 刻印 · 处决符文：残血收割。 */
    GLYPH_EXECUTE_RUNE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "execute_rune", "inscription"),
    /** 刻印 · 破甲符文：专破重甲。 */
    GLYPH_SUNDER_RUNE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "sunder_rune", "inscription"),
    /** 刻印 · 魔源涌流：连杀叠嗜战，投射物更快。 */
    GLYPH_SOURCE_SURGE(WeaponKeys.PROVIDER_ARS_NOUVEAU, "source_surge", "inscription"),

    /** 形态 · 疾咒形态：投射物飞得快、略省蓝。 */
    GLYPH_SWIFT_FORM(WeaponKeys.PROVIDER_ARS_NOUVEAU, "swift_form", "form"),
    /** 形态 · 贯穿形态：投射物穿透。 */
    GLYPH_PIERCING_FORM(WeaponKeys.PROVIDER_ARS_NOUVEAU, "piercing_form", "form"),

    // ───────────── 原版剑类：剑身 / 剑刃 / 剑柄 / 剑术 ─────────────

    /** 剑 · 制式：什么都不改。 */
    SWORD_STANDARD(WeaponKeys.PROVIDER_VANILLA_MELEE, "standard", ""),

    /** 剑身 · 淬火重剑：重、推得远，出手慢。 */
    SWORD_TEMPERED_EDGE(WeaponKeys.PROVIDER_VANILLA_MELEE, "tempered_edge", "blade"),
    /** 剑身 · 轻剑身：出手快、跑得快，伤害低。 */
    SWORD_LIGHT_BLADE(WeaponKeys.PROVIDER_VANILLA_MELEE, "light_blade", "blade"),
    /** 剑身 · 精钢长剑：够得远，略慢。 */
    SWORD_FINE_STEEL(WeaponKeys.PROVIDER_VANILLA_MELEE, "fine_steel", "blade"),

    /** 剑刃 · 断首刃：残血收割。 */
    SWORD_SERRATED(WeaponKeys.PROVIDER_VANILLA_MELEE, "serrated", "edge"),
    /** 剑刃 · 破甲尖：专破重甲。 */
    SWORD_ARMOR_PIERCER(WeaponKeys.PROVIDER_VANILLA_MELEE, "armor_piercer", "edge"),
    /** 剑刃 · 连斩刃：连击。 */
    SWORD_KEEN_EDGE(WeaponKeys.PROVIDER_VANILLA_MELEE, "keen_edge", "edge"),
    /** 剑刃 · 引血槽：吸血、略快。 */
    SWORD_FULLER(WeaponKeys.PROVIDER_VANILLA_MELEE, "fuller", "edge"),
    /** 剑刃 · 刺剑尖：暴击、略长。 */
    SWORD_RAPIER_POINT(WeaponKeys.PROVIDER_VANILLA_MELEE, "rapier_point", "edge"),
    /** 剑刃 · 烈焰剑刃：砍中有几率点燃，费剑。 */
    SWORD_FLAME_EDGE(WeaponKeys.PROVIDER_VANILLA_MELEE, "flame_edge", "edge"),

    /** 剑柄 · 配重剑首：又快又狠，费剑。 */
    SWORD_BALANCED_POMMEL(WeaponKeys.PROVIDER_VANILLA_MELEE, "balanced_pommel", "hilt"),
    /** 剑柄 · 疾行剑鞘：跑得快、拔剑第一下重。 */
    SWORD_QUICK_SCABBARD(WeaponKeys.PROVIDER_VANILLA_MELEE, "quick_scabbard", "hilt"),

    /** 剑术 · 决斗剑术：对人、挨打少。 */
    SWORD_DUELIST(WeaponKeys.PROVIDER_VANILLA_MELEE, "duelist", "art"),
    /** 剑术 · 猎剑术：对怪、推得开。 */
    SWORD_HUNTING_SWORD(WeaponKeys.PROVIDER_VANILLA_MELEE, "hunting_sword", "art"),
    /** 剑术 · 守势剑术（原「放血刃」，id 不变）：以守为主。 */
    SWORD_BLEEDER(WeaponKeys.PROVIDER_VANILLA_MELEE, "bleeder", "art"),
    /** 剑术 · 剑术笔记（原「磨石」，id 不变）：练得快、略快。 */
    SWORD_WHETSTONE(WeaponKeys.PROVIDER_VANILLA_MELEE, "whetstone", "art"),
    /** 剑术 · 凯旋剑术：击杀回血、叠嗜战，出手略慢。 */
    SWORD_TRIUMPH(WeaponKeys.PROVIDER_VANILLA_MELEE, "triumph", "art"),

    // ───────────── 弓弩：弓臂 / 弓弦 / 箭头 / 箭杆 / 箭囊 ─────────────

    /** 弓弩 · 制式：什么都不改。 */
    BOW_STANDARD(WeaponKeys.PROVIDER_BOW, "standard", ""),

    /** 弓臂 · 加重弓臂：劲大、推得远，拉得慢。 */
    BOW_HEAVY_LIMBS(WeaponKeys.PROVIDER_BOW, "heavy_limbs", "limb"),
    /** 弓臂 · 反曲弓臂：拉得快、箭快，伤害略低。 */
    BOW_RECURVE_LIMBS(WeaponKeys.PROVIDER_BOW, "recurve_limbs", "limb"),

    /** 弓弦 · 精制弓弦：爆头。 */
    BOW_FINE_STRING(WeaponKeys.PROVIDER_BOW, "fine_string", "string"),
    /** 弓弦 · 弓弦蜡：练得快、耐用。 */
    BOW_BOWSTRING_WAX(WeaponKeys.PROVIDER_BOW, "bowstring_wax", "string"),
    /** 弓弦 · 鹰眼弓弦：越远越狠，近身吃亏。 */
    BOW_EAGLE_EYE(WeaponKeys.PROVIDER_BOW, "eagle_eye", "string"),

    /** 箭头 · 倒钩箭头：残血收割。 */
    BOW_BARBED_HEADS(WeaponKeys.PROVIDER_BOW, "barbed_heads", "head"),
    /** 箭头 · 破甲箭头：专破重甲。 */
    BOW_BODKIN_HEADS(WeaponKeys.PROVIDER_BOW, "bodkin_heads", "head"),
    /** 箭头 · 宽刃箭头：专打无甲目标、带暴击。 */
    BOW_BROADHEAD_HEADS(WeaponKeys.PROVIDER_BOW, "broadhead_heads", "head"),
    /** 箭头 · 火矢箭头：射中有几率点燃。 */
    BOW_FIRE_ARROWS(WeaponKeys.PROVIDER_BOW, "fire_arrows", "head"),

    /** 箭杆 · 轻质箭杆：又快又飘。 */
    BOW_LIGHT_SHAFTS(WeaponKeys.PROVIDER_BOW, "light_shafts", "shaft"),
    /** 箭杆 · 速射箭杆：连射越射越准。 */
    BOW_RAPID_SHAFTS(WeaponKeys.PROVIDER_BOW, "rapid_shafts", "shaft"),
    /** 箭杆 · 贯穿箭杆：一箭穿几个。 */
    BOW_PIERCE_SHAFTS(WeaponKeys.PROVIDER_BOW, "pierce_shafts", "shaft"),
    /** 箭杆 · 霜寒箭杆：射中有几率让目标缓慢，箭略慢。 */
    BOW_FROST_SHAFTS(WeaponKeys.PROVIDER_BOW, "frost_shafts", "shaft"),

    /** 箭囊 · 猎手箭囊：打怪。 */
    BOW_HUNTER_QUIVER(WeaponKeys.PROVIDER_BOW, "hunter_quiver", "quiver"),
    /** 箭囊 · 战场箭囊：对人。 */
    BOW_WAR_QUIVER(WeaponKeys.PROVIDER_BOW, "war_quiver", "quiver"),
    /** 箭囊 · 回收箭袋：省箭。 */
    BOW_RECOVERY_QUIVER(WeaponKeys.PROVIDER_BOW, "recovery_quiver", "quiver"),

    // ───────────── 匠魂：锻头 / 手柄 / 镶嵌 / 锻打 ─────────────

    /** 匠魂 · 制式：什么都不改。 */
    TINKER_STANDARD(WeaponKeys.PROVIDER_TCONSTRUCT, "standard", ""),

    /** 锻头 · 重型锻头：砸得狠、推得远，出手慢。 */
    TINKER_HEAVY_HEAD(WeaponKeys.PROVIDER_TCONSTRUCT, "heavy_head", "head"),
    /** 锻头 · 锐化锻头：吃暴击，平砍轻。 */
    TINKER_KEEN_HEAD(WeaponKeys.PROVIDER_TCONSTRUCT, "keen_head", "head"),
    /** 锻头 · 宽刃锻头：够得远，略慢。 */
    TINKER_BROAD_HEAD(WeaponKeys.PROVIDER_TCONSTRUCT, "broad_head", "head"),

    /** 手柄 · 轻质手柄：出手快、跑得快，伤害低。 */
    TINKER_LIGHT_HANDLE(WeaponKeys.PROVIDER_TCONSTRUCT, "light_handle", "handle"),
    /** 手柄 · 加长手柄：攻击距离，代价是慢。 */
    TINKER_LONG_HANDLE(WeaponKeys.PROVIDER_TCONSTRUCT, "long_handle", "handle"),
    /** 手柄 · 缠绕握柄：连击。 */
    TINKER_GRIPPED_HANDLE(WeaponKeys.PROVIDER_TCONSTRUCT, "gripped_handle", "handle"),

    /** 镶嵌 · 汲魂镶嵌：吸血。 */
    TINKER_SOUL_BINDING(WeaponKeys.PROVIDER_TCONSTRUCT, "soul_binding", "binding"),
    /** 镶嵌 · 坚固镶嵌：耐久和减伤。 */
    TINKER_STURDY_BINDING(WeaponKeys.PROVIDER_TCONSTRUCT, "sturdy_binding", "binding"),
    /** 镶嵌 · 燃火镶嵌：点燃。 */
    TINKER_EMBER_BINDING(WeaponKeys.PROVIDER_TCONSTRUCT, "ember_binding", "binding"),

    /** 锻打 · 破甲锻打：专打重甲。 */
    TINKER_ARMOR_BREAK(WeaponKeys.PROVIDER_TCONSTRUCT, "armor_break", "forging"),
    /** 锻打 · 断首锻打：残血收割。 */
    TINKER_EXECUTION(WeaponKeys.PROVIDER_TCONSTRUCT, "execution", "forging"),
    /** 锻打 · 狩猎锻打：对怪强、对人弱。 */
    TINKER_HUNT(WeaponKeys.PROVIDER_TCONSTRUCT, "hunt", "forging"),
    /** 锻打 · 狂锻：越打越猛，也越脆。 */
    TINKER_FRENZY(WeaponKeys.PROVIDER_TCONSTRUCT, "frenzy", "forging"),

    // ───────────── 甜蜜魔法：杖芯 / 术式 / 媒介 / 修习 ─────────────

    /** 甜蜜魔法 · 制式：什么都不改。 */
    SM_STANDARD(WeaponKeys.PROVIDER_SWEETMAGIC, "standard", ""),

    /** 杖芯 · 高密杖芯：威力大，费蓝。 */
    SM_DENSE_CORE(WeaponKeys.PROVIDER_SWEETMAGIC, "dense_core", "core"),
    /** 杖芯 · 节流杖芯：省蓝，威力低。 */
    SM_EFFICIENT_CORE(WeaponKeys.PROVIDER_SWEETMAGIC, "efficient_core", "core"),
    /** 杖芯 · 共鸣杖芯：吃暴击。 */
    SM_RESONANT_CORE(WeaponKeys.PROVIDER_SWEETMAGIC, "resonant_core", "core"),

    /** 术式 · 速唱术式：冷却短，威力低。 */
    SM_RAPID_FORMULA(WeaponKeys.PROVIDER_SWEETMAGIC, "rapid_formula", "formula"),
    /** 术式 · 重咏术式：威力大，冷却长。 */
    SM_HEAVY_FORMULA(WeaponKeys.PROVIDER_SWEETMAGIC, "heavy_formula", "formula"),
    /** 术式 · 稳态术式：省蓝加威力，冷却略长。 */
    SM_STEADY_FORMULA(WeaponKeys.PROVIDER_SWEETMAGIC, "steady_formula", "formula"),

    /** 媒介 · 汲魂媒介：吸血。 */
    SM_SIPHON_MEDIUM(WeaponKeys.PROVIDER_SWEETMAGIC, "siphon_medium", "medium"),
    /** 媒介 · 燃火媒介：点燃。 */
    SM_EMBER_MEDIUM(WeaponKeys.PROVIDER_SWEETMAGIC, "ember_medium", "medium"),
    /** 媒介 · 凝霜媒介：减速。 */
    SM_FROST_MEDIUM(WeaponKeys.PROVIDER_SWEETMAGIC, "frost_medium", "medium"),
    /** 媒介 · 诅咒媒介：虚弱。 */
    SM_CURSE_MEDIUM(WeaponKeys.PROVIDER_SWEETMAGIC, "curse_medium", "medium"),

    /** 修习 · 猎魔修习：对怪强、对人弱。 */
    SM_HUNT(WeaponKeys.PROVIDER_SWEETMAGIC, "hunt", "study"),
    /** 修习 · 对人修习：反过来。 */
    SM_DUEL(WeaponKeys.PROVIDER_SWEETMAGIC, "duel", "study"),
    /** 修习 · 破甲修习：专打重甲。 */
    SM_PIERCE(WeaponKeys.PROVIDER_SWEETMAGIC, "pierce", "study"),
    /** 修习 · 钻研修习：涨经验快，威力低。 */
    SM_SCHOLAR(WeaponKeys.PROVIDER_SWEETMAGIC, "scholar", "study"),

    // ───────────── 铁枪：火药 / 弹丸 / 枪机·枪托 / 装填 / 枪术 ─────────────

    /** 铁枪 · 制式：什么都不改。 */
    IA_STANDARD(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "standard", ""),

    /** 火药 · 加量装药：一枪更狠，人被推得更远、后坐更大、装得更慢。 */
    IA_HEAVY_CHARGE(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "heavy_charge", "powder"),
    /** 火药 · 爆燃火药：改装件的爆炸、闪电、魂火伤害大涨，子弹本身变弱。 */
    IA_VOLATILE_POWDER(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "volatile_powder", "powder"),
    /** 火药 · 速燃火药：射速和装填快，伤害和弹速低。 */
    IA_QUICK_POWDER(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "quick_powder", "powder"),

    /** 弹丸 · 跳弹弹丸：在方块上多弹几次，弹过再命中更痛。 */
    IA_SKIPPING_BALL(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "skipping_ball", "shot"),
    /** 弹丸 · 寻踪弹丸：子弹往目标拐，飞得慢。 */
    IA_HOMING_BALL(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "homing_ball", "shot"),
    /** 弹丸 · 淬火弹丸：穿一个目标，专打披甲。 */
    IA_HARDENED_BALL(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "hardened_ball", "shot"),

    /** 枪机 · 扇击击锤：连射快，散得开。 */
    IA_FANNING_HAMMER(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "fanning_hammer", "lock"),
    /** 枪机 · 簧轮枪机：稳而准，射速慢。 */
    IA_WHEELLOCK(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "wheellock", "lock"),
    /** 枪托 · 软垫枪托：开枪不再把人往后推。 */
    IA_PADDED_STOCK(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "padded_stock", "lock"),

    /** 装填 · 纸壳定装弹：装得快，威力低。 */
    IA_PAPER_CARTRIDGE(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "paper_cartridge", "loading"),
    /** 装填 · 压实装药：满膛第一发更痛，装得慢。 */
    IA_TAMPED_CHARGE(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "tamped_charge", "loading"),
    /** 装填 · 节药装填：有几率不扣弹，威力低。 */
    IA_THRIFTY_LOADING(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "thrifty_loading", "loading"),

    /** 枪术 · 快枪手：近身速射，远了不行。 */
    IA_QUICKSHOT(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "quickshot", "gunplay"),
    /** 枪术 · 线列射击：远距离齐射，走得慢。 */
    IA_LINE_VOLLEY(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "line_volley", "gunplay"),
    /** 枪术 · 赏金猎人：击杀补弹、嗜战，自己更脆。 */
    IA_BOUNTY_HUNTER(WeaponKeys.PROVIDER_IRONS_ARTIFICE, "bounty_hunter", "gunplay");

    /** 所属来源 ID，同时也是该来源的总体专精键。 */
    private final String sourceId;

    /** 特化标识，在同一个来源内唯一。 */
    private final String id;

    /** 存档与网络传输用的完整键。 */
    private final String key;

    /**
     * 系别（枪械就是改装部位）。两个槽里的特化不能同系：扩容弹匣配弹鼓、两种弹药叠在一起，
     * 现实里装不上，数值上也只会把同一样东西叠到失控。制式是空串，不参与这条规则。
     */
    private final String family;

    Specialization(String sourceId, String id, String family) {
        this.sourceId = sourceId;
        this.id = id;
        this.key = sourceId + "|" + id;
        this.family = family;
    }

    /**
     * @return 所属来源 ID
     */
    public String sourceId() {
        return this.sourceId;
    }

    /**
     * @return 特化标识
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
     * @return 系别；制式是空串
     */
    public String family() {
        return this.family;
    }

    /**
     * @return 系别名称的语言键
     */
    public String familyKey() {
        return "spec_family.albionmastery." + this.sourceId + "." + this.family;
    }

    /**
     * 两个特化能不能同时装：制式和谁都不冲突，其余同系就冲突。
     *
     * @param other 另一个槽的特化，可为 null
     * @return 同系冲突返回 true
     */
    public boolean conflictsWith(@Nullable Specialization other) {
        return other != null && other != this && !this.isStandard() && !other.isStandard()
                && this.sourceId.equals(other.sourceId) && this.family.equals(other.family);
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
     * 只有制式用这个键写一句「不做任何改动」。
     *
     * <p>其余特化的得失说明不走语言文件，由客户端的 {@code SpecDescriber} 按服务端下发的实际数值生成，
     * 服主改了配置界面上的数字跟着变。
     *
     * @return 制式说明的语言键
     */
    public String gainKey() {
        return "spec.albionmastery." + this.sourceId + "." + this.id + ".gain";
    }

    /**
     * @return 配置文件里这个特化的小节名
     */
    public String configSection() {
        return this.sourceId + "_" + this.id;
    }

    /**
     * 按完整键、按来源的索引。
     *
     * <p>{@code byKey} 在开火、命中、每 tick 的射速 / 开镜修正里都会走到；
     * {@code values()} 每调一次都要复制整个数组再逐个比字符串，一百多项放在热路径上不划算，
     * 类加载时建一次表就够了。
     */
    private static final Map<String, Specialization> BY_KEY;
    private static final Map<String, List<Specialization>> BY_SOURCE;
    private static final Map<String, Specialization> STANDARD_BY_SOURCE;

    static {
        Map<String, Specialization> byKey = new HashMap<>();
        Map<String, List<Specialization>> bySource = new HashMap<>();
        Map<String, Specialization> standards = new HashMap<>();
        for (Specialization spec : values()) {
            byKey.put(spec.key, spec);
            bySource.computeIfAbsent(spec.sourceId, id -> new ArrayList<>()).add(spec);
            if (spec.isStandard()) {
                standards.put(spec.sourceId, spec);
            }
        }
        Map<String, List<Specialization>> frozen = new HashMap<>();
        bySource.forEach((id, list) -> frozen.put(id, List.copyOf(list)));
        BY_KEY = Map.copyOf(byKey);
        BY_SOURCE = Map.copyOf(frozen);
        STANDARD_BY_SOURCE = Map.copyOf(standards);
    }

    /**
     * 列出某个来源下的全部特化，制式排在第一个。
     *
     * @param sourceId 来源 ID
     * @return 只读的特化列表，来源不认识时返回空列表
     */
    public static List<Specialization> forSource(String sourceId) {
        // 不可变 Map 查 null 会抛异常，旧实现传 null 是返回空表，这里保持一致
        return sourceId == null ? List.of() : BY_SOURCE.getOrDefault(sourceId, List.of());
    }

    /**
     * 取某个来源的默认特化（制式）。
     *
     * @param sourceId 来源 ID
     * @return 制式特化；来源不认识时返回 null
     */
    @Nullable
    public static Specialization standardOf(String sourceId) {
        return sourceId == null ? null : STANDARD_BY_SOURCE.get(sourceId);
    }

    /**
     * 按完整键查特化。
     *
     * @param key 完整键
     * @return 特化；查不到时返回 null
     */
    @Nullable
    public static Specialization byKey(@Nullable String key) {
        return key == null || key.isEmpty() ? null : BY_KEY.get(key);
    }
}
