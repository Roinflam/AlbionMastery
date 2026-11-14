package com.flameforge.albionmastery.mastery;

import com.flameforge.albionmastery.config.SpecRules;
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

    /** 全部武器等级之和（增量维护，不参与序列化）。段位按它现算，热路径上不再遍历武器表。 */
    private long totalWeaponLevels;

    /** 类型键 -> 该类型下满级武器的数量（增量维护，不参与序列化）。 */
    private final Map<String, Integer> typeMaxedCount = new HashMap<>();

    /**
     * 特化键 -> 普通版等级（Lv1 起）。普通版按来源共用，特化键里本来就带着来源，不用再分一层。
     * 0 级的不在表里。
     */
    private final Map<String, Integer> specLevels = new HashMap<>();

    /** 类型键 -> （特化键 -> 突破等级 ★1 起）。突破按类型分开。0 级的不在表里。 */
    private final Map<String, Map<String, Integer>> breakthroughs = new HashMap<>();

    /** 当前的特化存档版本。0 = 改版前（只有两个槽、没有等级）的存档，登录时迁移一次。 */
    public static final int SPEC_DATA_VERSION = 1;

    /** 这份存档的特化版本，见 {@link #SPEC_DATA_VERSION}。 */
    private int specDataVersion;

    /**
     * 点数池 -> 上次重置的时刻（毫秒，系统时间）。改装点按来源一池（键是来源 ID），突破点按类型一池（键是类型键）。
     * 冷却按池各记各的：没重置过的池没有冷却，所以改版后每一池的第一次重置都是免费的。
     */
    private final Map<String, Long> respecTimes = new HashMap<>();

    /** 学习点：这名玩家获得专精经验时的倍率，1.0 是默认值。 */
    private double studyMultiplier = 1.0D;

    /**
     * 已经给这名玩家播过晋升的段位。段位本身由武器等级现算，这里只记「播到哪了」，
     * 免得每次登录、重生都把晋升动画再放一遍。-1 = 从没评定过（老存档、新玩家）。
     */
    private int rankSeen = -1;

    /**
     * 上次段位判定以来，有没有打怪练出来的武器升级（不存盘）。
     * 段位判定靠它决定晋升要不要通告全服：经验最后由哪一次同步冲刷出去说明不了来源，
     * 换维度、切特化都会先触发一次全量同步，把打怪攒下的改动一并带走。
     */
    private boolean gameplayWeaponLevelUp;

    private final Set<String> dirtyWeapons = new HashSet<>();
    private final Set<String> dirtyTypes = new HashSet<>();
    private final Set<String> dirtyOveralls = new HashSet<>();

    /** 是否需要做一次全量同步。 */
    private boolean needFullSync = true;

    // 读取

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
     * @return 已经播过晋升的段位，-1 表示从没评定过
     */
    public int getRankSeen() {
        return this.rankSeen;
    }

    /**
     * 记下已经播过晋升的段位。
     *
     * @param rank 段位，负数按 -1 处理
     */
    public void setRankSeen(int rank) {
        this.rankSeen = Math.max(-1, rank);
    }

    /**
     * 记一笔：有武器靠打怪升了级。
     */
    public void markGameplayWeaponLevelUp() {
        this.gameplayWeaponLevelUp = true;
    }

    /**
     * 取出并清掉「有武器靠打怪升了级」的标记。
     *
     * @return 上次取出以来有没有
     */
    public boolean consumeGameplayWeaponLevelUp() {
        boolean value = this.gameplayWeaponLevelUp;
        this.gameplayWeaponLevelUp = false;
        return value;
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
        setSpecialization(typeKey, 1, specKey);
    }

    /**
     * 某个类型某个槽位选的特化。
     *
     * @param typeKey 类型键
     * @param slot    槽位，1 或 2
     * @return 特化键；没选返回 null
     */
    @Nullable
    public String getSpecialization(String typeKey, int slot) {
        return this.specializations.get(WeaponKeys.specSlotKey(typeKey, slot));
    }

    /**
     * 设置某个类型某个槽位的特化。
     *
     * @param typeKey 类型键
     * @param slot    槽位，1 或 2
     * @param specKey 特化键；null 表示清掉
     */
    public void setSpecialization(String typeKey, int slot, @Nullable String specKey) {
        String key = WeaponKeys.specSlotKey(typeKey, slot);
        if (specKey == null) {
            this.specializations.remove(key);
        } else {
            this.specializations.put(key, specKey);
        }
        // 特化影响一整类武器，改动很少但影响面大，直接要求一次全量同步
        this.needFullSync = true;
    }

    /**
     * 这件武器有没有自己的独立配置（外圈）。
     *
     * <p>独立配置和类型的通用配置存在同一张表里，键分别是武器键和类型键（第二栏都带 {@code @2}），
     * 两种键的格式不一样，不会撞。独立配置里「什么都不选」存的是制式特化的键而不是删掉，
     * 所以两栏都空的独立配置也认得出来。
     *
     * @param weaponKey 武器键，可为 null
     * @return 有返回 true
     */
    public boolean hasWeaponOverride(@Nullable String weaponKey) {
        if (weaponKey == null) {
            return false;
        }
        for (int slot = 1; slot <= WeaponKeys.SPEC_SLOTS; slot++) {
            if (this.specializations.containsKey(WeaponKeys.specSlotKey(weaponKey, slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这件武器实际用哪一份配置：有独立配置就整套用它自己的（外圈），没有就用所属类型的通用配置（中圈）。
     *
     * <p>整套覆盖而不是按栏混用：两栏分别来自两处的话，很容易拼出同部位冲突，玩家也看不明白。
     *
     * @param typeKey   类型键
     * @param weaponKey 武器键，可为 null
     * @return 配置键：武器键或类型键
     */
    public String specConfigKey(String typeKey, @Nullable String weaponKey) {
        return hasWeaponOverride(weaponKey) ? weaponKey : typeKey;
    }

    /**
     * 删掉这件武器的独立配置，回到跟随类型的通用配置。
     *
     * @param weaponKey 武器键
     * @return 原来有独立配置返回 true
     */
    public boolean clearWeaponOverride(String weaponKey) {
        boolean removed = false;
        for (int slot = 1; slot <= WeaponKeys.SPEC_SLOTS; slot++) {
            removed |= this.specializations.remove(WeaponKeys.specSlotKey(weaponKey, slot)) != null;
        }
        if (removed) {
            this.needFullSync = true;
        }
        return removed;
    }

    /**
     * @param specKey 特化键
     * @return 普通版等级，没升过是 0
     */
    public int getSpecLevel(String specKey) {
        Integer level = this.specLevels.get(specKey);
        return level == null ? 0 : level;
    }

    /**
     * 设置普通版等级。不做点数校验，那是 {@code SpecializationManager} 的事。
     *
     * @param specKey 特化键
     * @param level   等级，0 及以下表示清掉
     */
    public void setSpecLevel(String specKey, int level) {
        if (level <= 0) {
            this.specLevels.remove(specKey);
        } else {
            this.specLevels.put(specKey, level);
        }
        this.needFullSync = true;
    }

    /**
     * @param typeKey 类型键
     * @param specKey 特化键
     * @return 这个类型里的突破等级，没突破过是 0
     */
    public int getBreakthrough(String typeKey, String specKey) {
        Map<String, Integer> perType = this.breakthroughs.get(typeKey);
        Integer star = perType == null ? null : perType.get(specKey);
        return star == null ? 0 : star;
    }

    /**
     * 设置某个类型里的突破等级。
     *
     * @param typeKey 类型键
     * @param specKey 特化键
     * @param star    突破等级，0 及以下表示清掉
     */
    public void setBreakthrough(String typeKey, String specKey, int star) {
        if (star <= 0) {
            Map<String, Integer> perType = this.breakthroughs.get(typeKey);
            if (perType != null) {
                perType.remove(specKey);
                if (perType.isEmpty()) {
                    this.breakthroughs.remove(typeKey);
                }
            }
        } else {
            this.breakthroughs.computeIfAbsent(typeKey, key -> new HashMap<>()).put(specKey, star);
        }
        this.needFullSync = true;
    }

    /**
     * 清掉某个来源的全部普通版等级，连带这个来源所有类型里的突破（突破依赖普通版满级）。
     *
     * @param providerId 来源 ID
     * @return 原来有没有东西被清掉
     */
    public boolean clearSourceProgress(String providerId) {
        boolean changed = this.specLevels.keySet().removeIf(key -> {
            Specialization spec = Specialization.byKey(key);
            return spec == null || providerId.equals(spec.sourceId());
        });
        changed |= this.breakthroughs.keySet().removeIf(typeKey -> providerId.equals(WeaponKeys.providerOf(typeKey)));
        if (changed) {
            this.needFullSync = true;
        }
        return changed;
    }

    /**
     * 清掉某个类型的全部突破。
     *
     * @param typeKey 类型键
     * @return 原来有没有
     */
    public boolean clearTypeBreakthroughs(String typeKey) {
        boolean changed = this.breakthroughs.remove(typeKey) != null;
        if (changed) {
            this.needFullSync = true;
        }
        return changed;
    }

    /**
     * @return 只读的普通版等级表
     */
    public Map<String, Integer> getSpecLevelsView() {
        return Collections.unmodifiableMap(this.specLevels);
    }

    /**
     * @return 只读的突破表（内层表也别改）
     */
    public Map<String, Map<String, Integer>> getBreakthroughsView() {
        return Collections.unmodifiableMap(this.breakthroughs);
    }

    /**
     * 用同步下来的等级表、突破表和退点时刻整体替换本地数据，只在客户端使用。
     *
     * @param levels        普通版等级
     * @param breakthroughs 突破
     * @param respecTimes   各池上次重置的时刻（已经换算成本机时间）
     */
    public void replaceSpecProgress(Map<String, Integer> levels, Map<String, Map<String, Integer>> breakthroughs,
                                    Map<String, Long> respecTimes) {
        this.specLevels.clear();
        this.specLevels.putAll(levels);
        this.breakthroughs.clear();
        breakthroughs.forEach((type, perType) -> this.breakthroughs.put(type, new HashMap<>(perType)));
        this.respecTimes.clear();
        this.respecTimes.putAll(respecTimes);
        this.specDataVersion = SPEC_DATA_VERSION;
    }

    /** @return 这份存档的特化版本 */
    public int getSpecDataVersion() {
        return this.specDataVersion;
    }

    /** @param version 特化版本 */
    public void setSpecDataVersion(int version) {
        this.specDataVersion = version;
    }

    /**
     * @param pool 点数池：来源 ID（改装点）或类型键（突破点）
     * @return 这一池上次重置的时刻（毫秒），0 = 没重置过
     */
    public long getRespecAt(String pool) {
        Long at = this.respecTimes.get(pool);
        return at == null ? 0L : at;
    }

    /**
     * @param pool   点数池
     * @param millis 重置的时刻（毫秒）；0 及以下表示清掉
     */
    public void setRespecAt(String pool, long millis) {
        if (millis <= 0L) {
            this.respecTimes.remove(pool);
        } else {
            this.respecTimes.put(pool, millis);
        }
        this.needFullSync = true;
    }

    /**
     * 清掉全部退点冷却（管理指令用）。
     *
     * @return 原来有没有
     */
    public boolean clearRespecTimes() {
        boolean had = !this.respecTimes.isEmpty();
        this.respecTimes.clear();
        this.needFullSync = true;
        return had;
    }

    /** @return 只读的退点时刻表 */
    public Map<String, Long> getRespecTimesView() {
        return Collections.unmodifiableMap(this.respecTimes);
    }

    /**
     * @return 全部武器等级之和（段位就按它算）
     */
    public long getTotalWeaponLevels() {
        return this.totalWeaponLevels;
    }

    /**
     * @param typeKey 类型键
     * @return 这个类型下满级武器的数量
     */
    public int getMaxedWeaponCount(String typeKey) {
        Integer count = this.typeMaxedCount.get(typeKey);
        return count == null ? 0 : count;
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

    // 写入

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
        unindex(record);
        // 类型可能因为枪包更新而变化，每次发经验都以最新的为准
        record.typeKey = typeKey;

        long max = curve.totalXpToReach(curve.getMaxLevel());
        int oldLevel = record.level;
        record.xp = addCapped(record.xp, amount, max);
        record.level = curve.levelFor(record.xp);
        record.maxed = record.level >= curve.getMaxLevel();
        index(record);
        this.dirtyWeapons.add(weaponKey);
        return record.level != oldLevel;
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
        entry.xp = addCapped(entry.xp, amount, max);
        entry.level = curve.levelFor(entry.xp);
        dirty.add(key);
        return entry.level != oldLevel;
    }

    /**
     * 加经验并夹到上限。
     *
     * <ul>
     *     <li>饱和加法：曲线顶到 {@code Long.MAX_VALUE} 时，满级再加一点会溢出成负数，
     *         负数的条目存盘时会被当成空条目丢掉，等于把进度清零。</li>
     *     <li>已有经验比上限还高（服主把等级上限或曲线调低了）时原样保留，不往下截：
     *         调低之后又调回去，进度还在。等级照样按新上限算。</li>
     * </ul>
     */
    private static long addCapped(long current, long amount, long max) {
        if (current >= max) {
            return current;
        }
        long sum = current + amount;
        if (sum < current) {
            sum = Long.MAX_VALUE;
        }
        return Math.min(max, sum);
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

        unindex(record);
        if (typeKey != null) {
            record.typeKey = typeKey;
        }
        record.xp = clamped;
        record.level = curve.levelFor(clamped);
        record.maxed = record.level >= curve.getMaxLevel();
        index(record);
        if (markDirty) {
            this.dirtyWeapons.add(weaponKey);
        }
    }

    /**
     * 把一件武器从缓存里摘掉（改等级 / 改类型之前调）。
     */
    private void unindex(WeaponRecord record) {
        this.totalWeaponLevels -= record.level;
        if (record.typeKey != null) {
            this.typeLevelSum.merge(record.typeKey, -record.level, Integer::sum);
            if (record.maxed) {
                this.typeMaxedCount.merge(record.typeKey, -1, Integer::sum);
            }
        }
    }

    /**
     * 把一件武器记进缓存（改完等级 / 类型之后调）。
     */
    private void index(WeaponRecord record) {
        this.totalWeaponLevels += record.level;
        if (record.typeKey != null) {
            this.typeLevelSum.merge(record.typeKey, record.level, Integer::sum);
            if (record.maxed) {
                this.typeMaxedCount.merge(record.typeKey, 1, Integer::sum);
            }
        }
    }

    /**
     * 按武器表现有的等级重建全部缓存。
     */
    private void rebuildIndexes() {
        this.typeLevelSum.clear();
        this.typeMaxedCount.clear();
        this.totalWeaponLevels = 0L;
        for (WeaponRecord record : this.weapons.values()) {
            index(record);
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
        this.typeMaxedCount.clear();
        this.totalWeaponLevels = 0L;
        this.specializations.clear();
        this.specLevels.clear();
        this.breakthroughs.clear();
        // 清空后就是一份新版存档，不用再迁移；退点冷却跟着清
        this.specDataVersion = SPEC_DATA_VERSION;
        this.respecTimes.clear();
        this.studyMultiplier = 1.0D;
        // 清空后段位归零；记成 0 而不是 -1，重新练上去时播的是「晋升」而不是「评定」
        this.rankSeen = 0;
        this.gameplayWeaponLevelUp = false;
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
        for (WeaponRecord record : this.weapons.values()) {
            record.level = weaponCurve.levelFor(record.xp);
            record.maxed = record.level >= weaponCurve.getMaxLevel();
        }
        rebuildIndexes();
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
        rebuildIndexes();
        // 特化与学习点也要跟着走。漏了这两行的后果是：玩家一死，
        // 辛苦练满才选上的特化会被打回「制式」，管理员发的学习点也归 1，
        // 而且完全没有任何报错，只有玩家自己发现「我的加成怎么没了」
        this.specializations.clear();
        this.specializations.putAll(other.specializations);
        // 特化等级、突破、退点状态同样要跟着走，漏了的话死一次点数就全白花了
        this.specLevels.clear();
        this.specLevels.putAll(other.specLevels);
        this.breakthroughs.clear();
        other.breakthroughs.forEach((type, perType) -> this.breakthroughs.put(type, new HashMap<>(perType)));
        this.specDataVersion = other.specDataVersion;
        this.respecTimes.clear();
        this.respecTimes.putAll(other.respecTimes);
        this.studyMultiplier = other.studyMultiplier;
        // 漏拷的话死一次就回到「从没评定过」，重生时会把段位评定动画再放一遍
        this.rankSeen = other.rankSeen;
        // 死亡那一刻刚打出来的升级还没判定过段位，跟着搬过去，重生后照样算打怪晋升
        this.gameplayWeaponLevelUp = other.gameplayWeaponLevelUp;
        clearDirty();
        this.needFullSync = true;
    }

    // 同步标记

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

    // 序列化

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

        ListTag levelList = new ListTag();
        for (Map.Entry<String, Integer> mapEntry : this.specLevels.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("S", mapEntry.getKey());
            tag.putInt("L", mapEntry.getValue());
            levelList.add(tag);
        }
        root.put("SpecLv", levelList);
        ListTag breakList = new ListTag();
        for (Map.Entry<String, Map<String, Integer>> perType : this.breakthroughs.entrySet()) {
            for (Map.Entry<String, Integer> mapEntry : perType.getValue().entrySet()) {
                CompoundTag tag = new CompoundTag();
                tag.putString("T", perType.getKey());
                tag.putString("S", mapEntry.getKey());
                tag.putInt("L", mapEntry.getValue());
                breakList.add(tag);
            }
        }
        root.put("SpecBt", breakList);
        root.putInt("SpecVer", this.specDataVersion);
        ListTag respecList = new ListTag();
        for (Map.Entry<String, Long> mapEntry : this.respecTimes.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("K", mapEntry.getKey());
            tag.putLong("T", mapEntry.getValue());
            respecList.add(tag);
        }
        root.put("Respec", respecList);
        // 默认值不写盘，省得给每个玩家的存档都塞一个 1.0
        if (this.studyMultiplier != 1.0D) {
            root.putDouble("Study", this.studyMultiplier);
        }
        if (this.rankSeen >= 0) {
            root.putInt("RankSeen", this.rankSeen);
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
        this.typeMaxedCount.clear();
        this.totalWeaponLevels = 0L;
        this.specializations.clear();
        this.specLevels.clear();
        this.breakthroughs.clear();
        // 改版前的存档没有 SpecVer：读成 0，登录时按老规则迁移一次
        this.specDataVersion = root.contains("SpecVer") ? Math.max(0, root.getInt("SpecVer")) : 0;
        this.respecTimes.clear();
        ListTag respecList = root.getList("Respec", Tag.TAG_COMPOUND);
        for (int i = 0; i < respecList.size(); i++) {
            CompoundTag tag = respecList.getCompound(i);
            String pool = tag.getString("K");
            long at = tag.getLong("T");
            if (at > 0L && (WeaponKeys.isValidTypeKey(pool) || WeaponKeys.isValidOverallKey(pool))) {
                this.respecTimes.put(pool, at);
            }
        }
        // 老存档没有这个键，getDouble 返回 0，要显式回落到 1.0
        double study = root.contains("Study") ? root.getDouble("Study") : 1.0D;
        // NaN 过不了 min / max 的夹取，得单独挡：坏掉的存档里读出 NaN 会让每次只发 1 点经验
        this.studyMultiplier = Double.isNaN(study) ? 1.0D : Math.max(0.0D, Math.min(100.0D, study));
        // 老存档没有这个键：按「从没评定过」处理，第一次登录时补一次段位评定
        this.rankSeen = root.contains("RankSeen") ? Math.max(-1, root.getInt("RankSeen")) : -1;

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
            // 两种脏数据都要挡：特化可能在版本更新里被删掉；
            // 早期版本是按「来源」存的，那些键在这里对不上来源，一并丢弃
            // 第二槽的键带后缀，校验时先去掉
            // 键可以是类型键（中圈的通用配置），也可以是武器键（外圈的独立配置）
            String baseKey = WeaponKeys.specBaseKey(typeKey);
            Specialization spec = Specialization.byKey(value);
            if (spec != null && (WeaponKeys.isValidTypeKey(baseKey) || WeaponKeys.isValidWeaponKey(baseKey))
                    && spec.sourceId().equals(WeaponKeys.providerOf(baseKey))) {
                this.specializations.put(typeKey, value);
            }
        }

        ListTag levelList = root.getList("SpecLv", Tag.TAG_COMPOUND);
        for (int i = 0; i < levelList.size(); i++) {
            CompoundTag tag = levelList.getCompound(i);
            Specialization spec = Specialization.byKey(tag.getString("S"));
            int level = Math.min(SpecRules.MAX_LEVELS, tag.getInt("L"));
            if (spec != null && !spec.isStandard() && level > 0) {
                this.specLevels.put(spec.key(), level);
            }
        }
        ListTag breakList = root.getList("SpecBt", Tag.TAG_COMPOUND);
        for (int i = 0; i < breakList.size(); i++) {
            CompoundTag tag = breakList.getCompound(i);
            String typeKey = tag.getString("T");
            Specialization spec = Specialization.byKey(tag.getString("S"));
            int star = Math.min(SpecRules.MAX_LEVELS, tag.getInt("L"));
            if (spec != null && !spec.isStandard() && star > 0 && WeaponKeys.isValidTypeKey(typeKey)
                    && spec.sourceId().equals(WeaponKeys.providerOf(typeKey))) {
                this.breakthroughs.computeIfAbsent(typeKey, key -> new HashMap<>()).put(spec.key(), star);
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

        /** 等级到没到上限（缓存，和 level 一起更新）。 */
        public boolean maxed;

        @Override
        public WeaponRecord copy() {
            WeaponRecord copy = new WeaponRecord();
            copy.xp = this.xp;
            copy.level = this.level;
            copy.typeKey = this.typeKey;
            copy.maxed = this.maxed;
            return copy;
        }
    }
}
