package com.flameforge.albionmastery.leaderboard;

import java.util.Comparator;

/**
 * 排行榜的排序方式。
 *
 * <p>每种方式都有一串「先比什么、再比什么」的顺序，全部比完还平手就按名字排，
 * 保证同一份数据每次排出来的顺序一样，不会刷新一下名次就乱跳。
 *
 * <p>双端共用：客户端发请求时带上序号，服务端按序号排好再发回来。
 */
public enum LeaderboardSort {

    /** 满级武器最多的排前面。 */
    MAXED("gui.albionmastery.leaderboard.sort_maxed") {
        @Override
        int primary(LeaderboardRow row) {
            return row.maxed();
        }
    },

    /** 所有武器等级加起来最高的排前面。 */
    LEVEL_SUM("gui.albionmastery.leaderboard.sort_level_sum") {
        @Override
        int primary(LeaderboardRow row) {
            return row.levelSum();
        }
    },

    /** 用过（有等级）的武器最多的排前面。 */
    UNLOCKED("gui.albionmastery.leaderboard.sort_unlocked") {
        @Override
        int primary(LeaderboardRow row) {
            return row.unlocked();
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
    abstract int primary(LeaderboardRow row);

    /**
     * 给界面用的公开版本：这行在当前排序方式下的主数值。
     *
     * @param row 一行数据
     * @return 主数值
     */
    public int headline(LeaderboardRow row) {
        return primary(row);
    }

    /**
     * 按这种方式排序用的比较器。
     *
     * <p>主值相同时依次看：满级数、等级总和、已解锁数、单件最高等级和它的经验，最后按名字。
     * 这样不管选哪种排序，平手的人之间的先后也是有道理的，而不是看谁先进 HashMap。
     * 「单件最高」不单独做一种排序：等级上限一到，满级武器的经验也封在同一个数上，全是平手，排不出东西。
     *
     * @return 比较器，大的排前面
     */
    public Comparator<LeaderboardRow> comparator() {
        return Comparator.comparingInt(this::primary).reversed()
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
}
