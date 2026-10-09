package cc.sighs.mixin;

import javax.annotation.Nullable;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import cc.sighs.rewind.client.WorldRepair;

/**
 * 世界选择界面上的「读取存档点」——进世界之前就能用的那条恢复入口。
 *
 * <p><b>第一排三颗等宽</b>：原版第一排是 {@code [进入世界 150][创建新的世界 150]}（整排 308 像素宽），
 * 这里把两颗都收成 100 宽，中间插进「读取存档点」——于是
 * {@code [进入世界 100][读取存档点 100][创建新的世界 100]}，三颗等宽、间隔 4 像素
 * （3 * 100 + 2 * 4 = 308），整排总宽与原来一样。
 *
 * <p><b>这一排在 26.1 上得手工摆</b>：footer 是个 4 列 {@code GridLayout}，列宽由第二排那四颗按钮
 * （各 71）定死，三颗 100 宽塞不进去；而且只要往网格里塞一个 100 宽的控件，网格自己就会被撑宽、
 * 连带把第二排也挤歪。所以网格保持原版不动（原来的两颗照旧跨 2 列），第一排三颗在
 * {@code init} 的尾巴上直接按坐标摆——坐标与另外三个 target 完全一样。
 *
 * <p><b>可用状态与目标存档都从 {@code updateButtonStatus(LevelSummary)} 来</b>：那是原版在选中项变化时
 * 走的钩子，参数就是当前选中的 {@code LevelSummary}（没选中时为 null）。不自己每 tick 去读列表，是因为
 * 判断「这个世界有没有存档点」要读一次索引文件，挂在选中变化上才划算。
 */
public final class WorldRepairMixins {
    private WorldRepairMixins() {
    }

    /**
     * 内层 mixin 继承目标的父类（{@code Screen}），这样 {@code addRenderableWidget} 这个 protected
     * 方法才调得到——mixin 类与 Screen 不同包、也不是它的子类时编不过，而 {@code @Invoker} 那条路
     * 在 0.8.5 的注解处理器上对「带参数的方法」解析不出来（它拿点号名字比对描述符形式的签名）。
     * 构造器只是为了让 javac 满意，Mixin 不会把它合并进目标类。
     */
    @Mixin(SelectWorldScreen.class)
    public abstract static class SelectWorldRepair extends Screen {
        /** 第一排三颗按钮的宽度。3 * 100 + 2 * 4 = 308，与原版第一排的总宽一致。 */
        private static final int BUTTON_WIDTH = 100;
        /** 第一排三颗按钮之间的留白。 */
        private static final int BUTTON_GAP = 4;
        /** 第一排的左边缘（原版那两颗合起来就是从 {@code width/2 - 154} 到 {@code width/2 + 154}）。 */
        private static final int ROW_LEFT_INSET = 154;
        /** 第一排到屏幕底边的距离，与原版一致（原版那两颗在 {@code height - 52}）。 */
        private static final int ROW_BOTTOM_OFFSET = 52;
        private static final int BUTTON_HEIGHT = 20;

        /** 原版的「进入世界」（26.1 把它存成了 private 字段）。 */
        @Shadow
        @Nullable
        private Button playWorldButton;

        /** 我们那颗按钮。 */
        @Unique
        @Nullable
        private Button rewind$repairButton;
        /** 原版的「创建新的世界」——它没存字段，只能在它进网格的时候截下来。 */
        @Unique
        @Nullable
        private Button rewind$createButton;

        protected SelectWorldRepair() {
            super(Component.empty());
        }

        /**
         * 把「创建新的世界」的引用截下来（它是个局部变量，没有字段指向它），顺便确认网格真的建成了
         * 它的原样——参数原样转交，网格结构一点都不动。
         *
         * <p>挂点认 {@code ordinal = 1} 的那次 {@code addChild(元素, 列数)} 调用——{@code createFooterButtons}
         * 里只有前两颗用这个两参数重载，按顺序是「进入世界(0) / 创建新的世界(1)」。
         */
        @Redirect(
                method = "createFooterButtons",
                at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;I)Lnet/minecraft/client/gui/layouts/LayoutElement;",
                        ordinal = 1))
        private LayoutElement rewind$captureCreateButton(GridLayout.RowHelper rows, LayoutElement child, int columns) {
            if (child instanceof Button button) {
                this.rewind$createButton = button;
            }
            return rows.addChild(child, columns);
        }

        /**
         * 摆第一排：三颗等宽、间隔 4，整排与原版同宽同高。
         *
         * <p>在 {@code init} 的尾巴上摆位是安全的：原版的 {@code layout.arrangeElements()} 在
         * {@code init} 里更早跑完；窗口缩放会重新走一遍 {@code init}，那时这里也会再摆一次。
         */
        @Inject(method = "init()V", at = @At("TAIL"))
        private void rewind$layOutFirstRow(CallbackInfo ci) {
            Button button = Button.builder(Component.translatable("rewind.repair.button"), WorldRepair.PRESS)
                    .bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build();
            this.addRenderableWidget(button);
            // init 末尾那次 updateButtonStatus(null) 跑在我们前面，所以初始一定是关的
            button.active = false;
            this.rewind$repairButton = button;

            if (this.playWorldButton == null || this.rewind$createButton == null) {
                // 理论上到不了：两颗都在 init 里建好了。真到了就只留我们一颗在中间，不影响别的按钮
                button.setX(this.width / 2 - BUTTON_WIDTH / 2);
                button.setY(this.height - ROW_BOTTOM_OFFSET);
                return;
            }
            int y = this.height - ROW_BOTTOM_OFFSET;
            int x = this.width / 2 - ROW_LEFT_INSET;
            this.playWorldButton.setX(x);
            this.playWorldButton.setY(y);
            this.playWorldButton.setWidth(BUTTON_WIDTH);
            x += BUTTON_WIDTH + BUTTON_GAP;
            button.setX(x);
            button.setY(y);
            x += BUTTON_WIDTH + BUTTON_GAP;
            this.rewind$createButton.setX(x);
            this.rewind$createButton.setY(y);
            this.rewind$createButton.setWidth(BUTTON_WIDTH);
        }

        @Inject(method = "updateButtonStatus", at = @At("TAIL"))
        private void rewind$refreshRepairButton(@Nullable LevelSummary summary, CallbackInfo ci) {
            WorldRepair.rememberSelection(summary);
            if (this.rewind$repairButton != null) {
                this.rewind$repairButton.active = WorldRepair.canRepair(summary);
            }
        }
    }
}
