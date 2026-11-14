package com.flameforge.albionmastery.config;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 特化的养成规则：几个槽、每个槽的门槛、每级系数和价格、点数怎么来、段位减免、退点冷却。
 *
 * <p>和特化数值一样以服务端配置为准，跟着配置快照一起下发，客户端界面上显示的门槛、点数、每级数值
 * 和服务端实际判定用的是同一份数。
 *
 * <p>槽位按「普通、突破、普通、突破」排：奇数槽装普通版，偶数槽装突破版。
 * 只开一个槽时没有突破槽，这个槽直接用特化当前最强的那一版（突破过就用突破版）。
 */
public final class SpecRules {

    /** 普通版 / 突破版最多分几级。 */
    public static final int MAX_LEVELS = 10;

    public final int slotCount;
    /** 槽 1：武器要到等级上限的百分之几。 */
    public final int slot1WeaponPercent;
    /** 槽 2（突破）：类型要到等级上限的百分之几。 */
    public final int slot2TypePercent;
    /** 槽 3（普通）：来源要到等级上限的百分之几。 */
    public final int slot3SourcePercent;
    /** 槽 4（突破）：类型要到等级上限的百分之几。 */
    public final int slot4TypePercent;
    /** 类型到这个进度后，类型下所有武器跳过槽 1 的武器门槛；0 表示不豁免。 */
    public final int typeBypassPercent;
    private final double[] normalScale;
    private final double[] breakthroughScale;
    private final int[] normalCost;
    private final int[] breakthroughCost;
    /** 来源满级时给多少改装点（按等级线性给，向下取整）。 */
    public final int modPointsAtMaxSource;
    /** 类型满级时给多少突破点。 */
    public final int breakthroughPointsAtMaxType;
    public final int rankModPointStart;
    public final int rankModPointEvery;
    public final int rankModPointMax;
    /** 类型里每有一把满级武器加几个突破点。 */
    public final int maxedWeaponBreakthroughPoint;
    public final int maxedWeaponBreakthroughPointMax;
    public final int rankReductionStart;
    public final int rankReductionEvery;
    public final int rankReductionPercentPerStep;
    public final int rankReductionMaxPercent;
    /** 段位减免要求这把武器所在的类型里已经有几把满级武器。 */
    public final int rankReductionMaxedWeaponsInType;
    public final int respecCooldownSeconds;

    public SpecRules(int slotCount, int slot1WeaponPercent, int slot2TypePercent, int slot3SourcePercent,
                     int slot4TypePercent, int typeBypassPercent,
                     double[] normalScale, double[] breakthroughScale, int[] normalCost, int[] breakthroughCost,
                     int modPointsAtMaxSource, int breakthroughPointsAtMaxType,
                     int rankModPointStart, int rankModPointEvery, int rankModPointMax,
                     int maxedWeaponBreakthroughPoint, int maxedWeaponBreakthroughPointMax,
                     int rankReductionStart, int rankReductionEvery, int rankReductionPercentPerStep,
                     int rankReductionMaxPercent, int rankReductionMaxedWeaponsInType,
                     int respecCooldownSeconds) {
        this.slotCount = clamp(slotCount, 1, 4);
        this.slot1WeaponPercent = clamp(slot1WeaponPercent, 1, 100);
        this.slot2TypePercent = clamp(slot2TypePercent, 0, 100);
        this.slot3SourcePercent = clamp(slot3SourcePercent, 0, 100);
        this.slot4TypePercent = clamp(slot4TypePercent, 0, 100);
        this.typeBypassPercent = clamp(typeBypassPercent, 0, 100);
        this.normalScale = sanitizeScale(normalScale, DEFAULT_NORMAL_SCALE);
        this.breakthroughScale = sanitizeScale(breakthroughScale, DEFAULT_BREAKTHROUGH_SCALE);
        this.normalCost = sanitizeCost(normalCost, this.normalScale.length);
        this.breakthroughCost = sanitizeCost(breakthroughCost, this.breakthroughScale.length);
        this.modPointsAtMaxSource = clamp(modPointsAtMaxSource, 0, 1000);
        this.breakthroughPointsAtMaxType = clamp(breakthroughPointsAtMaxType, 0, 1000);
        this.rankModPointStart = clamp(rankModPointStart, 1, 100000);
        this.rankModPointEvery = clamp(rankModPointEvery, 1, 100000);
        this.rankModPointMax = clamp(rankModPointMax, 0, 1000);
        this.maxedWeaponBreakthroughPoint = clamp(maxedWeaponBreakthroughPoint, 0, 100);
        this.maxedWeaponBreakthroughPointMax = clamp(maxedWeaponBreakthroughPointMax, 0, 1000);
        this.rankReductionStart = clamp(rankReductionStart, 1, 100000);
        this.rankReductionEvery = clamp(rankReductionEvery, 1, 100000);
        this.rankReductionPercentPerStep = clamp(rankReductionPercentPerStep, 0, 100);
        this.rankReductionMaxPercent = clamp(rankReductionMaxPercent, 0, 100);
        this.rankReductionMaxedWeaponsInType = clamp(rankReductionMaxedWeaponsInType, 0, 1000);
        this.respecCooldownSeconds = clamp(respecCooldownSeconds, 0, 30 * 86400);
    }

    public static final double[] DEFAULT_NORMAL_SCALE = {0.2D, 0.35D, 0.5D, 0.65D, 0.8D};
    public static final double[] DEFAULT_BREAKTHROUGH_SCALE = {0.88D, 0.96D, 1.04D, 1.12D, 1.2D};

    /**
     * @return 默认规则（配置还没加载、或者老服务端没下发时用）
     */
    public static SpecRules createDefault() {
        return new SpecRules(4, 100, 70, 40, 100, 100,
                DEFAULT_NORMAL_SCALE, DEFAULT_BREAKTHROUGH_SCALE, new int[]{1, 1, 1, 1, 1}, new int[]{1, 1, 1, 1, 1},
                20, 10, 3, 3, 10, 1, 5, 3, 2, 5, 50, 1, 86400);
    }

    // 槽位

    /**
     * 这个槽装的是不是突破版。只开一个槽时没有突破槽。
     *
     * @param slot 槽位
     * @return 偶数槽且槽数大于 1 时为 true
     */
    public boolean isBreakthroughSlot(int slot) {
        return this.slotCount > 1 && slot % 2 == 0;
    }

    /**
     * 只开一个槽：唯一的槽用特化当前最强的一版。
     *
     * @return 槽数为 1 时为 true
     */
    public boolean singleSlot() {
        return this.slotCount == 1;
    }

    /**
     * 按「等级上限的百分比」算出实际等级。
     *
     * @param percent  百分比
     * @param maxLevel 等级上限
     * @return 等级；百分比为 0 时返回 0（不设门槛）
     */
    public static int levelOf(int percent, int maxLevel) {
        if (percent <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(maxLevel * percent / 100.0D));
    }

    // 等级、系数、价格

    /** @return 普通版最高几级 */
    public int normalMax() {
        return this.normalScale.length;
    }

    /** @return 突破版最高几级 */
    public int breakthroughMax() {
        return this.breakthroughScale.length;
    }

    /**
     * @param level 普通版等级，1 起
     * @return 这一级的系数；0 级返回 0
     */
    public double normalScale(int level) {
        if (level <= 0) {
            return 0.0D;
        }
        return this.normalScale[Math.min(level, this.normalScale.length) - 1];
    }

    /**
     * @param star 突破等级，1 起
     * @return 这一级的系数；0 级返回 0
     */
    public double breakthroughScale(int star) {
        if (star <= 0) {
            return 0.0D;
        }
        return this.breakthroughScale[Math.min(star, this.breakthroughScale.length) - 1];
    }

    /**
     * @param level 要升到的普通版等级，1 起
     * @return 升这一级要几个改装点
     */
    public int normalCost(int level) {
        return level <= 0 || level > this.normalCost.length ? 0 : this.normalCost[level - 1];
    }

    /**
     * @param star 要升到的突破等级，1 起
     * @return 升这一级要几个突破点
     */
    public int breakthroughCost(int star) {
        return star <= 0 || star > this.breakthroughCost.length ? 0 : this.breakthroughCost[star - 1];
    }

    /**
     * @param level 普通版等级
     * @return 升到这一级一共花了几个改装点
     */
    public int normalSpent(int level) {
        int sum = 0;
        for (int i = 1; i <= Math.min(level, this.normalCost.length); i++) {
            sum += this.normalCost[i - 1];
        }
        return sum;
    }

    /**
     * @param star 突破等级
     * @return 突破到这一级一共花了几个突破点
     */
    public int breakthroughSpent(int star) {
        int sum = 0;
        for (int i = 1; i <= Math.min(star, this.breakthroughCost.length); i++) {
            sum += this.breakthroughCost[i - 1];
        }
        return sum;
    }

    /** @return 普通版每级系数的副本 */
    public double[] normalScales() {
        return this.normalScale.clone();
    }

    /** @return 突破版每级系数的副本 */
    public double[] breakthroughScales() {
        return this.breakthroughScale.clone();
    }

    // 点数和段位

    /**
     * @param sourceLevel 来源等级
     * @param maxLevel    等级上限
     * @return 来源等级给的改装点
     */
    public int modPointsFromLevel(int sourceLevel, int maxLevel) {
        return (int) ((long) Math.max(0, Math.min(sourceLevel, maxLevel)) * this.modPointsAtMaxSource
                / Math.max(1, maxLevel));
    }

    /**
     * @param rank 段位
     * @return 段位给每个来源加的改装点
     */
    public int modPointsFromRank(int rank) {
        if (rank < this.rankModPointStart || this.rankModPointMax <= 0) {
            return 0;
        }
        long steps = (long) (rank - this.rankModPointStart) / this.rankModPointEvery + 1L;
        return (int) Math.min(this.rankModPointMax, steps);
    }

    /**
     * @param typeLevel 类型等级
     * @param maxLevel  等级上限
     * @return 类型等级给的突破点
     */
    public int breakthroughPointsFromLevel(int typeLevel, int maxLevel) {
        return (int) ((long) Math.max(0, Math.min(typeLevel, maxLevel)) * this.breakthroughPointsAtMaxType
                / Math.max(1, maxLevel));
    }

    /**
     * @param maxedWeapons 类型里的满级武器数
     * @return 满级武器给的突破点
     */
    public int breakthroughPointsFromMaxed(int maxedWeapons) {
        long points = (long) Math.max(0, maxedWeapons) * this.maxedWeaponBreakthroughPoint;
        return (int) Math.min(this.maxedWeaponBreakthroughPointMax, points);
    }

    /**
     * 段位能把槽 1 的武器门槛减掉几级（没算「类型里要有几把满级武器」这条，那条看玩家数据）。
     *
     * @param rank     段位
     * @param maxLevel 等级上限
     * @return 减几级
     */
    public int rankReductionLevels(int rank, int maxLevel) {
        if (rank < this.rankReductionStart || this.rankReductionPercentPerStep <= 0) {
            return 0;
        }
        long steps = (long) (rank - this.rankReductionStart) / this.rankReductionEvery + 1L;
        long percent = Math.min(this.rankReductionMaxPercent, steps * this.rankReductionPercentPerStep);
        return (int) (maxLevel * percent / 100L);
    }

    // 网络

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.slotCount);
        buf.writeVarInt(this.slot1WeaponPercent);
        buf.writeVarInt(this.slot2TypePercent);
        buf.writeVarInt(this.slot3SourcePercent);
        buf.writeVarInt(this.slot4TypePercent);
        buf.writeVarInt(this.typeBypassPercent);
        writeDoubles(buf, this.normalScale);
        writeDoubles(buf, this.breakthroughScale);
        writeInts(buf, this.normalCost);
        writeInts(buf, this.breakthroughCost);
        buf.writeVarInt(this.modPointsAtMaxSource);
        buf.writeVarInt(this.breakthroughPointsAtMaxType);
        buf.writeVarInt(this.rankModPointStart);
        buf.writeVarInt(this.rankModPointEvery);
        buf.writeVarInt(this.rankModPointMax);
        buf.writeVarInt(this.maxedWeaponBreakthroughPoint);
        buf.writeVarInt(this.maxedWeaponBreakthroughPointMax);
        buf.writeVarInt(this.rankReductionStart);
        buf.writeVarInt(this.rankReductionEvery);
        buf.writeVarInt(this.rankReductionPercentPerStep);
        buf.writeVarInt(this.rankReductionMaxPercent);
        buf.writeVarInt(this.rankReductionMaxedWeaponsInType);
        buf.writeVarInt(this.respecCooldownSeconds);
    }

    public static SpecRules read(FriendlyByteBuf buf) {
        return new SpecRules(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(),
                readDoubles(buf), readDoubles(buf), readInts(buf), readInts(buf),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private static void writeDoubles(FriendlyByteBuf buf, double[] values) {
        buf.writeVarInt(values.length);
        for (double value : values) {
            buf.writeDouble(value);
        }
    }

    private static double[] readDoubles(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        // 长度前缀不对只能判包坏掉：截短的话后面所有字段都会错位
        if (count < 0 || count > MAX_LEVELS) {
            throw new IllegalArgumentException("AlbionMastery: 特化等级系数条数非法 " + count);
        }
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = buf.readDouble();
        }
        return values;
    }

    private static void writeInts(FriendlyByteBuf buf, int[] values) {
        buf.writeVarInt(values.length);
        for (int value : values) {
            buf.writeVarInt(value);
        }
    }

    private static int[] readInts(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_LEVELS) {
            throw new IllegalArgumentException("AlbionMastery: 特化等级价格条数非法 " + count);
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = buf.readVarInt();
        }
        return values;
    }

    // 配置文本解析

    /**
     * 把「0.2, 0.35, 0.5」这种配置文本读成系数表。读不出来的项跳过；一项都读不出来时返回 null。
     *
     * @param text 配置文本
     * @return 系数表
     */
    public static double[] parseScale(String text) {
        List<Double> values = new ArrayList<>();
        if (text != null) {
            for (String part : text.split("[,，\\s]+")) {
                if (part.isEmpty()) {
                    continue;
                }
                try {
                    double value = Double.parseDouble(part);
                    if (!Double.isNaN(value) && !Double.isInfinite(value)) {
                        values.add(value);
                    }
                } catch (NumberFormatException ignored) {
                    // 写错的一项跳过，别让整张表作废
                }
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        double[] out = new double[Math.min(MAX_LEVELS, values.size())];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /**
     * 把「1, 1, 2」这种配置文本读成价格表。
     *
     * @param text 配置文本
     * @return 价格表；一项都读不出来时返回 null
     */
    public static int[] parseCost(String text) {
        double[] values = parseScale(text);
        if (values == null) {
            return null;
        }
        int[] out = new int[values.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (int) Math.round(values[i]);
        }
        return out;
    }

    /**
     * 配置文本能不能读成至少一项（配置校验用）。
     *
     * @param value 配置值
     * @return 能读出来返回 true
     */
    public static boolean validList(Object value) {
        return value instanceof String text && parseScale(text) != null;
    }

    private static double[] sanitizeScale(double[] values, double[] fallback) {
        if (values == null || values.length == 0) {
            return fallback.clone();
        }
        int length = Math.min(MAX_LEVELS, values.length);
        double[] out = new double[length];
        for (int i = 0; i < length; i++) {
            double value = values[i];
            out[i] = Double.isNaN(value) ? 0.0D : Math.max(0.0D, Math.min(10.0D, value));
        }
        return out;
    }

    /**
     * 价格表和系数表一样长：短了用最后一项补，长了截掉。最低 0（白送）。
     */
    private static int[] sanitizeCost(int[] values, int length) {
        int[] out = new int[length];
        for (int i = 0; i < length; i++) {
            int value = values == null || values.length == 0 ? 1 : values[Math.min(i, values.length - 1)];
            out[i] = Math.max(0, Math.min(1000, value));
        }
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
