package com.flameforge.albionmastery.mastery;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 击杀经验的统一入口。
 *
 * <p>专精经验只来自<b>生物实际掉落的经验值</b>，也就是所有模组都改完之后、真正会落地的那个数。
 *
 * <p>击杀者与附近分到经验的玩家，都按<b>主手加副手</b>两把武器一起结算，副手按比例打折。
 *
 * <p>最终数量还会乘上所在维度的倍率，见 {@link DimensionXpBonus}。共享的人和击杀者在同一维度，
 * 所以倍率只算一次就够。
 *
 * <p>拿到这个「最终值」有两条路，这里两条都留着，互相兜底：
 * <ol>
 *     <li><b>Mixin</b>（{@code ForgeEventFactoryMixin}）——注入在
 *         {@code ForgeEventFactory.getExperienceDrop} 的返回处。事件的所有监听器都跑完了才轮到它，
 *         拿到的必然是最终值。</li>
 *     <li><b>事件监听</b>（{@code EventPriority.LOWEST}）——兜底用。
 *         {@code LOWEST} 只是个优先级桶，同桶内按注册顺序执行，别的模组也用 LOWEST 时
 *         谁先谁后是不确定的，所以它读到的值<b>不保证</b>是最终值。</li>
 * </ol>
 *
 * <p>两条路的配合方式：监听器只把结果<b>暂存</b>不发放；Mixin 跑到时把暂存项换成最终值发出去，
 * 同时置位 {@link #mixinActive}，之后监听器就直接跳过。要是 Mixin 压根没生效（比如 Forge 改了
 * 那个方法的签名），暂存项会在服务端 tick 末尾被兜底发放。这样既不会重复计算，也不会静默失效。
 */
public final class MasteryExperienceHook {

    /** Mixin 是否已经生效过。生效之后监听器就不再暂存了。 */
    private static volatile boolean mixinActive = false;

    /** 待发放的击杀奖励，键是被杀生物的实体 ID。 */
    private static final Map<Integer, Pending> PENDING = new HashMap<>();

    private MasteryExperienceHook() {
    }

    /**
     * 事件监听器调用：先暂存，等 Mixin 或 tick 末尾兜底发放。
     *
     * @param entityId       被杀生物的实体 ID
     * @param player         击杀者
     * @param droppedExperience 该监听器看到的掉落经验（不保证是最终值）
     */
    public static void record(int entityId, Player player, int droppedExperience) {
        if (mixinActive || !(player instanceof ServerPlayer)) {
            return;
        }
        PENDING.put(entityId, new Pending(player, droppedExperience));
    }

    /**
     * Mixin 调用：这是所有监听器跑完之后的最终值，直接发放。
     *
     * @param entityId          被杀生物的实体 ID
     * @param player            击杀者
     * @param droppedExperience 最终掉落经验
     */
    public static void commitFinal(int entityId, Player player, int droppedExperience) {
        mixinActive = true;
        PENDING.remove(entityId);
        award(player, droppedExperience);
    }

    /**
     * 服务端 tick 末尾兜底：把没被 Mixin 接手的暂存项发出去。
     *
     * <p>正常情况下这里永远是空的——Mixin 会在同一次调用里就把暂存项取走。
     */
    public static void flushPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> pending = new ArrayList<>(PENDING.values());
        PENDING.clear();
        for (Pending entry : pending) {
            award(entry.player, entry.droppedExperience);
        }
    }

    /**
     * 断线 / 关服时清空暂存，避免残留引用。
     */
    public static void clear() {
        PENDING.clear();
    }

    /**
     * 按掉落经验发放专精经验，并把一部分分给附近的其他玩家。
     *
     * @param killer            击杀者
     * @param droppedExperience 生物实际掉落的经验值
     */
    /**
     * 把一次击杀的经验分给附近所有玩家。
     *
     * <p>不再区分「击杀者」和「帮打的」：范围内每个还练得动的玩家都是一份，
     * 每人拿到的比例按参与人数查表（参考 LoL 的共享经验曲线）——
     * 一个人吃满，人越多每人越少，但队伍总收益是上升的。
     *
     * <p>「练得动」的判断是主副手至少有一件可专精武器还没三层全满。
     * 全满的人<b>不占人头</b>：不然一个满级大佬站旁边，就会白白把队友的收益压下去一档。
     *
     * @param killer            造成击杀的玩家，用来确定维度和位置
     * @param droppedExperience 怪物掉落的原版经验
     */
    private static void award(Player killer, int droppedExperience) {
        if (!(killer instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        int dropped = Math.max(0, droppedExperience);

        double base = MasteryConfig.COMMON.xpPerKill.get()
                + dropped * MasteryConfig.COMMON.xpPerMobExperience.get();
        // 危险的地方打怪更划算：下界 ×1.5、末地 ×2、模组维度 ×2
        base *= DimensionXpBonus.multiplierFor(player.level());
        if (base <= 0.0D) {
            return;
        }

        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        double radius = MasteryConfig.COMMON.nearbyShareRadius.get();
        double radiusSqr = radius * radius;

        List<ServerPlayer> participants = new ArrayList<>();
        for (ServerPlayer candidate : level.players()) {
            if (candidate.isSpectator()) {
                continue;
            }
            // 半径设成 0 就退化成「只有击杀者自己」
            if (candidate != player && (radius <= 0.0D
                    || candidate.distanceToSqr(player) > radiusSqr)) {
                continue;
            }
            if (hasRoomToLevel(candidate, snapshot)) {
                participants.add(candidate);
            }
        }
        if (participants.isEmpty()) {
            return;
        }

        double share = falloffFor(participants.size());
        long amount = Math.max(1L, Math.round(base * share));
        for (ServerPlayer participant : participants) {
            awardTo(participant, amount);
        }
    }

    /**
     * 查参与人数对应的每人比例。
     *
     * @param count 参与人数，至少 1
     * @return 比例；表为空时退回 1.0
     */
    private static double falloffFor(int count) {
        List<? extends Double> table = MasteryConfig.COMMON.shareFalloff.get();
        if (table.isEmpty()) {
            return 1.0D;
        }
        // 人数超过表长就一直用最后一项，不会越查越少变成 0
        Double value = table.get(Math.min(count, table.size()) - 1);
        if (value == null || value.isNaN() || value <= 0.0D) {
            return 0.0D;
        }
        return Math.min(1.0D, value);
    }

    /**
     * 判断一名玩家手上还有没有练得动的武器。
     *
     * <p>三层里任意一层没满就算练得动。主副手都没有可专精武器，或者拿着的都三层全满，
     * 就当他不参与这次分摊。
     *
     * @param player   玩家
     * @param snapshot 配置快照
     * @return 还练得动返回 true
     */
    private static boolean hasRoomToLevel(ServerPlayer player, MasteryConfig.Snapshot snapshot) {
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return false;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ResolvedWeapon weapon = WeaponResolver.resolve(player.getItemInHand(hand));
            if (weapon == null) {
                continue;
            }
            if (data.getWeaponLevel(weapon.weaponKey()) < snapshot.maxLevel
                    || data.getTypeLevel(weapon.typeKey()) < snapshot.maxLevel
                    || data.getOverallLevel(weapon.overallKey()) < snapshot.maxLevel) {
                return true;
            }
        }
        return false;
    }

    private static void awardTo(ServerPlayer player, long amount) {
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }
        double offhandRatio = MasteryConfig.COMMON.offhandXpRatio.get();
        Set<String> awarded = new HashSet<>(2);

        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            ResolvedWeapon weapon = WeaponResolver.resolve(stack);
            if (weapon == null || !awarded.add(weapon.weaponKey())) {
                continue;
            }
            long give = hand == InteractionHand.MAIN_HAND
                    ? amount : Math.round(amount * offhandRatio);
            if (give <= 0L) {
                continue;
            }
            try {
                MasteryHelper.awardXp(data, weapon, give, MasteryConfig.serverSnapshot());
            } catch (Exception exception) {
                AlbionMastery.LOGGER.error("发放击杀专精经验失败: {}", weapon.weaponKey(), exception);
            }
        }
    }

    /**
     * 暂存的一次击杀奖励。
     *
     * @param player            击杀者
     * @param droppedExperience 掉落经验
     */
    private record Pending(Player player, int droppedExperience) {
    }
}
