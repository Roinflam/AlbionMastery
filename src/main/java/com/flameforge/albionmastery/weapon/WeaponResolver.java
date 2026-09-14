package com.flameforge.albionmastery.weapon;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.weapon.compat.ArsNouveauSupport;
import com.flameforge.albionmastery.weapon.compat.BowSupport;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import com.flameforge.albionmastery.weapon.compat.LrTacticalSupport;
import com.flameforge.albionmastery.weapon.compat.SlashBladeSupport;
import com.flameforge.albionmastery.weapon.compat.VanillaMeleeSupport;
import com.flameforge.albionmastery.weapon.compat.TaczSupport;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 物品堆 -&gt; 专精定位的统一入口（双端可用）。
 *
 * <p>服务端结算伤害、发经验以及指令都走这里；客户端界面另有一套 Provider 负责枚举清单，
 * 但解析单个物品时最终也落到同一批 compat 类上，保证两边算出来的键完全一致。
 *
 * <p>可选依赖的处理方式：先用 {@link ModList} 判断模组是否安装，确认之后才去碰对应的
 * compat 类。compat 类里直接引用了第三方模组的类型，模组缺失时被加载会抛
 * {@code NoClassDefFoundError}，所以这层守卫不能省。
 */
public final class WeaponResolver {

    /** lrtactical（近战附属）的模组 ID。 */
    public static final String LRTACTICAL_MOD_ID = "lrtactical";

    /** 拔刀剑的模组 ID。 */
    public static final String SLASHBLADE_MOD_ID = "slashblade";

    /** 铁魔法的 modid。 */
    public static final String IRONS_MOD_ID = "irons_spellbooks";

    /** TACZ 的 modid。 */
    public static final String TACZ_MOD_ID = "tacz";

    /** 新生魔艺的 modid。 */
    public static final String ARS_MOD_ID = "ars_nouveau";

    /** lrtactical 是否已安装，首次查询后缓存。 */
    private static Boolean lrTacticalLoaded;

    /** 拔刀剑是否已安装，首次查询后缓存。 */
    private static Boolean slashBladeLoaded;

    private static Boolean ironsLoaded;

    private static Boolean taczLoaded;

    private static Boolean arsLoaded;

    private WeaponResolver() {
    }

    /**
     * 判断近战附属是否已安装。
     *
     * @return 已安装返回 true
     */
    public static boolean isLrTacticalLoaded() {
        if (lrTacticalLoaded == null) {
            lrTacticalLoaded = ModList.get().isLoaded(LRTACTICAL_MOD_ID);
        }
        return lrTacticalLoaded;
    }

    /**
     * 判断拔刀剑是否已安装。
     *
     * @return 已安装返回 true
     */
    public static boolean isSlashBladeLoaded() {
        if (slashBladeLoaded == null) {
            slashBladeLoaded = ModList.get().isLoaded(SLASHBLADE_MOD_ID);
        }
        return slashBladeLoaded;
    }

    /**
     * 把物品堆解析成完整的专精定位。
     *
     * @param stack 物品堆
     * @return 定位结果；不属于任何已接入体系时返回 null
     */
    /**
     * 新生魔艺是否已加载。
     *
     * @return 已加载返回 true
     */
    public static boolean isArsLoaded() {
        if (arsLoaded == null) {
            arsLoaded = ModList.get().isLoaded(ARS_MOD_ID);
        }
        return arsLoaded;
    }

    /**
     * TACZ 是否已加载。五个来源全是可选的，没装哪个就不碰哪个的类。
     *
     * @return 已加载返回 true
     */
    public static boolean isTaczLoaded() {
        if (taczLoaded == null) {
            taczLoaded = ModList.get().isLoaded(TACZ_MOD_ID);
        }
        return taczLoaded;
    }

    /**
     * 铁魔法是否已加载。结果缓存，和上面两个一样。
     *
     * @return 已加载返回 true
     */
    public static boolean isIronsLoaded() {
        if (ironsLoaded == null) {
            ironsLoaded = ModList.get().isLoaded(IRONS_MOD_ID);
        }
        return ironsLoaded;
    }

    /**
     * 「一件物品 → 一串武器」，服务端路径。
     *
     * <p>绝大多数物品就是一件武器，只有铁魔法的法术容器（魔法书、卷轴）里可能装好几个法术。
     * 击杀经验按这个发：拿着书，书里每个法术都拿。
     *
     * @param stack 物品
     * @return 武器列表；认不出来返回空表
     */
    public static List<ResolvedWeapon> resolveAll(ItemStack stack) {
        return resolveAll(stack, MasteryConfig.serverSnapshot().splitMeleeByPack);
    }

    /**
     * 「一件物品 → 一串武器」。
     *
     * @param stack            物品
     * @param splitMeleeByPack 近战是否按附属包拆分
     * @return 武器列表；认不出来返回空表
     */
    public static List<ResolvedWeapon> resolveAll(ItemStack stack, boolean splitMeleeByPack) {
        if (stack.isEmpty()) {
            return List.of();
        }
        if (isIronsLoaded() && IronsSpellsSupport.isContainer(stack)) {
            return IronsSpellsSupport.resolveAll(stack);
        }
        if (isArsLoaded() && ArsNouveauSupport.isCaster(stack)) {
            return ArsNouveauSupport.resolveAll(stack);
        }
        ResolvedWeapon single = resolve(stack, splitMeleeByPack);
        return single == null ? List.of() : List.of(single);
    }

    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack) {
        // 服务端路径：直接用本地生效的配置
        return resolve(stack, MasteryConfig.serverSnapshot().splitMeleeByPack);
    }

    /**
     * 解析武器，并指定近战的类型拆分口径。
     *
     * <p>客户端要传服务端下发的快照里的值：多人游戏里客户端的本地配置和服务端可能不一样，
     * 用本地值会算出一套对不上的类型键，界面上的类型节点就永远是 0 级。
     *
     * @param stack            物品堆
     * @param splitMeleeByPack 近战是否按刀包拆分类型
     * @return 解析结果；不是可专精武器时返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack, boolean splitMeleeByPack) {
        if (stack.isEmpty()) {
            return null;
        }

        ResolvedWeapon resolved = null;
        if (isTaczLoaded()) {
            resolved = TaczSupport.resolve(stack);
            if (resolved != null) {
                return resolved;
            }
        }

        if (isLrTacticalLoaded()) {
            resolved = LrTacticalSupport.resolve(stack, splitMeleeByPack);
            if (resolved != null) {
                return resolved;
            }
        }

        if (isSlashBladeLoaded()) {
            resolved = SlashBladeSupport.resolve(stack);
            if (resolved != null) {
                return resolved;
            }
        }

        if (isIronsLoaded()) {
            // 法术容器只认第一个法术；要全部的走 resolveAll
            resolved = IronsSpellsSupport.resolve(stack);
            if (resolved != null) {
                return resolved;
            }
        }

        if (isArsLoaded()) {
            resolved = ArsNouveauSupport.resolve(stack);
            if (resolved != null) {
                return resolved;
            }
        }

        // 兜底：谁都没认的剑归通用剑类、弓弩归通用弓弩。必须排最后，拔刀剑的刀也是 SwordItem
        resolved = VanillaMeleeSupport.resolve(stack);
        if (resolved != null) {
            return resolved;
        }
        return BowSupport.resolve(stack);
    }
}
