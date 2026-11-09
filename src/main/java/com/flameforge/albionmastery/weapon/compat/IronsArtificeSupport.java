package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import io.redspace.irons_artifice.advancement.ShotRecord;
import io.redspace.irons_artifice.damage.DamageSources;
import io.redspace.irons_artifice.entity.Bullet;
import io.redspace.irons_artifice.gun.ArmPoseKind;
import io.redspace.irons_artifice.gun.ShotProfile;
import io.redspace.irons_artifice.item.GunItem;
import io.redspace.irons_artifice.registry.ItemRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 铁枪（Iron's Arms 'n Artifice）的火器。
 *
 * <p>七把枪的物品类都是 {@link GunItem}，大类照铁枪自己的持枪姿势分：单手的算手枪，双手的算长枪。
 * 以后铁枪或者它的附属再加枪，只要是 {@code GunItem} 就自动归对类，不用手写归类表。
 * 武器键就是物品注册名，改装件装在枪的 NBT 里，换改装件不会让经验分家。
 *
 * <p>命中能追到是哪把枪：子弹实体身上带着开火那一刻的整份组装结果，里面的物品就是那把枪。
 * 改装件的爆炸、闪电、魂火伤害，直接来源也都是那颗子弹。
 *
 * <p>这个类引用铁枪的类，只能在铁枪装着时碰（调用方先看 {@code WeaponResolver.isIronsArtificeLoaded()}）。
 */
public final class IronsArtificeSupport {

    /** 大类：单手持握的枪（燧发枪、左轮）。 */
    public static final String TYPE_PISTOL = "pistol";

    /** 大类：双手持握的枪（滑膛枪、雷铳、火绳枪、发条步枪）。 */
    public static final String TYPE_RIFLE = "rifle";

    /** 发枪时附带几组子弹。 */
    private static final int AMMO_STACKS = 2;

    private IronsArtificeSupport() {
    }

    /**
     * 这件物品是不是铁枪的枪。
     *
     * @param stack 物品
     * @return 是返回 true
     */
    public static boolean isGun(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof GunItem;
    }

    /**
     * 枪 → 大类。
     *
     * @param gun 枪
     * @return 手枪或长枪
     */
    public static String typeOf(GunItem gun) {
        return gun.getGun().armPoseKind() == ArmPoseKind.PISTOL ? TYPE_PISTOL : TYPE_RIFLE;
    }

    /**
     * 物品 → 武器。
     *
     * @param stack 物品
     * @return 武器；不是铁枪的枪返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack) {
        if (!(stack.getItem() instanceof GunItem gun)) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(gun);
        return id == null ? null : ResolvedWeapon.of(WeaponKeys.PROVIDER_IRONS_ARTIFICE, id, typeOf(gun));
    }

    /**
     * 把注册表里全部铁枪列进服务端清单（管理指令发枪、补全武器键用）。
     *
     * @param out 输出
     */
    public static void catalog(List<WeaponCatalog.Entry> out) {
        for (Map.Entry<ResourceKey<Item>, Item> entry : ForgeRegistries.ITEMS.getEntries()) {
            if (!(entry.getValue() instanceof GunItem gun)) {
                continue;
            }
            ResolvedWeapon weapon = ResolvedWeapon.of(WeaponKeys.PROVIDER_IRONS_ARTIFICE,
                    entry.getKey().location(), typeOf(gun));
            out.add(new WeaponCatalog.Entry(weapon, () -> gunItems(gun)));
        }
    }

    /**
     * 一把枪外加两组子弹。枪刚造出来时弹匣没写进 NBT，铁枪按满弹匣算，拿到就能开火。
     */
    private static List<ItemStack> gunItems(GunItem gun) {
        List<ItemStack> items = new ArrayList<>();
        items.add(new ItemStack(gun));
        Item bullet = ItemRegistry.BULLET.get();
        for (int i = 0; i < AMMO_STACKS; i++) {
            items.add(new ItemStack(bullet, bullet.getMaxStackSize()));
        }
        return items;
    }

    /**
     * 是不是铁枪的子弹实体。
     *
     * @param entity 实体
     * @return 是返回 true
     */
    public static boolean isBullet(@Nullable Entity entity) {
        return entity instanceof Bullet;
    }

    /**
     * 子弹是哪把枪打出来的。
     *
     * @param direct 伤害的直接来源
     * @return 枪；不是铁枪子弹、或者子弹身上没有组装结果（读档回来的旧子弹）返回 null
     */
    @Nullable
    public static ResolvedWeapon fromBullet(@Nullable Entity direct) {
        if (!(direct instanceof Bullet bullet)) {
            return null;
        }
        ShotProfile profile = bullet.getProfile();
        return profile == null || profile.itemStack() == null ? null : resolve(profile.itemStack());
    }

    /**
     * 这一下是不是子弹直接命中（铁枪的子弹伤害类型），而不是改装件附带的爆炸、闪电、魂火。
     *
     * @param source 伤害源
     * @return 直接命中返回 true
     */
    public static boolean isDirectHit(DamageSource source) {
        return source.is(DamageSources.BULLET_DAMAGE_TYPE);
    }

    /**
     * 这颗子弹是不是满弹匣打出的第一发（单发枪每一发都是）。
     *
     * @param direct 伤害的直接来源
     * @return 是返回 true
     */
    public static boolean isFullMagazineShot(@Nullable Entity direct) {
        ShotRecord record = direct instanceof Bullet bullet ? bullet.getShotRecord() : null;
        return record != null && record.fullMagazine();
    }

    /**
     * 这颗子弹是不是在方块上弹射过。
     *
     * @param direct 伤害的直接来源
     * @return 弹射过返回 true
     */
    public static boolean hasRicocheted(@Nullable Entity direct) {
        ShotRecord record = direct instanceof Bullet bullet ? bullet.getShotRecord() : null;
        return record != null && record.ricocheted();
    }
}
