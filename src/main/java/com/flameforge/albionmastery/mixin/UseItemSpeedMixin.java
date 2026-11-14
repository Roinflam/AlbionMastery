package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.SpecVisualAttributes;
import com.flameforge.albionmastery.weapon.compat.BowSupport;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import net.minecraft.world.InteractionHand;

/**
 * 拉弓 / 上弦的计时按特化走——改的是计时本身，不是某一处画面。
 *
 * <p>补的是什么：原版「已经拉了多久」只有一个来源，就是 {@code useItemRemaining} 这个每 tick 减一的计数器。
 * 弓弦拉开的模型、第一人称手臂、第三人称上弦动作、拉弓的视野收缩、弩的上弦音效、松手时算出来的蓄力，
 * 全部由它推出来。所以只要让这个计数器按特化的倍速走，画面、音效和结算就是同一套时间，天然对齐。
 *
 * <p>以前的做法是改 {@code getUseItemRemainingTicks()} 的返回值，那是错的：
 * NeoForge 版的 {@code updateUsingItem} 第一行就是
 * {@code useItemRemaining = EventHooks.onItemUseTick(this, stack, getUseItemRemainingTicks())}，
 * 缩放过的返回值会被原样写回字段，下一 tick 再缩放一次，逐 tick 复利——
 * 倍率 1.2 拉到第 10 tick 时「已拉」是 23 而不是 12，慢速特化则永远拉不满。
 *
 * <p>倍速分两路取：服务端和「自己看自己」时按正在用的那把弓当场算，最准也没有延迟；
 * 看别人时本机没有他的专精数据，读服务端同步过来的属性（{@link SpecVisualAttributes#DRAW_SPEED}）。
 * 于是别人拉弓多快，你看到的就是多快。
 *
 * <p>只对玩家、只对弓弩生效。两个注入器各命中一次；多打上会让倍速叠两遍，命中数上下限都卡死。
 */
@Mixin(LivingEntity.class)
public abstract class UseItemSpeedMixin {

    @Shadow
    protected int useItemRemaining;

    @Shadow
    public abstract boolean isUsingItem();

    /** 不足一整 tick 的零头，攒够一 tick 再动计数器。 */
    @Unique
    private double albionmastery$drawCarry;

    /**
     * 每 tick 按倍速多扣（或者回补）几个 tick。
     *
     * <p>挂在 HEAD：同一 tick 里紧接着就是 {@code stack.onUseTick(...)}，
     * 弩的上弦音效就是在那里按进度放的，先调整完计时，音效的节奏也就跟着对了。
     *
     * @param stack 正在使用的物品
     * @param ci    回调
     */
    @Inject(method = "updateUsingItem(Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"), expect = 1, allow = 1)
    private void albionmastery$scaleDraw(ItemStack stack, CallbackInfo ci) {
        if (!((Object) this instanceof Player player) || !BowSupport.isBowOrCrossbow(stack)) {
            return;
        }
        // 原版自己那一下 -- 已经把计时减到 0 了（弩拉满之后继续按着）：到此为止，不再插手
        if (this.useItemRemaining <= 0) {
            return;
        }
        double speed = albionmastery$drawSpeed(player, stack);
        if (speed == 1.0D) {
            return;
        }

        // 倍速 1.3 就是每 tick 多走 0.3 tick，攒够一整 tick 才扣；0.8 则是每 tick 回补 0.2
        this.albionmastery$drawCarry += speed - 1.0D;
        int extra = (int) Math.floor(this.albionmastery$drawCarry);
        if (extra == 0) {
            return;
        }
        // 下限留 1：归零之后原版就不再调 onUseTick 了，弩的上弦音效会哑掉；真正的归零交给原版那一下 --。
        // 零头按「实际扣掉的」还，被上下限夹掉的那部分留在零头里下次再扣，不然慢速特化第一次就白扣一 tick
        int target = Mth.clamp(this.useItemRemaining - extra, 1, stack.getUseDuration(player));
        this.albionmastery$drawCarry -= this.useItemRemaining - target;
        this.useItemRemaining = target;
    }

    /**
     * 开始使用一件新物品：上一次拉弦剩下的零头清掉。
     *
     * <p>要判一下「本来就在用着」：原版的这个方法在已经在用时会直接返回什么都不做，
     * 不判的话按着右键的过程中零头会被反复清零，慢速特化就永远补不回那一 tick。
     *
     * @param hand 用的哪只手
     * @param ci   回调
     */
    @Inject(method = "startUsingItem(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"), expect = 1, allow = 1)
    private void albionmastery$resetCarry(InteractionHand hand, CallbackInfo ci) {
        if (!this.isUsingItem()) {
            this.albionmastery$drawCarry = 0.0D;
        }
    }

    /**
     * 这把弓弩现在该以几倍速拉。
     *
     * @param player 使用者
     * @param stack  弓弩
     * @return 倍速
     */
    @Unique
    private static double albionmastery$drawSpeed(Player player, ItemStack stack) {
        if (SpecializationEffects.hasLocalData(player)) {
            // 服务端、以及客户端上的自己：按正在用的这把当场算，副手也对，还没有同步延迟
            double factor = SpecializationEffects.forWeapon(player, BowSupport.resolve(stack)).drawSpeed();
            return factor > 0.0D && Double.isFinite(factor) ? factor : 1.0D;
        }
        // 别人：本机没有他的专精数据，读服务端同步过来的倍速
        return SpecVisualAttributes.of(player, SpecVisualAttributes.DRAW_SPEED);
    }
}
