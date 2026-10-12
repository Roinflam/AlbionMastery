package com.flameforge.albionmastery.command;

import com.flameforge.albionmastery.api.MasteryRankApi;
import com.flameforge.albionmastery.mastery.rank.MasteryRank;
import com.flameforge.albionmastery.network.NetworkHandler;
import com.flameforge.albionmastery.network.S2CMasteryRankUpPacket;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * 段位指令：{@code /albionmastery rank ...}，挂在 {@link MasteryCommand} 的根下面（权限等级 2）。
 *
 * <p>{@code get} / {@code getxp} 把结果作为指令返回值，配合
 * {@code /execute store result score ...} 就能把段位写进计分板，给命令方块、数据包、任务模组的指令奖励用。
 */
public final class MasteryRankCommand {

    /** 帮助里列出的段位指令。左边是用法，右边是说明的语言键。 */
    static final String[][] HELP = {
            {"/albionmastery rank", "command.albionmastery.help_rank"},
            {"/albionmastery rank get <玩家>", "command.albionmastery.help_rank_get"},
            {"/albionmastery rank getxp <玩家>", "command.albionmastery.help_rank_getxp"},
            {"/albionmastery rank preview <段位>", "command.albionmastery.help_rank_preview"},
    };

    private MasteryRankCommand() {
    }

    /**
     * 把段位指令挂到根指令下。
     *
     * @param root {@code /albionmastery}
     */
    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("rank")
                .executes(MasteryRankCommand::executeSelf)
                .then(Commands.literal("get")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(MasteryRankCommand::executeGet)))
                .then(Commands.literal("getxp")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(MasteryRankCommand::executeGetXp)))
                // 只播动画，不改任何数据：给服主看效果、截图用
                .then(Commands.literal("preview")
                        .then(Commands.argument("rank", IntegerArgumentType.integer(0, MasteryRank.MAX_RANK))
                                .executes(MasteryRankCommand::executePreview))));
    }

    /**
     * 显示自己的段位详情。
     */
    private static int executeSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        describe(context.getSource(), player);
        return MasteryRankApi.getRank(player);
    }

    /**
     * 查询某名玩家的段位，返回值是段位。
     */
    private static int executeGet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        describe(context.getSource(), target);
        return MasteryRankApi.getRank(target);
    }

    /**
     * 查询某名玩家的段位经验，返回值是段位经验（超过 int 上限时封顶）。
     */
    private static int executeGetXp(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        describe(context.getSource(), target);
        return (int) Math.min(Integer.MAX_VALUE, MasteryRankApi.getRankXp(target));
    }

    /**
     * 给自己播一次某个段位的晋升动画。
     */
    private static int executePreview(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int rank = IntegerArgumentType.getInteger(context, "rank");
        NetworkHandler.sendTo(player, new S2CMasteryRankUpPacket(Math.max(0, rank - 1), rank, false));
        return rank;
    }

    /**
     * 输出一名玩家的段位、段位经验和离下一段还差多少。
     *
     * @param source 指令来源
     * @param target 玩家
     */
    private static void describe(CommandSourceStack source, ServerPlayer target) {
        long xp = MasteryRankApi.getRankXp(target);
        int rank = MasteryRank.rankFor(xp);
        Component name = MasteryRank.name(rank).copy()
                .withStyle(style -> style.withColor(TextColor.fromRgb(MasteryRank.chatColor(rank))).withBold(true));
        source.sendSuccess(() -> Component.translatable("command.albionmastery.rank.info",
                target.getDisplayName(), name, rank, String.format(Locale.ROOT, "%,d", xp)).withStyle(ChatFormatting.GOLD), false);
        if (rank < MasteryRank.MAX_RANK) {
            long next = MasteryRank.xpForRank(rank + 1);
            source.sendSuccess(() -> Component.translatable("command.albionmastery.rank.next",
                    MasteryRank.name(rank + 1), String.format(Locale.ROOT, "%,d", next), String.format(Locale.ROOT, "%,d", next - xp))
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }
}
