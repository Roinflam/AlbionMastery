package com.flameforge.albionmastery.mastery;

/**
 * 专精等级曲线。
 *
 * <p>单级所需经验的公式为：{@code xp(L -> L+1) = round(base * (L + 1) ^ exponent)}。
 * 构造时一次性把「累计经验表」算好（长度 = maxLevel + 1），之后所有换算都是查表 + 二分，
 * 不会在伤害事件这种热路径里做 {@code Math.pow}。
 *
 * <p>本类是不可变对象。服务端由配置构造，客户端由服务端下发的配置快照构造，
 * 保证两端显示的等级完全一致。
 */
public final class LevelCurve {

    /** 最高等级。 */
    private final int maxLevel;

    /** 累计经验表：{@code cumulative[L]} = 从 0 级升到 L 级所需的总经验，{@code cumulative[0] == 0}。 */
    private final long[] cumulative;

    /**
     * 构造等级曲线。
     *
     * @param maxLevel 最高等级，必须大于等于 1
     * @param base     一级经验基数，必须大于 0
     * @param exponent 经验增长指数，必须大于等于 0
     */
    public LevelCurve(int maxLevel, double base, double exponent) {
        this.maxLevel = Math.max(1, maxLevel);
        this.cumulative = new long[this.maxLevel + 1];
        double safeBase = Math.max(0.01D, base);
        double safeExponent = Math.max(0.0D, exponent);
        long acc = 0L;
        for (int level = 1; level <= this.maxLevel; level++) {
            // 第 level 级所需的单级经验
            long need = Math.max(1L, Math.round(safeBase * Math.pow(level, safeExponent)));
            // 防溢出：单个玩家单件武器的总经验不可能接近 Long.MAX_VALUE，这里只做兜底
            acc = acc > Long.MAX_VALUE - need ? Long.MAX_VALUE : acc + need;
            this.cumulative[level] = acc;
        }
    }

    /**
     * @return 最高等级
     */
    public int getMaxLevel() {
        return this.maxLevel;
    }

    /**
     * 根据总经验计算当前等级。
     *
     * @param totalXp 总经验，负数按 0 处理
     * @return 当前等级，范围 {@code [0, maxLevel]}
     */
    public int levelFor(long totalXp) {
        if (totalXp <= 0L) {
            return 0;
        }
        if (totalXp >= this.cumulative[this.maxLevel]) {
            return this.maxLevel;
        }
        // 二分查找第一个「累计经验 > totalXp」的等级，其前一级即为当前等级
        int low = 0;
        int high = this.maxLevel;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (this.cumulative[mid] <= totalXp) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /**
     * 查询升到指定等级所需的累计总经验。
     *
     * @param level 目标等级，会被夹到 {@code [0, maxLevel]}
     * @return 累计总经验
     */
    public long totalXpToReach(int level) {
        int clamped = Math.max(0, Math.min(this.maxLevel, level));
        return this.cumulative[clamped];
    }

    /**
     * 查询当前等级内已积累的经验（用于 UI 进度条的分子）。
     *
     * @param totalXp 总经验
     * @return 当前等级内的经验；满级时返回 0
     */
    public long xpInCurrentLevel(long totalXp) {
        int level = levelFor(totalXp);
        if (level >= this.maxLevel) {
            return 0L;
        }
        return Math.max(0L, totalXp - this.cumulative[level]);
    }

    /**
     * 查询升到下一级还需要的单级经验总量（用于 UI 进度条的分母）。
     *
     * @param totalXp 总经验
     * @return 单级经验总量；满级时返回 0
     */
    public long xpForCurrentLevelSpan(long totalXp) {
        int level = levelFor(totalXp);
        if (level >= this.maxLevel) {
            return 0L;
        }
        return this.cumulative[level + 1] - this.cumulative[level];
    }

    /**
     * 查询距离升级还差多少经验。
     *
     * @param totalXp 总经验
     * @return 还差的经验；满级时返回 0
     */
    public long xpRemainingToNextLevel(long totalXp) {
        int level = levelFor(totalXp);
        if (level >= this.maxLevel) {
            return 0L;
        }
        return Math.max(0L, this.cumulative[level + 1] - totalXp);
    }
}
