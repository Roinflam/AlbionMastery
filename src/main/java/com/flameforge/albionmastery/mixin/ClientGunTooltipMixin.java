package com.flameforge.albionmastery.mixin;

import com.flameforge.albionmastery.client.MasteryClientBonus;
import com.flameforge.albionmastery.client.screen.MasteryScreen;
import com.flameforge.albionmastery.config.MasteryConfig;
import com.flameforge.albionmastery.config.SpecializationValues;
import com.flameforge.albionmastery.mastery.MasteryHelper;
import com.flameforge.albionmastery.mastery.spec.SpecializationEffects;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.tacz.guns.client.tooltip.ClientGunTooltip;
import com.tacz.guns.item.GunTooltipPart;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * 把专精信息接进 TACZ 枪械的自绘提示框，<b>独立成行、紧跟在「伤害」那一行下面</b>。
 *
 * <p>补的是什么：TACZ 的枪械提示不是普通文本行，而是一个自绘的
 * {@code ClientTooltipComponent}。它把每项数据存成一个独立的 {@code MutableComponent}
 * 字段，在 {@code getText()} 里算好，然后在 {@code renderText()} 里按固定顺序、
 * 用一个手动累加的 Y 坐标一行行画出来：
 * <pre>
 *   desc → ammoName / ammoCountText → infiniteAmmoText → gunType → damage
 *        → armorIgnore → headShotMultiplier → weight → tips → packInfo
 * </pre>
 * 整块的高度由 {@code getHeight()} 按「哪些区块要显示」写死累加。
 *
 * <p>所以「在伤害下面插一行」要动三处，缺一处就会错位：
 * <ol>
 *     <li>{@code getText()}：算好这一行的内容，顺便把提示框的最大宽度撑开；</li>
 *     <li>{@code getHeight()}：总高度加 10（TACZ 一行的行高就是 10）；</li>
 *     <li>{@code renderText()}：画完伤害那一行之后，把 Y 坐标再往下推 10，
 *         在推出来的位置画我们这一行。后面的穿甲、爆头、移速会顺着新的 Y 坐标继续往下排，
 *         不用一个个去改。</li>
 * </ol>
 *
 * <p>第三处用的是 {@code @ModifyVariable} 改 Y 坐标那个局部变量（LVT 槽位 6，
 * 也就是方法开头 {@code int yOffset = y}）。定位靠切片：从「读取 damage 字段」开始，
 * 之后遇到的第一次 {@code drawInBatch} 一定是画伤害那一行——这样即使以后 TACZ
 * 在伤害前面多加几行，也不会定位到别的行上去。
 *
 * <p>这一行只在「基础信息」区块显示时才加（伤害本身就在那个区块里）。
 * 如果枪包用 hide flag 把这个区块隐藏了，没有伤害行可以挂，这一行也一起不显示。
 *
 * <p>类上的 {@code remap = false} 只对 TACZ 自己的方法（getText / shouldShow 之类）生效；
 * {@code getHeight} / {@code renderText} 是 Minecraft 接口 {@code ClientTooltipComponent}
 * 的方法，正式环境有混淆名，所以这两处的注入单独打开 {@code remap = true}。
 *
 * <p>被重定向的伤害取值方法本身已经算过配件修正，因此显示值天然包含配件加成。
 */
@Mixin(value = ClientGunTooltip.class, remap = false)
public abstract class ClientGunTooltipMixin {

    /** TACZ 提示框里一行文字的行高。 */
    private static final int ALBIONMASTERY$LINE_HEIGHT = 10;

    /** TACZ 画提示文字用的光照值（0xF000F0，也就是 GUI 满亮）。 */
    private static final int ALBIONMASTERY$FULL_BRIGHT = 15728880;

    /** 等级色，和另一处提示共用同一套配色。 */
    private static final int ALBIONMASTERY$COLOR_VALUE = 0xFFE9A8;

    /** 加成数值色。 */
    private static final int ALBIONMASTERY$COLOR_BONUS = 0xE8C25A;

    /** 标签与分隔符色。 */
    private static final int ALBIONMASTERY$COLOR_LABEL = 0x9C8E70;

    @Shadow
    @Final
    private ItemStack gun;

    @Shadow
    private int maxWidth;

    /**
     * 算好的专精行；为 null 表示这把枪没练过或者伤害行不显示，什么都不画。
     *
     * <p>不给初始值：{@code getText()} 是在 TACZ 的构造器里调的，
     * 靠 Mixin 合并字段初始化器有先后顺序的坑，直接依赖 Java 默认的 null 最稳。
     */
    @Unique
    @Nullable
    private Component albionmastery$masteryLine;

    /**
     * 重定向伤害取值，乘上当前玩家对这把枪的专精加成。
     *
     * @param stack   枪械物品堆
     * @param gunData 枪械数据
     * @return 加成后的伤害
     */
    @Redirect(
            method = "getText",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/util/AttachmentDataUtils;getDamageWithAttachment"
                            + "(Lnet/minecraft/world/item/ItemStack;"
                            + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;)D"
            )
    )
    private double albionmastery$applyMasteryBonus(ItemStack stack, GunData gunData) {
        double base = AttachmentDataUtils.getDamageWithAttachment(stack, gunData);
        if (!MasteryConfig.CLIENT.overrideDamageDisplay.get()) {
            return base;
        }
        return base * MasteryClientBonus.multiplierFor(stack);
    }

    /**
     * 让提示里的弹匣容量也显示扩容之后的值。
     *
     * <p>伤害那一行是靠重定向调用点改的，弹匣容量则用 MixinExtras 的
     * {@code @ModifyExpressionValue}——同一个类里两种注解并用没有问题，
     * 它们改的是不同的调用点。
     *
     * @param original 原始容量
     * @return 应用特化之后的容量
     */
    @ModifyExpressionValue(
            method = "getText",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/util/AttachmentDataUtils;getAmmoCountWithAttachment"
                            + "(Lnet/minecraft/world/item/ItemStack;"
                            + "Lcom/tacz/guns/resource/pojo/data/gun/GunData;)I"
            ),
            require = 0
    )
    private int albionmastery$applyMagazineSpec(int original) {
        SpecializationValues values = SpecializationEffects.forStack(
                Minecraft.getInstance().player, this.gun);
        if (values.magazine() == 1.0D) {
            return original;
        }
        return Math.max(1, (int) Math.round(original * values.magazine()));
    }

    /**
     * 在 TACZ 算完所有文本之后，把专精行算出来存好，并把提示框宽度撑到能装下它。
     *
     * @param callbackInfo 回调信息
     */
    @Inject(method = "getText", at = @At("RETURN"))
    private void albionmastery$buildMasteryLine(CallbackInfo callbackInfo) {
        this.albionmastery$masteryLine = null;
        if (!MasteryConfig.CLIENT.showMasteryTooltip.get()
                || !albionmastery$isPartShown(GunTooltipPart.BASE_INFO)) {
            return;
        }
        MasteryHelper.Bonus bonus = MasteryClientBonus.bonusFor(this.gun);
        // 没练过的枪什么都不加，别给一条全是 0 的字占地方
        if (bonus == null || (bonus.total() <= 0.0D && bonus.weaponLevel() <= 0)) {
            return;
        }

        MutableComponent line = Component.translatable("tooltip.albionmastery.title")
                .withStyle(style -> style.withColor(TextColor.fromRgb(ALBIONMASTERY$COLOR_LABEL)))
                .append(Component.literal(" Lv." + bonus.weaponLevel()).withStyle(
                        style -> style.withColor(TextColor.fromRgb(ALBIONMASTERY$COLOR_VALUE))))
                .append(Component.literal("  " + MasteryScreen.formatPercent(bonus.total()))
                        .withStyle(style -> style.withColor(
                                TextColor.fromRgb(ALBIONMASTERY$COLOR_BONUS))));
        this.albionmastery$masteryLine = line;

        // 宽度是在 getText 里量好存起来的，新加的行更长就得把它顶上去，
        // 否则文字会画到背景框外面
        Font font = Minecraft.getInstance().font;
        this.maxWidth = Math.max(this.maxWidth, font.width(line));
    }

    /**
     * 有专精行时，把整块提示的高度加一行。
     *
     * <p>{@code getHeight} 是 Minecraft 接口方法，正式环境是混淆名，所以这里单独 {@code remap = true}。
     * 注入失败只会让这一行没位置画（下面 {@code renderText} 的注入也会一起失败），不影响启动。
     *
     * @param callbackInfo 携带原始高度的回调
     */
    @Inject(method = "getHeight", at = @At("RETURN"), cancellable = true, remap = true, require = 0)
    private void albionmastery$extendHeight(CallbackInfoReturnable<Integer> callbackInfo) {
        if (this.albionmastery$masteryLine == null) {
            return;
        }
        callbackInfo.setReturnValue(callbackInfo.getReturnValueI() + ALBIONMASTERY$LINE_HEIGHT);
    }

    /**
     * 画完伤害那一行之后，把 Y 坐标往下推一行，并在推出来的位置画专精行。
     *
     * <p>这里改的是 {@code renderText} 里那个逐行累加的 Y 坐标（LVT 槽位 6，
     * 方法开头的 {@code int yOffset = y}）。切片从读取 {@code damage} 字段开始，
     * 之后第一次 {@code drawInBatch(Component, ...)} 就是画伤害的那一次；
     * 注入点放在这次调用<b>之后</b>，此时 Y 还停在伤害行上，TACZ 自己的 {@code += 10}
     * 要到我们返回之后才执行。所以返回 {@code y + 10}，TACZ 再加 10，
     * 后面的穿甲 / 爆头 / 移速就自然排到了我们这一行的下面。
     *
     * <p>处理方法的参数在「被改的变量」之后接目标方法的完整参数表，这是 Mixin 自带的写法，
     * 拿字体、X 坐标、矩阵和缓冲区正好够画一行字。
     *
     * @param yOffset 当前的 Y 坐标（伤害行所在的那一行）
     * @param font    字体
     * @param x       提示框左边缘 X
     * @param y       提示框顶部 Y（原始参数，这里用不到）
     * @param matrix  变换矩阵
     * @param buffer  渲染缓冲
     * @return 画完专精行之后的 Y 坐标
     */
    @ModifyVariable(
            method = "renderText",
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            target = "Lcom/tacz/guns/client/tooltip/ClientGunTooltip;damage"
                                    + ":Lnet/minecraft/network/chat/MutableComponent;",
                            opcode = Opcodes.GETFIELD,
                            remap = false
                    )
            ),
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;drawInBatch"
                            + "(Lnet/minecraft/network/chat/Component;FFIZ"
                            + "Lorg/joml/Matrix4f;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
                            + "Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            ),
            index = 6,
            remap = true,
            require = 0
    )
    private int albionmastery$drawMasteryLine(int yOffset, Font font, int x, int y,
                                              Matrix4f matrix, MultiBufferSource.BufferSource buffer) {
        Component line = this.albionmastery$masteryLine;
        if (line == null) {
            return yOffset;
        }
        int lineY = yOffset + ALBIONMASTERY$LINE_HEIGHT;
        // 基础色给白色即可：这一行每一段都带自己的颜色，白色只是没颜色时的兜底
        font.drawInBatch(line, x, lineY, 0xFFFFFF, true, matrix, buffer,
                Font.DisplayMode.NORMAL, 0, ALBIONMASTERY$FULL_BRIGHT);
        return lineY;
    }

    /**
     * 判断某个提示区块有没有被枪包用 hide flag 藏起来。
     *
     * <p>和 TACZ 自己那个私有的 {@code shouldShow} 逻辑完全一样，
     * 这里自己写一遍是为了不去 {@code @Shadow} 一个私有方法。
     *
     * @param part 区块
     * @return 要显示返回 true
     */
    @Unique
    private boolean albionmastery$isPartShown(GunTooltipPart part) {
        return (GunTooltipPart.getHideFlags(this.gun) & part.getMask()) == 0;
    }
}
