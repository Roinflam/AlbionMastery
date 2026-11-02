package com.flameforge.albionmastery.client.screen;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模组列表里「配置」按钮打开的 Cloth Config 界面。
 *
 * <p>配置项有几百个（每个特化一组数值），不逐项手写：直接遍历两份 {@link ForgeConfigSpec}，
 * 每个二级小节一页，更深的小节折叠成子分类；输入框按值的类型挑，上下限、默认值、注释都从配置定义里读，
 * 以后配置里加了项，这里自动多出来，不用跟着改。
 *
 * <p>显示名优先用语言文件：普通项 {@code config.albionmastery.<路径>}；特化小节用特化名、
 * 特化里的数值用专精卡片上的维度名、来源倍率用来源名，这几样语言文件里本来就有。都没有就显示键名。
 * 提示文本优先 {@code config.albionmastery.<路径>.tooltip}，没有就用配置文件里的注释。
 *
 * <p>COMMON 配置改的是本机的文件：单人世界保存后由 Forge 的文件监视触发重载，立即生效；
 * 连着别人的服务器时，服务器按它自己的配置算，这里改了不影响服务器，每页开头有一行说明。
 */
public final class MasteryConfigScreen {

    private static final String PREFIX = "config.albionmastery.";

    /** 由模组自己维护、不该给人改的项。 */
    private static final Set<String> HIDDEN = Set.of("mastery.specialization.specBalanceVersion");

    /** 特化小节都在这一节下面，按来源再分一层，不然一百多个小节平铺在一页里没法找。 */
    private static final String SPEC_SECTION = "mastery.specialization";

    private MasteryConfigScreen() {
    }

    /**
     * 在模组列表里挂上配置按钮，只在客户端调用。
     */
    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> create(parent)));
    }

    /**
     * @param parent 关掉后回到的界面
     * @return 配置界面
     */
    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable(PREFIX + "title"))
                .setSavingRunnable(() -> {
                    MasteryConfig.CLIENT_SPEC.save();
                    MasteryConfig.COMMON_SPEC.save();
                });
        Labels labels = Labels.collect();
        new Walker(builder, labels, MasteryConfig.COMMON_SPEC, true).run();
        new Walker(builder, labels, MasteryConfig.CLIENT_SPEC, false).run();
        return builder.build();
    }

    /**
     * 语言文件里本来就有名字的几类配置项，按配置值对象认，不按路径拼。
     */
    private record Labels(Map<Object, Component> byValue, Map<String, Specialization> specBySection,
                          Map<Object, String> sourceByValue) {

        static Labels collect() {
            Map<Object, Component> byValue = new IdentityHashMap<>();
            Map<String, Specialization> specBySection = new HashMap<>();
            Map<Object, String> sourceByValue = new IdentityHashMap<>();
            MasteryConfig.COMMON.sourceMasteryMultipliers.forEach((source, value) -> {
                byValue.put(value, Component.translatable("albionmastery.source." + source));
                sourceByValue.put(value, source);
            });
            for (Specialization spec : Specialization.values()) {
                MasteryConfig.SpecEntry entry = MasteryConfig.specEntry(spec);
                if (entry == null) {
                    continue;
                }
                specBySection.put(spec.configSection(), spec);
                entry.values().forEach((key, value) -> {
                    String stat = "gui.albionmastery.spec_stat." + key;
                    if (I18n.exists(stat)) {
                        byValue.put(value, Component.translatable(stat));
                    }
                });
            }
            return new Labels(byValue, specBySection, sourceByValue);
        }
    }

    /**
     * 遍历一份配置定义，往界面里加页和项。
     */
    private static final class Walker {

        private final ConfigBuilder builder;
        private final ConfigEntryBuilder entries;
        private final Labels labels;
        private final ForgeConfigSpec spec;
        private final boolean common;

        Walker(ConfigBuilder builder, Labels labels, ForgeConfigSpec spec, boolean common) {
            this.builder = builder;
            this.entries = builder.entryBuilder();
            this.labels = labels;
            this.spec = spec;
            this.common = common;
        }

        void run() {
            if (!this.spec.isLoaded()) {
                return;
            }
            // 顶层只是一个总的小节（mastery / client），直接挂在它下面的项单独一页，它的每个子小节各占一页
            for (Map.Entry<String, Object> top : this.spec.getValues().valueMap().entrySet()) {
                List<String> topPath = List.of(top.getKey());
                if (!(top.getValue() instanceof UnmodifiableConfig section)) {
                    continue;
                }
                // 直接挂着的项那一页排在它的子小节前面，Cloth 按第一次建页的先后排分页
                if (section.valueMap().values().stream().anyMatch(v -> !(v instanceof UnmodifiableConfig))) {
                    category(topPath);
                }
                List<AbstractConfigListEntry> direct = new ArrayList<>();
                for (Map.Entry<String, Object> child : section.valueMap().entrySet()) {
                    List<String> path = append(topPath, child.getKey());
                    if (child.getValue() instanceof UnmodifiableConfig sub) {
                        fillCategory(path, sub);
                    } else {
                        addIfPresent(direct, valueEntry(path, child.getValue()));
                    }
                }
                if (!direct.isEmpty()) {
                    ConfigCategory category = category(topPath);
                    direct.forEach(category::addEntry);
                }
            }
        }

        private void fillCategory(List<String> path, UnmodifiableConfig section) {
            ConfigCategory category = category(path);
            String joined = String.join(".", path);
            Map<String, List<AbstractConfigListEntry>> specsBySource = new LinkedHashMap<>();
            for (Map.Entry<String, Object> child : section.valueMap().entrySet()) {
                List<String> childPath = append(path, child.getKey());
                Specialization spec = SPEC_SECTION.equals(joined)
                        ? this.labels.specBySection().get(child.getKey()) : null;
                AbstractConfigListEntry entry = node(childPath, child.getValue());
                if (entry == null) {
                    continue;
                }
                if (spec != null) {
                    specsBySource.computeIfAbsent(spec.sourceId(), k -> new ArrayList<>()).add(entry);
                } else {
                    category.addEntry(entry);
                }
            }
            specsBySource.forEach((source, list) -> category.addEntry(this.entries
                    .startSubCategory(Component.translatable("albionmastery.source." + source), list)
                    .build()));
        }

        private AbstractConfigListEntry node(List<String> path, Object node) {
            if (node instanceof UnmodifiableConfig section) {
                List<AbstractConfigListEntry> children = new ArrayList<>();
                for (Map.Entry<String, Object> child : section.valueMap().entrySet()) {
                    addIfPresent(children, node(append(path, child.getKey()), child.getValue()));
                }
                if (children.isEmpty()) {
                    return null;
                }
                return this.entries.startSubCategory(sectionName(path), children)
                        .setTooltip(sectionTooltip(path))
                        .build();
            }
            return valueEntry(path, node);
        }

        private ConfigCategory category(List<String> path) {
            ConfigCategory category = this.builder.getOrCreateCategory(sectionName(path));
            Component[] tooltip = sectionTooltip(path);
            if (tooltip != null) {
                category.setDescription(tooltip);
            }
            if (this.common && category.getEntries().isEmpty()) {
                category.addEntry(this.entries.startTextDescription(
                                Component.translatable(PREFIX + "common_note").withStyle(ChatFormatting.GRAY))
                        .build());
            }
            return category;
        }

        private Component sectionName(List<String> path) {
            String key = String.join(".", path);
            String section = path.get(path.size() - 1);
            Specialization spec = this.labels.specBySection().get(section);
            if (spec != null) {
                return Component.translatable(spec.nameKey());
            }
            if (I18n.exists(PREFIX + key)) {
                return Component.translatable(PREFIX + key);
            }
            return Component.literal(section);
        }

        private Component[] sectionTooltip(List<String> path) {
            String key = PREFIX + String.join(".", path) + ".tooltip";
            if (I18n.exists(key)) {
                return split(I18n.get(key));
            }
            String comment = this.spec.getLevelComment(path);
            return comment == null || comment.isBlank() ? null : split(comment);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private AbstractConfigListEntry valueEntry(List<String> path, Object node) {
            String key = String.join(".", path);
            if (HIDDEN.contains(key) || !(node instanceof ForgeConfigSpec.ConfigValue<?> value)) {
                return null;
            }
            ForgeConfigSpec.ValueSpec valueSpec = this.spec.getSpec().get(path);
            if (valueSpec == null) {
                return null;
            }
            Component name = valueName(key, path, value);
            Component[] tooltip = valueTooltip(key, valueSpec, value);
            ForgeConfigSpec.Range<?> range = valueSpec.getRange();

            if (value instanceof ForgeConfigSpec.BooleanValue v) {
                return this.entries.startBooleanToggle(name, v.get())
                        .setDefaultValue((Boolean) valueSpec.getDefault())
                        .setTooltip(tooltip).setSaveConsumer(v::set).build();
            }
            if (value instanceof ForgeConfigSpec.IntValue v) {
                var field = this.entries.startIntField(name, v.get())
                        .setDefaultValue((Integer) valueSpec.getDefault())
                        .setTooltip(tooltip).setSaveConsumer(v::set);
                if (range != null) {
                    field.setMin((Integer) range.getMin()).setMax((Integer) range.getMax());
                }
                return field.build();
            }
            if (value instanceof ForgeConfigSpec.LongValue v) {
                var field = this.entries.startLongField(name, v.get())
                        .setDefaultValue((Long) valueSpec.getDefault())
                        .setTooltip(tooltip).setSaveConsumer(v::set);
                if (range != null) {
                    field.setMin((Long) range.getMin()).setMax((Long) range.getMax());
                }
                return field.build();
            }
            if (value instanceof ForgeConfigSpec.DoubleValue v) {
                var field = this.entries.startDoubleField(name, v.get())
                        .setDefaultValue((Double) valueSpec.getDefault())
                        .setTooltip(tooltip).setSaveConsumer(v::set);
                if (range != null) {
                    field.setMin((Double) range.getMin()).setMax((Double) range.getMax());
                }
                return field.build();
            }
            if (value instanceof ForgeConfigSpec.EnumValue v) {
                return enumEntry(name, tooltip, v, valueSpec);
            }
            Object current = value.get();
            if (current instanceof String text) {
                ForgeConfigSpec.ConfigValue<String> v = (ForgeConfigSpec.ConfigValue<String>) value;
                return this.entries.startStrField(name, text)
                        .setDefaultValue((String) valueSpec.getDefault())
                        .setTooltip(tooltip)
                        .setErrorSupplier(s -> invalid(valueSpec, s))
                        .setSaveConsumer(v::set).build();
            }
            if (current instanceof List<?> list && list.stream().allMatch(e -> e instanceof String)) {
                ForgeConfigSpec.ConfigValue<List<String>> v = (ForgeConfigSpec.ConfigValue<List<String>>) value;
                return this.entries.startStrList(name, new ArrayList<>((List<String>) list))
                        .setDefaultValue(new ArrayList<>((List<String>) valueSpec.getDefault()))
                        .setTooltip(tooltip)
                        .setErrorSupplier(l -> invalid(valueSpec, l))
                        .setSaveConsumer(l -> v.set(new ArrayList<>(l)))
                        .build();
            }
            return null;
        }

        private <E extends Enum<E>> AbstractConfigListEntry enumEntry(Component name, Component[] tooltip,
                                                                      ForgeConfigSpec.EnumValue<E> value,
                                                                      ForgeConfigSpec.ValueSpec valueSpec) {
            E current = value.get();
            @SuppressWarnings("unchecked")
            E fallback = (E) valueSpec.getDefault();
            return this.entries.startEnumSelector(name, current.getDeclaringClass(), current)
                    .setDefaultValue(fallback)
                    .setTooltip(tooltip)
                    .setSaveConsumer(value::set)
                    .build();
        }

        private Component valueName(String key, List<String> path, ForgeConfigSpec.ConfigValue<?> value) {
            if (I18n.exists(PREFIX + key)) {
                return Component.translatable(PREFIX + key);
            }
            Component known = this.labels.byValue().get(value);
            return known != null ? known : Component.literal(path.get(path.size() - 1));
        }

        private Component[] valueTooltip(String key, ForgeConfigSpec.ValueSpec valueSpec, Object value) {
            if (I18n.exists(PREFIX + key + ".tooltip")) {
                return split(I18n.get(PREFIX + key + ".tooltip"));
            }
            // 来源倍率一个来源一行、按来源清单生成，语言文件里只有一条共用说明，把来源名填进去；
            // 不然会退回配置文件里那句中文注释
            String source = this.labels.sourceByValue().get(value);
            if (source != null && I18n.exists(PREFIX + "source_multiplier.tooltip")) {
                return split(I18n.get(PREFIX + "source_multiplier.tooltip",
                        I18n.get("albionmastery.source." + source), source));
            }
            // 特化数值的说明按维度共用一份，同一维度在每个特化里意思都一样
            String stat = PREFIX + "spec_value." + key.substring(key.lastIndexOf('.') + 1) + ".tooltip";
            if (key.startsWith(SPEC_SECTION + ".") && I18n.exists(stat)) {
                return split(I18n.get(stat));
            }
            String comment = valueSpec.getComment();
            return comment == null || comment.isBlank() ? null : split(comment);
        }
    }

    private static Optional<Component> invalid(ForgeConfigSpec.ValueSpec valueSpec, Object candidate) {
        return valueSpec.test(candidate) ? Optional.empty()
                : Optional.of(Component.translatable(PREFIX + "invalid"));
    }

    private static Component[] split(String text) {
        return text.lines().map(Component::literal).toArray(Component[]::new);
    }

    private static List<String> append(List<String> path, String child) {
        List<String> result = new ArrayList<>(path.size() + 1);
        result.addAll(path);
        result.add(child);
        return result;
    }

    private static void addIfPresent(List<AbstractConfigListEntry> list, AbstractConfigListEntry entry) {
        if (entry != null) {
            list.add(entry);
        }
    }
}
