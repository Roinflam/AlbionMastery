package com.flameforge.albionmastery.command;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecRules;
import com.flameforge.albionmastery.event.CommonEventHandler;
import com.flameforge.albionmastery.mastery.MasteryAdmin;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.SpecMoveSpeed;
import com.flameforge.albionmastery.mastery.spec.SpecPoints;
import com.flameforge.albionmastery.mastery.spec.SpecTerms;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import com.flameforge.albionmastery.mastery.spec.SpecializationManager;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ModConfigSpec;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * 管理员指令：批量满级、按键改等级、发武器、强制改特化、在线改配置。
 *
 * <p>和老指令不同，这里每一条都带 {@code <玩家>}，服里给别人调也能用；
 * 武器和类型拆成「来源 + ID」两段输入，免得武器键里的 {@code / : #} 逼着人加引号。
 */
public final class MasteryAdminCommand {

    /**
     * 已知的全部来源，补全用；实际有没有装由各自的 isXLoaded 判断。
     *
     * <p>跟着 {@link WeaponKeys#ALL_PROVIDERS} 走，新增来源不用改这里。
     */
    private static final String[] SOURCES = WeaponKeys.ALL_PROVIDERS.toArray(new String[0]);

    /** 配置项路径 -&gt; 配置值，第一次用时从配置规格里走一遍。 */
    @Nullable
    private static Map<String, ModConfigSpec.ConfigValue<?>> configPaths;

    private MasteryAdminCommand() {
    }

    /**
     * 把新指令挂到根指令下面。
     *
     * @param root {@code /albionmastery}
     */
    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(maxNode()).then(levelNode()).then(wipeNode()).then(weaponNode()).then(specNode())
                .then(configNode());
    }

    /**
     * 帮助里列出的新指令。左边是用法，右边是说明的语言键。
     */
    static final String[][] HELP = {
            {"/albionmastery max <玩家>", "command.albionmastery.help_max_all"},
            {"/albionmastery max <玩家> held", "command.albionmastery.help_max_held"},
            {"/albionmastery max <玩家> source <来源>", "command.albionmastery.help_max_source"},
            {"/albionmastery max <玩家> type <来源> <类型>", "command.albionmastery.help_max_type"},
            {"/albionmastery level <玩家> all <等级>", "command.albionmastery.help_level_all"},
            {"/albionmastery level <玩家> held <等级>", "command.albionmastery.help_level_held"},
            {"/albionmastery level <玩家> source <来源> <等级>", "command.albionmastery.help_level_source"},
            {"/albionmastery level <玩家> type <来源> <类型> <等级>", "command.albionmastery.help_level_type"},
            {"/albionmastery level <玩家> weapon <来源> <武器ID> <等级>", "command.albionmastery.help_level_weapon"},
            {"/albionmastery wipe <玩家>", "command.albionmastery.help_wipe"},
            {"/albionmastery weapon give <玩家> <来源> <武器ID>", "command.albionmastery.help_weapon_give"},
            {"/albionmastery weapon count", "command.albionmastery.help_weapon_count"},
            {"/albionmastery spec set <玩家> <来源> <类型> <槽位> <特化>", "command.albionmastery.help_spec_set"},
            {"/albionmastery spec clear <玩家>", "command.albionmastery.help_spec_clear"},
            {"/albionmastery spec cooldown <玩家>", "command.albionmastery.help_spec_cooldown"},
            {"/albionmastery config spec <来源> <特化>", "command.albionmastery.help_config_spec"},
            {"/albionmastery config get <配置项>", "command.albionmastery.help_config_get"},
            {"/albionmastery config set <配置项> <值>", "command.albionmastery.help_config_set"},
            {"/albionmastery config reset <配置项>", "command.albionmastery.help_config_reset"},
    };

    // ======================================================================
    // 参数
    // ======================================================================

    private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> targets() {
        return Commands.argument("targets", EntityArgument.players());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> sourceArg() {
        return Commands.argument("source", StringArgumentType.word()).suggests(MasteryAdminCommand::suggestSources);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> typeArg() {
        return Commands.argument("type", StringArgumentType.string()).suggests(MasteryAdminCommand::suggestTypes);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> weaponArg() {
        return Commands.argument("weapon", ResourceLocationArgument.id()).suggests(MasteryAdminCommand::suggestWeapons);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> levelArg() {
        return Commands.argument("level", IntegerArgumentType.integer(0, 1000));
    }

    private static String source(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "source");
    }

    private static String typeKey(CommandContext<CommandSourceStack> context) {
        return WeaponKeys.typeKey(source(context), StringArgumentType.getString(context, "type"));
    }

    private static String weaponKey(CommandContext<CommandSourceStack> context) {
        return WeaponKeys.weaponKey(source(context), ResourceLocationArgument.getId(context, "weapon"));
    }

    private static int level(CommandContext<CommandSourceStack> context) {
        return IntegerArgumentType.getInteger(context, "level");
    }

    // ======================================================================
    // max / level / wipe
    // ======================================================================

    private static ArgumentBuilder<CommandSourceStack, ?> maxNode() {
        return Commands.literal("max").then(targets()
                .executes(context -> bulkAll(context, MasteryAdmin.MAX))
                .then(Commands.literal("held").executes(context -> held(context, MasteryAdmin.MAX)))
                .then(Commands.literal("source").then(sourceArg()
                        .executes(context -> bulkSource(context, MasteryAdmin.MAX))))
                .then(Commands.literal("type").then(sourceArg().then(typeArg()
                        .executes(context -> bulkType(context, MasteryAdmin.MAX)))))
                .then(Commands.literal("weapon").then(sourceArg().then(weaponArg()
                        .executes(context -> node(context, weaponKey(context), MasteryAdmin.MAX))))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> levelNode() {
        return Commands.literal("level").then(targets()
                .then(Commands.literal("all").then(levelArg()
                        .executes(context -> bulkAll(context, level(context)))))
                .then(Commands.literal("held").then(levelArg()
                        .executes(context -> held(context, level(context)))))
                .then(Commands.literal("source").then(sourceArg().then(levelArg()
                        .executes(context -> node(context, WeaponKeys.overallKey(source(context)), level(context))))))
                .then(Commands.literal("type").then(sourceArg().then(typeArg().then(levelArg()
                        .executes(context -> node(context, typeKey(context), level(context)))))))
                .then(Commands.literal("weapon").then(sourceArg().then(weaponArg().then(levelArg()
                        .executes(context -> node(context, weaponKey(context), level(context))))))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> wipeNode() {
        return Commands.literal("wipe").then(targets()
                .executes(context -> bulkAll(context, 0))
                .then(Commands.literal("source").then(sourceArg()
                        .executes(context -> bulkSource(context, 0))))
                .then(Commands.literal("type").then(sourceArg().then(typeArg()
                        .executes(context -> bulkType(context, 0))))));
    }

    private static int bulkAll(CommandContext<CommandSourceStack> context, int level) throws CommandSyntaxException {
        return forEachTarget(context, (target, data) -> {
            MasteryAdmin.Result result = MasteryAdmin.setAll(target, data, level);
            reportBulk(context, target, result, level);
            return true;
        });
    }

    private static int bulkSource(CommandContext<CommandSourceStack> context, int level)
            throws CommandSyntaxException {
        String source = source(context);
        if (!checkSource(context, source)) {
            return 0;
        }
        return forEachTarget(context, (target, data) -> {
            MasteryAdmin.Result result = MasteryAdmin.setSource(target, data, source, level);
            reportBulk(context, target, result, level);
            return true;
        });
    }

    private static int bulkType(CommandContext<CommandSourceStack> context, int level) throws CommandSyntaxException {
        String source = source(context);
        if (!checkSource(context, source)) {
            return 0;
        }
        String typeKey = typeKey(context);
        if (!checkType(context, typeKey)) {
            return 0;
        }
        return forEachTarget(context, (target, data) -> {
            MasteryAdmin.Result result = MasteryAdmin.setType(target, data, typeKey, level);
            reportBulk(context, target, result, level);
            return true;
        });
    }

    /**
     * 手上武器的本体、类型、来源三层一起设。
     */
    private static int held(CommandContext<CommandSourceStack> context, int level) throws CommandSyntaxException {
        return forEachTarget(context, (target, data) -> {
            ResolvedWeapon weapon = WeaponResolver.resolve(target.getItemInHand(InteractionHand.MAIN_HAND));
            if (weapon == null) {
                context.getSource().sendFailure(Component.translatable(
                        "command.albionmastery.target_no_weapon", target.getDisplayName()));
                return false;
            }
            MasteryAdmin.setWeaponLevel(data, weapon.weaponKey(), weapon.typeKey(), level);
            MasteryAdmin.setTypeLevel(data, weapon.typeKey(), level);
            MasteryAdmin.setSourceLevel(data, weapon.overallKey(), level);
            int actual = data.getWeaponLevel(weapon.weaponKey());
            context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.set_held",
                    target.getDisplayName(), target.getMainHandItem().getHoverName(), actual)
                    .withStyle(ChatFormatting.GOLD), true);
            return true;
        });
    }

    /**
     * 设单个节点：来源、类型或武器，按键的格式分派。
     */
    private static int node(CommandContext<CommandSourceStack> context, String key, int level)
            throws CommandSyntaxException {
        String source = WeaponKeys.providerOf(key);
        if (source == null || !checkSource(context, source)
                || WeaponKeys.isValidTypeKey(key) && !checkType(context, key)) {
            return 0;
        }
        return forEachTarget(context, (target, data) -> {
            if (!MasteryAdmin.setLevel(target, data, key, null, level)) {
                context.getSource().sendFailure(Component.translatable("command.albionmastery.no_type", key));
                return false;
            }
            int actual = MasteryAdmin.levelOf(data, key);
            context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.set_node",
                    target.getDisplayName(), key, actual).withStyle(ChatFormatting.GOLD), true);
            return true;
        });
    }

    private static void reportBulk(CommandContext<CommandSourceStack> context, ServerPlayer target,
                                   MasteryAdmin.Result result, int level) {
        String key = level == 0 ? "command.albionmastery.wiped"
                : level == MasteryAdmin.MAX ? "command.albionmastery.maxed" : "command.albionmastery.set_bulk";
        context.getSource().sendSuccess(() -> Component.translatable(key, target.getDisplayName(),
                result.weapons(), result.types(), result.sources(), level).withStyle(ChatFormatting.GOLD), true);
    }

    // ======================================================================
    // weapon
    // ======================================================================

    private static ArgumentBuilder<CommandSourceStack, ?> weaponNode() {
        return Commands.literal("weapon")
                .then(Commands.literal("give").then(targets().then(sourceArg().then(weaponArg()
                        .executes(MasteryAdminCommand::give)))))
                .then(Commands.literal("count").executes(MasteryAdminCommand::count));
    }

    private static int give(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String weaponKey = weaponKey(context);
        WeaponCatalog.Entry entry = WeaponCatalog.byKey(context.getSource().getServer(), weaponKey);
        if (entry == null) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.unknown_weapon",
                    weaponKey));
            return 0;
        }
        int count = 0;
        for (ServerPlayer target : EntityArgument.getPlayers(context, "targets")) {
            ItemStack given = MasteryAdmin.give(target, entry);
            if (given.isEmpty()) {
                context.getSource().sendFailure(Component.translatable("command.albionmastery.give_failed",
                        weaponKey));
                continue;
            }
            count++;
            context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.given",
                    target.getDisplayName(), given.getDisplayName()).withStyle(ChatFormatting.GOLD), true);
        }
        return count;
    }

    /**
     * 服务端认得多少件武器，按来源分。装了枪包却显示 0，多半是枪包没在服务端加载。
     */
    private static int count(CommandContext<CommandSourceStack> context) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String source : SOURCES) {
            counts.put(source, 0);
        }
        List<WeaponCatalog.Entry> all = WeaponCatalog.all(context.getSource().getServer());
        for (WeaponCatalog.Entry entry : all) {
            counts.merge(entry.weapon().providerId(), 1, Integer::sum);
        }
        StringBuilder detail = new StringBuilder();
        counts.forEach((source, n) -> {
            if (!detail.isEmpty()) {
                detail.append(" · ");
            }
            detail.append(source).append(' ').append(n);
        });
        context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.catalog_count",
                all.size(), detail.toString()).withStyle(ChatFormatting.GRAY), false);
        return all.size();
    }

    // ======================================================================
    // spec
    // ======================================================================

    private static ArgumentBuilder<CommandSourceStack, ?> specNode() {
        return Commands.literal("spec")
                .then(Commands.literal("set").then(targets().then(sourceArg().then(typeArg()
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1, WeaponKeys.SPEC_SLOTS))
                                .then(Commands.argument("spec", StringArgumentType.word())
                                        .suggests(MasteryAdminCommand::suggestSpecs)
                                        .executes(MasteryAdminCommand::specSet)))))))
                .then(Commands.literal("clear").then(targets()
                        .executes(context -> specClear(context, null))
                        .then(sourceArg().then(typeArg()
                                .executes(context -> specClear(context, typeKey(context)))))))
                .then(Commands.literal("cooldown").then(targets().executes(MasteryAdminCommand::specCooldown)))
                // 直接改等级 / 突破，不看点数（造测试号、补偿玩家用）
                .then(Commands.literal("level").then(targets().then(sourceArg()
                        .then(Commands.argument("spec", StringArgumentType.word())
                                .suggests(MasteryAdminCommand::suggestSpecs)
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, SpecRules.MAX_LEVELS))
                                        .executes(MasteryAdminCommand::specLevel))))))
                .then(Commands.literal("breakthrough").then(targets().then(sourceArg().then(typeArg()
                        .then(Commands.argument("spec", StringArgumentType.word())
                                .suggests(MasteryAdminCommand::suggestSpecs)
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, SpecRules.MAX_LEVELS))
                                        .executes(MasteryAdminCommand::specBreakthrough)))))))
                // 替玩家重置：不看也不记冷却
                .then(Commands.literal("respec").then(targets().then(sourceArg()
                        .executes(context -> specRespec(context, null))
                        .then(typeArg().executes(context -> specRespec(context, typeKey(context)))))))
                .then(Commands.literal("points").then(targets().then(sourceArg()
                        .executes(context -> specPoints(context, null))
                        .then(typeArg().executes(context -> specPoints(context, typeKey(context)))))));
    }

    private static int specLevel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String source = source(context);
        Specialization spec = findSpec(source, StringArgumentType.getString(context, "spec"));
        if (spec == null || spec.isStandard()) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.spec_unknown",
                    source, StringArgumentType.getString(context, "spec")));
            return 0;
        }
        int level = Math.min(IntegerArgumentType.getInteger(context, "level"),
                MasteryConfig.serverSnapshot().specRules.normalMax());
        return forEachTarget(context, (target, data) -> {
            MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
            int oldLevel = data.getSpecLevel(spec.key());
            data.setSpecLevel(spec.key(), level);
            // 设完点数为负的话，下次登录会被整份退掉（连玩家自己原来的分配一起），所以直接撤回
            int short_ = -SpecPoints.modPointsAvailable(data, source, snapshot);
            if (short_ > 0) {
                data.setSpecLevel(spec.key(), oldLevel);
                context.getSource().sendFailure(Component.translatable("command.albionmastery.spec_overspend",
                        target.getDisplayName(), SpecTerms.of(source, "point"), short_));
                return false;
            }
            if (level < snapshot.specRules.normalMax()) {
                // 普通版没满级，突破就不成立了
                for (String type : new ArrayList<>(data.getBreakthroughsView().keySet())) {
                    data.setBreakthrough(type, spec.key(), 0);
                }
            }
            SpecializationManager.pruneUnusable(data, MasteryConfig.serverSnapshot().specRules, source, null);
            SpecMoveSpeed.refresh(target);
            context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.spec_level_set",
                    target.getDisplayName(), Component.translatable(spec.nameKey()), level)
                    .withStyle(ChatFormatting.GOLD), true);
            return true;
        });
    }

    private static int specBreakthrough(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String source = source(context);
        String typeKey = typeKey(context);
        Specialization spec = findSpec(source, StringArgumentType.getString(context, "spec"));
        if (spec == null || spec.isStandard()) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.spec_unknown",
                    source, StringArgumentType.getString(context, "spec")));
            return 0;
        }
        if (!checkSource(context, source) || !checkType(context, typeKey)) {
            return 0;
        }
        SpecRules rules = MasteryConfig.serverSnapshot().specRules;
        int star = Math.min(IntegerArgumentType.getInteger(context, "level"), rules.breakthroughMax());
        return forEachTarget(context, (target, data) -> {
            MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
            int oldLevel = data.getSpecLevel(spec.key());
            int oldStar = data.getBreakthrough(typeKey, spec.key());
            if (star > 0 && oldLevel < rules.normalMax()) {
                // 突破要普通版满级：一并补满，免得造出一个界面上解释不通的状态
                data.setSpecLevel(spec.key(), rules.normalMax());
            }
            data.setBreakthrough(typeKey, spec.key(), star);
            // 补满普通版吃改装点、突破吃突破点；哪一份设完为负，下次登录都会被整份退掉，直接撤回
            int modShort = -SpecPoints.modPointsAvailable(data, source, snapshot);
            int breakShort = -SpecPoints.breakthroughPointsAvailable(data, typeKey, snapshot);
            if (modShort > 0 || breakShort > 0) {
                data.setBreakthrough(typeKey, spec.key(), oldStar);
                data.setSpecLevel(spec.key(), oldLevel);
                context.getSource().sendFailure(Component.translatable("command.albionmastery.spec_overspend",
                        target.getDisplayName(), modShort > 0 ? SpecTerms.of(source, "point")
                                : Component.translatable("gui.albionmastery.spec_page.break_points"),
                        modShort > 0 ? modShort : breakShort));
                return false;
            }
            SpecializationManager.pruneUnusable(data, rules, source, typeKey);
            SpecMoveSpeed.refresh(target);
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.albionmastery.spec_breakthrough_set", target.getDisplayName(), typeKey,
                    Component.translatable(spec.nameKey()), star).withStyle(ChatFormatting.GOLD), true);
            return true;
        });
    }

    private static int specRespec(CommandContext<CommandSourceStack> context, @Nullable String typeKey)
            throws CommandSyntaxException {
        String source = source(context);
        if (!checkSource(context, source) || (typeKey != null && !checkType(context, typeKey))) {
            return 0;
        }
        SpecRules rules = MasteryConfig.serverSnapshot().specRules;
        return forEachTarget(context, (target, data) -> {
            if (typeKey == null) {
                data.clearSourceProgress(source);
                SpecializationManager.pruneUnusable(data, rules, source, null);
            } else {
                data.clearTypeBreakthroughs(typeKey);
                SpecializationManager.pruneUnusable(data, rules, source, typeKey);
            }
            SpecMoveSpeed.refresh(target);
            context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.spec_respec",
                    target.getDisplayName(), typeKey == null ? source : typeKey)
                    .withStyle(ChatFormatting.GOLD), true);
            return true;
        });
    }

    private static int specPoints(CommandContext<CommandSourceStack> context, @Nullable String typeKey)
            throws CommandSyntaxException {
        String source = source(context);
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        int count = 0;
        for (ServerPlayer target : EntityArgument.getPlayers(context, "targets")) {
            MasteryData data = MasteryHelper.get(target);
            if (data == null) {
                continue;
            }
            count++;
            int modTotal = SpecPoints.modPointsTotal(data, source, snapshot);
            int modLeft = SpecPoints.modPointsAvailable(data, source, snapshot);
            int rank = SpecializationEffects.rankOf(data, snapshot);
            context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.spec_points",
                    target.getDisplayName(), source, modLeft, modTotal, rank).withStyle(ChatFormatting.GRAY), false);
            if (typeKey != null) {
                int btTotal = SpecPoints.breakthroughPointsTotal(data, typeKey, snapshot);
                int btLeft = SpecPoints.breakthroughPointsAvailable(data, typeKey, snapshot);
                int gate = SpecializationEffects.weaponGate(data, typeKey, snapshot);
                int reduction = SpecializationEffects.rankReduction(data, typeKey, snapshot);
                int maxed = data.getMaxedWeaponCount(typeKey);
                context.getSource().sendSuccess(() -> Component.translatable(
                        "command.albionmastery.spec_points_type", typeKey, btLeft, btTotal, gate, reduction, maxed)
                        .withStyle(ChatFormatting.GRAY), false);
            }
        }
        return count;
    }

    @Nullable
    private static Specialization findSpec(String source, String id) {
        for (Specialization spec : Specialization.forSource(source)) {
            if (spec.id().equals(id)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 强制装上：不看解锁和冷却，但同系、重复照样拦——那两种装上了也不生效，只会让人困惑。
     */
    private static int specSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String source = source(context);
        String specId = StringArgumentType.getString(context, "spec");
        Specialization spec = findSpec(source, specId);
        if (spec == null) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.spec_unknown",
                    source, specId));
            return 0;
        }
        String typeKey = typeKey(context);
        if (!checkSource(context, source) || !checkType(context, typeKey)) {
            return 0;
        }
        int slot = IntegerArgumentType.getInteger(context, "slot");
        return forEachTarget(context, (target, data) -> {
            for (int other = 1; other <= WeaponKeys.SPEC_SLOTS; other++) {
                if (other == slot) {
                    continue;
                }
                Specialization otherSpec = Specialization.byKey(data.getSpecialization(typeKey, other));
                if (!spec.isStandard() && otherSpec == spec) {
                    context.getSource().sendFailure(Component.translatable("msg.albionmastery.spec_duplicate",
                            Component.translatable(spec.nameKey())));
                    return false;
                }
                if (spec.conflictsWith(otherSpec)) {
                    context.getSource().sendFailure(Component.translatable("msg.albionmastery.spec_conflict",
                            Component.translatable(spec.nameKey()), SpecTerms.of(source, "group"),
                            Component.translatable(spec.familyKey())));
                    return false;
                }
            }
            data.setSpecialization(typeKey, slot, spec.isStandard() ? null : spec.key());
            SpecMoveSpeed.refresh(target);
            context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.spec_set",
                    target.getDisplayName(), typeKey, SpecTerms.slot(source, slot),
                    Component.translatable(spec.nameKey())).withStyle(ChatFormatting.GOLD), true);
            return true;
        });
    }

    private static int specClear(CommandContext<CommandSourceStack> context, @Nullable String typeKey)
            throws CommandSyntaxException {
        return forEachTarget(context, (target, data) -> {
            List<String> slotKeys = new ArrayList<>(data.getSpecializationsView().keySet());
            int cleared = 0;
            for (String slotKey : slotKeys) {
                String baseType = WeaponKeys.specBaseKey(slotKey);
                // 指定了类型时，这个类型下「某一件武器的独立配置」也要一起清：
                // 那种配置的键是武器键不是类型键，只比类型键的话会漏掉，指令报了成功但特化还在
                if (typeKey != null && !typeKey.equals(baseType)
                        && !typeKey.equals(MasteryAdmin.typeKeyOf(
                                context.getSource().getServer(), data, baseType, null))) {
                    continue;
                }
                int slot = WeaponKeys.specSlotOf(slotKey);
                data.setSpecialization(baseType, slot, null);
                cleared++;
            }
            SpecMoveSpeed.refresh(target);
            int count = cleared;
            context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.spec_cleared",
                    target.getDisplayName(), count).withStyle(ChatFormatting.GOLD), true);
            return true;
        });
    }

    private static int specCooldown(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int count = 0;
        for (ServerPlayer target : EntityArgument.getPlayers(context, "targets")) {
            SpecializationManager.clearCooldowns(target.getUUID());
            // 重置冷却记在存档里，一并清掉
            MasteryData data = MasteryHelper.get(target);
            if (data != null && data.clearRespecTimes()) {
                CommonEventHandler.syncFull(target);
            }
            count++;
            context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.cooldown_cleared",
                    target.getDisplayName()).withStyle(ChatFormatting.GOLD), true);
        }
        return count;
    }

    // ======================================================================
    // config
    // ======================================================================

    private static ArgumentBuilder<CommandSourceStack, ?> configNode() {
        return Commands.literal("config")
                .then(Commands.literal("get").then(pathArg().executes(MasteryAdminCommand::configGet)))
                .then(Commands.literal("set").then(pathArg()
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .suggests(MasteryAdminCommand::suggestConfigValue)
                                .executes(MasteryAdminCommand::configSet))))
                .then(Commands.literal("reset").then(pathArg().executes(MasteryAdminCommand::configReset)))
                .then(Commands.literal("spec").then(sourceArg()
                        .then(Commands.argument("spec", StringArgumentType.word())
                                .suggests(MasteryAdminCommand::suggestSpecs)
                                .executes(MasteryAdminCommand::specShow)
                                .then(Commands.argument("dim", StringArgumentType.word())
                                        .suggests(MasteryAdminCommand::suggestDims)
                                        .executes(MasteryAdminCommand::specShow)
                                        .then(Commands.argument("number", DoubleArgumentType.doubleArg())
                                                .executes(MasteryAdminCommand::specValueSet))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> pathArg() {
        return Commands.argument("path", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(configPaths().keySet(), builder));
    }

    /**
     * 走一遍 COMMON 配置规格，列出全部配置项。规格在模组构造时就定死了，缓存一次就够。
     */
    private static synchronized Map<String, ModConfigSpec.ConfigValue<?>> configPaths() {
        if (configPaths == null) {
            Map<String, ModConfigSpec.ConfigValue<?>> out = new TreeMap<>();
            walk(MasteryConfig.COMMON_SPEC.getValues(), out);
            configPaths = Collections.unmodifiableMap(out);
        }
        return configPaths;
    }

    private static void walk(UnmodifiableConfig config, Map<String, ModConfigSpec.ConfigValue<?>> out) {
        for (UnmodifiableConfig.Entry entry : config.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig child) {
                walk(child, out);
            } else if (value instanceof ModConfigSpec.ConfigValue<?> configValue) {
                out.put(String.join(".", configValue.getPath()), configValue);
            }
        }
    }

    @Nullable
    private static ModConfigSpec.ConfigValue<?> configValue(CommandContext<CommandSourceStack> context) {
        String path = StringArgumentType.getString(context, "path");
        ModConfigSpec.ConfigValue<?> value = configPaths().get(path);
        if (value == null) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.config_bad_path", path));
        }
        return value;
    }

    @Nullable
    private static ModConfigSpec.ValueSpec valueSpec(ModConfigSpec.ConfigValue<?> value) {
        Object spec = MasteryConfig.COMMON_SPEC.getSpec().get(value.getPath());
        return spec instanceof ModConfigSpec.ValueSpec valueSpec ? valueSpec : null;
    }

    private static int configGet(CommandContext<CommandSourceStack> context) {
        ModConfigSpec.ConfigValue<?> value = configValue(context);
        if (value == null) {
            return 0;
        }
        String path = String.join(".", value.getPath());
        ModConfigSpec.ValueSpec spec = valueSpec(value);
        Object range = spec == null ? null : spec.getRange();
        Component line = range == null
                ? Component.translatable("command.albionmastery.config_value_norange", path,
                        String.valueOf(value.get()), String.valueOf(value.getDefault()))
                : Component.translatable("command.albionmastery.config_value", path,
                        String.valueOf(value.get()), String.valueOf(value.getDefault()), range.toString());
        context.getSource().sendSuccess(() -> editable(line.copy().withStyle(ChatFormatting.GOLD),
                "/albionmastery config set " + path + " "), false);
        if (spec != null && spec.getComment() != null && !spec.getComment().isBlank()) {
            for (String commentLine : spec.getComment().split("\n")) {
                context.getSource().sendSuccess(() -> Component.literal("  " + commentLine)
                        .withStyle(ChatFormatting.GRAY), false);
            }
        }
        return 1;
    }

    private static int configSet(CommandContext<CommandSourceStack> context) {
        ModConfigSpec.ConfigValue<?> value = configValue(context);
        if (value == null) {
            return 0;
        }
        return applyConfig(context, value, StringArgumentType.getString(context, "value").trim());
    }

    @SuppressWarnings("unchecked")
    private static int configReset(CommandContext<CommandSourceStack> context) {
        ModConfigSpec.ConfigValue<?> value = configValue(context);
        if (value == null) {
            return 0;
        }
        // 直接写默认值，不走文字解析：列表类的配置项没法从一行文字解析回来，但恢复默认总是可以的
        String path = String.join(".", value.getPath());
        Object before = value.get();
        Object fallback = value.getDefault();
        ((ModConfigSpec.ConfigValue<Object>) value).set(fallback);
        configChanged(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.config_set",
                path, String.valueOf(before), String.valueOf(fallback)).withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    /**
     * 按这一项的类型把文字解析成值，过一遍规格里的范围校验，写回配置文件并立刻生效。
     */
    @SuppressWarnings("unchecked")
    private static int applyConfig(CommandContext<CommandSourceStack> context, ModConfigSpec.ConfigValue<?> value,
                                   String raw) {
        String path = String.join(".", value.getPath());
        if (value.getDefault() instanceof List<?>) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.config_list", path));
            return 0;
        }
        Object parsed;
        try {
            parsed = parse(value.getDefault(), raw);
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.config_bad_value",
                    path, raw, exception.getMessage() == null ? "" : exception.getMessage()));
            return 0;
        }
        ModConfigSpec.ValueSpec spec = valueSpec(value);
        if (spec != null && !spec.test(parsed)) {
            Object range = spec.getRange();
            context.getSource().sendFailure(Component.translatable("command.albionmastery.config_bad_value",
                    path, raw, range == null ? "" : range.toString()));
            return 0;
        }
        Object before = value.get();
        ((ModConfigSpec.ConfigValue<Object>) value).set(parsed);
        configChanged(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.config_set",
                path, String.valueOf(before), String.valueOf(parsed)).withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static Object parse(Object template, String raw) {
        if (template instanceof Boolean) {
            if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
                return Boolean.parseBoolean(raw);
            }
            throw new IllegalArgumentException("true / false");
        }
        try {
            if (template instanceof Integer) {
                return Integer.parseInt(raw);
            }
            if (template instanceof Long) {
                return Long.parseLong(raw);
            }
            if (template instanceof Double) {
                return Double.parseDouble(raw);
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(template.getClass().getSimpleName());
        }
        if (template instanceof Enum<?> constant) {
            for (Object option : constant.getDeclaringClass().getEnumConstants()) {
                if (((Enum<?>) option).name().equalsIgnoreCase(raw)) {
                    return option;
                }
            }
            List<String> names = new ArrayList<>();
            for (Object option : constant.getDeclaringClass().getEnumConstants()) {
                names.add(((Enum<?>) option).name());
            }
            throw new IllegalArgumentException(String.join(" / ", names));
        }
        if (template instanceof String) {
            return raw;
        }
        throw new IllegalArgumentException("list");
    }

    /**
     * 改完配置：只存文件。
     *
     * <p>{@code save()} 会在当前线程同步发出配置重载事件，刷新快照、清武器清单缓存、全员全量同步、
     * 重算排行榜都由重载处理统一做（经 {@code server.execute} 回主线程）；这里再做一遍只会多一轮全量同步。
     * 之后文件监视多半还会再发一次重载，结果一样。
     */
    private static void configChanged(MinecraftServer server) {
        MasteryConfig.COMMON_SPEC.save();
    }

    /**
     * 某个特化的全部数值，或其中一项。每一行点一下就把改这一项的指令填进聊天框。
     */
    private static int specShow(CommandContext<CommandSourceStack> context) {
        String source = source(context);
        String specId = StringArgumentType.getString(context, "spec");
        Specialization spec = findSpec(source, specId);
        MasteryConfig.SpecEntry entry = spec == null ? null : MasteryConfig.specEntry(spec);
        if (spec == null || entry == null) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.spec_unknown",
                    source, specId));
            return 0;
        }
        String only = null;
        try {
            only = StringArgumentType.getString(context, "dim");
        } catch (IllegalArgumentException ignored) {
            // 没输项名就列全部
        }
        context.getSource().sendSuccess(() -> Component.translatable("command.albionmastery.config_spec_header",
                Component.translatable(spec.nameKey()), source, specId).withStyle(ChatFormatting.GOLD), false);
        int shown = 0;
        for (Map.Entry<String, ModConfigSpec.DoubleValue> dim : new TreeMap<>(entry.values()).entrySet()) {
            if (only != null && !only.equals(dim.getKey())) {
                continue;
            }
            ModConfigSpec.DoubleValue value = dim.getValue();
            Component line = Component.translatable("command.albionmastery.config_spec_line", dim.getKey(),
                    formatNumber(value.get()), formatNumber(value.getDefault()),
                    Component.translatableWithFallback("gui.albionmastery.spec_stat." + dim.getKey(), dim.getKey()));
            String suggestion = "/albionmastery config spec " + source + " " + specId + " " + dim.getKey() + " ";
            context.getSource().sendSuccess(() -> editable(line.copy().withStyle(ChatFormatting.GRAY), suggestion),
                    false);
            shown++;
        }
        if (only != null && shown == 0) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.config_bad_path",
                    source + "_" + specId + "." + only));
        }
        return shown;
    }

    private static int specValueSet(CommandContext<CommandSourceStack> context) {
        String source = source(context);
        String specId = StringArgumentType.getString(context, "spec");
        Specialization spec = findSpec(source, specId);
        MasteryConfig.SpecEntry entry = spec == null ? null : MasteryConfig.specEntry(spec);
        String dim = StringArgumentType.getString(context, "dim");
        ModConfigSpec.DoubleValue value = entry == null ? null : entry.values().get(dim);
        if (value == null) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.config_bad_path",
                    source + "_" + specId + "." + dim));
            return 0;
        }
        return applyConfig(context, value, String.valueOf(DoubleArgumentType.getDouble(context, "number")));
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**
     * 把一行字做成「点一下就把指令填进聊天框」，悬停时提示可以点。
     */
    private static MutableComponent editable(MutableComponent line, String command) {
        return line.withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("command.albionmastery.click_to_edit"))));
    }

    // ======================================================================
    // 补全
    // ======================================================================

    private static CompletableFuture<Suggestions> suggestSources(CommandContext<CommandSourceStack> context,
                                                                 SuggestionsBuilder builder) {
        Set<String> sources = new TreeSet<>();
        for (WeaponCatalog.Entry entry : WeaponCatalog.all(context.getSource().getServer())) {
            sources.add(entry.weapon().providerId());
        }
        for (MasteryData data : targetData(context)) {
            sources.addAll(data.getOverallsView().keySet());
        }
        if (sources.isEmpty()) {
            Collections.addAll(sources, SOURCES);
        }
        return SharedSuggestionProvider.suggest(sources, builder);
    }

    private static CompletableFuture<Suggestions> suggestTypes(CommandContext<CommandSourceStack> context,
                                                               SuggestionsBuilder builder) {
        String source = source(context);
        Set<String> types = new TreeSet<>();
        for (WeaponCatalog.Entry entry : WeaponCatalog.ofSource(context.getSource().getServer(), source)) {
            types.add(entry.weapon().type());
        }
        for (MasteryData data : targetData(context)) {
            for (String typeKey : data.getTypesView().keySet()) {
                if (source.equals(WeaponKeys.providerOf(typeKey))) {
                    types.add(WeaponKeys.typeOf(typeKey));
                }
            }
        }
        List<String> escaped = new ArrayList<>(types.size());
        for (String type : types) {
            escaped.add(StringArgumentType.escapeIfRequired(type));
        }
        return SharedSuggestionProvider.suggest(escaped, builder);
    }

    private static CompletableFuture<Suggestions> suggestWeapons(CommandContext<CommandSourceStack> context,
                                                                 SuggestionsBuilder builder) {
        String source = source(context);
        Set<ResourceLocation> ids = new TreeSet<>();
        for (WeaponCatalog.Entry entry : WeaponCatalog.ofSource(context.getSource().getServer(), source)) {
            ids.add(entry.weapon().weaponId());
        }
        String prefix = source + "/";
        for (MasteryData data : targetData(context)) {
            for (String weaponKey : data.getWeaponsView().keySet()) {
                ResourceLocation id = weaponKey.startsWith(prefix)
                        ? ResourceLocation.tryParse(weaponKey.substring(prefix.length())) : null;
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return SharedSuggestionProvider.suggestResource(ids, builder);
    }

    private static CompletableFuture<Suggestions> suggestSpecs(CommandContext<CommandSourceStack> context,
                                                               SuggestionsBuilder builder) {
        List<String> ids = new ArrayList<>();
        for (Specialization spec : Specialization.forSource(source(context))) {
            ids.add(spec.id());
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    }

    private static CompletableFuture<Suggestions> suggestDims(CommandContext<CommandSourceStack> context,
                                                              SuggestionsBuilder builder) {
        Specialization spec = findSpec(source(context), StringArgumentType.getString(context, "spec"));
        MasteryConfig.SpecEntry entry = spec == null ? null : MasteryConfig.specEntry(spec);
        return entry == null ? builder.buildFuture()
                : SharedSuggestionProvider.suggest(new TreeSet<>(entry.values().keySet()), builder);
    }

    private static CompletableFuture<Suggestions> suggestConfigValue(CommandContext<CommandSourceStack> context,
                                                                     SuggestionsBuilder builder) {
        ModConfigSpec.ConfigValue<?> value = configPaths().get(StringArgumentType.getString(context, "path"));
        if (value == null) {
            return builder.buildFuture();
        }
        Object template = value.getDefault();
        List<String> options = new ArrayList<>();
        if (template instanceof Boolean) {
            options.add("true");
            options.add("false");
        } else if (template instanceof Enum<?> constant) {
            for (Object option : constant.getDeclaringClass().getEnumConstants()) {
                options.add(((Enum<?>) option).name());
            }
        } else {
            // 数字：把当前值和默认值给出来，改的时候有个参照
            options.add(String.valueOf(value.get()));
            options.add(String.valueOf(template));
        }
        return SharedSuggestionProvider.suggest(options, builder);
    }

    /**
     * 补全阶段的目标玩家数据。目标参数可能还没输完，解析失败就当没有。
     */
    private static List<MasteryData> targetData(CommandContext<CommandSourceStack> context) {
        List<MasteryData> result = new ArrayList<>();
        try {
            Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "targets");
            for (ServerPlayer player : players) {
                MasteryData data = MasteryHelper.get(player);
                if (data != null) {
                    result.add(data);
                }
            }
        } catch (Exception ignored) {
            // 这条指令没有目标参数（config spec），或者还没输完
        }
        return result;
    }

    // ======================================================================
    // 工具
    // ======================================================================

    private static boolean checkSource(CommandContext<CommandSourceStack> context, String source) {
        // 只认已知的几个来源：打错一个字母就会凭空建出一条总体记录，还会被排行榜算进满级数
        if (!WeaponKeys.isValidOverallKey(source) || !List.of(SOURCES).contains(source)) {
            context.getSource().sendFailure(Component.translatable("command.albionmastery.bad_source", source));
            return false;
        }
        return true;
    }

    /**
     * 类型必须是服务端清单里有的，或者某个目标玩家已经有记录的，免得打错字建出一条孤儿类型记录。
     */
    private static boolean checkType(CommandContext<CommandSourceStack> context, String typeKey) {
        for (WeaponCatalog.Entry entry : WeaponCatalog.ofSource(context.getSource().getServer(),
                WeaponKeys.providerOf(typeKey))) {
            if (entry.weapon().typeKey().equals(typeKey)) {
                return true;
            }
        }
        for (MasteryData data : targetData(context)) {
            if (data.getTypesView().containsKey(typeKey)) {
                return true;
            }
            for (MasteryData.WeaponRecord record : data.getWeaponsView().values()) {
                if (typeKey.equals(record.typeKey)) {
                    return true;
                }
            }
        }
        context.getSource().sendFailure(Component.translatable("command.albionmastery.bad_type", typeKey));
        return false;
    }

    @FunctionalInterface
    private interface TargetAction {
        /**
         * @return 这名玩家处理成功返回 true
         */
        boolean apply(ServerPlayer target, MasteryData data) throws CommandSyntaxException;
    }

    /**
     * 对每个目标玩家做一次，成功的各自同步一次。
     *
     * @return 成功的人数
     */
    private static int forEachTarget(CommandContext<CommandSourceStack> context, TargetAction action)
            throws CommandSyntaxException {
        int count = 0;
        for (ServerPlayer target : EntityArgument.getPlayers(context, "targets")) {
            MasteryData data = MasteryHelper.get(target);
            if (data == null) {
                continue;
            }
            if (action.apply(target, data)) {
                MasteryAdmin.commit(target);
                count++;
            }
        }
        return count;
    }
}
