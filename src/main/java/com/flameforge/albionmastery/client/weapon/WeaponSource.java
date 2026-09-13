package com.flameforge.albionmastery.client.weapon;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个武器来源（专精树第二层，纯展示节点）。
 *
 * <p>对应一个接入的武器体系，例如「TACZ 枪械」「TACZ 近战附属」「拔刀剑」。
 * 这一层<b>不参与任何加成计算</b>，只是把下面的内容按来源归拢。
 *
 * <p>它下面紧接着的是「总体」节点（{@link #overallKey()}），那个才带等级。
 */
public final class WeaponSource {

    /** 来源 ID，与 {@code IWeaponProvider.providerId()} 一致。 */
    private final String id;

    /** 总体专精键。 */
    private final String overallKey;

    /** 显示名称。 */
    private final Component displayName;

    /** 该来源下的全部武器类型分组。 */
    private final List<WeaponTypeGroup> types = new ArrayList<>();

    /** 扁平化后的全部武器，首次访问时构建。 */
    private List<WeaponEntry> flattened;

    /**
     * 构造来源。
     *
     * @param id          来源 ID
     * @param overallKey  总体专精键
     * @param displayName 显示名称
     */
    public WeaponSource(String id, String overallKey, Component displayName) {
        this.id = id;
        this.overallKey = overallKey;
        this.displayName = displayName;
    }

    /**
     * @return 来源 ID
     */
    public String id() {
        return this.id;
    }

    /**
     * @return 总体专精键
     */
    public String overallKey() {
        return this.overallKey;
    }

    /**
     * @return 显示名称
     */
    public Component displayName() {
        return this.displayName;
    }

    /**
     * @return 该来源下的类型分组列表
     */
    public List<WeaponTypeGroup> types() {
        return this.types;
    }

    /**
     * 取得该来源下所有类型的武器，扁平化后的列表。
     *
     * <p>结果会缓存下来，供来源与总体节点轮换子图标使用。
     *
     * @return 扁平化的武器列表
     */
    public List<WeaponEntry> allWeapons() {
        if (this.flattened == null) {
            List<WeaponEntry> all = new ArrayList<>();
            for (WeaponTypeGroup group : this.types) {
                all.addAll(group.weapons());
            }
            this.flattened = List.copyOf(all);
        }
        return this.flattened;
    }

    /**
     * @return 该来源下的武器总数
     */
    public int weaponCount() {
        int total = 0;
        for (WeaponTypeGroup group : this.types) {
            total += group.weapons().size();
        }
        return total;
    }
}
