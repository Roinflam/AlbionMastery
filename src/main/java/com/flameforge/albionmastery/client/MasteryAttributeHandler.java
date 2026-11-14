package com.flameforge.albionmastery.client;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.util.thread.EffectiveSide;

import java.util.ArrayList;
import java.util.List;

import net.neoforged.fml.common.EventBusSubscriber;
/**
 * 把专精加成写进物品的属性修饰符里，只改提示里的显示（仅客户端逻辑侧）。
 *
 * <p>近战武器（刀包）的伤害走的是原版 {@code generic.attack_damage} 属性：
 * {@code MeleeItem.attack()} 读玩家的攻击力属性值再乘以这一击的 factor。
 * 而物品提示里那行绿色的「+99 攻击伤害」，也正是从物品的属性修饰符渲染出来的。
 *
 * <p>所以只要在客户端改写这个修饰符，提示里显示的就直接是加成后的攻击力，
 * 不需要额外加一行「原值 -&gt; 加成后」，和枪械那边的处理思路一致。
 *
 * <p>为什么只在客户端做：{@code ItemAttributeModifierEvent} 只给物品堆和装备槽，
 * 拿不到持有者，服务端无法判断该用谁的专精数据。实际伤害由
 * {@code MeleeCombatHandler} 在服务端结算，两边用的是同一套加成公式。
 *
 * <p>只处理主手的 {@code ADD_VALUE} 修饰符：按百分比生效的修饰符（比如刀包里常见的
 * 移速加成）如果也乘上倍率，语义会完全错掉。
 *
 * <p>这个事件不分槽位，一次给出物品所有槽位组的修饰符，只改 {@link EquipmentSlotGroup#MAINHAND} 那一组。
 * 提示框每个槽位组都会重新取一次修饰符（一次构建触发十次），每次都是从默认修饰符重新算，不会越乘越大。
 */
@EventBusSubscriber(modid = AlbionMastery.MOD_ID, value = Dist.CLIENT)
public final class MasteryAttributeHandler {

    private MasteryAttributeHandler() {
    }

    /**
     * 取当前特化对这个来源的近身伤害倍率。
     *
     * <p>不同来源看的是不同字段：近战看通用伤害，拔刀剑的属性栏对应的是刀身直接攻击。
     * 枪械的攻击力属性是枪托砸人的伤害，和专精加成没关系，返回 1 不动它。
     *
     * @param weapon 已解析的武器
     * @return 倍率
     */
    private static double specDamageOf(ResolvedWeapon weapon) {
        SpecializationValues values = SpecializationEffects.forData(
                ClientMasteryCache.data(), weapon, ClientMasteryCache.snapshot());
        if (WeaponKeys.PROVIDER_SLASHBLADE.equals(weapon.providerId())) {
            // 刀身斩击实际吃的是「总伤害 × 刀身」两层，只乘刀身的话，把总伤害压低的特化显示得偏高
            return values.damage() * values.directDamage();
        }
        // 和服务端结算走同一张表（MeleeCombatHandler 也是查 isMeleeProvider）：
        // 两边用两套判断的话，新增近战来源时属性栏和实际伤害就对不上
        if (WeaponKeys.isMeleeProvider(weapon.providerId())) {
            return values.damage();
        }
        return 1.0D;
    }

    /**
     * 提示里「攻击距离」那一行用的修饰符 ID，只存在于客户端的提示里。
     *
     * <p>和原版的 {@code base_entity_reach} 不同，所以显示成「+15% 实体交互距离」那一类，不并进基础行。
     */
    private static final ResourceLocation REACH_DISPLAY_ID =
            ResourceLocation.fromNamespaceAndPath(AlbionMastery.MOD_ID, "spec_reach_display");

    /**
     * 改写属性栏：攻击力乘专精和特化倍率，吃原版属性的近战
     * （见 {@link com.flameforge.albionmastery.weapon.WeaponKeys#VANILLA_ATTRIBUTE_MELEE_PROVIDERS}）
     * 的攻速和攻击距离也按特化显示。
     *
     * <p><b>只在客户端逻辑侧做。</b>这个事件两边都会触发，单人世界和开局域网的房主那里，
     * 服务端线程也会走到这个处理器——那边算出来的修饰符是真的会挂到实体身上的，
     * 要是也改了，攻击力就会被放大两次（服务端命中时还会再乘一次专精倍率），
     * 连拿着剑的怪都会吃到玩家的加成。
     *
     * @param event 属性修饰符事件
     */
    @SubscribeEvent
    public static void onItemAttributeModifiers(ItemAttributeModifierEvent event) {
        if (EffectiveSide.get().isServer()) {
            return;
        }
        if (!MasteryConfig.CLIENT.overrideDamageDisplay.get()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        ItemStack stack = event.getItemStack();
        ResolvedWeapon weapon = MasteryClientBonus.resolve(stack);
        if (weapon == null) {
            return;
        }

        // 攻击力：特化也要算进来，疾风把伤害压到 65%、重压提到 160%，这些都是实打实生效的，
        // 属性栏却照着原始值显示，玩家会以为「选了没用」
        MasteryHelper.Bonus bonus = MasteryClientBonus.bonusFor(stack);
        if (bonus != null) {
            rescale(event, player, Attributes.ATTACK_DAMAGE, bonus.multiplier() * specDamageOf(weapon));
        }

        // 攻速和攻击距离：这类近战的特化是挂在玩家属性上的（见 SpecMoveSpeed），
        // 原版属性栏只算物品自己的修饰符，看不到这一截，这里补上。
        // 判据和 SpecMoveSpeed 是同一张表，免得显示和实际挂的不是一回事
        if (WeaponKeys.usesVanillaMeleeAttributes(weapon.providerId())) {
            SpecializationValues values = SpecializationEffects.forData(
                    ClientMasteryCache.data(), weapon, ClientMasteryCache.snapshot());
            if (values.attackInterval() > 0.0D) {
                rescale(event, player, Attributes.ATTACK_SPEED, 1.0D / values.attackInterval());
            }
            if (Math.abs(values.reach() - 1.0D) > 1.0E-6D) {
                // 和实际挂的修饰符一样是「乘总值」，属性栏会显示成「+15% 实体交互距离」
                event.addModifier(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(REACH_DISPLAY_ID,
                        values.reach() - 1.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
                        EquipmentSlotGroup.MAINHAND);
            }
        }
    }

    /**
     * 把某个属性上主手的「加法」修饰符改写成乘过倍率之后的样子。
     *
     * <p>提示框会把 ID 等于属性基础 ID（攻击力 {@code base_attack_damage}、攻速 {@code base_attack_speed}）
     * 的那一项当成基础行，加上玩家的基础值显示成「X 攻击伤害」，其余加法项显示成「+X」。
     * 改写时保留原修饰符 ID，基础行才还是基础行；换了 ID 就会变成「+X 攻击伤害」。
     *
     * <p>换算让提示里的总值等于实际生效的值：实际是「(基础 + 各加法项之和) * 倍率」，
     * 所以每一项都乘倍率，玩家基础值多出来的那一截「基础 * (倍率 - 1)」只记一次——
     * 记在基础行上，没有基础行就记在第一项上。只有一项时就是「(基础 + 修饰) * 倍率 - 基础」。
     * 打开 NeoForge 的合并提示时，基础行和其余项由 NeoForge 加总成一行，加出来同样是实际值。
     *
     * <p>按百分比生效的修饰符（比如刀包里常见的移速加成）不动，乘上倍率语义就全错了。
     */
    private static void rescale(ItemAttributeModifierEvent event, LocalPlayer player, Holder<Attribute> attribute,
                                double multiplier) {
        if (Math.abs(multiplier - 1.0D) <= 1.0E-6D) {
            return;
        }
        // 先拷一份再改：事件给的是视图，改写时底下的表会变
        List<ItemAttributeModifiers.Entry> additions = new ArrayList<>();
        ItemAttributeModifiers.Entry baseEntry = null;
        ResourceLocation baseId = attribute.value().getBaseId();
        for (ItemAttributeModifiers.Entry entry : event.getModifiers()) {
            if (entry.attribute().equals(attribute) && entry.slot() == EquipmentSlotGroup.MAINHAND
                    && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                additions.add(entry);
                if (baseEntry == null && entry.modifier().id().equals(baseId)) {
                    baseEntry = entry;
                }
            }
        }
        if (additions.isEmpty()) {
            return;
        }
        if (baseEntry == null) {
            baseEntry = additions.get(0);
        }
        double base = player.getAttributeBaseValue(attribute);
        for (ItemAttributeModifiers.Entry entry : additions) {
            double scaled = entry.modifier().amount() * multiplier;
            if (entry == baseEntry) {
                scaled += base * (multiplier - 1.0D);
            }
            event.replaceModifier(attribute, new AttributeModifier(entry.modifier().id(), scaled,
                    AttributeModifier.Operation.ADD_VALUE), entry.slot());
        }
    }
}
