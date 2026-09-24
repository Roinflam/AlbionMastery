package com.flameforge.albionmastery.mastery.spec;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import javax.annotation.Nullable;

/**
 * 特化在不同武器上的叫法。
 *
 * <p>「特化」「第一特化」「同组」这种统称放在枪上、刀上、法术书上都不对口：枪是改装、分部位；
 * 法术书是铭刻；拔刀剑是修习。界面和服务端提示里凡是要说到「这一项叫什么、两栏叫什么、按什么分组」，
 * 一律走这里按来源取词，语言键是 {@code gui.albionmastery.term.<来源>.<词>}：
 * <ul>
 *     <li>{@code page}：这一整套叫什么（枪械改装、法术铭刻……），当标题和按钮用；</li>
 *     <li>{@code spec}：单独一项叫什么（改装、铭刻……）；</li>
 *     <li>{@code slot1} / {@code slot2}：两栏叫什么（主改装 / 副改装……）；</li>
 *     <li>{@code group}：分组叫什么（部位、类别……），同一组里只能选一个。</li>
 * </ul>
 * 每个来源都在语言文件里写全了，不做回退，服务端发出去的提示也能直接用。来源不认识时用 {@code default} 那一套。
 */
public final class SpecTerms {

    private SpecTerms() {
    }

    /**
     * @param sourceId 来源 ID，可为 null
     * @param term     词：page / spec / slot1 / slot2 / group
     * @return 语言键
     */
    public static String key(@Nullable String sourceId, String term) {
        String source = sourceId == null || sourceId.isEmpty() ? "default" : sourceId;
        return "gui.albionmastery.term." + source + "." + term;
    }

    /**
     * @param sourceId 来源 ID，可为 null
     * @param term     词
     * @return 可翻译文本
     */
    public static MutableComponent of(@Nullable String sourceId, String term) {
        return Component.translatable(key(sourceId, term));
    }

    /**
     * @param sourceId 来源 ID，可为 null
     * @param slot     第几栏，1 或 2
     * @return 这一栏的叫法
     */
    public static MutableComponent slot(@Nullable String sourceId, int slot) {
        return of(sourceId, "slot" + slot);
    }
}
