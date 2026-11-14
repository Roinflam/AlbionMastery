package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import io.redspace.ironsspellbooks.api.spells.SpellSlot;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 铁魔法（Iron's Spells 'n Spellbooks）的接入：把「法术」当成武器。
 *
 * <p>和别的来源最大的不同：<b>一件物品里可能装着好几个法术</b>。魔法书能装一排，
 * 卷轴只装一个，附魔进武器里的（imbued）也算一个。所以这里除了和别处一样的
 * 「一件物品 → 一件武器」，还多一个 {@link #resolveAll}「一件物品 → 一串武器」，
 * 击杀经验按后者发：拿着书，书里每个法术都拿。
 *
 * <p>来源 = 铁魔法，大类 = 学派（火、冰、雷、血……附属加的学派一样进），单件 = 法术。
 * 附属的法术和学派都注册在铁魔法自己的 Forge 注册表里，不需要为附属单独写任何东西。
 *
 * <p>只有这个类碰铁魔法的类，其它地方一律先问 {@code WeaponResolver.isIronsLoaded()} 再调进来，
 * 没装铁魔法时这个类根本不会被加载。
 */
public final class IronsSpellsSupport {

    private IronsSpellsSupport() {
    }

    /**
     * 这件物品里有没有装法术。
     *
     * @param stack 物品
     * @return 是法术容器（魔法书 / 卷轴 / 附魔武器）返回 true
     */
    public static boolean isContainer(ItemStack stack) {
        return !stack.isEmpty() && ISpellContainer.isSpellContainer(stack);
    }

    /**
     * 这件物品是不是魔法书（带法术转盘的容器）。卷轴和附魔进武器里的法术不带转盘。
     *
     * @param stack 物品
     * @return 是魔法书返回 true
     */
    public static boolean isSpellbook(ItemStack stack) {
        if (!isContainer(stack)) {
            return false;
        }
        try {
            ISpellContainer container = ISpellContainer.get(stack);
            return container != null && container.isSpellWheel();
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 「一件物品 → 一件武器」：取容器里的第一个法术。
     *
     * <p>给提示、按 B 定位这类只认一件的地方用。书里第一个法术就是最靠前的那一格。
     *
     * @param stack 物品
     * @return 第一个法术对应的武器；不是容器或者是空书返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack) {
        List<ResolvedWeapon> all = resolveAll(stack);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 「一件物品 → 一串武器」：容器里的每个法术各一件。
     *
     * @param stack 物品
     * @return 法术列表，按书里的格子顺序；不是容器或者是空书返回空表
     */
    public static List<ResolvedWeapon> resolveAll(ItemStack stack) {
        if (!isContainer(stack)) {
            return List.of();
        }
        ISpellContainer container = ISpellContainer.get(stack);
        if (container == null || container.isEmpty()) {
            return List.of();
        }
        List<SpellSlot> slots = container.getActiveSpells();
        List<ResolvedWeapon> result = new ArrayList<>(slots.size());
        for (SpellSlot slot : slots) {
            AbstractSpell spell = slot.getSpell();
            ResolvedWeapon weapon = ofSpell(spell);
            if (weapon != null) {
                result.add(weapon);
            }
        }
        return result;
    }

    /**
     * 玩家饰品栏（Curios 的 spellbook 槽）里装备着的魔法书。
     *
     * <p>铁魔法的书是放在饰品栏里用的，不在手上，所以发击杀经验时手里查不到它，要单独看这个槽。
     * 用铁魔法自己的工具方法取，不直接碰 Curios 的 API。
     *
     * @param player 玩家
     * @return 那本书；没装备返回空物品堆
     */
    public static ItemStack equippedSpellbook(Player player) {
        try {
            ItemStack stack = Utils.getPlayerSpellbookStack(player);
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Exception exception) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 玩家对某个法术的「法术强度」倍率：专精加成 × 特化的强度倍率。
     *
     * <p>铁魔法几乎所有法术的数值都从 {@code getSpellPower} 长出来：伤害是它，治疗量是它，
     * 缠绕 / 减速这类控制的持续时间是「它 × 20 tick」。所以专精加成乘在法术强度上，
     * 伤害、治疗、控制时长、书上和卷轴上显示的数字就全都一起跟着变，不用一个个去接。
     *
     * <p>两端都会调：客户端算提示框显示的数字，服务端算实际效果。客户端在关掉
     * {@code overrideDamageDisplay} 时返回 1，让显示保持原样（实际效果仍由服务端算）。
     *
     * @param player 施法者
     * @param spell  法术
     * @return 倍率；不认识的法术或没数据时是 1.0
     */
    public static double powerMultiplier(Player player, @Nullable AbstractSpell spell) {
        if (player.level().isClientSide() && !MasteryConfig.CLIENT.overrideDamageDisplay.get()) {
            return 1.0D;
        }
        ResolvedWeapon weapon = ofSpell(spell);
        if (weapon == null) {
            return 1.0D;
        }
        MasteryHelper.Bonus bonus = SpecializationEffects.bonusFor(player, weapon);
        SpecializationValues spec = SpecializationEffects.forWeapon(player, weapon);
        double multiplier = (bonus == null ? 1.0D : bonus.multiplier()) * spec.damage();
        return multiplier <= 0.0D ? 1.0D : multiplier;
    }

    /**
     * 专精加成折算出来的冷却 / 法力折扣倍率。
     *
     * <p>传送、净化、召唤末影箱这类法术没有任何数值可以乘，练满专精也看不到变化。
     * 所以专精加成按配置的比例再折一份到冷却和法力上（默认各 30%，上限减半），
     * 每个法术都能感觉到等级涨了，有数值的法术则是强度、冷却、法力三样一起受益。
     *
     * @param player 施法者
     * @param weapon 法术对应的武器
     * @param share  折算比例（配置里的 cooldownShare 或 manaShare）
     * @return 乘在冷却 / 法力上的倍率，1.0 表示不折
     */
    public static double masteryReductionFactor(Player player, @Nullable ResolvedWeapon weapon,
                                                double share) {
        if (weapon == null || share <= 0.0D) {
            return 1.0D;
        }
        MasteryHelper.Bonus bonus = SpecializationEffects.bonusFor(player, weapon);
        if (bonus == null || bonus.total() <= 0.0D) {
            return 1.0D;
        }
        // 按端取：客户端读服务端下发的快照，不读自己那份 COMMON 配置
        double cap = SpecializationEffects.snapshotFor(player).spellMasteryReductionCap;
        double reduction = Math.min(cap, bonus.total() * share);
        return Math.max(0.05D, 1.0D - reduction);
    }

    /**
     * 玩家在法术栏里<b>当前选中</b>的那个法术。
     *
     * <p>击杀经验只给它：不管书里塞了多少法术、手里拿的是不是它打的，只有选中的那个涨。
     * 否则拿一本满书随便杀点东西，十来个法术一起蹭经验。
     *
     * @param player 玩家
     * @return 选中的法术对应的武器；没选任何法术返回 null
     */
    @Nullable
    public static ResolvedWeapon selectedSpell(Player player) {
        try {
            SpellData data = new SpellSelectionManager(player).getSelectedSpellData();
            return data == null ? null : ofSpell(data.getSpell());
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 玩家当前施法用的装备槽名（铁魔法的 "spellbook" / "mainhand" / "offhand" 之类）。
     *
     * <p>补放要沿用第一次施放的槽，铁魔法靠它找施法物品和播动画。取不到就退回法术栏选中项的槽。
     *
     * @param player 玩家
     * @return 槽名；实在拿不到返回 null（铁魔法接受 null）
     */
    @Nullable
    public static String castingSlot(Player player) {
        try {
            MagicData magicData = MagicData.getPlayerMagicData(player);
            String slot = magicData == null ? null : magicData.getCastingEquipmentSlot();
            if (slot != null && !slot.isEmpty()) {
                return slot;
            }
            SpellSelectionManager.SelectionOption selection = new SpellSelectionManager(player).getSelection();
            return selection == null ? null : selection.slot;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 这个实体此刻正在施放的法术。
     *
     * <p>效果被加到身上的那一刻，铁魔法的施法状态里还记着「正在放哪个法术」，
     * 靠它把一个效果和一个法术对上号。没在施法返回 null。
     *
     * @param caster 施法者
     * @return 正在施放的法术；没在施法或认不出返回 null
     */
    @Nullable
    public static AbstractSpell castingSpell(@Nullable LivingEntity caster) {
        if (caster == null) {
            return null;
        }
        try {
            MagicData magicData = MagicData.getPlayerMagicData(caster);
            if (magicData == null || !magicData.isCasting() || magicData.getCastingSpell() == null) {
                return null;
            }
            return magicData.getCastingSpell().getSpell();
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 从一次伤害的来源认出是哪个法术打的。
     *
     * @param source 伤害源
     * @return 法术对应的武器；不是法术伤害返回 null
     */
    @Nullable
    public static ResolvedWeapon resolveDamage(DamageSource source) {
        if (source instanceof SpellDamageSource spellSource) {
            return ofSpell(spellSource.spell());
        }
        return null;
    }

    /**
     * 这个法术的专精倍率要不要乘进「法术强度」。
     *
     * <p>只有铁魔法本体的法术乘：本体每个用强度算半径、距离、生成数量的地方都核过，
     * 由 {@code mixin.range} 下的 Mixin 改成不含专精加成的强度。附属法术（学徒法典、风之魔法书……）
     * 拿强度算范围、召唤数量的地方没法一个个核，乘进去就会跟着专精一起放大成几倍，
     * 所以它们的专精倍率不进强度，改在受伤事件里只乘伤害（{@code SpellCombatHandler.onSpellHurt}）。
     *
     * @param spell 法术，可能为 null
     * @return 本体法术返回 true
     */
    public static boolean scalesSpellPower(@Nullable AbstractSpell spell) {
        if (spell == null) {
            return false;
        }
        ResourceLocation id = spell.getSpellResource();
        return id != null && IRONS_NAMESPACE.equals(id.getNamespace());
    }

    /** 铁魔法本体法术的命名空间。 */
    private static final String IRONS_NAMESPACE = "irons_spellbooks";

    /**
     * 一个法术对应的武器。
     *
     * @param spell 法术
     * @return 武器；空法术（占位用的 none）返回 null
     */
    @Nullable
    public static ResolvedWeapon ofSpell(@Nullable AbstractSpell spell) {
        if (spell == null || spell == SpellRegistry.none()) {
            return null;
        }
        ResourceLocation id = spell.getSpellResource();
        if (id == null) {
            return null;
        }
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_IRONS_SPELLS, id, typeOf(spell.getSchoolType()));
    }

    /**
     * 学派 → 类型字符串。
     *
     * <p>学派 ID 是 {@code irons_spellbooks:fire} 这种带冒号的，冒号在武器键里有别的含义，
     * 换成点号：{@code irons_spellbooks.fire}。客户端反过来解析时再把第一个点换回冒号。
     *
     * @param school 学派，可能为 null
     * @return 类型字符串
     */
    public static String typeOf(@Nullable SchoolType school) {
        if (school == null || school.getId() == null) {
            return WeaponKeys.TYPE_UNKNOWN;
        }
        return school.getId().getNamespace() + "." + school.getId().getPath();
    }

    /**
     * 类型字符串 → 学派 ID，{@link #typeOf} 的反向。
     *
     * @param type 类型字符串
     * @return 学派 ID；格式不对返回 null
     */
    @Nullable
    public static ResourceLocation schoolIdOf(String type) {
        int dot = type.indexOf('.');
        if (dot <= 0 || dot + 1 >= type.length()) {
            return null;
        }
        return ResourceLocation.tryParse(type.substring(0, dot) + ":" + type.substring(dot + 1));
    }

    /**
     * 把全部启用的法术列进清单，每个法术对应一张满级卷轴。
     *
     * @param out 输出
     */
    public static void catalog(List<WeaponCatalog.Entry> out) {
        // 直接遍历注册表：按注册编号的顺序，和武器清单其余部分同一个口径
        for (AbstractSpell spell : SpellRegistry.REGISTRY) {
            if (spell == null || spell == SpellRegistry.none() || !spell.isEnabled()) {
                continue;
            }
            ResolvedWeapon weapon = ofSpell(spell);
            if (weapon == null) {
                continue;
            }
            out.add(new WeaponCatalog.Entry(weapon, () -> {
                ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get());
                ISpellContainer.createScrollContainer(spell, spell.getMaxLevel(), scroll);
                return List.of(scroll);
            }));
        }
    }

    /**
     * 某名玩家放这个法术实际要花多少法力：特化倍率 × 专精折扣，和施法时真正扣的一致。
     *
     * <p>「够不够蓝」的判断和显示都要用这个值：以前它们按原价算，省蓝的特化在「够付折后价、不够付原价」时
     * 放不出来，加价的特化在「够原价、不够加价」时照样能放，多出来的代价就被免掉了。
     *
     * @param player 玩家
     * @param spell  法术
     * @param base   原价
     * @return 实际价格，原价大于 0 时至少为 1
     */
    public static int effectiveManaCost(@Nullable Player player, @Nullable AbstractSpell spell, int base) {
        if (player == null || spell == null || base <= 0) {
            return base;
        }
        ResolvedWeapon weapon = ofSpell(spell);
        double factor = SpecializationEffects.forWeapon(player, weapon).manaCost()
                * masteryReductionFactor(player, weapon,
                        SpecializationEffects.snapshotFor(player).spellMasteryManaShare);
        return factor == 1.0D ? base : Math.max(1, (int) Math.round(base * factor));
    }

    /**
     * 某名玩家放这个法术之后实际要等多久：特化倍率 × 专精折扣，和真正挂上去的冷却一致。
     *
     * <p>真正挂冷却时走的是 {@code SpellCooldownAddedEvent}（见 {@code SpellCombatHandler}），
     * 而提示和法术轮盘上写的秒数是另算的，不经过那个事件——不补这一条，界面上的数字就和实际等的时间对不上。
     *
     * @param player 玩家
     * @param spell  法术
     * @param base   原始冷却（tick）
     * @return 实际冷却，原值大于 0 时至少为 1
     */
    public static int effectiveCooldown(@Nullable Player player, @Nullable AbstractSpell spell, int base) {
        if (player == null || spell == null || base <= 0) {
            return base;
        }
        ResolvedWeapon weapon = ofSpell(spell);
        double factor = SpecializationEffects.forWeapon(player, weapon).spellCooldown()
                * masteryReductionFactor(player, weapon,
                        SpecializationEffects.snapshotFor(player).spellMasteryCooldownShare);
        return factor == 1.0D ? base : Math.max(1, (int) Math.round(base * factor));
    }
}
