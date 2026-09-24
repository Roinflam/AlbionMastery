package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 子弹穿透数按特化增加。
 *
 * <p>补的是什么：TACZ 的子弹在构造时从配件缓存读 {@code PIERCE}（能打穿几个目标，1 = 不穿透），
 * 之后命中一个减一个。这里在「带射手的那个构造器」末尾再加上特化给的额外穿透数。
 * 爆炸弹不穿透（TACZ 自己也是这么处理的），不加。
 *
 * <p>子弹只在服务端生成，穿透数随生成包发给客户端，客户端不用另算。
 */
@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class GunPierceMixin {

    @Shadow
    private int pierce;

    @Shadow
    private boolean explosion;

    /**
     * 构造完成后追加穿透数。
     */
    @Inject(
            method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;"
                    + "Lnet/minecraft/resources/ResourceLocation;Z"
                    + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;"
                    + "Lcom/tacz/guns/resource/pojo/data/gun/BulletData;)V",
            at = @At("TAIL"),
            require = 0
    )
    private void albionmastery$pierce(EntityType<? extends Projectile> type, Level level, LivingEntity shooter,
                                      ItemStack gunItem, ResourceLocation ammoId, ResourceLocation gunId,
                                      ResourceLocation gunDisplayId, boolean isTracerAmmo,
                                      GunData gunData, BulletData bulletData, CallbackInfo ci) {
        if (this.explosion || level.isClientSide()) {
            return;
        }
        int extra = (int) Math.round(SpecializationEffects.forStack(shooter, gunItem).pierce());
        if (extra > 0) {
            this.pierce += extra;
        }
    }
}
