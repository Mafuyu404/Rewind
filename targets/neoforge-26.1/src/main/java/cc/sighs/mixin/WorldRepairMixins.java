package cc.sighs.mixin;

import javax.annotation.Nullable;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
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
 * 世界选择界面上的「用存档点修复这个世界」——进世界之前就能用的那条恢复入口。
 *
 * <p><b>为什么落在底部按钮区，而不是存档行上</b>：26.1 的存档行（{@code WorldSelectionList$WorldListEntry}）
 * 里只有三个 {@code StringWidget}，一颗按钮都没有；「进入世界 / 编辑 / 删除 / 重建」全在 footer 那个
 * 4 列 {@code GridLayout} 里，作用对象是当前选中的那一行。这颗按钮跟着同样的规矩来，所以它只在选中了
 * 某个存档之后才可用（与「进入世界」那颗一致的语义）。
 *
 * <p><b>它是一整排新的 footer 行</b>：footer 里那 8 颗按钮正好占满两排，所以这颗加进去就是第三排；
 * 原版给 footer 留的高度是按两排算的（{@code HeaderAndFooterLayout(this, …, 60)}），三排要 68，所以
 * 顺手把 footer 高度放宽到 84（多留出原版那样的上下各 8 像素），再让布局重排一次——列表高度因此
 * 短 24 像素，这是这一处唯一的取舍。
 *
 * <p><b>可用状态与目标存档都从 {@code updateButtonStatus(LevelSummary)} 来</b>：那是原版在选中项变化时
 * 走的钩子，参数就是当前选中的 {@code LevelSummary}（没选中时为 null）。不自己每 tick 去读列表，是因为
 * 判断「这个世界有没有存档点」要读一次索引文件，挂在选中变化上才划算。
 */
public final class WorldRepairMixins {
    private WorldRepairMixins() {
    }

    /**
     * 内层 mixin 继承目标的父类（{@code Screen}），这样 {@code addRenderableWidget} 与
     * {@code repositionElements} 这些 protected 方法才调得到——mixin 类与 Screen 不同包、也不是它的
     * 子类时编不过，而 {@code @Invoker} 那条路在 0.8.5 的注解处理器上对「带参数的方法」解析不出来
     * （它拿点号名字比对描述符形式的签名）。构造器只是为了让 javac 满意，Mixin 不会把它合并进目标类。
     */
    @Mixin(SelectWorldScreen.class)
    public abstract static class SelectWorldRepair extends Screen {
        /** 三排按钮（3 * 20 + 2 * 4 = 68）外加原版那样的上下各 8 像素留白。 */
        private static final int REPAIR_FOOTER_HEIGHT = 84;

        /** 原版那个 header/footer 布局（footer 高度就存在它身上）。 */
        @Shadow
        private HeaderAndFooterLayout layout;

        /** footer 那个 4 列网格的行助手；建按钮时截下来，稍后把我们那颗也加进去。 */
        @Unique
        @Nullable
        private GridLayout.RowHelper rewind$footerRows;
        /** 我们那颗按钮。 */
        @Unique
        @Nullable
        private Button rewind$repairButton;

        protected SelectWorldRepair() {
            super(Component.empty());
        }

        /**
         * 截住 footer 的网格：它是个局部变量，光靠 {@code init} 的尾巴拿不到，所以在这里把行助手留下来。
         */
        @Redirect(
                method = "createFooterButtons",
                at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/client/gui/layouts/GridLayout;createRowHelper(I)Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;"))
        private GridLayout.RowHelper rewind$captureFooterRows(GridLayout footer, int columns) {
            GridLayout.RowHelper rows = footer.createRowHelper(columns);
            this.rewind$footerRows = rows;
            return rows;
        }

        @Inject(method = "init()V", at = @At("TAIL"))
        private void rewind$addRepairButton(CallbackInfo ci) {
            if (this.rewind$footerRows == null) {
                return;
            }
            Button button = Button.builder(Component.translatable("rewind.repair.button"), WorldRepair.PRESS).build();
            this.rewind$footerRows.addChild(button);
            this.layout.setFooterHeight(REPAIR_FOOTER_HEIGHT);
            this.addRenderableWidget(button);
            // init 末尾那次 updateButtonStatus(null) 跑在我们前面，所以初始一定是关的
            button.active = false;
            this.rewind$repairButton = button;
            this.repositionElements();
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
