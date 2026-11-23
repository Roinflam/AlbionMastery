package com.flameforge.albionmastery.weapon.compat;

import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 附属法术提示框里的伤害数字按专精倍率显示。
 *
 * <p>附属法术的专精倍率不进法术强度，只在受伤事件里乘伤害，所以提示框上的伤害不会变。
 * 这里只改翻译键里带 damage 的那几行，范围、持续时间实际不加，照原样显示。
 */
public final class AddonSpellTooltip {

    private AddonSpellTooltip() {
    }

    public static List<MutableComponent> scale(AbstractSpell spell, Entity caster, List<MutableComponent> lines) {
        if (lines == null || lines.isEmpty() || IronsSpellsSupport.scalesSpellPower(spell)
                || !(caster instanceof Player player)) {
            return lines;
        }
        double multiplier = IronsSpellsSupport.powerMultiplier(player, spell);
        if (Math.abs(multiplier - 1.0D) < 1.0E-6D) {
            return lines;
        }
        List<MutableComponent> out = new ArrayList<>(lines.size());
        for (MutableComponent line : lines) {
            out.add(scaleLine(line, multiplier));
        }
        return out;
    }

    private static MutableComponent scaleLine(MutableComponent line, double multiplier) {
        if (!(line.getContents() instanceof TranslatableContents contents) || !isDamageKey(contents.getKey())) {
            return line;
        }
        Object[] args = contents.getArgs();
        Object[] scaled = new Object[args.length];
        boolean changed = false;
        for (int i = 0; i < args.length; i++) {
            Object value = scaleArg(args[i], multiplier);
            changed |= value != args[i];
            scaled[i] = value;
        }
        if (!changed) {
            return line;
        }
        MutableComponent copy = Component.translatableWithFallback(contents.getKey(), contents.getFallback(), scaled)
                .setStyle(line.getStyle());
        line.getSiblings().forEach(copy::append);
        return copy;
    }

    // 伤害倍率、减伤、抗性这类键里也带 damage，但数值是百分比，不能跟着乘
    private static final String[] NOT_AMOUNT = {"multiplier", "percent", "reduc", "resist", "chance"};

    private static boolean isDamageKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (!lower.contains("damage")) {
            return false;
        }
        for (String word : NOT_AMOUNT) {
            if (lower.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static Object scaleArg(Object arg, double multiplier) {
        if (arg instanceof Number number) {
            return format(number.doubleValue() * multiplier);
        }
        if (arg instanceof String text) {
            try {
                return format(Double.parseDouble(text.trim()) * multiplier);
            } catch (NumberFormatException ignored) {
                return arg;
            }
        }
        return arg;
    }

    // 和铁魔法提示框一样最多留两位小数
    private static String format(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
