package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.client.ReloadAnimationSpeed;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 让 TACZ 的动画在换弹期间按流派倍速播放。
 *
 * <p>补的是什么：{@code ObjectAnimationRunner} 推进动画进度靠的是
 * {@code System.nanoTime()} 前后两次的差值，{@code run()}（开始播）、{@code update()}（每帧推进）、
 * {@code updateSoundOnly()}（只推进音轨）三个方法各取一次时间。这里把这三处取到的时间
 * 统一换成 {@link ReloadAnimationSpeed.VirtualClock} 给出的虚拟时间：正在换弹时虚拟时间走得快，
 * 动画自然就快了；不换弹时虚拟时间和真实时间一样快。
 *
 * <p>为什么三处都要换：三处共用同一个 {@code lastUpdateNs} 字段。只换 {@code update}
 * 不换 {@code run}，动画一开始播时基准时间就是错的，会瞬间跳过一段。
 *
 * <p>每个 Runner 一个自己的时钟（实例字段），互不影响；Mixin 会把这个字段的初始化
 * 合并进目标类的构造器，和 KuvaLich 里同一套做法。
 *
 * <p>用 {@code @ModifyExpressionValue}：和别的模组（比如同样改动画速度的 KuvaLich）
 * 挂在同一处时会串成一条链各乘各的，不会互相覆盖。
 *
 * <p>目标是 TACZ 的类，不参与 SRG 映射，{@code remap = false}；注入不上就是动画不变速，
 * 换弹逻辑本身照样是快的，用 {@code require = 0} 不影响启动。
 */
@Mixin(value = ObjectAnimationRunner.class, remap = false)
public abstract class ObjectAnimationRunnerMixin {

    /** 这个 Runner 自己的虚拟时钟。 */
    @Unique
    private final ReloadAnimationSpeed.VirtualClock albionmastery$clock =
            new ReloadAnimationSpeed.VirtualClock();

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
        return this.albionmastery$clock.advance(original, ReloadAnimationSpeed.currentScale());
    }
}
