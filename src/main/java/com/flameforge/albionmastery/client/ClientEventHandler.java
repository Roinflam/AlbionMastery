package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.client.screen.MasteryScreen;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
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
         * 标签重载了（进存档、数据包重载）：武器清单里的判定读标签，标记重建。
         *
         * @param event 标签更新事件
         */
        @SubscribeEvent
        public static void onTagsUpdated(net.minecraftforge.event.TagsUpdatedEvent event) {
            com.flameforge.albionmastery.client.weapon.ClientWeaponRegistry.invalidate();
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
            // 施法倍速的补挂要每 tick 走，开着界面时别人也在施法，不能跟着下面的提前返回一起跳过
            CastSpeedSync.tick();
            // 换弹动作的实测倍速同理。这一行会碰 TACZ 的类，没装 TACZ 时不能让它执行到
            if (WeaponResolver.isTaczLoaded()) {
                ReloadAnimSync.tick();
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
            CastSpeedSync.clear();
            if (WeaponResolver.isTaczLoaded()) {
                ReloadAnimSync.clear();
            }
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
            // 开着界面时改在界面之上画（见下面）：HUD 在界面底下，罗盘的背景几乎不透明，
            // 在罗盘里点「满级」弹出来的横幅以前只能听见声音看不见
            if (Minecraft.getInstance().screen != null) {
                return;
            }
            MasteryToastManager.render(event.getGuiGraphics());
        }

        /**
         * 开着界面时，升级横幅画在界面之上。
         *
         * @param event 界面绘制事件
         */
        @SubscribeEvent
        public static void onScreenRender(ScreenEvent.Render.Post event) {
            GuiGraphics guiGraphics = event.getGuiGraphics();
            guiGraphics.pose().pushPose();
            // 比罗盘的顶栏（600）和物品提示都高
            guiGraphics.pose().translate(0.0F, 0.0F, 800.0F);
            MasteryToastManager.render(guiGraphics);
            guiGraphics.pose().popPose();
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
