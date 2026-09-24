package com.flameforge.albionmastery.mastery;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 维度经验加成。
 *
 * <p>去更危险的地方打怪应该更划算，所以按所在维度给一个整体倍率：
 * <ul>
 *     <li>主世界 ×1</li>
 *     <li>下界 ×1.5</li>
 *     <li>末地 ×2</li>
 *     <li>模组维度 ×2 —— 判定方式是「维度 ID 的命名空间不是 {@code minecraft}」，
 *         装什么维度模组都自动生效，不需要逐个登记</li>
 * </ul>
 *
 * <p>需要单独调某个维度的，用配置里的覆盖列表，优先级高于上面所有规则。
 */
public final class DimensionXpBonus {

    /** 覆盖列表解析后的结果，维度 ID -> 倍率。 */
    private static Map<String, Double> overrides = Map.of();

    private DimensionXpBonus() {
    }

    /**
     * 重新解析配置里的覆盖列表，在配置加载 / 重载时调用。
     */
    public static void reload() {
        Map<String, Double> parsed = new HashMap<>();
        for (String raw : MasteryConfig.COMMON.dimensionXpMultipliers.get()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            int split = raw.lastIndexOf('=');
            if (split <= 0 || split + 1 >= raw.length()) {
                AlbionMastery.LOGGER.warn("维度经验倍率配置格式不对，应为「维度ID=倍率」: {}", raw);
                continue;
            }
            String id = raw.substring(0, split).trim().toLowerCase(Locale.ROOT);
            try {
                double value = Double.parseDouble(raw.substring(split + 1).trim());
                // parseDouble 认 Infinity 和 NaN：无穷大会让每次击杀给满 Long.MAX 的经验，NaN 会一路传下去
                if (!Double.isFinite(value) || value < 0.0D) {
                    AlbionMastery.LOGGER.warn("维度经验倍率必须是非负的有限数字: {}", raw);
                    continue;
                }
                parsed.put(id, value);
            } catch (NumberFormatException exception) {
                AlbionMastery.LOGGER.warn("维度经验倍率不是合法数字: {}", raw);
            }
        }
        overrides = Map.copyOf(parsed);
        if (!overrides.isEmpty()) {
            AlbionMastery.LOGGER.info("已加载 {} 条维度经验倍率覆盖", overrides.size());
        }
    }

    /**
     * 查询某个维度的经验倍率。
     *
     * @param level 维度
     * @return 倍率，查不到时为 1.0
     */
    public static double multiplierFor(Level level) {
        if (level == null) {
            return 1.0D;
        }
        ResourceKey<Level> key = level.dimension();
        ResourceLocation id = key.location();

        Double override = overrides.get(id.toString());
        if (override != null) {
            return override;
        }
        if (Level.NETHER.equals(key)) {
            return MasteryConfig.COMMON.netherXpMultiplier.get();
        }
        if (Level.END.equals(key)) {
            return MasteryConfig.COMMON.endXpMultiplier.get();
        }
        if (!"minecraft".equals(id.getNamespace())) {
            // 命名空间不是原版的，一律按模组维度算
            return MasteryConfig.COMMON.moddedDimensionXpMultiplier.get();
        }
        return 1.0D;
    }
}
