package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.AlbionMastery;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

import net.minecraft.core.registries.Registries;
/**
 * 「这个玩家的动作该放多快」——做成几个会自动同步的玩家属性。
 *
 * <p>补的是什么：特化改的是时长（换弹耗时、拉弓速度、出手间隔），可这些时长对应的画面
 * 在别人的客户端上一概按原速播——客户端只有自己的专精数据，查不到别人的特化。
 * 原版的属性系统本来就会把「可同步」的属性广播给所有看得见这个玩家的客户端，
 * 借它的车比自己发包省事得多：服务端在 {@link SpecMoveSpeed#refresh} 里挂上修饰符，
 * 所有人的客户端立刻就知道该以几倍速播他的动作。
 *
 * <p>这几个属性存的都是<b>播放倍速</b>（1.25 = 快两成半），不是耗时倍率，
 * 用的时候直接乘进动画速度即可，不用再各自换算一次。
 *
 * <p>弓弩那条还有第二个用处：拉弦的推进本身就按这个属性走（见 {@code UseItemSpeedMixin}），
 * 两端读的是同一个数，所以画面和结算天然对齐，不会出现「画面拉满了其实没蓄满」。
 */
public final class SpecVisualAttributes {

    /** 属性注册表。 */
    private static final DeferredRegister<Attribute> REGISTRY =
            DeferredRegister.create(Registries.ATTRIBUTE, AlbionMastery.MOD_ID);

    /** 拉弓 / 上弦的倍速（弓弩）。 */
    public static final DeferredHolder<Attribute, Attribute> DRAW_SPEED =
            REGISTRY.register("visual_draw_speed", () -> speed("albionmastery.visual_draw_speed"));

    /** 近战出手的倍速（近战附属）。 */
    public static final DeferredHolder<Attribute, Attribute> MELEE_SPEED =
            REGISTRY.register("visual_melee_speed", () -> speed("albionmastery.visual_melee_speed"));

    /** 换弹的倍速（枪械）。 */
    public static final DeferredHolder<Attribute, Attribute> RELOAD_SPEED =
            REGISTRY.register("visual_reload_speed", () -> speed("albionmastery.visual_reload_speed"));

    /** 施法前摇的倍速（铁魔法）。这条是开始施法的那一刻按当前法术算的，不看主手。 */
    public static final DeferredHolder<Attribute, Attribute> CAST_SPEED =
            REGISTRY.register("visual_cast_speed", () -> speed("albionmastery.visual_cast_speed"));

    private SpecVisualAttributes() {
    }

    /**
     * 注册到 MOD 总线。
     *
     * @param modBus MOD 总线
     */
    public static void register(IEventBus modBus) {
        REGISTRY.register(modBus);
    }

    /**
     * 把这几个属性加到玩家身上。
     *
     * <p>不加到别的生物上：只有玩家会有特化。
     *
     * @param event 属性补充事件
     */
    public static void onAttributeModification(EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, DRAW_SPEED);
        event.add(EntityType.PLAYER, MELEE_SPEED);
        event.add(EntityType.PLAYER, RELOAD_SPEED);
        event.add(EntityType.PLAYER, CAST_SPEED);
    }

    /**
     * 读某个实体身上的倍速。
     *
     * @param entity    实体
     * @param attribute 属性
     * @return 倍速；没有这个属性、或者值不合理时返回 1.0
     */
    public static double of(LivingEntity entity, DeferredHolder<Attribute, Attribute> attribute) {
        if (!attribute.isBound()) {
            return 1.0D;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return 1.0D;
        }
        double value = instance.getValue();
        return Double.isFinite(value) && value > 0.0D ? value : 1.0D;
    }

    /**
     * 一个取值范围合理的倍速属性。
     *
     * <p>{@code setSyncable(true)} 是关键：只有可同步的属性才会随
     * {@code ClientboundUpdateAttributesPacket} 发给每个看得见这个玩家的客户端。
     *
     * @param key 翻译键
     * @return 属性
     */
    private static Attribute speed(String key) {
        return new RangedAttribute("attribute.name." + key, 1.0D, 0.05D, 20.0D).setSyncable(true);
    }
}
