package com.flameforge.albionmastery.client.weapon;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个武器大类（内圈大节点）。
 *
 * <p>大类 = 来源 + 命名空间，例如：
 * <ul>
 *     <li>{@code tacz_gun/tacz} —— TACZ 本体自带枪包</li>
 *     <li>{@code tacz_gun/lrtactical} —— 某个附属 / 第三方枪包</li>
 * </ul>
 * 玩家新装一个枪包就自动多出一个大类，不需要改任何配置。
 */
public final class WeaponCategory {

    /** 大类唯一键。 */
    private final String key;

    /** 来源 ID。 */
    private final String providerId;

    /** 命名空间（枪包 / 刀包名）。 */
    private final String namespace;

    /** 显示名称。 */
    private final Component displayName;

    /** 该大类下的全部武器，构建完成后已排好序。 */
    private final List<WeaponEntry> weapons = new ArrayList<>();

    /**
     * 构造大类。
     *
     * @param key         大类唯一键
     * @param providerId  来源 ID
     * @param namespace   命名空间
     * @param displayName 显示名称
     */
    public WeaponCategory(String key, String providerId, String namespace, Component displayName) {
        this.key = key;
        this.providerId = providerId;
        this.namespace = namespace;
        this.displayName = displayName;
    }

    /**
     * @return 大类唯一键
     */
    public String key() {
        return this.key;
    }

    /**
     * @return 来源 ID
     */
    public String providerId() {
        return this.providerId;
    }

    /**
     * @return 命名空间
     */
    public String namespace() {
        return this.namespace;
    }

    /**
     * @return 显示名称
     */
    public Component displayName() {
        return this.displayName;
    }

    /**
     * @return 该大类下的武器列表（可变，仅供注册表构建阶段使用）
     */
    public List<WeaponEntry> weapons() {
        return this.weapons;
    }
}
