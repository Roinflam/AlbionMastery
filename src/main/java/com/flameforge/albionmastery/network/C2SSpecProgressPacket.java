package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.AMIds;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.event.CommonEventHandler;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.SpecPoints;
import com.flameforge.albionmastery.mastery.spec.SpecTerms;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import com.flameforge.albionmastery.mastery.spec.SpecializationManager;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;

/**
 * 客户端请求升级特化、突破、重置点数。
 *
 * <p>和选特化一样，客户端只说「想做什么」，点数够不够、冷却到没到全在服务端重新算。
 */
public class C2SSpecProgressPacket implements CustomPacketPayload {

    /** 负载 ID。 */
    public static final CustomPacketPayload.Type<C2SSpecProgressPacket> TYPE =
            new CustomPacketPayload.Type<>(AMIds.of("spec_progress"));

    /** 要做的事。 */
    public enum Action {
        /** 花改装点把特化升一级（{@code key} 不用，{@code specKey} 是特化）。 */
        UPGRADE,
        /** 花突破点突破一级（{@code key} 是类型键）。 */
        BREAKTHROUGH,
        /** 重置一个来源的改装点（{@code key} 是来源 ID）。 */
        RESPEC_SOURCE,
        /** 重置一个类型的突破点（{@code key} 是类型键）。 */
        RESPEC_TYPE
    }

    private final Action action;
    private final String key;
    private final String specKey;

    /**
     * @param action  要做的事
     * @param key     类型键 / 来源 ID，看 action
     * @param specKey 特化键，重置时为空串
     */
    public C2SSpecProgressPacket(Action action, String key, String specKey) {
        this.action = action;
        this.key = key == null ? "" : key;
        this.specKey = specKey == null ? "" : specKey;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(this.action);
        buf.writeUtf(this.key, 256);
        buf.writeUtf(this.specKey, 128);
    }

    public static C2SSpecProgressPacket decode(FriendlyByteBuf buf) {
        return new C2SSpecProgressPacket(buf.readEnum(Action.class), buf.readUtf(256), buf.readUtf(128));
    }

    public static void handle(C2SSpecProgressPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Specialization spec = Specialization.byKey(packet.specKey);
        SpecializationManager.Result result = switch (packet.action) {
            case UPGRADE -> spec == null ? SpecializationManager.Result.INVALID
                    : SpecializationManager.upgrade(player, spec);
            case BREAKTHROUGH -> spec == null ? SpecializationManager.Result.INVALID
                    : SpecializationManager.breakthrough(player, packet.key, spec);
            case RESPEC_SOURCE -> SpecializationManager.respecSource(player, packet.key);
            case RESPEC_TYPE -> SpecializationManager.respecType(player, packet.key);
        };
        Component feedback = packet.describe(player, spec, result);
        if (feedback != null) {
            player.displayClientMessage(feedback, true);
        }
        switch (result) {
            case OK -> player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F,
                    packet.action == Action.BREAKTHROUGH ? 1.8F : 1.4F);
            case INVALID, UNCHANGED -> {
            }
            default -> player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.7F, 1.0F);
        }
        if (result == SpecializationManager.Result.OK) {
            CommonEventHandler.syncFull(player);
        }
    }

    @Nullable
    private Component describe(ServerPlayer player, @Nullable Specialization spec,
                               SpecializationManager.Result result) {
        String provider = spec != null ? spec.sourceId() : WeaponKeys.providerOf(this.key);
        MasteryData data = MasteryHelper.get(player);
        return switch (result) {
            case OK -> switch (this.action) {
                case UPGRADE -> spec == null || data == null ? null
                        : Component.translatable("msg.albionmastery.spec_upgraded",
                                Component.translatable(spec.nameKey()), data.getSpecLevel(spec.key()))
                        .withStyle(ChatFormatting.GOLD);
                case BREAKTHROUGH -> spec == null || data == null ? null
                        : Component.translatable("msg.albionmastery.spec_broke_through",
                                Component.translatable(spec.nameKey()), data.getBreakthrough(this.key, spec.key()))
                        .withStyle(ChatFormatting.GOLD);
                case RESPEC_SOURCE -> Component.translatable("msg.albionmastery.spec_respec_source",
                        SpecTerms.of(provider, "point")).withStyle(ChatFormatting.GOLD);
                case RESPEC_TYPE -> Component.translatable("msg.albionmastery.spec_respec_type")
                        .withStyle(ChatFormatting.GOLD);
            };
            case NO_POINTS -> Component.translatable(this.action == Action.BREAKTHROUGH
                            ? "msg.albionmastery.spec_no_breakthrough_points" : "msg.albionmastery.spec_no_points",
                    SpecTerms.of(provider, "point")).withStyle(ChatFormatting.RED);
            case MAXED -> Component.translatable("msg.albionmastery.spec_maxed").withStyle(ChatFormatting.RED);
            case NEED_NORMAL_MAX -> Component.translatable("msg.albionmastery.spec_need_normal_max")
                    .withStyle(ChatFormatting.RED);
            case COOLDOWN -> data == null ? null : Component.translatable("msg.albionmastery.spec_respec_cooldown",
                            C2SSelectSpecializationPacket.formatDuration((SpecPoints.respecRemainingMillis(data,
                                    this.key, MasteryConfig.serverSnapshot().specRules,
                                    System.currentTimeMillis()) + 999L) / 1000L))
                    .withStyle(ChatFormatting.RED);
            case UNCHANGED -> this.action == Action.RESPEC_SOURCE || this.action == Action.RESPEC_TYPE
                    ? Component.translatable("msg.albionmastery.spec_respec_nothing").withStyle(ChatFormatting.GRAY)
                    : null;
            case DISABLED -> Component.translatable("msg.albionmastery.spec_disabled").withStyle(ChatFormatting.RED);
            default -> null;
        };
    }

    @Override
    public CustomPacketPayload.Type<C2SSpecProgressPacket> type() {
        return TYPE;
    }
}
