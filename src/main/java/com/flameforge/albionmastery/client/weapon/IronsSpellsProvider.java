package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.IronsSpellsSupport;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 铁魔法的客户端来源：把注册表里所有启用的法术列成节点。
 *
 * <p>法术图标是贴图不是物品，所以节点用贴图画（{@link WeaponEntry#iconTexture()}），
 * 物品图标那一格放一张卷轴只是兜底。附属注册的法术、学派都在同一张注册表里，自动进来。
 */
public final class IronsSpellsProvider implements IWeaponProvider {

    public static final IronsSpellsProvider INSTANCE = new IronsSpellsProvider();

    private IronsSpellsProvider() {
    }

    @Override
    public String providerId() {
        return WeaponKeys.PROVIDER_IRONS_SPELLS;
    }

    @Override
    public boolean isAvailable() {
        return WeaponResolver.isIronsLoaded();
    }

    @Override
    public Component sourceName() {
        return Component.translatable("albionmastery.source.irons_spellbooks");
    }

    @Override
    public void collect(List<WeaponEntry> out) {
        if (!isAvailable()) {
            return;
        }
        Iterable<AbstractSpell> spells;
        try {
            spells = SpellRegistry.REGISTRY.get();
        } catch (Exception exception) {
            AlbionMastery.LOGGER.debug("读取铁魔法法术注册表失败", exception);
            return;
        }
        ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get());
        int sort = 0;
        for (AbstractSpell spell : spells) {
            // 关掉的法术和占位的 none 不进树
            if (spell == null || spell == SpellRegistry.none() || !spell.isEnabled()) {
                continue;
            }
            ResourceLocation id = spell.getSpellResource();
            if (id == null) {
                continue;
            }
            String type = IronsSpellsSupport.typeOf(spell.getSchoolType());
            out.add(new WeaponEntry(
                    WeaponKeys.weaponKey(providerId(), id),
                    WeaponKeys.typeKey(providerId(), type),
                    WeaponKeys.overallKey(providerId()),
                    id,
                    id.getNamespace(),
                    displayNameOf(spell),
                    type,
                    sort++,
                    scroll,
                    spell.getSpellIconResource()));
        }
    }

    @Override
    public Component typeName(String type) {
        // 允许用本模组的语言文件覆盖某个学派的显示名
        String overrideKey = "albionmastery.type." + providerId() + "." + type;
        if (I18n.exists(overrideKey)) {
            return Component.translatable(overrideKey);
        }
        ResourceLocation schoolId = IronsSpellsSupport.schoolIdOf(type);
        if (schoolId != null) {
            SchoolType school = SchoolRegistry.REGISTRY.get().getValue(schoolId);
            if (school != null && school.getDisplayName() != null) {
                return school.getDisplayName();
            }
        }
        return Component.literal(type);
    }

    /**
     * 法术的显示名。
     *
     * <p>{@code getDisplayName(Player)} 会带上等级修饰之类的东西，树上只要干净的名字，
     * 直接用它的语言键。
     *
     * @param spell 法术
     * @return 显示名
     */
    private static Component displayNameOf(AbstractSpell spell) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (minecraft.player != null) {
                Component name = spell.getDisplayName(minecraft.player);
                if (name != null) {
                    return name;
                }
            }
        } catch (Exception exception) {
            AlbionMastery.LOGGER.debug("读取法术显示名失败: {}", spell.getSpellResource(), exception);
        }
        return Component.translatable("spell." + spell.getSpellResource().getNamespace()
                + "." + spell.getSpellResource().getPath());
    }
}
