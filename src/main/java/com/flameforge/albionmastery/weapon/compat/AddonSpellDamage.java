package com.flameforge.albionmastery.weapon.compat;

import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 认出附属法术打出的普通伤害。
 *
 * <p>不少附属（学徒法典、T.O、光污染……）打伤害时用自己注册的伤害类型，不走铁魔法的 SpellDamageSource，
 * 受伤事件里看不出是哪个法术。两种认法：伤害类型和法术同名（大部分附属都这么起名）；
 * 对不上的，伤害类型或直接造成伤害的实体属于玩家最近放过的那个附属法术的模组，就算那个法术的。
 */
public final class AddonSpellDamage {

    // 30 秒：召唤物、场地类法术大多在这之内打完；拉得太长，同模组武器的特殊攻击也会被算成法术
    private static final long RECENT_CAST_TICKS = 600L;

    private static final String MINECRAFT = "minecraft";
    private static final String IRONS = "irons_spellbooks";

    private static final Map<UUID, LastCast> LAST_CAST = new HashMap<>();

    private record LastCast(AbstractSpell spell, int tick) {
    }

    private AddonSpellDamage() {
    }

    public static void recordCast(ServerPlayer player, @Nullable AbstractSpell spell) {
        if (spell == null || spell == SpellRegistry.none()) {
            return;
        }
        ResourceLocation id = spell.getSpellResource();
        if (id == null || !isAddon(id.getNamespace())) {
            return;
        }
        // 用服务器 tick 而不是世界时间：各维度的 gameTime 不一样，跨维度会算错
        LAST_CAST.put(player.getUUID(), new LastCast(spell, player.server.getTickCount()));
    }

    @Nullable
    public static AbstractSpell find(ServerPlayer player, DamageSource source) {
        ResourceLocation typeId = source.typeHolder().unwrapKey().map(ResourceKey::location).orElse(null);
        if (typeId != null && isAddon(typeId.getNamespace())) {
            AbstractSpell named = SpellRegistry.getSpell(typeId);
            if (named != null && named != SpellRegistry.none()) {
                return named;
            }
        }
        LastCast last = LAST_CAST.get(player.getUUID());
        if (last == null || player.server.getTickCount() - last.tick() > RECENT_CAST_TICKS) {
            return null;
        }
        String namespace = last.spell().getSpellResource().getNamespace();
        if (typeId != null && namespace.equals(typeId.getNamespace())) {
            return last.spell();
        }
        Entity direct = source.getDirectEntity();
        if (direct != null && direct != player) {
            ResourceLocation entityId = ForgeRegistries.ENTITY_TYPES.getKey(direct.getType());
            if (entityId != null && namespace.equals(entityId.getNamespace())) {
                return last.spell();
            }
        }
        return null;
    }

    public static void clear() {
        LAST_CAST.clear();
    }

    private static boolean isAddon(String namespace) {
        return !MINECRAFT.equals(namespace) && !IRONS.equals(namespace);
    }
}
