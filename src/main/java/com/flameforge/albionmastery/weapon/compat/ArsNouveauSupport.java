package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.hollingsworth.arsnouveau.api.item.ICasterTool;
import com.hollingsworth.arsnouveau.api.registry.GlyphRegistry;
import com.hollingsworth.arsnouveau.api.registry.SpellCasterRegistry;
import com.hollingsworth.arsnouveau.api.spell.AbstractAugment;
import com.hollingsworth.arsnouveau.api.spell.AbstractCastMethod;
import com.hollingsworth.arsnouveau.api.spell.AbstractCaster;
import com.hollingsworth.arsnouveau.api.spell.AbstractEffect;
import com.hollingsworth.arsnouveau.api.spell.AbstractSpellPart;
import com.hollingsworth.arsnouveau.api.spell.Spell;
import com.hollingsworth.arsnouveau.api.spell.SpellSchool;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        // 只认实现了施法接口的物品（魔艺自带的法术书、法杖、卷轴、施法书等都实现了）。
        // 非施法物品取不到施法器，currentSpell 返回 null；这里不必去取施法器，看接口就够
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
            // 施法物品走它自己的取法（附属可以覆写），其余物品按登记表取，没登记、没施法器组件的返回 null
            AbstractCaster<?> caster = stack.getItem() instanceof ICasterTool tool
                    ? tool.getSpellCaster(stack)
                    : SpellCasterRegistry.from(stack);
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
        if (spell == null || spell.isEmpty()) {
            return List.of();
        }
        Set<ResolvedWeapon> parts = new LinkedHashSet<>();
        for (AbstractSpellPart part : spell.recipe()) {
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
     * 一个法术用到的所有符文的特化，合在一起算：同一份配置（同一个类型、或同一件单独配置的符文）只算一次，
     * 不同的配置按特化叠加的规则合并（倍率相乘、概率相加）。
     *
     * <p>以前伤害倍率、法力倍率按符文平均，一个三种类型的法术里，某个类型的特化只剩三分之一的效果；
     * 暴击、吸血、连击这些又只看主效果符文那一个类型，装在形态、增幅类型上的特化收益完全不生效，代价却照扣。
     * 现在每个特化都按卡片上写的数值完整生效一次。
     *
     * @param caster 施法者
     * @param spell  法术
     * @return 合并后的数值
     */
    public static SpecializationValues spellSpec(@Nullable LivingEntity caster, @Nullable Spell spell) {
        SpecializationValues result = SpecializationValues.NEUTRAL;
        Set<String> seen = new java.util.HashSet<>();
        for (ResolvedWeapon part : partsOf(spell)) {
            if (seen.add(SpecializationEffects.configKeyFor(caster, part))) {
                result = SpecializationValues.combine(result, SpecializationEffects.forWeapon(caster, part));
            }
        }
        return result;
    }

    /**
     * 一个法术的「强度」倍率：所有符文各自的（专精加成 × 特化强度倍率）取平均。
     *
     * <p>两端都能调（客户端算法力显示，服务端算实际效果）。客户端关掉
     * {@code overrideDamageDisplay} 时返回 1，让显示保持原样。
     *
     * @param caster 施法者
     * @param spell  法术
     * @return 倍率；没符文或没数据时 1.0
     */
    public static double spellStrength(@Nullable LivingEntity caster, @Nullable Spell spell) {
        return spellStrength(caster, spell, null);
    }

    /**
     * 同上，但可以把已经算好的特化数值传进来复用。
     *
     * <p>一次法术伤害里「合并出这个法术的特化」要遍历所有符文、逐个解析武器、再合并数值，
     * 是这条路上最贵的一步。伤害处理那边本来就已经算过一次，别在这里再算一遍。
     *
     * @param caster 施法者
     * @param spell  法术
     * @param cached 已经算好的特化数值，没有就传 null
     * @return 强度倍率
     */
    public static double spellStrength(@Nullable LivingEntity caster, @Nullable Spell spell,
                                       @Nullable SpecializationValues cached) {
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
        // 专精加成按符文平均（它是练级练出来的，一个符文练得高不该让整个法术翻倍），
        // 特化的伤害倍率按合并后的完整值乘一次
        double sum = 0.0D;
        for (ResolvedWeapon part : parts) {
            MasteryHelper.Bonus bonus = SpecializationEffects.bonusFor(player, part);
            sum += bonus == null ? 1.0D : bonus.multiplier();
        }
        double strength = sum / parts.size()
                * (cached != null ? cached : spellSpec(player, spell)).damage();
        return strength <= 0.0D ? 1.0D : strength;
    }

    /**
     * 一个法术的专精加成平均值（不含特化），给冷却 / 法力折扣用。
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
     * 一个法术的法力消耗倍率：所有符文所在特化的法力倍率取平均，再乘专精折扣。
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
        double factor = spellSpec(player, spell).manaCost();
        // 按端取快照：客户端算显示的法力、服务端算真正扣的法力，两边必须是同一个数
        MasteryConfig.Snapshot snapshot = SpecializationEffects.snapshotFor(player);
        double reduction = Math.min(snapshot.spellMasteryReductionCap,
                averageBonus(player, spell) * snapshot.spellMasteryManaShare);
        return Math.max(0.05D, factor * (1.0D - reduction));
    }

    /**
     * 管投射物（飞行速度、穿透）的特化数值：法术形态符文所属类型上的特化，叠上主效果特化。
     *
     * <p>魔艺的特化是按符文类型分开选的。飞多快、能穿几个，本来是「形态」的事，所以形态类型上选的特化
     * 一定要算进来，不然给形态选特化就没意义了；主效果那边也叠上，是为了在学派上选了「疾咒形态」的玩家
     * 同样有感觉。两边都选的话效果叠加，代价（强度、法力）也会在两个符文上各付一次，不会白拿。
     *
     * @param caster 施法者
     * @param spell  法术
     * @return 数值
     */
    public static SpecializationValues projectileSpec(@Nullable LivingEntity caster, @Nullable Spell spell) {
        return spellSpec(caster, spell);
    }

    /**
     * 一个法术「主效果」所在特化的数值，给暴击 / 连击 / 吸血这些不好平均的项用。
     *
     * <p>主效果 = 法术里第一个效果符文；没有效果符文（纯形态 + 增幅）就是中性值。
     *
     * @param caster 施法者
     * @param spell  法术
     * @return 数值
     */
    /**
     * 法术主效果（第一个效果符文）对应的武器：命中后的特化效果按它算，和伤害那边一致。
     *
     * @param spell 法术
     * @return 武器；法术里没有效果符文返回 null
     */
    @Nullable
    public static ResolvedWeapon primaryWeapon(@Nullable Spell spell) {
        if (spell == null || spell.isEmpty()) {
            return null;
        }
        for (AbstractSpellPart part : spell.recipe()) {
            if (part instanceof AbstractEffect) {
                return ofPart(part);
            }
        }
        return null;
    }

    /**
     * 法术主效果（第一个效果符文）对应的特化数值。
     *
     * @param caster 施法者
     * @param spell  法术
     * @return 数值
     */
    public static SpecializationValues primarySpec(@Nullable LivingEntity caster, @Nullable Spell spell) {
        if (spell == null || spell.isEmpty()) {
            return SpecializationValues.NEUTRAL;
        }
        for (AbstractSpellPart part : spell.recipe()) {
            if (part instanceof AbstractEffect) {
                return SpecializationEffects.forWeapon(caster, ofPart(part));
            }
        }
        return SpecializationValues.NEUTRAL;
    }

    /**
     * 把全部启用的符文列进清单，物品就是符文本身。
     *
     * @param out 输出
     */
    public static void catalog(List<WeaponCatalog.Entry> out) {
        for (Map.Entry<ResourceLocation, AbstractSpellPart> entry : GlyphRegistry.getSpellpartMap().entrySet()) {
            AbstractSpellPart part = entry.getValue();
            if (part == null || !part.isEnabled()) {
                continue;
            }
            ResolvedWeapon weapon = ofPart(part);
            if (weapon == null) {
                continue;
            }
            out.add(new WeaponCatalog.Entry(weapon,
                    () -> part.getGlyph() == null ? List.of() : List.of(new ItemStack(part.getGlyph()))));
        }
    }

    /**
     * 这次伤害是不是新生魔艺的法术打的。
     *
     * @param source 伤害源
     * @return 是返回 true
     */
    public static boolean isSpellDamage(net.minecraft.world.damagesource.DamageSource source) {
        return source instanceof com.hollingsworth.arsnouveau.api.util.DamageUtil.SpellDamageSource;
    }
}
