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

    /** 枪械 · 精准射击：爆头翻倍、带暴击，打身上反而不如从前。 */
    GUN_MARKSMAN(WeaponKeys.PROVIDER_TACZ_GUN, "marksman"),

    /** 枪械 · 收割：残血目标伤害暴涨，击杀顺手补弹；对满血目标偏软。 */
    GUN_REAPER(WeaponKeys.PROVIDER_TACZ_GUN, "reaper"),

    /** 枪械 · 猎手：击杀大量补弹、经验更多，代价是伤害略低。 */
    GUN_HUNTER(WeaponKeys.PROVIDER_TACZ_GUN, "hunter"),

    /** 枪械 · 嗜血：打中就回血，伤害和弹匣都缩水。 */
    GUN_VAMPIRE(WeaponKeys.PROVIDER_TACZ_GUN, "vampire"),

    /** 枪械 · 游击：跑得快、换弹快，伤害和弹匣都缩水。 */
    GUN_GUERRILLA(WeaponKeys.PROVIDER_TACZ_GUN, "guerrilla"),

    /** 枪械 · 压制：连续命中同一目标伤害递增，首发偏软。 */
    GUN_SUPPRESSION(WeaponKeys.PROVIDER_TACZ_GUN, "suppression"),

    /** 近战 · 制式。 */
    MELEE_STANDARD(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "standard"),

    /** 近战 · 疾风：出手快、跑得快，单次伤害低。 */
    MELEE_SWIFT(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "swift"),

    /** 近战 · 重压：伤害高、带暴击，出手慢、费耐久。 */
    MELEE_HEAVY(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "heavy"),

    /** 近战 · 贯甲：专打披甲目标、省耐久，对没有护甲的目标反而更弱。 */
    MELEE_PIERCE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "pierce"),

    /** 近战 · 处决：残血目标一刀带走并小幅回血，满血目标砍不动。 */
    MELEE_EXECUTE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "execute"),

    /** 近战 · 嗜血：砍中就回血，代价是伤害偏低。 */
    MELEE_VAMPIRE(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "vampire"),

    /** 近战 · 连斩：连续砍同一目标伤害递增，首刀偏软。 */
    MELEE_COMBO(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "combo"),

    /** 近战 · 求道：经验更多、武器更耐用，代价是伤害略低。 */
    MELEE_ASCETIC(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, "ascetic"),

    /** 拔刀剑 · 制式。 */
    BLADE_STANDARD(WeaponKeys.PROVIDER_SLASHBLADE, "standard"),

    /** 拔刀剑 · 幻影剑：召唤剑伤害翻倍、略快，刀身直接攻击削弱。 */
    BLADE_PHANTOM(WeaponKeys.PROVIDER_SLASHBLADE, "phantom"),

    /** 拔刀剑 · 刀身：直接攻击强化、带暴击，召唤剑削弱。 */
    BLADE_EDGE(WeaponKeys.PROVIDER_SLASHBLADE, "edge"),

    /** 拔刀剑 · 修罗：自己血越少打得越狠、砍中回血，代价是平时软一截。 */
    BLADE_ASURA(WeaponKeys.PROVIDER_SLASHBLADE, "asura"),

    /** 拔刀剑 · 决斗：对玩家强、刀更耐用，对怪物弱，PVP 服专用。 */
    BLADE_DUEL(WeaponKeys.PROVIDER_SLASHBLADE, "duel"),

    /** 拔刀剑 · 噬魂：砍中就回血，代价是伤害偏低。 */
    BLADE_SOUL_EATER(WeaponKeys.PROVIDER_SLASHBLADE, "soul_eater"),

    /** 拔刀剑 · 连斩：连续砍同一目标伤害递增，首刀偏软。 */
    BLADE_COMBO(WeaponKeys.PROVIDER_SLASHBLADE, "combo"),

    /** 拔刀剑 · 求道：经验更多、刀更耐用，代价是伤害略低。 */
    BLADE_ASCETIC(WeaponKeys.PROVIDER_SLASHBLADE, "ascetic");

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
