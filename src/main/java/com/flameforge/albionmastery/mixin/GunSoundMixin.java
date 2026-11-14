package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.pojo.display.SoundData;
import com.tacz.guns.client.sound.GunSoundInstance;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.network.message.ServerMessageSound;
import com.tacz.guns.sound.SoundManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 枪声按特化改：消音器一类压音量和传播距离，换弹、拉栓、掏枪音效跟着动作速度变调。
 *
 * <p>补的是什么：TACZ 客户端的枪械音效都走 {@code SoundPlayManager.playClientSound(实体, 音效数据, 音量, 音调, 距离, ...)}。
 * 射击音效（{@code playShootSound} / {@code playSilenceSound}）改第 3、5 个参数：音量和传播距离；
 * 换弹 / 拉栓 / 掏枪音效改第 4 个参数音调。Minecraft 的音调就是播放速度，
 * 换弹快 20% 音调就调到 1.25 倍，音效和动画、逻辑三者才对得上。
 *
 * <p>枪包可以在显示文件里给某个音效写死 {@code volume} / {@code pitch}。写了的话，
 * 9 参的 {@code playClientSound} 在 {@code SoundData.resolveVolume} / {@code resolvePitch} 里用枪包的值，
 * 传进来的音量、音调整个丢掉，外层改的参数就白改了。所以再加一层：外层改参数时把倍率记下来，
 * {@code resolve*} 返回时只在枪包写了值的情况下补乘一次；枪包没写时用的就是外层改过的值，不再乘。
 * 记下的倍率用一次就复位，{@code playClientSound} 返回时再无条件复位一次，不会漏到下一个音效上。
 *
 * <p>别的玩家的第三人称枪声由服务端发包、客户端在 {@code playMessageSound} 里播，
 * 服务端已经按开枪者的特化改过音量和距离；这里只在枪包写死音量时把同一个倍率补乘回去。
 *
 * <p>纯客户端类，走客户端 Mixin 列表；注不上只是音效不变。
 */
@Mixin(SoundPlayManager.class)
public abstract class GunSoundMixin {

    /** 9 参 {@code playClientSound}（{@code SoundData} 版），所有带音效数据的重载最后都转进这里。 */
    @Unique
    private static final String ALBIONMASTERY$PLAY_DATA_FULL = "Lcom/tacz/guns/client/sound/SoundPlayManager;playClientSound"
            + "(Lnet/minecraft/world/entity/Entity;Lcom/tacz/guns/client/resource/pojo/display/SoundData;FFIZIZZ)"
            + "Lcom/tacz/guns/client/sound/GunSoundInstance;";

    /** 5 参 {@code playClientSound}（{@code SoundData} 版），换弹、拉栓、掏枪用它。 */
    @Unique
    private static final String ALBIONMASTERY$PLAY_DATA_SHORT = "Lcom/tacz/guns/client/sound/SoundPlayManager;playClientSound"
            + "(Lnet/minecraft/world/entity/Entity;Lcom/tacz/guns/client/resource/pojo/display/SoundData;FFI)"
            + "Lcom/tacz/guns/client/sound/GunSoundInstance;";

    /** 9 参 {@code playClientSound} 本身的描述符，给挂在它内部的注入器用。 */
    @Unique
    private static final String ALBIONMASTERY$PLAY_DATA_FULL_METHOD = "playClientSound"
            + "(Lnet/minecraft/world/entity/Entity;Lcom/tacz/guns/client/resource/pojo/display/SoundData;FFIZIZZ)"
            + "Lcom/tacz/guns/client/sound/GunSoundInstance;";

    /** TACZ 服务端发第三人称枪声时写死的音量。 */
    @Unique
    private static final float ALBIONMASTERY$SERVER_SHOOT_VOLUME = 0.8F;

    /** 下一次 {@code resolveVolume} 要补乘的倍率。只在客户端线程读写，用一次就复位。 */
    @Unique
    private static float albionmastery$pendingVolume = 1.0F;

    /** 下一次 {@code resolvePitch} 要补乘的倍率。只在客户端线程读写，用一次就复位。 */
    @Unique
    private static float albionmastery$pendingPitch = 1.0F;

    /**
     * 射击音量。
     *
     * @param volume 原始音量
     * @return 乘上特化倍率之后的音量
     */
    @ModifyArg(
            method = {
                    "playShootSound(Lnet/minecraft/world/entity/LivingEntity;Lcom/tacz/guns/client/resource/GunDisplayInstance;"
                            + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;)V",
                    "playSilenceSound(Lnet/minecraft/world/entity/LivingEntity;Lcom/tacz/guns/client/resource/GunDisplayInstance;"
                            + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;)V"
            },
            at = @At(value = "INVOKE", target = ALBIONMASTERY$PLAY_DATA_FULL),
            index = 2,
            require = 0,
            expect = 2
    )
    private static float albionmastery$shootVolume(float volume) {
        float factor = (float) albionmastery$values().gunVolume();
        albionmastery$pendingVolume = factor;
        return volume * factor;
    }

    /**
     * 射击音效传播距离：音量压到几成，距离也缩到几成，远处的人才真的听不见。
     *
     * <p>距离不经过枪包的写死值，这一处单独就够。
     *
     * @param distance 原始距离
     * @return 缩放后的距离
     */
    @ModifyArg(
            method = {
                    "playShootSound(Lnet/minecraft/world/entity/LivingEntity;Lcom/tacz/guns/client/resource/GunDisplayInstance;"
                            + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;)V",
                    "playSilenceSound(Lnet/minecraft/world/entity/LivingEntity;Lcom/tacz/guns/client/resource/GunDisplayInstance;"
                            + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;)V"
            },
            at = @At(value = "INVOKE", target = ALBIONMASTERY$PLAY_DATA_FULL),
            index = 4,
            require = 0,
            expect = 2
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
            method = "playReloadSound(Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lcom/tacz/guns/client/resource/GunDisplayInstance;Z)V",
            at = @At(value = "INVOKE", target = ALBIONMASTERY$PLAY_DATA_SHORT),
            index = 3,
            require = 0,
            expect = 2
    )
    private static float albionmastery$reloadPitch(float pitch) {
        return albionmastery$scalePitch(pitch, albionmastery$values().reloadTime());
    }

    /**
     * 拉栓音效音调：和换弹同一个道理，拉栓快了音效也要跟着快，不然枪早就拉完了声音还在响。
     *
     * @param pitch 原始音调
     * @return 缩放后的音调
     */
    @ModifyArg(
            method = "playBoltSound(Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lcom/tacz/guns/client/resource/GunDisplayInstance;)V",
            at = @At(value = "INVOKE", target = ALBIONMASTERY$PLAY_DATA_SHORT),
            index = 3,
            require = 0,
            expect = 1
    )
    private static float albionmastery$boltPitch(float pitch) {
        return albionmastery$scalePitch(pitch, albionmastery$values().boltTime());
    }

    /**
     * 掏枪音效音调。
     *
     * @param pitch 原始音调
     * @return 缩放后的音调
     */
    @ModifyArg(
            method = "playDrawSound(Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lcom/tacz/guns/client/resource/GunDisplayInstance;)V",
            at = @At(value = "INVOKE", target = ALBIONMASTERY$PLAY_DATA_SHORT),
            index = 3,
            require = 0,
            expect = 1
    )
    private static float albionmastery$drawPitch(float pitch) {
        return albionmastery$scalePitch(pitch, albionmastery$values().drawTime());
    }

    /**
     * 枪包写死了音量时补乘外层记下的倍率。
     *
     * @param data     音效数据
     * @param random   随机源
     * @param fallback 传进来的音量（外层已经乘过）
     * @param original 原调用
     * @return 最终音量
     */
    @WrapOperation(
            method = ALBIONMASTERY$PLAY_DATA_FULL_METHOD,
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/client/resource/pojo/display/SoundData;resolveVolume"
                            + "(Lnet/minecraft/util/RandomSource;F)F"),
            require = 0,
            expect = 1
    )
    private static float albionmastery$packVolume(SoundData data, RandomSource random, float fallback,
                                                  Operation<Float> original) {
        float factor = albionmastery$pendingVolume;
        albionmastery$pendingVolume = 1.0F;
        float resolved = original.call(data, random, fallback);
        // 枪包没写音量时用的就是传进来的值，外层已经乘过，不能再乘
        return data.hasVolume() && factor != 1.0F ? resolved * factor : resolved;
    }

    /**
     * 枪包写死了音调时补乘外层记下的倍率。
     *
     * @param data     音效数据
     * @param random   随机源
     * @param fallback 传进来的音调（外层已经改过）
     * @param original 原调用
     * @return 最终音调
     */
    @WrapOperation(
            method = ALBIONMASTERY$PLAY_DATA_FULL_METHOD,
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/client/resource/pojo/display/SoundData;resolvePitch"
                            + "(Lnet/minecraft/util/RandomSource;F)F"),
            require = 0,
            expect = 1
    )
    private static float albionmastery$packPitch(SoundData data, RandomSource random, float fallback,
                                                 Operation<Float> original) {
        float factor = albionmastery$pendingPitch;
        albionmastery$pendingPitch = 1.0F;
        float resolved = original.call(data, random, fallback);
        // 同上：枪包没写音调时传进来的值已经改过
        return data.hasPitch() && factor != 1.0F ? resolved * factor : resolved;
    }

    /**
     * 每次播完都把记下的倍率复位。
     *
     * <p>音效数据为 null 时（枪包没给这个动作配音效）方法开头就返回，两个 {@code resolve*} 都走不到，
     * 外层却已经记下了倍率；不在这里复位，它会被下一个枪包写了音量 / 音调的任意 TACZ 音效吃掉。
     *
     * @param cir 回调
     */
    @Inject(method = ALBIONMASTERY$PLAY_DATA_FULL_METHOD, at = @At("RETURN"), require = 0, expect = 2)
    private static void albionmastery$clearPending(CallbackInfoReturnable<GunSoundInstance> cir) {
        albionmastery$pendingVolume = 1.0F;
        albionmastery$pendingPitch = 1.0F;
    }

    /**
     * 别的玩家的第三人称枪声：服务端发来的音量是写死的 0.8 乘开枪者的音量倍率，除回来就是倍率。
     * 枪包没写音量时这个音量照常生效，写了的话由 {@link #albionmastery$packVolume} 补乘。
     *
     * @param message 服务端发来的音效包
     * @param entity  发出声音的实体
     * @param display 枪的显示数据
     * @param ci      回调
     */
    @Inject(
            method = "lambda$playMessageSound$2(Lcom/tacz/guns/network/message/ServerMessageSound;"
                    + "Lnet/minecraft/world/entity/LivingEntity;Lcom/tacz/guns/client/resource/GunDisplayInstance;)V",
            at = @At(value = "INVOKE", target = ALBIONMASTERY$PLAY_DATA_FULL),
            require = 0,
            expect = 2
    )
    private static void albionmastery$messageVolume(ServerMessageSound message, LivingEntity entity,
                                                    GunDisplayInstance display, CallbackInfo ci) {
        String name = message.getSoundName();
        if (SoundManager.SHOOT_3P_SOUND.equals(name) || SoundManager.SILENCE_3P_SOUND.equals(name)) {
            float factor = message.getVolume() / ALBIONMASTERY$SERVER_SHOOT_VOLUME;
            albionmastery$pendingVolume = Float.isFinite(factor) && factor >= 0.0F ? factor : 1.0F;
        }
    }

    /**
     * 按耗时倍率改音调，并把改动的比值记下来，留给枪包写死音调时补乘。
     *
     * @param pitch      原始音调
     * @param timeFactor 耗时倍率
     * @return 缩放后的音调
     */
    @Unique
    private static float albionmastery$scalePitch(float pitch, double timeFactor) {
        float scaled = albionmastery$pitchFor(pitch, timeFactor);
        albionmastery$pendingPitch = pitch != 0.0F ? scaled / pitch : 1.0F;
        return scaled;
    }

    /**
     * 耗时倍率换成音调：0.8 倍耗时 = 1.25 倍速播放。
     *
     * <p>原版的音调最高只认到 2.0，再快的特化音效就跟不动了，那是引擎的上限。
     *
     * @param pitch      原始音调
     * @param timeFactor 耗时倍率
     * @return 缩放后的音调
     */
    @Unique
    private static float albionmastery$pitchFor(float pitch, double timeFactor) {
        if (timeFactor <= 0.0D || timeFactor == 1.0D) {
            return pitch;
        }
        return (float) (pitch / timeFactor);
    }

    /**
     * 这几个处理器拿不到当前实体的参数（ModifyArg 只给被改的那一个参数），
     * 所以按本地玩家主手的枪查：音效本来就是给本机听的。
     *
     * @return 当前生效的特化数值
     */
    @Unique
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
