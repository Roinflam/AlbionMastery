package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.client.PlayerAnimSpeed;
import com.flameforge.albionmastery.mastery.spec.SpecVisualAttributes;
import me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration;
import me.xjqsh.lrtactical.compat.player_animator.ThirdPersonAnimationConfig;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 别人看到的挥刀动作也按攻速特化的倍速播。
 *
 * <p>补的是什么：近战附属的第三人称挥砍是 Player Animator 的关键帧动作，长度写死。
 * 攻速特化把出手间隔压到六成，别人看到的还是原速：攻速快时上一刀的动作还没播完下一刀就到了，
 * 攻速慢时刀早就划过去了伤害才结算。第一人称那边已经由 {@code ObjectAnimationRunnerMixin} 管了。
 *
 * <p>待机动作恢复原速：倍速修饰符是挂在整层上的，挥砍完不还原，站着不动的姿势也会跟着变速。
 *
 * <p>lrtactical 0.4.3 把层枚举从 {@code PlayerAnimatorIntegration} 挪进了
 * {@code ThirdPersonAnimationConfig}，旋转层的常量也从 {@code ROTATION_ANIMATION} 改名为
 * {@code ROTATION_LAYER}，两个方法的描述符跟着变了，这里写全，免得同名方法以后再加重载时选错。
 * 每个方法只注入一个 {@code RETURN} 处理器（攻击动作 2 个返回点、待机动作 3 个，都会调到同一个处理器），
 * 按注入器计数 {@code expect = 1}。
 *
 * <p>它或 Player Animator 没装时整个 Mixin 不应用。
 */
@Mixin(PlayerAnimatorIntegration.class)
public abstract class MeleeThirdPersonAnimMixin {

    /**
     * 挥砍动作：按这名玩家的攻速倍率播。
     *
     * @param player      玩家
     * @param animationId 动画资源
     * @param name        动画名
     * @param layer       动画层
     * @param priority    优先级
     * @param ci          回调
     */
    @Inject(method = "playAttackAnimation(Lnet/minecraft/client/player/AbstractClientPlayer;"
            + "Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;"
            + "Lme/xjqsh/lrtactical/compat/player_animator/ThirdPersonAnimationConfig$AnimationLayer;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private static void albionmastery$attackSpeed(AbstractClientPlayer player, ResourceLocation animationId,
                                                  String name,
                                                  ThirdPersonAnimationConfig.AnimationLayer layer,
                                                  int priority, CallbackInfo ci) {
        PlayerAnimSpeed.apply(player, albionmastery$layerId(layer),
                SpecVisualAttributes.of(player, SpecVisualAttributes.MELEE_SPEED));
    }

    /**
     * 待机动作：恢复原速。
     *
     * @param player      玩家
     * @param animationId 动画资源
     * @param name        动画名
     * @param layer       动画层
     * @param priority    优先级
     * @param ci          回调
     */
    @Inject(method = "playIdleAnimation(Lnet/minecraft/client/player/AbstractClientPlayer;"
            + "Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;"
            + "Lme/xjqsh/lrtactical/compat/player_animator/ThirdPersonAnimationConfig$AnimationLayer;I)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private static void albionmastery$idleSpeed(AbstractClientPlayer player, ResourceLocation animationId,
                                                String name,
                                                ThirdPersonAnimationConfig.AnimationLayer layer,
                                                int priority, CallbackInfo ci) {
        PlayerAnimSpeed.apply(player, albionmastery$layerId(layer), 1.0D);
    }

    /**
     * 层枚举换成层 ID。
     *
     * <p>枚举里的 ID 字段是私有的，但这三个 ID 本身是公开常量，按枚举值对过去就行。
     *
     * @param layer 层枚举
     * @return 层 ID
     */
    @Unique
    private static ResourceLocation albionmastery$layerId(ThirdPersonAnimationConfig.AnimationLayer layer) {
        if (layer == ThirdPersonAnimationConfig.AnimationLayer.LOWER) {
            return PlayerAnimatorIntegration.LOWER_LAYER;
        }
        if (layer == ThirdPersonAnimationConfig.AnimationLayer.ROTATION) {
            return PlayerAnimatorIntegration.ROTATION_LAYER;
        }
        return PlayerAnimatorIntegration.UPPER_LAYER;
    }
}
