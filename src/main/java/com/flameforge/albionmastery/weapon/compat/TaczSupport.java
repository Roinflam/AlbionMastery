package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.index.CommonAmmoIndex;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.world.entity.Entity;

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

    /** 发枪时附带几组子弹。 */
    private static final int AMMO_STACKS = 2;

    /**
     * 把服务端认得的全部枪列进清单。用的是通用枪械索引，专用服务端上也有。
     *
     * @param out 输出
     */
    public static void catalog(List<WeaponCatalog.Entry> out) {
        for (Map.Entry<ResourceLocation, CommonGunIndex> entry : TimelessAPI.getAllCommonGunIndex()) {
            ResourceLocation gunId = entry.getKey();
            CommonGunIndex index = entry.getValue();
            if (gunId == null || index == null || gunId.getPath().isEmpty()) {
                continue;
            }
            // 类型和 gunType() 取的是同一个字段，这里手上已经有索引，直接用
            String type = index.getType() == null || index.getType().isBlank()
                    ? WeaponKeys.TYPE_UNKNOWN : index.getType();
            ResolvedWeapon weapon = ResolvedWeapon.of(WeaponKeys.PROVIDER_TACZ_GUN, gunId, type);
            out.add(new WeaponCatalog.Entry(weapon, () -> gunItems(gunId, index)));
        }
    }

    /**
     * 一把压满弹匣、膛里有弹的枪，外加两组它用的子弹，拿到就能开火。
     */
    private static List<ItemStack> gunItems(ResourceLocation gunId, CommonGunIndex index) {
        List<ItemStack> items = new ArrayList<>();
        GunData data = index.getGunData();
        int magazine = data == null ? 0 : Math.max(0, data.getAmmoAmount());
        GunItemBuilder builder = GunItemBuilder.create().setId(gunId).setAmmoCount(magazine).setAmmoInBarrel(true);
        if (data != null) {
            // 不设射击模式的话 NBT 里是 UNKNOWN，左键只会提示「射击模式切换失败」，得先手动切一次才能开火
            List<FireMode> modes = data.getFireModeSet();
            if (modes != null && !modes.isEmpty()) {
                builder.setFireMode(modes.get(0));
            }
            builder.setHeatData(data.hasHeatData());
        }
        ItemStack gun = builder.build();
        if (gun.isEmpty()) {
            return items;
        }
        items.add(gun);
        ResourceLocation ammoId = data == null ? null : data.getAmmoId();
        if (ammoId == null) {
            return items;
        }
        int stackSize = TimelessAPI.getCommonAmmoIndex(ammoId).map(CommonAmmoIndex::getStackSize).orElse(0);
        for (int i = 0; i < AMMO_STACKS && stackSize > 0; i++) {
            ItemStack ammo = AmmoItemBuilder.create().setId(ammoId).setCount(stackSize).build();
            if (!ammo.isEmpty()) {
                items.add(ammo);
            }
        }
        return items;
    }

    /**
     * 子弹是哪把枪打出来的。子弹自己记着枪的 ID，开枪后换了武器也认得出来。
     *
     * @param direct 伤害的直接来源
     * @return 枪；不是 TACZ 子弹返回 null
     */
    @Nullable
    public static ResolvedWeapon fromBullet(@Nullable Entity direct) {
        if (!(direct instanceof EntityKineticBullet bullet)) {
            return null;
        }
        ResourceLocation gunId = bullet.getGunId();
        if (gunId == null || gunId.getPath().isEmpty()) {
            return null;
        }
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_TACZ_GUN, gunId, gunType(gunId));
    }
}
