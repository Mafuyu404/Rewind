package cc.sighs.mixin;

import javax.annotation.Nullable;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import cc.sighs.rewind.client.WorldRepair;

/**
 * 世界选择界面上的「用存档点修复这个世界」——进世界之前就能用的那条恢复入口。
 *
 * <p><b>为什么落在底部按钮区，而不是存档行上</b>：1.21.1 的存档行（{@code WorldSelectionList$WorldListEntry}）
 * 里一颗按钮都没有，「进入世界 / 编辑 / 删除 / 重建」全在 {@code SelectWorldScreen.init()} 建的底部按钮上，
 * 作用对象是当前选中的那一行。这颗按钮跟着同样的规矩来，所以它只在选中了某个存档之后才可用（与
 * 「进入世界」那颗一致的语义）。
 *
 * <p><b>位置算在列表与底部按钮之间的空档里</b>：1.21.1 传给列表的高度是 {@code height - 112}，
 * 而底部第一排按钮在 {@code height - 52}，中间那 60 像素原本是空的——所以这颗按钮不动原版任何一颗
 * 按钮的坐标。（1.20.1 没有这个空档，那边的实现要把底部两排整体上移，见 forge / fabric target。）
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
        /** 我们那颗按钮。 */
        @Unique
        @Nullable
        private Button rewind$repairButton;

        protected SelectWorldRepair() {
            super(Component.empty());
        }

        @Inject(method = "init()V", at = @At("TAIL"))
        private void rewind$addRepairButton(CallbackInfo ci) {
            Button button = Button.builder(Component.translatable("rewind.repair.button"), WorldRepair.PRESS)
                    .bounds(this.width / 2 - 154, this.height - 76, 308, 20)
                    .build();
            this.addRenderableWidget(button);
            // init 末尾那次 updateButtonStatus(null) 跑在我们前面，所以初始一定是关的
            button.active = false;
            this.rewind$repairButton = button;
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
