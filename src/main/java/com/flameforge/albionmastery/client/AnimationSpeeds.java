package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * 每一段枪械 / 近战动画该以几倍速播放。
 *
 * <p>补的是什么：TACZ 的动画推进只看真实时间，没有任何速度系数——换弹特化让换弹只要六成时间，
 * 动画还是原来那么长，玩家看到的是「动画还在装弹夹，枪已经能开了」。近战、拉栓、掏枪、开镜、
 * 冲刺收枪、射击后坐同理。
 *
 * <p>怎么分的速：每段动画（一个 {@code ObjectAnimationRunner}）在<b>刚开始播的那一刻</b>定一次速，
 * 之后不再变。定速的依据有两层：
 * <ol>
 *     <li><b>动画名</b>：默认枪包里换弹叫 {@code reload_*}、拉栓叫 {@code bolt}、射击叫 {@code shoot}……
 *         认得出来的直接按名字归类，认不出来的交给第二层；</li>
 *     <li><b>触发输入</b>：状态机是被 {@code trigger("reload")} 这样的输入驱动的
 *         （见 {@code AnimationTriggerMixin}），第三方枪包把动画改了名，输入还是那几个，
 *         所以按输入归类对任何枪包都成立。</li>
 * </ol>
 * 两层都认不出来就是 1.0 倍速——宁可不改，也不要把待机、行走、跑步这些循环动画一起改快。
 *
 * <p>为什么要「每段各自定速」而不是「当前这一刻统一一个速度」：同一把枪身上同时有好几条动画轨道在跑，
 * 换弹的时候待机、行走、瞄准、枪跳都还在播，统一加速会把走路摆枪一起抖起来；
 * 而且原来那套是按服务端同步回来的状态判断的，动作刚开始的那 1 个 RTT 还没同步到，
 * 开头一小段是原速，结束时状态一清又立刻跳回原速，两头都不连贯。
 */
public final class AnimationSpeeds {

    /** 倍速的上下限：再快也要看得清，再慢也不要像卡住。 */
    private static final double MIN_SPEED = 0.25D;

    /** 倍速上限。 */
    private static final double MAX_SPEED = 4.0D;

    /**
     * 当前正在处理的状态机输入，按调用层次叠着放。
     *
     * <p>状态机的 lua 脚本里会在一个输入的处理过程中再 {@code trigger} 别的输入（比如瞄准时打断检视），
     * 所以要叠一摞而不是只记一个。放在 {@link ThreadLocal} 里是因为开火的定时器线程也会调到状态机。
     */
    private static final ThreadLocal<Deque<String>> INPUTS = ThreadLocal.withInitial(ArrayDeque::new);

    private AnimationSpeeds() {
    }

    /**
     * 动画的分类。
     */
    public enum Kind {
        /** 不动速。 */
        NONE,
        /** 换弹。 */
        RELOAD,
        /** 拉栓 / 泵动。 */
        BOLT,
        /** 掏枪。 */
        DRAW,
        /** 开火（枪跳、后坐）。 */
        SHOOT,
        /** 开镜 / 收镜。 */
        AIM,
        /** 战术冲刺的收枪、举枪。 */
        SPRINT,
        /** 近战附属的挥砍。 */
        MELEE
    }

    /**
     * 进入一次状态机输入。
     *
     * @param input 输入名
     */
    public static void pushInput(@Nullable String input) {
        INPUTS.get().push(input == null ? "" : input);
    }

    /**
     * 退出一次状态机输入。
     */
    public static void popInput() {
        Deque<String> stack = INPUTS.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    /**
     * 清空输入栈。
     *
     * <p>每帧推进状态机之前叫一次：那一刻一定不在任何输入的处理过程中，
     * 残留下来的只可能是上一次处理中途出错漏掉的，清掉才不会把后面的动画归错类。
     */
    public static void clearInputs() {
        INPUTS.get().clear();
    }

    /**
     * 一段动画刚开始播，它该是几倍速。
     *
     * @param clipName 动画名，可能为 null
     * @return 倍速；不该动速时是 1.0
     */
    public static double speedForNewClip(@Nullable String clipName) {
        Kind kind = classify(clipName);
        return kind == Kind.NONE ? 1.0D : speedOf(kind);
    }

    /**
     * 这段动画属于哪一类。
     *
     * @param clipName 动画名
     * @return 分类
     */
    private static Kind classify(@Nullable String clipName) {
        Kind byName = fromClipName(clipName == null ? "" : clipName.toLowerCase(Locale.ROOT));
        if (byName != null) {
            return byName;
        }
        Kind byInput = fromInput(INPUTS.get().peek());
        return byInput == null ? Kind.NONE : byInput;
    }

    /**
     * 按动画名归类。默认枪包和近战附属的命名都在这儿。
     *
     * @param clip 小写的动画名
     * @return 分类；名字不认识返回 null，交给触发输入判断
     */
    @Nullable
    private static Kind fromClipName(String clip) {
        if (clip.isEmpty()) {
            return null;
        }
        // 空仓挂机待机、检视、收枪、切火力模式、转管枪的旋转、散热：都是循环或者没有对应特化的动作
        if (clip.startsWith("idle") || clip.startsWith("static") || clip.startsWith("walk")
                || clip.startsWith("inspect") || clip.startsWith("put_away") || clip.startsWith("fire_select")
                || clip.startsWith("spin") || clip.contains("heat") || clip.startsWith("jump")
                // 下面这些是状态机在别的输入处理过程中顺手起的（战术滑铲、趴下、开关机瞄），
                // 名字认不出来就会去问「当时是哪个输入」，答案是错的——它们和那个输入没关系
                || clip.startsWith("slide") || clip.startsWith("crawl") || clip.startsWith("sight")
                || clip.startsWith("put_down")) {
            return Kind.NONE;
        }
        // 冲刺的起手和收手跟着「冲刺后多久能开火」走，中间那段循环不动
        if (clip.equals("run_start") || clip.equals("run_end")) {
            return Kind.SPRINT;
        }
        if (clip.startsWith("run")) {
            return Kind.NONE;
        }
        if (clip.startsWith("reload")) {
            return Kind.RELOAD;
        }
        // bolt_caught 是空仓挂机的待机姿势，不是拉栓动作
        if (clip.startsWith("bolt_caught")) {
            return Kind.NONE;
        }
        if (clip.startsWith("bolt")) {
            return Kind.BOLT;
        }
        if (clip.startsWith("draw")) {
            return Kind.DRAW;
        }
        if (clip.startsWith("shoot")) {
            return Kind.SHOOT;
        }
        if (clip.startsWith("aim")) {
            return Kind.AIM;
        }
        // 枪自带的刺刀 / 枪托近战没有对应特化，别跟着刀包的攻速一起变
        if (clip.startsWith("melee_bayonet") || clip.equals("melee_stock") || clip.equals("melee_push")) {
            return Kind.NONE;
        }
        if (clip.startsWith("melee")) {
            return Kind.MELEE;
        }
        return null;
    }

    /**
     * 按触发输入归类。第三方枪包改了动画名时靠这条兜底。
     *
     * @param input 输入名
     * @return 分类；这个输入和速度无关返回 null
     */
    @Nullable
    private static Kind fromInput(@Nullable String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        return switch (input) {
            case "reload" -> Kind.RELOAD;
            // "blot" 是 TACZ 自己拼错的拉栓输入，正式版本里就是这个词；"bolt" 一起认着，以防哪天改回来
            case "blot", "bolt", "bolt_normal" -> Kind.BOLT;
            case "draw" -> Kind.DRAW;
            case "shoot" -> Kind.SHOOT;
            case "aim", "aim_retreat" -> Kind.AIM;
            case "attack_left", "attack_right" -> Kind.MELEE;
            default -> null;
        };
    }

    /**
     * 这一类动作现在该是几倍速。
     *
     * <p>按本地玩家主手武器的特化算：第一人称动画本来就只为本地玩家自己的手持物品播。
     * 旁观别人时相机实体不是自己，这时一律 1.0——别拿自己的特化去改别人的枪。
     *
     * @param kind 分类
     * @return 倍速
     */
    private static double speedOf(Kind kind) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.getCameraEntity() != player) {
            return 1.0D;
        }
        SpecializationValues values = SpecializationEffects.forStack(player, player.getMainHandItem());
        double speed = switch (kind) {
            case RELOAD -> inverse(values.reloadTime());
            case BOLT -> inverse(values.boltTime());
            case DRAW -> inverse(values.drawTime());
            // 射速本来就是「倍速」的语义：1.3 倍射速，枪跳动画也放快到 1.3 倍
            case SHOOT -> positive(values.fireRate());
            case AIM -> inverse(values.adsTime());
            case SPRINT -> inverse(values.sprintTime());
            case MELEE -> inverse(values.attackInterval());
            default -> 1.0D;
        };
        return speed == 1.0D ? 1.0D : Mth.clamp(speed, MIN_SPEED, MAX_SPEED);
    }

    /**
     * 耗时倍率换成播放倍速：0.8 倍耗时 = 1.25 倍速。
     *
     * @param timeFactor 耗时倍率
     * @return 倍速
     */
    private static double inverse(double timeFactor) {
        return timeFactor > 0.0D && Double.isFinite(timeFactor) ? 1.0D / timeFactor : 1.0D;
    }

    /**
     * 挡掉不合理的倍速。
     *
     * @param value 倍速
     * @return 合理的倍速
     */
    private static double positive(double value) {
        return value > 0.0D && Double.isFinite(value) ? value : 1.0D;
    }
}
