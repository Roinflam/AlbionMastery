package com.flameforge.albionmastery.mastery;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 玩家专精数据的 Capability 定义。
 *
 * <p>数据挂在 Player 实体上，跟随存档持久化；死亡重生时由
 * {@code PlayerEvent.Clone} 负责继承。
 */
public final class MasteryCapability {

    /** Capability 实例，由 {@code RegisterCapabilitiesEvent} 注册后可用。 */
    public static final Capability<MasteryData> MASTERY = CapabilityManager.get(new CapabilityToken<>() {
    });

    private MasteryCapability() {
    }

    /**
     * 挂载到玩家实体上的 Capability 提供者。
     */
    public static class Provider implements ICapabilitySerializable<CompoundTag> {

        /** 实际的数据对象。 */
        private final MasteryData data = new MasteryData();

        /** 惰性包装，避免每次查询都新建对象。 */
        private final LazyOptional<MasteryData> optional = LazyOptional.of(() -> this.data);

        @Nonnull
        @Override
        public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
            return cap == MASTERY ? this.optional.cast() : LazyOptional.empty();
        }

        @Override
        public CompoundTag serializeNBT() {
            return this.data.serializeNBT();
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            this.data.deserializeNBT(nbt);
        }

        /**
         * 使 LazyOptional 失效，实体被移除时调用，防止持有过期引用。
         */
        public void invalidate() {
            this.optional.invalidate();
        }
    }
}
