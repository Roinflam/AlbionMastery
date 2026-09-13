package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.client.ClientMasteryCache;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 增量同步包（S2C）。
 *
 * <p>战斗中经验变化非常频繁（霰弹枪一次开火就是若干发弹丸），所以服务端不会每次变化都发包，
 * 而是把变动的键收集起来，每隔若干 tick 合并成一个增量包发出。
 */
public class S2CUpdateMasteryPacket {

    /** 单个包允许携带的最大条目数。 */
    private static final int MAX_ENTRIES = 4096;

    private final Map<String, S2CSyncMasteryPacket.WeaponSync> weapons;
    private final Map<String, Long> types;
    private final Map<String, Long> overalls;

    /**
     * 构造网络包。
     *
     * @param weapons  变动的武器表
     * @param types    变动的类型表
     * @param overalls 变动的总体表
     */
    public S2CUpdateMasteryPacket(Map<String, S2CSyncMasteryPacket.WeaponSync> weapons,
                                  Map<String, Long> types,
                                  Map<String, Long> overalls) {
        this.weapons = weapons;
        this.types = types;
        this.overalls = overalls;
    }

    /**
     * 序列化。
     *
     * @param buf 目标缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.weapons.size());
        for (Map.Entry<String, S2CSyncMasteryPacket.WeaponSync> entry : this.weapons.entrySet()) {
            buf.writeUtf(entry.getKey(), 256);
            buf.writeVarLong(entry.getValue().xp());
            buf.writeUtf(entry.getValue().typeKey(), 256);
        }
        writeMap(buf, this.types);
        writeMap(buf, this.overalls);
    }

    /**
     * 反序列化。
     *
     * @param buf 源缓冲区
     * @return 网络包
     */
    public static S2CUpdateMasteryPacket decode(FriendlyByteBuf buf) {
        int size = readSize(buf);
        Map<String, S2CSyncMasteryPacket.WeaponSync> weapons = new HashMap<>(Math.max(8, size));
        for (int i = 0; i < size; i++) {
            String key = buf.readUtf(256);
            long xp = buf.readVarLong();
            String typeKey = buf.readUtf(256);
            if (xp >= 0L && WeaponKeys.isValidWeaponKey(key)) {
                weapons.put(key, new S2CSyncMasteryPacket.WeaponSync(xp,
                        WeaponKeys.isValidTypeKey(typeKey) ? typeKey : ""));
            }
        }
        return new S2CUpdateMasteryPacket(weapons, readMap(buf, true), readMap(buf, false));
    }

    /**
     * 主线程处理：写入客户端缓存。
     *
     * @param context 网络上下文
     */
    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientMasteryCache.acceptDelta(this.weapons, this.types, this.overalls)));
        ctx.setPacketHandled(true);
    }

    private static void writeMap(FriendlyByteBuf buf, Map<String, Long> map) {
        buf.writeVarInt(map.size());
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            buf.writeUtf(entry.getKey(), 256);
            buf.writeVarLong(entry.getValue());
        }
    }

    private static Map<String, Long> readMap(FriendlyByteBuf buf, boolean typeKey) {
        int size = readSize(buf);
        Map<String, Long> map = new HashMap<>(Math.max(8, size));
        for (int i = 0; i < size; i++) {
            String key = buf.readUtf(256);
            long xp = buf.readVarLong();
            boolean valid = typeKey ? WeaponKeys.isValidTypeKey(key) : WeaponKeys.isValidOverallKey(key);
            if (xp >= 0L && valid) {
                map.put(key, xp);
            }
        }
        return map;
    }

    private static int readSize(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("AlbionMastery: 增量同步包条目数非法 " + size);
        }
        return size;
    }
}
