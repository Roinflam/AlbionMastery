package com.flameforge.albionmastery.weapon;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * 各级专精节点的唯一键。
 *
 * <p>专精树一共五层，其中三层带等级：
 * <pre>
 *   总览（中心，无等级）
 *     └── 来源模组      —— 纯展示，无等级
 *           └── 总体    —— 有等级，键就是来源 ID，例如 tacz_gun
 *                 └── 武器类型  —— 有等级，例如 tacz_gun#rifle
 *                       └── 具体武器 —— 有等级，例如 tacz_gun/tacz:ak47
 * </pre>
 *
 * <p>三种键用不同的分隔符区分，解析时不会混淆，也能直接当 NBT 里的持久化标识：
 * <ul>
 *     <li>总体键：只有来源 ID，不含 {@code #} 与 {@code /}</li>
 *     <li>类型键：{@code <来源>#<类型>}</li>
 *     <li>武器键：{@code <来源>/<命名空间>:<路径>}</li>
 * </ul>
 */
public final class WeaponKeys {

    /** 来源 ID：TACZ 枪械（含所有枪包）。 */
    public static final String PROVIDER_TACZ_GUN = "tacz_gun";

    /** 来源 ID：lrtactical 近战武器（含所有刀包）。 */
    public static final String PROVIDER_LRTACTICAL_MELEE = "lrtactical_melee";

    /** 来源 ID：拔刀剑（含所有附属刀包）。 */
    public static final String PROVIDER_SLASHBLADE = "slashblade";

    /** 铁魔法（Iron's Spells 'n Spellbooks）：单件是法术，大类是学派。 */
    public static final String PROVIDER_IRONS_SPELLS = "irons_spellbooks";

    /** 新生魔艺（Ars Nouveau）：单件是符文，大类是学派 / 形态 / 增幅。 */
    public static final String PROVIDER_ARS_NOUVEAU = "ars_nouveau";

    /** 通用近战武器：所有模组里继承原版 SwordItem / AxeItem / TridentItem 的物品，大类是剑 / 斧 / 三叉戟。兜底来源，永远可用。 */
    public static final String PROVIDER_VANILLA_MELEE = "vanilla_melee";

    /** 通用弓弩：所有继承原版 BowItem / CrossbowItem 的物品，大类是弓 / 弩。兜底来源，永远可用。 */
    public static final String PROVIDER_BOW = "bow";

    /** 第二流派槽在选择表里的键后缀：类型键 + 这个后缀。存盘和网络包结构因此都不用改。 */
    public static final String SPEC_SLOT2_SUFFIX = "@2";

    /** 流派槽数。 */
    public static final int SPEC_SLOTS = 2;

    /**
     * 某个类型的某个流派槽在选择表里的键。
     *
     * @param typeKey 类型键
     * @param slot    槽位，1 或 2
     * @return 选择表的键
     */
    public static String specSlotKey(String typeKey, int slot) {
        return slot >= 2 ? typeKey + SPEC_SLOT2_SUFFIX : typeKey;
    }

    /**
     * 从选择表的键还原出类型键（去掉槽位后缀）。
     *
     * @param slotKey 选择表的键
     * @return 类型键
     */
    public static String specBaseKey(String slotKey) {
        return slotKey.endsWith(SPEC_SLOT2_SUFFIX)
                ? slotKey.substring(0, slotKey.length() - SPEC_SLOT2_SUFFIX.length()) : slotKey;
    }

    /** 类型未知时的兜底值。 */
    public static final String TYPE_UNKNOWN = "unknown";

    /** 来源与武器 ID 的分隔符。 */
    private static final char WEAPON_SEPARATOR = '/';

    /** 来源与类型的分隔符。 */
    private static final char TYPE_SEPARATOR = '#';

    private WeaponKeys() {
    }

    /**
     * 生成总体专精键。
     *
     * @param providerId 来源 ID
     * @return 总体键，形如 {@code tacz_gun}
     */
    public static String overallKey(String providerId) {
        return providerId;
    }

    /**
     * 生成武器类型专精键。
     *
     * @param providerId 来源 ID
     * @param type       类型标识，例如 {@code rifle}
     * @return 类型键，形如 {@code tacz_gun#rifle}
     */
    public static String typeKey(String providerId, String type) {
        String safe = type == null || type.isBlank() ? TYPE_UNKNOWN : type;
        return providerId + TYPE_SEPARATOR + safe;
    }

    /**
     * 生成武器专精键。
     *
     * @param providerId 来源 ID
     * @param weaponId   武器在其来源体系内的 ID
     * @return 武器键，形如 {@code tacz_gun/tacz:ak47}
     */
    public static String weaponKey(String providerId, ResourceLocation weaponId) {
        return providerId + WEAPON_SEPARATOR + weaponId;
    }

    /**
     * 从任意键里取出来源 ID。
     *
     * @param key 任意一种键
     * @return 来源 ID；键非法时返回 null
     */
    @Nullable
    public static String providerOf(@Nullable String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        int slash = key.indexOf(WEAPON_SEPARATOR);
        int hash = key.indexOf(TYPE_SEPARATOR);
        if (slash > 0) {
            return key.substring(0, slash);
        }
        if (hash > 0) {
            return key.substring(0, hash);
        }
        return key;
    }

    /**
     * 从类型键里取出类型标识。
     *
     * @param typeKey 类型键
     * @return 类型标识；键非法时返回 {@link #TYPE_UNKNOWN}
     */
    public static String typeOf(String typeKey) {
        int hash = typeKey.indexOf(TYPE_SEPARATOR);
        if (hash < 0 || hash + 1 >= typeKey.length()) {
            return TYPE_UNKNOWN;
        }
        return typeKey.substring(hash + 1);
    }

    /**
     * 从武器键里取出枪包 / 刀包的命名空间。
     *
     * @param weaponKey 武器键
     * @return 命名空间；键非法时返回空串
     */
    public static String packOf(String weaponKey) {
        int slash = weaponKey.indexOf(WEAPON_SEPARATOR);
        int colon = weaponKey.indexOf(':');
        if (slash < 0 || colon <= slash + 1) {
            return "";
        }
        return weaponKey.substring(slash + 1, colon);
    }

    /**
     * 校验武器键。
     *
     * @param key 待校验的键
     * @return 合法返回 true
     */
    public static boolean isValidWeaponKey(@Nullable String key) {
        if (!basicValid(key)) {
            return false;
        }
        int slash = key.indexOf(WEAPON_SEPARATOR);
        int colon = key.indexOf(':');
        return slash > 0 && colon > slash + 1 && colon + 1 < key.length();
    }

    /**
     * 校验类型键。
     *
     * @param key 待校验的键
     * @return 合法返回 true
     */
    public static boolean isValidTypeKey(@Nullable String key) {
        if (!basicValid(key)) {
            return false;
        }
        int hash = key.indexOf(TYPE_SEPARATOR);
        return hash > 0 && hash + 1 < key.length() && key.indexOf(WEAPON_SEPARATOR) < 0;
    }

    /**
     * 校验总体键。
     *
     * @param key 待校验的键
     * @return 合法返回 true
     */
    public static boolean isValidOverallKey(@Nullable String key) {
        return basicValid(key)
                && key.indexOf(WEAPON_SEPARATOR) < 0
                && key.indexOf(TYPE_SEPARATOR) < 0
                && key.indexOf(':') < 0;
    }

    /**
     * 通用的长度与空值校验，防止脏数据进入内存。
     *
     * @param key 待校验的键
     * @return 通过返回 true
     */
    private static boolean basicValid(@Nullable String key) {
        return key != null && !key.isEmpty() && key.length() <= 256;
    }
}
