package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.mastery.spec.SpecVisualAttributes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 施法动作倍速的「晚到补挂」。
 *
 * <p>补的是什么：施法倍速是一个会同步的玩家属性（{@link SpecVisualAttributes#CAST_SPEED}），
 * 而属性更新包是实体追踪器每 tick 批量发的，「开始吟唱」那条包却是施法当场同步发的——
 * 两条包的先后顺序是固定的：吟唱包先到，属性包后到。于是收到吟唱包的那一帧去读属性，
 * 读到的永远是上一次施法的倍速，看别人施法时倍速总慢一拍。
 *
 * <p>做法：开始吟唱时照常先挂一次（自己施法时本机就能算准），再记一笔，
 * 接下来几个客户端 tick 按属性重挂。多播一两 tick 原速看不出来，倍速却能跟上。
 *
 * <p>双重施法的补放同理：那一次的引导时间额外乘了配置里的系数，只有服务端知道，
 * 客户端只能等属性到。
 */
public final class CastSpeedSync {

    /** 还要补挂几 tick。属性包最迟下一 tick 就到，留三 tick 是给服务端卡顿留的余量。 */
    private static final int RETRY_TICKS = 3;

    /** 施法者 UUID -> 待补挂的项。只在客户端线程碰。 */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    /**
     * 一笔待补挂。
     *
     * @param layerId   动画层
     * @param ticksLeft 还剩几 tick
     * @param baseline  记这一笔时属性上的值：属性还没变就说明新值还没到，别拿旧值去盖
     */
    private record Pending(ResourceLocation layerId, int ticksLeft, double baseline) {
    }

    private CastSpeedSync() {
    }

    /**
     * 记一笔：接下来几 tick 按属性重挂这个玩家的施法倍速。
     *
     * @param caster  施法者
     * @param layerId 动画层
     */
    public static void schedule(AbstractClientPlayer caster, ResourceLocation layerId) {
        if (caster != null && layerId != null) {
            PENDING.put(caster.getUUID(), new Pending(layerId, RETRY_TICKS,
                    SpecVisualAttributes.of(caster, SpecVisualAttributes.CAST_SPEED)));
        }
    }

    /**
     * 吟唱结束了，别再补挂。
     *
     * @param casterId 施法者
     */
    public static void cancel(UUID casterId) {
        if (casterId != null) {
            PENDING.remove(casterId);
        }
    }

    /**
     * 断开连接时清空，别攥着上一局的玩家。
     */
    public static void clear() {
        PENDING.clear();
    }

    /**
     * 每客户端 tick 走一遍待补挂的项。
     */
    public static void tick() {
        if (PENDING.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Iterator<Map.Entry<UUID, Pending>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Pending> entry = iterator.next();
            Player player = minecraft.level == null ? null : minecraft.level.getPlayerByUUID(entry.getKey());
            if (!(player instanceof AbstractClientPlayer client)) {
                // 人走了（出视野、下线），这笔就作废
                iterator.remove();
                continue;
            }
            Pending pending = entry.getValue();
            double current = SpecVisualAttributes.of(client, SpecVisualAttributes.CAST_SPEED);
            // 属性还是记这一笔时的那个值，说明新值还在路上：这时候挂上去只会把
            // 本机刚算准的倍速换成上一次施法留下的旧值，等下一 tick 再看
            if (Math.abs(current - pending.baseline()) > 1.0E-6D) {
                PlayerAnimSpeed.apply(client, pending.layerId(), current);
                iterator.remove();
                continue;
            }
            if (pending.ticksLeft() <= 1) {
                iterator.remove();
            } else {
                entry.setValue(new Pending(pending.layerId(), pending.ticksLeft() - 1, pending.baseline()));
            }
        }
    }
}
