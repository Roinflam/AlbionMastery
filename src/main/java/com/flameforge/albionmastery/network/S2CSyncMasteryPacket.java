package com.flameforge.albionmastery.network;

import com.flameforge.albionmastery.client.ClientMasteryCache;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.config.SpecRules;
import com.flameforge.albionmastery.mastery.spec.SpecPoints;
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

    /** 特化选择表最多接受多少条，只用来挡异常包。 */
    private static final int MAX_SPECIALIZATION_ENTRIES = 4096;

    /** 特化表的键最长多少：武器键上限 256，再加第二栏的后缀。 */
    private static final int MAX_SPEC_KEY = 300;

    /** 学习点倍率。 */
    private final double studyMultiplier;

    /** 特化等级、突破、各点数池的退点冷却。 */
    private final SpecProgress progress;

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
                                double studyMultiplier,
                                SpecProgress progress) {
        this.progress = progress;
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
        // 退点冷却发「还剩多少毫秒」而不是服务端的时刻：两边系统时间不一定对得上
        long now = System.currentTimeMillis();
        Map<String, Long> respec = new HashMap<>();
        for (String pool : data.getRespecTimesView().keySet()) {
            long remaining = SpecPoints.respecRemainingMillis(data, pool, snapshot.specRules, now);
            if (remaining > 0L) {
                respec.put(pool, remaining);
            }
        }
        Map<String, Map<String, Integer>> breakthroughs = new HashMap<>();
        data.getBreakthroughsView().forEach((type, perType) -> breakthroughs.put(type, new HashMap<>(perType)));
        return new S2CSyncMasteryPacket(snapshot, weapons,
                toXpMap(data.getTypesView()), toXpMap(data.getOverallsView()),
                new HashMap<>(data.getSpecializationsView()), data.getStudyMultiplier(),
                new SpecProgress(new HashMap<>(data.getSpecLevelsView()), breakthroughs, respec));
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
            buf.writeUtf(entry.getKey(), MAX_SPEC_KEY);
            buf.writeUtf(entry.getValue(), 128);
        }
        buf.writeDouble(this.studyMultiplier);
        this.progress.write(buf);
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

        // 特化选择是按「类型键」存的（type|tacz_gun|rifle 这种），一个来源底下有多少类型就可能有多少条，
        // 所以上限不能按特化枚举的数量封——那样枪械类型一多就会把后面的条目直接截掉
        int specCount = Math.max(0, Math.min(MAX_SPECIALIZATION_ENTRIES, buf.readVarInt()));
        Map<String, String> specializations = new HashMap<>();
        for (int i = 0; i < specCount; i++) {
            String typeKey = buf.readUtf(MAX_SPEC_KEY);
            String specKey = buf.readUtf(128);
            Specialization spec = Specialization.byKey(specKey);
            // 校验和存档读取那边一致：键得是合法类型键，特化得属于这个类型所在的来源。
            // 早先这里误把类型键当来源 ID 去和特化比，永远对不上，
            // 结果服务端切换成功了、客户端却一条都收不到，看起来就是「切不了」
            String baseKey = WeaponKeys.specBaseKey(typeKey);
            if (spec != null && (WeaponKeys.isValidTypeKey(baseKey) || WeaponKeys.isValidWeaponKey(baseKey))
                    && spec.sourceId().equals(WeaponKeys.providerOf(baseKey))) {
                specializations.put(typeKey, specKey);
            }
        }
        double study = buf.readDouble();
        if (Double.isNaN(study)) {
            study = 1.0D;
        }
        study = Math.max(0.0D, Math.min(100.0D, study));
        SpecProgress progress = SpecProgress.read(buf);
        return new S2CSyncMasteryPacket(snapshot, weapons, types, overalls, specializations, study, progress);
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
                        this.specializations, this.studyMultiplier, this.progress)));
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

    /**
     * 特化养成的同步载荷。
     *
     * @param levels          特化键 -&gt; 普通版等级
     * @param breakthroughs   类型键 -&gt; （特化键 -&gt; 突破等级）
     * @param respecRemaining 点数池 -&gt; 退点冷却还剩多少毫秒（没冷却的不在表里）
     */
    public record SpecProgress(Map<String, Integer> levels, Map<String, Map<String, Integer>> breakthroughs,
                               Map<String, Long> respecRemaining) {

        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(this.levels.size());
            this.levels.forEach((key, level) -> {
                buf.writeUtf(key, 128);
                buf.writeVarInt(level);
            });
            int count = 0;
            for (Map<String, Integer> perType : this.breakthroughs.values()) {
                count += perType.size();
            }
            buf.writeVarInt(count);
            this.breakthroughs.forEach((type, perType) -> perType.forEach((key, star) -> {
                buf.writeUtf(type, 256);
                buf.writeUtf(key, 128);
                buf.writeVarInt(star);
            }));
            buf.writeVarInt(this.respecRemaining.size());
            this.respecRemaining.forEach((pool, remaining) -> {
                buf.writeUtf(pool, 256);
                buf.writeVarLong(remaining);
            });
        }

        static SpecProgress read(FriendlyByteBuf buf) {
            int levelCount = readSize(buf);
            Map<String, Integer> levels = new HashMap<>();
            for (int i = 0; i < levelCount; i++) {
                String key = buf.readUtf(128);
                int level = Math.max(0, Math.min(SpecRules.MAX_LEVELS, buf.readVarInt()));
                if (level > 0 && Specialization.byKey(key) != null) {
                    levels.put(key, level);
                }
            }
            int breakCount = readSize(buf);
            Map<String, Map<String, Integer>> breakthroughs = new HashMap<>();
            for (int i = 0; i < breakCount; i++) {
                String type = buf.readUtf(256);
                String key = buf.readUtf(128);
                int star = Math.max(0, Math.min(SpecRules.MAX_LEVELS, buf.readVarInt()));
                if (star > 0 && WeaponKeys.isValidTypeKey(type) && Specialization.byKey(key) != null) {
                    breakthroughs.computeIfAbsent(type, t -> new HashMap<>()).put(key, star);
                }
            }
            int respecCount = readSize(buf);
            Map<String, Long> respec = new HashMap<>();
            for (int i = 0; i < respecCount; i++) {
                String pool = buf.readUtf(256);
                long remaining = buf.readVarLong();
                if (remaining > 0L) {
                    respec.put(pool, remaining);
                }
            }
            return new SpecProgress(levels, breakthroughs, respec);
        }
    }
}
