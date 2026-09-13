package com.flameforge.albionmastery.mastery;

import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 单个玩家的全部专精数据。
 *
 * <p>三张带等级的表，对应专精树上三个有等级的层：
 * <ul>
 *     <li>{@code overalls}：来源 ID -&gt; 总体专精（例如「TACZ 全部枪械」）</li>
 *     <li>{@code types}：类型键 -&gt; 武器类型专精（步枪 / 狙击 / 近战 ……）</li>
 *     <li>{@code weapons}：武器键 -&gt; 单件武器专精</li>
 * </ul>
 *
 * <p>另外维护 {@code typeLevelSum} 缓存，记录每个类型下所有武器的等级之和。
 * 「同类型其它武器每级 +0.1%」这条加成需要遍历整个类型求和，放在伤害事件里现算太浪费，
 * 用增量维护的缓存把它降成 O(1)。每个武器条目会记住自己的类型键，
 * 读档后不依赖任何外部查询就能重建这份缓存。
 */
public class MasteryData {

    /** 武器键 -> 条目。 */
    private final Map<String, WeaponRecord> weapons = new HashMap<>();

    /** 类型键 -> 条目。 */
    private final Map<String, Entry> types = new HashMap<>();

    /** 来源 ID -> 条目。 */
    private final Map<String, Entry> overalls = new HashMap<>();

    /** 类型键 -> 该类型下所有武器等级之和（增量维护，不参与序列化）。 */
    private final Map<String, Integer> typeLevelSum = new HashMap<>();
    /** 类型键 -> 已选特化的完整键。没选过的类型不在表里，按制式处理。 */
    private final Map<String, String> specializations = new HashMap<>();

    /** 学习点：这名玩家获得专精经验时的倍率，1.0 是默认值。 */
    private double studyMultiplier = 1.0D;

    private final Set<String> dirtyWeapons = new HashSet<>();
    private final Set<String> dirtyTypes = new HashSet<>();
    private final Set<String> dirtyOveralls = new HashSet<>();

    /** 是否需要做一次全量同步。 */
    private boolean needFullSync = true;

    // ======================================================================
    // 读取
    // ======================================================================

    /**
     * 查询单件武器的总经验。
     *
     * @param weaponKey 武器键
     * @return 总经验
     */
    public long getWeaponXp(String weaponKey) {
        WeaponRecord record = this.weapons.get(weaponKey);
        return record == null ? 0L : record.xp;
    }

    /**
     * 查询单件武器的等级。
     *
     * @param weaponKey 武器键
     * @return 等级
     */
    public int getWeaponLevel(String weaponKey) {
        WeaponRecord record = this.weapons.get(weaponKey);
        return record == null ? 0 : record.level;
    }

    /**
     * 查询武器类型的总经验。
     *
     * @param typeKey 类型键
     * @return 总经验
     */
    public long getTypeXp(String typeKey) {
        Entry entry = this.types.get(typeKey);
        return entry == null ? 0L : entry.xp;
    }

    /**
     * 查询武器类型的等级。
     *
     * @param typeKey 类型键
     * @return 等级
     */
    public int getTypeLevel(String typeKey) {
        Entry entry = this.types.get(typeKey);
        return entry == null ? 0 : entry.level;
    }

    /**
     * 查询总体专精的总经验。
     *
     * @param overallKey 总体键
     * @return 总经验
     */
    public long getOverallXp(String overallKey) {
        Entry entry = this.overalls.get(overallKey);
        return entry == null ? 0L : entry.xp;
    }

    /**
     * 查询总体专精的等级。
     *
     * @param overallKey 总体键
     * @return 等级
     */
    public int getOverallLevel(String overallKey) {
        Entry entry = this.overalls.get(overallKey);
        return entry == null ? 0 : entry.level;
    }

    /**
     * @return 学习点倍率，默认 1.0
     */
    public double getStudyMultiplier() {
        return this.studyMultiplier;
    }

    /**
     * 设置学习点倍率。
     *
     * <p>会被夹到 0 到 100 之间：负数会让经验倒扣，无穷大和 NaN 会把存档写坏，
     * 这两种都不该因为一条打错的指令就发生。
     *
     * @param multiplier 倍率
     */
    public void setStudyMultiplier(double multiplier) {
        double value = Double.isNaN(multiplier) ? 1.0D : multiplier;
        this.studyMultiplier = Math.max(0.0D, Math.min(100.0D, value));
        this.needFullSync = true;
    }

    /**
     * 查询某个来源已选的特化。
     *
     * @param typeKey 类型键
     * @return 特化的完整键；没选过时返回 null
     */
    @Nullable
    public String getSpecialization(String typeKey) {
        return this.specializations.get(typeKey);
    }

    /**
     * 设置某个来源的特化。
     *
     * <p>不做解锁与冷却校验，那是 {@code SpecializationManager} 的事；
     * 这里只负责存下来并标记需要同步。
     *
     * @param typeKey 类型键
     * @param specKey 特化的完整键，传 null 表示清除
     */
    public void setSpecialization(String typeKey, @Nullable String specKey) {
        if (specKey == null) {
            this.specializations.remove(typeKey);
        } else {
            this.specializations.put(typeKey, specKey);
        }
        // 特化影响一整类武器，改动很少但影响面大，直接要求一次全量同步
        this.needFullSync = true;
    }

    /**
     * @return 只读的特化表视图
     */
    public Map<String, String> getSpecializationsView() {
        return Collections.unmodifiableMap(this.specializations);
    }

    /**
     * 用同步下来的特化表整体替换本地表，只在客户端使用。
     *
     * @param incoming 服务端下发的特化表
     */
    public void replaceSpecializations(Map<String, String> incoming) {
        this.specializations.clear();
        this.specializations.putAll(incoming);
    }

    /**
     * 查询某个类型下所有武器的等级之和。
     *
     * @param typeKey 类型键
     * @return 等级之和
     */
    public int getTypeLevelSum(String typeKey) {
        Integer sum = this.typeLevelSum.get(typeKey);
        return sum == null ? 0 : sum;
    }

    /**
     * @return 只读的武器表视图
     */
    public Map<String, WeaponRecord> getWeaponsView() {
        return Collections.unmodifiableMap(this.weapons);
    }

    /**
     * @return 只读的类型表视图
     */
    public Map<String, Entry> getTypesView() {
        return Collections.unmodifiableMap(this.types);
    }

    /**
     * @return 只读的总体表视图
     */
    public Map<String, Entry> getOverallsView() {
        return Collections.unmodifiableMap(this.overalls);
    }

    // ======================================================================
    // 写入
    // ======================================================================

    /**
     * 给一件武器增加经验，并同步维护类型等级之和缓存。
     *
     * @param weaponKey 武器键
     * @param typeKey   该武器所属的类型键
     * @param amount    增加的经验，非正数忽略
     * @param curve     等级曲线
     * @return 等级是否发生变化
     */
    public boolean addWeaponXp(String weaponKey, String typeKey, long amount, LevelCurve curve) {
        if (amount <= 0L) {
            return false;
        }
        WeaponRecord record = this.weapons.computeIfAbsent(weaponKey, key -> new WeaponRecord());
        // 类型可能因为枪包更新而变化，每次发经验都以最新的为准
        if (!typeKey.equals(record.typeKey)) {
            if (record.typeKey != null) {
                this.typeLevelSum.merge(record.typeKey, -record.level, Integer::sum);
            }
            record.typeKey = typeKey;
            this.typeLevelSum.merge(typeKey, record.level, Integer::sum);
        }

        long max = curve.totalXpToReach(curve.getMaxLevel());
        int oldLevel = record.level;
        record.xp = Math.min(max, record.xp + amount);
        record.level = curve.levelFor(record.xp);
        this.dirtyWeapons.add(weaponKey);

        if (record.level != oldLevel) {
            this.typeLevelSum.merge(typeKey, record.level - oldLevel, Integer::sum);
            return true;
        }
        return false;
    }

    /**
     * 给一个武器类型增加经验。
     *
     * @param typeKey 类型键
     * @param amount  增加的经验
     * @param curve   等级曲线
     * @return 等级是否发生变化
     */
    public boolean addTypeXp(String typeKey, long amount, LevelCurve curve) {
        return addEntryXp(this.types, this.dirtyTypes, typeKey, amount, curve);
    }

    /**
     * 给一个总体专精增加经验。
     *
     * @param overallKey 总体键
     * @param amount     增加的经验
     * @param curve      等级曲线
     * @return 等级是否发生变化
     */
    public boolean addOverallXp(String overallKey, long amount, LevelCurve curve) {
        return addEntryXp(this.overalls, this.dirtyOveralls, overallKey, amount, curve);
    }

    /**
     * 给一张普通条目表加经验的公共实现。
     *
     * @param target 目标表
     * @param dirty  对应的脏键集合
     * @param key    键
     * @param amount 经验
     * @param curve  等级曲线
     * @return 等级是否发生变化
     */
    private static boolean addEntryXp(Map<String, Entry> target, Set<String> dirty,
                                      String key, long amount, LevelCurve curve) {
        if (amount <= 0L) {
            return false;
        }
        Entry entry = target.computeIfAbsent(key, k -> new Entry());
        long max = curve.totalXpToReach(curve.getMaxLevel());
        int oldLevel = entry.level;
        entry.xp = Math.min(max, entry.xp + amount);
        entry.level = curve.levelFor(entry.xp);
        dirty.add(key);
        return entry.level != oldLevel;
    }

    /**
     * 直接设定一件武器的经验，用于指令与客户端接收同步包。
     *
     * @param weaponKey 武器键
     * @param typeKey   类型键，允许为 null（客户端同步时会带上）
     * @param xp        目标总经验
     * @param curve     等级曲线
     * @param markDirty 是否标记为待同步
     */
    public void setWeaponXp(String weaponKey, String typeKey, long xp, LevelCurve curve, boolean markDirty) {
        long max = curve.totalXpToReach(curve.getMaxLevel());
        long clamped = Math.max(0L, Math.min(max, xp));
        WeaponRecord record = this.weapons.computeIfAbsent(weaponKey, key -> new WeaponRecord());

        if (record.typeKey != null) {
            this.typeLevelSum.merge(record.typeKey, -record.level, Integer::sum);
        }
        if (typeKey != null) {
            record.typeKey = typeKey;
        }
        record.xp = clamped;
        record.level = curve.levelFor(clamped);
        if (record.typeKey != null) {
            this.typeLevelSum.merge(record.typeKey, record.level, Integer::sum);
        }
        if (markDirty) {
            this.dirtyWeapons.add(weaponKey);
        }
    }

    /**
     * 直接设定一个武器类型的经验。
     *
     * @param typeKey   类型键
     * @param xp        目标总经验
     * @param curve     等级曲线
     * @param markDirty 是否标记为待同步
     */
    public void setTypeXp(String typeKey, long xp, LevelCurve curve, boolean markDirty) {
        setEntryXp(this.types, this.dirtyTypes, typeKey, xp, curve, markDirty);
    }

    /**
     * 直接设定一个总体专精的经验。
     *
     * @param overallKey 总体键
     * @param xp         目标总经验
     * @param curve      等级曲线
     * @param markDirty  是否标记为待同步
     */
    public void setOverallXp(String overallKey, long xp, LevelCurve curve, boolean markDirty) {
        setEntryXp(this.overalls, this.dirtyOveralls, overallKey, xp, curve, markDirty);
    }

    private static void setEntryXp(Map<String, Entry> target, Set<String> dirty, String key,
                                   long xp, LevelCurve curve, boolean markDirty) {
        long max = curve.totalXpToReach(curve.getMaxLevel());
        long clamped = Math.max(0L, Math.min(max, xp));
        Entry entry = target.computeIfAbsent(key, k -> new Entry());
        entry.xp = clamped;
        entry.level = curve.levelFor(clamped);
        if (markDirty) {
            dirty.add(key);
        }
    }

    /**
     * 清空全部专精数据。
     */
    public void clear() {
        this.weapons.clear();
        this.types.clear();
        this.overalls.clear();
        this.typeLevelSum.clear();
        this.specializations.clear();
        this.studyMultiplier = 1.0D;
        clearDirty();
        this.needFullSync = true;
    }

    /**
     * 按当前等级曲线重算所有条目的等级与缓存。
     *
     * <p>三层各有各的曲线：类型与总体的升级所需经验分别是单件武器的若干倍，
     * 所以不能共用一条。
     *
     * @param weaponCurve  单件武器的曲线
     * @param typeCurve    武器类型的曲线
     * @param overallCurve 总体的曲线
     */
    public void recomputeLevels(LevelCurve weaponCurve, LevelCurve typeCurve, LevelCurve overallCurve) {
        this.typeLevelSum.clear();
        for (WeaponRecord record : this.weapons.values()) {
            record.level = weaponCurve.levelFor(record.xp);
            if (record.typeKey != null) {
                this.typeLevelSum.merge(record.typeKey, record.level, Integer::sum);
            }
        }
        for (Entry entry : this.types.values()) {
            entry.level = typeCurve.levelFor(entry.xp);
        }
        for (Entry entry : this.overalls.values()) {
            entry.level = overallCurve.levelFor(entry.xp);
        }
    }

    /**
     * 从另一份数据整体拷贝，用于玩家死亡重生时继承专精。
     *
     * @param other 源数据
     */
    public void copyFrom(MasteryData other) {
        this.weapons.clear();
        this.types.clear();
        this.overalls.clear();
        for (Map.Entry<String, WeaponRecord> mapEntry : other.weapons.entrySet()) {
            this.weapons.put(mapEntry.getKey(), mapEntry.getValue().copy());
        }
        for (Map.Entry<String, Entry> mapEntry : other.types.entrySet()) {
            this.types.put(mapEntry.getKey(), mapEntry.getValue().copy());
        }
        for (Map.Entry<String, Entry> mapEntry : other.overalls.entrySet()) {
            this.overalls.put(mapEntry.getKey(), mapEntry.getValue().copy());
        }
        this.typeLevelSum.clear();
        this.typeLevelSum.putAll(other.typeLevelSum);
        // 特化与学习点也要跟着走。漏了这两行的后果是：玩家一死，
        // 辛苦练满才选上的流派会被打回「制式」，管理员发的学习点也归 1，
        // 而且完全没有任何报错，只有玩家自己发现「我的加成怎么没了」
        this.specializations.clear();
        this.specializations.putAll(other.specializations);
        this.studyMultiplier = other.studyMultiplier;
        clearDirty();
        this.needFullSync = true;
    }

    // ======================================================================
    // 同步标记
    // ======================================================================

    /**
     * @return 是否需要全量同步
     */
    public boolean isNeedFullSync() {
        return this.needFullSync;
    }

    /**
     * 标记需要全量同步。
     */
    public void markFullSync() {
        this.needFullSync = true;
    }

    /**
     * @return 是否存在待同步的增量改动
     */
    public boolean hasDirty() {
        return !this.dirtyWeapons.isEmpty() || !this.dirtyTypes.isEmpty() || !this.dirtyOveralls.isEmpty();
    }

    /**
     * @return 待同步的武器键集合
     */
    public Set<String> getDirtyWeapons() {
        return this.dirtyWeapons;
    }

    /**
     * @return 待同步的类型键集合
     */
    public Set<String> getDirtyTypes() {
        return this.dirtyTypes;
    }

    /**
     * @return 待同步的总体键集合
     */
    public Set<String> getDirtyOveralls() {
        return this.dirtyOveralls;
    }

    /**
     * 清空同步标记。
     */
    public void clearDirty() {
        this.dirtyWeapons.clear();
        this.dirtyTypes.clear();
        this.dirtyOveralls.clear();
        this.needFullSync = false;
    }

    /**
     * 取出某件武器记录的类型键。
     *
     * @param weaponKey 武器键
     * @return 类型键；未记录时返回 null
     */
    public String getWeaponTypeKey(String weaponKey) {
        WeaponRecord record = this.weapons.get(weaponKey);
        return record == null ? null : record.typeKey;
    }

    // ======================================================================
    // 序列化
    // ======================================================================

    /**
     * 序列化为 NBT。
     *
     * <p>用 ListTag 而不是把键直接当 CompoundTag 的 key，是为了让键的字符集不受限制。
     *
     * @return 序列化结果
     */
    public CompoundTag serializeNBT() {
        CompoundTag root = new CompoundTag();

        ListTag weaponList = new ListTag();
        for (Map.Entry<String, WeaponRecord> mapEntry : this.weapons.entrySet()) {
            WeaponRecord record = mapEntry.getValue();
            if (record.xp <= 0L) {
                continue;
            }
            CompoundTag tag = new CompoundTag();
            tag.putString("K", mapEntry.getKey());
            tag.putLong("X", record.xp);
            if (record.typeKey != null) {
                tag.putString("T", record.typeKey);
            }
            weaponList.add(tag);
        }
        root.put("Weapons", weaponList);
        root.put("Types", writeEntries(this.types));
        root.put("Overalls", writeEntries(this.overalls));

        ListTag specList = new ListTag();
        for (Map.Entry<String, String> mapEntry : this.specializations.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("S", mapEntry.getKey());
            tag.putString("V", mapEntry.getValue());
            specList.add(tag);
        }
        root.put("Specs", specList);
        // 默认值不写盘，省得给每个玩家的存档都塞一个 1.0
        if (this.studyMultiplier != 1.0D) {
            root.putDouble("Study", this.studyMultiplier);
        }
        return root;
    }

    /**
     * 从 NBT 反序列化。本方法不计算等级，调用方必须紧接着调用
     * {@link #recomputeLevels(LevelCurve)}。
     *
     * @param root 源 NBT
     */
    public void deserializeNBT(CompoundTag root) {
        this.weapons.clear();
        this.types.clear();
        this.overalls.clear();
        this.typeLevelSum.clear();
        this.specializations.clear();
        // 老存档没有这个键，getDouble 返回 0，要显式回落到 1.0
        this.studyMultiplier = root.contains("Study")
                ? Math.max(0.0D, Math.min(100.0D, root.getDouble("Study")))
                : 1.0D;

        ListTag weaponList = root.getList("Weapons", Tag.TAG_COMPOUND);
        for (int i = 0; i < weaponList.size(); i++) {
            CompoundTag tag = weaponList.getCompound(i);
            String key = tag.getString("K");
            if (!WeaponKeys.isValidWeaponKey(key)) {
                continue;
            }
            long xp = Math.max(0L, tag.getLong("X"));
            if (xp <= 0L) {
                continue;
            }
            WeaponRecord record = new WeaponRecord();
            record.xp = xp;
            String typeKey = tag.getString("T");
            record.typeKey = WeaponKeys.isValidTypeKey(typeKey) ? typeKey : null;
            this.weapons.put(key, record);
        }

        readEntries(root.getList("Types", Tag.TAG_COMPOUND), this.types, true);
        readEntries(root.getList("Overalls", Tag.TAG_COMPOUND), this.overalls, false);

        // 老存档没有 Specs 这一段，getList 返回空表，等于所有来源都是制式，不需要额外迁移
        ListTag specList = root.getList("Specs", Tag.TAG_COMPOUND);
        for (int i = 0; i < specList.size(); i++) {
            CompoundTag tag = specList.getCompound(i);
            String typeKey = tag.getString("S");
            String value = tag.getString("V");
            // 两种脏数据都要挡：流派可能在版本更新里被删掉；
            // 早期版本是按「来源」存的，那些键在这里对不上来源，一并丢弃
            Specialization spec = Specialization.byKey(value);
            if (spec != null && WeaponKeys.isValidTypeKey(typeKey)
                    && spec.sourceId().equals(WeaponKeys.providerOf(typeKey))) {
                this.specializations.put(typeKey, value);
            }
        }
        this.needFullSync = true;
    }

    private static ListTag writeEntries(Map<String, Entry> source) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Entry> mapEntry : source.entrySet()) {
            if (mapEntry.getValue().xp <= 0L) {
                continue;
            }
            CompoundTag tag = new CompoundTag();
            tag.putString("K", mapEntry.getKey());
            tag.putLong("X", mapEntry.getValue().xp);
            list.add(tag);
        }
        return list;
    }

    private static void readEntries(ListTag list, Map<String, Entry> target, boolean typeKey) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            String key = tag.getString("K");
            boolean valid = typeKey ? WeaponKeys.isValidTypeKey(key) : WeaponKeys.isValidOverallKey(key);
            if (!valid) {
                continue;
            }
            long xp = Math.max(0L, tag.getLong("X"));
            if (xp <= 0L) {
                continue;
            }
            Entry entry = new Entry();
            entry.xp = xp;
            target.put(key, entry);
        }
    }

    /**
     * 一条普通专精记录：总经验 + 缓存的等级。
     */
    public static class Entry {

        /** 累计总经验。 */
        public long xp;

        /** 由总经验换算出的等级，作为缓存存在，避免热路径反复二分。 */
        public int level;

        /**
         * @return 一份深拷贝
         */
        public Entry copy() {
            Entry copy = new Entry();
            copy.xp = this.xp;
            copy.level = this.level;
            return copy;
        }
    }

    /**
     * 单件武器的记录，比普通条目多记一个所属类型键。
     *
     * <p>记住类型是为了让 {@code typeLevelSum} 缓存能在读档后自行重建，
     * 不必反过来去查枪械索引——那份数据在读档时不一定已经加载好。
     */
    public static final class WeaponRecord extends Entry {

        /** 所属类型键，老存档可能为 null。 */
        public String typeKey;

        @Override
        public WeaponRecord copy() {
            WeaponRecord copy = new WeaponRecord();
            copy.xp = this.xp;
            copy.level = this.level;
            copy.typeKey = this.typeKey;
            return copy;
        }
    }
}
