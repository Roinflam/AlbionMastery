package com.flameforge.albionmastery.mastery;

import com.flameforge.albionmastery.AlbionMastery;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * 玩家专精数据挂在玩家实体上的方式。
 *
 * <p>1.21.1 没有实体 Capability，改用 NeoForge 的数据附件；类名沿用 1.20.1，两个分支里取数据的入口
 * 都是 {@code MasteryHelper.get(Player)}。
 *
 * <ul>
 *     <li>存档位置：{@code neoforge:attachments} 下的 {@code albionmastery:mastery}，内容是
 *     {@link MasteryData#serializeNBT()} 的根，和 1.20.1 {@code ForgeCaps} 下同名键里的一字不差；</li>
 *     <li>第一次 {@code getData} 时自动建一份空数据；</li>
 *     <li>死亡重生、出末地时由 NeoForge 整份复制（{@code copyOnDeath}），复制走 {@link MasteryData#copyFrom}：
 *     默认的「写出再读回」会丢掉不进存档的瞬态标记，而且读回不重算等级，到下一次全量同步之前等级全是 0。</li>
 * </ul>
 */
public final class MasteryCapability {

    /** 附件注册表。 */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, AlbionMastery.MOD_ID);

    /** 玩家专精数据。注册名 {@code mastery}，也就是存档里的键 {@code albionmastery:mastery}。 */
    public static final Supplier<AttachmentType<MasteryData>> MASTERY = ATTACHMENTS.register("mastery",
            () -> AttachmentType.builder(MasteryData::new)
                    // 不用 serializable(...)：它要 MasteryData 实现带 HolderLookup.Provider 参数的 INBTSerializable，
                    // 转接一层让 MasteryData 和 1.20.1 保持一样
                    .serialize(new IAttachmentSerializer<CompoundTag, MasteryData>() {
                        @Override
                        public MasteryData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
                            MasteryData data = new MasteryData();
                            data.deserializeNBT(tag);
                            return data;
                        }

                        @Override
                        public CompoundTag write(MasteryData data, HolderLookup.Provider provider) {
                            return data.serializeNBT();
                        }
                    })
                    // 这两项必须写在 serialize 之后，否则构建时抛 IllegalStateException
                    .copyOnDeath()
                    .copyHandler((data, holder, provider) -> {
                        MasteryData copy = new MasteryData();
                        copy.copyFrom(data);
                        return copy;
                    })
                    .build());

    private MasteryCapability() {
    }

    /**
     * 把附件注册表挂到本模组的事件总线上。模组构造时调用。
     *
     * @param modBus 本模组的事件总线
     */
    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }
}
