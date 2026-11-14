package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.GunRateScaling;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 枪械动画状态机看到的弹匣容量和点射间隔，和实际（服务端）用的保持一致。
 *
 * <ul>
 *     <li>弹匣容量：管式装填的霰弹枪按「最大容量 − 当前弹数」决定逐发装填循环几次。
 *         扩容之后动画按原容量播完就回到待机，服务端却还在一发一发往里装（开火被拒）；缩容时反过来多播几次。</li>
 *     <li>点射间隔：动画里用来控制点射节奏。</li>
 * </ul>
 */
@Mixin(GunAnimationStateContext.class)
public abstract class GunAnimationContextMixin {

    @Shadow
    private ItemStack currentGunItem;

    /**
     * 动画用的最大弹数。
     *
     * @param original 原容量
     * @return 按本地玩家特化缩放后的容量，和服务端、HUD 同一个算法
     */
    @ModifyExpressionValue(
            method = "lambda$getMaxAmmoCount$6(Lcom/tacz/guns/api/item/IGun;"
                    + "Lcom/tacz/guns/client/resource/GunDisplayInstance;)Ljava/lang/Integer;",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/util/AttachmentDataUtils;getAmmoCountWithAttachment"
                            + "(Lnet/minecraft/world/item/ItemStack;"
                            + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;)I"),
            require = 0,
            expect = 1
    )
    private int albionmastery$maxAmmo(int original) {
        if (this.currentGunItem == null || Minecraft.getInstance().player == null) {
            return original;
        }
        SpecializationValues values = SpecializationEffects.forStack(Minecraft.getInstance().player,
                this.currentGunItem);
        if (values.magazine() == 1.0D) {
            return original;
        }
        return Math.max(1, (int) Math.round(original * values.magazine()));
    }

    /**
     * 动画用的点射间隔。
     *
     * @param interval 原间隔（秒）
     * @param entity   持枪的实体
     * @return 缩放后的间隔
     */
    @ModifyExpressionValue(
            method = "lambda$getShootInterval$2(Lnet/minecraft/world/entity/Entity;)Ljava/lang/Long;",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/resource/pojo/data/gun/BurstData;getMinInterval()D"),
            require = 0,
            expect = 1
    )
    private double albionmastery$burstInterval(double interval, Entity entity) {
        return entity instanceof LivingEntity living ? GunRateScaling.scaleSeconds(living, interval) : interval;
    }
}
