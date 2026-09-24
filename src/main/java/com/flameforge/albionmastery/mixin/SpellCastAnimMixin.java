package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.client.CastSpeedSync;
import com.flameforge.albionmastery.client.PlayerAnimSpeed;
import com.flameforge.albionmastery.mastery.spec.SpecVisualAttributes;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellAnimations;
import io.redspace.ironsspellbooks.player.ClientSpellCastHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

import javax.annotation.Nullable;

/**
 * 长吟唱的施法动作按施法时间特化的倍速播。
 *
 * <p>补的是什么：施法前摇是一段固定长度的关键帧动作（抬手、聚能），和吟唱时间本来就没绑定。
 * 施法时间特化把吟唱压到六成，动作才抬到一半法术就放出去了，看着像被打断；
 * 加长吟唱则是动作早早摆好、干等着。这里让动作跟着吟唱时间一起快慢。
 *
 * <p>倍速的来源分两种：自己施法时当场按这个法术的特化算（没有网络延迟），
 * 别人施法时读会同步的属性（客户端没有别人的专精数据）。
 *
 * <p>瞬发和持续引导不动：我们本来就没改它们的施法时间。
 *
 * <p>目标是铁魔法的类，{@code remap = false}；铁魔法或 Player Animator 没装时整个 Mixin 不应用。
 */
@Mixin(value = ClientSpellCastHelper.class, remap = false)
public abstract class SpellCastAnimMixin {

    /**
     * 开始吟唱：给施法动作定速。
     *
     * @param castingEntityId 施法者
     * @param spellId         法术 ID
     * @param spellLevel      法术等级
     * @param ci              回调
     */
    @Inject(method = "handleClientBoundOnCastStarted", at = @At("RETURN"), require = 0)
    private static void albionmastery$castSpeed(UUID castingEntityId, String spellId, int spellLevel,
                                                CallbackInfo ci) {
        AbstractClientPlayer player = albionmastery$playerOf(castingEntityId);
        if (player == null) {
            return;
        }
        PlayerAnimSpeed.apply(player, SpellAnimations.ANIMATION_RESOURCE,
                albionmastery$speedOf(player, spellId));
        // 「开始吟唱」的包和属性更新包不是一起发的：属性是每 tick 由实体追踪器批量发的，
        // 必定晚于这条包到。看别人施法时这一帧读到的还是上一次的倍速，
        // 所以接下来几 tick 再按属性重挂一次（多播一 tick 原速看不出来）。
        // 自己施法时同理：补放那一次的系数只有服务端知道，也得等属性到
        CastSpeedSync.schedule(player, SpellAnimations.ANIMATION_RESOURCE);
    }

    /**
     * 吟唱结束：恢复原速，免得下一段动作跟着上一次的倍速播。
     *
     * @param castingEntityId 施法者
     * @param spellId         法术 ID
     * @param cancelled       是不是被打断
     * @param ci              回调
     */
    @Inject(method = "handleClientBoundOnCastFinished", at = @At("RETURN"), require = 0)
    private static void albionmastery$castDone(UUID castingEntityId, String spellId, boolean cancelled,
                                               CallbackInfo ci) {
        CastSpeedSync.cancel(castingEntityId);
        AbstractClientPlayer player = albionmastery$playerOf(castingEntityId);
        if (player != null) {
            PlayerAnimSpeed.apply(player, SpellAnimations.ANIMATION_RESOURCE, 1.0D);
        }
    }

    /**
     * 按 UUID 找这个客户端上的玩家。
     *
     * @param id 玩家 UUID
     * @return 玩家；不在这个世界里返回 null
     */
    @Nullable
    private static AbstractClientPlayer albionmastery$playerOf(UUID id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || id == null) {
            return null;
        }
        Player player = minecraft.level.getPlayerByUUID(id);
        return player instanceof AbstractClientPlayer client ? client : null;
    }

    /**
     * 这次施法该以几倍速播动作。
     *
     * @param player  施法者
     * @param spellId 法术 ID
     * @return 倍速
     */
    private static double albionmastery$speedOf(AbstractClientPlayer player, String spellId) {
        ResourceLocation id = ResourceLocation.tryParse(spellId);
        AbstractSpell spell = id == null ? null : SpellRegistry.getSpell(id);
        if (spell == null || spell.getCastType() != CastType.LONG) {
            return 1.0D;
        }
        if (player == Minecraft.getInstance().player) {
            // 自己的数据本机就有，当场算，不用等属性同步
            double factor = SpecializationEffects.forWeapon(player, IronsSpellsSupport.ofSpell(spell)).castTime();
            return factor > 0.0D && Double.isFinite(factor) ? 1.0D / factor : 1.0D;
        }
        return SpecVisualAttributes.of(player, SpecVisualAttributes.CAST_SPEED);
    }
}
