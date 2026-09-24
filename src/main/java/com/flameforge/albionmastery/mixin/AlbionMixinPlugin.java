package com.flameforge.albionmastery.mixin;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 按「目标模组装没装」决定每个 Mixin 要不要应用。
 *
 * <p>补的是什么：Mixin 配置里列着的类只要目标类找不到就会让整个配置报错，
 * 而 TACZ、近战附属、拔刀剑、铁魔法四个来源全是可选依赖。这个插件在 Mixin 去找目标类<b>之前</b>
 * 先看一眼：目标在某个可选模组的包里、那个模组又没装，就直接说「不用应用」，Mixin 连目标类都不会去加载。
 *
 * <p>这个阶段模组还没加载完，不能用 {@code ModList}，只能用 {@code FMLLoader.getLoadingModList()}
 * 查模组文件在不在。以后再接别的可选模组，往 {@link #OPTIONAL_TARGETS} 里加一行就行。
 */
public final class AlbionMixinPlugin implements IMixinConfigPlugin {

    /**
     * 目标类包前缀 -&gt; 这个目标需要哪些模组都装着。第一项是前缀，后面全是模组 ID。
     *
     * <p>按顺序匹配，第一条前缀对上就按那条判断，所以更细的前缀要排在更粗的前面：
     * 第三人称动作那两条既要对应模组，也要 Player Animator。
     */
    private static final String[][] OPTIONAL_TARGETS = {
            {"com.tacz.guns.compat.playeranimator.", "tacz", "playeranimator"},
            {"me.xjqsh.lrtactical.compat.player_animator.", "lrtactical", "playeranimator"},
            {"com.tacz.guns.", "tacz"},
            {"me.xjqsh.lrtactical.", "lrtactical"},
            {"mods.flammpfeil.slashblade.", "slashblade"},
            {"io.redspace.ironsspellbooks.", "irons_spellbooks"},
            {"com.hollingsworth.arsnouveau.", "ars_nouveau"},
            {"com.gametechbc.gtbcs_geomancy_plus.", "gtbcs_geomancy_plus"},
            {"sweetmagic.", "sweetmagic"},
    };

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        for (String[] entry : OPTIONAL_TARGETS) {
            if (targetClassName.startsWith(entry[0])) {
                for (int i = 1; i < entry.length; i++) {
                    if (!isModPresent(entry[i])) {
                        return false;
                    }
                }
                return true;
            }
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }

    /**
     * 某个模组的文件有没有被发现（不等于加载完成，但足够判断「装没装」）。
     *
     * @param modId 模组 ID
     * @return 在返回 true
     */
    private static boolean isModPresent(String modId) {
        LoadingModList list = FMLLoader.getLoadingModList();
        return list != null && list.getModFileById(modId) != null;
    }
}
