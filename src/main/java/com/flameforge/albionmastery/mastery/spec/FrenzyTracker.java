package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.config.SpecializationValues;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 嗜战：每击杀一个目标叠一层，每层加一截伤害；一段时间不再击杀，层数整个清零。
 *
 * <p>和 {@link ComboTracker} 一样只在服务端、只在内存里记。层数记在玩家身上而不是武器上：
 * 换了一把没有嗜战的武器，层数还在，但这把武器吃不到加成（倍率按当前武器的特化算）。
 *
 * <p>不是线程安全的，也不需要：击杀和伤害事件都在服务端主线程上触发。
 */
public final class FrenzyTracker {

    /** 最后一次击杀之后，层数能维持多少 tick。 */
    public static final int DURATION_TICKS = 160;

    /** 每隔多少 tick 清理一次过期记录。 */
    private static final int SWEEP_INTERVAL_TICKS = 200;

    private static final Map<UUID, State> STATES = new HashMap<>();

    private static int sinceLastSweep;

    private FrenzyTracker() {
    }

    /**
     * 记一次击杀：特化有嗜战就叠一层（到上限为止），并把持续时间刷新。
     *
     * @param player 击杀者
     * @param values 击杀用的武器当前生效的特化数值
     */
    public static void onKill(ServerPlayer player, SpecializationValues values) {
        if (!values.hasFrenzy()) {
            return;
        }
        long now = player.level().getGameTime();
        State state = STATES.computeIfAbsent(player.getUUID(), uuid -> new State());
        if (now > state.expireTick) {
            state.stacks = 0;
        }
        state.stacks = Math.min((int) Math.round(values.frenzyMax()), state.stacks + 1);
        state.expireTick = now + DURATION_TICKS;
    }

    /**
     * 这一下该吃的嗜战倍率。
     *
     * @param player 攻击者
     * @param values 当前武器的特化数值
     * @return 倍率，没有嗜战或层数已过期时是 1
     */
    public static double multiplier(ServerPlayer player, SpecializationValues values) {
        if (!values.hasFrenzy()) {
            return 1.0D;
        }
        State state = STATES.get(player.getUUID());
        if (state == null || player.level().getGameTime() > state.expireTick) {
            return 1.0D;
        }
        int stacks = Math.min(state.stacks, (int) Math.round(values.frenzyMax()));
        return 1.0D + stacks * values.frenzyStep();
    }

    /**
     * 每个服务端 tick 调一次，定期扔掉过期的记录。
     *
     * @param gameTime 当前世界 tick
     */
    public static void tick(long gameTime) {
        if (++sinceLastSweep < SWEEP_INTERVAL_TICKS || STATES.isEmpty()) {
            return;
        }
        sinceLastSweep = 0;
        Iterator<State> iterator = STATES.values().iterator();
        while (iterator.hasNext()) {
            if (gameTime > iterator.next().expireTick) {
                iterator.remove();
            }
        }
    }

    /**
     * @param playerId 下线玩家
     */
    public static void forget(UUID playerId) {
        STATES.remove(playerId);
    }

    /** 服务端停止时整体清空。 */
    public static void clear() {
        STATES.clear();
    }

    /**
     * 一个玩家的嗜战状态。
     */
    private static final class State {

        private int stacks;

        private long expireTick = Long.MIN_VALUE / 2;
    }
}
