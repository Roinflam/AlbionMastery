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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.ItemAttributeModifierEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 把专精加成写进物品的攻击力属性修饰符里（仅客户端）。
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
 * <p>只处理主手的 {@code ADDITION} 修饰符：按百分比生效的修饰符（比如刀包里常见的
 * 移速加成）如果也乘上倍率，语义会完全错掉。
 */
@Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID, value = Dist.CLIENT)
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
                ClientMasteryCache.data(), weapon.typeKey(), ClientMasteryCache.snapshot());
        if (WeaponKeys.PROVIDER_SLASHBLADE.equals(weapon.providerId())) {
            return values.directDamage();
        }
        if (WeaponKeys.PROVIDER_LRTACTICAL_MELEE.equals(weapon.providerId())
                || WeaponKeys.PROVIDER_VANILLA_MELEE.equals(weapon.providerId())) {
            return values.damage();
        }
        return 1.0D;
    }

    /**
     * 改写攻击力修饰符。
     *
     * @param event 属性修饰符事件
     */
    @SubscribeEvent
    public static void onItemAttributeModifiers(ItemAttributeModifierEvent event) {
        if (!MasteryConfig.CLIENT.overrideDamageDisplay.get()) {
            return;
        }
        if (event.getSlotType() != EquipmentSlot.MAINHAND) {
            return;
        }

        ItemStack stack = event.getItemStack();
        ResolvedWeapon weapon = MasteryClientBonus.resolve(stack);
        if (weapon == null) {
            return;
        }
        MasteryHelper.Bonus bonus = MasteryClientBonus.bonusFor(stack);
        if (bonus == null) {
            return;
        }
        // 特化也要算进来：疾风把伤害压到 65%、重压提到 160%，这些都是实打实生效的，
        // 属性栏却照着原始值显示，玩家会以为「选了没用」
        double specDamage = specDamageOf(weapon);
        double multiplier = bonus.multiplier() * specDamage;
        if (Math.abs(multiplier - 1.0D) <= 1.0E-6D) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        Collection<AttributeModifier> current = event.getModifiers().get(Attributes.ATTACK_DAMAGE);
        if (current.isEmpty()) {
            return;
        }

        // 先拷一份再改，避免在遍历 Multimap 视图的同时修改它
        List<AttributeModifier> additions = new ArrayList<>();
        for (AttributeModifier modifier : current) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) {
                additions.add(modifier);
            }
        }
        if (additions.isEmpty()) {
            return;
        }

        // 原版在主手攻击力这一行会把玩家的基础攻击力加进去一起显示，
        // 这里按「(基础 + 修饰) * 倍率 - 基础」换算，保证提示里的总值就是最终伤害
        double base = player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);

        for (AttributeModifier modifier : additions) {
            event.removeModifier(Attributes.ATTACK_DAMAGE, modifier);
            double boosted = (base + modifier.getAmount()) * multiplier - base;
            event.addModifier(Attributes.ATTACK_DAMAGE, new AttributeModifier(
                    modifier.getId(), modifier.getName(), boosted, AttributeModifier.Operation.ADDITION));
        }
    }
}
