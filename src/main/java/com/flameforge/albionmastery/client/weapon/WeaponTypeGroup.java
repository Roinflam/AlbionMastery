package com.flameforge.albionmastery.client.weapon;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个武器类型分组（专精树第三层，带等级）。
 *
 * <p>类型取自枪械索引里的 {@code type} 字段——步枪、狙击、霰弹、冲锋枪、机枪、手枪、火箭筒，
 * 也就是 TACZ 创造栏分页用的那套分类。任何枪包想在创造栏里正常显示都必须填这个字段，
 * 所以它跨枪包通用，不需要为哪个包单独适配。近战武器统一归为一个类型。
 */
public final class WeaponTypeGroup {

    /** 类型专精键。 */
    private final String key;

    /** 类型标识。 */
    private final String type;

    /** 显示名称。 */
    private final Component displayName;

    /** 该类型下的全部武器。 */
    private final List<WeaponEntry> weapons = new ArrayList<>();

    /**
     * 构造分组。
     *
     * @param key         类型专精键
     * @param type        类型标识
     * @param displayName 显示名称
     */
    public WeaponTypeGroup(String key, String type, Component displayName) {
        this.key = key;
        this.type = type;
        this.displayName = displayName;
    }

    /**
     * @return 类型专精键
     */
    public String key() {
        return this.key;
    }

    /**
     * @return 类型标识
     */
    public String type() {
        return this.type;
    }

    /**
     * @return 显示名称
     */
    public Component displayName() {
        return this.displayName;
    }

    /**
     * @return 该类型下的武器列表
     */
    public List<WeaponEntry> weapons() {
        return this.weapons;
    }
}
