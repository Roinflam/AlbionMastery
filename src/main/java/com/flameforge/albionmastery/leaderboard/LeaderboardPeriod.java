package com.flameforge.albionmastery.leaderboard;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.WeekFields;

/**
 * 榜单的时间范围。
 *
 * <p>总榜看的是累计值；日 / 周 / 月榜看的是「这段时间里涨了多少」。
 * 涨多少 = 现在的值 − 这段时间开始时的值，后者叫「基线」，
 * 由 {@link MasteryLeaderboard} 在每次记录玩家数据时按需滚动。
 *
 * <p>每种范围都有一个「期号」（{@link #currentKey()}）：今天是哪天、本周是哪周、本月是哪月。
 * 基线上记着它属于哪一期，期号对不上就说明进入新的一期了，基线得重打。
 * 期号按服务端的系统时区算，周按 ISO 规则（周一开始）。
 */
public enum LeaderboardPeriod {

    /** 总榜：累计值，没有期号。 */
    ALL("gui.albionmastery.leaderboard.period_all", "gui.albionmastery.leaderboard.period_label_all", null),

    /** 日榜。 */
    DAY("gui.albionmastery.leaderboard.period_day", "gui.albionmastery.leaderboard.period_label_day", "D"),

    /** 周榜。 */
    WEEK("gui.albionmastery.leaderboard.period_week", "gui.albionmastery.leaderboard.period_label_week", "W"),

    /** 月榜。 */
    MONTH("gui.albionmastery.leaderboard.period_month", "gui.albionmastery.leaderboard.period_label_month", "M");

    /** 标签页上的名字的语言键（总榜 / 日榜 …）。 */
    private final String nameKey;

    /** 徽章前缀的语言键（全局 / 日榜 …），拼成「全局第一」「日榜第一」。 */
    private final String labelKey;

    /** 基线在存盘 NBT 里的键；总榜没有基线，为 null。 */
    private final String nbtKey;

    LeaderboardPeriod(String nameKey, String labelKey, String nbtKey) {
        this.nameKey = nameKey;
        this.labelKey = labelKey;
        this.nbtKey = nbtKey;
    }

    /**
     * @return 标签页名字的语言键
     */
    public String nameKey() {
        return this.nameKey;
    }

    /**
     * @return 徽章前缀的语言键
     */
    public String labelKey() {
        return this.labelKey;
    }

    /**
     * @return 基线在 NBT 里的键；总榜返回 null
     */
    public String nbtKey() {
        return this.nbtKey;
    }

    /**
     * @return 是不是「这段时间涨了多少」的榜（总榜以外都是）
     */
    public boolean isDelta() {
        return this != ALL;
    }

    /**
     * 当前这一期的期号。
     *
     * @return 期号；总榜返回空串
     */
    public String currentKey() {
        ZoneId zone = ZoneId.systemDefault();
        return switch (this) {
            case ALL -> "";
            case DAY -> LocalDate.now(zone).toString();
            case WEEK -> {
                LocalDate today = LocalDate.now(zone);
                // 按周所属的年份算，免得跨年那一周前后两天分到两期
                yield today.get(WeekFields.ISO.weekBasedYear()) + "-W"
                        + today.get(WeekFields.ISO.weekOfWeekBasedYear());
            }
            case MONTH -> YearMonth.now(zone).toString();
        };
    }

    /**
     * 按序号取范围，序号非法时回落到总榜。
     *
     * @param ordinal 序号（网络包里传的）
     * @return 范围，永不为 null
     */
    public static LeaderboardPeriod byOrdinal(int ordinal) {
        LeaderboardPeriod[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return ALL;
        }
        return values[ordinal];
    }
}
