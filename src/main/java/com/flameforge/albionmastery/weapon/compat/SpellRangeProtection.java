package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.config.MasteryConfig;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;

/**
 * 为法术范围计算提供不含本模组专精加成的原始强度。
 *
 * <p>不复制铁魔法公式，不移除装备属性；仅在调用原方法期间让本模组的强度 Mixin 跳过。
 * 标记按线程隔离，客户端渲染与集成服务端不会互相影响；finally 保证嵌套调用和异常后恢复。
 * 每个线程仅首次使用时分配一个布尔数组，重复计算不扫描实体、不抓取调用栈。
 * 本类含可选模组类型，只由铁魔法及其附属 Mixin 在目标模组存在时调用。
 */
public final class SpellRangeProtection {

    /** 当前线程是否正在计算不受专精放大的法术范围。 */
    private static final ThreadLocal<boolean[]> SUPPRESSED = ThreadLocal.withInitial(() -> new boolean[1]);

    /** 工具类不允许实例化。 */
    private SpellRangeProtection() {
    }

    /**
     * 判断本次强度计算是否需要跳过专精加成。
     *
     * @return 处于范围计算上下文时为 true
     */
    public static boolean isSuppressed() {
        return SUPPRESSED.get()[0];
    }

    /**
     * 根据施法者所在逻辑端获取范围保护状态。
     *
     * @param caster 施法者，可为空；非玩家本来不吃专精加成
     * @return 玩家且服务端同步的范围保护已开启时为 true
     */
    public static boolean isEnabled(@Nullable Entity caster) {
        if (!(caster instanceof Player player)) {
            return false;
        }
        if (!player.level().isClientSide()) {
            return MasteryConfig.serverSnapshot().preventMasterySpellRangeScaling;
        }
        MasteryConfig.Snapshot snapshot = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                () -> () -> com.flameforge.albionmastery.client.ClientMasteryCache.snapshot());
        return snapshot == null || snapshot.preventMasterySpellRangeScaling;
    }

    /**
     * 为半径、距离或挖掘尺寸调用原始法术强度公式。
     *
     * @param spell 实际法术实例，来自已验证的调用点
     * @param spellLevel 原始法术等级
     * @param caster 施法者，可为空
     * @return 保留本体、配置与装备影响的强度；关闭保护时包含原专精加成
     * @throws RuntimeException 原法术方法失败时原样传播，保护状态仍会恢复
     */
    public static float spellPower(AbstractSpell spell, int spellLevel, @Nullable Entity caster) {
        if (!isEnabled(caster)) {
            return spell.getSpellPower(spellLevel, caster);
        }
        boolean[] state = SUPPRESSED.get();
        boolean previous = state[0];
        state[0] = true;
        try {
            return spell.getSpellPower(spellLevel, caster);
        } finally {
            state[0] = previous;
        }
    }

    /**
     * 为范围计算调用原始施法者强度倍率公式。
     *
     * @param spell 实际法术实例，来自已验证的调用点
     * @param caster 施法者，可为空
     * @return 保留本体、配置与装备影响的倍率；关闭保护时包含原专精加成
     * @throws RuntimeException 原法术方法失败时原样传播，保护状态仍会恢复
     */
    public static float entityPower(AbstractSpell spell, @Nullable LivingEntity caster) {
        if (!isEnabled(caster)) {
            return spell.getEntityPowerMultiplier(caster);
        }
        boolean[] state = SUPPRESSED.get();
        boolean previous = state[0];
        state[0] = true;
        try {
            return spell.getEntityPowerMultiplier(caster);
        } finally {
            state[0] = previous;
        }
    }
}
