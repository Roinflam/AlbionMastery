package com.flameforge.albionmastery.event.damage;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

/**
 * 伤害链的「伤害阶段」：护甲、魔法减免和扣吸收都做完之后、扣血之前发出，可取消。
 *
 * <p>对应 1.20.1 Forge 的 {@code LivingDamageEvent}：此时吸收值已经扣过，事件里的数就是要扣的血量，
 * 为 0 也照样发；取消视为 0。类名和方法和 1.20.1 一样，伤害监听只换 import。
 *
 * <p><b>同名提醒：</b>NeoForge 的 {@code net.neoforged.neoforge.event.entity.living.LivingDamageEvent}
 * 是 {@code Pre} / {@code Post} 的父类，和这里不是一回事，同一个文件两个都要用时写全名。
 *
 * <p>在 {@code NeoForge.EVENT_BUS} 上发，只给本模组自己的监听用，不作为对外接口。
 * 由 {@link DamageStages#damage} 发出。
 */
public class LivingDamageEvent extends LivingEvent implements ICancellableEvent {

    private final DamageSource source;
    private float amount;

    public LivingDamageEvent(LivingEntity entity, DamageSource source, float amount) {
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
