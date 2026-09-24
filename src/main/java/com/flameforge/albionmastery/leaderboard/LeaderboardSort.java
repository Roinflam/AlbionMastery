package com.flameforge.albionmastery.leaderboard;

import java.util.Comparator;

/**
 * 排行榜的排序方式。
 *
 * <p>每种方式都有一串「先比什么、再比什么」的顺序，全部比完还平手就按名字排，
 * 保证同一份数据每次排出来的顺序一样，不会刷新一下名次就乱跳。
 *
 * <p>双端共用：客户端发请求时带上序号，服务端按序号排好再发回来。
 * 序号进网络包，新的排序方式只能往后加，不能插队。
 */
public enum LeaderboardSort {

    /** 满级武器最多的排前面。 */
    MAXED("gui.albionmastery.leaderboard.sort_maxed") {
        @Override
        long primary(LeaderboardRow row) {
            return row.maxed();
        }
    },

    /** 所有武器等级加起来最高的排前面。 */
    LEVEL_SUM("gui.albionmastery.leaderboard.sort_level_sum") {
        @Override
        long primary(LeaderboardRow row) {
            return row.levelSum();
        }
    },

    /** 用过（有等级）的武器最多的排前面。 */
    UNLOCKED("gui.albionmastery.leaderboard.sort_unlocked") {
        @Override
        long primary(LeaderboardRow row) {
            return row.unlocked();
        }
    },

    /**
     * 所有武器经验加起来最多的排前面。日 / 周 / 月榜的默认排序。
     *
     * <p>增量榜只看等级的话，后期玩家刷一整天升不了一级，三个等级维度全是 0，人就从日榜上消失了；
     * 而新号随便练两把就能升好几级排在他前面。经验是唯一一个「刷了就涨」的维度，增量榜看它才准。
     */
    XP("gui.albionmastery.leaderboard.sort_xp") {
        @Override
        long primary(LeaderboardRow row) {
            return row.xpSum();
        }
    };

    /** 显示在排序按钮上的语言键。 */
    private final String nameKey;

    LeaderboardSort(String nameKey) {
        this.nameKey = nameKey;
    }

    /**
     * @return 显示名称的语言键
     */
    public String nameKey() {
        return this.nameKey;
    }

    /**
     * 这种排序方式最看重的那个数字，界面上作为大字显示在每行右侧。
     *
     * @param row 一行数据
     * @return 主排序值
     */
    abstract long primary(LeaderboardRow row);

    /**
     * 给界面用的公开版本：这行在当前排序方式下的主数值。
     *
     * @param row 一行数据
     * @return 主数值
     */
    public long headline(LeaderboardRow row) {
        return primary(row);
    }

    /**
     * 按这种方式排序用的比较器。
     *
     * <p>主值相同时依次看：经验总和、满级数、等级总和、已解锁数、单件最高等级和它的经验，最后按名字。
     * 这样不管选哪种排序，平手的人之间的先后也是有道理的，而不是看谁先进 HashMap。
     * 经验排在第一顺位：等级这几项都是台阶，同一级里差着大半级经验的两个人不该看名字定先后。
     * 「单件最高」不单独做一种排序：等级上限一到，满级武器的经验也封在同一个数上，全是平手，排不出东西。
     *
     * @return 比较器，大的排前面
     */
    public Comparator<LeaderboardRow> comparator() {
        return Comparator.comparingLong(this::primary).reversed()
                .thenComparing(Comparator.comparingLong(LeaderboardRow::xpSum).reversed())
                .thenComparing(Comparator.comparingInt(LeaderboardRow::maxed).reversed())
                .thenComparing(Comparator.comparingInt(LeaderboardRow::levelSum).reversed())
                .thenComparing(Comparator.comparingInt(LeaderboardRow::unlocked).reversed())
                .thenComparing(Comparator.comparingInt(LeaderboardRow::topLevel).reversed())
                .thenComparing(Comparator.comparingLong(LeaderboardRow::topXp).reversed())
                .thenComparing(LeaderboardRow::name, String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * 按序号取排序方式，序号非法时回落到默认的满级数。
     *
     * @param ordinal 序号（网络包里传的）
     * @return 排序方式，永不为 null
     */
    public static LeaderboardSort byOrdinal(int ordinal) {
        LeaderboardSort[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return MAXED;
        }
        return values[ordinal];
    }

    /**
     * 某种时间范围下默认选中哪种排序。
     *
     * @param period 时间范围
     * @return 总榜给满级数，日 / 周 / 月榜给经验
     */
    public static LeaderboardSort defaultFor(LeaderboardPeriod period) {
        return period.isDelta() ? XP : MAXED;
    }
}
