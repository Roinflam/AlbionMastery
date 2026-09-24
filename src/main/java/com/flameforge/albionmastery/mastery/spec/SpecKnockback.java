package com.flameforge.albionmastery.mastery.spec;

import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 特化的击退倍率。
 *
 * <p>击退事件（{@code LivingKnockBackEvent}）只带被击退的实体和力度，不知道是谁、用什么打的。
 * 所以反过来做：各来源的战斗处理器在结算伤害时（{@code LivingHurtEvent}，它在原版的击退之前触发）
 * 把「这个目标这一下该吃几倍击退」登记在这里，击退事件来了再按目标取出来乘上。
 *
 * <p>原版一次近战会触发两次击退（受伤自带的 0.4 和武器 / 附魔的额外击退），两次都在同一 tick，
 * 所以登记的倍率在整个 tick 内有效，每个服务端 tick 结束统一清空。
 *
 * <p>只在服务端主线程读写；用弱引用表，漏清也不会攥住实体。
 */
public final class SpecKnockback {

    /** 本 tick 内被命中的目标 -&gt; 击退倍率。 */
    private static final Map<LivingEntity, Mark> PENDING = new WeakHashMap<>();

    /** 一次登记：倍率和登记它的攻击者。 */
    private record Mark(double factor, java.util.UUID attacker) {
    }

    private SpecKnockback() {
    }

    /**
     * 登记这一 tick 里某个攻击者打这个目标时的击退倍率。倍率是 1 时顺手把旧的登记摘掉：这一下不该被上一下的倍率带偏。
     *
     * <p>攻击者得由调用方给：登记发生在受伤事件里，那时原版还没把「最后一个打它的」更新成这个攻击者。
     *
     * @param target   目标
     * @param attacker 攻击者，null 表示不认人
     * @param factor   倍率
     */
    public static void mark(LivingEntity target, @javax.annotation.Nullable java.util.UUID attacker, double factor) {
        if (factor == 1.0D) {
            PENDING.remove(target);
        } else {
            PENDING.put(target, new Mark(factor, attacker));
        }
    }

    /**
     * @param target 被击退的实体
     * @return 本 tick 登记的击退倍率；没登记时是 1
     */
    public static double factorFor(LivingEntity target) {
        // 整个 tick 有效：原版近战一下会有两段击退（受伤那 0.4，再加疾跑、击退附魔、武器击退属性那一段），
        // 刀包也是先受伤再按武器数据击退，两段都要缩放。但只认登记它的那个攻击者——
        // 同一 tick 里别人（包括怪）打这个目标时，最后打它的就不是他了，不缩放
        Mark mark = PENDING.get(target);
        if (mark == null) {
            return 1.0D;
        }
        LivingEntity last = target.getLastHurtByMob();
        if (mark.attacker() != null && (last == null || !mark.attacker().equals(last.getUUID()))) {
            return 1.0D;
        }
        return mark.factor();
    }

    /** 每个服务端 tick 结束时清空。 */
    public static void clear() {
        if (!PENDING.isEmpty()) {
            PENDING.clear();
        }
    }
}
