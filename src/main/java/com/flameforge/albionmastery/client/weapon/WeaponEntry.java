package com.flameforge.albionmastery.client.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 一件可专精的武器（专精树最外层）。
 *
 * <p>只在客户端存在，用于构建界面。服务端不需要这张表：伤害事件里能直接拿到武器 ID，
 * 类型由枪械索引查出来，不依赖任何预先注册。
 *
 * @param key           武器专精键
 * @param typeKey       所属类型专精键
 * @param overallKey    所属总体专精键
 * @param weaponId      武器 ID
 * @param packNamespace 所属枪包 / 刀包的命名空间，仅用于详情面板展示
 * @param displayName   显示名称
 * @param type          类型标识（rifle / sniper / melee ……）
 * @param sort          来源提供的排序值，越小越靠前
 * @param icon          节点图标物品堆，可能为空
 */
public record WeaponEntry(String key,
                          String typeKey,
                          String overallKey,
                          ResourceLocation weaponId,
                          String packNamespace,
                          Component displayName,
                          String type,
                          int sort,
                          ItemStack icon) {
}
