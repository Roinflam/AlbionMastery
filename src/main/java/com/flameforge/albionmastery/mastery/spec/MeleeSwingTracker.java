package com.flameforge.albionmastery.mastery.spec;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraftforge.entity.PartEntity;

/**
 * 记一下「这一 tick 玩家真正点的是谁」。
 *
 * <p>补的是什么：原版的横扫、以及不少模组的溅射 / 连锁伤害，打在旁边那些目标身上时，
 * 用的伤害源和玩家亲手打的那一下一模一样（{@code playerAttack}，直接来源就是玩家本人）。
 * 光看伤害源分不出来，于是每个被扫到的目标都会被当成一次完整挥砍：
 * 专精倍率再乘一遍、经验再发一份、吸血 / 点燃 / 连击各触发一次。
 * 原版横扫本来只有 1 点余伤，被放大之后比正面那一下还狠。
 *
 * <p>做法：{@code AttackEntityEvent} 在玩家点下去的那一刻触发，而且只对真正点中的那个目标触发。
 * 把「游戏时间 + 目标」记下来，后面同一 tick 里再来的 {@code playerAttack} 如果目标对不上，
 * 那就是余伤 / 溅射。
 *
 * <p>没有记录时一律放行：有些模组的武器压根不发 {@code AttackEntityEvent}，
 * 直接自己调 {@code hurt}。那种武器本来就在正常工作，不能因为这道守卫把它们全掐了。
 */
public final class MeleeSwingTracker {

    /**
     * 一次挥砍。
     *
     * @param gameTime 挥砍发生的游戏时间（tick）
     * @param targetId 被点中的那个实体
     * @param rootId   它的本体：多部件 Boss（末影龙、灾变的利维坦和下界合金巨兽）点中的是部件，
     *                 部件转手把伤害交给本体，伤害事件里出现的是本体，和部件 ID 对不上
     */
    private record Swing(long gameTime, int targetId, int rootId) {
    }

    /** 玩家 UUID -> 他最近那一次挥砍。只在服务端主线程碰。 */
    private static final Map<UUID, Swing> LAST = new HashMap<>();

    private MeleeSwingTracker() {
    }

    /**
     * 玩家点了某个实体。
     *
     * @param player 玩家
     * @param target 被点中的实体
     */
    public static void mark(ServerPlayer player, @Nullable Entity target) {
        if (target == null) {
            return;
        }
        // 部件和本体两个 ID 都记：一般的多部件 Boss 是本体吃伤害，
        // 但也有模组的部件自己吃，只记一个的话另一种就被当成溅射掐掉了
        int targetId = target.getId();
        int rootId = target instanceof PartEntity<?> part && part.getParent() != null
                ? part.getParent().getId() : targetId;
        LAST.put(player.getUUID(), new Swing(player.level().getGameTime(), targetId, rootId));
    }

    /**
     * 这次伤害的目标，是不是玩家这一 tick 亲手点的那个。
     *
     * @param player 玩家
     * @param target 这次伤害的目标
     * @return 是、或者这一 tick 压根没有挥砍记录时返回 true
     */
    public static boolean isDirectTarget(ServerPlayer player, Entity target) {
        Swing swing = LAST.get(player.getUUID());
        if (swing == null || swing.gameTime() != player.level().getGameTime()) {
            // 这一 tick 没有挥砍记录：要么是别的路子打出来的伤害，要么是不发事件的模组武器，放行
            return true;
        }
        int id = target.getId();
        return swing.targetId() == id || swing.rootId() == id;
    }

    /**
     * 玩家下线，忘掉他。
     *
     * @param player 玩家
     */
    public static void forget(ServerPlayer player) {
        LAST.remove(player.getUUID());
    }

    /**
     * 关服时清空，别让静态表攥着上一局的东西。
     */
    public static void clear() {
        LAST.clear();
    }
}
