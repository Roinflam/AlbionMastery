package com.flameforge.albionmastery.compat.ftbquests;

import com.flameforge.albionmastery.client.rank.RankEmblem;
import com.flameforge.albionmastery.mastery.rank.MasteryRank;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ftb.mods.ftblibrary.icon.Icon;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * FTB 任务界面里的段位徽章图标。
 *
 * <p>不另做贴图，直接调 {@link RankEmblem} 画徽章，任务界面给多大的格子就按多大画。
 * 只从客户端代码里创建（任务的 {@code getAltIcon}、任务类型的图标 lambda），服务端不会画它。
 */
final class RankEmblemIcon extends Icon {

    /** 画哪个段位的徽章。 */
    private final int rank;

    private RankEmblemIcon(int rank) {
        this.rank = rank;
    }

    /**
     * @param rank 段位，超出范围的夹到 0 ~ {@link MasteryRank#MAX_RANK}
     * @return 图标
     */
    static Icon of(int rank) {
        return new RankEmblemIcon(Math.max(0, Math.min(rank, MasteryRank.MAX_RANK)));
    }

    /**
     * FTB 画任务按钮时先把位姿放大到按钮大小，再按 1×1 的格子调 {@code draw}；直接拿 1 当边长，
     * 徽章会被当成看不见而整个跳过，细节档位也会选错。所以先把位姿的缩放折算进边长，
     * 在缩放还原后的坐标系里按真实大小画。
     */
    @Override
    @OnlyIn(Dist.CLIENT)
    public void draw(GuiGraphics graphics, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        PoseStack pose = graphics.pose();
        Matrix4f matrix = pose.last().pose();
        float scale = (float) Math.sqrt(matrix.m00() * matrix.m00() + matrix.m01() * matrix.m01());
        if (scale <= 1.0E-4F) {
            return;
        }
        pose.pushPose();
        pose.translate(x + w / 2.0F, y + h / 2.0F, 0.0F);
        pose.scale(1.0F / scale, 1.0F / scale, 1.0F);
        RankEmblem.draw(graphics, rank, 0.0F, 0.0F, Math.min(w, h) * scale, 1.0F);
        pose.popPose();
    }

    /*
     * FTB 的 Icon.hashCode() 取 getJson().hashCode()，而 getJson() 默认又是 new JsonPrimitive(toString())，
     * Object.toString() 再回头调 hashCode()——子类三个都不重写就是无限递归。JEI 的任务配方插件建索引时
     * 会拿任务图标做哈希，栈溢出直接打断 JEI 启动，整个物品栏都出不来（只在有段位任务的服务器上出现）。
     * 三个都按段位号自己实现，不再绕回父类。
     */

    @Override
    public String toString() {
        return "albionmastery:rank_emblem/" + rank;
    }

    @Override
    public int hashCode() {
        return 31 * RankEmblemIcon.class.hashCode() + rank;
    }

    @Override
    public boolean equals(Object o) {
        return o == this || (o instanceof RankEmblemIcon other && other.rank == rank);
    }
}
