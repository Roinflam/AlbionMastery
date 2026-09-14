package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 按流派缩放武器的耐久消耗。
 *
 * <p>补的是什么：原版所有耐久扣减最终都走 {@code ItemStack.hurt(amount, random, user)}
 * （{@code hurtAndBreak} 也是先调它）。近战附属和拔刀剑都是靠这条路扣耐久的，
 * 所以在这个方法的入口把 {@code amount} 参数按流派倍率缩放一下就够，不用去碰两个模组各自的代码。
 * TACZ 枪械没有耐久概念，解析出来是枪械就不动。
 *
 * <p>只在带 {@code user} 的调用上生效：没有玩家就没有流派，也就没什么可缩放的。
 *
 * <p>倍率乘出来带小数时用<b>概率取整</b>而不是四舍五入：近战每砍一下扣 1 点，倍率 0.6
 * 四舍五入永远是 1，等于没生效；改成「60% 概率扣 1、40% 概率扣 0」，长期平均正好是 0.6。
 *
 * <p>目标是原版方法，正式环境名字会被混淆，所以这个 Mixin <b>不能</b>加 {@code remap = false}。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackDurabilityMixin {

    /**
     * 缩放耐久扣减量。
     *
     * <p>处理方法的参数在「被改的变量」之后接目标方法的完整参数表，拿到随机源和玩家。
     *
     * @param amount 原始扣减量（就是第一个参数本身）
     * @param original 目标方法的第一个参数（和 amount 相同，Mixin 要求把参数表写全）
     * @param random 随机源
     * @param user   扣耐久的玩家，可能为 null
     * @return 缩放后的扣减量
     */
    @ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int albionmastery$scaleDurabilityDamage(int amount, int original, RandomSource random,
                                                    ServerPlayer user) {
        if (user == null || amount <= 0) {
            return amount;
        }
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        if (!snapshot.enableSpecialization) {
            return amount;
        }
        ItemStack self = (ItemStack) (Object) this;
        ResolvedWeapon weapon = WeaponResolver.resolve(self, snapshot.splitMeleeByPack);
        if (weapon == null) {
            return amount;
        }
        MasteryData data = MasteryHelper.get(user);
        if (data == null) {
            return amount;
        }
        SpecializationValues values = SpecializationEffects.forData(data, weapon, snapshot);
        double factor = values.durability();
        if (factor == 1.0D) {
            return amount;
        }
        double scaled = amount * factor;
        int whole = (int) Math.floor(scaled);
        double fraction = scaled - whole;
        if (fraction > 0.0D && random.nextDouble() < fraction) {
            whole++;
        }
        return Math.max(0, whole);
    }
}
