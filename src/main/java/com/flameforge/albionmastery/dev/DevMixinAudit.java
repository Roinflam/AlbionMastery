package com.flameforge.albionmastery.dev;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.Platform;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * 开发环境专用：强制加载全部 Mixin 目标类，核对注入器有没有打上。
 *
 * <p>{@code -Dmixin.debug.countInjections=true} 只在目标类被加载、Mixin 应用的那一刻检查命中数，
 * 测试里没走到的类（按需加载的界面、HUD、提示框，专用服上不加载的类）根本不查。这里调
 * {@link MixinEnvironment#audit()} 把还没加载的目标逐个加载一遍：每个类在日志里留一行
 * {@code Force-loading class ...}，加载失败记 {@code Could not force-load ...}，命中数不够的照常报
 * {@code Injection validation failed}。
 *
 * <p>两种触发方式（只在开发环境登记，打包后的 jar 里没有入口）：
 * <ul>
 *     <li>指令 {@code /amdev audit}（权限 2），进世界之后随时跑；</li>
 *     <li>启动参数 {@code -Dalbionmastery.dev.audit=true}：专用服起服完成时自动跑一次；客户端在第一次
 *     打开主界面时跑一次（不用进世界），进单人世界时内置服务端不再重复。</li>
 * </ul>
 * 结果只看日志：开始、结束各打一行 {@code [mixin-audit]}，中间是 Mixin 自己的输出。
 */
public final class DevMixinAudit {

    /** 启动时自动跑一次的系统属性。 */
    public static final String AUTO_PROPERTY = "albionmastery.dev.audit";

    private static boolean ranOnce;

    private DevMixinAudit() {
    }

    /**
     * 登记指令和自动触发。只在开发环境调用。
     */
    public static void register() {
        Platform.eventBus().addListener(DevMixinAudit::onRegisterCommands);
        if (Boolean.getBoolean(AUTO_PROPERTY)) {
            Platform.eventBus().addListener(DevMixinAudit::onServerStarted);
            if (Platform.isClient()) {
                DevMixinAuditClient.register();
            }
        }
    }

    /**
     * 跑一次审计。
     *
     * @param trigger 触发来源，写进日志
     * @return 本次耗时（毫秒）
     */
    public static synchronized long run(String trigger) {
        ranOnce = true;
        AlbionMastery.LOGGER.info("[mixin-audit] begin ({})", trigger);
        long start = System.nanoTime();
        MixinEnvironment.getCurrentEnvironment().audit();
        long ms = (System.nanoTime() - start) / 1_000_000L;
        AlbionMastery.LOGGER.info("[mixin-audit] end ({}, {} ms)", trigger, ms);
        return ms;
    }

    /**
     * 自动触发：已经跑过就不再跑。
     *
     * @param trigger 触发来源
     */
    static synchronized void runOnce(String trigger) {
        if (!ranOnce) {
            run(trigger);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("amdev")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("audit").executes(context -> {
                    long ms = run("command");
                    context.getSource().sendSuccess(
                            () -> Component.literal("[mixin-audit] done in " + ms + " ms, see the log"), false);
                    return 1;
                })));
    }

    private static void onServerStarted(ServerStartedEvent event) {
        runOnce(event.getServer().isDedicatedServer() ? "dedicated server started" : "integrated server started");
    }
}
