package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * 子弹初速按特化缩放。
 *
 * <p>补的是什么：TACZ 开枪（{@code ModernKineticGunScriptAPI.shootOnce}）时，子弹速度是从配件缓存里按
 * {@code GunProperties.AMMO_SPEED} 这个属性取出来的（配件加成已经乘进去了），之后再乘全局配置。
 * 这里在「取 AMMO_SPEED」那一次调用的返回值上再乘特化倍率。切片从读取 {@code AMMO_SPEED} 常量开始，
 * 之后第一次 {@code getCache(GunProperty)} 就是它，别的属性（精度之类）不会误伤。
 *
 * <p>两端都会开枪（服务端真子弹、客户端预测子弹），两端各按各的数据算，倍率一样。
 * 倍率上限 2.5：再快子弹一 tick 飞太远，碰撞检测会漏。
 */
@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class GunBulletSpeedMixin {

    @Shadow
    public abstract LivingEntity getShooter();

    @Shadow
    public abstract ItemStack getItemStack();

    /**
     * 缩放取出来的弹速。缓存里放的是装箱的 Float，原样按 Object 接、按 Object 还。
     *
     * @param original 缓存里的弹速
     * @return 缩放后的弹速
     */
    @ModifyExpressionValue(
            method = "shootOnce(Z)V",
            slice = @Slice(from = @At(value = "FIELD",
                    target = "Lcom/tacz/guns/api/GunProperties;AMMO_SPEED:Lcom/tacz/guns/api/GunProperty;",
                    opcode = Opcodes.GETSTATIC, ordinal = 0)),
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Lcom/tacz/guns/resource/modifier/AttachmentCacheProperty;getCache"
                            + "(Lcom/tacz/guns/api/GunProperty;)Ljava/lang/Object;"),
            require = 0, expect = 1
    )
    private Object albionmastery$bulletSpeed(Object original) {
        if (!(original instanceof Float speed)) {
            return original;
        }
        double factor = SpecializationEffects.forStack(getShooter(), getItemStack()).bulletSpeed();
        return factor == 1.0D ? original : (Float) (float) (speed * factor);
    }
}
