package com.flameforge.albionmastery.event;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.ComboTracker;
import com.flameforge.albionmastery.mastery.spec.SpecMoveSpeed;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

/**
 * 流派里那些<b>不是「乘在伤害上」</b>的效果，集中放在这里：
 * <ul>
 *     <li>吸血：按最终造成的伤害回血（{@link LivingDamageEvent}）；</li>
 *     <li>击杀补弹：枪械击杀后直接往弹匣里补子弹（{@link EntityKillByGunEvent}）；</li>
 *     <li>持有移速：主手换武器时重算移速修饰符（{@link LivingEquipmentChangeEvent}）；</li>
 *     <li>连击计数的定期清理与玩家下线清理。</li>
 * </ul>
 *
 * <p>伤害倍率类的效果（暴击、连击、各种条件倍率）留在三个来源各自的战斗处理器里，
 * 因为那些必须在伤害结算<b>之前</b>改数，而这里的几个都是结算<b>之后</b>才有意义的。
 *
 * <p>全部只在服务端生效：吸血改的是血量、补弹改的是物品 NBT、移速改的是属性，
 * 这三样都会自动同步到客户端，客户端不需要再算一遍。
 */
@Mod.EventBusSubscriber(modid = AlbionMastery.MOD_ID)
public final class SpecializationCombatHandler {

    private SpecializationCombatHandler() {
    }

    /**
     * 吸血：造成伤害后按比例回血。
     *
     * <p>用 {@code LivingDamageEvent} 而不是 {@code LivingHurtEvent}：前者的数值是
     * 护甲、抗性都扣完之后真正会从血条上减掉的那份，吸血按这个算才不会出现
     * 「打满甲目标只掉 1 血却回了 5 血」。优先级放最低，让别的模组先改完伤害。
     *
     * <p>武器按攻击者主手认：子弹飞出去之后玩家可能已经换了武器，这种边界情况按新武器算，
     * 无伤大雅，比为每颗子弹记一份武器快照划算得多。
     *
     * @param event 伤害事件
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDamage(LivingDamageEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0.0F) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target == player || !target.isAlive()) {
            return;
        }
        SpecializationValues values = currentValues(player, player.getMainHandItem());
        if (values == null || values.lifeSteal() <= 0.0D) {
            return;
        }
        // 回血量按目标剩余血量封顶：一枪 200 伤害打在 20 血的怪身上，不该回 200 血的 10%
        float effective = Math.min(event.getAmount(), target.getHealth());
        float heal = (float) (effective * values.lifeSteal());
        if (heal > 0.0F && player.getHealth() < player.getMaxHealth()) {
            player.heal(heal);
        }
    }

    /**
     * 击杀补弹：用枪打死目标后，立刻往弹匣里补回一部分子弹。
     *
     * <p>补的是<b>弹匣里的数</b>（物品 NBT 上的当前弹药），不动背包弹药，
     * 所以对玩家来说等于「白送了几发」。「直接从背包取弹」模式的枪没有弹匣概念，跳过。
     *
     * <p>容量上限按配件和流派（扩容弹匣）算过之后的值，和换弹逻辑里用的同一套，
     * 不会出现补到「原始容量」就停下来的错位。
     *
     * @param event 击杀事件
     */
    @SubscribeEvent
    public static void onEntityKillByGun(EntityKillByGunEvent event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }
        if (!(event.getAttacker() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        ResourceLocation gunId = event.getGunId();
        if (gunId == null) {
            return;
        }
        ItemStack gun = findHeldGun(player, gunId);
        if (gun == null) {
            return;
        }
        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun == null || iGun.useInventoryAmmo(gun)) {
            return;
        }
        SpecializationValues values = currentValues(player, gun);
        if (values == null || values.killRefill() <= 0.0D) {
            return;
        }

        Optional<CommonGunIndex> index = TimelessAPI.getCommonGunIndex(gunId);
        if (index.isEmpty()) {
            return;
        }
        GunData gunData = index.get().getGunData();
        int capacity = AttachmentDataUtils.getAmmoCountWithAttachment(gun, gunData);
        // 和 GunAmmoAndReloadMixin 里同一个算法，扩容弹匣流派补的也是扩容后的量
        if (values.magazine() != 1.0D) {
            capacity = Math.max(1, (int) Math.round(capacity * values.magazine()));
        }
        int current = iGun.getCurrentAmmoCount(gun);
        if (current >= capacity) {
            return;
        }
        // 至少补 1 发：小弹匣手枪按比例算出来 0.4 发，四舍五入成 0 会让这个流派在手枪上完全没感觉
        int refill = Math.max(1, (int) Math.round(capacity * values.killRefill()));
        iGun.setCurrentAmmoCount(gun, Math.min(capacity, current + refill));
    }

    /**
     * 主手物品变了就重算移速修饰符。
     *
     * <p>这个事件在主手物品的 NBT 有任何变化时都会触发（枪械开一枪弹药数变了也算），
     * 所以重算那边做了「值没变就不动属性」的判断，这里不用再节流。
     *
     * @param event 装备变化事件
     */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.MAINHAND) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            SpecMoveSpeed.refresh(player);
        }
    }

    /**
     * 定期清理过期的连击记录。
     *
     * @param event 服务端 tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }
        ComboTracker.tick(event.getServer().overworld().getGameTime());
    }

    /**
     * 玩家下线：清掉他的连击记录和移速修饰符。
     *
     * @param event 下线事件
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ComboTracker.forget(player.getUUID());
            SpecMoveSpeed.remove(player);
        }
    }

    /**
     * 服务器关闭时整体清空，别让静态表攥着实体引用。
     *
     * @param event 关服事件
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ComboTracker.clear();
    }

    /**
     * 在玩家手里找到指定 ID 的枪。
     *
     * <p>先看主手再看副手：击杀事件只给枪械 ID 不给物品堆，而补弹要改物品 NBT，
     * 只能自己按 ID 去手里对。
     *
     * @param player 玩家
     * @param gunId  枪械 ID
     * @return 那把枪；两只手都不是就返回 null
     */
    private static ItemStack findHeldGun(ServerPlayer player, ResourceLocation gunId) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            IGun iGun = IGun.getIGunOrNull(stack);
            if (iGun != null && gunId.equals(iGun.getGunId(stack))) {
                return stack;
            }
        }
        return null;
    }

    /**
     * 取玩家手上这件武器当前生效的流派数值。
     *
     * @param player 玩家
     * @param stack  武器
     * @return 数值；不是可专精武器或没数据时返回 null
     */
    private static SpecializationValues currentValues(ServerPlayer player, ItemStack stack) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        if (!snapshot.enableSpecialization) {
            return null;
        }
        ResolvedWeapon weapon = WeaponResolver.resolve(stack, snapshot.splitMeleeByPack);
        if (weapon == null) {
            return null;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return null;
        }
        return SpecializationEffects.forData(data, weapon.typeKey(), snapshot);
    }
}
