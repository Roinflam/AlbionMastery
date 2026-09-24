package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.client.AnimationSpeeds;
import com.flameforge.albionmastery.client.IAnimationSpeed;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让 TACZ（以及复用同一套动画系统的近战附属）的动画按特化倍速播放。
 *
 * <p>补的是什么：{@code ObjectAnimationRunner} 推进动画进度靠的是
 * {@code System.nanoTime()} 前后两次的差值，{@code run()}（开始播）、{@code update()}（每帧推进）、
 * {@code updateSoundOnly()}（只推音轨）三个方法各取一次时间，共用同一个 {@code lastUpdateNs}。
 * 这里把这三处取到的时间统一换成 {@link AnimationSpeeds.VirtualClock} 给出的虚拟时间：
 * 这段动画该放快，虚拟时间就走得快。三处都要换——只换 {@code update} 不换 {@code run}，
 * 动画一开始播基准时间就是错的，会瞬间跳过一段。
 *
 * <p>倍速是<b>每段动画各自一份</b>，在 Runner 刚被造出来时定一次（{@code animation} 字段是 final，
 * 一个 Runner 终生只对应一段动画，换动画一定是新的 Runner）。这样换弹加速的时候，
 * 同时在跑的待机、行走、瞄准动画不会跟着一起抖。
 *
 * <p>用 {@code @ModifyExpressionValue}：和别的模组（比如同样改动画速度的 KuvaLich）
 * 挂在同一处时会串成一条链各乘各的，不会互相覆盖。
 *
 * <p>目标是 TACZ 的类，不参与 SRG 映射，{@code remap = false}；注入不上就是动画不变速，
 * 逻辑本身照样是快的，用 {@code require = 0} 不影响启动。
 */
@Mixin(value = ObjectAnimationRunner.class, remap = false)
public abstract class ObjectAnimationRunnerMixin implements IAnimationSpeed {

    @Shadow
    public abstract boolean isTransitioning();

    @Shadow
    public abstract ObjectAnimationRunner getTransitionTo();

    /** 这段动画自己的虚拟时钟。 */
    @Unique
    private final AnimationSpeeds.VirtualClock albionmastery$clock = new AnimationSpeeds.VirtualClock();

    /** 这段动画的倍速，开始播的那一刻定死。 */
    @Unique
    private double albionmastery$speed = 1.0D;

    /**
     * 动画一被造出来就定好它是几倍速。
     *
     * @param animation 这段动画
     * @param ci        回调
     */
    @Inject(method = "<init>(Lcom/tacz/guns/api/client/animation/ObjectAnimation;)V",
            at = @At("TAIL"), require = 0)
    private void albionmastery$classify(ObjectAnimation animation, CallbackInfo ci) {
        this.albionmastery$speed = AnimationSpeeds.speedForNewClip(animation == null ? null : animation.name);
    }

    @Override
    public double albionmastery$speed() {
        return this.albionmastery$speed;
    }

    /**
     * 把真实时间换成虚拟时间。
     *
     * @param original 真实的 {@code System.nanoTime()}
     * @return 虚拟时间戳
     */
    @ModifyExpressionValue(
            method = {"run()V", "update(Z)V", "updateSoundOnly()V"},
            at = @At(value = "INVOKE", target = "Ljava/lang/System;nanoTime()J"),
            require = 0
    )
    private long albionmastery$scaleAnimationTime(long original) {
        return this.albionmastery$clock.advance(original, this.albionmastery$effectiveSpeed());
    }

    /**
     * 这一帧该用几倍速。
     *
     * <p>正在过渡时用的是「要转过去的那段动画」的倍速：这段的进度已经冻住了，时钟只推混合进度，
     * 而玩家看到的是新动作的起手——快动作的起手就该快。
     *
     * @return 倍速
     */
    @Unique
    private double albionmastery$effectiveSpeed() {
        if (this.isTransitioning()) {
            ObjectAnimationRunner to = this.getTransitionTo();
            if (to instanceof IAnimationSpeed next) {
                return next.albionmastery$speed();
            }
        }
        return this.albionmastery$speed;
    }
}
