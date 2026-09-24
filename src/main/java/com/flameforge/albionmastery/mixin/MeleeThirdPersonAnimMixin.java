package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.client.PlayerAnimSpeed;
import com.flameforge.albionmastery.mastery.spec.SpecVisualAttributes;
import me.xjqsh.lrtactical.compat.player_animator.PlayerAnimatorIntegration;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
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
 * <p>目标是近战附属的类，{@code remap = false}；它或 Player Animator 没装时整个 Mixin 不应用。
 */
@Mixin(value = PlayerAnimatorIntegration.class, remap = false)
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
    @Inject(method = "playAttackAnimation", at = @At("RETURN"), require = 0)
    private static void albionmastery$attackSpeed(AbstractClientPlayer player, ResourceLocation animationId,
                                                  String name,
                                                  PlayerAnimatorIntegration.AnimationLayer layer,
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
    @Inject(method = "playIdleAnimation", at = @At("RETURN"), require = 0)
    private static void albionmastery$idleSpeed(AbstractClientPlayer player, ResourceLocation animationId,
                                                String name,
                                                PlayerAnimatorIntegration.AnimationLayer layer,
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
    private static ResourceLocation albionmastery$layerId(PlayerAnimatorIntegration.AnimationLayer layer) {
        if (layer == PlayerAnimatorIntegration.AnimationLayer.LOWER) {
            return PlayerAnimatorIntegration.LOWER_LAYER;
        }
        if (layer == PlayerAnimatorIntegration.AnimationLayer.ROTATION) {
            return PlayerAnimatorIntegration.ROTATION_ANIMATION;
        }
        return PlayerAnimatorIntegration.UPPER_LAYER;
    }
}
