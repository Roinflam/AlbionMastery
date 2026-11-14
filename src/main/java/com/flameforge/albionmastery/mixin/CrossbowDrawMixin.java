package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.event.BowCombatHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.item.CrossbowItem;

/**
 * 上弦前后各记一笔弹药数，给省箭特化核对「这一发真的扣了箭」。
 *
 * <p>上弦速度不在这里改：拉弦的计时本身已经按特化的倍速走了（见 {@code UseItemSpeedMixin}），
 * {@code releaseUsing} 里算出来的「已经拉了多少 tick」就是缩放过的，够不够上弦、什么时候能松手，
 * 和玩家看到的拉弦画面是同一套时间。这里再乘一次就是双份。
 *
 * <p>{@code releaseUsing} 只有一个 {@code return}，开头、结尾各命中一次。没写上限：多打上只是重复记、重复核对，
 * 结尾第二次进来时记录已经清掉了，不会多退箭。
 */
@Mixin(CrossbowItem.class)
public abstract class CrossbowDrawMixin {

    /**
     * 上弦之前记下背包里的弹药数（省箭特化用，只在服务端）。
     *
     * @param stack    弩
     * @param level    世界
     * @param entity   使用者
     * @param timeLeft 剩余使用时长
     * @param ci       回调
     */
    @Inject(method = "releaseUsing(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)V",
            at = @At("HEAD"), expect = 1)
    private void albionmastery$beforeLoad(ItemStack stack, Level level, LivingEntity entity, int timeLeft,
                                          CallbackInfo ci) {
        if (!level.isClientSide() && entity instanceof ServerPlayer player) {
            BowCombatHandler.beforeCrossbowLoad(player, stack);
        }
    }

    /**
     * 上弦之后核对弹药确实扣了，再决定退不退（省箭特化用，只在服务端）。
     *
     * @param stack    弩
     * @param level    世界
     * @param entity   使用者
     * @param timeLeft 剩余使用时长
     * @param ci       回调
     */
    @Inject(method = "releaseUsing(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)V",
            at = @At("RETURN"), expect = 1)
    private void albionmastery$afterLoad(ItemStack stack, Level level, LivingEntity entity, int timeLeft,
                                         CallbackInfo ci) {
        if (!level.isClientSide() && entity instanceof ServerPlayer player) {
            BowCombatHandler.afterCrossbowLoad(player, stack);
        }
    }
}
