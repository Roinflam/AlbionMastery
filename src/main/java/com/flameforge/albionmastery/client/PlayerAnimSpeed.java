package com.flameforge.albionmastery.client;

import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;

/**
 * 第三人称身体动作的播放倍速。
 *
 * <p>补的是什么：换弹、挥刀这些动作在别人眼里是另一套动画——第三人称的上半身关键帧动作，
 * 由 Player Animator 播。特化把换弹压到六成时间，别人看到的还是原速的装弹动作：
 * 他明明已经能开枪了，你看到的他还在插弹匣。自己切到第三人称看自己也一样。
 *
 * <p>Player Animator 自带一个 {@code SpeedModifier}，挂在动画层上就能整层变速。
 * 这里给每个玩家的每一层记一个修饰符：第一次挂上去，之后每次播新动作只改里面的倍速。
 * 不重复挂——修饰符是会累乘的，挂两个 1.25 就成了 1.56。
 *
 * <p>倍速从哪来：{@code SpecVisualAttributes} 那几个会同步的属性。客户端只有自己的专精数据，
 * 别人的倍率只能由服务端告诉我们，而属性系统本来就会把它广播给所有看得见这个玩家的客户端。
 */
public final class PlayerAnimSpeed {

    /** 倍速下限。 */
    private static final double MIN_SPEED = 0.25D;

    /** 倍速上限。 */
    private static final double MAX_SPEED = 4.0D;

    /**
     * 每个动画层挂的那一个倍速修饰符。层随玩家走，玩家没了就一起回收。
     *
     * <p>值要用弱引用装着：修饰符被挂上去之后自己也记着所属的层（{@code AbstractModifier.host}），
     * 直接存强引用就成了「值吊着键」，弱键表永远清不掉这一项，玩家进进出出就会一直攒。
     * 层自己的修饰符列表会把它牢牢拿住，所以弱引用不会被提前回收。
     */
    private static final Map<ModifierLayer<?>, WeakReference<SpeedModifier>> MODIFIERS = new WeakHashMap<>();

    private PlayerAnimSpeed() {
    }

    /**
     * 这一层现在没有动作在播吗。
     *
     * @param player  玩家
     * @param layerId 动画层的 ID
     * @return 空着返回 true
     */
    public static boolean isIdle(AbstractClientPlayer player, ResourceLocation layerId) {
        if (player == null || layerId == null) {
            return false;
        }
        IAnimation animation = PlayerAnimationAccess.getPlayerAssociatedData(player).get(layerId);
        if (!(animation instanceof ModifierLayer<?> layer)) {
            return false;
        }
        IAnimation playing = layer.getAnimation();
        return playing == null || !playing.isActive();
    }

    /**
     * 把某个玩家某一层的播放倍速设成给定值。
     *
     * @param player  玩家（可以是别人）
     * @param layerId 动画层的 ID
     * @param speed   倍速，1.0 表示原速
     */
    public static void apply(AbstractClientPlayer player, ResourceLocation layerId, double speed) {
        if (player == null || layerId == null) {
            return;
        }
        double wanted = Double.isFinite(speed) && speed > 0.0D ? Mth.clamp(speed, MIN_SPEED, MAX_SPEED) : 1.0D;
        IAnimation animation = PlayerAnimationAccess.getPlayerAssociatedData(player).get(layerId);
        if (!(animation instanceof ModifierLayer<?> layer)) {
            return;
        }
        WeakReference<SpeedModifier> known = MODIFIERS.get(layer);
        SpeedModifier modifier = known == null ? null : known.get();
        if (modifier == null) {
            // 原速就别往层上挂东西了：绝大多数玩家没有这类特化，白挂一个修饰符不划算
            if (wanted == 1.0D) {
                return;
            }
            modifier = new SpeedModifier((float) wanted);
            layer.addModifierLast(modifier);
            MODIFIERS.put(layer, new WeakReference<>(modifier));
            return;
        }
        modifier.speed = (float) wanted;
    }
}
