package com.flameforge.albionmastery.event.damage;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;

import java.util.Collections;
import java.util.Set;
import java.util.Stack;
import java.util.WeakHashMap;

/**
 * 在 1.20.1 Forge 发 {@code LivingHurtEvent} / {@code LivingDamageEvent} 的原位置，发本模组自己的两个同名事件
 * （{@link LivingHurtEvent}、{@link LivingDamageEvent}）。由 {@code LivingEntityDamageStagesMixin} 和
 * {@code PlayerDamageStagesMixin} 调用。
 *
 * <h3>为什么不用 NeoForge 自带的事件</h3>
 * <p>{@code LivingIncomingDamageEvent} 在盾牌、冰冻、无敌帧之前：无敌帧内第二下的差值、被无敌帧挡掉的那一下、
 * 盾全挡的数都和 1.20.1 不一样，会多发经验、多叠连击。{@code LivingDamageEvent.Pre} 在吸收之前且不能取消，
 * {@code .Post} 只读。所以用 Mixin 在原位置发，监听代码只换 import。
 *
 * <h3>数从哪来</h3>
 * <p>NeoForge 在 {@code hurt} 里给每一下压一个 {@link DamageContainer}，之后整条流程以栈顶容器的
 * {@code newDamage} 为准。两个阶段从栈顶读数、把监听器改过的数写回，原版紧接着读的就是它。
 *
 * <h3>取消、改到 0 时</h3>
 * <p>1.20.1 受伤事件被取消或数 ≤ 0 时 {@code actuallyHurt} 直接返回：不扣血，也不发伤害事件。
 * 这里把容器的数设成 0，<b>不</b>取消原版后面的流程（常和很多模组同装，吞掉别人的
 * {@code LivingDamageEvent.Pre} / {@code .Post} 不合适），但本次的伤害阶段不再发本模组的事件，
 * 吸血、命中附带效果这类伤害监听不会多跑一次。做法是把这个容器按引用记进跳过表，伤害阶段见到就移除并返回。
 * <p>跳过表用弱引用：别的模组在它自己的受伤阶段取消回调时，本模组的伤害阶段走不到，条目会留下；
 * 容器拽着伤害来源（攻击者、所在世界），强引用会一直拽住它们。容器在 {@code hurt} 末尾弹栈后就没人引用，条目随之回收。
 * {@link DamageContainer} 没有重写 {@code equals} / {@code hashCode}，弱表按引用比。
 * 不用每个实体一个字段：两个阶段之间 NeoForge 会发别的模组的 {@code LivingDamageEvent.Pre}，那里对同一实体
 * 再打一下会把字段覆盖掉。只在服务端线程读写（客户端的 {@code hurt} 进不到 {@code actuallyHurt}）。
 *
 * <h3>监听器里再打一下被取消时的漏栈</h3>
 * <p>NeoForge 的 {@code hurt} 先压容器、再发 {@code LivingIncomingDamageEvent}，被取消就直接返回、不弹容器。
 * 监听器里对同一目标再 {@code hurt} 一下而那一下被别的模组取消时，它的容器会留在栈顶，
 * 回到这一下后护甲、吸收、扣血读的都成了那个容器。所以发事件前记下栈深，发完把多出来的弹掉。
 */
public final class DamageStages {

    /** 受伤阶段已判定「这一下不扣血」的容器，伤害阶段见到就跳过。 */
    private static final Set<DamageContainer> SKIP_DAMAGE_STAGE =
            Collections.newSetFromMap(new WeakHashMap<DamageContainer, Boolean>());

    private DamageStages() {
    }

    /**
     * 受伤阶段：{@code actuallyHurt} 开头，对应 1.20.1 {@code actuallyHurt} 里的 {@code onLivingHurt}。
     *
     * <p>注入点在 {@code actuallyHurt} 自己的 {@code isInvulnerableTo} 判断之前，1.20.1 的事件在判断里面，
     * 所以这里先判一次；栈空（别的模组绕过 {@code hurt} 直接调）时也不发。
     * 进来的数是 0（盾全挡）也照发：1.20.1 盾全挡后照样调 {@code actuallyHurt}，{@code onLivingHurt} 不看数。
     *
     * @param entity     受击者
     * @param source     伤害来源
     * @param containers 受击者的伤害容器栈
     */
    public static void hurt(LivingEntity entity, DamageSource source, Stack<DamageContainer> containers) {
        if (containers.isEmpty() || entity.isInvulnerableTo(source)) {
            return;
        }
        DamageContainer container = containers.peek();
        int depth = containers.size();
        LivingHurtEvent event = NeoForge.EVENT_BUS.post(new LivingHurtEvent(entity, source, container.getNewDamage()));
        dropLeaked(containers, depth);
        if (event.isCanceled() || event.getAmount() <= 0.0F) {
            container.setNewDamage(0.0F);
            SKIP_DAMAGE_STAGE.add(container);
            return;
        }
        container.setNewDamage(event.getAmount());
    }

    /**
     * 伤害阶段：{@code actuallyHurt} 里扣完吸收之后、扣血之前，对应 1.20.1 的 {@code onLivingDamage}。
     * 数为 0 也发；取消视为 0；结果写回容器，原版下一行就按它扣血。
     *
     * @param entity     受击者
     * @param source     伤害来源
     * @param containers 受击者的伤害容器栈
     */
    public static void damage(LivingEntity entity, DamageSource source, Stack<DamageContainer> containers) {
        if (containers.isEmpty()) {
            return;
        }
        DamageContainer container = containers.peek();
        if (SKIP_DAMAGE_STAGE.remove(container)) {
            return;
        }
        int depth = containers.size();
        LivingDamageEvent event = NeoForge.EVENT_BUS.post(new LivingDamageEvent(entity, source, container.getNewDamage()));
        dropLeaked(containers, depth);
        container.setNewDamage(event.isCanceled() ? 0.0F : event.getAmount());
    }

    /** 把监听器里再打一下被取消后留下的容器弹掉，栈回到发事件前的深度。 */
    private static void dropLeaked(Stack<DamageContainer> containers, int depth) {
        while (containers.size() > depth) {
            containers.pop();
        }
    }
}
