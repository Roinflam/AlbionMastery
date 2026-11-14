package com.flameforge.albionmastery.event.damage;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

/**
 * 伤害链的「受伤阶段」：{@code actuallyHurt} 开头、护甲和魔法减免之前发出，可取消。
 *
 * <p>对应 1.20.1 Forge 的 {@code LivingHurtEvent}，位置一样（盾、冰冻、无敌帧之后，护甲之前），
 * 类名和方法也一样，伤害监听只换 import：
 * <ul>
 *     <li>这里改的数会再被护甲、抗性减；</li>
 *     <li>取消或改到 0 及以下，这一下不扣血，本次的 {@link LivingDamageEvent} 也不再发（1.20.1 在这里直接返回）；</li>
 *     <li>{@code isInvulnerableTo} 为真时不发。</li>
 * </ul>
 *
 * <p>在 {@code NeoForge.EVENT_BUS} 上发，只给本模组自己的监听用，不作为对外接口。
 * 由 {@link DamageStages#hurt} 发出。
 */
public class LivingHurtEvent extends LivingEvent implements ICancellableEvent {

    private final DamageSource source;
    private float amount;

    public LivingHurtEvent(LivingEntity entity, DamageSource source, float amount) {
        super(entity);
        this.source = source;
        this.amount = amount;
    }

    public DamageSource getSource() {
        return source;
    }

    public float getAmount() {
        return amount;
    }

    public void setAmount(float amount) {
        this.amount = amount;
    }
}
