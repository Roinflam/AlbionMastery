package com.flameforge.albionmastery.weapon;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * 一件武器在专精体系里的完整定位。
 *
 * <p>一次解析就把三层键都算好，服务端结算伤害、发经验时不需要再各算一遍。
 *
 * @param providerId 来源 ID
 * @param weaponId   武器 ID
 * @param type       武器类型标识，例如 {@code rifle}、{@code melee}
 * @param weaponKey  武器专精键
 * @param typeKey    类型专精键
 * @param overallKey 总体专精键
 */
public record ResolvedWeapon(String providerId,
                             ResourceLocation weaponId,
                             String type,
                             String weaponKey,
                             String typeKey,
                             String overallKey) {

    /**
     * 由来源与武器 ID 构造，自动算出三层键。
     *
     * @param providerId 来源 ID
     * @param weaponId   武器 ID
     * @param type       武器类型
     * @return 定位结果
     */
    public static ResolvedWeapon of(String providerId, ResourceLocation weaponId, String type) {
        String safeType = type == null || type.isBlank() ? WeaponKeys.TYPE_UNKNOWN : type;
        return new ResolvedWeapon(
                providerId,
                weaponId,
                safeType,
                WeaponKeys.weaponKey(providerId, weaponId),
                WeaponKeys.typeKey(providerId, safeType),
                WeaponKeys.overallKey(providerId)
        );
    }

    /**
     * 由已经存在的键反推出一个定位。
     *
     * <p>指令里管理员直接敲武器键时用得上：那时候手上并没有这件物品，只有存档里的一串键。
     * 类型必须由调用方给出（从玩家的武器记录里取），因为武器键本身不含类型信息。
     *
     * @param weaponKey 武器键，形如 {@code tacz_gun/tacz:ak47}
     * @param typeKey   类型键，形如 {@code tacz_gun#rifle}
     * @return 定位；武器键格式不合法时返回 {@code null}
     */
    @Nullable
    public static ResolvedWeapon ofKeys(String weaponKey, String typeKey) {
        int slash = weaponKey.indexOf('/');
        if (slash <= 0 || slash + 1 >= weaponKey.length()) {
            return null;
        }
        ResourceLocation weaponId = ResourceLocation.tryParse(weaponKey.substring(slash + 1));
        if (weaponId == null) {
            return null;
        }
        String providerId = weaponKey.substring(0, slash);
        return new ResolvedWeapon(
                providerId,
                weaponId,
                WeaponKeys.typeOf(typeKey),
                weaponKey,
                typeKey,
                WeaponKeys.overallKey(providerId)
        );
    }
}
