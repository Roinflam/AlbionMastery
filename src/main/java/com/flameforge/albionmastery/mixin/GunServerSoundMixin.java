package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.tacz.guns.sound.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 别人听到的枪声也按开枪那个人的特化缩放（音量和传播距离）。
 *
 * <p>{@code GunSoundMixin} 只改开枪的人自己听到的第一人称枪声；旁边的玩家听到的第三人称枪声
 * 是服务端用这个方法广播的，音量和传播距离都在服务端定。不改这里的话，消音器「安静」的好处、
 * 制退器「很吵」的代价，对别人都不成立。
 *
 * <p>只动射击和消音射击两种第三人称音效，换弹、拉栓这些不管。改法是带着缩放后的参数重新调一次、
 * 把原来那次取消掉，用一个标记防止重入。
 */
@Mixin(value = SoundManager.class, remap = false)
public abstract class GunServerSoundMixin {

    /** 正在用缩放后的参数重新广播，这一次不再处理。只在服务端主线程上用。 */
    @Unique
    private static boolean albionmastery$resending;

    /**
     * 广播枪声前按开枪者的特化缩放。
     */
    @Inject(method = "sendSoundToNearby(Lnet/minecraft/world/entity/LivingEntity;I"
            + "Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;"
            + "Ljava/lang/String;FF)V",
            at = @At("HEAD"), cancellable = true, require = 0, expect = 1)
    private static void albionmastery$scaleGunVolume(LivingEntity source, int distance, ResourceLocation gunId,
                                                     ResourceLocation gunDisplayId, String soundName, float volume,
                                                     float pitch, CallbackInfo callback) {
        if (albionmastery$resending || !(source instanceof ServerPlayer player)) {
            return;
        }
        if (!SoundManager.SHOOT_3P_SOUND.equals(soundName) && !SoundManager.SILENCE_3P_SOUND.equals(soundName)) {
            return;
        }
        double factor = SpecializationEffects.forStack(player, player.getMainHandItem()).gunVolume();
        if (Math.abs(factor - 1.0D) < 1.0E-6D) {
            return;
        }
        // 音量压到几成，距离也缩到几成；变吵的时候传得更远——音量本身到 1.0 就被声音引擎夹住了，
        // 只放大音量的话「很吵」的代价在近处几乎体现不出来，传播距离才是真的让更多人听见
        int scaledDistance = Math.max(1, (int) Math.round(distance * factor));
        albionmastery$resending = true;
        try {
            SoundManager.sendSoundToNearby(source, scaledDistance, gunId, gunDisplayId, soundName,
                    (float) (volume * factor), pitch);
        } finally {
            albionmastery$resending = false;
        }
        callback.cancel();
    }
}
