package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.client.PlayerAnimSpeed;
import com.flameforge.albionmastery.client.ReloadAnimSync;
import com.flameforge.albionmastery.mastery.spec.SpecVisualAttributes;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import com.tacz.guns.compat.playeranimator.animation.AnimationManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;
import org.spongepowered.asm.mixin.Unique;

/**
 * 别人看到的换弹动作也按换弹特化的倍速播。
 *
 * <p>补的是什么：第三人称的上半身动作是 Player Animator 的关键帧动画，长度写死在动画文件里。
 * 换弹特化把换弹压到六成时间，别人看到的还是原速——他早就能开枪了，动作还在装弹夹。
 * 自己切第三人称看自己同样如此（这套动作对本人和旁人是同一份）。
 *
 * <p>开火动作不动：射速本来就可能比开火动作短，原版 TACZ 装了射速配件也是这个样子，
 * 强行压缩反而不像。掏枪、拉栓在第三人称没有单独的动作。
 *
 * <p>倍率取自会同步的属性，所以看谁就是谁的倍率，不会拿自己的特化去套别人。
 * TACZ 或 Player Animator 没装时这个 Mixin 整个不应用。
 */
@Mixin(AnimationManager.class)
public abstract class GunThirdPersonAnimMixin {

    /** 注入目标：播一段上半身一次性动作。 */
    private static final String ALBIONMASTERY$PLAY_ONCE = "playOnceAnimation(Lnet/minecraft/client/player/AbstractClientPlayer;"
            + "Lcom/tacz/guns/client/resource/GunDisplayInstance;Lnet/minecraft/resources/ResourceLocation;"
            + "Ljava/lang/String;)V";

    /**
     * 这一层现在空着吗——也就是 TACZ 待会儿到底会不会真的把动作换掉。
     *
     * <p>只在渲染线程上用，不需要线程安全。
     */
    @Unique
    private static boolean albionmastery$layerWasIdle;

    /**
     * 动作开始之前先看一眼这一层空不空。
     *
     * <p>TACZ 自己的条件就是「这一层没有动作在播」，不满足它会放弃换动作。
     * 不看这一眼的话，它没换我们却照样改速度，正在播的另一段动作会被我们改快改慢。
     *
     * @param player      玩家
     * @param display     枪的显示数据
     * @param animationId 动画资源
     * @param name        动画名
     * @param ci          回调
     */
    @Inject(method = ALBIONMASTERY$PLAY_ONCE, at = @At("HEAD"), require = 0, expect = 1)
    private static void albionmastery$checkLayer(AbstractClientPlayer player, GunDisplayInstance display,
                                                 ResourceLocation animationId, String name, CallbackInfo ci) {
        albionmastery$layerWasIdle = PlayerAnimSpeed.isIdle(player, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION);
    }

    /**
     * 一段一次性上半身动作开始播。
     *
     * @param player      玩家
     * @param display     枪的显示数据
     * @param animationId 动画资源
     * @param name        动画名
     * @param ci          回调
     */
    @Inject(method = ALBIONMASTERY$PLAY_ONCE, at = @At("RETURN"), require = 0, expect = 3)
    private static void albionmastery$speed(AbstractClientPlayer player, GunDisplayInstance display,
                                            ResourceLocation animationId, String name, CallbackInfo ci) {
        if (!albionmastery$layerWasIdle) {
            return;
        }
        String clip = name == null ? "" : name.toLowerCase(Locale.ROOT);
        boolean reload = clip.contains("reload");
        // 先按我们自己这一份倍率估一个：别的模组也在改换弹速度时它不准，但总比按 1 倍强
        double speed = reload
                ? SpecVisualAttributes.of(player, SpecVisualAttributes.RELOAD_SPEED) : 1.0D;
        PlayerAnimSpeed.apply(player, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, speed);
        if (reload) {
            // 再去量实际的换弹进度，几 tick 后换成真实倍速。
            // 换弹快慢是所有模组相乘的结果，只按自己那一份播，别人看到的动作会和实际对不上
            ReloadAnimSync.schedule(player, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION);
        } else {
            ReloadAnimSync.cancel(player);
        }
    }
}
