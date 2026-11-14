package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.client.AnimationSpeeds;
import com.flameforge.albionmastery.client.IAnimationSpeed;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
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
 * <p>补的是什么：{@code ObjectAnimationRunner} 每次推进动画（{@code update(boolean)}）
 * 和只推音轨（{@code updateSoundOnly()}）都先调私有的 {@code elapsedSinceLastUpdate()}，
 * 取「距上次推进过了多少纳秒」，第一次调用返回 0。{@code update} 用同一个差值推进主进度和过渡进度。
 * 这里直接按倍速缩放这个差值：这段动画该放快，每帧推进的时间就多。
 * 第一次调用返回 0，乘多少倍都是 0，开始播的那一刻不会跳帧。
 *
 * <p>倍速是<b>每段动画各自一份</b>，在 Runner 刚被造出来时定一次（{@code animation} 字段是 final，
 * 一个 Runner 终生只对应一段动画，换动画一定是新的 Runner）。这样换弹加速的时候，
 * 同时在跑的待机、行走、瞄准动画不会跟着一起抖。
 *
 * <p>用 {@code @ModifyReturnValue}：和别的模组挂在同一个返回值上时会串成一条链各乘各的，
 * 不会互相覆盖。注入不上就是动画不变速，逻辑本身照样是快的，用 {@code require = 0} 不影响启动。
 */
@Mixin(ObjectAnimationRunner.class)
public abstract class ObjectAnimationRunnerMixin implements IAnimationSpeed {

    @Shadow
    public abstract boolean isTransitioning();

    @Shadow
    public abstract ObjectAnimationRunner getTransitionTo();

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
            at = @At("TAIL"), require = 0, expect = 1)
    private void albionmastery$classify(ObjectAnimation animation, CallbackInfo ci) {
        this.albionmastery$speed = AnimationSpeeds.speedForNewClip(animation == null ? null : animation.name);
    }

    @Override
    public double albionmastery$speed() {
        return this.albionmastery$speed;
    }

    /**
     * 按倍速缩放这一帧推进的时间。
     *
     * @param elapsed 距上次推进的真实纳秒数
     * @return 这一帧动画实际推进的纳秒数
     */
    @ModifyReturnValue(method = "elapsedSinceLastUpdate()J", at = @At("RETURN"), require = 0, expect = 2)
    private long albionmastery$scaleElapsed(long elapsed) {
        double speed = this.albionmastery$effectiveSpeed();
        return speed == 1.0D ? elapsed : Math.round(elapsed * speed);
    }

    /**
     * 这一帧该用几倍速。
     *
     * <p>正在过渡时用的是「要转过去的那段动画」的倍速：这段的进度已经冻住了，时钟只推混合进度，
     * 而玩家看到的是新动作的起手，快动作的起手就该快。
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
