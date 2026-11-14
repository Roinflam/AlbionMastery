package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.InaccuracyType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 散布、拉栓速度、免耗弹、射击推力四项特化效果，都挂在 TACZ 的开火 / 拉栓数据入口 {@code ModernKineticGunScriptAPI} 上。
 *
 * <p>和 {@code GunAmmoAndReloadMixin}、{@code GunBulletSpeedMixin} 同一个目标类，分开写只是为了按效果归类，
 * 注入点互不重叠。全部 {@code require = 0}：TACZ 改了实现就是这一项不生效，不影响启动；
 * 同时写了 {@code expect}，开着注入计数检查时命中数不够会报出来，不会悄悄失效。
 */
@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class GunHandlingMixin {

    @Shadow
    public abstract LivingEntity getShooter();

    @Shadow
    public abstract ItemStack getItemStack();

    @Shadow
    public abstract boolean useInventoryAmmo();

    /**
     * 散布：{@code shootOnce} 里先按射手姿态（站、走、蹲、趴、开镜）从配件缓存取散布，
     * 乘过热系数、过一遍脚本修正，最后 {@code Math.max(0, 散布)}。在这个 max 的结果上乘特化倍率。
     *
     * <p>腰射和开镜分两个倍率：TACZ 只有瞄准进度正好满 1 才按「开镜」那档算，
     * 所以开镜途中开的枪吃的是腰射倍率，和游戏里实际用的那档一致。
     * 姿态在这里重新判一次（纯函数，只看射手当前的姿势和速度），不去抓原方法的局部变量。
     *
     * @param inaccuracy 原始散布
     * @return 缩放后的散布
     */
    @ModifyExpressionValue(
            method = "shootOnce(Z)V",
            slice = @Slice(from = @At(value = "FIELD",
                    target = "Lcom/tacz/guns/api/GunProperties;INACCURACY:Lcom/tacz/guns/api/GunProperty;",
                    opcode = Opcodes.GETSTATIC, ordinal = 0)),
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0),
            require = 0, expect = 1
    )
    private float albionmastery$spread(float inaccuracy) {
        LivingEntity shooter = getShooter();
        SpecializationValues values = SpecializationEffects.forStack(shooter, getItemStack());
        if (values.hipSpread() == 1.0D && values.aimSpread() == 1.0D) {
            return inaccuracy;
        }
        double factor = InaccuracyType.getInaccuracyType(shooter) == InaccuracyType.AIM
                ? values.aimSpread() : values.hipSpread();
        return (float) (inaccuracy * factor);
    }

    /**
     * 拉栓速度：和换弹一样，{@code getBoltTime()} 返回的是「这次拉栓已经过去了多少毫秒」，
     * 拉栓状态机拿它和枪械数据里的拉栓时长比。想让拉栓快，就让已过去的时间显得更长——除以倍率。
     *
     * @param original 已经过去的毫秒数；0 表示没在拉栓
     * @return 缩放后的毫秒数
     */
    @ModifyReturnValue(method = "getBoltTime()J", at = @At("RETURN"), require = 0, expect = 2)
    private long albionmastery$boltTime(long original) {
        if (original <= 0L) {
            return original;
        }
        double factor = SpecializationEffects.forStack(getShooter(), getItemStack()).boltTime();
        if (factor == 1.0D || factor <= 0.0D) {
            return original;
        }
        return Math.max(1L, Math.round(original / factor));
    }

    /**
     * 射击推力：TACZ 1.2.0 开火时按枪械数据把射手往后推一把（{@code applyRecoilPush}，只在服务端，
     * 推完把速度发给客户端）。力度乘后坐力特化的倍率，和镜头后坐用同一个数，
     * 选了「后坐力降低」的玩家被推开的距离也跟着变小。
     *
     * @param strength 按姿态算好的推力
     * @return 缩放后的推力
     */
    @ModifyExpressionValue(
            method = "applyRecoilPush(Lcom/tacz/guns/resource/modifier/AttachmentCacheProperty;IFF)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/resource/pojo/data/gun/GunRecoilPushData;getFinalStrength(ZZZI)D"),
            require = 0, expect = 1
    )
    private double albionmastery$recoilPush(double strength) {
        double factor = SpecializationEffects.forStack(getShooter(), getItemStack()).recoil();
        if (factor == 1.0D || factor < 0.0D) {
            return strength;
        }
        return strength * factor;
    }

    /**
     * 免耗弹：每发有一定概率不扣弹匣。
     *
     * <p>只在「这一发本来就要从弹匣里扣」的情况下才省：
     * <ul>
     *     <li>背包直读的枪没有弹匣，扣的是背包里的弹药物品，不省；</li>
     *     <li>栓动枪扣的是膛内那一发，省了等于不用拉栓，手感会很怪，不省；</li>
     *     <li>弹匣已经空了、只剩膛内一发的闭膛枪，扣的是膛内那发，不省。</li>
     * </ul>
     * 满足条件时直接返回「扣成功了」而不真去扣，开火流程照常走。只在服务端掷骰，客户端的弹药数随物品 NBT 同步过来。
     *
     * @param cir 回调
     */
    @Inject(method = "reduceAmmoOnce()Z", at = @At("HEAD"), cancellable = true, require = 0, expect = 1)
    private void albionmastery$ammoSave(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity shooter = getShooter();
        if (shooter == null || shooter.level().isClientSide()) {
            return;
        }
        ItemStack stack = getItemStack();
        double chance = SpecializationEffects.forStack(shooter, stack).ammoSave();
        if (chance <= 0.0D || useInventoryAmmo()) {
            return;
        }
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null || gun.getCurrentAmmoCount(stack) < 1) {
            return;
        }
        Bolt bolt = TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
                .map(index -> index.getGunData().getBolt())
                .orElse(null);
        if (bolt == null || bolt == Bolt.MANUAL_ACTION) {
            return;
        }
        if (shooter.getRandom().nextDouble() < chance) {
            cir.setReturnValue(true);
        }
    }
}
