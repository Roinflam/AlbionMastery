package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import javax.annotation.Nullable;

/**
 * 按特化缩放武器的耐久消耗。
 *
 * <p>补的是什么：耐久扣减只剩 {@code hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)} 一条路，
 * 另两个重载都转调它。近战附属和拔刀剑也是靠这条路扣耐久的，所以在这里把扣减量按特化倍率缩放一下就够，
 * 不用去碰两个模组各自的代码。TACZ 枪械没有耐久概念，解析出来是枪械就不动。
 *
 * <p>改的是 {@code EnchantmentHelper.processDurabilityChange} 的第 3 个实参（原版和 NeoForge 里只有这一处调用）：
 * 到这里物品自己的 {@code damageItem} 钩子已经处理过、创造模式已经排除、扣减量已经判过 &gt; 0，
 * 正好是以前「{@code ItemStack.hurt} 开头、耐久附魔判定之前」那个位置。缩放到 0 时原版下一行直接返回，
 * 和以前 {@code hurt} 收到 0 一样。不在调用前改局部变量：到调用处实参已经压栈，改了不生效。
 *
 * <p>只对玩家生效：没有玩家就没有特化，也就没什么可缩放的。
 *
 * <p>倍率乘出来带小数时用<b>概率取整</b>而不是四舍五入：近战每砍一下扣 1 点，倍率 0.6
 * 四舍五入永远是 1，等于没生效；改成「60% 概率扣 1、40% 概率扣 0」，长期平均正好是 0.6。
 *
 * <p>命中数上下限都是 1：多打上会把倍率乘两遍。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackDurabilityMixin {

    /**
     * 缩放耐久扣减量。
     *
     * @param amount 原始扣减量
     * @param user   扣耐久的实体，可能为 null（外层方法参数里唯一的 {@code LivingEntity}）
     * @return 缩放后的扣减量
     */
    @ModifyArg(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;processDurabilityChange(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;I)I"),
            index = 2, expect = 1, allow = 1)
    private int albionmastery$scaleDurabilityDamage(int amount, @Local(argsOnly = true) @Nullable LivingEntity user) {
        if (!(user instanceof ServerPlayer player) || amount <= 0) {
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
        MasteryData data = MasteryHelper.get(player);
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
        // 随机数取使用者自己的：以前传进来的随机源就是使用者的 getRandom()
        if (fraction > 0.0D && player.getRandom().nextDouble() < fraction) {
            whole++;
        }
        return Math.max(0, whole);
    }
}
