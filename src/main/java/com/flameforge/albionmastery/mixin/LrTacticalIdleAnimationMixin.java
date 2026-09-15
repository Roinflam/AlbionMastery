package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.AlbionMastery;
import me.xjqsh.lrtactical.compat.player_animator.IdleAnimationHandler;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复 lrtactical 在退出世界阶段仍执行第三人称待机动画 Tick 导致客户端崩溃的问题。
 *
 * <p>问题发生在 Forge 已经卸载 SERVER 配置、但客户端这一帧仍保留 {@code ClientLevel} 的短暂窗口。
 * lrtactical 的 {@code IdleAnimationHandler#onClientTick} 会直接调用
 * {@link AbstractClientPlayer#onClimbable()} 来判断是否正在攀爬，后者进入 Forge 的梯子判定并读取已经卸载的配置，
 * 开发环境会直接抛出 {@link IllegalStateException}。
 *
 * <p>这里只包住 lrtactical 自己的这一次 {@code onClimbable()} 调用：正常游戏时原样返回；
 * 只有异常文本明确表示「配置尚未加载 / 已卸载」时才在退出世界的残余帧里按 {@code false} 处理。
 * 其它 {@link IllegalStateException} 一律继续抛出，避免把真正的问题悄悄吞掉。
 *
 * <p>目标是第三方模组类，因此 Mixin 本体不参与重映射；注入点调用的是 Minecraft 方法，
 * {@code @At(remap = true)} 单独开启重映射，保证开发环境与生产环境都能定位到未混淆的
 * {@code onClimbable}。
 */
@Mixin(value = IdleAnimationHandler.class, remap = false)
public abstract class LrTacticalIdleAnimationMixin {

    /** Forge 在配置不可读取时抛出的固定提示片段。 */
    private static final String CONFIG_NOT_LOADED_MESSAGE =
            "Cannot get config value before config is loaded";

    /** 同一进程只记录一次兼容兜底，避免退出阶段连续几个 RenderTick 刷日志。 */
    private static boolean loggedConfigUnload;

    /**
     * 替换 lrtactical 对 {@link AbstractClientPlayer#onClimbable()} 的直接调用。
     *
     * @param player 正在计算移动状态的客户端玩家
     * @return 正常情况下返回原方法结果；配置已卸载时返回 false
     * @throws IllegalStateException 非配置卸载导致的状态异常继续抛出
     */
    @Redirect(
            method = "onClientTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/AbstractClientPlayer;onClimbable()Z",
                    remap = true
            ),
            require = 0,
            remap = false
    )
    private static boolean albionmastery$safeOnClimbable(AbstractClientPlayer player) {
        try {
            return player.onClimbable();
        } catch (IllegalStateException exception) {
            String message = exception.getMessage();
            if (message == null || !message.contains(CONFIG_NOT_LOADED_MESSAGE)) {
                throw exception;
            }

            if (!loggedConfigUnload) {
                loggedConfigUnload = true;
                AlbionMastery.LOGGER.debug(
                        "退出世界时 lrtactical 仍执行待机动画 Tick，已跳过一次失效的梯子配置读取");
            }
            return false;
        }
    }
}
