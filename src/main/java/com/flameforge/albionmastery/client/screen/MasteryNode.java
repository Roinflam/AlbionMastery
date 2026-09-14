package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.client.weapon.WeaponEntry;
import com.flameforge.albionmastery.client.weapon.WeaponSource;
import com.flameforge.albionmastery.client.weapon.WeaponTypeGroup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 专精界面上的一个节点。
 *
 * <p>坐标使用「图空间」——以中心节点为原点，不受平移与缩放影响；渲染时再统一换算成屏幕坐标。
 * 这样布局算法可以完全不关心视口状态。
 */
public final class MasteryNode {

    /**
     * 节点类型，同时也是它在树上的层级。
     */
    public enum Type {
        /** 中心：总览说明，不带等级。 */
        INFO,
        /** 第一层：来源模组，同时承载该来源的总体专精，带等级。 */
        SOURCE,
        /** 第二层：武器类型专精，带等级。 */
        TYPE,
        /** 第三层：具体武器专精，带等级。 */
        WEAPON
    }

    /** 节点类型。 */
    public final Type type;

    /** 图空间坐标。 */
    public final double graphX;

    /** 图空间坐标。 */
    public final double graphY;

    /** 图空间半径（六边形外接圆）。 */
    public final double radius;

    /** 关联来源，SOURCE 有值。 */
    @Nullable
    public final WeaponSource source;

    /** 关联类型分组，TYPE 与 WEAPON 有值。 */
    @Nullable
    public final WeaponTypeGroup typeGroup;

    /** 关联武器，仅 WEAPON 有值。 */
    @Nullable
    public final WeaponEntry weapon;

    /** 父节点，用于绘制连线；中心节点为 null。 */
    @Nullable
    public final MasteryNode parent;

    /** 出现动画延迟（毫秒），按层级与同层序号错开。 */
    public final float appearDelayMs;

    /** 悬停缩放插值，每帧向目标值逼近。 */
    public float hoverAnim;

    /**
     * 本帧缓存的等级，配合 {@link #cachedLevelStamp} 用。
     *
     * <p>一帧里连线、节点底板、光环、铭牌会各问一次这个节点的等级，每次都是一次
     * 字符串键的哈希查表；几千个节点全在视野里时这就是几万次查表。缓存到帧结束，一帧只查一次。
     */
    public int cachedLevel;

    /** {@link #cachedLevel} 属于哪一帧；和界面的帧号对不上就重新算。初始 -1 表示还没算过。 */
    public int cachedLevelStamp = -1;

    /** 子图标轮换的间隔（毫秒）。 */
    private static final long ICON_CYCLE_MS = 1600L;

    /**
     * 构造节点。
     *
     * @param type          节点类型
     * @param graphX        图空间 X
     * @param graphY        图空间 Y
     * @param radius        图空间半径
     * @param source        关联来源
     * @param typeGroup     关联类型分组
     * @param weapon        关联武器
     * @param parent        父节点
     * @param appearDelayMs 出现动画延迟
     */
    public MasteryNode(Type type, double graphX, double graphY, double radius,
                       @Nullable WeaponSource source, @Nullable WeaponTypeGroup typeGroup,
                       @Nullable WeaponEntry weapon, @Nullable MasteryNode parent,
                       float appearDelayMs) {
        this.type = type;
        this.graphX = graphX;
        this.graphY = graphY;
        this.radius = radius;
        this.source = source;
        this.typeGroup = typeGroup;
        this.weapon = weapon;
        this.parent = parent;
        this.appearDelayMs = appearDelayMs;
    }

    /**
     * 该节点是否带等级。
     *
     * @return 带等级返回 true
     */
    public boolean hasMastery() {
        return this.type != Type.INFO;
    }

    /**
     * 取得该节点在专精数据里对应的键。
     *
     * @return 对应的专精键；说明节点与来源节点返回 null
     */
    @Nullable
    public String dataKey() {
        return switch (this.type) {
            case SOURCE -> this.source == null ? null : this.source.overallKey();
            case TYPE -> this.typeGroup == null ? null : this.typeGroup.key();
            case WEAPON -> this.weapon == null ? null : this.weapon.key();
            default -> null;
        };
    }

    /**
     * 取得节点的显示名称。
     *
     * @return 显示名称
     */
    public Component displayName() {
        return switch (this.type) {
            case INFO -> Component.translatable("gui.albionmastery.node.info");
            case SOURCE -> this.source == null ? Component.empty() : this.source.displayName();
            case TYPE -> this.typeGroup == null ? Component.empty() : this.typeGroup.displayName();
            case WEAPON -> this.weapon == null ? Component.empty() : this.weapon.displayName();
        };
    }

    /**
     * 取得当前该显示的图标。
     *
     * <p>武器节点用自己的图标；上层节点自己没有图标，就在旗下武器的图标之间轮换，
     * 这样一眼能看出这个分支底下大概是些什么东西，比一个空格子有用得多。
     * 每个节点按自身键做了相位偏移，不会全屏一起跳。
     *
     * @param timeMs 当前时刻
     * @return 图标物品堆，没有可用图标时返回 {@link ItemStack#EMPTY}
     */
    public ItemStack iconAt(long timeMs) {
        WeaponEntry entry = iconEntryAt(timeMs);
        return entry == null ? ItemStack.EMPTY : entry.icon();
    }

    /**
     * 此刻该显示的图标来自哪件武器。
     *
     * <p>画图标的地方要看这件武器是贴图图标还是物品图标，所以给整个条目而不只是物品。
     *
     * @param timeMs 当前时刻
     * @return 武器条目；没有可显示的返回 null
     */
    @Nullable
    public WeaponEntry iconEntryAt(long timeMs) {
        return switch (this.type) {
            case WEAPON -> this.weapon;
            case TYPE -> this.typeGroup == null
                    ? null : cycle(this.typeGroup.weapons(), timeMs, this.typeGroup.key());
            case SOURCE -> this.source == null
                    ? null : cycle(this.source.allWeapons(), timeMs, this.source.id());
            default -> null;
        };
    }

    /**
     * 在一组武器的图标之间轮换。
     *
     * @param weapons 武器列表
     * @param timeMs  当前时刻
     * @param seed    相位种子，让不同节点错开跳动
     * @return 当前应显示的图标
     */
    @Nullable
    private static WeaponEntry cycle(List<WeaponEntry> weapons, long timeMs, String seed) {
        if (weapons.isEmpty()) {
            return null;
        }
        long step = timeMs / ICON_CYCLE_MS + Math.abs((long) seed.hashCode());
        return weapons.get((int) Math.floorMod(step, weapons.size()));
    }

    /**
     * 用于重建后恢复选中的稳定标识。
     *
     * @return 标识字符串
     */
    public String identity() {
        return switch (this.type) {
            case INFO -> "info";
            case SOURCE -> "source:" + (this.source == null ? "" : this.source.id());
            case TYPE -> "type:" + (this.typeGroup == null ? "" : this.typeGroup.key());
            case WEAPON -> "weapon:" + (this.weapon == null ? "" : this.weapon.key());
        };
    }
}
