package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 连击计数：连续命中同一个目标，每命中一次伤害多一层，换目标或者隔太久就清零。
 *
 * <p><b>只在服务端记，只记在内存里</b>。连击是几秒钟的事，存进 Capability 毫无意义；
 * 服务器重启清零也没人会在意。
 *
 * <p>每个玩家一条记录：上一次打的是谁、叠了几层、最后一次命中是哪个 tick。
 * 命中时先看目标和时间窗，再决定是接着叠还是从头来。返回的倍率是<b>本次命中之前</b>
 * 已经叠起来的层数算出来的——第一下永远是 1.0，从第二下开始才吃加成，
 * 否则「连击」就成了「无条件加成」。
 *
 * <p>不是线程安全的，也不需要：所有战斗事件都在服务端主线程上触发。
 */
public final class ComboTracker {

    /** 玩家 -&gt; 连击状态。 */
    private static final Map<UUID, State> STATES = new HashMap<>();

    /** 每隔多少 tick 清理一次早已过期的记录，避免下线玩家的状态一直留着。 */
    private static final int SWEEP_INTERVAL_TICKS = 200;

    /** 距离上次清理过去了多少 tick。 */
    private static int sinceLastSweep = 0;

    private ComboTracker() {
    }

    /**
     * 记一次命中，并返回这次命中该吃的连击倍率。
     *
     * <p>流派没有连击（{@link SpecializationValues#hasCombo()} 为 false）时不记录也不加成，
     * 直接返回 1.0，避免给每个玩家都白白挂一条状态。
     *
     * @param player 攻击者
     * @param target 被打的目标
     * @param values 当前生效的特化数值
     * @return 本次命中的伤害倍率，永远 &gt;= 1.0
     */
    public static double hit(ServerPlayer player, LivingEntity target, SpecializationValues values) {
        if (!values.hasCombo()) {
            return 1.0D;
        }
        long now = player.level().getGameTime();
        int window = MasteryConfig.COMMON.comboWindowTicks.get();
        int max = (int) Math.round(values.comboMax());

        State state = STATES.get(player.getUUID());
        if (state == null) {
            state = new State();
            STATES.put(player.getUUID(), state);
        }

        boolean sameTarget = target.getId() == state.targetId;
        boolean inWindow = now - state.lastHitTick <= window;
        if (!sameTarget || !inWindow) {
            // 换了目标，或者上一下已经是很久之前：从零开始
            state.stacks = 0;
        }
        double multiplier = 1.0D + state.stacks * values.comboStep();

        state.targetId = target.getId();
        state.lastHitTick = now;
        state.stacks = Math.min(max, state.stacks + 1);
        return multiplier;
    }

    /**
     * 每个服务端 tick 调一次，定期把过期的记录扔掉。
     *
     * <p>不是每 tick 都遍历：记录本来就少，200 tick 扫一次足够，热点路径上省一点是一点。
     *
     * @param gameTime 当前的世界 tick
     */
    public static void tick(long gameTime) {
        if (++sinceLastSweep < SWEEP_INTERVAL_TICKS) {
            return;
        }
        sinceLastSweep = 0;
        if (STATES.isEmpty()) {
            return;
        }
        long window = MasteryConfig.COMMON.comboWindowTicks.get();
        Iterator<State> iterator = STATES.values().iterator();
        while (iterator.hasNext()) {
            if (gameTime - iterator.next().lastHitTick > window) {
                iterator.remove();
            }
        }
    }

    /**
     * 玩家下线时把他的记录清掉。
     *
     * @param playerId 玩家 UUID
     */
    public static void forget(UUID playerId) {
        STATES.remove(playerId);
    }

    /** 服务器停止时整体清空。 */
    public static void clear() {
        STATES.clear();
    }

    /**
     * 一个玩家的连击状态。
     */
    private static final class State {

        /** 上一次命中的目标的实体 ID；-1 表示还没打过人。 */
        private int targetId = -1;

        /** 已经叠起来的层数。 */
        private int stacks = 0;

        /** 最后一次命中的世界 tick。 */
        private long lastHitTick = Long.MIN_VALUE / 2;
    }
}
