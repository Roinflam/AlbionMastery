package com.flameforge.albionmastery.weapon;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.weapon.compat.ArsNouveauSupport;
import com.flameforge.albionmastery.weapon.compat.BowSupport;
import com.flameforge.albionmastery.weapon.compat.IronsArtificeSupport;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import com.flameforge.albionmastery.weapon.compat.LrTacticalSupport;
import com.flameforge.albionmastery.weapon.compat.SlashBladeSupport;
import com.flameforge.albionmastery.weapon.compat.VanillaMeleeSupport;
import com.flameforge.albionmastery.weapon.compat.TaczSupport;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;
import java.util.List;
import com.flameforge.albionmastery.weapon.compat.WeaponOverrides;
import net.minecraft.core.registries.BuiltInRegistries;

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

    /** 铁枪（Iron's Arms 'n Artifice）的模组 ID。 */
    public static final String IRONS_ARTIFICE_MOD_ID = "irons_artifice";

    /** lrtactical 是否已安装，首次查询后缓存。 */
    private static Boolean lrTacticalLoaded;

    /** 拔刀剑是否已安装，首次查询后缓存。 */
    private static Boolean slashBladeLoaded;

    private static Boolean ironsLoaded;

    private static Boolean taczLoaded;

    private static Boolean arsLoaded;

    /** 铁枪装没装的缓存。 */
    private static Boolean ironsArtificeLoaded;

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
     * 拔刀剑装没装。
     *
     * @return 装了返回 true
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
     * 铁枪是否已加载。
     *
     * @return 已加载返回 true
     */
    public static boolean isIronsArtificeLoaded() {
        if (ironsArtificeLoaded == null) {
            ironsArtificeLoaded = ModList.get().isLoaded(IRONS_ARTIFICE_MOD_ID);
        }
        return ironsArtificeLoaded;
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
        List<ResolvedWeapon> spells = List.of();
        // 铁魔法认作法术容器的不再问新生魔艺
        boolean ironsContainer = false;
        if (isIronsLoaded() && IronsSpellsSupport.isContainer(stack)) {
            ironsContainer = true;
            spells = IronsSpellsSupport.resolveAll(stack);
        }
        if (!ironsContainer && isArsLoaded() && ArsNouveauSupport.isCaster(stack)) {
            spells = ArsNouveauSupport.resolveAll(stack);
        }
        if (spells.isEmpty()) {
            ResolvedWeapon single = resolve(stack, splitMeleeByPack);
            return single == null ? List.of() : List.of(single);
        }
        // 已经拿到法术了，这一趟只问「它自己是不是一件近战 / 弓弩武器」，
        // 必须跳过法术那两个分支：它们内部会把整份法术 NBT 重新解析一遍，
        // 而算出来的结果在下面必然被丢掉——这条路就在击杀经验的热路径上，群体击杀会被乘上去
        ResolvedWeapon single = resolve(stack, splitMeleeByPack, true);
        // 这件物品自己也是一件近战 / 弓弩武器（奥术铁砧附过法术的剑、魔艺的附魔师之剑）：
        // resolve() 把它认成近战，近战伤害也是按那个键放大的，经验就必须发到同一个键上。
        // 两边口径不一致的话，这把剑的武器节点永远涨不了，可伤害偏偏按它的等级算
        if (single == null) {
            return spells;
        }
        List<ResolvedWeapon> all = new java.util.ArrayList<>(spells.size() + 1);
        all.add(single);
        all.addAll(spells);
        return List.copyOf(all);
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
        return resolve(stack, splitMeleeByPack, false);
    }

    /**
     * 解析武器，可以跳过法术那两个来源。
     *
     * @param stack              物品堆
     * @param splitMeleeByPack   近战是否按刀包拆分类型
     * @param skipSpellProviders 跳过铁魔法 / 新生魔艺：{@link #resolveAll} 已经单独拿过法术了，
     *                           再走一遍等于把整份法术 NBT 白解析一次
     * @return 解析结果；不是可专精武器时返回 null
     */
    @Nullable
    private static ResolvedWeapon resolve(ItemStack stack, boolean splitMeleeByPack,
                                          boolean skipSpellProviders) {
        if (stack.isEmpty()) {
            return null;
        }

        // 排除表在这里统一拦一道：TACZ、拔刀剑、铁魔法这些是按 NBT 认身份的，
        // 各自的 support 里不查这张表，只在这儿拦才对所有来源都成立
        if (WeaponOverrides.isExcluded(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
            return null;
        }

        ResolvedWeapon resolved = null;
        if (isTaczLoaded()) {
            resolved = TaczSupport.resolve(stack);
            if (resolved != null) {
                return resolved;
            }
        }

        if (isIronsArtificeLoaded()) {
            resolved = IronsArtificeSupport.resolve(stack);
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

        // 原版剑、斧、三叉戟、弓弩排在法术之前：铁魔法按 NBT 认法术容器，奥术铁砧附过法术的剑也会被认成法术，
        // 那样近战命中拿不到任何加成、剑的攻速和攻击距离特化也被摘掉。附进去的法术照样能放，
        // 施法时的法术专精按施放的那个法术算，不经过这里
        if (VanillaMeleeSupport.isVanillaMelee(stack)) {
            resolved = VanillaMeleeSupport.resolve(stack);
            if (resolved != null) {
                return resolved;
            }
        }
        if (BowSupport.isBowOrCrossbow(stack)) {
            resolved = BowSupport.resolve(stack);
            if (resolved != null) {
                return resolved;
            }
        }

        if (!skipSpellProviders && isIronsLoaded()) {
            // 法术容器只认第一个法术；要全部的走 resolveAll
            resolved = IronsSpellsSupport.resolve(stack);
            if (resolved != null) {
                return resolved;
            }
        }

        if (!skipSpellProviders && isArsLoaded()) {
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
