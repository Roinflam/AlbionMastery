package com.flameforge.albionmastery.client.screen;

import com.flameforge.albionmastery.client.weapon.WeaponEntry;
import com.flameforge.albionmastery.client.weapon.WeaponSource;
import com.flameforge.albionmastery.client.weapon.WeaponTypeGroup;

import java.util.ArrayList;
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
 * 从别的来源头顶横穿过去。现在每片最多 {@value #MAX_SOURCE_WEDGE_DEG} 度，片间留空隙。
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

    // 各层半径由内向外递减，相邻层之间保持 1.3～1.6 倍的差距，
    // 差距太小（比如 30 比 22）在实际画面里根本分不出层次

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
     * 来源节点所在的圈。
     *
     * <p>拉得比早先远得多：中心节点放大之后，来源节点原来那个距离显得很挤，
     * 分支还没散开就已经贴上中心了。留出一段空白，放射状的分支才有「从中心长出去」的感觉。
     */
    private static final double SOURCE_RING = 430.0D;

    /** 类型圈相对外圈半径的比例。 */
    private static final double TYPE_RING_RATIO = 0.45D;

    /** 类型圈的最小半径。要明显大于来源圈，否则两层会挨在一起。 */
    private static final double TYPE_RING_MIN = 780.0D;

    /** 类型圈与武器圈之间至少留出的间距，避免两层贴在一起。 */
    private static final double TYPE_RING_MARGIN = 260.0D;

    /** 武器圈的最小半径。 */
    private static final double WEAPON_RING_MIN = 1240.0D;

    /**
     * 武器圈的最大半径，再往外就只能靠缩小节点消化。
     *
     * <p>放得比原来大得多：装了上千件武器时，半径顶到上限之后就只能压缩节点大小和间距，
     * 结果是放到最大也还是一堆挤在一起的小点。宁可让整张图更大——反正可以缩放，
     * 挤成一团是没法通过缩放救回来的。
     */
    private static final double WEAPON_RING_MAX = 16000.0D;

    /**
     * 单个武器类型至少占据的角度单位数。
     *
     * <p>只有两三件武器的类型如果按实际数量分配角度，它的类型节点会和隔壁挤在一起——
     * 类型节点比武器节点大得多，几个单位的弧长根本放不下。给一个下限，
     * 内部的武器仍按正常间距居中排，多出来的角度留白。
     */
    private static final double MIN_TYPE_UNITS = 8.0D;

    /** 单个来源扇形的角度上限（度）。 */
    private static final double MAX_SOURCE_WEDGE_DEG = 125.0D;

    /** 单个来源扇形的角度下限（度）：保证相邻的来源节点不会在内圈上撞在一起。 */
    private static final double MIN_SOURCE_WEDGE_DEG = 14.0D;

    /** 相邻两片扇形之间的空隙上限（度）。 */
    private static final double MAX_SOURCE_GAP_DEG = 46.0D;

    /** 相邻两片扇形之间的空隙下限（度）：算总角度预算时按这个值预留。 */
    private static final double MIN_SOURCE_GAP_DEG = 10.0D;

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
            total += Math.max(group.weapons().size(), MIN_TYPE_UNITS);
            groups++;
        }
        return Math.max(1.0D, total - 1.0D + GROUP_GAP_UNITS * Math.max(0, groups - 1));
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

        double maxWedge = Math.toRadians(MAX_SOURCE_WEDGE_DEG);
        double minWedge = Math.toRadians(MIN_SOURCE_WEDGE_DEG);
        double minGap = Math.toRadians(MIN_SOURCE_GAP_DEG);
        int gapCount = Math.max(0, count - 1);

        // 一整圈刨掉片间最小空隙之后，能分给所有扇形的总角度
        double arcBudget = Math.max(Math.toRadians(30.0D), Math.PI * 2.0D - minGap * gapCount);
        // 理想间距：相邻两个武器节点的圆心距离
        double desiredSpacing = WEAPON_RADIUS_MAX * 2.0D * WEAPON_GAP_FACTOR;

        // ------------------------------------------------------------------
        // 统一外圈半径
        //
        // 半径同时要满足两件事，取两者里更大的那个：
        //   1) 最大的一片按理想间距排开之后，角度不超过 MAX_SOURCE_WEDGE_DEG；
        //   2) 所有片加起来按理想间距排开之后，总角度塞得进一圈（已扣掉最小空隙）。
        // 半径越大，同样的弧长占的角度越小，所以这两条都是「半径的下限」。
        // ------------------------------------------------------------------
        double ring = Math.max(WEAPON_RING_MIN,
                Math.max(maxUnits * desiredSpacing / maxWedge,
                        totalUnits * desiredSpacing / arcBudget));
        ring = Math.min(WEAPON_RING_MAX, ring);

        // 半径撞到上限还是塞不下（武器实在太多），就把间距连同节点一起等比缩小
        double spacing = Math.min(desiredSpacing,
                Math.min(maxWedge * ring / maxUnits, arcBudget * ring / totalUnits));
        double weaponRadius = Math.max(WEAPON_RADIUS_MIN,
                Math.min(WEAPON_RADIUS_MAX, spacing / (2.0D * WEAPON_GAP_FACTOR)));
        // 类型圈跟着外圈走，同样全图统一
        double typeRing = Math.max(TYPE_RING_MIN,
                Math.min(ring - TYPE_RING_MARGIN, ring * TYPE_RING_RATIO));
        // 一件武器占的角度，全图统一：来源大小不再影响节点疏密
        double unitAngle = spacing / ring;

        // 武器越多，单件之间错开得越少，保证整轮动画时长恒定
        int totalWeapons = 0;
        for (WeaponSource source : active) {
            totalWeapons += source.weaponCount();
        }
        float indexDelay = Math.min(INDEX_DELAY_MS, TOTAL_STAGGER_MS / Math.max(1, totalWeapons));

        // 每片扇形的角度：按各自的单位数换算，太窄的按下限撑开
        double[] wedges = new double[count];
        double usedArc = 0.0D;
        for (int i = 0; i < count; i++) {
            wedges[i] = Math.max(minWedge, Math.min(maxWedge, units[i] * unitAngle));
            usedArc += wedges[i];
        }

        // 下限撑开之后总角度有可能超过一圈（来源特别多、又都很小），等比压回去。
        // 片内的武器仍按 unitAngle 排，实在排不下时才跟着这片一起缩（见 layoutTypes）。
        double arcLimit = Math.PI * 2.0D - minGap * gapCount;
        if (usedArc > arcLimit) {
            double shrink = arcLimit / usedArc;
            for (int i = 0; i < count; i++) {
                wedges[i] *= shrink;
            }
            usedArc = arcLimit;
        }

        double gap = 0.0D;
        if (count > 1) {
            gap = Math.min(Math.toRadians(MAX_SOURCE_GAP_DEG),
                    Math.max(minGap, (Math.PI * 2.0D - usedArc) / count));
            usedArc += gap * (count - 1);
        }

        // 整组扇形以正上方为中心摆开
        double cursor = -Math.PI / 2.0D - usedArc / 2.0D;
        int weaponIndex = 0;

        for (int s = 0; s < count; s++) {
            WeaponSource source = active.get(s);
            double wedge = wedges[s];
            double center = cursor + wedge / 2.0D;

            MasteryNode sourceNode = new MasteryNode(
                    MasteryNode.Type.SOURCE,
                    Math.cos(center) * SOURCE_RING, Math.sin(center) * SOURCE_RING,
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
            double allocation = Math.max(weapons.size(), MIN_TYPE_UNITS);
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
