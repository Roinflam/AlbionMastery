package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端换弹动画的播放速度。
 *
 * <p>TACZ 的动画 {@code ObjectAnimationRunner} 是拿 {@code System.nanoTime()} 的差值推进进度的，
 * 没有任何速度系数，所以逻辑上换弹再快，动画也还是原长度播完。
 * 这里做两件事：
 * <ol>
 *     <li>{@link #currentScale()} 回答「现在动画该以几倍速播」：本地玩家手持枪械、
 *         并且服务端同步过来的换弹状态是「正在换弹」时，返回 1 / 换弹耗时倍率
 *         （倍率 0.8 就是 1.25 倍速），其余时候 1.0；</li>
 *     <li>{@link VirtualClock} 给每个动画 Runner 一个「虚拟时钟」：真实时间每过 1 纳秒，
 *         虚拟时间过 {@code scale} 纳秒。Runner 用虚拟时钟算进度，动画就跟着变速了。</li>
 * </ol>
 *
 * <p>为什么要一个「时钟」而不是直接把差值乘上倍率：Runner 里记的 {@code lastUpdateNs}
 * 是上一次拿到的时间戳，如果只在 {@code update} 里乘差值、{@code run} 里不乘，
 * 两处的时间基准就对不上，动画会在开始的一瞬间跳一下。三处都换成同一个虚拟时钟才是自洽的。
 *
 * <p>倍速只在「正在换弹」期间生效，换弹一结束就恢复 1.0——这一点靠的是服务端同步的换弹状态，
 * 和服务端逻辑（{@code GunAmmoAndReloadMixin}）用的是同一份特化数值，两边天然对齐。
 */
public final class ReloadAnimationSpeed {

    /** 上一次算倍速时的客户端 tick，用来做每 tick 只算一次的缓存。 */
    private static int cachedTick = -1;

    /** 缓存的倍速。 */
    private static double cachedScale = 1.0D;

    private ReloadAnimationSpeed() {
    }

    /**
     * 当前动画该以几倍速播放。
     *
     * <p>每帧会被每一个动画 Runner 各叫一次，所以按客户端 tick 缓存：
     * 一 tick 内不管叫多少次都只真正算一遍。
     *
     * @return 倍速；不需要变速时是 1.0
     */
    public static double currentScale() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 1.0D;
        }
        if (player.tickCount == cachedTick) {
            return cachedScale;
        }
        cachedTick = player.tickCount;
        cachedScale = compute(player);
        return cachedScale;
    }

    /**
     * 真正算一次倍速。
     *
     * @param player 本地玩家
     * @return 倍速
     */
    private static double compute(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.isEmpty() || !(main.getItem() instanceof IGun)) {
            return 1.0D;
        }
        ReloadState state = IGunOperator.fromLivingEntity(player).getSynReloadState();
        if (state == null || state.getStateType() == ReloadState.StateType.NOT_RELOADING) {
            return 1.0D;
        }
        SpecializationValues values = SpecializationEffects.forStack(player, main);
        double factor = values.reloadTime();
        if (factor == 1.0D || factor <= 0.0D) {
            return 1.0D;
        }
        return 1.0D / factor;
    }

    /**
     * 一个可变速的虚拟时钟。
     *
     * <p>每次 {@link #advance} 传入真实的纳秒时间戳和当前倍速，返回虚拟时间戳：
     * 真实时间前进了 d，虚拟时间就前进 d × 倍速。倍速为 1 时和真实时间完全一致。
     *
     * <p>不是线程安全的，也不需要：动画只在渲染线程上推进。
     */
    public static final class VirtualClock {

        /** 上一次收到的真实时间戳。 */
        private long lastReal;

        /** 上一次返回的虚拟时间戳。 */
        private long lastVirtual;

        /** 还没收到过第一个时间戳。 */
        private boolean fresh = true;

        /**
         * 推进时钟并取虚拟时间。
         *
         * @param realNanos 真实的 {@code System.nanoTime()}
         * @param scale     当前倍速
         * @return 虚拟时间戳（纳秒）
         */
        public long advance(long realNanos, double scale) {
            if (this.fresh) {
                // 第一次直接对齐真实时间，让还没开始播的动画和别的 Runner 站在同一条起跑线上
                this.fresh = false;
                this.lastReal = realNanos;
                this.lastVirtual = realNanos;
                return realNanos;
            }
            long delta = realNanos - this.lastReal;
            this.lastReal = realNanos;
            if (delta > 0L) {
                this.lastVirtual += scale == 1.0D ? delta : (long) (delta * scale);
            }
            return this.lastVirtual;
        }
    }
}
