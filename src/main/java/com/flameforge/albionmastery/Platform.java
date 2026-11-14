package com.flameforge.albionmastery;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * 加载器相关的几个小查询：模组是否加载、是否客户端、游戏目录、游戏事件总线。
 *
 * <p>1.20.1 分支有一份同名同签名的类（里面是 Forge 的写法），业务代码只调这里，
 * 两个分支之间同步改动时这些调用处不用改。
 */
public final class Platform {

    private Platform() {
    }

    /**
     * 某个模组是否已加载。模组列表还没建好时（Mixin 插件、模组构造之前）退回加载前的清单。
     *
     * @param modId 模组 ID
     * @return 已加载返回 true
     */
    public static boolean isModLoaded(String modId) {
        ModList list = ModList.get();
        if (list != null) {
            return list.isLoaded(modId);
        }
        LoadingModList loading = LoadingModList.get();
        return loading != null && loading.getModFileById(modId) != null;
    }

    /**
     * 当前物理端是否客户端（单人游戏的内置服务端也算客户端）。
     *
     * @return 客户端返回 true，专用服返回 false
     */
    public static boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    /**
     * 是否开发环境（从源码起的游戏，不是打包后的 jar）。
     *
     * @return 开发环境返回 true
     */
    public static boolean isDevelopment() {
        return !FMLEnvironment.production;
    }

    /**
     * 游戏目录（客户端是 {@code .minecraft}，专用服是服务端根目录）。
     *
     * @return 游戏目录
     */
    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    /**
     * 游戏事件总线（玩家、实体、服务端事件都在这上面发）。
     *
     * @return 游戏事件总线
     */
    public static IEventBus eventBus() {
        return NeoForge.EVENT_BUS;
    }
}
