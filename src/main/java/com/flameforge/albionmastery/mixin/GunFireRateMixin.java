package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 射速按特化缩放。
 *
 * <p>补的是什么：TACZ 所有「两发之间要隔多久」的判断都来自 {@code GunData.getShootInterval(射手, 开火模式, 枪)}——
 * 客户端 {@code LocalPlayerShoot} 判能不能开下一枪、服务端 {@code LivingEntityShoot} 校验客户端发来的开火包、
 * 动画状态机和 Lua 脚本取射击间隔，走的都是这一个方法，而且它自带射手和物品，正好能查特化。
 * 在返回值上除以射速倍率，两端一起变，服务端不会把客户端「开快了」的那几枪当作作弊丢掉。
 *
 * <p>下限 50 ms（1200 RPM）：TACZ 的全自动是客户端每 tick 尝试开一枪，一秒最多 20 发，
 * 间隔再短也打不出来，反而会让服务端的冷却校验和客户端对不上。原本就比 50 ms 短的（过热枪加速之类）不去动它。
 *
 * <p>点射（BURST）模式的连发间隔和最小间隔由 {@code BurstData} 单独给，那几处拿不到射手，不受射速影响。
 */
@Mixin(value = GunData.class, remap = false)
public abstract class GunFireRateMixin {

    /** 最短射击间隔（毫秒），对应 TACZ 自己 RPM 上限 1200。 */
    @Unique
    private static final long ALBIONMASTERY$MIN_INTERVAL_MS = 50L;

    /**
     * 缩放射击间隔。
     *
     * @param original 原始间隔（毫秒）
     * @param shooter  射手
     * @param fireMode 开火模式
     * @param gunStack 枪
     * @return 缩放后的间隔
     */
    @ModifyReturnValue(method = "getShootInterval", at = @At("RETURN"), require = 0)
    private long albionmastery$fireRate(long original, LivingEntity shooter, FireMode fireMode, ItemStack gunStack) {
        if (original <= 0L) {
            return original;
        }
        double factor = SpecializationEffects.forStack(shooter, gunStack).fireRate();
        if (factor == 1.0D || factor <= 0.0D) {
            return original;
        }
        long scaled = Math.round(original / factor);
        return Math.max(Math.min(original, ALBIONMASTERY$MIN_INTERVAL_MS), scaled);
    }
}
