package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.client.AnimationSpeeds;
import com.tacz.guns.api.client.animation.statemachine.AnimationStateMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 记下「这段动画是被哪个输入触发的」，好给它定速。
 *
 * <p>补的是什么：动画的分类首选按动画名认（{@code reload_tactical}、{@code bolt}……），
 * 可动画名是枪包作者定的，第三方枪包完全可以改成别的。而不管叫什么名字，
 * 动画都是被状态机的输入驱动的：换弹是 {@code "reload"}、拉栓是 {@code "blot"}、
 * 开火是 {@code "shoot"}，近战附属是 {@code "attack_left"} / {@code "attack_right"}。
 * 这里在输入的处理过程中把输入名记在栈上，处理过程中新起的动画就能问到是谁触发的。
 *
 * <p>为什么是一摞而不是一个：lua 脚本在处理一个输入时会再触发别的输入（比如开镜时打断检视）。
 * 为什么还要在 {@code update} 开头清一次：万一某次处理中途抛了异常，出栈就漏了，
 * 残留的输入名会把后面的动画都归错类；{@code update} 开头一定不在任何输入的处理过程中，
 * 在那里清空是安全的自愈点。
 *
 * <p>这个基类同时覆盖 TACZ 的枪和近战附属的刀（{@code LuaAnimationStateMachine} 没有覆写
 * {@code trigger}）。注不上只是动画不变速。
 */
@Mixin(AnimationStateMachine.class)
public abstract class AnimationTriggerMixin {

    /**
     * 进入一次输入处理。
     *
     * @param input 输入名
     * @param ci    回调
     */
    @Inject(method = "trigger(Ljava/lang/String;)V", at = @At("HEAD"), require = 0, expect = 1)
    private void albionmastery$pushInput(String input, CallbackInfo ci) {
        AnimationSpeeds.pushInput(input);
    }

    /**
     * 退出一次输入处理。
     *
     * @param input 输入名
     * @param ci    回调
     */
    @Inject(method = "trigger(Ljava/lang/String;)V", at = @At("RETURN"), require = 0, expect = 2)
    private void albionmastery$popInput(String input, CallbackInfo ci) {
        AnimationSpeeds.popInput();
    }

    /**
     * 每帧推进状态机之前清一次残留。
     *
     * @param ci 回调
     */
    @Inject(method = "update()V", at = @At("HEAD"), require = 0, expect = 1)
    private void albionmastery$clearInputs(CallbackInfo ci) {
        AnimationSpeeds.clearInputs();
    }
}
