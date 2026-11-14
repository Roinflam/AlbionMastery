package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 让「还能不能换弹」的判断也认扩容后的弹匣容量。
 *
 * <p>为什么要单独补这一处：{@code canReload} 是拿「当前弹药」和「弹匣容量」比大小，
 * 用的还是原始容量。扩容弹匣把上限从 30 提到 60 之后，如果这里不改，
 * 玩家装到 30 发就会被判定为「已经满了」，再也按不动换弹键——
 * 表现出来就是「扩容了但装不满」，非常难查。
 *
 * <p>这个方法带 {@code LivingEntity} 参数，正好是我们需要的玩家上下文。
 * MixinExtras 允许处理方法在原值后面接着声明目标方法的参数，直接拿来用。
 */
@Mixin(value = AbstractGunItem.class, remap = false)
public abstract class GunCanReloadMixin {

    /**
     * 把判断用的弹匣容量换成应用特化之后的值。
     *
     * @param original 原始容量
     * @param entity   持枪实体
     * @param stack    枪械物品堆
     * @return 应用特化之后的容量
     */
    @ModifyExpressionValue(
            method = "canReload(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/util/AttachmentDataUtils;getAmmoCountWithAttachment"
                            + "(Lnet/minecraft/world/item/ItemStack;"
                            + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;)I"
            ),
            require = 0, expect = 1
    )
    private int albionmastery$applyMagazineSpec(int original, LivingEntity entity, ItemStack stack) {
        SpecializationValues values = SpecializationEffects.forStack(entity, stack);
        if (values.magazine() == 1.0D) {
            return original;
        }
        return Math.max(1, (int) Math.round(original * values.magazine()));
    }
}
