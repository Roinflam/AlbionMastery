package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.hollingsworth.arsnouveau.api.item.ICasterTool;
import com.hollingsworth.arsnouveau.api.spell.AbstractAugment;
import com.hollingsworth.arsnouveau.api.spell.AbstractCastMethod;
import com.hollingsworth.arsnouveau.api.spell.AbstractEffect;
import com.hollingsworth.arsnouveau.api.spell.AbstractSpellPart;
import com.hollingsworth.arsnouveau.api.spell.ISpellCaster;
import com.hollingsworth.arsnouveau.api.spell.Spell;
import com.hollingsworth.arsnouveau.api.spell.SpellSchool;
import com.hollingsworth.arsnouveau.api.util.CasterUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 新生魔艺（Ars Nouveau）的接入：把「符文」当成武器。
 *
 * <p>新生魔艺的法术是玩家自己用符文拼的，没有固定的法术表，所以拿零件当武器：
 * 伤害、点燃、治疗这些<b>效果</b>，弹射、触碰这些<b>形态</b>，增幅、延时这些<b>增幅</b>，
 * 每一个符文一个节点。全部注册在 {@code GlyphRegistry} 里，附属加的符文一样在。
 *
 * <p>大类：效果按学派（火 / 水 / 风 / 土 / 防护 / 召唤 / 死灵 / 操控），形态和增幅各自一个大类，
 * 没有学派的效果归「其它」。
 *
 * <p>一个拼出来的法术怎么算加成：取它用到的<b>所有符文的加成平均值</b>——弹射 + 伤害 + 增幅三个都练满
 * 才吃满，只练伤害符文只拿三分之一，形态和增幅练了同样算数。
 *
 * <p>只有这个类碰新生魔艺的类，其它地方一律先问 {@code WeaponResolver.isArsLoaded()} 再调进来。
 */
public final class ArsNouveauSupport {

    /** 形态符文的大类。 */
    public static final String TYPE_FORM = "form";

    /** 增幅符文的大类。 */
    public static final String TYPE_AUGMENT = "augment";

    /** 没有学派的效果符文的大类。 */
    public static final String TYPE_MISC = "misc";

    private ArsNouveauSupport() {
    }

    /**
     * 这件物品能不能施法（法术书、法杖、咒术书……实现了施法接口的都算）。
     *
     * @param stack 物品
     * @return 能施法返回 true
     */
    public static boolean isCaster(ItemStack stack) {
        // 不能用 CasterUtil.getCaster != null 判断：它对任何物品都会 new 一个空的 SpellCaster 出来，
        // 永远不为 null，会把所有武器都当成新生魔艺的施法物品——拔刀剑、近战刀的提示和经验全被吞掉
        return !stack.isEmpty() && stack.getItem() instanceof ICasterTool;
    }

    /**
     * 「一件物品 → 一件武器」：当前选中法术的第一个符文。
     *
     * @param stack 物品
     * @return 第一个符文对应的武器；不是施法物品或法术是空的返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack) {
        List<ResolvedWeapon> all = resolveAll(stack);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 「一件物品 → 一串武器」：当前选中法术用到的全部符文，去重，按法术里的顺序。
     *
     * <p>只看<b>当前选中</b>的那个法术，书里别的槽位不算——不然拿着一本塞满法术的书，
     * 随便杀一只怪几十个符文一起涨经验。
     *
     * @param stack 物品
     * @return 符文列表；不是施法物品或法术是空的返回空表
     */
    public static List<ResolvedWeapon> resolveAll(ItemStack stack) {
        Spell spell = currentSpell(stack);
        return spell == null ? List.of() : partsOf(spell);
    }

    /**
     * 一件施法物品当前选中的法术。
     *
     * @param stack 物品
     * @return 法术；不是施法物品或法术是空的返回 null
     */
    @Nullable
    public static Spell currentSpell(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        try {
            ISpellCaster caster = CasterUtil.getCaster(stack);
            if (caster == null) {
                return null;
            }
            Spell spell = caster.getSpell();
            return spell == null || spell.isEmpty() ? null : spell;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 一个法术用到的全部符文，去重。
     *
     * @param spell 法术
     * @return 武器列表
     */
    public static List<ResolvedWeapon> partsOf(@Nullable Spell spell) {
        if (spell == null || spell.recipe == null || spell.recipe.isEmpty()) {
            return List.of();
        }
        Set<ResolvedWeapon> parts = new LinkedHashSet<>();
        for (AbstractSpellPart part : spell.recipe) {
            ResolvedWeapon weapon = ofPart(part);
            if (weapon != null) {
                parts.add(weapon);
            }
        }
        return new ArrayList<>(parts);
    }

    /**
     * 一个符文对应的武器。
     *
     * @param part 符文
     * @return 武器；空或没注册名返回 null
     */
    @Nullable
    public static ResolvedWeapon ofPart(@Nullable AbstractSpellPart part) {
        if (part == null) {
            return null;
        }
        ResourceLocation id = part.getRegistryName();
        if (id == null) {
            return null;
        }
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_ARS_NOUVEAU, id, typeOf(part));
    }

    /**
     * 符文 → 大类。形态、增幅各一类；效果按第一个学派，没有学派的归「其它」。
     *
     * @param part 符文
     * @return 类型字符串
     */
    public static String typeOf(AbstractSpellPart part) {
        if (part instanceof AbstractCastMethod) {
            return TYPE_FORM;
        }
        if (part instanceof AbstractAugment) {
            return TYPE_AUGMENT;
        }
        List<SpellSchool> schools = part.spellSchools;
        if (schools != null) {
            for (SpellSchool school : schools) {
                if (school != null && school.getId() != null && !school.getId().isEmpty()) {
                    return school.getId();
                }
            }
        }
        return TYPE_MISC;
    }

    /**
     * 一个法术的「强度」倍率：所有符文各自的（专精加成 × 流派强度倍率）取平均。
     *
     * <p>两端都能调（客户端算法力显示，服务端算实际效果）。客户端关掉
     * {@code overrideDamageDisplay} 时返回 1，让显示保持原样。
     *
     * @param caster 施法者
     * @param spell  法术
     * @return 倍率；没符文或没数据时 1.0
     */
    public static double spellStrength(@Nullable LivingEntity caster, @Nullable Spell spell) {
        if (!(caster instanceof Player player)) {
            return 1.0D;
        }
        if (player.level().isClientSide() && !MasteryConfig.CLIENT.overrideDamageDisplay.get()) {
            return 1.0D;
        }
        List<ResolvedWeapon> parts = partsOf(spell);
        if (parts.isEmpty()) {
            return 1.0D;
        }
        double sum = 0.0D;
        for (ResolvedWeapon part : parts) {
            MasteryHelper.Bonus bonus = SpecializationEffects.bonusFor(player, part);
            SpecializationValues spec = SpecializationEffects.forWeapon(player, part);
            sum += (bonus == null ? 1.0D : bonus.multiplier()) * spec.damage();
        }
        double average = sum / parts.size();
        return average <= 0.0D ? 1.0D : average;
    }

    /**
     * 一个法术的专精加成平均值（不含流派），给冷却 / 法力折扣用。
     *
     * @param caster 施法者
     * @param spell  法术
     * @return 平均加成比例（0.2 = +20%），没符文或没数据时 0
     */
    public static double averageBonus(@Nullable LivingEntity caster, @Nullable Spell spell) {
        if (!(caster instanceof Player player)) {
            return 0.0D;
        }
        List<ResolvedWeapon> parts = partsOf(spell);
        if (parts.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (ResolvedWeapon part : parts) {
            MasteryHelper.Bonus bonus = SpecializationEffects.bonusFor(player, part);
            sum += bonus == null ? 0.0D : bonus.total();
        }
        return sum / parts.size();
    }

    /**
     * 一个法术的法力消耗倍率：所有符文所在流派的法力倍率取平均，再乘专精折扣。
     *
     * @param caster 施法者
     * @param spell  法术
     * @return 倍率
     */
    public static double manaFactor(@Nullable LivingEntity caster, @Nullable Spell spell) {
        if (!(caster instanceof Player player)) {
            return 1.0D;
        }
        List<ResolvedWeapon> parts = partsOf(spell);
        if (parts.isEmpty()) {
            return 1.0D;
        }
        double sum = 0.0D;
        for (ResolvedWeapon part : parts) {
            sum += SpecializationEffects.forWeapon(player, part).manaCost();
        }
        double factor = sum / parts.size();
        double share = MasteryConfig.COMMON.spellMasteryManaShare.get();
        double reduction = Math.min(MasteryConfig.COMMON.spellMasteryReductionCap.get(),
                averageBonus(player, spell) * share);
        return Math.max(0.05D, factor * (1.0D - reduction));
    }

    /**
     * 一个法术「主效果」所在流派的数值，给暴击 / 连击 / 吸血这些不好平均的项用。
     *
     * <p>主效果 = 法术里第一个效果符文；没有效果符文（纯形态 + 增幅）就是中性值。
     *
     * @param caster 施法者
     * @param spell  法术
     * @return 数值
     */
    public static SpecializationValues primarySpec(@Nullable LivingEntity caster, @Nullable Spell spell) {
        if (spell == null || spell.recipe == null) {
            return SpecializationValues.NEUTRAL;
        }
        for (AbstractSpellPart part : spell.recipe) {
            if (part instanceof AbstractEffect) {
                return SpecializationEffects.forWeapon(caster, ofPart(part));
            }
        }
        return SpecializationValues.NEUTRAL;
    }
}
