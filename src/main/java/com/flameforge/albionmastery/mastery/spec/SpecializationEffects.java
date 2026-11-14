package com.flameforge.albionmastery.mastery.spec;

import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecRules;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryData;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.rank.MasteryRank;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import javax.annotation.Nullable;

/**
 * 查询某个玩家当前生效的特化数值。
 *
 * <p><b>两端的数据不在同一个地方，这点是这个类存在的全部理由。</b>
 * 服务端的专精数据挂在玩家的 Capability 上；客户端的 Capability 是<b>空的</b>——
 * 服务端从来没往那里同步过，客户端那份数据在 {@code ClientMasteryCache} 里。
 * 早先这里两端都去读 Capability，结果就是客户端永远查不到特化：
 * 换弹动画、攻速预判全都按原始数值跑，服务端却按特化后的数值算，看起来就像「特化没生效」。
 *
 * <p>所以这里按端分流：逻辑端走 Capability，客户端走同步缓存。
 * 客户端那条路径只在 {@code FMLEnvironment.dist} 是客户端时才走到，专用服务端不会加载到任何客户端类。
 */
public final class SpecializationEffects {

    private SpecializationEffects() {
    }

    /**
     * 取某个玩家手上这件武器当前生效的数值。
     *
     * <p>这个方法会被 Mixin 在两端同时调用，端的判断在里面做掉，调用方不用操心。
     *
     * @param entity 实体，不是玩家或为空时返回中性值
     * @param stack  武器物品
     * @return 生效数值，永不为 null
     */
    public static SpecializationValues forStack(@Nullable LivingEntity entity,
                                                @Nullable ItemStack stack) {
        if (!(entity instanceof Player player) || stack == null || stack.isEmpty()) {
            return SpecializationValues.NEUTRAL;
        }
        boolean client = player.level().isClientSide();
        if (client && !isLocalPlayer(player)) {
            return SpecializationValues.NEUTRAL;
        }
        MasteryConfig.Snapshot snapshot = client ? clientSnapshot() : MasteryConfig.serverSnapshot();
        ResolvedWeapon weapon = WeaponResolver.resolve(stack, snapshot.splitMeleeByPack);
        if (weapon == null) {
            return SpecializationValues.NEUTRAL;
        }
        MasteryData data = client ? clientData() : MasteryHelper.get(player);
        return forData(data, weapon, snapshot);
    }

    /**
     * 这个实体的特化数值在本端算得出来吗。
     *
     * <p>服务端对谁都算得出来；客户端只有自己的专精数据，别人的一律算不出（返回中性值）。
     * 需要「本机算不出来就退回同步属性」的地方靠它分流。
     *
     * @param entity 实体
     * @return 算得出来返回 true
     */
    public static boolean hasLocalData(@Nullable LivingEntity entity) {
        return entity instanceof Player player
                && (!player.level().isClientSide() || isLocalPlayer(player));
    }

    /**
     * 按已经解析好的武器取当前生效的数值，两端通用。
     *
     * <p>法术不是物品：施法、冷却、法术等级这些事件里只有法术本身，没有物品堆可解析，
     * 走这个入口。取数据的方式和 {@link #forStack} 一样，客户端读缓存、服务端读 Capability。
     *
     * @param entity 玩家
     * @param weapon 武器
     * @return 数值；不是玩家或没数据时是中性值
     */
    public static SpecializationValues forWeapon(@Nullable LivingEntity entity,
                                                 @Nullable ResolvedWeapon weapon) {
        if (!(entity instanceof Player player) || weapon == null) {
            return SpecializationValues.NEUTRAL;
        }
        boolean client = player.level().isClientSide();
        if (client && !isLocalPlayer(player)) {
            return SpecializationValues.NEUTRAL;
        }
        MasteryConfig.Snapshot snapshot = client ? clientSnapshot() : MasteryConfig.serverSnapshot();
        MasteryData data = client ? clientData() : MasteryHelper.get(player);
        return forData(data, weapon, snapshot);
    }

    /**
     * 某件武器此刻用的是哪一份配置（它自己的独立配置，或所属类型的通用配置），两端通用。
     * 一个法术里好几个符文属于同一份配置时，靠它去重。
     *
     * @param entity 玩家
     * @param weapon 武器
     * @return 配置键；拿不到数据时返回类型键
     */
    public static String configKeyFor(@Nullable LivingEntity entity, ResolvedWeapon weapon) {
        if (!(entity instanceof Player player)) {
            return weapon.typeKey();
        }
        boolean client = player.level().isClientSide();
        MasteryData data = client ? (isLocalPlayer(player) ? clientData() : null) : MasteryHelper.get(player);
        return data == null ? weapon.typeKey() : data.specConfigKey(weapon.typeKey(), weapon.weaponKey());
    }

    /**
     * 按已经解析好的武器算专精加成明细，两端通用。
     *
     * @param entity 玩家
     * @param weapon 武器
     * @return 加成明细；不是玩家或没数据时返回 null
     */
    @Nullable
    public static MasteryHelper.Bonus bonusFor(@Nullable LivingEntity entity,
                                               @Nullable ResolvedWeapon weapon) {
        if (!(entity instanceof Player player) || weapon == null) {
            return null;
        }
        boolean client = player.level().isClientSide();
        if (client && !isLocalPlayer(player)) {
            return null;
        }
        MasteryConfig.Snapshot snapshot = client ? clientSnapshot() : MasteryConfig.serverSnapshot();
        MasteryData data = client ? clientData() : MasteryHelper.get(player);
        if (data == null) {
            return null;
        }
        return MasteryHelper.computeBonus(data, weapon, snapshot);
    }

    /**
     * 客户端上这个玩家是不是本机玩家。
     *
     * <p>客户端缓存里只有本机玩家自己的数据。别的玩家在你屏幕上开枪、拉栓、开镜时，
     * TACZ 同样会走进那些被特化改过的方法；这时拿你的特化去套他，他的动画、射速预测就全乱了，
     * 所以一律按中性值处理——他真实的效果在服务端算。
     *
     * @param player 玩家
     * @return 是本机玩家返回 true
     */
    private static boolean isLocalPlayer(Player player) {
        // 客户端类只出现在 dist 判断保护的那一支里，专用服务端走不到，也就不会去加载它
        return FMLEnvironment.dist == Dist.CLIENT
                && com.flameforge.albionmastery.client.ClientMasteryCache.isLocalPlayer(player);
    }

    /**
     * 从客户端同步缓存取数据。
     *
     * @return 客户端数据；不在客户端时返回 null
     */
    @Nullable
    private static MasteryData clientData() {
        return FMLEnvironment.dist == Dist.CLIENT
                ? com.flameforge.albionmastery.client.ClientMasteryCache.data()
                : null;
    }

    /**
     * 从客户端同步缓存取配置快照。
     *
     * @return 服务端下发的快照；拿不到时退回默认值
     */
    private static MasteryConfig.Snapshot clientSnapshot() {
        MasteryConfig.Snapshot snapshot = FMLEnvironment.dist == Dist.CLIENT
                ? com.flameforge.albionmastery.client.ClientMasteryCache.snapshot()
                : null;
        return snapshot == null ? MasteryConfig.Snapshot.createDefault() : snapshot;
    }

    /**
     * 按端取配置快照：客户端用服务端下发的那一份，服务端用自己的。
     *
     * <p>凡是「显示的价格」和「实际扣的价格」必须一致的地方都要走这个方法：
     * COMMON 配置在专用服务端和客户端上是两份独立文件，直接读本端配置，
     * 轮盘上写的法力和真正扣的法力就可能是两个数。
     *
     * @param player 玩家
     * @return 这一端该用的快照
     */
    public static MasteryConfig.Snapshot snapshotFor(Player player) {
        return player.level().isClientSide() ? clientSnapshot() : MasteryConfig.serverSnapshot();
    }

    /**
     * 取某个<b>武器类型</b>上生效的数值。
     *
     * <p>特化是按类型选的，不是按来源：枪械底下的步枪和霰弹枪玩法差得远，
     * 让它们共用一个特化没有意义。所以这里的键是类型键。
     *
     * @param data     专精数据，可为 null
     * @param typeKey  类型键
     * @param snapshot 配置快照
     * @return 生效数值，永不为 null
     */
    public static SpecializationValues forData(@Nullable MasteryData data, String typeKey,
                                               MasteryConfig.Snapshot snapshot) {
        return forData(data, typeKey, null, snapshot);
    }

    /**
     * 按武器算当前生效的数值（门槛看武器所属的类型和来源）。
     *
     * @param data     玩家数据
     * @param weapon   武器
     * @param snapshot 配置快照
     * @return 各槽叠加后的数值
     */
    public static SpecializationValues forData(@Nullable MasteryData data, ResolvedWeapon weapon,
                                               MasteryConfig.Snapshot snapshot) {
        return forData(data, weapon.typeKey(), weapon.weaponKey(), snapshot);
    }

    /**
     * 各槽各看门槛，过了的叠在一起。
     *
     * <p>先看这件武器自己过没过槽 1 的武器门槛（武器练到门槛、或者类型已经练透），没过就一个槽都不生效；
     * {@code weaponKey} 为 null 时（只问类型）按「类型里有没有哪件武器过了门槛」算。
     * 过了之后每个槽再看自己的条件，槽里装的特化按它在这个槽里的等级取值（普通槽用 Lv、突破槽用 ★）。
     *
     * @param data      玩家数据
     * @param typeKey   类型键
     * @param weaponKey 武器键，可为 null
     * @param snapshot  配置快照
     * @return 叠加后的数值
     */
    public static SpecializationValues forData(@Nullable MasteryData data, String typeKey,
                                               @Nullable String weaponKey,
                                               MasteryConfig.Snapshot snapshot) {
        if (data == null || !snapshot.enableSpecialization) {
            return SpecializationValues.NEUTRAL;
        }
        if (weaponKey != null ? !weaponPassesGate(data, typeKey, weaponKey, snapshot)
                : !anyWeaponPassesGate(data, typeKey, snapshot)) {
            return SpecializationValues.NEUTRAL;
        }
        SpecRules rules = snapshot.specRules;
        String configKey = data.specConfigKey(typeKey, weaponKey);
        String providerId = WeaponKeys.providerOf(typeKey);
        SpecializationValues result = null;
        Specialization[] applied = new Specialization[rules.slotCount];
        int appliedCount = 0;
        for (int slot = 1; slot <= rules.slotCount; slot++) {
            if (!slotConditionMet(data, typeKey, slot, snapshot)) {
                continue;
            }
            Specialization spec = configuredSpec(data, configKey, providerId, slot);
            if (spec == null || clashes(spec, applied, appliedCount)) {
                // 同系的只吃前面那个槽：新规则之前存下来的「扩容弹匣 + 弹鼓」这类组合，
                // 后面的槽照样留在存档里，但不生效，界面上会标出来
                continue;
            }
            boolean breakthrough = usesBreakthrough(data, typeKey, spec, slot, rules);
            int level = breakthrough ? data.getBreakthrough(typeKey, spec.key()) : data.getSpecLevel(spec.key());
            if (level <= 0) {
                continue;
            }
            SpecializationValues values = snapshot.specValues(spec, breakthrough, level);
            result = result == null ? values : SpecializationValues.combine(result, values);
            applied[appliedCount++] = spec;
        }
        return result == null ? SpecializationValues.NEUTRAL : result;
    }

    /**
     * 这个特化在这个槽里按哪一版取值：突破槽用突破版；只开一个槽时，突破过就用突破版，没突破用普通版。
     *
     * @param data    玩家数据
     * @param typeKey 类型键
     * @param spec    特化
     * @param slot    槽位
     * @param rules   规则
     * @return 用突破版返回 true
     */
    public static boolean usesBreakthrough(MasteryData data, String typeKey, Specialization spec, int slot,
                                           SpecRules rules) {
        if (rules.singleSlot()) {
            return data.getBreakthrough(typeKey, spec.key()) > 0;
        }
        return rules.isBreakthroughSlot(slot);
    }

    /**
     * 这个特化在这个槽里的等级（按 {@link #usesBreakthrough} 取 Lv 或 ★）。
     *
     * @return 等级，0 = 这个槽里用不了
     */
    public static int levelInSlot(MasteryData data, String typeKey, Specialization spec, int slot,
                                  SpecRules rules) {
        return usesBreakthrough(data, typeKey, spec, slot, rules)
                ? data.getBreakthrough(typeKey, spec.key()) : data.getSpecLevel(spec.key());
    }

    private static boolean clashes(Specialization spec, Specialization[] applied, int count) {
        for (int i = 0; i < count; i++) {
            if (applied[i] == spec || spec.conflictsWith(applied[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某份配置的某个槽里装着的特化（只认属于这个来源、不是制式的）。
     */
    @Nullable
    private static Specialization configuredSpec(MasteryData data, String configKey, @Nullable String providerId,
                                                 int slot) {
        Specialization spec = Specialization.byKey(data.getSpecialization(configKey, slot));
        // 特化归属的来源必须和这个类型键的来源对得上，
        // 否则就是存档里留下的脏数据（比如类型被重新分过组）
        if (spec == null || spec.isStandard() || !spec.sourceId().equals(providerId)) {
            return null;
        }
        return spec;
    }

    /**
     * 某个槽上此刻真正生效的特化：武器过了门槛、槽开了、选的不是制式、属于这个来源、等级至少 1。
     *
     * <p>不管同系冲突，冲突在 {@link #forData(MasteryData, String, String, MasteryConfig.Snapshot)} 里按槽位顺序处理。
     *
     * @param data      玩家数据
     * @param typeKey   类型键
     * @param weaponKey 武器键，可为 null
     * @param slot      槽位
     * @param snapshot  配置快照
     * @return 特化；这个槽不生效时返回 null
     */
    @Nullable
    public static Specialization activeSpec(MasteryData data, String typeKey, @Nullable String weaponKey,
                                            int slot, MasteryConfig.Snapshot snapshot) {
        if (!isSlotActive(data, typeKey, weaponKey, slot, snapshot)) {
            return null;
        }
        // 外圈的独立配置优先，没有才用中圈的通用配置
        Specialization spec = configuredSpec(data, data.specConfigKey(typeKey, weaponKey),
                WeaponKeys.providerOf(typeKey), slot);
        if (spec == null || levelInSlot(data, typeKey, spec, slot, snapshot.specRules) <= 0) {
            return null;
        }
        return spec;
    }

    /**
     * 某个槽位对某件武器此刻能不能生效：武器过了槽 1 的门槛，并且这个槽自己的条件达到了。
     *
     * @param data      玩家数据
     * @param typeKey   类型键
     * @param weaponKey 武器键，可为 null（只看类型：类型里有哪件武器过了门槛就算）
     * @param slot      槽位
     * @param snapshot  配置快照
     * @return 能生效返回 true
     */
    public static boolean isSlotActive(MasteryData data, String typeKey, @Nullable String weaponKey,
                                       int slot, MasteryConfig.Snapshot snapshot) {
        return isSlotUnlocked(data, typeKey, weaponKey, slot, snapshot);
    }

    /**
     * 某个槽位开没开（能不能往里装）：只看类型的通用配置时，类型里有哪件武器过了门槛就算开了。
     *
     * @param data     玩家数据
     * @param typeKey  类型键
     * @param slot     槽位
     * @param snapshot 配置快照
     * @return 开了返回 true
     */
    public static boolean isSlotUnlocked(@Nullable MasteryData data, String typeKey, int slot,
                                         MasteryConfig.Snapshot snapshot) {
        return isSlotUnlocked(data, typeKey, null, slot, snapshot);
    }

    /**
     * 某个槽位对这份配置开没开：武器门槛 + 槽自己的条件。
     *
     * @param data      玩家数据
     * @param typeKey   类型键
     * @param weaponKey 武器键；null 表示类型的通用配置
     * @param slot      槽位
     * @param snapshot  配置快照
     * @return 开了返回 true
     */
    public static boolean isSlotUnlocked(@Nullable MasteryData data, String typeKey, @Nullable String weaponKey,
                                         int slot, MasteryConfig.Snapshot snapshot) {
        if (data == null || !snapshot.enableSpecialization || WeaponKeys.providerOf(typeKey) == null) {
            return false;
        }
        if (!slotConditionMet(data, typeKey, slot, snapshot)) {
            return false;
        }
        return weaponKey != null ? weaponPassesGate(data, typeKey, weaponKey, snapshot)
                : anyWeaponPassesGate(data, typeKey, snapshot);
    }

    /** 槽自己的条件看哪一圈。 */
    public enum SlotNeed {
        /** 没有额外条件（槽 1，只看武器门槛）。 */
        NONE,
        /** 看类型（中圈）等级。 */
        TYPE,
        /** 看来源（内圈）等级。 */
        SOURCE
    }

    /**
     * @param slot  槽位
     * @return 这个槽自己的条件看哪一圈
     */
    public static SlotNeed slotNeed(int slot) {
        return switch (slot) {
            case 2, 4 -> SlotNeed.TYPE;
            case 3 -> SlotNeed.SOURCE;
            default -> SlotNeed.NONE;
        };
    }

    /**
     * @param slot     槽位
     * @param snapshot 配置快照
     * @return 这个槽自己的条件要几级；0 = 不要求
     */
    public static int slotNeedLevel(int slot, MasteryConfig.Snapshot snapshot) {
        SpecRules rules = snapshot.specRules;
        int percent = switch (slot) {
            case 2 -> rules.slot2TypePercent;
            case 3 -> rules.slot3SourcePercent;
            case 4 -> rules.slot4TypePercent;
            default -> 0;
        };
        return SpecRules.levelOf(percent, snapshot.maxLevel);
    }

    /**
     * 槽自己的条件（不含武器门槛）：槽 2、4 看类型等级，槽 3 看来源等级，槽 1 没有。超出槽数的一律不开。
     *
     * @param data     玩家数据
     * @param typeKey  类型键
     * @param slot     槽位
     * @param snapshot 配置快照
     * @return 达到了返回 true
     */
    public static boolean slotConditionMet(MasteryData data, String typeKey, int slot,
                                           MasteryConfig.Snapshot snapshot) {
        if (slot < 1 || slot > snapshot.specRules.slotCount) {
            return false;
        }
        int need = slotNeedLevel(slot, snapshot);
        return switch (slotNeed(slot)) {
            case TYPE -> data.getTypeLevel(typeKey) >= need;
            case SOURCE -> {
                String providerId = WeaponKeys.providerOf(typeKey);
                yield providerId != null && data.getOverallLevel(WeaponKeys.overallKey(providerId)) >= need;
            }
            case NONE -> true;
        };
    }

    /**
     * 这个类型是不是已经练透了：类型下所有武器跳过槽 1 的武器门槛。
     *
     * @param data     玩家数据
     * @param typeKey  类型键
     * @param snapshot 配置快照
     * @return 豁免返回 true
     */
    public static boolean typeBypassesGate(MasteryData data, String typeKey, MasteryConfig.Snapshot snapshot) {
        int percent = snapshot.specRules.typeBypassPercent;
        return percent > 0 && data.getTypeLevel(typeKey) >= SpecRules.levelOf(percent, snapshot.maxLevel);
    }

    /**
     * 玩家现在的段位，按缓存的武器等级之和算（不遍历武器表）。
     *
     * @param data     玩家数据
     * @param snapshot 配置快照
     * @return 段位
     */
    public static int rankOf(MasteryData data, MasteryConfig.Snapshot snapshot) {
        return MasteryRank.rankFor(MasteryRank.rankXpForLevelSum(data.getTotalWeaponLevels(),
                snapshot.rankXpPerWeaponLevel));
    }

    /**
     * 段位实际给这个类型的武器减掉几级门槛：段位够了、并且这个类型里已经有足够的满级武器。
     *
     * @param data     玩家数据
     * @param typeKey  类型键
     * @param snapshot 配置快照
     * @return 减几级
     */
    public static int rankReduction(MasteryData data, String typeKey, MasteryConfig.Snapshot snapshot) {
        SpecRules rules = snapshot.specRules;
        if (rules.rankReductionPercentPerStep <= 0
                || data.getMaxedWeaponCount(typeKey) < rules.rankReductionMaxedWeaponsInType) {
            return 0;
        }
        return rules.rankReductionLevels(rankOf(data, snapshot), snapshot.maxLevel);
    }

    /**
     * 不算段位减免的槽 1 武器门槛。
     *
     * @param snapshot 配置快照
     * @return 等级
     */
    public static int baseWeaponGate(MasteryConfig.Snapshot snapshot) {
        return SpecRules.levelOf(snapshot.specRules.slot1WeaponPercent, snapshot.maxLevel);
    }

    /**
     * 这个类型里的武器要练到几级才吃特化（已经减掉段位减免）。
     *
     * @param data     玩家数据
     * @param typeKey  类型键
     * @param snapshot 配置快照
     * @return 等级，至少 1
     */
    public static int weaponGate(MasteryData data, String typeKey, MasteryConfig.Snapshot snapshot) {
        return Math.max(1, baseWeaponGate(snapshot) - rankReduction(data, typeKey, snapshot));
    }

    /**
     * 这件武器过没过槽 1 的门槛。开火、命中每次都会走到，先查便宜的条件，段位最后才算。
     *
     * @param data      玩家数据
     * @param typeKey   类型键
     * @param weaponKey 武器键
     * @param snapshot  配置快照
     * @return 过了返回 true
     */
    public static boolean weaponPassesGate(MasteryData data, String typeKey, String weaponKey,
                                           MasteryConfig.Snapshot snapshot) {
        int level = data.getWeaponLevel(weaponKey);
        if (level >= baseWeaponGate(snapshot) || typeBypassesGate(data, typeKey, snapshot)) {
            return true;
        }
        return level > 0 && level >= weaponGate(data, typeKey, snapshot);
    }

    /**
     * 这个类型里有没有哪件武器过了槽 1 的门槛（类型的通用配置能不能装、界面上槽开没开）。
     * 要遍历武器表，不要放到热路径上。
     *
     * @param data     玩家数据
     * @param typeKey  类型键
     * @param snapshot 配置快照
     * @return 有返回 true
     */
    public static boolean anyWeaponPassesGate(MasteryData data, String typeKey, MasteryConfig.Snapshot snapshot) {
        if (typeBypassesGate(data, typeKey, snapshot) || data.getMaxedWeaponCount(typeKey) > 0) {
            return true;
        }
        int gate = weaponGate(data, typeKey, snapshot);
        for (MasteryData.WeaponRecord record : data.getWeaponsView().values()) {
            if (typeKey.equals(record.typeKey) && record.level >= gate) {
                return true;
            }
        }
        return false;
    }

    /**
     * 目标血量超过这个比例就算「接近满血」。
     *
     * <p>不用严格的 100%：怪物身上挂个中毒、掉一格血就不算满血了，那条件几乎永远不成立。
     */
    private static final double FRESH_RATIO = 0.95D;

    /**
     * 把所有「看情况」的倍率算成一个数。
     *
     * <p>这些条件放在一起算，是为了三个来源的战斗处理器只调一次就够，不用各写一遍，
     * 也不会出现「枪械支持残血斩杀、近战忘了加」这种漏。用不到的维度是中性值，乘上去等于没乘。
     *
     * @param values   当前生效的特化数值
     * @param attacker 攻击者，可为 null
     * @param target   目标，可为 null
     * @param headShot 是否爆头；不适用（近战、拔刀剑）时传 null
     * @return 综合倍率，永远是正数
     */
    public static double conditionalMultiplier(SpecializationValues values,
                                               @Nullable LivingEntity attacker,
                                               @Nullable LivingEntity target,
                                               @Nullable Boolean headShot) {
        double multiplier = 1.0D;

        if (target != null) {
            multiplier *= targetMultiplier(values, attacker, target);
        }

        if (headShot != null) {
            multiplier *= headShot ? values.headShotDamage() : values.bodyShotDamage();
        }

        multiplier *= selfMultiplier(values, attacker);
        return Math.max(0.0D, multiplier);
    }

    /**
     * 只看目标的那几层：对人 / 对怪、有没有甲、残血 / 满血、是不是从背后打的。
     *
     * <p>单独拿出来是给拔刀剑用的：它的刀身斩击在「挥出去之前」的事件里结算，那时还不知道砍中谁，
     * 只能先乘和目标无关的部分，等真正命中时再补这一层。
     *
     * @param values   当前生效的特化数值
     * @param attacker 攻击者，可为 null（为 null 时不算背刺）
     * @param target   目标
     * @return 倍率
     */
    public static double targetMultiplier(SpecializationValues values, @Nullable LivingEntity attacker,
                                          LivingEntity target) {
        double multiplier = target instanceof Player ? values.vsPlayerDamage() : values.vsMobDamage();
        // 有没有护甲，直接看护甲值。护甲为 0 的目标吃不到「贯甲」的好处，
        // 这正是那个特化的代价，不是漏算
        multiplier *= target.getArmorValue() > 0.0F
                ? values.armoredDamage() : values.unarmoredDamage();

        double ratio = healthRatio(target);
        if (ratio <= values.executeThreshold()) {
            multiplier *= values.executeDamage();
        } else if (ratio >= FRESH_RATIO) {
            multiplier *= values.freshDamage();
        }

        if (attacker != null && values.backstabDamage() != 1.0D && isBehind(attacker, target)) {
            multiplier *= values.backstabDamage();
        }
        if (attacker != null && (values.nearDamage() != 1.0D || values.farDamage() != 1.0D)) {
            // 按攻击者和目标的直线距离分三档：近（霰弹、近身）吃 near，远（狙击、远射）吃 far，中间不变
            double distanceSq = attacker.distanceToSqr(target);
            if (distanceSq <= NEAR_DISTANCE * NEAR_DISTANCE) {
                multiplier *= values.nearDamage();
            } else if (distanceSq >= FAR_DISTANCE * FAR_DISTANCE) {
                multiplier *= values.farDamage();
            }
        }
        return Math.max(0.0D, multiplier);
    }

    /** 「近距离」的范围（格）。 */
    public static final double NEAR_DISTANCE = 6.0D;

    /** 「远距离」的起点（格）。 */
    public static final double FAR_DISTANCE = 24.0D;

    /**
     * 攻击者在不在目标背后：以目标的朝向为准，攻击者落在身后约 120° 的扇形里算背刺。
     *
     * <p>只看水平面，站高站低不影响；朝向用头的朝向（生物转头比转身快，看的是它「在看哪」）。
     *
     * @param attacker 攻击者
     * @param target   目标
     * @return 在背后返回 true
     */
    public static boolean isBehind(LivingEntity attacker, LivingEntity target) {
        double dx = attacker.getX() - target.getX();
        double dz = attacker.getZ() - target.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return false;
        }
        double yaw = Math.toRadians(target.getYHeadRot());
        // Minecraft 的朝向：yaw = 0 面朝 +Z，向 -X 转为正
        double lookX = -Math.sin(yaw);
        double lookZ = Math.cos(yaw);
        // 攻击者方向和目标朝向的夹角余弦 < -0.5，即夹角大于 120°，算在背后
        return (dx * lookX + dz * lookZ) / length < -0.5D;
    }

    /**
     * 只看攻击者自己的那一层：背水（自己血越少越狠）、嗜战（击杀叠起来的层数）。
     *
     * @param values   当前生效的特化数值
     * @param attacker 攻击者，可为 null
     * @return 倍率
     */
    public static double selfMultiplier(SpecializationValues values, @Nullable LivingEntity attacker) {
        double multiplier = 1.0D;
        if (attacker != null && values.desperationDamage() != 1.0D) {
            double ratio = healthRatio(attacker);
            double threshold = values.desperationThreshold();
            if (threshold > 0.0D && ratio < threshold) {
                // 在「开始生效」和「吃满」两条血线之间线性过渡：
                // 做成一刀切的话玩家会在阈值上下反复横跳，手感很怪；
                // 而让它一直插值到 0 血才吃满，等于加成只在快死的那一瞬间有意义
                double floor = Math.min(values.desperationFloor(), threshold);
                double span = threshold - floor;
                double t = span <= 0.0D ? 1.0D
                        : Math.max(0.0D, Math.min(1.0D, (threshold - ratio) / span));
                multiplier *= 1.0D + (values.desperationDamage() - 1.0D) * t;
            }
        }
        // 嗜战：击杀叠起来的层数（只在服务端记）
        if (attacker instanceof ServerPlayer player) {
            multiplier *= FrenzyTracker.multiplier(player, values);
        }
        return Math.max(0.0D, multiplier);
    }

    /**
     * 掷一次暴击。
     *
     * <p>暴击和「看情况」的那些倍率是独立的一层：先算条件倍率，再乘暴击，
     * 三个来源的战斗处理器各自在改伤害之前调一次。概率为 0 的特化直接返回 1.0，不消耗随机数。
     *
     * @param values 当前生效的特化数值
     * @param random 随机源，用攻击者自己的 {@code getRandom()}
     * @return 暴击了返回暴击倍率，否则 1.0
     */
    public static double critMultiplier(SpecializationValues values, RandomSource random) {
        double chance = values.critChance();
        if (chance <= 0.0D || values.critDamage() <= 1.0D) {
            return 1.0D;
        }
        return random.nextDouble() < chance ? values.critDamage() : 1.0D;
    }

    /**
     * 算一个实体的血量比例。
     *
     * @param entity 实体
     * @return 0 到 1 之间；最大血量异常时按满血算
     */
    private static double healthRatio(LivingEntity entity) {
        float max = entity.getMaxHealth();
        if (max <= 0.0F) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, entity.getHealth() / max));
    }

    /**
     * 判断某个武器类型的特化是否已经解锁：第一个槽开了（类型里有武器过了槽 1 的门槛）。
     *
     * @param data     专精数据，可为 null
     * @param typeKey  类型键
     * @param snapshot 配置快照
     * @return 已解锁返回 true
     */
    public static boolean isUnlocked(@Nullable MasteryData data, String typeKey,
                                     MasteryConfig.Snapshot snapshot) {
        if (data == null || !snapshot.enableSpecialization) {
            return false;
        }
        // 「解锁」= 第一槽可以选了
        return isSlotUnlocked(data, typeKey, 1, snapshot);
    }
}
