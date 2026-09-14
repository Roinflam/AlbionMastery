package com.flameforge.albionmastery.client.weapon;

import com.flameforge.albionmastery.AlbionMastery;
import com.flameforge.albionmastery.weapon.WeaponKeys;
import com.flameforge.albionmastery.weapon.WeaponResolver;
import com.flameforge.albionmastery.weapon.compat.ArsNouveauSupport;
import com.hollingsworth.arsnouveau.api.registry.GlyphRegistry;
import com.hollingsworth.arsnouveau.api.spell.AbstractSpellPart;
import com.hollingsworth.arsnouveau.api.spell.SpellSchool;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * 新生魔艺的客户端来源：把注册表里所有启用的符文列成节点，图标就是符文物品。
 */
public final class ArsNouveauProvider implements IWeaponProvider {

    public static final ArsNouveauProvider INSTANCE = new ArsNouveauProvider();

    private ArsNouveauProvider() {
    }

    @Override
    public String providerId() {
        return WeaponKeys.PROVIDER_ARS_NOUVEAU;
    }

    @Override
    public boolean isAvailable() {
        return WeaponResolver.isArsLoaded();
    }

    @Override
    public Component sourceName() {
        return Component.translatable("albionmastery.source.ars_nouveau");
    }

    @Override
    public void collect(List<WeaponEntry> out) {
        if (!isAvailable()) {
            return;
        }
        Map<ResourceLocation, AbstractSpellPart> parts;
        try {
            parts = GlyphRegistry.getSpellpartMap();
        } catch (Exception exception) {
            AlbionMastery.LOGGER.debug("读取新生魔艺符文注册表失败", exception);
            return;
        }
        int sort = 0;
        for (Map.Entry<ResourceLocation, AbstractSpellPart> entry : parts.entrySet()) {
            AbstractSpellPart part = entry.getValue();
            if (part == null || entry.getKey() == null || !part.isEnabled()) {
                continue;
            }
            ResourceLocation id = entry.getKey();
            ItemStack icon = part.getGlyph() == null ? ItemStack.EMPTY : new ItemStack(part.getGlyph());
            // 符文物品的名字就是符文的名字，直接用，省得猜它的语言键
            Component name = icon.isEmpty() ? Component.literal(part.getName()) : icon.getHoverName();
            String type = ArsNouveauSupport.typeOf(part);
            out.add(new WeaponEntry(
                    WeaponKeys.weaponKey(providerId(), id),
                    WeaponKeys.typeKey(providerId(), type),
                    WeaponKeys.overallKey(providerId()),
                    id,
                    id.getNamespace(),
                    name,
                    type,
                    sort++,
                    icon));
        }
    }

    @Override
    public Component typeName(String type) {
        // 形态 / 增幅 / 其它三类是本模组自己定的，走自己的语言键；学派也允许用语言键覆盖
        String key = "albionmastery.type." + providerId() + "." + type;
        if (I18n.exists(key)) {
            return Component.translatable(key);
        }
        for (AbstractSpellPart part : GlyphRegistry.getSpellpartMap().values()) {
            if (part == null || part.spellSchools == null) {
                continue;
            }
            for (SpellSchool school : part.spellSchools) {
                if (school != null && type.equals(school.getId())) {
                    return school.getTextComponent();
                }
            }
        }
        return Component.literal(type);
    }
}
