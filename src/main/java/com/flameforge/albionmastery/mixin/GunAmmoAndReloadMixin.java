package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 把枪械特化的弹匣容量与换弹速度接进 TACZ 的换弹逻辑。
 *
 * <p>补的是什么：{@code ModernKineticGunScriptAPI} 是 TACZ 换弹状态机的数据入口，
 * 弹匣上限、还需要装多少发、往弹匣里放多少发、换弹进行到哪一步了，全部经过这个类。
 * 更妙的是它自带 {@code shooter} 字段——弹匣容量那个工具方法
 * （{@code AttachmentDataUtils.getAmmoCountWithAttachment}）本身<b>拿不到玩家</b>，
 * 而特化是按玩家算的，所以不能直接去改那个工具方法，必须挑有玩家上下文的调用点下手。
 *
 * <p>为什么用 MixinExtras 的 {@code @ModifyExpressionValue} / {@code @ModifyReturnValue}
 * 而不是 {@code @Redirect}：这两个注解是可叠加的，多个模组挂在同一处会串成一条链，
 * 各改各的。同一台服务端上如果还有别的模组改 TACZ 的弹匣（比如自家的 KuvaLich），
 * 用 {@code @Redirect} 就会抢同一个调用点直接崩，用这两个则是乘上去，谁也不妨碍谁。
 *
 * <p>目标是第三方模组的类，名称不参与 SRG 映射，因此整个 Mixin 使用 {@code remap = false}。
 * TACZ 更新后方法签名可能变，所以 {@code require = 0}：注入不上就是特化不生效，
 * 不至于让整个模组起不来。
 */
@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class GunAmmoAndReloadMixin {

    @Shadow
    public abstract LivingEntity getShooter();

    @Shadow
    public abstract ItemStack getItemStack();

    /**
     * 弹匣容量：拦下这个类里每一次取「配件加成后的弹匣容量」的结果。
     *
     * <p>三个方法都要拦，少一个就会前后对不上——比如只改上限不改「还需装几发」，
     * 换弹会在装满原始容量之后就停下来。{@code removeAmmoFromMagazine} 不读容量，不用拦。
     *
     * @param original 原始容量
     * @return 应用特化之后的容量，至少 1
     */
    @ModifyExpressionValue(
            method = {"getNeededAmmoAmount()I", "getMaxAmmoCount()I", "putAmmoInMagazine(I)I"},
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/util/AttachmentDataUtils;getAmmoCountWithAttachment"
                            + "(Lnet/minecraft/world/item/ItemStack;"
                            + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;)I"
            ),
            require = 0, expect = 3
    )
    private int albionmastery$applyMagazineSpec(int original) {
        SpecializationValues values = SpecializationEffects.forStack(getShooter(), getItemStack());
        if (values.magazine() == 1.0D) {
            return original;
        }
        // 容量必须是正整数：倍率再低也得能装 1 发，否则换弹逻辑会陷在「永远装不满」
        return Math.max(1, (int) Math.round(original * values.magazine()));
    }

    /**
     * 换弹速度：让换弹状态机眼里的时间走得更快（或更慢）。
     *
     * <p><b>{@code getReloadTime()} 返回的不是「换弹要花多久」，而是「这次换弹已经过去了多少毫秒」</b>
     * （{@code System.currentTimeMillis() - reloadTimestamp}）。TACZ 每 tick 拿这个已过去的时间去和
     * 枪械数据里写死的 feed / cooldown 时长比大小，超过了就进入下一阶段。
     * 所以想让换弹快 20%（倍率 0.8），要做的是让「已过去的时间」显得更长——
     * 也就是<b>除以</b>倍率而不是乘。之前这里写成了乘，效果正好反了：倍率越小换弹越慢。
     *
     * <p>这里改的只是逻辑上的换弹进度（什么时候真正把子弹装进弹匣）。
     * 客户端换弹动画是按真实时间播放的，本身不受这个数影响，由 {@code ObjectAnimationRunnerMixin}
     * 用同一个倍率另外变速，两边才能对上。
     *
     * @param original 已经过去的毫秒数；0 表示当前没在换弹
     * @return 按特化缩放之后的毫秒数
     */
    @ModifyReturnValue(method = "getReloadTime()J", at = @At("RETURN"), require = 0, expect = 2)
    private long albionmastery$applyReloadSpec(long original) {
        // 0 是「没在换弹」的信号，不能碰
        if (original <= 0L) {
            return original;
        }
        SpecializationValues values = SpecializationEffects.forStack(getShooter(), getItemStack());
        double factor = values.reloadTime();
        if (factor == 1.0D) {
            return original;
        }
        // factor 在网络包读入时已被夹到 [0.05, 20]，不会出现除以 0
        return Math.max(1L, Math.round(original / factor));
    }
}
