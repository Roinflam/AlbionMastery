package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.AMIds;
import com.flameforge.albionmastery.mastery.MasteryAdmin;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.SpecializationManager;
import com.flameforge.albionmastery.weapon.WeaponCatalog;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 罗盘里管理按钮发来的操作（C2S），只作用于发包的人自己。
 *
 * <p>按钮只在客户端认为自己有权限时才画出来，但那只是界面；
 * 服务端每个包都重新判一次权限等级，没权限的包直接丢掉。
 */
public class C2SAdminActionPacket implements CustomPacketPayload {

    /** 负载 ID。 */
    public static final CustomPacketPayload.Type<C2SAdminActionPacket> TYPE =
            new CustomPacketPayload.Type<>(AMIds.of("admin_action"));

    /** 需要的权限等级，和指令一致。 */
    public static final int PERMISSION_LEVEL = 2;

    /** 键的最大长度，和武器键的上限一致。 */
    private static final int MAX_KEY = 256;

    /**
     * 操作种类。
     */
    public enum Action {
        /** 设某一个节点的等级；value 小于 0 表示满级。 */
        SET_LEVEL,
        /** 某一个节点的等级加减 value 级。 */
        ADJUST_LEVEL,
        /** 整个来源或整个类型设成同一个等级；value 小于 0 表示满级。 */
        SET_SUBTREE,
        /** 全部设成同一个等级；value 小于 0 表示满级。 */
        SET_ALL,
        /** 发一件武器对应的物品。 */
        GIVE_WEAPON,
        /** 清掉特化切换冷却。 */
        CLEAR_COOLDOWN
    }

    private final Action action;
    private final String key;
    private final String typeKey;
    private final int value;

    /**
     * 构造网络包。
     *
     * @param action  操作
     * @param key     目标节点的键（来源 ID、类型键或武器键），不需要时传空串
     * @param typeKey 武器所属的类型键，只有武器节点需要，其余传空串
     * @param value   等级或增减量
     */
    public C2SAdminActionPacket(Action action, String key, String typeKey, int value) {
        this.action = action;
        this.key = key == null ? "" : key;
        this.typeKey = typeKey == null ? "" : typeKey;
        this.value = value;
    }

    /**
     * 写入缓冲区。
     *
     * @param buf 目标缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(this.action);
        buf.writeUtf(this.key, MAX_KEY);
        buf.writeUtf(this.typeKey, MAX_KEY);
        buf.writeInt(this.value);
    }

    /**
     * 从缓冲区读取。
     *
     * @param buf 源缓冲区
     * @return 网络包
     */
    public static C2SAdminActionPacket decode(FriendlyByteBuf buf) {
        return new C2SAdminActionPacket(buf.readEnum(Action.class), buf.readUtf(MAX_KEY), buf.readUtf(MAX_KEY),
                buf.readInt());
    }

    /**
     * 服务端处理（主线程）。
     *
     * @param packet  网络包
     * @param context 网络上下文
     */
    public static void handle(C2SAdminActionPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.hasPermissions(PERMISSION_LEVEL)) {
            player.displayClientMessage(Component.translatable("msg.albionmastery.admin.no_permission")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        MasteryData data = MasteryHelper.get(player);
        if (data == null) {
            return;
        }
        Component feedback = packet.apply(player, data);
        if (feedback != null) {
            player.displayClientMessage(feedback, true);
        }
    }

    /**
     * 执行操作。
     *
     * @return 给玩家看的一句反馈；单个节点改等级不用说话（客户端会弹升级提示），返回 null
     */
    private Component apply(ServerPlayer player, MasteryData data) {
        int level = this.value < 0 ? MasteryAdmin.MAX : this.value;
        switch (this.action) {
            case SET_LEVEL, ADJUST_LEVEL -> {
                int target = level;
                if (this.action == Action.ADJUST_LEVEL) {
                    int current = MasteryAdmin.levelOf(data, this.key);
                    if (current < 0) {
                        return fail("msg.albionmastery.admin.bad_key");
                    }
                    target = Math.max(0, current + this.value);
                }
                String hint = this.typeKey.isEmpty() ? null : this.typeKey;
                if (!MasteryAdmin.setLevel(player, data, this.key, hint, target)) {
                    return fail("msg.albionmastery.admin.bad_key");
                }
                MasteryAdmin.commit(player);
                ding(player);
                return null;
            }
            case SET_SUBTREE -> {
                MasteryAdmin.Result result;
                if (WeaponKeys.isValidTypeKey(this.key)) {
                    result = MasteryAdmin.setType(player, data, this.key, level);
                } else if (WeaponKeys.isValidOverallKey(this.key)) {
                    result = MasteryAdmin.setSource(player, data, this.key, level);
                } else {
                    return fail("msg.albionmastery.admin.bad_key");
                }
                MasteryAdmin.commit(player);
                ding(player);
                return summary(result, level);
            }
            case SET_ALL -> {
                MasteryAdmin.Result result = MasteryAdmin.setAll(player, data, level);
                MasteryAdmin.commit(player);
                ding(player);
                return summary(result, level);
            }
            case GIVE_WEAPON -> {
                WeaponCatalog.Entry entry = WeaponCatalog.byKey(player.server, this.key);
                if (entry == null) {
                    return fail("msg.albionmastery.admin.unknown_weapon");
                }
                ItemStack given = MasteryAdmin.give(player, entry);
                if (given.isEmpty()) {
                    return fail("msg.albionmastery.admin.give_failed");
                }
                player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 1.2F);
                return Component.translatable("msg.albionmastery.admin.given", given.getHoverName())
                        .withStyle(ChatFormatting.GOLD);
            }
            case CLEAR_COOLDOWN -> {
                SpecializationManager.clearCooldowns(player.getUUID());
                ding(player);
                return Component.translatable("msg.albionmastery.admin.cooldown_cleared")
                        .withStyle(ChatFormatting.GOLD);
            }
            default -> {
                return null;
            }
        }
    }

    private static Component summary(MasteryAdmin.Result result, int level) {
        String key = level == 0 ? "msg.albionmastery.admin.wiped"
                : level == MasteryAdmin.MAX ? "msg.albionmastery.admin.maxed" : "msg.albionmastery.admin.set";
        return Component.translatable(key, result.weapons(), result.types(), result.sources(), level)
                .withStyle(ChatFormatting.GOLD);
    }

    private static Component fail(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.RED);
    }

    private static void ding(ServerPlayer player) {
        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5F, 1.3F);
    }

    @Override
    public CustomPacketPayload.Type<C2SAdminActionPacket> type() {
        return TYPE;
    }
}
