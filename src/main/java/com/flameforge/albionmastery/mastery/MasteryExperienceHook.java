// 路径：src/main/java/com/flameforge/albionmastery/mastery/MasteryExperienceHook.java
package com.flameforge.albionmastery.mastery;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.weapon.ResolvedWeapon;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import javax.annotation.Nullable;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.flameforge.albionmastery.weapon.compat.SweetMagicSupport;

/**
 * 击杀经验的统一入口。
 *
 * <p>专精经验只来自<b>生物实际掉落的经验值</b>，也就是所有模组都改完之后、真正会落地的那个数。
 *
 * <p>击杀者与附近分到经验的玩家，都按<b>主手加副手</b>两把武器一起结算，副手按比例打折。
 *
 * <p>最终数量还会乘上所在维度的倍率，见 {@link DimensionXpBonus}。共享的人和击杀者在同一维度，
 * 所以倍率只算一次就够。
 *
 * <p>拿到这个「最终值」有两条路，这里两条都留着，互相兜底：
 * <ol>
 *     <li><b>Mixin</b>（{@code ForgeEventFactoryMixin}）——注入在
 *         {@code ForgeEventFactory.getExperienceDrop} 的返回处。事件的所有监听器都跑完了才轮到它，
 *         拿到的必然是最终值。</li>
 *     <li><b>事件监听</b>（{@code EventPriority.LOWEST}）——兜底用。
 *         {@code LOWEST} 只是个优先级桶，同桶内按注册顺序执行，别的模组也用 LOWEST 时
 *         谁先谁后是不确定的，所以它读到的值<b>不保证</b>是最终值。</li>
 * </ol>
 *
 * <p>两条路的配合方式：监听器只把结果<b>暂存</b>不发放；Mixin 跑到时把暂存项换成最终值发出去，
 * 同时置位 {@link #mixinActive}，之后监听器就直接跳过。要是 Mixin 压根没生效（比如 Forge 改了
 * 那个方法的签名），暂存项会在服务端 tick 末尾被兜底发放。这样既不会重复计算，也不会静默失效。
 */
public final class MasteryExperienceHook {

    /** Mixin 是否已经生效过。生效之后监听器就不再暂存了。 */
    private static volatile boolean mixinActive = false;

    /** 待发放的击杀奖励，键是被杀生物的实体 ID。 */
    private static final Map<Integer, Pending> PENDING = new HashMap<>();

    /** CustomNPCs 的 modid，只用来比命名空间。 */
    private static final String CUSTOM_NPCS_MOD_ID = "customnpcs";

    private MasteryExperienceHook() {
    }

    /**
     * 事件监听器调用：先暂存，等 Mixin 或 tick 末尾兜底发放。
     *
     * @param entityId       被杀生物的实体 ID
     * @param player         击杀者
     * @param droppedExperience 该监听器看到的掉落经验（不保证是最终值）
     */
    public static void record(int entityId, Player player, int originalExperience, int droppedExperience) {
        if (mixinActive || !(player instanceof ServerPlayer)) {
            return;
        }
        PENDING.put(entityId, new Pending(player, originalExperience, droppedExperience));
    }

    /**
     * Mixin 调用：这是所有监听器跑完之后的最终值，直接发放。
     *
     * @param entityId          被杀生物的实体 ID
     * @param player            击杀者
     * @param droppedExperience 最终掉落经验
     */
    public static void commitFinal(int entityId, Player player, int originalExperience, int droppedExperience) {
        mixinActive = true;
        PENDING.remove(entityId);
        award(player, originalExperience, droppedExperience);
    }

    /**
     * 这次击杀能不能发专精经验。Mixin 和兜底监听两条路都先过这一关，规则只写一份。
     *
     * <ul>
     *     <li>击杀者得是真人：自动击杀机器的假玩家不算，不然站在刷怪塔旁边就能挂机分经验；</li>
     *     <li>旁观者不算；自己把自己弄死不算；</li>
     *     <li>杀玩家要看 {@code xpFromPlayerDamage}。</li>
     * </ul>
     *
     * @param victim 被杀的实体
     * @param killer 击杀者
     * @return 能发返回 true
     */
    public static boolean isEligibleKill(LivingEntity victim, @Nullable Player killer) {
        if (!(killer instanceof ServerPlayer player) || player instanceof FakePlayer || player.isSpectator()) {
            return false;
        }
        if (victim == killer) {
            return false;
        }
        return !(victim instanceof Player) || MasteryConfig.COMMON.xpFromPlayerDamage.get();
    }

    /**
     * 服务端 tick 末尾兜底：把没被 Mixin 接手的暂存项发出去。
     *
     * <p>正常情况下这里永远是空的——Mixin 会在同一次调用里就把暂存项取走。
     */
    public static void flushPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> pending = new ArrayList<>(PENDING.values());
        PENDING.clear();
        for (Pending entry : pending) {
            award(entry.player, entry.originalExperience, entry.droppedExperience);
        }
    }

    /**
     * 断线 / 关服时清空暂存，避免残留引用。
     */
    public static void clear() {
        PENDING.clear();
    }

    /**
     * 按掉落经验发放专精经验，并把一部分分给附近的其他玩家。
     *
     * @param killer            击杀者
     * @param droppedExperience 生物实际掉落的经验值
     */
    /**
     * 把一次击杀的经验分给附近所有玩家。
     *
     * <p>不再区分「击杀者」和「帮打的」：范围内每个还练得动的玩家都是一份，
     * 每人拿到的比例按参与人数查表（参考 LoL 的共享经验曲线）——
     * 一个人吃满，人越多每人越少，但队伍总收益是上升的。
     *
     * <p>「练得动」的判断是主副手至少有一件可专精武器还没三层全满。
     * 全满的人<b>不占人头</b>：不然一个满级大佬站旁边，就会白白把队友的收益压下去一档。
     *
     * @param killer            造成击杀的玩家，用来确定维度和位置
     * @param droppedExperience 怪物掉落的原版经验
     */
    /**
     * 被杀的是不是自定义 NPC（CustomNPCs）。
     *
     * <p>按实体类型的命名空间认，不引用它的任何类，没装也能编译、能跑。
     *
     * @param entity 实体
     * @return 是 NPC 返回 true
     */
    public static boolean isCustomNpc(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return typeId != null && CUSTOM_NPCS_MOD_ID.equals(typeId.getNamespace());
    }

    /**
     * 杀 NPC：按 NPC 编辑页里设的掉落经验（min / max 平均）折算，并额外乘
     * {@code npcExperienceMultiplier}。
     *
     * <p>这里刻意区分「明确配置为 0」和「反射读取失败」：
     * <ul>
     *     <li>NPC 的最小 / 最大经验都为 0：就是 0，直接不给专精经验；</li>
     *     <li>读不到 NPC 配置：才回退到 {@code npcFallbackMobExperience}；</li>
     *     <li>原始经验大于 1 且倍率大于 0：倍率折算后最低按 1 计算。</li>
     * </ul>
     *
     * <p>走 {@code LivingDeathEvent}，因为 NPC 掉经验是它自己生成经验球，不走原版那条路，
     * 掉落经验事件根本不会触发。
     *
     * @param killer 击杀者
     * @param npc    被杀的 NPC
     */
    public static void awardNpcKill(Player killer, LivingEntity npc) {
        var configured = NpcExperienceReader.read(npc);
        double experience = configured.isPresent()
                ? configured.getAsDouble()
                : MasteryConfig.COMMON.npcFallbackMobExperience.get();

        // 明确配置为 0 时保持 0；只有真正读不到时才会走上面的兜底值。
        if (experience <= 0.0D) {
            return;
        }

        double multiplier = MasteryConfig.COMMON.npcExperienceMultiplier.get();
        double adjusted = experience * multiplier;
        if (experience > 1.0D && multiplier > 0.0D) {
            adjusted = Math.max(1.0D, adjusted);
        }
        if (adjusted <= 0.0D) {
            return;
        }
        award(killer, adjusted, adjusted);
    }

    private static void award(Player killer, double originalExperience, double droppedExperience) {
        if (!(killer instanceof ServerPlayer player) || player instanceof FakePlayer
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        // 默认按原版数值算：恶意、金龟这类模组把掉落经验放大多少倍，专精经验都不跟着涨；
        // 怪最终不掉经验（掉落值 0，比如被取消了）时也不给
        boolean useOriginal = MasteryConfig.COMMON.xpFromOriginalMobExperience.get();
        double dropped = Math.max(0.0D,
                useOriginal && droppedExperience > 0.0D ? originalExperience : droppedExperience);

        double base = MasteryConfig.COMMON.xpPerKill.get()
                + dropped * MasteryConfig.COMMON.xpPerMobExperience.get();
        // 危险的地方打怪更划算：下界 ×1.5、末地 ×2、模组维度 ×2
        base *= DimensionXpBonus.multiplierFor(player.level());
        if (base <= 0.0D) {
            return;
        }

        MasteryConfig.Snapshot snapshot = MasteryConfig.serverSnapshot();
        double radius = MasteryConfig.COMMON.nearbyShareRadius.get();
        double radiusSqr = radius * radius;

        // 每个参与者的武器清单只算一次：这份清单要查主副手、法术书、法杖，
        // 还要解析武器，算一次大几十微秒。以前「判断他练不练得动」和「真正发经验」
        // 各算一遍，群体击杀（一 tick 打死十几只）时这笔重复开销会被乘成毫秒级
        List<Participant> participants = new ArrayList<>();
        for (ServerPlayer candidate : level.players()) {
            // 旁观者、假玩家、已经死了还没重生的（开了死亡不掉落时手里照样拿着武器）都不占人头
            if (candidate.isSpectator() || candidate instanceof FakePlayer || !candidate.isAlive()) {
                continue;
            }
            // 半径设成 0 就退化成「只有击杀者自己」
            if (candidate != player && (radius <= 0.0D
                    || candidate.distanceToSqr(player) > radiusSqr)) {
                continue;
            }
            MasteryData data = MasteryHelper.get(candidate);
            if (data == null) {
                continue;
            }
            List<XpGroup> groups = xpGroups(candidate);
            if (hasRoomToLevel(data, groups, snapshot)) {
                participants.add(new Participant(candidate, data, groups));
            }
        }
        if (participants.isEmpty()) {
            return;
        }

        double share = falloffFor(participants.size());
        long amount = Math.max(1L, Math.round(base * share));
        for (Participant participant : participants) {
            awardTo(participant, amount);
        }
    }

    /**
     * 一名参与分摊的玩家，连同这次已经算好的数据和武器清单。
     *
     * @param player 玩家
     * @param data   专精数据
     * @param groups 这次该发经验的武器分组
     */
    private record Participant(ServerPlayer player, MasteryData data, List<XpGroup> groups) {
    }

    /**
     * 查参与人数对应的每人比例。
     *
     * @param count 参与人数，至少 1
     * @return 比例；表为空时退回 1.0
     */
    private static double falloffFor(int count) {
        List<? extends Double> table = MasteryConfig.COMMON.shareFalloff.get();
        if (table.isEmpty()) {
            return 1.0D;
        }
        // 人数超过表长就一直用最后一项，不会越查越少变成 0。
        // 按 Number 取：服主写成 [1, 0.725] 这种整数字面量时 TOML 读出来是 Integer，
        // 按 Double 取会抛 ClassCastException，而这里在击杀流程里，每杀一只都抛
        Object raw = table.get(Math.min(count, table.size()) - 1);
        if (!(raw instanceof Number number)) {
            return 0.0D;
        }
        double value = number.doubleValue();
        if (Double.isNaN(value) || value <= 0.0D) {
            return 0.0D;
        }
        return Math.min(1.0D, value);
    }

    /**
     * 判断一名玩家手上还有没有练得动的武器。
     *
     * <p>三层里任意一层没满就算练得动。主副手都没有可专精武器，或者拿着的都三层全满，
     * 就当他不参与这次分摊。
     *
     * @param player   玩家
     * @param snapshot 配置快照
     * @return 还练得动返回 true
     */
    private static boolean hasRoomToLevel(MasteryData data, List<XpGroup> groups,
                                          MasteryConfig.Snapshot snapshot) {
        for (XpGroup group : groups) {
            for (ResolvedWeapon weapon : group.weapons()) {
                if (data.getWeaponLevel(weapon.weaponKey()) < snapshot.maxLevel
                        || data.getTypeLevel(weapon.typeKey()) < snapshot.maxLevel
                        || data.getOverallLevel(weapon.overallKey()) < snapshot.maxLevel) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 一组要拿击杀经验的武器，以及这组按几折发。
     *
     * @param weapons 武器
     * @param ratio   经验比例（主手 1.0，副手按配置打折）
     */
    private record XpGroup(List<ResolvedWeapon> weapons, double ratio) {
    }

    /**
     * 这次击杀该给哪些武器发经验。
     *
     * <p>主手、副手各一组：一件物品可能是一串武器（新生魔艺法术书里当前法术用到的每个符文），
     * 组内每件都拿；铁魔法的卷轴算，魔法书不算。铁魔法单独一组：饰品栏里的书只给法术栏里
     * <b>当前选中</b>的那一个法术——只给选中的，才不会拿一本满书随便杀点东西就十来个法术一起涨。
     *
     * @param player 玩家
     * @return 分组列表，可能含空组
     */
    private static List<XpGroup> xpGroups(ServerPlayer player) {
        List<XpGroup> groups = new ArrayList<>(3);
        double offhandRatio = MasteryConfig.COMMON.offhandXpRatio.get();
        groups.add(new XpGroup(handWeapons(player.getMainHandItem()), 1.0D));
        groups.add(new XpGroup(handWeapons(player.getOffhandItem()), offhandRatio));
        if (WeaponResolver.isIronsLoaded()) {
            ResolvedWeapon selected = IronsSpellsSupport.selectedSpell(player);
            groups.add(new XpGroup(selected == null ? List.of() : List.of(selected), 1.0D));
        }
        // 甜蜜魔法：手里拿的是法杖，真正在练的是它当前选中的那张魔法卷轴。
        // 主副手各一组，和其它手持武器一个口径——副手那根同样按副手比例算，
        // 两只手各拿一根时也各拿各的那份
        if (WeaponResolver.isSweetMagicLoaded()) {
            ResolvedWeapon mainMagic = SweetMagicSupport.selectedMagic(player, player.getMainHandItem());
            ResolvedWeapon offMagic = SweetMagicSupport.selectedMagic(player, player.getOffhandItem());
            groups.add(new XpGroup(mainMagic == null ? List.of() : List.of(mainMagic), 1.0D));
            groups.add(new XpGroup(offMagic == null ? List.of() : List.of(offMagic), offhandRatio));
        }
        return groups;
    }

    /**
     * 手里一件物品对应的武器。
     *
     * <p>铁魔法的<b>魔法书</b>在这里不算：书只能装在饰品栏里用，拿在手上什么都放不出来，
     * 它那份走「当前选中的法术」那一组。<b>卷轴</b>和附魔进武器里的法术照算——那是真拿在手上用的。
     *
     * @param stack 物品
     * @return 武器列表
     */
    private static List<ResolvedWeapon> handWeapons(ItemStack stack) {
        if (WeaponResolver.isIronsLoaded() && IronsSpellsSupport.isSpellbook(stack)) {
            return List.of();
        }
        return WeaponResolver.resolveAll(stack);
    }

    private static void awardTo(Participant participant, long amount) {
        MasteryData data = participant.data();
        Set<String> awarded = new HashSet<>(4);
        // 类型和总体这次已经记过的键：一个法术好几个符文、主副手同一来源时，
        // 武器各拿各的，但类型和总体只记一份，不因为手里武器多就翻倍
        Set<String> credited = new HashSet<>(4);
        double multiRatio = MasteryConfig.COMMON.multiWeaponXpRatio.get();

        for (XpGroup group : participant.groups()) {
            List<ResolvedWeapon> weapons = group.weapons();
            if (weapons.isEmpty()) {
                continue;
            }
            long give = Math.round(amount * group.ratio());
            // 一组里好几件（一个法术的好几个符文）时按配置打折
            if (weapons.size() > 1) {
                give = Math.round(give * multiRatio);
            }
            if (give <= 0L) {
                continue;
            }
            for (ResolvedWeapon weapon : weapons) {
                if (!awarded.add(weapon.weaponKey())) {
                    continue;
                }
                try {
                    MasteryHelper.awardXp(data, weapon, give, MasteryConfig.serverSnapshot(), true, credited);
                } catch (Exception exception) {
                    AlbionMastery.LOGGER.error("发放击杀专精经验失败: {}", weapon.weaponKey(), exception);
                }
            }
        }
    }

    /**
     * 暂存的一次击杀奖励。
     *
     * @param player            击杀者
     * @param droppedExperience 掉落经验
     */
    private record Pending(Player player, int originalExperience, int droppedExperience) {
    }
}


