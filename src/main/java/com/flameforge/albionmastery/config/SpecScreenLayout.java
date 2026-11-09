package com.flameforge.albionmastery.config;

/**
 * 特化页的三种排布。三种用的是同一套数据、按钮和请求，只是摆法不同。
 */
public enum SpecScreenLayout {
    /** 左栏列出槽位，右边卡片两列。 */
    A,
    /** 槽位横排在最上面，左边窄栏放点数和合计效果，卡片三列（默认）。 */
    B,
    /** 左栏槽位，中栏只列名字和等级，右栏是选中那项的每级数值表。窗口太窄时按 B 显示。 */
    C;

    /**
     * @return 循环到下一种
     */
    public SpecScreenLayout next() {
        SpecScreenLayout[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
