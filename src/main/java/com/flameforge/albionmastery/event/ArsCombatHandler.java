package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.compat.ArsNouveauSupport;
import com.hollingsworth.arsnouveau.api.event.EffectResolveEvent;
import com.hollingsworth.arsnouveau.api.event.SpellCostCalcEvent;
import com.hollingsworth.arsnouveau.api.event.SpellDamageEvent;
import com.hollingsworth.arsnouveau.api.spell.Spell;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 新生魔艺的专精效果。
 *
 * <p>它的法术数值不像铁魔法那样有一个「法术强度」总闸：伤害是「基础 + 增幅数 × 每级」，
 * 治疗也是，效果时长也是，加法不是乘法，没法在一个地方乘倍率。所以分三路：
 * <ul>
 *     <li><b>伤害</b>：{@code SpellDamageEvent.Pre} 直接乘（强度 × 条件 × 暴击 × 连击）；</li>
 *     <li><b>治疗、效果时长</b>：每个效果结算前后有 {@code EffectResolveEvent.Pre / Post}，
 *         在这个窗口里记下「谁在放、强度多少」，窗口内发生的回血（{@code LivingHealEvent}）
 *         和上效果（{@code MobEffectEvent.Added}）按强度放大。结算是同步的、单线程的，
 *         窗口里发生的事一定是这个效果引起的；</li>
 *     <li><b>法力</b>：{@code SpellCostCalcEvent} 乘上流派倍率和专精折扣。</li>
 * </ul>
 * 新生魔艺没有冷却机制，冷却那一项对它不起作用。
 *
 * <p>方法签名里有新生魔艺的事件类，没装时不能让事件总线去反射它，由主类判断装了才手动注册。
 */
public final class ArsCombatHandler {

    /** 当前正在结算的效果的施法者；null 表示没在结算。 */
    private static ServerPlayer resolvingCaster;

    /** 当前正在结算的效果的强度倍率。 */
    private static double resolvingStrength = 1.0D;

    private ArsCombatHandler() {
    }

    /**
     * 效果结算开始：记下施法者和这个法术的强度。
     *
     * @param event 结算事件
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEffectResolvePre(EffectResolveEvent.Pre event) {
        resolvingCaster = null;
        resolvingStrength = 1.0D;
        if (event.world == null || event.world.isClientSide() || event.isCanceled()) {
            return;
        }
        if (!(event.shooter instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        double strength = ArsNouveauSupport.spellStrength(player, event.spell);
        if (strength > 0.0D && strength != 1.0D) {
            resolvingCaster = player;
            resolvingStrength = strength;
        }
    }

    /**
     * 效果结算结束：清掉记录，别让窗口外的回血也被放大。
     *
     * @param event 结算事件
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEffectResolvePost(EffectResolveEvent.Post event) {
        resolvingCaster = null;
        resolvingStrength = 1.0D;
    }

    /**
     * 结算窗口内的回血按强度放大（治疗符文、再生之类）。
     *
     * @param event 回血事件
     */
    @SubscribeEvent
    public static void onLivingHeal(LivingHealEvent event) {
        if (resolvingCaster == null || event.getAmount() <= 0.0F) {
            return;
        }
        event.setAmount((float) (event.getAmount() * resolvingStrength));
    }

    /**
     * 结算窗口内上的效果按强度延长持续时间（加速、缓慢、燃烧之类）。
     *
     * @param event 效果添加事件
     */
    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added event) {
        if (resolvingCaster == null || resolvingStrength <= 1.0D) {
            return;
        }
        MobEffectInstance instance = event.getEffectInstance();
        if (instance == null || instance.getDuration() <= 0 || instance.isInfiniteDuration()) {
            return;
        }
        int longer = (int) Math.round(instance.getDuration() * resolvingStrength);
        if (longer > instance.getDuration()) {
            instance.update(new MobEffectInstance(instance.getEffect(), longer, instance.getAmplifier(),
                    instance.isAmbient(), instance.isVisible(), instance.showIcon()));
        }
    }

    /**
     * 法术伤害结算前乘倍率。
     *
     * @param event 伤害事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onSpellDamage(SpellDamageEvent.Pre event) {
        if (event.isCanceled() || event.damage <= 0.0F || event.context == null) {
            return;
        }
        if (!(event.caster instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        Spell spell = event.context.getSpell();
        double multiplier = ArsNouveauSupport.spellStrength(player, spell);
        SpecializationValues spec = ArsNouveauSupport.primarySpec(player, spell);
        LivingEntity target = event.target instanceof LivingEntity living ? living : null;
        if (target != null && target != player) {
            multiplier *= SpecializationEffects.conditionalMultiplier(spec, player, target, null)
                    * ComboTracker.hit(player, target, spec);
        }
        multiplier *= SpecializationEffects.critMultiplier(spec, player.getRandom());
        if (Math.abs(multiplier - 1.0D) > 1.0E-6D) {
            event.damage = (float) (event.damage * multiplier);
        }
    }

    /**
     * 法术伤害结算后按主效果流派吸血。
     *
     * @param event 伤害事件
     */
    @SubscribeEvent
    public static void onSpellDamagePost(SpellDamageEvent.Post event) {
        if (event.damage <= 0.0F || event.context == null) {
            return;
        }
        if (!(event.caster instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        if (!(event.target instanceof LivingEntity target) || target == player) {
            return;
        }
        SpecializationValues spec = ArsNouveauSupport.primarySpec(player, event.context.getSpell());
        if (spec.lifeSteal() <= 0.0D || player.getHealth() >= player.getMaxHealth()) {
            return;
        }
        float heal = (float) (event.damage * spec.lifeSteal());
        if (heal > 0.0F) {
            player.heal(heal);
        }
    }

    /**
     * 法力消耗按流派倍率和专精折扣缩放。
     *
     * @param event 法力计算事件
     */
    @SubscribeEvent
    public static void onSpellCost(SpellCostCalcEvent event) {
        if (event.context == null || event.currentCost <= 0) {
            return;
        }
        LivingEntity caster = event.context.getUnwrappedCaster();
        double factor = ArsNouveauSupport.manaFactor(caster, event.context.getSpell());
        if (factor != 1.0D) {
            event.currentCost = Math.max(1, (int) Math.round(event.currentCost * factor));
        }
    }
}
