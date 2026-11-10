package com.flameforge.albionmastery.client.screen;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 英文的单复数：数量是 1 时换用 {@code <键>.one}。
 *
 * <p>语言文件没有复数规则，「1 picks」「1 players ranked」只能靠多一个键。中文不分单复数，
 * 但 en_us 总是垫在当前语言底下，中文文件里不写 {@code .one} 就会在数量为 1 时露出英文，
 * 所以凡是加了 {@code .one} 的键，zh_cn 里也要有一份（和原键同文）。
 *
 * <p>只在客户端用：服务端发出去的提示要是依赖新键，旧版客户端会显示成键名，那边改成不带数量词的说法。
 */
public final class Plural {

    private Plural() {
    }

    /**
     * @param key 原键（复数说法）
     * @param n   决定单复数的那个数
     * @return n 为 1 时是 {@code key + ".one"}，否则原键
     */
    public static String key(String key, long n) {
        return n == 1 ? key + ".one" : key;
    }

    /**
     * @param key  原键
     * @param n    决定单复数的那个数
     * @param args 格式化参数（n 本身也要按语言文件里的位置传进来）
     * @return 翻译好的文字
     */
    public static String get(String key, long n, Object... args) {
        return I18n.get(key(key, n), args);
    }

    /**
     * @param key  原键
     * @param n    决定单复数的那个数
     * @param args 格式化参数
     * @return 可翻译文本
     */
    public static MutableComponent component(String key, long n, Object... args) {
        return Component.translatable(key(key, n), args);
    }
}
