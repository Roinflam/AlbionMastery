package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.client.MasteryClientBonus;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.tacz.guns.client.sound.SoundPlayManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 枪声按流派改：消音器一类压音量和传播距离，换弹音效跟着换弹速度变调。
 *
 * <p>补的是什么：TACZ 客户端所有枪械音效最后都走 {@code SoundPlayManager.playClientSound(实体, 音效, 音量, 音调, 距离)}。
 * 射击音效（{@code playShootSound} / {@code playSilenceSound}）改第 3、5 个参数：音量和传播距离；
 * 换弹音效（{@code playReloadSound}）改第 4 个参数音调——Minecraft 的音调就是播放速度，
 * 换弹快 20% 音调就调到 1.25 倍，音效和动画、逻辑三者才对得上。
 *
 * <p>只改本机能看到的实体，别人开枪按谁的枪谁的流派算：这几个方法的第一个参数就是开枪 / 换弹的实体，
 * 按它主手的枪查流派。纯客户端类，走客户端 Mixin 列表；注不上只是音效不变。
 */
@Mixin(value = SoundPlayManager.class, remap = false)
public abstract class GunSoundMixin {

    /**
     * 射击音量。
     *
     * @param volume 原始音量
     * @return 乘上流派倍率之后的音量
     */
    @ModifyArg(
            method = {"playShootSound", "playSilenceSound"},
            at = @At(value = "INVOKE", target = "Lcom/tacz/guns/client/sound/SoundPlayManager;playClientSound"
                    + "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceLocation;FFI)"
                    + "Lcom/tacz/guns/client/sound/GunSoundInstance;"),
            index = 2,
            require = 0
    )
    private static float albionmastery$shootVolume(float volume) {
        return (float) (volume * albionmastery$values().gunVolume());
    }

    /**
     * 射击音效传播距离：音量压到几成，距离也缩到几成，远处的人才真的听不见。
     *
     * @param distance 原始距离
     * @return 缩放后的距离
     */
    @ModifyArg(
            method = {"playShootSound", "playSilenceSound"},
            at = @At(value = "INVOKE", target = "Lcom/tacz/guns/client/sound/SoundPlayManager;playClientSound"
                    + "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceLocation;FFI)"
                    + "Lcom/tacz/guns/client/sound/GunSoundInstance;"),
            index = 4,
            require = 0
    )
    private static int albionmastery$shootDistance(int distance) {
        double volume = albionmastery$values().gunVolume();
        return volume >= 1.0D ? distance : Math.max(1, (int) Math.round(distance * volume));
    }

    /**
     * 换弹音效音调：换弹耗时倍率 0.8 就是 1.25 倍速播放。
     *
     * @param pitch 原始音调
     * @return 缩放后的音调
     */
    @ModifyArg(
            method = "playReloadSound",
            at = @At(value = "INVOKE", target = "Lcom/tacz/guns/client/sound/SoundPlayManager;playClientSound"
                    + "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceLocation;FFI)"
                    + "Lcom/tacz/guns/client/sound/GunSoundInstance;"),
            index = 3,
            require = 0
    )
    private static float albionmastery$reloadPitch(float pitch) {
        double reload = albionmastery$values().reloadTime();
        if (reload <= 0.0D || reload == 1.0D) {
            return pitch;
        }
        return (float) (pitch / reload);
    }

    /**
     * 这几个方法都拿不到当前实体的参数（ModifyArg 只给被改的那一个参数），
     * 所以按本地玩家主手的枪查——音效本来就是给本机听的。
     *
     * @return 当前生效的流派数值
     */
    private static SpecializationValues albionmastery$values() {
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            return SpecializationValues.NEUTRAL;
        }
        ItemStack main = player.getMainHandItem();
        LivingEntity entity = player;
        return SpecializationEffects.forStack(entity, main);
    }
}
