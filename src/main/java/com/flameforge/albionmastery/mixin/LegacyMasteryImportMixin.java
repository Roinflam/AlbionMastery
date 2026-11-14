package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.mastery.MasteryCapability;
import com.flameforge.albionmastery.mastery.MasteryData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 1.20.1 存档里的专精数据导入 1.21.1 的数据附件。
 *
 * <p>1.20.1 的专精数据是实体 Capability，存在玩家 NBT 的 {@code ForgeCaps."albionmastery:mastery"} 下；
 * 1.21.1 改成数据附件，存在 {@code neoforge:attachments."albionmastery:mastery"} 下，两处内容一字不差。
 * NeoForge 不认 {@code ForgeCaps}，原版数据修复也不碰它（玩家类型在各版本 schema 里都是可选字段，
 * 没声明的键原样保留），所以旧档读进来时它还在，但没有任何人读它；而 {@code Entity.saveWithoutId}
 * 每次从字段重建整份 NBT，第一次存盘后它就没了。导入只有这一次机会。
 *
 * <p>为什么注在 {@code ServerPlayer.readAdditionalSaveData} 末尾：
 * <ul>
 *     <li>两条读档路都经过它：专用服和局域网客人读 {@code playerdata/<uuid>.dat}（{@code PlayerDataStorage.load}），
 *     单人房主读 {@code level.dat} 的 {@code Player} 节点（{@code PlayerList.load}），两边都先跑原版数据修复，
 *     再调 {@code Entity.load}，把完整的玩家 NBT 原样交给这个方法；</li>
 *     <li>{@code Entity.load} 先读附件再调它，到这里附件已经读完，导入不会被随后的读档覆盖；</li>
 *     <li>{@code PlayerEvent.LoadFromFile} 不带 NBT，房主的数据又不在玩家目录里，用不上。</li>
 * </ul>
 *
 * <p>只在 NBT 里没有新键时导入：有新键说明这份档已经在 1.21.1 上存过，以它为准。判断看 NBT 本身，
 * 不看 {@code hasData}：附件读完之后、这里之前要是有别的代码先调了 {@code getData}，{@code hasData}
 * 会为真，旧数据就被跳过，存盘后再也找不回来。
 *
 * <p>导入之后不用另做什么：{@link MasteryData#deserializeNBT} 末尾已标记要全量同步，登录时的特化迁移、
 * 等级重算和段位评定照常进行。
 *
 * <p>目标是原版方法，方法名写 Mojmap（1.21.1 NeoForge 运行期就是 Mojmap，不经重映射）。方法只有一个出口，
 * 命中数是 1；重复导入不会翻倍（第二次读到的是同一份数据），{@code allow} 仍卡在 1，多打上时启动就报出来。
 */
@Mixin(ServerPlayer.class)
public abstract class LegacyMasteryImportMixin {

    /** 1.20.1 Forge 存实体 Capability 的顶层键。 */
    private static final String LEGACY_CAPS_KEY = "ForgeCaps";

    /** NeoForge 存数据附件的顶层键（{@code AttachmentHolder.ATTACHMENTS_NBT_KEY}）。 */
    private static final String ATTACHMENTS_KEY = "neoforge:attachments";

    /**
     * 专精数据在两处的子键。1.20.1 是 Capability 的注册名，1.21.1 是附件的注册名，都是 {@code albionmastery:mastery}。
     */
    private static final String MASTERY_KEY = AlbionMastery.MOD_ID + ":mastery";

    /**
     * 读完玩家存档后，NBT 里只有旧键时把旧数据读进附件。
     *
     * @param tag 完整的玩家 NBT（已经过原版数据修复）
     * @param ci  回调
     */
    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"), allow = 1)
    private void albionmastery$importForgeCaps(CompoundTag tag, CallbackInfo ci) {
        if (!tag.contains(LEGACY_CAPS_KEY, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag caps = tag.getCompound(LEGACY_CAPS_KEY);
        if (!caps.contains(MASTERY_KEY, Tag.TAG_COMPOUND)) {
            return;
        }
        if (tag.getCompound(ATTACHMENTS_KEY).contains(MASTERY_KEY, Tag.TAG_COMPOUND)) {
            return;
        }
        ServerPlayer self = (ServerPlayer) (Object) this;
        MasteryData data = self.getData(MasteryCapability.MASTERY);
        data.deserializeNBT(caps.getCompound(MASTERY_KEY));
        AlbionMastery.LOGGER.info("Imported 1.20.1 mastery data for {}: {} weapons, {} specializations",
                self.getGameProfile().getName(), data.getWeaponsView().size(), data.getSpecializationsView().size());
    }
}
