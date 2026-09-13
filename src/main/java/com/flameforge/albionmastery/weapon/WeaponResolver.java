package com.flameforge.albionmastery.weapon;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.weapon.compat.LrTacticalSupport;
import com.flameforge.albionmastery.weapon.compat.SlashBladeSupport;
import com.flameforge.albionmastery.weapon.compat.TaczSupport;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;

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

    /** lrtactical 是否已安装，首次查询后缓存。 */
    private static Boolean lrTacticalLoaded;

    /** 拔刀剑是否已安装，首次查询后缓存。 */
    private static Boolean slashBladeLoaded;

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

        ResolvedWeapon resolved = TaczSupport.resolve(stack);
        if (resolved != null) {
            return resolved;
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

        return null;
    }
}
