package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.MasteryExperienceHook;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.EventHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 抓取生物掉落经验的<b>最终值</b>。
 *
 * <p>补的是什么：{@code EventHooks.getExperienceDrop} 内部会触发
 * {@code LivingExperienceDropEvent}，然后把事件里的结果返回出去。注入在它的返回处，
 * 意味着所有监听器（经验倍率、掠夺扩展、自定义怪物之类）都已经改完了，
 * 拿到的必然是真正会落地的那个数。
 *
 * <p>为什么不能只靠监听事件：{@code EventPriority.LOWEST} 只是一个优先级桶，
 * 同桶内按注册顺序执行。别的模组也用 LOWEST 时谁先谁后是不确定的，
 * 监听器读到的值不保证是最终值。
 *
 * <p>这里只读不写，不会影响玩家实际能捡到的经验球。
 *
 * <p>方法里两个 {@code return}（事件被取消时返回 0、否则返回事件里的数），一次调用只走其中一个，
 * 所以命中数正好是 2。目标版本固定，注入失败要在开发时就报出来，不写 {@code require = 0}；
 * 多打上会把一次击杀的经验发两遍，上限也卡在 2。
 */
@Mixin(EventHooks.class)
public abstract class EventHooksMixin {

    /**
     * 在返回处抓取最终掉落经验。
     *
     * @param entity          被杀生物
     * @param attackingPlayer 击杀者，可能为 null
     * @param experience      原始经验值
     * @param callback        回调，携带最终返回值
     */
    @Inject(method = "getExperienceDrop(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/player/Player;I)I",
            at = @At("RETURN"), expect = 2, allow = 2)
    private static void albionmastery$captureFinalExperience(LivingEntity entity, Player attackingPlayer,
                                                             int experience,
                                                             CallbackInfoReturnable<Integer> callback) {
        if (attackingPlayer == null || entity == null || MasteryExperienceHook.isCustomNpc(entity)
                || !MasteryExperienceHook.isEligibleKill(entity, attackingPlayer)) {
            return;
        }
        // 第三个参数是进事件之前的原版数值，返回值是所有模组改完之后实际要掉的数，两个都传下去按配置挑
        MasteryExperienceHook.commitFinal(entity.getId(), attackingPlayer, experience, callback.getReturnValueI());
    }
}
