package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.client.weapon.WeaponEntry;
import com.flameforge.albionmastery.client.weapon.WeaponSource;
import com.flameforge.albionmastery.client.weapon.WeaponTypeGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 专精节点的布局算法。
 *
 * <p>四层放射状树，<b>全部展开</b>，不做折叠：
 * <pre>
 *   总览
 *     └── 来源模组（TACZ 枪械 / 近战附属 / 拔刀剑）—— 同时就是该来源的总体专精
 *           └── 武器类型（步枪 / 狙击 / 霰弹 …）
 *                 └── 具体武器 —— 全部排在同一圈上
 * </pre>
 *
 * <p>节点半径由内向外递减，层次一眼可分。
 *
 * <p><b>同一层的所有节点共用一个圈。</b>
 * 早先每片扇形按自己的武器数量单独算外圈半径，结果武器最多的来源（比如刀包很全的拔刀剑）
 * 半径被顶到几千，武器少的来源被下限卡在 {@value #WEAPON_RING_MIN}，
 * 同一层的节点落在深浅不一的好几个圈上，看着就是一支特别往外戳、其它支缩在里面。
 * 现在外圈半径、类型圈半径、武器节点大小都全图统一，来源之间<b>只分角度、不分半径</b>。
 *
 * <p><b>每个来源只占一片扇形，扇形有角度上限。</b>
 * 按武器数量正比分整圈时，武器多的来源能拿到两百多度，它的分支会绕过大半圈、
 * 从别的来源头顶横穿过去。所以每片最多 {@value #MAX_SOURCE_WEDGE_DEG} 度，片间留空隙。
 *
 * <p><b>排完外圈还空着一大段时放开上限，盘面铺满整圈。</b>
 * 125 度的上限是给武器多的图定的。武器少的时候（只装一个武器模组，加上原版近战、弓弩这两个总在的来源），
 * 最大那片被卡在 125 度，武器圈被这个上限顶得很远，另外几片又小，排完正下方空出一大段，
 * 看全貌时武器节点只剩一两个像素。所以不看来源有几个，先照上面的规则排一遍，再量外圈：
 * <ul>
 *     <li>外圈上连续空着的角度不超过 {@value #WIDE_TRIGGER_EMPTY_DEG} 度（老规则自己允许的最宽片间空隙），
 *         或者扇形加 {@value #WIDE_SOURCE_GAP_DEG} 度的空隙已经占满一圈、没有余地，就用这一版；</li>
 *     <li>否则重排（见 {@link #buildWide}）：单片上限放到 {@value #WIDE_SOURCE_WEDGE_DEG} 度，武器圈收到刚好铺满一圈，
 *         各片按同一个角度单位等比拉开，片间空隙一样宽、至少 {@value #WIDE_SOURCE_GAP_DEG} 度；
 *         武器圈被最小半径兜住时，角度单位最多再拉开到 {@value #WIDE_STRETCH_MAX} 倍。
 *         200 度以内来源到类型的连线最多偏九十来度，离中心还有来源圈半径的八成多，不会横穿别家。
 *         重排完最宽的空档没比第一版窄（每个来源只有一两件武器的极小图会这样），就还用第一版。</li>
 * </ul>
 * 只有一个来源时它的类型均匀铺满整圈（见 {@link #buildSingleSource}），来源节点在正上方，
 * 到各类型的连线由界面沿来源圈画成弧线再出去，不横穿中心。
 *
 * <p>三个圈的最小半径按武器总数收小，最多收到 {@value #RING_SCALE_FLOOR} 倍（见 {@link #ringScaleFor}），同样不看来源数。
 * 武器有 {@value #RING_SCALE_REF_WEAPONS} 件以上、老规则排完又不空的图，算出来的坐标和放开之前逐位一致；
 * 平分模式的分配规则不受影响，只跟着收小最小半径。
 *
 * <p>外圈的角度分配是<b>逐个累加</b>而不是各组按比例居中：每件武器占一个等宽的角度单位，
 * 类型与类型之间额外插入 {@value #GROUP_GAP_UNITS} 个单位的空隙。
 * 「每组在自己的子扇区里居中、占 85%」的做法在两个小组挨在一起时，
 * 边缘节点的间距会缩到不足一个单位，节点就粘在一起了。
 *
 * <p>由于半径统一，武器少的来源算出来的扇形会很窄（窄到源节点之间要打架），
 * 所以扇形有 {@value #MIN_SOURCE_WEDGE_DEG} 度的下限。被下限撑开的扇形<b>不会</b>
 * 把里面的武器也跟着摊开——武器始终按全图统一的角度单位排，整体在扇形里居中，多出来的角度留白。
 */
public final class NodeLayout {

    // 各层节点半径由内向外递减，相邻层之间保持 1.3～1.6 倍的差距，
    // 差距太小（比如 30 比 22）在实际画面里根本分不出层次。
    // 三个圈到中心的距离按 1 : 2 : 3 排，图再大也是等距的三环

    /** 中心总览节点半径。整张图的视觉重心，比来源节点大出一圈才压得住场。 */
    private static final double INFO_RADIUS = 105.0D;

    /** 来源模组节点半径。 */
    private static final double SOURCE_RADIUS = 54.0D;

    /** 武器类型节点半径。 */
    private static final double TYPE_RADIUS = 36.0D;

    /** 武器节点的最大半径。 */
    private static final double WEAPON_RADIUS_MAX = 22.0D;

    /** 武器节点的最小半径，挤到这个程度就不再缩了。 */
    private static final double WEAPON_RADIUS_MIN = 9.0D;

    /** 相邻武器节点的间距系数：间距 = 节点直径 × 该系数。 */
    private static final double WEAPON_GAP_FACTOR = 1.35D;

    /**
     * 来源圈的最小半径。
     *
     * <p>拉得比早先远得多：中心节点放大之后，来源节点原来那个距离显得很挤，
     * 分支还没散开就已经贴上中心了。留出一段空白，放射状的分支才有「从中心长出去」的感觉。
     * 图大的时候来源圈会跟着武器圈一起往外走（见 {@link #sourceRingFor}），这里只是下限。
     * 武器少时这个下限（连同类型圈、武器圈的下限和类型圈间距）按武器总数收小，
     * 最多收到 {@value #RING_SCALE_FLOOR} 倍，见 {@link #ringScaleFor}。
     */
    private static final double SOURCE_RING_MIN = 430.0D;

    /**
     * 来源圈相对武器圈的比例。
     *
     * <p>三圈到中心的距离按 1 : 2 : 3 排：中心→来源、来源→类型、类型→武器三段一样长。
     * 早先来源圈写死 430，一千多件武器把武器圈撑到一万六，缩小看全貌时来源节点全糊在中心那个点上，
     * 连线像一把伞骨从中心直接扎到中圈，看不出层次。
     */
    private static final double SOURCE_RING_RATIO = 1.0D / 3.0D;

    /**
     * 类型圈在来源圈到武器圈这段距离上的位置，0.5 就是正中间。
     *
     * <p>早先是按外圈半径的 0.45 倍算：外圈一大，来源→类型只有 0.45R − 430，
     * 类型→武器却有 0.55R，武器越多两段差得越远，中圈看着像贴在内圈上。
     * 改成按两圈之间的距离取比例，两段永远一样长。
     */
    private static final double TYPE_RING_RATIO = 0.5D;

    /** 类型圈的最小半径。要明显大于来源圈，否则两层会挨在一起。 */
    private static final double TYPE_RING_MIN = 780.0D;

    /** 类型圈与武器圈之间至少留出的间距，避免两层贴在一起。 */
    private static final double TYPE_RING_MARGIN = 260.0D;

    /** 武器圈的最小半径。武器少时同样乘 {@link #ringScaleFor} 的系数。 */
    private static final double WEAPON_RING_MIN = 1240.0D;

    /**
     * 武器圈的最大半径，再往外就只能靠缩小节点消化。
     *
     * <p>放得比原来大得多：装了上千件武器时，半径顶到上限之后就只能压缩节点大小和间距，
     * 结果是放到最大也还是一堆挤在一起的小点。宁可让整张图更大——缩放范围是跟着图走的，
     * 图大只是全貌时更小，挤成一团是没法通过缩放救回来的。
     * 四万的半径下，一圈能按满尺寸放下约四千件武器；一千八百件是一万六到两万。
     */
    private static final double WEAPON_RING_MAX = 40000.0D;

    /**
     * 单个武器类型至少占据的角度单位数。
     *
     * <p>只有两三件武器的类型如果按实际数量分配角度，它的类型节点会和隔壁挤在一起——
     * 类型节点比武器节点大得多，几个单位的弧长根本放不下。给一个下限，
     * 内部的武器仍按正常间距居中排，多出来的角度留白。
     */
    private static final double MIN_TYPE_UNITS = 8.0D;

    /**
     * 只有一把或两把武器的小类型，额外多给一点角度单位。
     *
     * <p>像「发射器」这种只有一把武器的类型，如果只按 {@link #MIN_TYPE_UNITS} 兜底，
     * 类型节点自己是站住了，但和相邻类型的呼吸空间还是偏小，看起来会比别的中圈挤。
     * 多补一点单位，优先把这类特别小的类型拉开；武器多的类型维持原逻辑，不会整圈一起膨胀。
     */
    private static final double SINGLE_WEAPON_TYPE_EXTRA_UNITS = 2.8D;

    /** 只有两把武器的小类型补一点角度单位，力度比单把更轻。 */
    private static final double DOUBLE_WEAPON_TYPE_EXTRA_UNITS = 1.4D;

    /** 单个来源扇形的角度上限（度）。排完外圈还空着一大段时改用 {@link #WIDE_SOURCE_WEDGE_DEG}。 */
    private static final double MAX_SOURCE_WEDGE_DEG = 125.0D;

    /** 武器总数到这个数时，三圈最小半径回到原值；更少就按平方根收小。只看武器总数，不看来源有几个。 */
    private static final double RING_SCALE_REF_WEAPONS = 240.0D;

    /**
     * 三圈最小半径最多收到原值的几成。
     *
     * <p>再小来源圈就贴到中心节点上了（中心 105 + 来源 54，来源圈收到六成是 258，中间还剩一百来格），
     * 正是 {@link #SOURCE_RING_MIN} 注释里说的「分支还没散开就贴上中心」。
     */
    private static final double RING_SCALE_FLOOR = 0.6D;

    /**
     * 照 125 度上限排完之后，外圈上连续空着超过这个角度（度）就放开上限重排，见 {@link #buildWide}。
     *
     * <p>取的是 {@link #MAX_SOURCE_GAP_DEG}：老规则排满一圈时片间空隙最宽也就这么宽，
     * 外圈哪一段空得比它还宽，就是排剩下的——正下方收口那段，或者小来源被扇形下限撑开、两边留出的白。
     * 不超过它的图原样不动。
     *
     * <p>常量在 {@link #MAX_SOURCE_GAP_DEG} 前面声明，写全类名才能直接引用它。
     */
    private static final double WIDE_TRIGGER_EMPTY_DEG = NodeLayout.MAX_SOURCE_GAP_DEG;

    /** 重排时单片扇形的角度上限（度）。 */
    private static final double WIDE_SOURCE_WEDGE_DEG = 200.0D;

    /**
     * 重排时片间空隙的下限（度）。会走到重排的图来源都不多（武器少才空），
     * 空隙给宽一点各家的边界才分得清；同时也是重排的门槛：老规则的扇形加上这么宽的空隙已经占满一圈，就没有拉开的余地。
     */
    private static final double WIDE_SOURCE_GAP_DEG = 16.0D;

    /**
     * 重排时武器圈被最小半径兜住、照理想间距排不满一圈，统一角度单位最多拉开几倍去铺满。
     * 全图仍是同一个单位，各来源的疏密一致。
     */
    private static final double WIDE_STRETCH_MAX = 1.6D;

    /**
     * 只有一个来源时，单个类型底下的武器最多摊开多少度。
     *
     * <p>只装拔刀剑这种只有一个大类型的，铺满整圈就意味着类型节点到对面那几把武器的连线要穿过盘心
     * （偏 180 度必穿心，偏 135 度离中心只剩类型圈半径的一半不到）。超了就把角度单位压小，
     * 底下留一段空，由界面按内容取景兜底。
     */
    private static final double MAX_TYPE_SPAN_DEG = 200.0D;

    /** 单个来源扇形的角度下限（度）：保证相邻的来源节点不会在内圈上撞在一起。 */
    private static final double MIN_SOURCE_WEDGE_DEG = 26.0D;

    /** 相邻两片扇形之间的空隙上限（度）。 */
    private static final double MAX_SOURCE_GAP_DEG = 46.0D;

    /** 相邻两片扇形之间的空隙下限（度）：算总角度预算时按这个值预留。 */
    private static final double MIN_SOURCE_GAP_DEG = 10.0D;

    /** 平分模式下，来源之间的空隙占每个来源那一份角度的比例。 */
    private static final double EQUAL_GAP_SHARE = 0.18D;

    /** 平分模式下空隙的最小角度。 */
    private static final double EQUAL_GAP_MIN_DEG = 8.0D;

    /** 平分模式下空隙的最大角度。 */
    private static final double EQUAL_GAP_MAX_DEG = 30.0D;

    /** 类型与类型之间额外插入的空隙，单位是「一件武器的角度」。 */
    private static final double GROUP_GAP_UNITS = 1.4D;

    private static final float DEPTH_DELAY_MS = 70.0F;

    /** 单件武器之间错开出现的间隔上限。 */
    private static final float INDEX_DELAY_MS = 6.0F;

    /**
     * 整轮武器出现动画允许占用的总时长。
     *
     * <p>以前是每件武器固定错开 6 毫秒，装了大枪包之后有五六百件武器，
     * 最后一件要等三秒多才冒出来——打开界面想看的那把偏偏排在后面就很难受。
     * 现在按武器总数把间隔摊薄，武器再多整轮也在这个时间内跑完。
     */
    private static final float TOTAL_STAGGER_MS = 620.0F;

    private NodeLayout() {
    }

    /**
     * 算一个来源需要多少个「角度单位」。
     *
     * <p>每件武器一个单位，类型之间再插入空隙；武器很少的类型按下限补足，
     * 保证它的类型节点也有落脚的地方。
     *
     * <p>返回值是「第一个武器中心到最后一个武器中心」跨越的单位数，
     * 所以是数量减一再加上组间空隙，不是简单的数量求和。
     *
     * @param source 来源
     * @return 单位数
     */
    private static double unitsOf(WeaponSource source) {
        double total = 0.0D;
        int groups = 0;
        for (WeaponTypeGroup group : source.types()) {
            if (group.weapons().isEmpty()) {
                continue;
            }
            total += allocationUnits(group);
            groups++;
        }
        return Math.max(1.0D, total - 1.0D + GROUP_GAP_UNITS * Math.max(0, groups - 1));
    }

    /**
     * 单个类型实际占用的角度单位数。
     *
     * <p>不是简单地拿武器数量和 {@link #MIN_TYPE_UNITS} 二选一。
     * 极小类型（1~2 把）真正难看的地方不在外圈，而在中圈：类型节点本身比武器节点大得多，
     * 如果只给一个很勉强的兜底，它就会显得和隔壁贴得特别近。
     * 所以对 1 把 / 2 把的类型额外补一点角度，让中圈观感和其它枪械类型更一致。
     *
     * @param group 类型分组
     * @return 该类型应占的单位数
     */
    private static double allocationUnits(WeaponTypeGroup group) {
        int size = group.weapons().size();
        if (size <= 0) {
            return 0.0D;
        }
        double base = Math.max(size, MIN_TYPE_UNITS);
        if (size == 1) {
            return base + SINGLE_WEAPON_TYPE_EXTRA_UNITS;
        }
        if (size == 2) {
            return base + DOUBLE_WEAPON_TYPE_EXTRA_UNITS;
        }
        return base;
    }

    /**
     * 生成全部节点。
     *
     * @param sources 来源列表
     * @return 节点列表，第一个元素固定是中心总览节点
     */
    public static List<MasteryNode> build(List<WeaponSource> sources) {
        List<MasteryNode> nodes = new ArrayList<>();
        MasteryNode info = new MasteryNode(MasteryNode.Type.INFO, 0.0D, 0.0D, INFO_RADIUS,
                null, null, null, null, 0.0F);
        nodes.add(info);

        List<WeaponSource> active = new ArrayList<>();
        for (WeaponSource source : sources) {
            if (source.weaponCount() > 0) {
                active.add(source);
            }
        }
        if (active.isEmpty()) {
            return nodes;
        }

        int count = active.size();
        double[] units = new double[count];
        double totalUnits = 0.0D;
        double maxUnits = 1.0D;
        for (int i = 0; i < count; i++) {
            units[i] = Math.max(1.0D, unitsOf(active.get(i)));
            totalUnits += units[i];
            maxUnits = Math.max(maxUnits, units[i]);
        }

        // 武器越多，单件之间错开得越少，保证整轮动画时长恒定
        int totalWeapons = 0;
        for (WeaponSource source : active) {
            totalWeapons += source.weaponCount();
        }
        float indexDelay = Math.min(INDEX_DELAY_MS, TOTAL_STAGGER_MS / Math.max(1, totalWeapons));

        // 武器少时三圈的最小半径按武器总数收小；武器够多时恒为 1，下面的算式和收小之前逐位一致
        double scale = ringScaleFor(totalWeapons);
        // 理想间距：相邻两个武器节点的圆心距离
        double desiredSpacing = WEAPON_RADIUS_MAX * 2.0D * WEAPON_GAP_FACTOR;

        if (count == 1) {
            return buildSingleSource(nodes, info, active.get(0), units[0], desiredSpacing, scale, indexDelay);
        }

        double maxWedge = Math.toRadians(MAX_SOURCE_WEDGE_DEG);
        double minWedge = Math.toRadians(MIN_SOURCE_WEDGE_DEG);
        double minGap = Math.toRadians(MIN_SOURCE_GAP_DEG);
        // 一圈有 count 个空隙（最后一片和第一片之间也有一个）
        int gapCount = count;

        // 一整圈刨掉片间最小空隙之后，能分给所有扇形的总角度
        double arcBudget = Math.max(Math.toRadians(30.0D), Math.PI * 2.0D - minGap * gapCount);

        // ------------------------------------------------------------------
        // 统一外圈半径
        //
        // 半径同时要满足两件事，取两者里更大的那个：
        //   1) 最大的一片按理想间距排开之后，角度不超过单片上限；
        //   2) 所有片加起来按理想间距排开之后，总角度塞得进一圈（已扣掉最小空隙）。
        // 半径越大，同样的弧长占的角度越小，所以这两条都是「半径的下限」。
        // ------------------------------------------------------------------
        double ring = Math.max(WEAPON_RING_MIN * scale,
                Math.max(maxUnits * desiredSpacing / maxWedge,
                        totalUnits * desiredSpacing / arcBudget));
        ring = Math.min(WEAPON_RING_MAX, ring);

        // 半径撞到上限还是塞不下（武器实在太多），就把间距连同节点一起等比缩小
        double spacing = Math.min(desiredSpacing,
                Math.min(maxWedge * ring / maxUnits, arcBudget * ring / totalUnits));
        double weaponRadius = Math.max(WEAPON_RADIUS_MIN,
                Math.min(WEAPON_RADIUS_MAX, spacing / (2.0D * WEAPON_GAP_FACTOR)));
        // 类型圈放在来源圈和武器圈的正中间，同样全图统一
        double typeRing = typeRingFor(ring, scale);
        // 一件武器占的角度，全图统一：来源大小不再影响节点疏密
        double unitAngle = spacing / ring;

        double[] wedges = new double[count];
        double usedArc = 0.0D;
        if (MasteryConfig.CLIENT.equalSourceWedges.get()) {
            // ------------------------------------------------------------------
            // 平分模式：每个来源占的角度一样大，来源之间的空隙一样宽。
            // 剑这种把全部模组的剑都收进来的来源武器特别多，按数量分会把整圈的大半吃掉，
            // 挤得别的来源贴在一起；平分之后各家边界清楚，武器多的自己在片里排密一点。
            // 半径按最大的那片重新算：让它在等分的角度里尽量还能按理想间距排开。
            // ------------------------------------------------------------------
            double share = Math.PI * 2.0D / count;
            double equalGap = Math.max(Math.toRadians(EQUAL_GAP_MIN_DEG),
                    Math.min(Math.toRadians(EQUAL_GAP_MAX_DEG), share * EQUAL_GAP_SHARE));
            double equalWedge = share - equalGap;
            ring = Math.min(WEAPON_RING_MAX,
                    Math.max(WEAPON_RING_MIN * scale, maxUnits * desiredSpacing / equalWedge));
            spacing = Math.min(desiredSpacing, equalWedge * ring / maxUnits);
            weaponRadius = Math.max(WEAPON_RADIUS_MIN,
                    Math.min(WEAPON_RADIUS_MAX, spacing / (2.0D * WEAPON_GAP_FACTOR)));
            typeRing = typeRingFor(ring, scale);
            unitAngle = spacing / ring;
            for (int i = 0; i < count; i++) {
                wedges[i] = equalWedge;
            }
            usedArc = equalWedge * count + equalGap * (count - 1);
            return placeSources(nodes, active, info, wedges, equalGap, usedArc, units, unitAngle,
                    ring, weaponRadius, typeRing, sourceRingFor(ring, scale), indexDelay);
        }

        // 每片扇形的角度：按各自的单位数换算，太窄的按下限撑开
        for (int i = 0; i < count; i++) {
            wedges[i] = Math.max(minWedge, Math.min(maxWedge, units[i] * unitAngle));
            usedArc += wedges[i];
        }

        // 下限撑开之后总角度有可能超过一圈（来源特别多、又都很小），等比压回去。
        // 片内的武器仍按 unitAngle 排，实在排不下时才跟着这片一起缩（见 layoutTypes）。
        //
        // 空隙的个数是 count 而不是 count - 1：这是一圈不是一条线，最后一片和第一片之间
        // 也隔着一个空隙。之前按 count - 1 算，一圈被扇形塞满时，最后一片（剑）就直接
        // 贴到第一片（枪械）上，别的片之间却都还有空隙。
        double arcLimit = Math.PI * 2.0D - minGap * count;
        if (usedArc > arcLimit) {
            double shrink = arcLimit / usedArc;
            for (int i = 0; i < count; i++) {
                wedges[i] *= shrink;
            }
            usedArc = arcLimit;
        }

        double gap = Math.min(Math.toRadians(MAX_SOURCE_GAP_DEG),
                Math.max(minGap, (Math.PI * 2.0D - usedArc) / count));
        // 摆放时只用到 count - 1 个空隙，第 count 个就是最后一片到第一片之间
        // 自然剩下的那段；空隙被上限压住时剩下的会更宽，落在正下方，不会更窄
        double wedgeSum = usedArc;
        usedArc += gap * (count - 1);

        placeSources(nodes, active, info, wedges, gap, usedArc, units, unitAngle,
                ring, weaponRadius, typeRing, sourceRingFor(ring, scale), indexDelay);

        // 排完再看空不空：外圈没有空得比片间空隙更宽的段，或者扇形加宽空隙已经占满一圈、没有拉开的余地，就用这一版
        double packedWidest = widestWeaponGap(nodes);
        if (wedgeSum + Math.toRadians(WIDE_SOURCE_GAP_DEG) * count >= Math.PI * 2.0D
                || packedWidest <= Math.toRadians(WIDE_TRIGGER_EMPTY_DEG)) {
            return nodes;
        }
        List<MasteryNode> packed = new ArrayList<>(nodes);
        nodes.subList(1, nodes.size()).clear();
        buildWide(nodes, info, active, units, maxUnits, unitAngle, desiredSpacing, scale, indexDelay);
        // 每个来源只有一两件武器的极小图，最宽的空档是类型自己的留白，单位拉大它跟着变宽，重排反而更空，这时用回老规则那版
        return widestWeaponGap(nodes) < packedWidest ? nodes : packed;
    }

    /**
     * 照 125 度上限排完外圈还空着一大段时的重排：放开上限，武器圈收到刚好铺满一圈。
     *
     * <ol>
     *     <li>先算「铺满一圈」要的角度单位：各片按这个单位换算（太窄的照样按 {@value #MIN_SOURCE_WEDGE_DEG} 度撑开），
     *         加上 {@value #WIDE_SOURCE_GAP_DEG} 度的片间空隙正好一整圈（见 {@link #fillUnitAngle}）；</li>
     *     <li>武器圈取三者里最大的：最小半径、最大那片按理想间距排开不超过 {@value #WIDE_SOURCE_WEDGE_DEG} 度、
     *         按理想间距正好铺满一圈。半径只会比老规则小，同样的武器占的角度更大，各片一起等比拉开；</li>
     *     <li>武器圈被最小半径兜住时，按理想间距还是排不满，角度单位再拉开，最多到 {@value #WIDE_STRETCH_MAX} 倍，
     *         也不让最大那片超过上限。全图始终是同一个单位；</li>
     *     <li>片间空隙均分剩下的角度，超过 {@value #MAX_SOURCE_GAP_DEG} 度的部分照老规则落在正下方。</li>
     * </ol>
     *
     * @param nodes          节点列表（只含中心节点）
     * @param info           中心节点
     * @param active         有武器的来源，至少两个
     * @param units          每个来源的单位数
     * @param maxUnits       最大的单位数
     * @param packedUnit     老规则算出的角度单位，铺满一圈的单位从它往上找
     * @param desiredSpacing 相邻武器节点的理想圆心距
     * @param scale          三圈最小半径的缩放系数
     * @param indexDelay     出场动画的逐件延迟
     * @return 节点列表
     */
    private static List<MasteryNode> buildWide(List<MasteryNode> nodes, MasteryNode info, List<WeaponSource> active,
                                               double[] units, double maxUnits, double packedUnit,
                                               double desiredSpacing, double scale, float indexDelay) {
        int count = active.size();
        double maxWedge = Math.toRadians(WIDE_SOURCE_WEDGE_DEG);
        double minWedge = Math.toRadians(MIN_SOURCE_WEDGE_DEG);
        double minGap = Math.toRadians(WIDE_SOURCE_GAP_DEG);
        double fillUnit = fillUnitAngle(units, packedUnit, minWedge, Math.PI * 2.0D - minGap * count);

        double ring = Math.max(WEAPON_RING_MIN * scale,
                Math.max(maxUnits * desiredSpacing / maxWedge, desiredSpacing / fillUnit));
        ring = Math.min(WEAPON_RING_MAX, ring);
        // 半径撞到上限时（武器实在太多）间距跟着压小，和老规则一样
        double spacing = Math.min(desiredSpacing, Math.min(maxWedge * ring / maxUnits, fillUnit * ring));
        double weaponRadius = Math.max(WEAPON_RADIUS_MIN,
                Math.min(WEAPON_RADIUS_MAX, spacing / (2.0D * WEAPON_GAP_FACTOR)));
        // 上面的半径已经保证 spacing / ring 不超过后两项，这里只会往大拉
        double unitAngle = Math.min(spacing / ring * WIDE_STRETCH_MAX, Math.min(maxWedge / maxUnits, fillUnit));

        double[] wedges = new double[count];
        double usedArc = 0.0D;
        for (int i = 0; i < count; i++) {
            wedges[i] = Math.max(minWedge, units[i] * unitAngle);
            usedArc += wedges[i];
        }
        double gap = Math.min(Math.toRadians(MAX_SOURCE_GAP_DEG),
                Math.max(minGap, (Math.PI * 2.0D - usedArc) / count));
        usedArc += gap * (count - 1);

        return placeSources(nodes, active, info, wedges, gap, usedArc, units, unitAngle,
                ring, weaponRadius, typeRingFor(ring, scale), sourceRingFor(ring, scale), indexDelay);
    }

    /**
     * 铺满一圈的角度单位：各片按单位数换算、太窄的撑到 {@code minWedge}，加起来正好等于 {@code budget}。
     *
     * <p>撑开的那几片不随单位变，所以先假设都不撑，算出单位后把会被撑开的挑出来扣掉它们的定额，
     * 再算一遍，直到没有新的被撑开（最多来源数那么多轮）。调用前已确认 {@code packedUnit} 下还排得进去，
     * 结果不会比它小。
     *
     * @param units      每个来源的单位数
     * @param packedUnit 老规则的角度单位（下限）
     * @param minWedge   单片下限（弧度）
     * @param budget     扇形能用的总角度（弧度）
     * @return 角度单位
     */
    private static double fillUnitAngle(double[] units, double packedUnit, double minWedge, double budget) {
        boolean[] padded = new boolean[units.length];
        double unit = packedUnit;
        for (int round = 0; round <= units.length; round++) {
            double free = budget;
            double freeUnits = 0.0D;
            for (int i = 0; i < units.length; i++) {
                if (padded[i]) {
                    free -= minWedge;
                } else {
                    freeUnits += units[i];
                }
            }
            if (freeUnits <= 0.0D) {
                break;
            }
            unit = free / freeUnits;
            boolean changed = false;
            for (int i = 0; i < units.length; i++) {
                if (!padded[i] && units[i] * unit < minWedge) {
                    padded[i] = true;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return Math.max(packedUnit, unit);
    }

    /**
     * 外圈上相邻两件武器之间最大的角度空档（弧度），首尾相接算一圈。
     *
     * @param nodes 节点列表
     * @return 最大空档
     */
    private static double widestWeaponGap(List<MasteryNode> nodes) {
        int count = 0;
        for (MasteryNode node : nodes) {
            if (node.type == MasteryNode.Type.WEAPON) {
                count++;
            }
        }
        if (count == 0) {
            return Math.PI * 2.0D;
        }
        double[] angles = new double[count];
        int i = 0;
        for (MasteryNode node : nodes) {
            if (node.type == MasteryNode.Type.WEAPON) {
                angles[i++] = Math.atan2(node.graphY, node.graphX);
            }
        }
        Arrays.sort(angles);
        double widest = angles[0] + Math.PI * 2.0D - angles[count - 1];
        for (int k = 1; k < count; k++) {
            widest = Math.max(widest, angles[k] - angles[k - 1]);
        }
        return widest;
    }

    /**
     * 只有一个来源时的布局：它的类型均匀铺满整圈。
     *
     * <p>整圈按「单位数 + 1 + 类型间空隙」均分，首尾两个类型之间也留出一个类型间空隙，
     * 收口的那段空隙落在正下方；来源节点放在正上方。单个类型摊开超过 {@value #MAX_TYPE_SPAN_DEG} 度就压小角度单位，底下留空。
     *
     * <p>武器很少时外圈被最小半径兜住，角度单位又是按整圈均分的，同一类型相邻武器的间距会拉到理想值的好几倍：
     * 只有两三件到十来件武器时是四到八倍，一个类型三把武器就摊成七十多度的扇面；二十件左右是两倍多，六十件正好。
     * 这里没像 {@link #buildWide} 那样限制拉开的倍数，是有意的：单来源只在配置把原版剑斧弓弩全踢掉时才会出现，
     * 武器又少到这个地步的就更少，不值得为它另加规则，整圈铺满就行。
     *
     * <p>来源节点到对面类型的连线要是直着拉就会横穿中心，界面那边按
     * 「沿来源圈走到类型的方位、再径直出去」来画（{@code MasteryScreen.isArcLink}）。
     *
     * @param nodes          节点列表（已含中心节点）
     * @param info           中心节点
     * @param source         唯一的来源
     * @param units          这个来源的角度单位数
     * @param desiredSpacing 相邻武器节点的理想圆心距
     * @param scale          三圈最小半径的缩放系数
     * @param indexDelay     出场动画的逐件延迟
     * @return 节点列表
     */
    private static List<MasteryNode> buildSingleSource(List<MasteryNode> nodes, MasteryNode info,
                                                       WeaponSource source, double units,
                                                       double desiredSpacing, double scale,
                                                       float indexDelay) {
        double slots = units + 1.0D + GROUP_GAP_UNITS;
        double unitAngle = Math.PI * 2.0D / slots;
        int maxWeapons = 1;
        for (WeaponTypeGroup group : source.types()) {
            maxWeapons = Math.max(maxWeapons, group.weapons().size());
        }
        double maxTypeSpan = Math.toRadians(MAX_TYPE_SPAN_DEG);
        if (maxWeapons > 1 && unitAngle * (maxWeapons - 1) > maxTypeSpan) {
            unitAngle = maxTypeSpan / (maxWeapons - 1);
        }
        double ring = Math.min(WEAPON_RING_MAX, Math.max(WEAPON_RING_MIN * scale, desiredSpacing / unitAngle));
        double weaponRadius = Math.max(WEAPON_RADIUS_MIN,
                Math.min(WEAPON_RADIUS_MAX, unitAngle * ring / (2.0D * WEAPON_GAP_FACTOR)));
        double sourceRing = sourceRingFor(ring, scale);
        double top = -Math.PI / 2.0D;

        MasteryNode sourceNode = new MasteryNode(
                MasteryNode.Type.SOURCE, 0.0D, -sourceRing,
                SOURCE_RADIUS, source, null, null, info, DEPTH_DELAY_MS);
        nodes.add(sourceNode);
        // 扇形从正下方起、转满一圈：内容在整圈里居中，正好以正上方为中心
        layoutTypes(nodes, source, sourceNode, top - Math.PI, Math.PI * 2.0D, units, unitAngle,
                ring, weaponRadius, typeRingFor(ring, scale), 0, indexDelay);
        return nodes;
    }

    /**
     * 三圈最小半径的缩放系数：按武器总数的平方根收小（不看来源有几个），
     * 到 {@value #RING_SCALE_REF_WEAPONS} 件回到 1，最低 {@value #RING_SCALE_FLOOR}。
     *
     * <p>界面算打开镜头时也用它分辨「三圈收小了的图」，见 {@code MasteryScreen.fitZoom}。
     *
     * @param totalWeapons 武器总数
     * @return 系数
     */
    static double ringScaleFor(int totalWeapons) {
        double scale = Math.sqrt(totalWeapons / RING_SCALE_REF_WEAPONS);
        return Math.max(RING_SCALE_FLOOR, Math.min(1.0D, scale));
    }

    /**
     * 来源圈的半径：武器圈的三分之一，但不小于 {@value #SOURCE_RING_MIN} × 缩放系数。
     *
     * @param ring  武器圈半径
     * @param scale 最小半径的缩放系数（武器够多时为 1）
     * @return 来源圈半径
     */
    private static double sourceRingFor(double ring, double scale) {
        return Math.max(SOURCE_RING_MIN * scale, ring * SOURCE_RING_RATIO);
    }

    /**
     * 类型圈的半径：来源圈到武器圈之间按 {@value #TYPE_RING_RATIO} 取点，再夹进合理范围。
     *
     * @param ring  武器圈半径
     * @param scale 最小半径的缩放系数（武器够多时为 1）
     * @return 类型圈半径
     */
    private static double typeRingFor(double ring, double scale) {
        double sourceRing = sourceRingFor(ring, scale);
        double middle = sourceRing + (ring - sourceRing) * TYPE_RING_RATIO;
        return Math.max(TYPE_RING_MIN * scale, Math.min(ring - TYPE_RING_MARGIN * scale, middle));
    }

    /**
     * 按算好的扇形角度把各来源摆到圈上。整组扇形以正上方为中心摆开。
     *
     * @param nodes        节点列表（追加进去）
     * @param active       有武器的来源
     * @param info         中心说明节点
     * @param wedges       每个来源的扇形角度
     * @param gap          来源之间的空隙角度
     * @param usedArc      扇形加空隙的总角度
     * @param units        每个来源的单位数
     * @param unitAngle    一件武器占的角度
     * @param ring         外圈半径
     * @param weaponRadius 武器节点半径
     * @param typeRing     类型圈半径
     * @param sourceRing   来源圈半径
     * @param indexDelay   出场动画的逐件延迟
     * @return 节点列表
     */
    private static List<MasteryNode> placeSources(List<MasteryNode> nodes, List<WeaponSource> active,
                                                  MasteryNode info, double[] wedges, double gap,
                                                  double usedArc, double[] units, double unitAngle,
                                                  double ring, double weaponRadius, double typeRing,
                                                  double sourceRing, float indexDelay) {
        double cursor = -Math.PI / 2.0D - usedArc / 2.0D;
        int weaponIndex = 0;

        for (int s = 0; s < active.size(); s++) {
            WeaponSource source = active.get(s);
            double wedge = wedges[s];
            double center = cursor + wedge / 2.0D;

            MasteryNode sourceNode = new MasteryNode(
                    MasteryNode.Type.SOURCE,
                    Math.cos(center) * sourceRing, Math.sin(center) * sourceRing,
                    SOURCE_RADIUS, source, null, null, info,
                    DEPTH_DELAY_MS + s * 24.0F);
            nodes.add(sourceNode);

            weaponIndex = layoutTypes(nodes, source, sourceNode, cursor, wedge, units[s], unitAngle,
                    ring, weaponRadius, typeRing, weaponIndex, indexDelay);
            cursor += wedge + gap;
        }
        return nodes;
    }

    /**
     * 在一片扇形里逐个累加地排布类型与武器。
     *
     * <p>内容不一定占满整片扇形：扇形被角度下限撑开时，武器仍按全图统一的角度单位排，
     * 整体在扇形里居中，两侧留白。这样小来源的节点疏密和大来源保持一致。
     *
     * @param out          输出列表
     * @param source       来源
     * @param sourceNode   来源节点，作为类型节点的父节点
     * @param wedgeStart   扇形起始角
     * @param wedge        扇形跨度
     * @param units        该片需要的角度单位数
     * @param unitAngle    全图统一的「一件武器占的角度」
     * @param radius       外圈半径（全图统一）
     * @param weaponRadius 武器节点半径（全图统一）
     * @param typeRing     类型圈半径（全图统一）
     * @param weaponIndex  已放置的武器计数，用于错开出现动画
     * @param indexDelay   单件武器之间错开的毫秒数
     * @return 更新后的武器计数
     */
    private static int layoutTypes(List<MasteryNode> out, WeaponSource source, MasteryNode sourceNode,
                                   double wedgeStart, double wedge, double units, double unitAngle,
                                   double radius, double weaponRadius, double typeRing,
                                   int weaponIndex, float indexDelay) {
        // 一件武器占的角度：正常用全图统一值；万一这一片被压窄了塞不下，退回按扇形均分
        double unit = Math.min(unitAngle, wedge / Math.max(1.0D, units));
        // 内容整体居中：units * unit 是首尾两个武器中心之间的跨度
        double cursor = wedgeStart + (wedge - units * unit) / 2.0D;

        // 即使一个来源底下只有一个类型（比如只装了 lrtactical 本体、没有别的刀包），
        // 也照样画类型节点。少画一层确实省了一圈，但那一支的深度就和别的来源不一样，
        // 摆在一张图上看着很别扭——整齐比省地方重要。
        for (int t = 0; t < source.types().size(); t++) {
            WeaponTypeGroup group = source.types().get(t);
            List<WeaponEntry> weapons = group.weapons();
            if (weapons.isEmpty()) {
                continue;
            }
            // 这一类分到的角度：不足下限的按下限给，武器在里面居中排，多出来的留白
            double allocation = allocationUnits(group);
            double spanStart = cursor;
            double spanEnd = cursor + (allocation - 1) * unit;
            double typeCenter = (spanStart + spanEnd) / 2.0D;
            cursor = spanEnd + (GROUP_GAP_UNITS + 1.0D) * unit;

            MasteryNode parent = new MasteryNode(
                    MasteryNode.Type.TYPE,
                    Math.cos(typeCenter) * typeRing, Math.sin(typeCenter) * typeRing,
                    TYPE_RADIUS, source, group, null, sourceNode,
                    DEPTH_DELAY_MS * 2.0F + t * 18.0F);
            out.add(parent);

            double used = (weapons.size() - 1) * unit;
            double first = typeCenter - used / 2.0D;
            for (int i = 0; i < weapons.size(); i++) {
                double angle = first + unit * i;
                out.add(new MasteryNode(
                        MasteryNode.Type.WEAPON,
                        Math.cos(angle) * radius, Math.sin(angle) * radius,
                        weaponRadius, null, group, weapons.get(i), parent,
                        DEPTH_DELAY_MS * 3.0F + weaponIndex * indexDelay));
                weaponIndex++;
            }
        }
        return weaponIndex;
    }
}


