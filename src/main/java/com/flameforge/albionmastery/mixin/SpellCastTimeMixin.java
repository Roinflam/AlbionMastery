package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.event.SpellCombatHandler;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import io.redspace.ironsspellbooks.api.spells.CastType;

/**
 * 铁魔法施法时间（前摇）按特化缩放。
 *
 * <p>补的是什么：{@code AbstractSpell.getEffectiveCastTime(等级, 施法者)} 是最终的吟唱 / 前摇时长，
 * 施法者身上的施法速度属性已经算在里面了。在返回值上再乘特化倍率。两端都会调（客户端做进度条，
 * 服务端做判定），各按各的数据算。只对玩家生效。
 */
@Mixin(value = AbstractSpell.class, remap = false)
public abstract class SpellCastTimeMixin {

    /**
     * @param spellLevel   法术等级
     * @param caster       施法者
     * @param callbackInfo 携带原始施法时间（tick）的回调
     */
    @Inject(method = "getEffectiveCastTime", at = @At("RETURN"), cancellable = true, require = 0)
    private void albionmastery$scaleCastTime(int spellLevel, LivingEntity caster,
                                             CallbackInfoReturnable<Integer> callbackInfo) {
        // 持续引导的法术（光束、吐息），这个值是「能引导多久」而不是「要读多久」：
        // 按读条缩短的话，加快施法的特化反而把光束缩短了，拖慢施法的反倒延长了。不动它
        if (!(caster instanceof Player player)
                || ((AbstractSpell) (Object) this).getCastType() == CastType.CONTINUOUS) {
            return;
        }
        double factor = SpecializationEffects.forWeapon(player,
                IronsSpellsSupport.ofSpell((AbstractSpell) (Object) this)).castTime();
        // 双重施法补放的那一次：引导时间再乘配置里的系数（默认减半）
        if (!player.level().isClientSide() && SpellCombatHandler.isEchoCasting(player)) {
            factor *= MasteryConfig.COMMON.doubleCastTimeFactor.get();
        }
        if (factor != 1.0D && callbackInfo.getReturnValueI() > 0) {
            callbackInfo.setReturnValue(Math.max(1, (int) Math.round(callbackInfo.getReturnValueI() * factor)));
        }
    }
}
