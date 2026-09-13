package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import com.flameforge.albionmastery.mastery.spec.SpecializationManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * 客户端请求切换某个来源的专精特化。
 *
 * <p>这是本模组的第一个 C2S 包，处理时必须把客户端当成不可信输入：
 * 包里只带一个流派键，解锁条件、冷却、键是否合法全部在服务端重新判一遍，
 * 客户端界面里的置灰只是给玩家看的，不构成任何授权。
 */
public class C2SSelectSpecializationPacket {

    /** 目标武器类型的键。 */
    private final String typeKey;

    /** 流派的完整键。 */
    private final String specKey;

    /**
     * 构造网络包。
     *
     * @param typeKey 目标武器类型的键
     * @param specKey 流派的完整键
     */
    public C2SSelectSpecializationPacket(String typeKey, String specKey) {
        this.typeKey = typeKey;
        this.specKey = specKey;
    }

    /**
     * 写入缓冲区。
     *
     * @param buf 目标缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.typeKey, 128);
        buf.writeUtf(this.specKey, 128);
    }

    /**
     * 从缓冲区读取。
     *
     * @param buf 源缓冲区
     * @return 网络包
     */
    public static C2SSelectSpecializationPacket decode(FriendlyByteBuf buf) {
        return new C2SSelectSpecializationPacket(buf.readUtf(128), buf.readUtf(128));
    }

    /**
     * 服务端处理。
     *
     * @param context 网络上下文
     */
    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            Specialization spec = Specialization.byKey(this.specKey);
            if (spec == null) {
                // 键不认识：要么是客户端版本不一致，要么是有人在乱发包，直接丢掉
                return;
            }
            SpecializationManager.Result result =
                    SpecializationManager.select(player, this.typeKey, spec);
            Component feedback = describe(result, player, spec, this.typeKey);
            if (feedback != null) {
                player.displayClientMessage(feedback, true);
            }
            // 成功和失败各给一声，光靠物品栏上方那行小字很容易没注意到
            playFeedbackSound(player, result);
            if (result == SpecializationManager.Result.OK) {
                MasteryData data = MasteryHelper.get(player);
                if (data != null) {
                    NetworkHandler.sendTo(player, S2CSyncMasteryPacket.of(
                            data, MasteryConfig.serverSnapshot()));
                    data.clearDirty();
                }
            }
        });
        ctx.setPacketHandled(true);
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
            case LOCKED, COOLDOWN, DISABLED -> player.playNotifySound(SoundEvents.VILLAGER_NO,
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
     * @param spec    目标流派
     * @param typeKey 目标武器类型的键
     * @return 提示文本；不需要提示时返回 null
     */
    @Nullable
    private static Component describe(SpecializationManager.Result result, ServerPlayer player,
                                      Specialization spec, String typeKey) {
        return switch (result) {
            case OK -> Component.translatable("msg.albionmastery.spec_selected",
                    Component.translatable(spec.nameKey())).withStyle(ChatFormatting.GOLD);
            case DISABLED -> Component.translatable("msg.albionmastery.spec_disabled")
                    .withStyle(ChatFormatting.RED);
            case LOCKED -> Component.translatable("msg.albionmastery.spec_locked")
                    .withStyle(ChatFormatting.RED);
            case COOLDOWN -> Component.translatable("msg.albionmastery.spec_cooldown",
                            formatDuration(SpecializationManager.remainingCooldownSeconds(
                                    player, typeKey, MasteryConfig.serverSnapshot())))
                    .withStyle(ChatFormatting.RED);
            // 键非法或者压根没变，不用打扰玩家
            case INVALID, UNCHANGED -> null;
        };
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
    private static Component formatDuration(long seconds) {
        long minutes = (seconds + 59L) / 60L;
        if (minutes < 60L) {
            return Component.translatable("msg.albionmastery.duration_minutes", minutes);
        }
        return Component.translatable("msg.albionmastery.duration_hours",
                minutes / 60L, minutes % 60L);
    }
}
