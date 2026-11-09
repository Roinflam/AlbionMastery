package com.flameforge.albionmastery.weapon;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.weapon.compat.ArsNouveauSupport;
import com.flameforge.albionmastery.weapon.compat.IronsArtificeSupport;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import com.flameforge.albionmastery.weapon.compat.LrTacticalSupport;
import com.flameforge.albionmastery.weapon.compat.SlashBladeSupport;
import com.flameforge.albionmastery.weapon.compat.TaczSupport;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import com.flameforge.albionmastery.weapon.compat.TinkersSupport;

/**
 * 服务端这边的武器清单：已安装的枪包、刀包、拔刀剑、法术、符文和原版剑弓，一件一条。
 *
 * <p>罗盘界面用的是客户端那份清单（带图标、带翻译好的名字），服务端拿不到那些东西；
 * 但管理指令要「全部满级」「拿一把」，得知道服务端认得哪些武器、怎么造出物品，就用这份。
 * 每一条的三层键都和战斗里解析出来的一致：类型的算法直接调各来源 Support 里的同一个函数，
 * 原版剑弓则干脆拿物品过一遍 {@link WeaponResolver}。
 */
public final class WeaponCatalog {

    /**
     * 清单缓存多久（毫秒）。补全指令时每敲一个字都要查一次，
     * 而枪包、数据包重载一局也就几次，半分钟的延迟完全可以接受。
     */
    private static final long CACHE_MS = 600_000L;

    private static List<Entry> entries = List.of();
    private static Map<String, Entry> byKey = Map.of();
    private static long builtAt;
    @Nullable
    private static MinecraftServer builtFor;

    private WeaponCatalog() {
    }

    /**
     * 清单里的一件武器。
     *
     * @param weapon 三层键
     * @param items  造出这件武器对应物品的办法；只在真要发物品时才调用，列清单时不造
     */
    public record Entry(ResolvedWeapon weapon, Supplier<List<ItemStack>> items) {

        /**
         * 造出物品。造失败（附属模组的物品构造抛异常之类）返回空表，不往外抛。
         *
         * @return 物品列表，每次都是新的一份
         */
        public List<ItemStack> createItems() {
            try {
                List<ItemStack> created = this.items.get();
                List<ItemStack> result = new ArrayList<>(created.size());
                for (ItemStack stack : created) {
                    if (stack != null && !stack.isEmpty()) {
                        result.add(stack);
                    }
                }
                return result;
            } catch (Exception exception) {
                AlbionMastery.LOGGER.warn("构建武器物品失败: {}", this.weapon.weaponKey(), exception);
                return List.of();
            }
        }
    }

    /**
     * @param server 服务端
     * @return 全部武器，按来源分组的固定顺序
     */
    public static synchronized List<Entry> all(MinecraftServer server) {
        ensure(server);
        return entries;
    }

    /**
     * @param server     服务端
     * @param providerId 来源 ID
     * @return 这个来源下的全部武器
     */
    public static synchronized List<Entry> ofSource(MinecraftServer server, String providerId) {
        ensure(server);
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.weapon().providerId().equals(providerId)) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * @param server    服务端
     * @param weaponKey 武器键
     * @return 对应的条目；服务端不认识这件武器时返回 null
     */
    @Nullable
    public static synchronized Entry byKey(MinecraftServer server, String weaponKey) {
        ensure(server);
        return byKey.get(weaponKey);
    }

    /**
     * 丢掉缓存，下次查询重建。数据包重载、枪包重载之后调用。
     */
    public static synchronized void invalidate() {
        builtFor = null;
        entries = List.of();
        byKey = Map.of();
    }

    private static void ensure(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (server == builtFor && now - builtAt < CACHE_MS) {
            return;
        }
        boolean split = MasteryConfig.serverSnapshot().splitMeleeByPack;
        List<Entry> out = new ArrayList<>();
        // 每个来源单独兜一层异常：一个附属的注册表坏了，不该拖得别的来源也列不出来
        if (WeaponResolver.isTaczLoaded()) {
            collect("TACZ", () -> TaczSupport.catalog(out));
        }
        if (WeaponResolver.isIronsArtificeLoaded()) {
            collect("Iron's Arms 'n Artifice", () -> IronsArtificeSupport.catalog(out));
        }
        if (WeaponResolver.isLrTacticalLoaded()) {
            collect("lrtactical", () -> LrTacticalSupport.catalog(out, split));
        }
        if (WeaponResolver.isSlashBladeLoaded()) {
            collect("SlashBlade", () -> SlashBladeSupport.catalog(out, server.overworld()));
        }
        if (WeaponResolver.isIronsLoaded()) {
            collect("Iron's Spells", () -> IronsSpellsSupport.catalog(out));
        }
        if (WeaponResolver.isArsLoaded()) {
            collect("Ars Nouveau", () -> ArsNouveauSupport.catalog(out));
        }
        collect("vanilla", () -> collectItems(out, split));

        // 同一个键只留第一条：解析顺序和战斗里一致，先认走的算数
        Map<String, Entry> keyed = new LinkedHashMap<>();
        for (Entry entry : out) {
            keyed.putIfAbsent(entry.weapon().weaponKey(), entry);
        }
        entries = Collections.unmodifiableList(new ArrayList<>(keyed.values()));
        byKey = Collections.unmodifiableMap(keyed);
        builtFor = server;
        builtAt = now;
    }

    private static void collect(String name, Runnable action) {
        try {
            action.run();
        } catch (Exception | LinkageError exception) {
            AlbionMastery.LOGGER.warn("列出 {} 的武器失败", name, exception);
        }
    }

    /**
     * 原版剑、斧、三叉戟和弓弩：遍历物品注册表，谁被解析成这两个来源就收谁。
     *
     * <p>和客户端那两个 Provider 的做法一样，拔刀剑的刀、刀包的刀虽然也是 {@code SwordItem}，
     * 但解析器会先把它们认走，不会混进来。
     */
    private static void collectItems(List<Entry> out, boolean split) {
        for (Map.Entry<ResourceKey<Item>, Item> registryEntry
                : ForgeRegistries.ITEMS.getEntries()) {
            Item item = registryEntry.getValue();
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) {
                continue;
            }
            ResolvedWeapon resolved = WeaponResolver.resolve(stack, split);
            if (resolved == null) {
                continue;
            }
            String provider = resolved.providerId();
            if (!WeaponKeys.PROVIDER_VANILLA_MELEE.equals(provider) && !WeaponKeys.PROVIDER_BOW.equals(provider)
                    && !WeaponKeys.PROVIDER_TCONSTRUCT.equals(provider)
                    && !WeaponKeys.PROVIDER_SWEETMAGIC.equals(provider)) {
                continue;
            }
            ResourceLocation id = registryEntry.getKey().location();
            if (!id.equals(resolved.weaponId())) {
                continue;
            }
            boolean tinkers = WeaponKeys.PROVIDER_TCONSTRUCT.equals(provider);
            out.add(new Entry(resolved, () -> List.of(
                    tinkers ? TinkersSupport.displayStack(item) : new ItemStack(item))));
        }
    }
}
