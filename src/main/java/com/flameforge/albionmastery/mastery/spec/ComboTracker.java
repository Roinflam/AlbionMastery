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
 * 服务端重启清零也没人会在意。
 *
 * <p>每个玩家一条记录：上一次打的是谁、叠了几层、最后一次命中是哪个 tick。
 * 命中时先看目标和时间窗，再决定是接着叠还是从头来。返回的倍率是<b>本次命中之前</b>
 * 已经叠起来的层数算出来的——第一下永远是 1.0，从第二下开始才吃加成，
 * 否则「连击」就成了「无条件加成」。
 *
 * <p>一个 tick 最多叠一层：同一 tick 里对同一目标的多次命中（霰弹弹丸、穿透、多段法术）
 * 共用第一次命中的倍率。TACZ 全自动一 tick 最多一发，这条限制对正常连射没有影响。
 *
 * <p>不是线程安全的，也不需要：所有战斗事件都在服务端主线程上触发。
 */
public final class ComboTracker {

    /** 玩家 -&gt; 连击状态。 */
    private static final Map<UUID, State> STATES = new HashMap<>();

    /** 一个玩家同时记多少个目标的连击，满了先把过期的扔掉。 */
    private static final int MAX_TARGETS = 16;

    /** 每隔多少 tick 清理一次早已过期的记录，避免下线玩家的状态一直留着。 */
    private static final int SWEEP_INTERVAL_TICKS = 200;

    /** 距离上次清理过去了多少 tick。 */
    private static int sinceLastSweep = 0;

    private ComboTracker() {
    }

    /**
     * 记一次命中，并返回这次命中该吃的连击倍率。
     *
     * <p>特化没有连击（{@link SpecializationValues#hasCombo()} 为 false）时不记录也不加成，
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

        State state = STATES.computeIfAbsent(player.getUUID(), uuid -> new State());
        // 每个目标各叠各的：原版剑的横扫是先打主目标再打旁边的，霰弹、穿透、范围斩一下也会打到好几个，
        // 以前「换了目标就清零」，一次打中两个以上目标时主目标每一刀都被清掉，连击永远叠不起来
        Stack stack = state.targets.get(target.getId());
        if (stack != null && now == stack.lastHitTick) {
            // 同一 tick 里对同一目标的多次命中（霰弹的每颗弹丸、穿透、多段法术）算作「一下」：
            // 吃同一个倍率、不额外叠层。否则一发 12 颗弹丸的霰弹，第一枪就把 10 层连击叠满了
            return stack.tickMultiplier;
        }
        if (stack == null || now - stack.lastHitTick > window) {
            // 这个目标没打过，或者上一下已经是很久之前：从零开始
            if (state.targets.size() >= MAX_TARGETS) {
                state.targets.values().removeIf(old -> now - old.lastHitTick > window);
            }
            stack = new Stack();
            state.targets.put(target.getId(), stack);
        }
        double multiplier = 1.0D + stack.stacks * values.comboStep();
        stack.lastHitTick = now;
        stack.tickMultiplier = multiplier;
        stack.stacks = Math.min(max, stack.stacks + 1);
        state.lastHitTick = now;
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

    /** 服务端停止时整体清空。 */
    public static void clear() {
        STATES.clear();
    }

    /**
     * 一个玩家的连击状态。
     */
    private static final class State {

        /** 目标实体 ID -&gt; 对它叠起来的连击。 */
        private final Map<Integer, Stack> targets = new HashMap<>();

        /** 对任意目标最后一次命中的世界 tick，定期清理时看它。 */
        private long lastHitTick = Long.MIN_VALUE / 2;
    }

    /**
     * 对一个目标的连击。
     */
    private static final class Stack {

        /** 已经叠起来的层数。 */
        private int stacks = 0;

        /** 最后一次命中的世界 tick。 */
        private long lastHitTick = Long.MIN_VALUE / 2;

        /** 最后那个 tick 用的倍率，同一 tick 再打中同一目标时原样复用。 */
        private double tickMultiplier = 1.0D;
    }
}
