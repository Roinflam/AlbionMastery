package com.flameforge.albionmastery.command;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.event.CommonEventHandler;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * 管理 / 调试指令：{@code /albionmastery ...}
 *
 * <p>全部指令都要求权限等级 2，且只作用于执行者自己。等级 100 的系统靠打是测不完的，
 * 这几条指令主要给界面调试用。
 */
public final class MasteryCommand {

    /** 指令所需的权限等级。 */
    private static final int PERMISSION_LEVEL = 2;

    private MasteryCommand() {
    }

    /**
     * 注册指令。
     *
     * @param dispatcher 指令分发器
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("albionmastery")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                // 只敲根指令就把所有用法列出来，不用去翻文档或者靠 Tab 一层层试
                .executes(MasteryCommand::executeHelp)
                .then(Commands.literal("help").executes(MasteryCommand::executeHelp))
                .then(Commands.literal("info").executes(MasteryCommand::executeInfo))
                .then(Commands.literal("addxp")
                        .then(Commands.argument("amount", LongArgumentType.longArg(1L, 1_000_000_000L))
                                .executes(MasteryCommand::executeAddXp)))
                .then(Commands.literal("setlevel")
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 1000))
                                .executes(context -> executeSetLevel(context, Scope.WEAPON))))
                .then(Commands.literal("settypelevel")
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 1000))
                                .executes(context -> executeSetLevel(context, Scope.TYPE))))
                .then(Commands.literal("setoveralllevel")
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 1000))
                                .executes(context -> executeSetLevel(context, Scope.OVERALL))))
                .then(Commands.literal("setall")
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 1000))
                                .executes(context -> executeSetLevel(context, Scope.ALL))))
                .then(Commands.literal("reset").executes(MasteryCommand::executeReset))

                // give：给别人加经验。上面那些老指令只作用于「执行者自己手上那把」，
                // 服里带新人的时候完全不够用
                .then(Commands.literal("give")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.literal("held")
                                        .then(Commands.argument("amount",
                                                        LongArgumentType.longArg(1L, 1_000_000_000L))
                                                .executes(MasteryCommand::executeGiveHeld)))
                                .then(Commands.literal("key")
                                        .then(Commands.argument("weapon", StringArgumentType.string())
                                                .suggests(MasteryCommand::suggestWeaponKeys)
                                                .then(Commands.argument("amount",
                                                                LongArgumentType.longArg(1L, 1_000_000_000L))
                                                        .executes(MasteryCommand::executeGiveKey))))))

                // study：学习点，玩家获得专精经验时的倍率
                .then(Commands.literal("study")
                        .then(Commands.literal("query")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(MasteryCommand::executeStudyQuery)))
                        .then(Commands.literal("set")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("multiplier",
                                                        DoubleArgumentType.doubleArg(0.0D, 100.0D))
                                                .executes(MasteryCommand::executeStudySet))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(MasteryCommand::executeStudyReset))));
        // 批量满级、发武器、改特化、改配置这些管理指令放在另一个类里，挂到同一个根下面
        MasteryAdminCommand.attach(root);
        dispatcher.register(root);
    }

    /**
     * 所有子指令的用法与说明。
     *
     * <p>左边是用法原文（不翻译，照着敲就能用），右边是说明的语言键。
     */
    private static final String[][] HELP_ENTRIES = {
            {"/albionmastery info", "command.albionmastery.help_info"},
            {"/albionmastery addxp <数量>", "command.albionmastery.help_addxp"},
            {"/albionmastery setlevel <等级>", "command.albionmastery.help_setlevel"},
            {"/albionmastery settypelevel <等级>", "command.albionmastery.help_settypelevel"},
            {"/albionmastery setoveralllevel <等级>", "command.albionmastery.help_setoveralllevel"},
            {"/albionmastery setall <等级>", "command.albionmastery.help_setall"},
            {"/albionmastery reset", "command.albionmastery.help_reset"},
            {"/albionmastery give <玩家> held <数量>", "command.albionmastery.help_give_held"},
            {"/albionmastery give <玩家> key <武器键> <数量>", "command.albionmastery.help_give_key"},
            {"/albionmastery study query <玩家>", "command.albionmastery.help_study_query"},
            {"/albionmastery study set <玩家> <倍率>", "command.albionmastery.help_study_set"},
            {"/albionmastery study reset <玩家>", "command.albionmastery.help_study_reset"},
    };

    /**
     * 输出全部指令帮助。
     *
     * @param context 指令上下文
     * @return 输出的条数
     */
    private static int executeHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.albionmastery.help_title").withStyle(ChatFormatting.GOLD), false);
        List<String[]> entries = new ArrayList<>(Arrays.asList(HELP_ENTRIES));
        entries.addAll(Arrays.asList(MasteryAdminCommand.HELP));
        for (String[] entry : entries) {
            // 用法用可点击的建议：点一下直接填进聊天框，省得手敲长指令
            Component line = Component.literal(entry[0])
                    .withStyle(style -> style.withColor(ChatFormatting.YELLOW)
                            .withClickEvent(new ClickEvent(
                                    ClickEvent.Action.SUGGEST_COMMAND, entry[0])))
                    .copy()
                    .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable(entry[1]).withStyle(ChatFormatting.GRAY));
            context.getSource().sendSuccess(() -> line, false);
        }
        return entries.size();
    }

    /**
     * 给指定玩家手上那把武器加经验。
     *
     * @param context 指令上下文
     * @return 成功处理的玩家数
     */
    private static int executeGiveHeld(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        long amount = LongArgumentType.getLong(context, "amount");
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();

        int count = 0;
        for (ServerPlayer target : targets) {
            ResolvedWeapon weapon = WeaponResolver.resolve(
                    target.getItemInHand(InteractionHand.MAIN_HAND));
            if (weapon == null) {
                context.getSource().sendFailure(Component.translatable(
                        "command.albionmastery.target_no_weapon", target.getDisplayName()));
                continue;
            }
            MasteryData data = MasteryHelper.get(target);
            if (data == null) {
                continue;
            }
            // 指令给的经验不乘学习点：写多少进多少，不然管理员自己都算不明白
            MasteryHelper.awardXp(data, weapon, amount, snapshot, false);
            CommonEventHandler.syncFull(target);
            count++;
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.albionmastery.give", amount, weapon.weaponKey(),
                    target.getDisplayName()).withStyle(ChatFormatting.GOLD), true);
        }
        return count;
    }

    /**
     * 给指定玩家的某一把武器加经验，武器用完整键指定。
     *
     * <p>只认这名玩家<b>已经有记录</b>的武器：类型键存在武器记录里，没有记录就不知道该往哪个
     * 类型上加，硬猜只会把经验加到一个错误的类型上。让玩家先用一次，或者改用 held 模式。
     *
     * @param context 指令上下文
     * @return 成功处理的玩家数
     */
    private static int executeGiveKey(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        String weaponKey = StringArgumentType.getString(context, "weapon");
        long amount = LongArgumentType.getLong(context, "amount");
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();

        if (!WeaponKeys.isValidWeaponKey(weaponKey)) {
            context.getSource().sendFailure(Component.translatable(
                    "command.albionmastery.bad_key", weaponKey));
            return 0;
        }

        int count = 0;
        for (ServerPlayer target : targets) {
            MasteryData data = MasteryHelper.get(target);
            if (data == null) {
                continue;
            }
            MasteryData.WeaponRecord record = data.getWeaponsView().get(weaponKey);
            String typeKey = record == null ? null : record.typeKey;
            if (typeKey == null) {
                context.getSource().sendFailure(Component.translatable(
                        "command.albionmastery.no_record", target.getDisplayName(), weaponKey));
                continue;
            }
            ResolvedWeapon weapon = ResolvedWeapon.ofKeys(weaponKey, typeKey);
            if (weapon == null) {
                context.getSource().sendFailure(Component.translatable(
                        "command.albionmastery.bad_key", weaponKey));
                continue;
            }
            MasteryHelper.awardXp(data, weapon, amount, snapshot, false);
            CommonEventHandler.syncFull(target);
            count++;
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.albionmastery.give", amount, weaponKey,
                    target.getDisplayName()).withStyle(ChatFormatting.GOLD), true);
        }
        return count;
    }

    /**
     * 补全武器键。
     *
     * <p>候选来自「目标玩家已经练过的武器」加上「执行者自己手上那把」。
     * 不去枚举所有已安装枪包刀包里的武器：那份清单只有客户端有，服务端拿不到，
     * 而且几百上千条补全出来也没法用。
     *
     * @param context 指令上下文
     * @param builder 补全构建器
     * @return 补全结果
     */
    private static CompletableFuture<Suggestions> suggestWeaponKeys(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> keys = new TreeSet<>();
        try {
            for (ServerPlayer target : EntityArgument.getPlayers(context, "targets")) {
                MasteryData data = MasteryHelper.get(target);
                if (data != null) {
                    keys.addAll(data.getWeaponsView().keySet());
                }
            }
        } catch (Exception ignored) {
            // 补全阶段目标参数可能还没输完，解析失败很正常，退回到只补自己手上那把
        }
        ServerPlayer self = context.getSource().getPlayer();
        if (self != null) {
            ResolvedWeapon held = WeaponResolver.resolve(
                    self.getItemInHand(InteractionHand.MAIN_HAND));
            if (held != null) {
                keys.add(held.weaponKey());
            }
        }
        return SharedSuggestionProvider.suggest(keys, builder);
    }

    /**
     * 查询学习点。
     *
     * @param context 指令上下文
     * @return 查询到的玩家数
     */
    private static int executeStudyQuery(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        int count = 0;
        for (ServerPlayer target : EntityArgument.getPlayers(context, "targets")) {
            MasteryData data = MasteryHelper.get(target);
            if (data == null) {
                continue;
            }
            double multiplier = data.getStudyMultiplier();
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.albionmastery.study_query", target.getDisplayName(),
                    formatMultiplier(multiplier)).withStyle(ChatFormatting.GRAY), false);
            count++;
        }
        return count;
    }

    /**
     * 设置学习点。
     *
     * @param context 指令上下文
     * @return 设置成功的玩家数
     */
    private static int executeStudySet(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        double multiplier = DoubleArgumentType.getDouble(context, "multiplier");
        return applyStudy(context, multiplier);
    }

    /**
     * 把学习点恢复成默认的 1 倍。
     *
     * @param context 指令上下文
     * @return 重置成功的玩家数
     */
    private static int executeStudyReset(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyStudy(context, 1.0D);
    }

    /**
     * 给一批玩家设置学习点，并各自提示本人。
     *
     * @param context    指令上下文
     * @param multiplier 倍率
     * @return 处理成功的玩家数
     */
    private static int applyStudy(CommandContext<CommandSourceStack> context, double multiplier)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        int count = 0;
        for (ServerPlayer target : EntityArgument.getPlayers(context, "targets")) {
            MasteryData data = MasteryHelper.get(target);
            if (data == null) {
                continue;
            }
            data.setStudyMultiplier(multiplier);
            CommonEventHandler.syncFull(target);
            // 当事人自己要知道：不然经验涨得快了慢了都莫名其妙
            target.displayClientMessage(Component.translatable(
                            "msg.albionmastery.study_changed", formatMultiplier(multiplier))
                    .withStyle(ChatFormatting.GOLD), false);
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.albionmastery.study_set", target.getDisplayName(),
                    formatMultiplier(multiplier)).withStyle(ChatFormatting.GOLD), true);
            count++;
        }
        return count;
    }

    /**
     * 把 0.185 这样的比例写成 {@code +18.50%}。
     *
     * @param value 比例
     * @return 文本
     */
    private static String percent(double value) {
        return String.format(Locale.ROOT, "%+.2f%%", value * 100.0D);
    }

    /**
     * 把倍率写成好读的样子：整数不带小数点，其余保留两位且去掉末尾的零。
     *
     * @param multiplier 倍率
     * @return 文本，例如 {@code 2} 或 {@code 1.5}
     */
    private static String formatMultiplier(double multiplier) {
        if (multiplier == Math.rint(multiplier)) {
            return String.valueOf((long) multiplier);
        }
        return String.format(Locale.ROOT, "%.2f", multiplier)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**
     * 指令作用范围。
     */
    private enum Scope {
        /** 只设定单件武器。 */
        WEAPON,
        /** 只设定武器类型。 */
        TYPE,
        /** 只设定总体。 */
        OVERALL,
        /** 三层一起设定。 */
        ALL
    }

    /**
     * 显示当前手持武器的专精明细。
     *
     * @param context 指令上下文
     * @return 结果码
     */
    private static int executeInfo(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = tryGetPlayer(context);
        if (player == null) {
            return 0;
        }
        ResolvedWeapon weapon = getHeldWeapon(context, player);
        if (weapon == null) {
            return 0;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return 0;
        }

        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        MasteryHelper.Bonus bonus = MasteryHelper.computeBonus(data, weapon, snapshot);

        context.getSource().sendSuccess(() ->
                Component.literal(weapon.weaponKey()).withStyle(ChatFormatting.AQUA), false);
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.albionmastery.info_levels",
                weapon.overallKey(), bonus.overallLevel(),
                weapon.typeKey(), bonus.typeLevel(), bonus.weaponLevel())
                .withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.albionmastery.info_bonus",
                percent(bonus.total()), percent(bonus.fromOverall()), percent(bonus.fromType()),
                percent(bonus.fromSelf()), percent(bonus.fromOthers()))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /**
     * 给当前手持武器增加经验（会按配置比例同时分给类型与总体）。
     *
     * @param context 指令上下文
     * @return 结果码
     */
    private static int executeAddXp(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = tryGetPlayer(context);
        if (player == null) {
            return 0;
        }
        ResolvedWeapon weapon = getHeldWeapon(context, player);
        if (weapon == null) {
            return 0;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return 0;
        }

        long amount = LongArgumentType.getLong(context, "amount");
        MasteryHelper.awardXp(data, weapon, amount, MasteryConfig.serverSnapshot(), false);
        CommonEventHandler.syncFull(player);
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.albionmastery.addxp", amount, weapon.weaponKey())
                .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    /**
     * 按范围直接设定等级。
     *
     * @param context 指令上下文
     * @param scope   作用范围
     * @return 结果码
     */
    private static int executeSetLevel(CommandContext<CommandSourceStack> context, Scope scope) {
        ServerPlayer player = tryGetPlayer(context);
        if (player == null) {
            return 0;
        }
        ResolvedWeapon weapon = getHeldWeapon(context, player);
        if (weapon == null) {
            return 0;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return 0;
        }

        int level = IntegerArgumentType.getInteger(context, "level");
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        // 三层曲线不同，设等级要各按各的曲线换算所需经验
        if (scope == Scope.WEAPON || scope == Scope.ALL) {
            data.setWeaponXp(weapon.weaponKey(), weapon.typeKey(),
                    snapshot.weaponCurve().totalXpToReach(level), snapshot.weaponCurve(), true);
        }
        if (scope == Scope.TYPE || scope == Scope.ALL) {
            data.setTypeXp(weapon.typeKey(),
                    snapshot.typeCurve().totalXpToReach(level), snapshot.typeCurve(), true);
        }
        if (scope == Scope.OVERALL || scope == Scope.ALL) {
            data.setOverallXp(weapon.overallKey(),
                    snapshot.overallCurve().totalXpToReach(level), snapshot.overallCurve(), true);
        }
        CommonEventHandler.syncFull(player);

        String target = switch (scope) {
            case WEAPON -> weapon.weaponKey();
            case TYPE -> weapon.typeKey();
            case OVERALL -> weapon.overallKey();
            case ALL -> weapon.weaponKey() + " + " + weapon.typeKey() + " + " + weapon.overallKey();
        };
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.albionmastery.setlevel", target, level)
                .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    /**
     * 清空执行者的全部专精数据。
     *
     * @param context 指令上下文
     * @return 结果码
     */
    private static int executeReset(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = tryGetPlayer(context);
        if (player == null) {
            return 0;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return 0;
        }
        data.clear();
        CommonEventHandler.syncFull(player);
        context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.reset")
                .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    /**
     * 从指令来源取出执行者玩家。
     *
     * @param context 指令上下文
     * @return 玩家；非玩家来源返回 null
     */
    @Nullable
    private static ServerPlayer tryGetPlayer(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.player_only"));
        }
        return player;
    }

    /**
     * 取出玩家主手持有的可专精武器，取不到时输出失败提示。
     *
     * @param context 指令上下文
     * @param player  玩家
     * @return 武器定位；主手不是可专精武器时返回 null
     */
    @Nullable
    private static ResolvedWeapon getHeldWeapon(CommandContext<CommandSourceStack> context, ServerPlayer player) {
        ResolvedWeapon weapon = WeaponResolver.resolve(player.getItemInHand(InteractionHand.MAIN_HAND));
        if (weapon == null) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.no_gun"));
        }
        return weapon;
    }
}
