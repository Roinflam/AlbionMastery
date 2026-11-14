package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.AMIds;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.event.CommonEventHandler;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.MasteryData;
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
 * 客户端请求切换某个来源的专精特化。
 *
 * <p>这是本模组的第一个 C2S 包，处理时必须把客户端当成不可信输入：
 * 包里只带一个特化键，解锁条件、冷却、键是否合法全部在服务端重新判一遍，
 * 客户端界面里的置灰只是给玩家看的，不构成任何授权。
 */
public class C2SSelectSpecializationPacket implements CustomPacketPayload {

    /** 负载 ID。 */
    public static final CustomPacketPayload.Type<C2SSelectSpecializationPacket> TYPE =
            new CustomPacketPayload.Type<>(AMIds.of("select_specialization"));

    /** 目标武器类型的键。 */
    private final String typeKey;

    /** 特化的完整键。 */
    private final String specKey;

    /** 槽位，1～4。 */
    private final int slot;

    /** 武器键：空串表示改类型的通用配置，否则改这件武器的独立配置。 */
    private final String weaponKey;

    /** true 表示不是选特化，而是让这件武器恢复跟随通用配置。 */
    private final boolean followType;

    /** 武器键的最大长度。 */
    private static final int MAX_WEAPON_KEY = 256;

    /**
     * 构造网络包（第一槽）。
     *
     * @param typeKey 目标武器类型的键
     * @param specKey 特化的完整键
     */
    public C2SSelectSpecializationPacket(String typeKey, String specKey) {
        this(typeKey, specKey, 1);
    }

    /**
     * 构造网络包。
     *
     * @param typeKey 目标武器类型的键
     * @param specKey 特化的完整键
     * @param slot    槽位，1 或 2
     */
    public C2SSelectSpecializationPacket(String typeKey, String specKey, int slot) {
        this(typeKey, specKey, slot, "", false);
    }

    /**
     * 构造网络包。
     *
     * @param typeKey    目标武器类型的键
     * @param specKey    特化的完整键（恢复通用配置时忽略）
     * @param slot       槽位，1 或 2
     * @param weaponKey  武器键，空串表示改通用配置
     * @param followType 是否是「恢复通用配置」
     */
    public C2SSelectSpecializationPacket(String typeKey, String specKey, int slot, String weaponKey,
                                         boolean followType) {
        this.typeKey = typeKey;
        this.specKey = specKey;
        this.slot = slot;
        this.weaponKey = weaponKey == null ? "" : weaponKey;
        this.followType = followType;
    }

    /**
     * 写入缓冲区。
     *
     * @param buf 目标缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.typeKey, 128);
        buf.writeUtf(this.specKey, 128);
        buf.writeVarInt(this.slot);
        buf.writeUtf(this.weaponKey, MAX_WEAPON_KEY);
        buf.writeBoolean(this.followType);
    }

    /**
     * 从缓冲区读取。
     *
     * @param buf 源缓冲区
     * @return 网络包
     */
    public static C2SSelectSpecializationPacket decode(FriendlyByteBuf buf) {
        return new C2SSelectSpecializationPacket(buf.readUtf(128), buf.readUtf(128),
                Math.max(1, Math.min(WeaponKeys.SPEC_SLOTS, buf.readVarInt())),
                buf.readUtf(MAX_WEAPON_KEY), buf.readBoolean());
    }

    /**
     * 服务端处理。
     *
     * @param packet  网络包
     * @param context 网络上下文
     */
    public static void handle(C2SSelectSpecializationPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        String weapon = packet.weaponKey.isEmpty() ? null : packet.weaponKey;
        if (packet.followType) {
            if (weapon == null) {
                return;
            }
            SpecializationManager.Result result = SpecializationManager.followType(player, packet.typeKey, weapon);
            // 失败也要给话：补了冷却检查之后 COOLDOWN 成了可达分支，
            // 只放一声拒绝音的话，玩家只会觉得按钮点了没反应
            String provider = WeaponKeys.providerOf(packet.typeKey);
            Component feedback = switch (result) {
                case OK -> Component.translatable("msg.albionmastery.spec_follow_type",
                        SpecTerms.of(provider, "page")).withStyle(ChatFormatting.GOLD);
                case COOLDOWN -> Component.translatable("msg.albionmastery.spec_cooldown",
                                SpecTerms.of(provider, "spec"),
                                formatDuration(SpecializationManager.remainingSwitchCooldown(
                                        player, packet.typeKey, weapon, MasteryConfig.serverSnapshot())))
                        .withStyle(ChatFormatting.RED);
                case DISABLED -> Component.translatable("msg.albionmastery.spec_disabled")
                        .withStyle(ChatFormatting.RED);
                default -> null;
            };
            if (feedback != null) {
                player.displayClientMessage(feedback, true);
            }
            if (result == SpecializationManager.Result.OK) {
                CommonEventHandler.syncFull(player);
            }
            playFeedbackSound(player, result);
            return;
        }
        Specialization spec = Specialization.byKey(packet.specKey);
        if (spec == null) {
            // 键不认识：要么是客户端版本不一致，要么是有人在乱发包，直接丢掉
            return;
        }
        SpecializationManager.Result result =
                SpecializationManager.select(player, packet.typeKey, weapon, packet.slot, spec);
        Component feedback = describe(result, player, spec, packet.typeKey, weapon, packet.slot,
                weapon != null);
        if (feedback != null) {
            player.displayClientMessage(feedback, true);
        }
        // 成功和失败各给一声，光靠物品栏上方那行小字很容易没注意到
        playFeedbackSound(player, result);
        if (result == SpecializationManager.Result.OK) {
            // syncFull 会顺带给排行榜打标记（榜上的特化数要更新）并重算挂在属性上的特化效果
            CommonEventHandler.syncFull(player);
        }
    }

    /**
     * 按结果给一声反馈音。
     *
     * <p>成功用附魔台那声「叮」，失败用村民的拒绝声——这两个音在原版里本来就是
     * 「成了」和「不行」的意思，玩家不用学就懂。
     *
     * @param player 玩家
     * @param result 切换结果
     */
    private static void playFeedbackSound(ServerPlayer player, SpecializationManager.Result result) {
        switch (result) {
            case OK -> player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS,
                    0.6F, 1.4F);
            case LOCKED, COOLDOWN, DISABLED, DUPLICATE, CONFLICT, NOT_LEVELED -> player.playNotifySound(SoundEvents.VILLAGER_NO,
                    SoundSource.PLAYERS, 0.7F, 1.0F);
            // 键非法或者没变，不出声
            default -> {
            }
        }
    }

    /**
     * 把切换结果翻译成给玩家看的提示。
     *
     * @param result 切换结果
     * @param player 玩家
     * @param spec      目标特化
     * @param typeKey   类型键
     * @param weaponKey 武器键，改的是通用配置时为 null
     * @param slot      槽位
     * @param forWeapon 改的是不是某件武器的独立配置
     * @return 提示文本；不需要提示时返回 null
     */
    @Nullable
    private static Component describe(SpecializationManager.Result result, ServerPlayer player,
                                      Specialization spec, String typeKey, @Nullable String weaponKey,
                                      int slot, boolean forWeapon) {
        return switch (result) {
            case OK -> Component.translatable(forWeapon
                            ? "msg.albionmastery.spec_selected_weapon" : "msg.albionmastery.spec_selected_slot",
                    SpecTerms.slot(spec.sourceId(), slot),
                    Component.translatable(spec.nameKey())).withStyle(ChatFormatting.GOLD);
            case DUPLICATE -> Component.translatable("msg.albionmastery.spec_duplicate",
                            Component.translatable(spec.nameKey()))
                    .withStyle(ChatFormatting.RED);
            case CONFLICT -> Component.translatable("msg.albionmastery.spec_conflict",
                            Component.translatable(spec.nameKey()),
                            SpecTerms.of(spec.sourceId(), "group"),
                            Component.translatable(spec.familyKey()))
                    .withStyle(ChatFormatting.RED);
            case DISABLED -> Component.translatable("msg.albionmastery.spec_disabled")
                    .withStyle(ChatFormatting.RED);
            case LOCKED -> Component.translatable("msg.albionmastery.spec_locked",
                            SpecTerms.slot(spec.sourceId(), slot))
                    .withStyle(ChatFormatting.RED);
            case COOLDOWN -> Component.translatable("msg.albionmastery.spec_cooldown",
                            SpecTerms.of(spec.sourceId(), "spec"),
                            // 和判定同一个口径：两个键取大，否则拒绝理由来自类型键时会写成「还需 0 分钟」
                            formatDuration(SpecializationManager.remainingSwitchCooldown(
                                    player, typeKey, weaponKey, slot, MasteryConfig.serverSnapshot())))
                    .withStyle(ChatFormatting.RED);
            case NOT_LEVELED -> Component.translatable(
                            snapshotRules().isBreakthroughSlot(slot)
                                    ? "msg.albionmastery.spec_not_broken" : "msg.albionmastery.spec_not_leveled",
                            Component.translatable(spec.nameKey()), SpecTerms.slot(spec.sourceId(), slot))
                    .withStyle(ChatFormatting.RED);
            // 键非法或者压根没变，不用打扰玩家；剩下几种是升级 / 突破才有的结果
            case INVALID, UNCHANGED, NO_POINTS, MAXED, NEED_NORMAL_MAX -> null;
        };
    }

    private static com.flameforge.albionmastery.config.SpecRules snapshotRules() {
        return MasteryConfig.serverSnapshot().specRules;
    }

    /**
     * 把秒数写成「1 小时 23 分钟」这种好读的形式。
     *
     * <p>单位走语言文件而不是写死中文：这条提示是发给玩家看的，
     * 英文客户端读到一句中英混排的话会很怪。
     *
     * @param seconds 秒数
     * @return 组件
     */
    static Component formatDuration(long seconds) {
        long minutes = (seconds + 59L) / 60L;
        if (minutes < 60L) {
            return Component.translatable("msg.albionmastery.duration_minutes", minutes);
        }
        return Component.translatable("msg.albionmastery.duration_hours",
                minutes / 60L, minutes % 60L);
    }

    @Override
    public CustomPacketPayload.Type<C2SSelectSpecializationPacket> type() {
        return TYPE;
    }
}
