package com.flameforge.albionmastery.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读物品栏上方那行动作栏提示（服务端 {@code displayClientMessage(..., true)} 发来的）。
 *
 * <p>专精界面是整屏不透明的，动作栏画在界面底下，切换特化失败（冷却、同组、未解锁）的提示玩家根本看不见，
 * 只听到一声拒绝音。特化页面自己把这行字再画一遍。原版字段，名字会被混淆，不能加 {@code remap = false}。
 */
@Mixin(Gui.class)
public interface GuiOverlayAccessor {

    /**
     * @return 当前动作栏文字，可能为 null
     */
    @Accessor("overlayMessageString")
    Component albionmastery$getOverlayMessage();

    /**
     * @return 动作栏文字还剩多少 tick 消失
     */
    @Accessor("overlayMessageTime")
    int albionmastery$getOverlayMessageTime();
}
