package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryExperienceHook;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 击杀奖励的事件侧入口。
 *
 * <p>击杀专精经验按被杀生物<b>实际掉落的经验值</b>来给，打一只鸡和打一只凋灵不该给一样多。
 *
 * <p>这里读到的值<b>不保证</b>是最终值——{@code LOWEST} 只是个优先级桶，同桶内按注册顺序执行，
 * 别的模组也用 LOWEST 时谁先谁后是不确定的。所以这里只把结果暂存下来，
 * 交给 {@code ForgeEventFactoryMixin} 用真正的最终值覆盖；
 * Mixin 万一没生效，暂存项会在 tick 末尾被兜底发放。详见 {@link MasteryExperienceHook}。
 */
@Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID)
public final class KillRewardHandler {

    private KillRewardHandler() {
    }

    /**
     * 生物掉落经验时暂存一次击杀奖励。
     *
     * @param event 掉落经验事件
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        Player attacker = event.getAttackingPlayer();
        if (!MasteryExperienceHook.isEligibleKill(event.getEntity(), attacker)
                || !(attacker instanceof ServerPlayer player)) {
            return;
        }
        // NPC 走死亡事件按固定值给，这里不重复
        if (MasteryExperienceHook.isCustomNpc(event.getEntity())) {
            return;
        }
        // 事件被取消意味着这只怪最终不掉经验
        int dropped = event.isCanceled() ? 0 : Math.max(0, event.getDroppedExperience());
        MasteryExperienceHook.record(event.getEntity().getId(), player,
                Math.max(0, event.getOriginalExperience()), dropped);
    }

    /**
     * 自定义 NPC 被玩家杀死：按它编辑页里设的掉落经验给专精经验。
     *
     * <p>NPC 大多不掉原版经验，掉落经验那条路不会触发，所以单独挂死亡事件。
     * 谁算击杀者和别处一样看伤害源的 {@code getEntity()}，子弹、法术都能追到玩家。
     *
     * @param event 死亡事件
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onNpcDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !MasteryExperienceHook.isCustomNpc(event.getEntity())) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)
                || !MasteryExperienceHook.isEligibleKill(event.getEntity(), player)) {
            return;
        }
        MasteryExperienceHook.awardNpcKill(player, event.getEntity());
    }
}
