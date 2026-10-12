package com.flameforge.albionmastery.mastery.rank;

import com.flameforge.albionmastery.mastery.LevelCurve;
import com.flameforge.albionmastery.mastery.MasteryData;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * 段位：账号级的总进度，照搬星际战甲的精通段位。
 *
 * <p>段位经验只来自外圈：每件武器每升一级给固定的段位经验（默认 100，见配置
 * {@code rankXpPerWeaponLevel}），所以段位经验 = 全部武器等级之和 × 每级段位经验。
 * 类型和总体两圈本身就是从武器经验派生的，再算一遍等于重复计数，不参与。
 *
 * <p>门槛与星际战甲一致：
 * <ul>
 *     <li>1~30 段：累计 {@code 2500 × 段位²}，30 段（至崇宗师）是 2,250,000；</li>
 *     <li>30 段以后是传奇段位，每段固定再要 147,500，传奇 N = 2,250,000 + 147,500 × N。</li>
 * </ul>
 * 传奇段位不设上限（星际战甲是按内容逐步开放的，这里武器数量决定上限），
 * 只为防止溢出夹到 {@link #MAX_RANK}。
 *
 * <p>段位是从段位经验现算的，不单独存盘：服主改了等级上限或经验曲线，段位跟着变，不会和武器等级对不上。
 */
public final class MasteryRank {

    /** 至崇宗师，最后一个有名字的段位。 */
    public static final int NAMED_RANKS = 30;

    /** 1~30 段的系数：累计经验 = 2500 × 段位²。 */
    public static final long QUADRATIC_BASE = 2500L;

    /** 至崇宗师所需的累计段位经验。 */
    public static final long GRAND_MASTER_XP = QUADRATIC_BASE * NAMED_RANKS * NAMED_RANKS;

    /** 每个传奇段位所需的段位经验。 */
    public static final long LEGENDARY_STEP = 147_500L;

    /** 段位上限，只是防溢出用的兜底，正常玩不到。 */
    public static final int MAX_RANK = NAMED_RANKS + 99_999;

    /** 每三个段位一组（青铜 / 白银 / 黄金），共十组。 */
    public static final int TIER_COUNT = 10;

    /** 段位徽章的金属色。 */
    public enum Metal {
        /** 0 段，没有金属色。 */
        NONE,
        BRONZE,
        SILVER,
        GOLD
    }

    private MasteryRank() {
    }

    /**
     * 一份专精数据的段位经验：全部武器等级之和 × 每级段位经验。
     *
     * <p>武器表里的等级是缓存，调用前要保证已经按当前曲线算过（服务端 {@code syncFull}、
     * 客户端收全量包、看别人罗盘时都会先 {@code recomputeLevels}）。
     * 遍历的是存档里的全部武器，不看本机装没装对应模组：武器所属的模组被删了，练出来的段位经验照样算。
     *
     * @param data       专精数据，可为 null
     * @param xpPerLevel 每级段位经验
     * @return 段位经验
     */
    public static long rankXpOf(@Nullable MasteryData data, int xpPerLevel) {
        if (data == null) {
            return 0L;
        }
        long levels = 0L;
        for (MasteryData.WeaponRecord record : data.getWeaponsView().values()) {
            if (record.level > 0) {
                levels += record.level;
            }
        }
        return rankXpForLevelSum(levels, xpPerLevel);
    }

    /**
     * 同 {@link #rankXpOf(MasteryData, int)}，但每件武器的等级按经验和曲线现算，不读缓存。
     *
     * <p>服务端对外接口用这个：刚读档、还没做过全量同步的数据里等级缓存全是 0，
     * 别的模组在登录事件里先于本模组来查段位时，读缓存会得到 0 段。
     *
     * @param data        专精数据，可为 null
     * @param weaponCurve 单件武器的等级曲线
     * @param xpPerLevel  每级段位经验
     * @return 段位经验
     */
    public static long rankXpOf(@Nullable MasteryData data, LevelCurve weaponCurve, int xpPerLevel) {
        if (data == null) {
            return 0L;
        }
        long levels = 0L;
        for (MasteryData.WeaponRecord record : data.getWeaponsView().values()) {
            levels += weaponCurve.levelFor(record.xp);
        }
        return rankXpForLevelSum(levels, xpPerLevel);
    }

    /**
     * 由武器等级之和换算段位经验。排行榜行里只有等级之和，用这个换算。
     *
     * @param levelSum   全部武器等级之和
     * @param xpPerLevel 每级段位经验
     * @return 段位经验
     */
    public static long rankXpForLevelSum(long levelSum, int xpPerLevel) {
        return Math.max(0L, levelSum) * Math.max(1, xpPerLevel);
    }

    /**
     * 达到某个段位所需的累计段位经验。
     *
     * @param rank 段位，0 及以下返回 0
     * @return 累计段位经验
     */
    public static long xpForRank(int rank) {
        if (rank <= 0) {
            return 0L;
        }
        int clamped = Math.min(rank, MAX_RANK);
        if (clamped <= NAMED_RANKS) {
            return QUADRATIC_BASE * clamped * clamped;
        }
        return GRAND_MASTER_XP + LEGENDARY_STEP * (clamped - NAMED_RANKS);
    }

    /**
     * 段位经验对应的段位。
     *
     * @param xp 累计段位经验
     * @return 段位，0 到 {@link #MAX_RANK}
     */
    public static int rankFor(long xp) {
        if (xp < QUADRATIC_BASE) {
            return 0;
        }
        if (xp >= GRAND_MASTER_XP) {
            long legendary = (xp - GRAND_MASTER_XP) / LEGENDARY_STEP;
            return (int) Math.min(MAX_RANK, NAMED_RANKS + legendary);
        }
        // 开方有浮点误差，先估再前后各修一步
        int rank = (int) Math.sqrt(xp / (double) QUADRATIC_BASE);
        while (rank < NAMED_RANKS && xpForRank(rank + 1) <= xp) {
            rank++;
        }
        while (rank > 0 && xpForRank(rank) > xp) {
            rank--;
        }
        return rank;
    }

    /**
     * @param rank 段位
     * @return 是不是传奇段位
     */
    public static boolean isLegendary(int rank) {
        return rank > NAMED_RANKS;
    }

    /**
     * @param rank 段位
     * @return 传奇段位的序号（传奇 1 返回 1），不是传奇段位返回 0
     */
    public static int legendaryIndex(int rank) {
        return isLegendary(rank) ? rank - NAMED_RANKS : 0;
    }

    /**
     * 段位属于哪一组：0 = 启蒙者，1 = 信徒 …… 9 = 大师；0 段返回 -1，传奇段位返回 {@link #TIER_COUNT}。
     *
     * @param rank 段位
     * @return 组号
     */
    public static int tier(int rank) {
        if (rank <= 0) {
            return -1;
        }
        if (isLegendary(rank)) {
            return TIER_COUNT;
        }
        return (rank - 1) / 3;
    }

    /**
     * 段位徽章的金属色：每组依次是青铜、白银、黄金。传奇段位接着循环（传奇 1 青铜、传奇 2 白银、传奇 3 黄金……），
     * 和星际战甲一样，传奇只在造型和描边上另做区分。
     *
     * @param rank 段位
     * @return 金属色
     */
    public static Metal metal(int rank) {
        if (rank <= 0) {
            return Metal.NONE;
        }
        return switch ((rank - 1) % 3) {
            case 0 -> Metal.BRONZE;
            case 1 -> Metal.SILVER;
            default -> Metal.GOLD;
        };
    }

    /**
     * 聊天栏里段位名用的颜色（RGB）。界面上的徽章有自己的渐变配色，这里只要一个能认出金属的主色。
     *
     * @param rank 段位
     * @return RGB
     */
    public static int chatColor(int rank) {
        return switch (metal(rank)) {
            case BRONZE -> 0xD9935A;
            case SILVER -> 0xD5DCE6;
            case GOLD -> 0xF5CD55;
            default -> 0x9C8E70;
        };
    }

    /**
     * 段位名称，例如「黄金苍鹰」「传奇 3」。
     *
     * @param rank 段位
     * @return 可翻译的名称
     */
    public static Component name(int rank) {
        if (isLegendary(rank)) {
            return Component.translatable("rank.albionmastery.legendary", legendaryIndex(rank));
        }
        return Component.translatable("rank.albionmastery.name." + Math.max(0, rank));
    }

    /**
     * 当前段位内已经攒了多少段位经验。
     *
     * @param xp 累计段位经验
     * @return 本段位内的经验
     */
    public static long xpIntoRank(long xp) {
        return Math.max(0L, xp - xpForRank(rankFor(xp)));
    }

    /**
     * 当前段位升到下一段一共要多少段位经验。
     *
     * @param xp 累计段位经验
     * @return 本段位的跨度；已到上限返回 0
     */
    public static long rankSpan(long xp) {
        int rank = rankFor(xp);
        if (rank >= MAX_RANK) {
            return 0L;
        }
        return xpForRank(rank + 1) - xpForRank(rank);
    }

    /**
     * 当前段位的进度，0 到 1。
     *
     * @param xp 累计段位经验
     * @return 进度
     */
    public static float progress(long xp) {
        long span = rankSpan(xp);
        if (span <= 0L) {
            return 1.0F;
        }
        return Math.min(1.0F, xpIntoRank(xp) / (float) span);
    }
}
