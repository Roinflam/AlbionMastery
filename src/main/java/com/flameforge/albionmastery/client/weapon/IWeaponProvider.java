package com.flameforge.albionmastery.client.weapon;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 武器来源提供者。
 *
 * <p>每接入一个武器体系就实现一个 Provider，界面与提示文本不需要任何改动：
 * <ul>
 *     <li>{@code tacz_gun} —— TACZ 枪械</li>
 *     <li>{@code lrtactical_melee} —— TACZ 近战附属</li>
 *     <li>{@code slashblade} —— 拔刀剑（后续）</li>
 * </ul>
 *
 * <p>本接口只在客户端使用，负责枚举清单。单个物品的解析走公共的
 * {@link com.flameforge.albionmastery.weapon.WeaponResolver}，双端一致。
 */
public interface IWeaponProvider {

    /**
     * @return 来源 ID，会作为各级专精键的前缀
     */
    String providerId();

    /**
     * 判断该来源当前是否可用（对应模组是否已安装）。
     *
     * @return 可用返回 true
     */
    boolean isAvailable();

    /**
     * @return 该来源在专精树上的显示名称，例如「TACZ 枪械」
     */
    Component sourceName();

    /**
     * 收集该来源下的全部武器。
     *
     * @param out 收集结果的输出列表
     */
    void collect(List<WeaponEntry> out);

    /**
     * 取得某个武器类型的显示名称。
     *
     * @param type 类型标识
     * @return 显示名称
     */
    Component typeName(String type);
}
