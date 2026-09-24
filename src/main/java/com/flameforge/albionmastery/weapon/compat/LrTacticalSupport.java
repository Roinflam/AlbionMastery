package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import me.xjqsh.lrtactical.api.LrTacticalAPI;
import me.xjqsh.lrtactical.api.item.IMeleeWeapon;
import me.xjqsh.lrtactical.item.index.MeleeWeaponIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/**
 * lrtactical（TACZ 近战附属）近战武器的双端解析支持。
 *
 * <p>lrtactical 是可选依赖，本类直接引用它的类，因此<b>调用方必须先确认该模组已安装</b>
 * （见 {@link com.flameforge.albionmastery.weapon.WeaponResolver}），
 * 否则会在类加载阶段抛 {@code NoClassDefFoundError}。
 */
public final class LrTacticalSupport {

    /**
     * 近战武器的类型标识。
     *
     * <p>刀包索引里的 {@code type} 字段是 {@code lrtactical:normal} 这类<b>行为类型</b>
     * （决定判定形状与特效），不是「匕首 / 长刀」这种武器分类，拿来分层没有意义，
     * 所以近战统一归为一个类型。
     */
    public static final String TYPE_MELEE = "melee";

    private LrTacticalSupport() {
    }

    /**
     * 取出物品堆对应的近战武器 ID。
     *
     * @param stack 物品堆
     * @return 近战武器 ID；不是 lrtactical 近战武器时返回 null
     */
    @Nullable
    public static ResourceLocation meleeId(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        // 用 instanceof 判定而不是 IMeleeWeapon.of()，避免依赖其对非近战物品的返回约定
        if (!(stack.getItem() instanceof IMeleeWeapon meleeWeapon)) {
            return null;
        }
        ResourceLocation id = meleeWeapon.getId(stack);
        if (id == null || id.getPath().isEmpty() || IMeleeWeapon.EMPTY.equals(id)) {
            return null;
        }
        return id;
    }

    /**
     * 把物品堆解析成完整定位。
     *
     * @param stack 物品堆
     * @return 定位结果；不是 lrtactical 近战武器时返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack, boolean splitByPack) {
        ResourceLocation id = meleeId(stack);
        if (id == null) {
            return null;
        }
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, id, typeOf(id, splitByPack));
    }

    /**
     * 算一件近战武器归到哪个类型。
     *
     * <p>拆分开启时用刀包的命名空间当类型，和拔刀剑的附属包一个口径；关闭时所有近战共用
     * {@link #TYPE_MELEE}，也就是 1.0.0 的旧行为。
     *
     * <p><b>这个判断必须两端一致</b>：服务端用它算类型键存经验，客户端用它建专精树。
     * 所以拆分开关走的是服务端下发的配置快照，而不是各端各读各的本地配置。
     *
     * @param id           武器 ID
     * @param splitByPack  是否按刀包拆分
     * @return 类型标识
     */
    public static String typeOf(ResourceLocation id, boolean splitByPack) {
        if (!splitByPack) {
            return TYPE_MELEE;
        }
        String namespace = id.getNamespace();
        return namespace.isBlank() ? TYPE_MELEE : namespace;
    }

    /**
     * 把刀包里的全部近战武器列进清单。索引走的是通用资源管理器，专用服务端上也有。
     *
     * @param out         输出
     * @param splitByPack 近战是否按刀包拆分类型
     */
    public static void catalog(List<WeaponCatalog.Entry> out, boolean splitByPack) {
        for (MeleeWeaponIndex<?> index : LrTacticalAPI.getMeleeIndexes()) {
            if (index == null) {
                continue;
            }
            ResourceLocation id = index.getId();
            if (id == null || id.getPath().isEmpty() || IMeleeWeapon.EMPTY.equals(id)) {
                continue;
            }
            ResolvedWeapon weapon = ResolvedWeapon.of(WeaponKeys.PROVIDER_LRTACTICAL_MELEE, id,
                    typeOf(id, splitByPack));
            out.add(new WeaponCatalog.Entry(weapon, () -> {
                ItemStack stack = index.createItemStack();
                return stack == null ? List.of() : List.of(stack);
            }));
        }
    }
}
