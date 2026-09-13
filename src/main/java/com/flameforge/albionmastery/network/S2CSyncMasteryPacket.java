package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.client.ClientMasteryCache;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.spec.Specialization;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 全量同步包（S2C）。
 *
 * <p>玩家登录、重生、切维度时下发，内容包括服务端生效中的数值配置快照，
 * 以及该玩家三层专精（总体 / 类型 / 单件武器）的全部经验。
 */
public class S2CSyncMasteryPacket {

    /** 单个包允许携带的最大条目数，防止异常数据撑爆客户端内存。 */
    private static final int MAX_ENTRIES = 20000;

    private final MasteryConfig.Snapshot snapshot;

    /** 武器键 -> {经验, 类型键}。 */
    private final Map<String, WeaponSync> weapons;

    /** 类型键 -> 经验。 */
    private final Map<String, Long> types;

    /** 总体键 -> 经验。 */
    private final Map<String, Long> overalls;

    /** 来源 ID -> 已选特化的完整键。 */
    private final Map<String, String> specializations;

    /** 学习点倍率。 */
    private final double studyMultiplier;

    /**
     * 构造网络包。
     *
     * @param snapshot 配置快照
     * @param weapons  武器经验表
     * @param types    类型经验表
     * @param overalls        总体经验表
     * @param specializations 特化选择表
     * @param studyMultiplier 学习点倍率
     */
    public S2CSyncMasteryPacket(MasteryConfig.Snapshot snapshot,
                                Map<String, WeaponSync> weapons,
                                Map<String, Long> types,
                                Map<String, Long> overalls,
                                Map<String, String> specializations,
                                double studyMultiplier) {
        this.snapshot = snapshot;
        this.weapons = weapons;
        this.types = types;
        this.overalls = overalls;
        this.specializations = specializations;
        this.studyMultiplier = studyMultiplier;
    }

    /**
     * 由玩家数据构造一个全量同步包。
     *
     * @param data     玩家专精数据
     * @param snapshot 配置快照
     * @return 网络包
     */
    public static S2CSyncMasteryPacket of(MasteryData data, MasteryConfig.Snapshot snapshot) {
        Map<String, WeaponSync> weapons = new HashMap<>();
        for (Map.Entry<String, MasteryData.WeaponRecord> entry : data.getWeaponsView().entrySet()) {
            MasteryData.WeaponRecord record = entry.getValue();
            if (record.xp > 0L) {
                weapons.put(entry.getKey(), new WeaponSync(record.xp,
                        record.typeKey == null ? "" : record.typeKey));
            }
        }
        return new S2CSyncMasteryPacket(snapshot, weapons,
                toXpMap(data.getTypesView()), toXpMap(data.getOverallsView()),
                new HashMap<>(data.getSpecializationsView()), data.getStudyMultiplier());
    }

    private static Map<String, Long> toXpMap(Map<String, MasteryData.Entry> source) {
        Map<String, Long> map = new HashMap<>();
        for (Map.Entry<String, MasteryData.Entry> entry : source.entrySet()) {
            if (entry.getValue().xp > 0L) {
                map.put(entry.getKey(), entry.getValue().xp);
            }
        }
        return map;
    }

    /**
     * 序列化。
     *
     * @param buf 目标缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        this.snapshot.write(buf);
        buf.writeVarInt(this.weapons.size());
        for (Map.Entry<String, WeaponSync> entry : this.weapons.entrySet()) {
            buf.writeUtf(entry.getKey(), 256);
            buf.writeVarLong(entry.getValue().xp());
            buf.writeUtf(entry.getValue().typeKey(), 256);
        }
        writeMap(buf, this.types);
        writeMap(buf, this.overalls);
        buf.writeVarInt(this.specializations.size());
        for (Map.Entry<String, String> entry : this.specializations.entrySet()) {
            buf.writeUtf(entry.getKey(), 128);
            buf.writeUtf(entry.getValue(), 128);
        }
        buf.writeDouble(this.studyMultiplier);
    }

    /**
     * 反序列化。
     *
     * @param buf 源缓冲区
     * @return 网络包
     */
    public static S2CSyncMasteryPacket decode(FriendlyByteBuf buf) {
        MasteryConfig.Snapshot snapshot = MasteryConfig.Snapshot.read(buf);

        int size = readSize(buf);
        Map<String, WeaponSync> weapons = new HashMap<>(Math.max(16, size));
        for (int i = 0; i < size; i++) {
            String key = buf.readUtf(256);
            long xp = buf.readVarLong();
            String typeKey = buf.readUtf(256);
            if (xp > 0L && WeaponKeys.isValidWeaponKey(key)) {
                weapons.put(key, new WeaponSync(xp, WeaponKeys.isValidTypeKey(typeKey) ? typeKey : ""));
            }
        }
        Map<String, Long> types = readMap(buf, true);
        Map<String, Long> overalls = readMap(buf, false);

        // 条数按枚举总量封顶：正常情况下最多每个来源一条，异常包不至于让客户端撑爆
        int specCount = Math.max(0, Math.min(Specialization.values().length, buf.readVarInt()));
        Map<String, String> specializations = new HashMap<>();
        for (int i = 0; i < specCount; i++) {
            String sourceId = buf.readUtf(128);
            String specKey = buf.readUtf(128);
            Specialization spec = Specialization.byKey(specKey);
            if (spec != null && spec.sourceId().equals(sourceId)) {
                specializations.put(sourceId, specKey);
            }
        }
        double study = buf.readDouble();
        if (Double.isNaN(study)) {
            study = 1.0D;
        }
        study = Math.max(0.0D, Math.min(100.0D, study));
        return new S2CSyncMasteryPacket(snapshot, weapons, types, overalls, specializations, study);
    }

    /**
     * 主线程处理：写入客户端缓存。
     *
     * @param context 网络上下文
     */
    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientMasteryCache.acceptFullSync(
                        this.snapshot, this.weapons, this.types, this.overalls,
                        this.specializations, this.studyMultiplier)));
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
            if (xp > 0L && valid) {
                map.put(key, xp);
            }
        }
        return map;
    }

    private static int readSize(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("AlbionMastery: 同步包条目数非法 " + size);
        }
        return size;
    }

    /**
     * 单件武器的同步载荷。
     *
     * @param xp      总经验
     * @param typeKey 所属类型键，空串表示未知
     */
    public record WeaponSync(long xp, String typeKey) {
    }
}
