package com.flameforge.albionmastery.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端按键绑定。
 */
public final class KeyBindings {

    /** 按键分类，显示在控制设置里。 */
    public static final String CATEGORY = "key.categories.albionmastery";

    /** 打开专精界面，默认 B 键。 */
    public static final KeyMapping OPEN_MASTERY = new KeyMapping(
            "key.albionmastery.open_mastery",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY
    );

    private KeyBindings() {
    }
}
