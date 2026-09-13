package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.client.screen.MasteryScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端事件处理。
 */
public final class ClientEventHandler {

    private ClientEventHandler() {
    }

    /**
     * FORGE 总线上的客户端事件。
     */
    @Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID, value = Dist.CLIENT)
    public static final class Forge {

        private Forge() {
        }

        /**
         * 每客户端 tick 检查一次开界面按键。
         *
         * @param event tick 事件
         */
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.screen != null) {
                return;
            }
            // 用 while 消费掉这一帧内堆积的所有点击，避免松手后又弹一次界面
            boolean open = false;
            while (KeyBindings.OPEN_MASTERY.consumeClick()) {
                open = true;
            }
            if (open) {
                minecraft.setScreen(new MasteryScreen());
            }
        }

        /**
         * 断开连接时清空本地缓存，防止下次进服看到上一局的数据。
         *
         * @param event 登出事件
         */
        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientMasteryCache.reset();
        }

        /**
         * 在 HUD 之上绘制升级提示。
         *
         * <p>挂在物品栏这一层的 Post 上，是为了每帧只画一次——
         * {@code RenderGuiOverlayEvent} 会为每个 HUD 元素各触发一次。
         *
         * @param event HUD 绘制事件
         */
        @SubscribeEvent
        public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
            if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) {
                return;
            }
            MasteryToastManager.render(event.getGuiGraphics());
        }
    }

    /**
     * MOD 总线上的客户端事件。
     */
    @Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {

        private ModBus() {
        }

        /**
         * 注册按键绑定。
         *
         * @param event 注册事件
         */
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(KeyBindings.OPEN_MASTERY);
        }
    }
}
