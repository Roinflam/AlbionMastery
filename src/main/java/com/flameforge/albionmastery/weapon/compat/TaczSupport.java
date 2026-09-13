package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * TACZ 枪械的双端解析支持。
 *
 * <p>TACZ 是本模组的强制依赖，可以直接引用其 API，不需要做加载判断。
 */
public final class TaczSupport {

    private TaczSupport() {
    }

    /**
     * 取出物品堆对应的枪械 ID。
     *
     * @param stack 物品堆
     * @return 枪械 ID；不是 TACZ 枪械时返回 null
     */
    @Nullable
    public static ResourceLocation gunId(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) {
            return null;
        }
        ResourceLocation gunId = iGun.getGunId(stack);
        if (gunId == null || gunId.getPath().isEmpty()) {
            return null;
        }
        return gunId;
    }

    /**
     * 查询一把枪的类型。
     *
     * <p>类型取的是枪械索引里的 {@code type} 字段（rifle / sniper / shotgun / smg /
     * mg / pistol / rpg），也就是 TACZ 创造栏分页用的那套分类。任何枪包想在创造栏里
     * 正常显示都必须填这个字段，所以它是跨枪包通用的，不需要为哪个包单独适配。
     *
     * @param gunId 枪械 ID
     * @return 类型标识；查不到时返回 {@link WeaponKeys#TYPE_UNKNOWN}
     */
    public static String gunType(ResourceLocation gunId) {
        try {
            return TimelessAPI.getCommonGunIndex(gunId)
                    .map(CommonGunIndex::getType)
                    .filter(type -> type != null && !type.isBlank())
                    .orElse(WeaponKeys.TYPE_UNKNOWN);
        } catch (Exception exception) {
            return WeaponKeys.TYPE_UNKNOWN;
        }
    }

    /**
     * 把物品堆解析成完整定位。
     *
     * @param stack 物品堆
     * @return 定位结果；不是 TACZ 枪械时返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack) {
        ResourceLocation gunId = gunId(stack);
        if (gunId == null) {
            return null;
        }
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_TACZ_GUN, gunId, gunType(gunId));
    }
}
