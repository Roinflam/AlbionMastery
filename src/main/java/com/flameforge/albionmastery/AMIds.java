package com.flameforge.albionmastery;

import net.minecraft.resources.ResourceLocation;

/**
 * {@link ResourceLocation} 的构造入口。
 *
 * <p>1.21 起 {@code new ResourceLocation(...)} 不再公开，改用工厂方法。业务代码一律从这里建 ID，
 * 1.20.1 分支有一份同名同签名的类（里面用构造器），两个分支之间 cherry-pick 时这些调用处不会冲突。
 */
public final class AMIds {

    private AMIds() {
    }

    /**
     * 本模组命名空间下的 ID。
     *
     * @param path 路径
     * @return {@code albionmastery:<path>}
     */
    public static ResourceLocation of(String path) {
        return ResourceLocation.fromNamespaceAndPath(AlbionMastery.MOD_ID, path);
    }

    /**
     * 指定命名空间的 ID。
     *
     * @param namespace 命名空间
     * @param path      路径
     * @return {@code <namespace>:<path>}
     */
    public static ResourceLocation of(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    /**
     * 解析 {@code 命名空间:路径} 形式的字符串，没写命名空间时按 {@code minecraft}。
     *
     * @param id 字符串形式的 ID
     * @return 解析出的 ID
     * @throws net.minecraft.ResourceLocationException 字符串不合法
     */
    public static ResourceLocation parse(String id) {
        return ResourceLocation.parse(id);
    }
}
