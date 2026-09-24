package com.flameforge.albionmastery.client;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * 第三人称换弹动作的倍速：不按谁的特化算，按<b>实际换弹到底走多快</b>反算。
 *
 * <p>补的是什么：换弹快慢不只有我们一家在改。TACZ 的换弹状态机看的是
 * {@code getReloadTime()}——「这次换弹已经过去了多少毫秒」，谁想让换弹变快，
 * 做的都是把这个数撑大。我们撑一次，别的模组（比如 KuvaLich 的装填模组）再撑一次，
 * 最终快多少是两边相乘的结果。第三人称动作要是只按我们自己那一份倍率播，
 * 别人看到的就是「他手上还在插弹匣，子弹早就打出去大半个弹匣了」。
 *
 * <p>做法：{@code ReloadState.getCountDown()} 是「这个阶段还剩多少毫秒」，
 * 而它是拿被撑大过的已过时间算出来的，所以它每个真实 tick 掉多少，
 * 就正好等于整条链子加起来的加速倍率。隔几 tick 采两次样，一除就得到倍速，
 * 谁改的都算在里面，我们不需要认识任何别的模组。
 *
 * <p>代价是开头那几 tick 先按我们自己那一份倍率播（总比按 1 倍强），
 * 采到样之后再换成真实倍速。一段两三秒的换弹动作，前 100 毫秒的误差看不出来。
 *
 * <p>这个类会碰 TACZ 的类，调用方必须先确认 TACZ 装了。
 */
public final class ReloadAnimSync {

    /**
     * 最多等这么多 tick。
     *
     * <p>要够长：动作是「开始换弹」那条包触发的，而换弹状态是另一条包、还要等服务端 tick 完才发，
     * 必然晚几 tick 才到。等不到就放弃，保持开头那个估计值。
     */
    private static final int MAX_TICKS = 20;

    /** 两次采样至少隔这么多 tick 再算，隔太近容易被网络抖动带偏。 */
    private static final int MIN_GAP = 2;

    /** 一个服务端 tick 是多少毫秒。 */
    private static final double MS_PER_TICK = 50.0D;

    /** 倍速的上下限，和 {@link PlayerAnimSpeed} 保持一致。 */
    private static final double MIN_SPEED = 0.25D;

    /** 倍速的上下限，和 {@link PlayerAnimSpeed} 保持一致。 */
    private static final double MAX_SPEED = 4.0D;

    /**
     * 一次待测量。
     *
     * @param layerId   动画层
     * @param stateType 基准那一刻的换弹阶段；为 null 表示还在等换弹状态同步过来，基准没建起来
     * @param countDown 基准那一刻这个阶段还剩多少毫秒
     * @param ticksLeft 还能等几 tick
     * @param waited    从建起基准算起已经过了几 tick
     */
    private record Sample(ResourceLocation layerId, @Nullable ReloadState.StateType stateType, long countDown,
                          int ticksLeft, int waited) {
    }

    /** 玩家 UUID -> 待测量。只在客户端线程碰。 */
    private static final Map<UUID, Sample> PENDING = new HashMap<>();

    private ReloadAnimSync() {
    }

    /**
     * 这个玩家开始播换弹动作了，接下来几 tick 量一下他的换弹到底走多快。
     *
     * @param player  玩家（可能是别人）
     * @param layerId 动画层
     */
    public static void schedule(AbstractClientPlayer player, ResourceLocation layerId) {
        if (player == null || layerId == null) {
            return;
        }
        // 这一刻几乎肯定还没在换弹：动作是「开始换弹」那条包当场触发的，
        // 而换弹状态要等服务端 tick 完才同步过来。所以先挂一个「基准还没建」的占位，
        // 等状态真的变成换弹中，再从那时候开始量
        PENDING.put(player.getUUID(), new Sample(layerId, null, 0L, MAX_TICKS, 0));
    }

    /**
     * 这个玩家的换弹结束了，别再量了。
     *
     * @param player 玩家
     */
    public static void cancel(AbstractClientPlayer player) {
        if (player != null) {
            PENDING.remove(player.getUUID());
        }
    }

    /**
     * 断开连接时清空。
     */
    public static void clear() {
        PENDING.clear();
    }

    /**
     * 每客户端 tick 走一遍在量的那几个人。
     */
    public static void tick() {
        if (PENDING.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Iterator<Map.Entry<UUID, Sample>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Sample> entry = iterator.next();
            Player player = minecraft.level == null ? null : minecraft.level.getPlayerByUUID(entry.getKey());
            if (!(player instanceof AbstractClientPlayer client)) {
                iterator.remove();
                continue;
            }
            Sample sample = entry.getValue();
            ReloadState state = reloadStateOf(client);
            ReloadState.StateType now = state == null ? null : state.getStateType();
            if (sample.stateType() == null) {
                // 还在等换弹状态同步过来
                if (now != null && now != ReloadState.StateType.NOT_RELOADING) {
                    entry.setValue(new Sample(sample.layerId(), now, state.getCountDown(),
                            sample.ticksLeft() - 1, 0));
                } else if (sample.ticksLeft() <= 1) {
                    iterator.remove();
                } else {
                    entry.setValue(new Sample(sample.layerId(), null, 0L, sample.ticksLeft() - 1, 0));
                }
                continue;
            }
            // 换弹结束了、或者换阶段了（装填 → 收尾，倒计时会跳一下），这次就不量了
            if (now == null || now != sample.stateType()) {
                iterator.remove();
                continue;
            }
            int waited = sample.waited() + 1;
            long dropped = sample.countDown() - state.getCountDown();
            if (waited >= MIN_GAP && dropped > 0L) {
                double speed = dropped / (waited * MS_PER_TICK);
                PlayerAnimSpeed.apply(client, sample.layerId(), Mth.clamp(speed, MIN_SPEED, MAX_SPEED));
                iterator.remove();
                continue;
            }
            if (sample.ticksLeft() <= 1) {
                // 一直没掉（同步包还没到、或者这把枪的这一段根本不走倒计时），保持开头那个估计值
                iterator.remove();
                continue;
            }
            entry.setValue(new Sample(sample.layerId(), sample.stateType(), sample.countDown(),
                    sample.ticksLeft() - 1, waited));
        }
    }

    /**
     * 取这名玩家同步过来的换弹状态。
     *
     * @param player 玩家
     * @return 状态；取不到返回 null
     */
    private static ReloadState reloadStateOf(AbstractClientPlayer player) {
        try {
            IGunOperator operator = IGunOperator.fromLivingEntity(player);
            return operator == null ? null : operator.getSynReloadState();
        } catch (Exception exception) {
            // 别人的枪械数据还没同步过来时可能取不到，量不到就算了，不能让渲染线程挂掉
            return null;
        }
    }
}
