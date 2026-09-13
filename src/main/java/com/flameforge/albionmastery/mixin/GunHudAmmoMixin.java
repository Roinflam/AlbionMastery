package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.client.ClientMasteryCache;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import com.tacz.guns.client.gui.overlay.GunHudOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 让准星旁边的弹药 HUD 显示扩容之后的弹匣上限。
 *
 * <p>逻辑侧（换弹状态机）已经在 {@code GunAmmoAndReloadMixin} 里改过了，
 * 但 HUD 是另一条独立的取值路径。不补这一处，玩家会看到「45 / 30」这种鬼东西。
 *
 * <p>这里读的是客户端同步缓存里的数据，而不是本地配置：多人服里数值以服务端为准。
 * HUD 画的一定是本地玩家自己的枪，所以直接用同步缓存就够，不需要再判断是谁。
 */
@Mixin(value = GunHudOverlay.class, remap = false)
public abstract class GunHudAmmoMixin {

    /**
     * 把 HUD 上的弹匣上限换成应用特化之后的值。
     *
     * @param original 原始容量
     * @return 应用特化之后的容量
     */
    @ModifyExpressionValue(
            method = "handleCacheCount",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/util/AttachmentDataUtils;getAmmoCountWithAttachment"
                            + "(Lnet/minecraft/world/item/ItemStack;"
                            + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;)I"
            ),
            require = 0
    )
    private static int albionmastery$applyMagazineSpec(int original, LocalPlayer player,
                                                      ItemStack stack) {
        SpecializationValues values = SpecializationEffects.forStack(player, stack);
        if (values.magazine() == 1.0D) {
            return original;
        }
        return Math.max(1, (int) Math.round(original * values.magazine()));
    }
}
