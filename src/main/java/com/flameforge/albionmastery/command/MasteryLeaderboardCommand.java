package com.flameforge.albionmastery.command;

import com.flameforge.albionmastery.leaderboard.LeaderboardPeriod;
import com.flameforge.albionmastery.leaderboard.LeaderboardRow;
import com.flameforge.albionmastery.leaderboard.MasteryLeaderboard;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 往期榜单指令：{@code /albionmastery leaderboard history <day|week|month> [期号]}，
 * 挂在 {@link MasteryCommand} 的根下面（权限等级 2）。
 *
 * <p>日 / 周 / 月榜一换期就从头算，按期发奖的服主得能查到上一期的最终名次。
 * 榜单在换期那一刻自动存档（见 {@link MasteryLeaderboard}），这里只负责读出来。
 */
public final class MasteryLeaderboardCommand {

    /** 帮助里列出的指令。左边是用法，右边是说明的语言键。 */
    static final String[][] HELP = {
            {"/albionmastery leaderboard history <day|week|month> [期号]", "command.albionmastery.help_board_history"},
    };

    /** 聊天栏里列前几名，完整的看存档文件。 */
    private static final int CHAT_TOP = 10;

    private MasteryLeaderboardCommand() {
    }

    /**
     * 把指令挂到根指令下。
     *
     * @param root {@code /albionmastery}
     */
    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        LiteralArgumentBuilder<CommandSourceStack> history = Commands.literal("history");
        for (LeaderboardPeriod period : LeaderboardPeriod.values()) {
            if (!period.isDelta()) {
                continue;
            }
            history.then(Commands.literal(period.id())
                    .executes(context -> show(context, period, null))
                    .then(Commands.argument("key", StringArgumentType.word())
                            .suggests((context, builder) -> suggestKeys(context, builder, period))
                            .executes(context -> show(context, period, StringArgumentType.getString(context, "key")))));
        }
        root.then(Commands.literal("leaderboard").then(history));
    }

    private static CompletableFuture<Suggestions> suggestKeys(CommandContext<CommandSourceStack> context,
                                                              SuggestionsBuilder builder, LeaderboardPeriod period) {
        List<MasteryLeaderboard.PastPeriod> list =
                MasteryLeaderboard.get(context.getSource().getServer()).history(period);
        return SharedSuggestionProvider.suggest(list.stream().map(MasteryLeaderboard.PastPeriod::key), builder);
    }

    /**
     * 输出某一期的前几名；不给期号就是最近结束的那一期。
     *
     * @return 这一期有进展的人数（查不到为 0）
     */
    private static int show(CommandContext<CommandSourceStack> context, LeaderboardPeriod period, String key) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        List<MasteryLeaderboard.PastPeriod> list = MasteryLeaderboard.get(server).history(period);
        Component periodName = periodName(period);
        if (list.isEmpty()) {
            source.sendFailure(Component.translatableWithFallback("command.albionmastery.board_history.none",
                    "暂无%s存档：存档在换期时生成，自安装此版本后的第一次换期开始", periodName));
            return 0;
        }
        MasteryLeaderboard.PastPeriod past = key == null ? list.get(0) : null;
        for (MasteryLeaderboard.PastPeriod candidate : list) {
            if (candidate.key().equals(key)) {
                past = candidate;
            }
        }
        if (past == null) {
            source.sendFailure(Component.translatableWithFallback("command.albionmastery.board_history.unknown",
                    "没有 %s 期的%s存档", key, periodName));
            source.sendSuccess(() -> keyList(period, list), false);
            return 0;
        }

        MasteryLeaderboard.PastPeriod shown = past;
        source.sendSuccess(() -> Component.translatableWithFallback("command.albionmastery.board_history.header",
                "%s %s（%s）：共 %s 人有进展，按本期经验排序", periodName, shown.key(), period.describeKey(shown.key()), shown.participants()).withStyle(ChatFormatting.GOLD), false);
        for (int i = 0; i < Math.min(CHAT_TOP, shown.rows().size()); i++) {
            LeaderboardRow row = shown.rows().get(i);
            int rank = i + 1;
            source.sendSuccess(() -> Component.translatableWithFallback("command.albionmastery.board_history.row",
                    "%s. %s  +%s 经验，升 %s 级", rank, row.name(), String.format(Locale.ROOT, "%,d", row.xpSum()), row.levelSum())
                    .withStyle(rank <= 3 ? ChatFormatting.YELLOW : ChatFormatting.WHITE), false);
        }
        String file = MasteryLeaderboard.historyDirectory(server)
                .resolve(period.id() + "-" + shown.key() + ".txt").toString();
        source.sendSuccess(() -> Component.translatableWithFallback("command.albionmastery.board_history.more",
                "完整前 %s 名见服务端文件 %s", shown.rows().size(), file).withStyle(ChatFormatting.GRAY), false);
        if (list.size() > 1) {
            source.sendSuccess(() -> keyList(period, list), false);
        }
        return shown.participants();
    }

    /**
     * 「可查的期：……」，每个期号点一下就查那一期。
     */
    private static Component keyList(LeaderboardPeriod period, List<MasteryLeaderboard.PastPeriod> list) {
        MutableComponent keys = Component.empty();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                keys.append(" ");
            }
            String key = list.get(i).key();
            keys.append(Component.literal(key).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/albionmastery leaderboard history " + period.id() + " " + key))));
        }
        return Component.translatableWithFallback("command.albionmastery.board_history.list", "可查的期：%s", keys)
                .withStyle(ChatFormatting.GRAY);
    }

    /**
     * 范围名（日榜 / 周榜 / 月榜）。
     *
     * <p>指令输出都带中文兜底：专用服不加载模组的语言文件，服主多半在控制台里查，没有兜底只会看到一串语言键。
     */
    private static Component periodName(LeaderboardPeriod period) {
        String fallback = switch (period) {
            case DAY -> "日榜";
            case WEEK -> "周榜";
            case MONTH -> "月榜";
            case ALL -> "总榜";
        };
        return Component.translatableWithFallback(period.nameKey(), fallback);
    }
}
