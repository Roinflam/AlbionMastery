package com.flameforge.albionmastery.weapon.compat;

import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import sweetmagic.api.emagic.SMElement;
import sweetmagic.api.iitem.IMagic;
import sweetmagic.api.iitem.IWand;
import sweetmagic.api.iitem.info.WandInfo;
import sweetmagic.init.entity.projectile.AbstractMagicShot;

import javax.annotation.Nullable;
import java.util.Locale;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;

/**
 * 甜蜜魔法：魔法卷轴是「武器」，法杖只是载体。
 *
 * <p>和铁魔法（法术是武器）、新生魔艺（符文是武器）一个思路：练的是这一张魔法，不是这根杖。
 * 中圈按元素分（炎、霜、雷、地、光、暗……），外圈是每一张魔法卷轴。
 *
 * <p>伤害怎么算到卷轴头上：弹体只带着法杖，不带卷轴。所以施法的那一刻开一个窗口记下
 * 「谁、用哪张卷轴」，这期间生成的弹体和召唤物身上打一个标记（写进实体的持久数据，存档和跨区块都不丢）；
 * 命中时从弹体上读回来。瞬发和领域类没有弹体，就用施法窗口兜底，再不行按法杖当前选中的那一张算。
 */
public final class SweetMagicSupport {

    /** 弹体身上记卷轴用的键。 */
    private static final String TAG_MAGIC = "albionmastery_sm_magic";

    /** 甜蜜魔法自己的伤害类型命名空间。 */
    private static final String NAMESPACE = "sweetmagic";

    /** 法杖升级经验在物品 NBT 里的键（{@code IWand.EXP}）。 */
    private static final String WAND_EXP = "exp";

    /**
     * 正在施法的窗口，按线程各存一份。
     *
     * <p>为什么按线程：单人游戏里客户端和服务端跑在同一个进程里，共用一个静态字段的话，
     * 服务端正在施法时客户端渲染线程读到的物品提示会跟着变成缩放后的数值，
     * 还会两个线程同时碰同一份数据。按线程存，谁开的窗口谁看得见。
     */
    private static final ThreadLocal<Cast> WINDOW = new ThreadLocal<>();

    /**
     * 一次施法：谁、放的哪张卷轴、用的哪根法杖。
     *
     * @param player  施法者
     * @param weapon  卷轴
     * @param wand    法杖
     * @param wandExp 开窗口时法杖的升级经验
     * @param baseMF  这张卷轴缩放前的基础法力价，用来算该补多少经验
     */
    private record Cast(Player player, @Nullable ResolvedWeapon weapon, ItemStack wand, int wandExp,
                        int baseMF) {
    }

    private SweetMagicSupport() {
    }

    /**
     * 这件物品是不是魔法卷轴。
     *
     * @param stack 物品
     * @return 是返回 true
     */
    public static boolean isMagic(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof IMagic;
    }

    /**
     * 这件物品是不是法杖。
     *
     * @param stack 物品
     * @return 是返回 true
     */
    public static boolean isWand(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof IWand;
    }

    /**
     * 卷轴 → 大类（元素）。
     *
     * @param stack 物品
     * @return 元素名；不是卷轴返回 null
     */
    @Nullable
    public static String typeOf(ItemStack stack) {
        if (!isMagic(stack)) {
            return null;
        }
        SMElement element = ((IMagic) stack.getItem()).getElement();
        return element == null ? null : element.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 卷轴 → 武器。
     *
     * @param stack 物品
     * @return 武器；不是卷轴或没注册名返回 null
     */
    @Nullable
    public static ResolvedWeapon resolve(ItemStack stack) {
        String type = typeOf(stack);
        if (type == null) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_SWEETMAGIC, id, type);
    }

    /**
     * 玩家主手法杖当前选中的那张卷轴。
     *
     * <p>击杀经验按它发：手里拿的是法杖，真正在练的是选中的那张魔法。
     *
     * @param player 玩家
     * @return 武器；手上不是法杖、或者那一格是空的返回 null
     */
    @Nullable
    public static ResolvedWeapon selectedMagic(Player player) {
        // 主手拿着剑、副手拿着法杖时，原版会把右键转给副手，施法用的是副手那根。
        // 只看主手的话，这种玩法下经验记错账、特化也按错的卷轴算
        ResolvedWeapon main = selectedMagic(player, player.getMainHandItem());
        return main != null ? main : selectedMagic(player, player.getOffhandItem());
    }

    /**
     * 这根法杖当前选中的那张卷轴。
     *
     * @param player 玩家
     * @param wand   法杖
     * @return 武器；不是法杖、或者那一格是空的返回 null
     */
    @Nullable
    public static ResolvedWeapon selectedMagic(Player player, ItemStack wand) {
        ItemStack magic = selectedMagicStack(player, wand);
        return magic == null ? null : resolve(magic);
    }

    /**
     * 这根法杖当前选中的那张卷轴本体。
     *
     * @param player 玩家
     * @param wand   法杖
     * @return 卷轴物品；不是法杖、或者那一格是空的返回 null
     */
    @Nullable
    public static ItemStack selectedMagicStack(Player player, ItemStack wand) {
        if (!isWand(wand)) {
            return null;
        }
        try {
            ItemStack magic = ((IWand) wand.getItem()).getSlotItem(player, new WandInfo(wand));
            return magic == null || magic.isEmpty() ? null : magic;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 开一个施法窗口。
     *
     * @param player 施法者
     * @param wand   这次用的法杖（主手或副手，由调用方决定）
     */
    public static void beginCast(Player player, ItemStack wand) {
        ItemStack magic = selectedMagicStack(player, wand);
        // 基础法力价要在开窗口之前读：窗口一开，SweetMagicCostMixin 就会把 getUseMF 改成缩放后的值
        WINDOW.set(new Cast(player, magic == null ? null : resolve(magic), wand, wandExp(wand),
                baseUseMF(magic)));
    }

    /**
     * 这张卷轴缩放前的基础法力价。
     *
     * @param magic 卷轴；可为 null
     * @return 法力价；不是卷轴时是 0
     */
    private static int baseUseMF(@Nullable ItemStack magic) {
        if (magic == null || !isMagic(magic)) {
            return 0;
        }
        try {
            return Math.max(0, ((IMagic) magic.getItem()).getUseMF());
        } catch (Exception exception) {
            return 0;
        }
    }

    /**
     * 施法结束：先把法杖自己的升级经验补回原样，再关窗口。
     *
     * <p>为什么要补：省蓝的特化改的是卷轴的「基础法力价」，而甜蜜魔法给法杖发的升级经验
     * 正好也是按这个数算的（基础法力 ÷ 10）。不补的话，选了节流杖芯的人省三成蓝，
     * 法杖也跟着慢三成升级；选高密杖芯的反而白赚经验。特化该管的是法力，不该动升级速度。
     */
    public static void finishCast() {
        Cast cast = WINDOW.get();
        WINDOW.remove();
        if (cast == null || cast.weapon() == null || cast.wand().isEmpty() || cast.baseMF() <= 0) {
            return;
        }
        double factor = SpecializationEffects.forWeapon(cast.player(), cast.weapon()).manaCost();
        if (factor <= 0.0D || factor == 1.0D || !Double.isFinite(factor)) {
            return;
        }
        CompoundTag tag = cast.wand().getTag();
        if (tag == null || !tag.contains(WAND_EXP)) {
            return;
        }
        int gained = tag.getInt(WAND_EXP) - cast.wandExp();
        // 只补「这一发涨了多少」；升级那一下经验会被清零倒扣，那种情况不碰
        if (gained <= 0) {
            return;
        }
        // 法杖经验 = (法力价/10 + 卷轴自带的 addExp) × 增经倍率，只有法力价那一项被我们缩放过。
        // 这里只按 1 倍增经还原：喝了增经药水、穿了增经腿甲饰品时会剩一点残差，
        // 方向仍然是对的（少补一点而不是多补），比按比例除整块经验靠谱得多。
        // 按比例把整块经验除回去的话，会连 addExp 那一项也一起砍——
        // 提升阶位那几张卷轴的经验全来自 addExp、法力价本来就是 0，那样等于白扣
        int restored = cast.baseMF() / 10 - Math.max(0, (int) Math.round(cast.baseMF() * factor)) / 10;
        if (restored == 0) {
            return;
        }
        tag.putInt(WAND_EXP, Math.max(0, tag.getInt(WAND_EXP) + restored));
    }

    /**
     * 法杖当前的升级经验。
     *
     * @param wand 法杖
     * @return 经验；没有这个标签时是 0
     */
    private static int wandExp(ItemStack wand) {
        CompoundTag tag = wand.getTag();
        return tag == null ? 0 : tag.getInt(WAND_EXP);
    }

    /**
     * 关掉施法窗口。
     */
    public static void endCast() {
        WINDOW.remove();
    }

    /**
     * 正在施法的那个人。
     *
     * @return 玩家；窗口没开返回 null
     */
    @Nullable
    public static Player castingPlayer() {
        Cast cast = WINDOW.get();
        return cast == null ? null : cast.player();
    }

    /**
     * 窗口里这名玩家正在放的卷轴。
     *
     * @param player 玩家
     * @return 卷轴；窗口没开或者不是这个玩家返回 null
     */
    @Nullable
    public static ResolvedWeapon castingWeapon(@Nullable Entity player) {
        Cast cast = WINDOW.get();
        return cast != null && player != null && player == cast.player() ? cast.weapon() : null;
    }

    /**
     * 把卷轴记到弹体（或召唤物）身上。
     *
     * @param entity 实体
     * @param weapon 卷轴
     */
    public static void tagProjectile(Entity entity, ResolvedWeapon weapon) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", weapon.weaponId().toString());
        tag.putString("type", weapon.type());
        entity.getPersistentData().put(TAG_MAGIC, tag);
    }

    /**
     * 从弹体上读回卷轴。
     *
     * @param entity 实体
     * @return 卷轴；没记过返回 null
     */
    @Nullable
    public static ResolvedWeapon fromProjectile(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(TAG_MAGIC)) {
            return null;
        }
        CompoundTag tag = data.getCompound(TAG_MAGIC);
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("id"));
        String type = tag.getString("type");
        if (id == null || type.isEmpty()) {
            return null;
        }
        return ResolvedWeapon.of(WeaponKeys.PROVIDER_SWEETMAGIC, id, type);
    }

    /**
     * 这是不是甜蜜魔法打出来的伤害。
     *
     * @param source 伤害来源
     * @return 是返回 true
     */
    public static boolean isMagicDamage(DamageSource source) {
        if (source.getDirectEntity() instanceof AbstractMagicShot) {
            return true;
        }
        // 领域、持续伤害这些没有弹体，按伤害类型的命名空间认
        return source.typeHolder().unwrapKey()
                .map(key -> NAMESPACE.equals(key.location().getNamespace()))
                .orElse(false);
    }

    /**
     * 这次伤害该算在哪张卷轴上。
     *
     * @param player 施法者
     * @param source 伤害来源
     * @return 卷轴；认不出来返回 null
     */
    @Nullable
    public static ResolvedWeapon weaponFor(Player player, DamageSource source) {
        Entity direct = source.getDirectEntity();
        ResolvedWeapon tagged = fromProjectile(direct);
        if (tagged != null) {
            return tagged;
        }
        // 召唤物打出来的东西：标记记在召唤物身上，弹体自己没有
        if (direct instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
            tagged = fromProjectile(projectile.getOwner());
            if (tagged != null) {
                return tagged;
            }
        }
        ResolvedWeapon casting = castingWeapon(player);
        if (casting != null) {
            return casting;
        }
        // 最后才退回「法杖当前选中的那张」：这一条会在放完之后切卷轴时记错账，只当兜底
        return selectedMagic(player);
    }
}
