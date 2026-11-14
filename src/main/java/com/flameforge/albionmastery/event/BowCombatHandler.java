package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.SpecKnockback;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.BowSupport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.ArrowLooseEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * 弓弩的专精效果。只用原版 / Forge 事件，不依赖任何模组。
 *
 * <ul>
 *     <li>箭出生时（{@code EntityJoinLevelEvent}）：记下是哪把弓射的，按特化缩放箭速、加穿透；</li>
 *     <li>省箭：松手（弩是上弦）时记下背包里的箭有多少，这一 tick 结束后核对确实少了一支，才按概率退还；</li>
 *     <li>箭命中时（{@code LivingHurtEvent}，直接来源是箭）：按记下的那把弓乘专精、特化、条件（含爆头）、暴击、连击，
 *         并登记击退倍率；</li>
 *     <li>拉弓松手时（{@code ArrowLooseEvent}）：按特化缩放蓄力值，等于拉满更快。弩的上弦在
 *         {@code CrossbowDrawMixin} 里用同一个倍率处理。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID)
public final class BowCombatHandler {

    private BowCombatHandler() {
    }

    /**
     * 箭（含投出去的三叉戟）出生：记武器、缩放箭速。
     *
     * @param event 实体加入世界事件
     */
    @SubscribeEvent
    public static void onArrowSpawn(EntityJoinLevelEvent event) {
        // 区块重新加载进来的旧箭不再处理：不然每加载一次箭速就再乘一遍、穿透再加一遍
        if (event.getLevel().isClientSide() || event.loadedFromDisk()
                || !(event.getEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        if (!(arrow.getOwner() instanceof ServerPlayer player)) {
            return;
        }
        ResolvedWeapon weapon = shooterWeapon(player, arrow);
        if (weapon == null) {
            return;
        }
        BowSupport.tagArrow(arrow, weapon);

        // 箭速沿用枪械的「子弹初速」那一项
        SpecializationValues spec = SpecializationEffects.forWeapon(player, weapon);
        double speed = spec.bulletSpeed();
        if (speed != 1.0D && speed > 0.0D) {
            Vec3 motion = arrow.getDeltaMovement();
            arrow.setDeltaMovement(motion.scale(speed));
            // 原版箭的伤害 = 速度 × 基础伤害，箭速 ×1.3 等于顺带把伤害也 ×1.3，和特化卡上写的对不上。
            // 这里把基础伤害按同一倍率除回去，箭速就只是箭速。三叉戟的伤害是固定值，不用补
            if (!(arrow instanceof ThrownTrident)) {
                arrow.setBaseDamage(arrow.getBaseDamage() / speed);
            }
        }
        if (!WeaponKeys.PROVIDER_BOW.equals(weapon.providerId())) {
            // 投出去的三叉戟只借用箭速这一项，穿透和省箭是弓弩的事
            return;
        }
        // 穿透沿用枪械那一项：原版的穿透等级就是「还能再穿几个」，直接往上加
        int pierce = (int) Math.round(spec.pierce());
        if (pierce > 0) {
            arrow.setPierceLevel((byte) Math.min(127, arrow.getPierceLevel() + pierce));
        }
        if (arrow.pickup == AbstractArrow.Pickup.ALLOWED) {
            PendingShot shot = PENDING_SHOTS.get(player.getUUID());
            if (shot != null && shot.arrow == null) {
                // 弓：记下这一箭，等 tick 结束核对背包再决定退不退
                shot.arrow = arrow;
            } else {
                // 弩：上弦时已经退过箭的，射出去这支不能再捡
                consumeFreeCrossbowShot(player, arrow);
            }
        }
    }

    // 省箭
    //
    // 为什么不能看「箭能不能捡」来判断扣没扣箭：原版确实是「扣了箭 = 可拾取」，但整合包里的模组弓、
    // 无限弓、箭袋各有各的写法，有的不扣箭却留着可拾取，照那个判断退箭就成了每射一箭白送一支。
    // 所以只认一件事：背包里同种的箭，射之前和射之后是不是真的少了。没少（无限附魔、创造模式、
    // 模组的无限弓、从箭袋饰品里扣的）一律不退；每一箭最多退一支，一次射出多支的模组也刷不了。

    /** 弩上弦时已经退过一支箭的标记，下一支从这把弩射出去的箭不可拾取。 */
    private static final String FREE_SHOT_TAG = "albionmastery_free_shot";

    /** 还没核对的弓箭射击。只在服务端主线程读写。 */
    private static final Map<UUID, PendingShot> PENDING_SHOTS = new HashMap<>();

    /** 正在上弦的那一次（{@code CrossbowItem.releaseUsing} 前后各调一次，中间不会穿插别的玩家）。 */
    private static PendingShot pendingLoad;

    /**
     * 一次待核实的省箭。
     */
    private static final class PendingShot {

        /** 射手。 */
        private final ServerPlayer player;

        /** 这一箭用的弹药，一支。退还时原样给回去（药箭、光灵箭都保留）。 */
        private final ItemStack ammo;

        /** 射之前背包里同种弹药的数量。 */
        private final int countBefore;

        /** 省箭概率。 */
        private final double chance;

        /** 这一次射出去的那支箭；弓在出生时补上，弩不用。 */
        private AbstractArrow arrow;

        private PendingShot(ServerPlayer player, ItemStack ammo, int countBefore, double chance) {
            this.player = player;
            this.ammo = ammo;
            this.countBefore = countBefore;
            this.chance = chance;
        }
    }

    /**
     * 松手（弓）或上弦（弩）之前：特化有省箭、玩家不是创造模式、手上确实有弹药，就记一笔待核实。
     *
     * @param player 射手
     * @param weapon 弓或弩
     * @return 待核实记录；不需要核实时返回 null
     */
    private static PendingShot beginShot(ServerPlayer player, ItemStack weapon) {
        if (player.getAbilities().instabuild) {
            return null;
        }
        double chance = SpecializationEffects.forWeapon(player, BowSupport.resolve(weapon)).ammoSave();
        if (chance <= 0.0D) {
            return null;
        }
        // 背包里没箭、靠无限附魔射时，这里拿到的是一支凭空造出来的箭，背包里数到 0，后面也不会少，自然不退
        ItemStack ammo = player.getProjectile(weapon);
        if (ammo.isEmpty()) {
            return null;
        }
        return new PendingShot(player, ammo.copyWithCount(1), countAmmo(player, ammo), chance);
    }

    /**
     * 核对一次射击：背包里的弹药确实少了才按概率退一支。
     *
     * @param shot 待核实记录
     * @return 退了返回 true
     */
    private static boolean settle(PendingShot shot) {
        if (shot.player.isRemoved() || countAmmo(shot.player, shot.ammo) >= shot.countBefore) {
            return false;
        }
        if (shot.player.getRandom().nextDouble() >= shot.chance) {
            return false;
        }
        ItemStack refund = shot.ammo.copy();
        if (!shot.player.getInventory().add(refund)) {
            shot.player.drop(refund, false);
        }
        return true;
    }

    /**
     * 每个服务端 tick 结束时核对还没核对的弓箭射击。松手可能发生在两个 tick 之间（客户端发来的松手包），
     * 那一笔会在下一个 tick 结束时核对，那时扣箭早就做完了。
     *
     * @param event 服务端 tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_SHOTS.isEmpty()) {
            return;
        }
        for (PendingShot shot : PENDING_SHOTS.values()) {
            // 没射出箭（蓄力不够、被别的模组取消了）就什么都不做
            if (shot.arrow != null && settle(shot)) {
                // 已经退了一支，射出去的这支就不能再捡，否则一支变两支
                shot.arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            }
        }
        PENDING_SHOTS.clear();
    }

    /**
     * 弩上弦之前（{@code CrossbowItem.releaseUsing} 开头）。
     *
     * @param player   射手
     * @param crossbow 弩
     */
    public static void beforeCrossbowLoad(ServerPlayer player, ItemStack crossbow) {
        pendingLoad = CrossbowItem.isCharged(crossbow) ? null : beginShot(player, crossbow);
    }

    /**
     * 弩上弦之后（{@code CrossbowItem.releaseUsing} 结尾）：真装上了、背包里的弹药真少了，才按概率退一支，
     * 并在弩上记一笔，这一发射出去的箭不可拾取。弩的弹药是上弦时扣的，所以在这里核对，不等射出去。
     *
     * @param player   射手
     * @param crossbow 弩
     */
    public static void afterCrossbowLoad(ServerPlayer player, ItemStack crossbow) {
        PendingShot load = pendingLoad;
        pendingLoad = null;
        if (load == null || load.player != player || !CrossbowItem.isCharged(crossbow) || !settle(load)) {
            return;
        }
        // 烟花火箭射出去本来就捡不回来，只有箭才需要标记
        if (load.ammo.getItem() instanceof ArrowItem) {
            crossbow.getOrCreateTag().putBoolean(FREE_SHOT_TAG, true);
        }
    }

    /**
     * 从上弦时退过箭的弩射出去的箭改成不可拾取，并摘掉标记。
     *
     * @param player 射手
     * @param arrow  箭
     */
    private static void consumeFreeCrossbowShot(ServerPlayer player, AbstractArrow arrow) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            CompoundTag tag = stack.getTag();
            if (stack.getItem() instanceof CrossbowItem && tag != null && tag.getBoolean(FREE_SHOT_TAG)) {
                tag.remove(FREE_SHOT_TAG);
                arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
                return;
            }
        }
    }

    /**
     * 数背包（含副手、盔甲栏）里和这支弹药完全一样（物品和 NBT 都一样）的数量。
     *
     * @param player 玩家
     * @param ammo   弹药
     * @return 数量
     */
    private static int countAmmo(ServerPlayer player, ItemStack ammo) {
        Inventory inventory = player.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (ItemStack.isSameItemSameTags(stack, ammo)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 拉弓松手：记下射之前背包里的箭，给省箭特化核对。
     *
     * @param event 松手事件
     */
    @SubscribeEvent
    public static void onArrowLoose(ArrowLooseEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ResolvedWeapon weapon = BowSupport.resolve(event.getBow());
        if (weapon == null) {
            return;
        }
        // 蓄力值不在这里改：拉弓的计时本身已经按特化倍速走了（见 UseItemSpeedMixin），
        // 事件里的蓄力就是缩放过的，和玩家看到的弓弦拉开程度一致，再乘一次就是双份
        // 松手事件在扣箭之前触发，正好记下射之前背包里有多少
        PendingShot shot = beginShot(player, event.getBow());
        if (shot != null) {
            PENDING_SHOTS.put(player.getUUID(), shot);
        } else {
            PENDING_SHOTS.remove(player.getUUID());
        }
    }

    /**
     * 箭命中：按射它的那把弓乘倍率。
     *
     * @param event 伤害事件
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onArrowHit(LivingHurtEvent event) {
        if (!(event.getSource().getDirectEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target == player || event.getAmount() <= 0.0F) {
            return;
        }
        ResolvedWeapon weapon = BowSupport.fromArrow(arrow);
        if (weapon == null) {
            return;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        MasteryHelper.Bonus bonus = MasteryHelper.computeBonus(data, weapon, snapshot);
        SpecializationValues spec = SpecializationEffects.forData(data, weapon, snapshot);
        // 箭也分爆头：只有特化真的在乎爆头时才去算命中点，省一次射线求交
        Boolean headShot = spec.headShotDamage() != 1.0D || spec.bodyShotDamage() != 1.0D
                ? isHeadShot(arrow, target) : null;
        double multiplier = bonus.multiplier() * spec.damage()
                * SpecializationEffects.conditionalMultiplier(spec, player, target, headShot)
                * SpecializationEffects.critMultiplier(spec, player.getRandom())
                * ComboTracker.hit(player, target, spec);
        if (Math.abs(multiplier - 1.0D) > 1.0E-6D) {
            event.setAmount((float) (event.getAmount() * multiplier));
        }
        SpecKnockback.mark(target, player.getUUID(), spec.knockback());
    }

    /**
     * 这支箭是不是射在头上。
     *
     * <p>伤害结算时箭还停在这一 tick 的起点，离目标可能有好几格，直接看箭的高度不准。
     * 所以沿着箭这一 tick 的飞行路线和目标的碰撞箱求交，拿到真正的命中点，
     * 命中点高于「眼睛往下一点」就算爆头——对玩家大约是顶上 0.4 格，正好是头。
     *
     * @param arrow  箭
     * @param target 目标
     * @return 爆头返回 true
     */
    private static boolean isHeadShot(AbstractArrow arrow, LivingEntity target) {
        Vec3 start = arrow.position();
        Vec3 end = start.add(arrow.getDeltaMovement());
        double hitY = target.getBoundingBox().inflate(0.3D).clip(start, end)
                .map(hit -> hit.y).orElse(arrow.getY());
        return hitY >= target.getEyeY() - target.getBbHeight() * 0.12D;
    }

    /**
     * 玩家手里的弓或弩，主手优先。
     *
     * @param player 玩家
     * @return 武器；两手都没有返回 null
     */
    /**
     * 射出这支箭（或扔出这把三叉戟）的武器。
     *
     * @param player 射手
     * @param arrow  箭或三叉戟
     * @return 武器；认不出返回 null
     */
    @Nullable
    private static ResolvedWeapon shooterWeapon(ServerPlayer player, AbstractArrow arrow) {
        // 拉弓松手、扔三叉戟都是在「使用」结束的那一刻生出实体，这时正在用的那件就是射它的武器，
        // 哪只手都认得对。以前优先看主手：主手拿弓、副手弩射的箭会被记成弓；副手拿弓时扔的三叉戟也被记成弓
        ItemStack using = player.getUseItem();
        if (!using.isEmpty()) {
            ResolvedWeapon weapon = arrow instanceof ThrownTrident
                    ? WeaponResolver.resolve(using) : BowSupport.resolve(using);
            if (weapon != null) {
                return weapon;
            }
        }
        if (arrow instanceof ThrownTrident) {
            return WeaponResolver.resolve(player.getMainHandItem());
        }
        // 弩不走拉弓：射击发生在右键那一下，那一刻弩还是上好弦的状态
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                return BowSupport.resolve(stack);
            }
        }
        return heldBow(player);
    }

    /**
     * 手上的弓弩，主手优先。认不出正在用的是哪件时的兜底。
     *
     * @param player 玩家
     * @return 弓弩；两只手都没有返回 null
     */
    private static ResolvedWeapon heldBow(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            ResolvedWeapon weapon = BowSupport.resolve(stack);
            if (weapon != null) {
                return weapon;
            }
        }
        return null;
    }
}
