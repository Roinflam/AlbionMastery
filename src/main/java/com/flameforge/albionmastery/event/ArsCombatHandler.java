package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.compat.ArsNouveauSupport;
import com.hollingsworth.arsnouveau.api.event.EffectResolveEvent;
import com.hollingsworth.arsnouveau.api.event.SpellCostCalcEvent;
import com.hollingsworth.arsnouveau.api.event.SpellDamageEvent;
import com.hollingsworth.arsnouveau.api.spell.Spell;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import com.hollingsworth.arsnouveau.common.entity.EntityProjectileSpell;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import com.flameforge.albionmastery.mastery.spec.DamageAttribution;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
 *     <li><b>法力</b>：{@code SpellCostCalcEvent} 乘上特化倍率和专精折扣。</li>
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
     * 每 tick 末尾兜底清一次：别的模组取消了结算事件时魔艺不发 Post，记录会一直留着，
     * 之后任何实体的回血、药水效果都会被缩放。
     *
     * @param event 服务端 tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        resolvingCaster = null;
        resolvingStrength = 1.0D;
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
        // 单人世界客户端线程也会走到这里，读的却是服务端线程设的静态值；特化自己的回血也不放大
        if (resolvingCaster == null || event.getAmount() <= 0.0F || event.getEntity().level().isClientSide()
                || SpecializationCombatHandler.isSpecHealing()) {
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
        if (resolvingCaster == null || resolvingStrength <= 1.0D || event.getEntity().level().isClientSide()) {
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
        // 登记「接下来这一下伤害是哪个法术打的」：吸血、命中特效、击杀回血、嗜战在通用处理里按它认武器
        SpecializationValues spec = ArsNouveauSupport.spellSpec(player, spell);
        DamageAttribution.beginArsDamage(player, ArsNouveauSupport.primaryWeapon(spell), spec);
        // 特化数值上面刚算过，直接传进去复用，别让它在里面再合并一遍全部符文
        double multiplier = ArsNouveauSupport.spellStrength(player, spell, spec);
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
     * 法术伤害结算完：撤掉登记。
     *
     * <p>吸血以前在这里按「请求伤害」算，没扣护甲、也不按目标剩余血量封顶，打满抗性的目标照样全额回；
     * 现在交给 {@code SpecializationCombatHandler}，按实际扣掉的血算，和别的武器一致。
     *
     * @param event 伤害事件
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSpellDamagePost(SpellDamageEvent.Post event) {
        DamageAttribution.endArsDamage();
    }

    /**
     * 魔艺投射物出生：按形态 / 主效果特化缩放飞行速度、增加穿透。
     *
     * <p>和弓箭同一个做法：投射物在 {@code addFreshEntity} 之前已经 {@code shoot} 过、穿透也按增幅符文算好了，
     * 加入世界时直接改速度和剩余穿透数。区块重新加载进来的旧投射物不再改，否则每加载一次快一截。
     *
     * @param event 实体加入世界事件
     */
    @SubscribeEvent
    public static void onProjectileSpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk()
                || !(event.getEntity() instanceof EntityProjectileSpell projectile)) {
            return;
        }
        // 结算器从 resolver() 取：公有字段 spellResolver 已标记待删除，同一个对象由 setResolver 同时写进同步数据。
        // 同步数据的默认值是一个空法术的结算器，不会是 null，空法术算出来是中性值，不改速度、不加穿透
        if (!(projectile.getOwner() instanceof ServerPlayer player)) {
            return;
        }
        SpellResolver resolver = projectile.resolver();
        if (resolver == null) {
            return;
        }
        SpecializationValues spec = ArsNouveauSupport.projectileSpec(player, resolver.spell);
        if (spec.bulletSpeed() != 1.0D) {
            Vec3 motion = projectile.getDeltaMovement();
            projectile.setDeltaMovement(motion.scale(spec.bulletSpeed()));
        }
        int pierce = (int) Math.round(spec.pierce());
        if (pierce > 0) {
            projectile.pierceLeft += pierce;
        }
    }

    /**
     * 法力消耗按特化倍率和专精折扣缩放。
     *
     * <p>魔艺判定够不够法力时发 {@code SpellCostCalcEvent.Pre}，真正扣法力时发 {@code SpellCostCalcEvent.Post}，
     * 两次各从同一个原价算起。这里监听基类，两次都收到、都按同一个倍率折算，不会叠乘。
     * 不能只接 {@code Post}：那样判定阶段仍按原价，折扣大时会出现「够折后价却放不出」。
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
