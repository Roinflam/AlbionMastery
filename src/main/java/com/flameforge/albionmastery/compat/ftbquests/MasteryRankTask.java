package com.flameforge.albionmastery.compat.ftbquests;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.api.MasteryRankApi;
import com.flameforge.albionmastery.client.rank.ClientRank;
import com.flameforge.albionmastery.mastery.rank.MasteryRank;
import com.flameforge.albionmastery.mastery.rank.MasteryRankTracker;
import dev.architectury.hooks.level.entity.PlayerHooks;
import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftblibrary.config.NameMap;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/**
 * FTB 任务的「专精段位」任务：玩家的段位或段位经验满足比较条件即完成。
 *
 * <p>比较方式有五种：≥、>、=、≤、<。
 * <ul>
 *     <li>≥ 和 > 有真实的进度条（当前值 / 目标值），只增不减，和 FTB 自带的统计任务一样；</li>
 *     <li>=、≤、< 没有「越来越近」的方向，只有完成、未完成两种状态。</li>
 * </ul>
 * FTB 的任务一旦完成就不会撤销，所以 = / ≤ / < 的意思是「某次检查时满足过」：
 * 比如「段位 = 0」新人一进服就完成，之后升了段也还是完成。
 *
 * <p>段位以服务端的专精数据为准：平时读最近一次同步时算好的值（{@link MasteryRankTracker#cachedRankXp}），
 * 还没同步过才经 {@link MasteryRankApi} 现算。检查时机有三个：登录时、
 * 段位经验变化时（{@link FtbQuestsCompat#recheck}）、每 5 秒一次兜底。
 * 进度和 FTB 其它任务一样按队伍算，队里任意一人满足就是整队完成。
 */
public final class MasteryRankTask extends Task {

    /** 任务类型 ID，也就是任务文件里 {@code type} 字段的值。 */
    public static final ResourceLocation TYPE_ID = new ResourceLocation(AlbionMastery.MOD_ID, "mastery_rank");

    /** 任务类型图标上画的段位：黄金苍鹰，金色徽章在添加任务的列表里最好认。 */
    static final int TYPE_ICON_RANK = 18;

    /** 兜底检查的间隔（tick）。段位变化时会主动重查，这里只防漏，5 秒足够。 */
    private static final int AUTO_SUBMIT_INTERVAL = 100;

    /** 语言键前缀，和 FTB 给配置项拼的键一致：{@code ftbquests.task.<命名空间>.<路径>}。 */
    private static final String LANG = "ftbquests.task.albionmastery.mastery_rank";

    /** 登记后的任务类型，见 {@link FtbQuestsBridge#register()}。 */
    static TaskType type;

    /** 比较哪个指标。 */
    public enum Metric {
        /** 段位：0 = 无等级，1~30 = 启蒙者 … 至崇宗师，31 起是传奇 1、传奇 2 …… */
        RANK("rank"),
        /** 段位经验：全部武器等级之和 × 每级段位经验。 */
        RANK_XP("rank_xp");

        private final String id;

        Metric(String id) {
            this.id = id;
        }

        /**
         * @return 任务文件和网络包里用的 ID，写进存档了，不能改
         */
        public String id() {
            return id;
        }
    }

    /** 比较方式。 */
    public enum Comparison {
        /** 大于等于。 */
        GE("ge", "≥"),
        /** 大于。 */
        GT("gt", ">"),
        /** 等于。 */
        EQ("eq", "="),
        /** 小于等于。 */
        LE("le", "≤"),
        /** 小于。 */
        LT("lt", "<");

        private final String id;
        private final String symbol;

        Comparison(String id, String symbol) {
            this.id = id;
            this.symbol = symbol;
        }

        /**
         * @return 任务文件和网络包里用的 ID，写进存档了，不能改
         */
        public String id() {
            return id;
        }

        /**
         * @return 标题里显示的符号
         */
        public String symbol() {
            return symbol;
        }

        /**
         * @param actual 玩家当前的值
         * @param target 目标值
         * @return 条件是否成立
         */
        public boolean test(long actual, long target) {
            return switch (this) {
                case GE -> actual >= target;
                case GT -> actual > target;
                case EQ -> actual == target;
                case LE -> actual <= target;
                case LT -> actual < target;
            };
        }

        /**
         * @return 有没有进度条：只有 ≥ 和 > 是数值越涨越接近，其余三种只看成立与否
         */
        public boolean counting() {
            return this == GE || this == GT;
        }
    }

    private static final NameMap<Metric> METRICS = NameMap.of(Metric.RANK, Metric.values())
            .id(Metric::id)
            .baseNameKey(LANG + ".metric")
            .create();

    private static final NameMap<Comparison> COMPARISONS = NameMap.of(Comparison.GE, Comparison.values())
            .id(Comparison::id)
            .baseNameKey(LANG + ".comparison")
            .create();

    private Metric metric = Metric.RANK;
    private Comparison comparison = Comparison.GE;
    private long value = 1L;

    /**
     * FTB 读任务文件、编辑器新建任务时调用。
     *
     * @param id    任务 ID
     * @param quest 所属任务
     */
    public MasteryRankTask(long id, Quest quest) {
        super(id, quest);
    }

    @Override
    public TaskType getType() {
        return type;
    }

    /**
     * 实际参与比较的目标值。段位超过上限的按上限算：
     * 编辑界面里两种指标共用一个数值框，切换指标时没法跟着改范围，只能在这里夹。
     */
    private long target() {
        long target = Math.max(0L, value);
        return metric == Metric.RANK ? Math.min(target, MasteryRank.MAX_RANK) : target;
    }

    /**
     * ≥ 的满进度就是目标值，> 是目标值 + 1（整数里「大于 n」就是「至少 n + 1」），其余三种是 1。
     *
     * <p>至少为 1：FTB 把进度 0 当作「未开始」，满进度为 0 的任务永远不会被标为完成。
     */
    @Override
    public long getMaxProgress() {
        long target = target();
        return switch (comparison) {
            case GE -> Math.max(1L, target);
            case GT -> target == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, target + 1L);
            default -> 1L;
        };
    }

    @Override
    public boolean checkOnLogin() {
        return true;
    }

    @Override
    public int autoSubmitOnPlayerTick() {
        return AUTO_SUBMIT_INTERVAL;
    }

    @Override
    public void submitTask(TeamData teamData, ServerPlayer player, ItemStack craftedItem) {
        if (teamData.isCompleted(this) || !checkTaskSequence(teamData) || PlayerHooks.isFake(player)) {
            return;
        }
        long rankXp = rankXpOf(player);
        long actual = metric == Metric.RANK ? MasteryRank.rankFor(rankXp) : rankXp;
        long max = getMaxProgress();
        long current = teamData.getProgress(this);
        if (comparison.test(actual, target())) {
            // 已存进度可能是改任务之前留下的（换了指标、调低了数值、改成 = / ≤ / <），会等于甚至超过新的满进度，
            // 不能再按只增不减比：不等于满进度就写一次，setProgress 会把超出的夹回满进度并标记完成
            if (current != max) {
                teamData.setProgress(this, max);
            } else if (!teamData.isLocked() && teamData.areDependenciesComplete(getQuest())) {
                // 正好等于新的满进度（≥ 10 已存 5，改成 ≥ 5）：setProgress 见值没变直接返回，
                // 照 FTB 登录时补标满进度任务的做法直接标完成
                teamData.markTaskCompleted(this);
            }
            return;
        }
        // 满进度只留给条件成立的那一刻，没成立时最多停在前一格
        long progress = comparison.counting() ? Math.max(0L, Math.min(actual, max - 1L)) : 0L;
        // 只增不减：段位经验正常只涨不跌；队里段位低的人来检查、管理员清空数据，都不把已有进度拉回去
        if (progress > current) {
            teamData.setProgress(this, progress);
        }
    }

    /**
     * 先用最近一次同步时算好的段位经验，这次上线还没同步过（FTB 的登录检查排在本模组前面）才现算。
     * 兜底检查每 5 秒对每个未完成的任务各来一次，全服玩家还挤在同一个 tick，现算要把全部武器的等级重算一遍。
     * 缓存最多落后一个同步间隔，段位经验一变，同步后会立刻重查。
     */
    private static long rankXpOf(ServerPlayer player) {
        Long cached = MasteryRankTracker.cachedRankXp(player.getUUID());
        return cached != null ? cached : MasteryRankApi.getRankXp(player);
    }

    @Override
    public void writeData(CompoundTag nbt) {
        super.writeData(nbt);
        nbt.putString("metric", metric.id());
        nbt.putString("comparison", comparison.id());
        nbt.putLong("value", value);
    }

    @Override
    public void readData(CompoundTag nbt) {
        super.readData(nbt);
        // 认不出的 ID（手写写错、以后删掉的选项）退回默认值
        metric = METRICS.get(nbt.getString("metric"));
        comparison = COMPARISONS.get(nbt.getString("comparison"));
        value = nbt.contains("value", Tag.TAG_ANY_NUMERIC) ? Math.max(0L, nbt.getLong("value")) : 1L;
    }

    @Override
    public void writeNetData(FriendlyByteBuf buffer) {
        super.writeNetData(buffer);
        // 枚举按 ID 字符串传，不按下标：两端版本对不上时认不出的值退回默认，不会把后面的字段读串
        buffer.writeUtf(metric.id());
        buffer.writeUtf(comparison.id());
        buffer.writeVarLong(value);
    }

    @Override
    public void readNetData(FriendlyByteBuf buffer) {
        super.readNetData(buffer);
        metric = METRICS.get(buffer.readUtf());
        comparison = COMPARISONS.get(buffer.readUtf());
        value = Math.max(0L, buffer.readVarLong());
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void fillConfigGroup(ConfigGroup config) {
        super.fillConfigGroup(config);
        config.addEnum("metric", metric, v -> metric = v, METRICS);
        config.addEnum("comparison", comparison, v -> comparison = v, COMPARISONS);
        // 只限下界，上界见 target()
        config.addLong("value", value, v -> value = v, 1L, 0L, Long.MAX_VALUE);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public Component getAltTitle() {
        long target = target();
        if (metric == Metric.RANK) {
            return Component.translatable(LANG + ".title.rank",
                    comparison.symbol(), MasteryRank.name((int) target), Long.toString(target));
        }
        return Component.translatable(LANG + ".title.rank_xp", comparison.symbol(), formatNumber(target));
    }

    /**
     * 按段位任务画目标段位的徽章；按段位经验的任务画这么多经验对应的段位。
     */
    @Override
    @OnlyIn(Dist.CLIENT)
    public Icon getAltIcon() {
        long target = target();
        return RankEmblemIcon.of(metric == Metric.RANK ? (int) target : MasteryRank.rankFor(target));
    }

    /**
     * 悬停时补两段：目标换算成另一种指标是多少，以及自己现在的段位和段位经验，出题和做题的人都不用另查。
     * 按段位经验的任务在编辑模式下还会提醒目标值是否设得完成不了，见 {@link #addRankXpWarnings}。
     */
    @Override
    @OnlyIn(Dist.CLIENT)
    public void addMouseOverText(TooltipList list, TeamData teamData) {
        super.addMouseOverText(list, teamData);
        long target = target();
        list.blankLine();
        if (metric == Metric.RANK) {
            int rank = (int) target;
            list.add(Component.translatable(LANG + ".hover.rank_needs",
                    MasteryRank.name(rank), formatNumber(MasteryRank.xpForRank(rank))).withStyle(ChatFormatting.GRAY));
        } else {
            list.add(Component.translatable(LANG + ".hover.xp_reaches",
                    formatNumber(target), MasteryRank.name(MasteryRank.rankFor(target))).withStyle(ChatFormatting.GRAY));
            addRankXpWarnings(list, target);
        }
        int selfRank = ClientRank.selfRank();
        list.add(Component.translatable(LANG + ".hover.self_rank",
                MasteryRank.name(selfRank), Integer.toString(selfRank)).withStyle(ChatFormatting.YELLOW));
        list.add(Component.translatable(LANG + ".hover.self_rank_xp",
                formatNumber(ClientRank.selfRankXp())).withStyle(ChatFormatting.YELLOW));
    }

    /**
     * 段位经验任务的出题提醒，只在编辑模式下显示：做任务的玩家看到「建议改用 ≥」也改不了。
     *
     * <p>段位经验永远是每级段位经验的整数倍；同步又会把一段时间里的升级合成一批，
     * 两件武器同时升级、低级时一下打出好几级，段位经验都会一次涨好几级的量。「=」只在检查那一刻比一次，
     * 跨过去就再也等不到，任务永远完成不了。
     *
     * @param list   悬停文字
     * @param target 目标段位经验
     */
    @OnlyIn(Dist.CLIENT)
    private void addRankXpWarnings(TooltipList list, long target) {
        if (!ClientQuestFile.canClientPlayerEdit()) {
            return;
        }
        boolean exact = comparison == Comparison.EQ;
        int perLevel = Math.max(1, ClientRank.xpPerLevel());
        if (target % perLevel != 0) {
            // ≥ / > / ≤ / < 只是实际门槛落到相邻的整数倍上，不影响完成，灰字提一句；= 就是永远达不到
            list.add(Component.translatable(LANG + ".hover.warn_not_multiple", formatNumber(perLevel))
                    .withStyle(exact ? ChatFormatting.RED : ChatFormatting.GRAY));
        }
        if (exact) {
            list.add(Component.translatable(LANG + ".hover.warn_eq").withStyle(ChatFormatting.RED));
        }
    }

    /** 千分位写法，例如 1,000,000。 */
    private static String formatNumber(long number) {
        return String.format(Locale.ROOT, "%,d", number);
    }
}
