package com.flameforge.albionmastery.client;

/**
 * 给 TACZ 的动画 Runner 额外挂上的一个问句：「你这段动画是几倍速？」
 *
 * <p>{@code ObjectAnimationRunnerMixin} 会让每个 Runner 实现这个接口。
 * 过渡（旧动画淡出、新动画淡入）期间，混合时长是由旧 Runner 的时钟推的，
 * 但玩家看到的其实是新动作的起手，所以那一段要问新 Runner 要倍速——
 * 手里只有一个 {@code ObjectAnimationRunner} 类型的引用，靠这个接口转过去拿。
 */
public interface IAnimationSpeed {

    /**
     * 这段动画的播放倍速。
     *
     * @return 倍速；不变速时是 1.0
     */
    double albionmastery$speed();
}
