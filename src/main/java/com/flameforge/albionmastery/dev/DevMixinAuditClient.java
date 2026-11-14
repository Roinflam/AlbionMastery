package com.flameforge.albionmastery.dev;

import com.flameforge.albionmastery.Platform;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * {@link DevMixinAudit} 的客户端那一半：第一次打开主界面时跑一次审计。
 *
 * <p>单独一个类，专用服上不会加载到这里引用的客户端类。
 */
final class DevMixinAuditClient {

    private DevMixinAuditClient() {
    }

    static void register() {
        Platform.eventBus().addListener(DevMixinAuditClient::onScreenOpened);
    }

    private static void onScreenOpened(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen) {
            DevMixinAudit.runOnce("client title screen");
        }
    }
}
