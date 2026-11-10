package com.flameforge.albionmastery.client.screen;

import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * 按宽度折行，中文不让标点掉到行首。
 *
 * <p>补的是什么：原版的折行只认空格和「放不下就从这里切」，对中文来说就是数着字数一刀切，
 * 逗号、句号、右括号经常被甩到下一行开头——「……大类<br>，外圈是」，一屏看下来全是这种断法，
 * 字没变多，但读起来非常累。这里按中文排版的老规矩处理：该跟在上一行的标点就不换到行首，
 * 该带着下文的左括号也不留在行尾。
 *
 * <p>英文照样按单词断，不会把一个词切成两半。
 *
 * <p>只处理纯文本：调用方本来就是「一段话 + 一个颜色」，颜色由调用方自己传。
 */
public final class TextWrap {

    /** 不能出现在行首的字符：跟在前文后面的标点。 */
    private static final String NO_LINE_START = "，。、；：？！）］｝〕〉》」』】·…—～％,.;:?!)]}%>’”";

    /** 不能出现在行尾的字符：要带着下文的开括号。 */
    private static final String NO_LINE_END = "（［｛〔〈《「『【([{<‘“";

    /** 为了避开标点最多往回退几个字，退太多会让一行短得难看。 */
    private static final int MAX_SHIFT = 4;

    private TextWrap() {
    }

    /**
     * 把一段话按宽度折成几行。
     *
     * @param font  字体
     * @param text  原文，可以带换行符
     * @param width 可用宽度（像素）
     * @return 折好的每一行；原文为空时返回一个空串，保证至少占一行
     */
    public static List<String> wrap(Font font, String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        if (width <= 0) {
            out.add(text);
            return out;
        }
        for (String paragraph : text.split("\n", -1)) {
            wrapParagraph(font, paragraph, width, out);
        }
        return out;
    }

    /**
     * 折一个自然段。
     *
     * @param font      字体
     * @param paragraph 段落
     * @param width     可用宽度
     * @param out       收集行的列表
     */
    private static void wrapParagraph(Font font, String paragraph, int width, List<String> out) {
        if (paragraph.isEmpty()) {
            out.add("");
            return;
        }
        int start = 0;
        while (start < paragraph.length()) {
            String rest = paragraph.substring(start);
            String fit = font.plainSubstrByWidth(rest, width);
            if (fit.length() >= rest.length()) {
                out.add(rest);
                return;
            }
            // 至少切一个字：宽度小到一个字都放不下时也要往前走，否则会死循环
            int end = start + Math.max(1, fit.length());
            end = avoidWordBreak(paragraph, start, end);
            int beforePunct = end;
            end = avoidBadBreak(paragraph, start, end);
            if (end != beforePunct && insideWord(paragraph, end)) {
                // 为了避开标点往回退，退进了一个英文词里（「free.」退成「fre / e.」）：整词带下去，
                // 这一行只剩半个词放不下时宁可让标点落在行首
                int word = wordStart(paragraph, start, end);
                end = word > start ? word : beforePunct;
            }
            end = avoidOrphan(paragraph, start, end);

            out.add(stripTrailingSpaces(paragraph.substring(start, end)));
            start = end;
            while (start < paragraph.length() && paragraph.charAt(start) == ' ') {
                start++;
            }
        }
    }

    /**
     * 英文别从单词中间断开：断点落在一个英文词（连同贴着它的标点、数字、符号，如「+5.」「1h.」「★2」）里面时，
     * 退回到这个词的开头。整行就是一个词、退了这一行就空了时才硬切。
     *
     * @param text  原文
     * @param start 本行起点
     * @param end   本行终点（不含）
     * @return 调整后的终点
     */
    private static int avoidWordBreak(String text, int start, int end) {
        if (!insideWord(text, end)) {
            return end;
        }
        int word = wordStart(text, start, end);
        return word > start ? word : end;
    }

    /**
     * 断点是不是落在一个英文词中间：两边都是非空格、非中日韩字符。中文字之间、中文和英文之间都能断。
     *
     * @param text 原文
     * @param end  断点
     * @return 在词中间返回 true
     */
    private static boolean insideWord(String text, int end) {
        return end > 0 && end < text.length() && isWordChar(text.charAt(end - 1)) && isWordChar(text.charAt(end));
    }

    /**
     * 断点所在英文词的开头（往回找到空格或中文字为止）。
     *
     * @param text  原文
     * @param start 本行起点，不会退到它前面
     * @param end   断点
     * @return 词的第一个字符的位置
     */
    private static int wordStart(String text, int start, int end) {
        int i = end;
        while (i > start && isWordChar(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /**
     * 标点避头尾：行首不留收尾标点，行尾不留开括号。
     *
     * <p>做法是把断点往回挪：本该落在下一行开头的那个逗号，连同它前面的一个字一起留到下一行，
     * 于是逗号就不在行首了。挪一两个字看不出来，挪多了这一行会明显变短，所以限次数。
     *
     * @param text  原文
     * @param start 本行起点
     * @param end   本行终点（不含）
     * @return 调整后的终点
     */
    private static int avoidBadBreak(String text, int start, int end) {
        int shift = 0;
        while (end > start + 1 && shift < MAX_SHIFT && end < text.length()
                && NO_LINE_START.indexOf(text.charAt(end)) >= 0) {
            end--;
            shift++;
        }
        shift = 0;
        while (end > start + 1 && shift < MAX_SHIFT
                && NO_LINE_END.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
            shift++;
        }
        return end;
    }

    /**
     * 中文段落的最后一行别只剩一两个字（「……单独配置这<br>件。」）：剩下的太短时从本行再匀一个字过去。
     * 只看中文断点，英文按单词断，本来就不会出现半个字的末行。
     *
     * @param text  原文
     * @param start 本行起点
     * @param end   本行终点（不含）
     * @return 调整后的终点
     */
    private static int avoidOrphan(String text, int start, int end) {
        int rest = text.length() - end;
        if (rest <= 0 || rest > 2 || end - start <= 4 || !isCjk(text.charAt(end - 1))) {
            return end;
        }
        int moved = end - 1;
        // 匀过去的字本身是收尾标点就再多带一个，免得标点落到行首
        while (moved > start + 1 && NO_LINE_START.indexOf(text.charAt(moved)) >= 0) {
            moved--;
        }
        return insideWord(text, moved) ? end : moved;
    }

    /**
     * 是不是英文词的一部分：除空格外的西文字符都算，字母数字和贴着它的标点、符号（+、%、★、括号）一起不拆开。
     *
     * @param c 字符
     * @return 是返回 true
     */
    private static boolean isWordChar(char c) {
        return c != ' ' && !isCjk(c);
    }

    /**
     * 中日韩文字和全角标点：它们之间每个字都能断。
     *
     * @param c 字符
     * @return 是返回 true
     */
    private static boolean isCjk(char c) {
        return c >= 0x2E80;
    }

    /**
     * 去掉行尾空格，免得右对齐或居中时看起来偏了。
     *
     * @param line 行
     * @return 去掉行尾空格的行
     */
    private static String stripTrailingSpaces(String line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            end--;
        }
        return line.substring(0, end);
    }
}
