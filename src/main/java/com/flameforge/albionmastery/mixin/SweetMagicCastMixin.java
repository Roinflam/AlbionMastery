package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.weapon.compat.SweetMagicSupport;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sweetmagic.init.item.magic.SMWand;

/**
 * 甜蜜魔法施法的那一下：开一个「谁在放哪张卷轴」的窗口。
 *
 * <p>窗口有两个用处：施法期间生成的弹体和召唤物会被记上是哪张卷轴放的（命中时才知道算谁的账），
 * 以及这期间读到的法力消耗、冷却按这张卷轴的特化缩放（见 {@code SweetMagicCostMixin}）。
 *
 * <p>为什么挂在法杖类上而不是 {@code IWand} 接口：施法逻辑确实全写在接口的默认方法里，
 * 但 Mixin 不支持往接口里注入（编译期就会报 {@code Injector in interface is unsupported}）。
 * 好在两个入口——右键直接放、蓄力松手放——都是法杖类自己覆写的原版方法，从这里包住整段施法。
 *
 * <p>这两个方法覆写的是原版物品的方法，开发环境叫 {@code use} / {@code releaseUsing}，
 * 正式环境是混淆名，两个都写上。目标是甜蜜魔法的类，{@code remap = false}。
 */
@Mixin(value = SMWand.class, remap = false)
public abstract class SweetMagicCastMixin {

    /**
     * 右键施法开始。
     *
     * @param level  世界
     * @param player 玩家
     * @param hand   手
     * @param cir    回调
     */
    @Inject(method = {"use", "m_7203_"}, at = @At("HEAD"), require = 0)
    private void albionmastery$beginUse(Level level, Player player, net.minecraft.world.InteractionHand hand,
                                        CallbackInfoReturnable<?> cir) {
        // 用这次右键实际用的那只手：副手持杖施法在原版里是很常见的玩法
        albionmastery$open(level, player, player.getItemInHand(hand));
    }

    /**
     * 右键施法结束。
     *
     * @param level  世界
     * @param player 玩家
     * @param hand   手
     * @param cir    回调
     */
    @Inject(method = {"use", "m_7203_"}, at = @At("RETURN"), require = 0)
    private void albionmastery$endUse(Level level, Player player, net.minecraft.world.InteractionHand hand,
                                      CallbackInfoReturnable<?> cir) {
        if (!level.isClientSide()) {
            SweetMagicSupport.finishCast();
        }
    }

    /**
     * 蓄力松手施法开始。
     *
     * @param stack    法杖
     * @param level    世界
     * @param entity   使用者
     * @param timeLeft 剩余使用时长
     * @param ci       回调
     */
    @Inject(method = {"releaseUsing", "m_5551_"}, at = @At("HEAD"), require = 0)
    private void albionmastery$beginRelease(ItemStack stack, Level level, LivingEntity entity, int timeLeft,
                                            CallbackInfo ci) {
        if (entity instanceof Player player) {
            albionmastery$open(level, player, stack);
        }
    }

    /**
     * 蓄力松手施法结束。
     *
     * @param stack    法杖
     * @param level    世界
     * @param entity   使用者
     * @param timeLeft 剩余使用时长
     * @param ci       回调
     */
    @Inject(method = {"releaseUsing", "m_5551_"}, at = @At("RETURN"), require = 0)
    private void albionmastery$endRelease(ItemStack stack, Level level, LivingEntity entity, int timeLeft,
                                          CallbackInfo ci) {
        if (!level.isClientSide()) {
            SweetMagicSupport.finishCast();
        }
    }

    /**
     * 开窗口：记下是谁、当前选中的是哪张卷轴。
     *
     * @param level  世界
     * @param player 玩家
     * @param wand   这次右键用的法杖
     */
    private static void albionmastery$open(Level level, Player player, ItemStack wand) {
        if (level.isClientSide() || player == null || wand == null) {
            return;
        }
        SweetMagicSupport.beginCast(player, wand);
    }
}
