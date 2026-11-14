package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import io.redspace.ironsspellbooks.api.events.ModifySpellLevelEvent;
import io.redspace.ironsspellbooks.api.events.SpellCooldownAddedEvent;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.world.item.ItemStack;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

import javax.annotation.Nullable;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.ArrayList;
import java.util.List;
import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import com.flameforge.albionmastery.event.damage.LivingHurtEvent;
import com.flameforge.albionmastery.mastery.spec.SpecMoveSpeed;
import io.redspace.ironsspellbooks.api.events.SpellPreCastEvent;

/**
 * 铁魔法法术的伤害加成。
 *
 * <p>本体法术的基础倍率（专精加成 × 特化强度）不在这里：它乘在「法术强度」上（{@code AbstractSpellPowerMixin}），
 * 伤害、治疗、控制时长、提示框显示一起吃到。这里的受伤事件只负责
 * 必须知道目标才能算的那几层：条件倍率、暴击、连击。
 * 附属法术的基础倍率不进强度（强度会放大它们的范围和召唤数量），在受伤事件里乘到伤害上，治疗和时长不加。
 *
 * <p>专精加成还会按配置比例折一份到冷却和法力上（{@code IronsSpellsSupport.masteryReductionFactor}），
 * 传送、净化这类没有数值的法术靠这个体现等级。
 *
 * <p>效果持续时间：铁魔法本体的增益 / 减益时长几乎全是「法术强度 × 20 tick」，强度涨了时长自然涨。
 * 少数时长只按等级写死的（多在附属里）列在配置 {@code spellEffectDurationSpells} 里，
 * 效果加到身上那一刻（{@code MobEffectEvent.Added}）按专精倍率延长，两条路互不重叠。
 *
 * <p><b>不在这里发经验</b>：经验统一是「手里拿着东西、周围有怪死」那一套，在
 * {@code MasteryExperienceHook} 里，法术也一样——拿着书，书里每个法术都拿击杀经验。
 *
 * <p>特化里法术专属的三项也在这里生效：
 * <ul>
 *     <li>法力消耗：{@code SpellOnCastEvent}，施放前改 mana cost；</li>
 *     <li>冷却时间：{@code SpellCooldownAddedEvent.Pre}，冷却挂上去之前改时长；</li>
 *     <li>法术等级：{@code ModifySpellLevelEvent}，铁魔法算法术等级时统一走它。
 *         等级会间接放大范围和实体数量，运行快照里这一项恒为 0，这里实际不再加等级。</li>
 * </ul>
 * 这几个事件在客户端也会触发（用于预测和显示），所以取数据走 {@code forWeapon}，两端各读各的。
 *
 * <p>这个类的方法签名里有铁魔法的事件类，没装铁魔法时不能碰它，所以不用
 * {@code @EventBusSubscriber} 自动注册，而是主类里判断装了才手动注册。
 */
public final class SpellCombatHandler {

    private SpellCombatHandler() {
    }

    /**
     * 法术伤害按「看目标」的那几层特化缩放：条件倍率、暴击、连击。
     *
     * <p>挂在通用的受伤事件上、按伤害源是不是铁魔法的法术伤害来认，而不是铁魔法自己的法术伤害事件：
     * 地震、火山爆发、召唤出来的武器和生物是直接造成伤害的，不发那个事件，以前这几层对它们都不生效；
     * 那个事件又发在友伤判定之前，被挡掉的伤害也会叠连击、掷暴击。受伤事件只在伤害真的要结算时才来。
     *
     * @param event 受伤事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onSpellHurt(LivingHurtEvent event) {
        if (!(event.getSource() instanceof SpellDamageSource spellSource)) {
            return;
        }
        float amount = event.getAmount();
        if (amount <= 0.0F) {
            return;
        }
        if (!(spellSource.getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target == player || !target.isAlive()) {
            return;
        }
        ResolvedWeapon weapon = IronsSpellsSupport.resolveDamage(spellSource);
        if (weapon == null) {
            return;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        SpecializationValues spec = SpecializationEffects.forData(data, weapon, snapshot);

        // 本体法术的专精加成和特化强度倍率已经在 AbstractSpellPowerMixin 里乘进法术强度了，
        // 这里只乘「看目标」才知道的那几层：条件倍率、暴击、连击。再乘一遍基础倍率就是双倍。
        // 附属法术的倍率不进强度（强度会放大它们的范围和召唤数量），基础倍率在这里乘到伤害上
        double multiplier = SpecializationEffects.conditionalMultiplier(spec, player, target, null)
                * SpecializationEffects.critMultiplier(spec, player.getRandom())
                * ComboTracker.hit(player, target, spec);
        if (!IronsSpellsSupport.scalesSpellPower(spellSource.spell())) {
            multiplier *= IronsSpellsSupport.powerMultiplier(player, spellSource.spell());
        }
        if (Math.abs(multiplier - 1.0D) > 1.0E-6D) {
            event.setAmount((float) (amount * multiplier));
        }
    }

    /**
     * 开始吟唱：把施法倍速挂成会同步的属性，别人才能按同一个速度看到施法动作。
     *
     * <p>为什么不在 {@code SpellOnCastEvent} 里做：那个事件是「吟唱完、法术真正放出去」时才触发的，
     * 而施法动作在吟唱一开始就播了——等到那时候再写，属性永远慢一整次施法。
     * 这个事件在铁魔法发「开始吟唱」的包之前触发，正好赶得上。
     *
     * @param event 施法前事件
     */
    @SubscribeEvent
    public static void onSpellPreCast(SpellPreCastEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer caster)) {
            return;
        }
        ResourceLocation spellId = ResourceLocation.tryParse(event.getSpellId());
        AbstractSpell spell = spellId == null ? null : SpellRegistry.getSpell(spellId);
        // 只有长吟唱才有前摇动作；瞬发和持续引导的施法时间我们本来就不改
        if (spell == null || spell.getCastType() != CastType.LONG) {
            SpecMoveSpeed.refreshCastSpeed(caster, 1.0D);
            return;
        }
        SpecializationValues spec = SpecializationEffects.forWeapon(caster, IronsSpellsSupport.ofSpell(spell));
        double castTime = spec.castTime();
        // 补放那一次的引导时间被 SpellCastTimeMixin 额外乘了一道系数（默认减半），
        // 动画不跟着乘的话，动作才抬到一半法术就放出去了，看着像被打断
        if (isEchoCasting(caster)) {
            castTime *= MasteryConfig.COMMON.doubleCastTimeFactor.get();
        }
        SpecMoveSpeed.refreshCastSpeed(caster, castTime);
    }

    /**
     * 施法时按特化缩放法力消耗。
     *
     * @param event 施法事件
     */
    @SubscribeEvent
    public static void onSpellCast(SpellOnCastEvent event) {
        // 这个事件不可取消，不用问 isCanceled
        if (event.getEntity() == null) {
            return;
        }
        ResourceLocation spellId = ResourceLocation.tryParse(event.getSpellId());
        if (spellId == null) {
            return;
        }
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        ResolvedWeapon weapon = IronsSpellsSupport.ofSpell(spell);
        SpecializationValues spec = SpecializationEffects.forWeapon(event.getEntity(), weapon);
        if (MasteryConfig.COMMON.debugLogging.get()) {
            com.flameforge.albionmastery.AlbionMastery.LOGGER.info(
                    "[albionmastery] 施法事件: {} 来源 {} 端 {} 法力倍率 {} 双重施法 {}",
                    spellId, event.getCastSource(), event.getEntity().level().isClientSide() ? "客户端" : "服务端",
                    spec.manaCost(), spec.doubleCast());
        }
        // 特化倍率 × 专精折扣（后者让没有数值的法术也能感觉到等级涨了）
        double factor = spec.manaCost() * IronsSpellsSupport.masteryReductionFactor(
                event.getEntity(), weapon,
                SpecializationEffects.snapshotFor(event.getEntity()).spellMasteryManaShare);
        if (factor != 1.0D && event.getManaCost() > 0) {
            // 至少留 1 点，别把付费法术变成免费的
            event.setManaCost(Math.max(1, (int) Math.round(event.getManaCost() * factor)));
        }
        queueDoubleCast(event, spell, spec);
    }

    /** 等着下一 tick 补放的双重施法。 */
    private static final Deque<PendingCast> PENDING_CASTS = new ArrayDeque<>();

    /** 正在进行的补放：它结束时挂的那次冷却要取消。玩家 UUID -&gt; 记录。 */
    private static final java.util.Map<UUID, EchoRecord> ECHO_COOLDOWN_SKIP = new java.util.HashMap<>();

    /** 补放开始后多久之内结束才算（tick）：吟唱最长的法术也在这之内放完。 */
    private static final long ECHO_COOLDOWN_WINDOW = 400L;

    /** 一次补放：哪个法术、记录什么时候过期（世界 tick）。 */
    private record EchoRecord(AbstractSpell spell, long expiresAt) {
    }

    /** 玩家正在施法时，补放隔几 tick 再试一次、最多试几次。 */
    private static final int ECHO_RETRY_TICKS = 5;
    private static final int ECHO_MAX_RETRIES = 8;

    /**
     * 服务端关掉时清空补放队列：单人世界里退出再进一个游戏时间更早的存档，
     * 旧队首永远不到期，后面的补放全被卡住，还一直攥着上一个世界的玩家。
     *
     * @param event 服务端停止事件
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING_CASTS.clear();
        ECHO_COOLDOWN_SKIP.clear();
    }

    /**
     * 双重施法：这次施放成功后，按概率排一次免费的补放。
     *
     * <p>补放走的是和玩家按键一样的完整流程（{@code attemptInitiateCast}）：有引导的照样引导，
     * 引导时间乘配置里的系数（默认减半），施法条、动画、音效都和正常施法一样。
     * 用 {@code CastSource.COMMAND}：铁魔法对这个来源不扣法力、不算冷却。
     * 持续引导类和召唤类不补（见 {@link #canDoubleCast}）。
     * 补放本身也会触发施法事件，靠来源是 COMMAND 认出来跳过，不会无限套娃。
     * 不在事件里直接放，排到下一 tick：事件触发时铁魔法还在这次施放的中间，立刻再放会打架。
     *
     * @param event 施法事件
     * @param spell 法术
     * @param spec  特化数值
     */
    private static void queueDoubleCast(SpellOnCastEvent event, @Nullable AbstractSpell spell,
                                        SpecializationValues spec) {
        if (spell == null || spec.doubleCast() <= 0.0D || event.getCastSource() == CastSource.COMMAND) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!canDoubleCast(spell)) {
            return;
        }
        // 可重复施放的法术（雷霆步、传送门、邪能冲击之类）补放会被当成「再放一次」，
        // 直接消耗掉玩家的一次重复施放机会（比如 0.2 秒后自动传回去），不补
        if (spell.getRecastCount(event.getSpellLevel(), player) > 0) {
            return;
        }
        if (player.getRandom().nextDouble() >= spec.doubleCast()) {
            return;
        }
        // 记下这次是从哪个装备槽放的（书 / 主手 / 副手），补放时沿用，铁魔法靠它找施法物品
        String slot = IronsSpellsSupport.castingSlot(player);
        PENDING_CASTS.add(new PendingCast(player, spell, event.getSpellLevel(), slot,
                player.level().getGameTime() + DOUBLE_CAST_DELAY_TICKS, 0));
        if (MasteryConfig.COMMON.debugLogging.get()) {
            com.flameforge.albionmastery.AlbionMastery.LOGGER.info("[albionmastery] 双重施法排队: {} 玩家 {} 概率 {}",
                    spell.getSpellResource(), player.getGameProfile().getName(), spec.doubleCast());
        }
    }

    /** 第一次施放到自动补放之间隔多少 tick（0.2 秒），让两发看得出是两发。 */
    private static final int DOUBLE_CAST_DELAY_TICKS = 4;

    /**
     * 下一 tick 把排好的补放真正放出去。
     *
     * @param event 服务端 tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING_CASTS.isEmpty() || event.getServer() == null) {
            return;
        }
        long now = event.getServer().overworld().getGameTime();
        List<PendingCast> deferred = new ArrayList<>();
        // 队列按入队顺序，到时间的在前面；碰到第一个没到时间的就停，剩下的下 tick 再看
        while (!PENDING_CASTS.isEmpty() && PENDING_CASTS.peek().dueTick() <= now) {
            PendingCast pending = PENDING_CASTS.poll();
            ServerPlayer player = pending.player();
            if (player.isRemoved() || !player.isAlive()) {
                continue;
            }
            // 玩家已经在读下一个法术了：attemptInitiateCast 发现正在施法会直接取消当前的施法，
            // 补放反而把玩家自己的法术打断，补放本身也放不出来。晚几 tick 再试，等太久就算了
            if (MagicData.getPlayerMagicData(player).isCasting()) {
                if (pending.attempts() < ECHO_MAX_RETRIES) {
                    deferred.add(new PendingCast(player, pending.spell(), pending.level(), pending.slot(),
                            now + ECHO_RETRY_TICKS, pending.attempts() + 1));
                }
                continue;
            }
            // 施法物品：饰品栏的书优先，没有就主手（卷轴 / 附魔武器）
            ItemStack castingItem = IronsSpellsSupport.equippedSpellbook(player);
            if (castingItem.isEmpty()) {
                castingItem = player.getMainHandItem();
            }
            // 标记「这次是补放」：SpellCastTimeMixin 看到标记就把引导时间乘系数；
            // attemptInitiateCast 里算引导时间是同步的，调完立刻摘标记
            ECHO_CASTING.add(player.getUUID());
            try {
                boolean started = pending.spell().attemptInitiateCast(castingItem, pending.level(),
                        player.level(), player, CastSource.COMMAND, false, pending.slot());
                if (started) {
                    // 补放结束时铁魔法会照常挂一次冷却（它不看「不触发冷却」那个参数），
                    // 冷却条就在补放后又从头走一遍。记下来，冷却事件里认出这一次就取消
                    ECHO_COOLDOWN_SKIP.put(player.getUUID(), new EchoRecord(pending.spell(), now + ECHO_COOLDOWN_WINDOW));
                }
                if (MasteryConfig.COMMON.debugLogging.get()) {
                    com.flameforge.albionmastery.AlbionMastery.LOGGER.info(
                            "[albionmastery] 双重施法补放: {} 玩家 {} 结果 {}",
                            pending.spell().getSpellResource(), player.getGameProfile().getName(), started);
                }
            } catch (Exception exception) {
                com.flameforge.albionmastery.AlbionMastery.LOGGER.warn("双重施法补放失败: {}",
                        pending.spell().getSpellResource(), exception);
            } finally {
                ECHO_CASTING.remove(player.getUUID());
            }
        }
        PENDING_CASTS.addAll(deferred);
    }

    /**
     * 一次排队的补放。
     *
     * @param player 施法者
     * @param spell  法术
     * @param level  法术等级
     */
    private record PendingCast(ServerPlayer player, AbstractSpell spell, int level, String slot, long dueTick,
                               int attempts) {
    }

    /** 正在补放的玩家，给施法时间 Mixin 看。 */
    private static final Set<UUID> ECHO_CASTING = new HashSet<>();

    /**
     * 这个玩家此刻是不是在补放（引导时间要乘系数）。
     *
     * @param player 玩家
     * @return 是返回 true
     */
    public static boolean isEchoCasting(net.minecraft.world.entity.player.Player player) {
        return ECHO_CASTING.contains(player.getUUID());
    }

    /**
     * 这个法术能不能双重施法。
     *
     * <p>持续引导类（按住持续放的）不补：补放会让玩家平白进入一段引导。
     * 召唤类不补：召唤物翻倍是另一个量级的强度，而且很多召唤法术有数量上限、补放会把前一批替掉。
     * 召唤类按类名认（铁魔法和附属的召唤法术类名都带 Summon），类名里没有的在配置里点名。
     *
     * @param spell 法术
     * @return 能返回 true
     */
    private static boolean canDoubleCast(AbstractSpell spell) {
        if (spell.getCastType() == CastType.CONTINUOUS) {
            return false;
        }
        if (spell.getClass().getSimpleName().contains("Summon")) {
            return false;
        }
        ResourceLocation id = spell.getSpellResource();
        return id == null || !MasteryConfig.COMMON.doubleCastExcludedSpells.get().contains(id.toString());
    }

    /**
     * 冷却挂上去之前按特化缩放时长。
     *
     * @param event 冷却事件
     */
    @SubscribeEvent
    public static void onSpellCooldown(SpellCooldownAddedEvent.Pre event) {
        if (event.isCanceled() || event.getEntity() == null) {
            return;
        }
        if (event.getCastSource() == CastSource.COMMAND && !event.getEntity().level().isClientSide()) {
            EchoRecord echo = ECHO_COOLDOWN_SKIP.get(event.getEntity().getUUID());
            if (echo != null && echo.spell() == event.getSpell()
                    && event.getEntity().level().getGameTime() <= echo.expiresAt()) {
                ECHO_COOLDOWN_SKIP.remove(event.getEntity().getUUID());
                event.setCanceled(true);
                return;
            }
        }
        ResolvedWeapon weapon = IronsSpellsSupport.ofSpell(event.getSpell());
        SpecializationValues spec = SpecializationEffects.forWeapon(event.getEntity(), weapon);
        double factor = spec.spellCooldown() * IronsSpellsSupport.masteryReductionFactor(
                event.getEntity(), weapon,
                SpecializationEffects.snapshotFor(event.getEntity()).spellMasteryCooldownShare);
        if (factor != 1.0D && event.getEffectiveCooldown() > 0) {
            event.setEffectiveCooldown(Math.max(1, (int) Math.round(event.getEffectiveCooldown() * factor)));
        }
    }

    /**
     * 算法术等级时按特化加等级。
     *
     * @param event 法术等级事件
     */
    @SubscribeEvent
    public static void onModifySpellLevel(ModifySpellLevelEvent event) {
        if (event.getEntity() == null) {
            return;
        }
        ResolvedWeapon weapon = IronsSpellsSupport.ofSpell(event.getSpell());
        SpecializationValues spec = SpecializationEffects.forWeapon(event.getEntity(), weapon);
        int add = (int) Math.round(spec.spellLevel());
        if (add > 0) {
            event.addLevels(add);
        }
    }

    /**
     * 配置里点名的法术：效果加到身上时按专精倍率延长持续时间。
     *
     * <p>效果和法术怎么对上号：效果的来源实体正在施法就按它，否则被加效果的实体自己正在施法
     * （自己给自己上增益）就按它。两边都不在施法说明是别的东西上的效果，不动。
     *
     * <p>延长用的是 {@code MobEffectInstance.update}：只在新时长更长时覆盖，等级不变，
     * 和原版效果刷新走同一条路，不会把隐藏效果之类的字段弄乱。
     *
     * @param event 效果添加事件
     */
    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added event) {
        LivingEntity target = event.getEntity();
        if (target == null || target.level().isClientSide()) {
            return;
        }
        MobEffectInstance instance = event.getEffectInstance();
        if (instance == null || instance.getDuration() <= 0 || instance.isInfiniteDuration()) {
            return;
        }
        ServerPlayer caster = null;
        AbstractSpell spell = null;
        Entity source = event.getEffectSource();
        if (source instanceof ServerPlayer sourcePlayer) {
            spell = IronsSpellsSupport.castingSpell(sourcePlayer);
            caster = spell == null ? null : sourcePlayer;
        }
        if (spell == null && target instanceof ServerPlayer self) {
            spell = IronsSpellsSupport.castingSpell(self);
            caster = spell == null ? null : self;
        }
        if (spell == null || caster == null || caster.isSpectator()) {
            return;
        }
        ResourceLocation spellId = spell.getSpellResource();
        if (spellId == null || !MasteryConfig.COMMON.spellEffectDurationSpells.get().contains(spellId.toString())) {
            return;
        }
        ResourceLocation effectId = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value());
        if (effectId == null || isCastStateEffect(effectId)
                || MasteryConfig.COMMON.spellEffectDurationIgnoredEffects.get().contains(effectId.toString())) {
            return;
        }
        double multiplier = IronsSpellsSupport.powerMultiplier(caster, spell);
        if (multiplier <= 1.0D) {
            return;
        }
        int longer = (int) Math.round(instance.getDuration() * multiplier);
        if (longer > instance.getDuration()) {
            instance.update(new MobEffectInstance(instance.getEffect(), longer, instance.getAmplifier(),
                    instance.isAmbient(), instance.isVisible(), instance.showIcon()));
        }
    }

    /**
     * 是不是施法状态类的效果（铁魔法用效果来标记「正在吟唱」「正在循环」这类状态）。
     *
     * <p>这些的时长就是施法时长本身，延长了会让施法卡住，必须跳过。按 ID 的写法认：
     * 以 {@code _cast} / {@code _loop} 结尾，或者含 {@code casting} / {@code resource}。
     *
     * @param effectId 效果 ID
     * @return 是施法状态效果返回 true
     */
    private static boolean isCastStateEffect(ResourceLocation effectId) {
        String path = effectId.getPath();
        return path.endsWith("_cast") || path.endsWith("_loop")
                || path.contains("casting") || path.contains("resource");
    }
}
