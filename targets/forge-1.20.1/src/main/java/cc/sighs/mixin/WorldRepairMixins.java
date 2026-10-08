package cc.sighs.mixin;

import javax.annotation.Nullable;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import cc.sighs.rewind.client.WorldRepair;

/**
 * 世界选择界面上的「用存档点修复这个世界」——进世界之前就能用的那条恢复入口。
 *
 * <p><b>为什么落在底部按钮区，而不是存档行上</b>：1.20.1 的存档行（{@code WorldSelectionList$WorldListEntry}）
 * 里一颗按钮都没有——它只画图标与三行文字，「进入世界 / 编辑 / 删除 / 重建」全在
 * {@code SelectWorldScreen.init()} 建的底部按钮上，作用对象是当前选中的那一行。这颗按钮跟着同样的
 * 规矩来，所以它只在选中了某个存档之后才可用（与「进入世界」那颗一致的语义）。
 *
 * <p><b>它是顶部那排下面新加的一排</b>：1.20.1 的底部两排（{@code height - 52} 与 {@code height - 28}，
 * 各占 308 像素宽）已经排满，没有空档可插，所以这一处把原版那两排整体上移 24 像素、把列表跟着
 * 收短 24 像素（{@code height - 64} → {@code height - 88}，保持原版那 12 像素间距），最下面那排
 * 才是我们。1.21.1 / 26.1 那边列表与按钮之间本来就有空档，不需要挪原版的按钮。
 *
 * <p><b>可用状态来自 {@code tick()}</b>：1.20.1 的 {@code updateButtonStatus(boolean, boolean)} 只收
 * 两个布尔量，拿不到 {@code LevelSummary}，所以这里每 tick 对一次当前选中那一行；选中项没变就直接
 * 返回，不重复读索引文件。
 */
public final class WorldRepairMixins {
    private WorldRepairMixins() {
    }

    /** 存档行里那份存档信息是 private 的，借 {@code @Accessor} 读出来。 */
    @Mixin(WorldSelectionList.WorldListEntry.class)
    public interface WorldListEntryAccess {
        @Accessor("summary")
        LevelSummary rewind$summary();
    }

    /**
     * 内层 mixin 继承目标的父类（{@code Screen}），这样 {@code addRenderableWidget} 这个 protected
     * 方法才调得到——mixin 类与 Screen 不同包、也不是它的子类时编不过，而 {@code @Invoker} 那条路
     * 在 0.8.5 的注解处理器上对「带参数的方法」解析不出来（它拿点号名字比对描述符形式的签名）。
     * 构造器只是为了让 javac 满意，Mixin 不会把它合并进目标类。
     */
    @Mixin(SelectWorldScreen.class)
    public abstract static class SelectWorldRepair extends Screen {
        /** 底部两排整体上移这么多像素，给最下面那排（我们那颗）腾地方。 */
        private static final int FOOTER_LIFT = 24;

        /** 原版那个存档列表（private）。 */
        @Shadow
        private WorldSelectionList list;

        /** 我们那颗按钮。 */
        @Unique
        @Nullable
        private Button rewind$repairButton;
        /** 上一次算过可用状态的那个存档目录名，用来跳过没变化的重算。 */
        @Unique
        private String rewind$selectedId = "";

        protected SelectWorldRepair() {
            super(Component.empty());
        }

        /**
         * 底部那 6 颗按钮的 y 全部上移。
         *
         * <p>它们都是 {@code Button.builder(...).bounds(x, y, w, h).build()} 建出来的，所以一个
         * {@code @Redirect} 就把 6 处一起挪了——不需要逐颗去改坐标。
         */
        @Redirect(
                method = "init()V",
                at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/client/gui/components/Button$Builder;bounds(IIII)Lnet/minecraft/client/gui/components/Button$Builder;"))
        private Button.Builder rewind$liftFooter(Button.Builder builder, int x, int y, int width, int height) {
            return builder.bounds(x, y - FOOTER_LIFT, width, height);
        }

        /**
         * 列表跟着收短，免得最下面那排按钮压到列表上（原版把列表留到 {@code height - 64}）。
         *
         * <p>挂点写成「构造器调用的完整描述符」而不是 {@code @At("NEW")}：后者更适合配合
         * {@code @Redirect}，改参数用 INVOKE 形态最稳。
         */
        @ModifyArg(
                method = "init()V",
                at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList;<init>"
                                + "(Lnet/minecraft/client/gui/screens/worldselection/SelectWorldScreen;"
                                + "Lnet/minecraft/client/Minecraft;IIIIILjava/lang/String;"
                                + "Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList;)V"),
                index = 5)
        private int rewind$shortenList(int bottom) {
            return bottom - FOOTER_LIFT;
        }

        @Inject(method = "init()V", at = @At("TAIL"))
        private void rewind$addRepairButton(CallbackInfo ci) {
            Button button = Button.builder(Component.translatable("rewind.repair.button"), WorldRepair.PRESS)
                    .bounds(this.width / 2 - 154, this.height - 28, 308, 20)
                    .build();
            this.addRenderableWidget(button);
            button.active = false;
            this.rewind$repairButton = button;
        }

        /**
         * 每 tick 对一次「当前选中哪一行」：变了才重算可用状态（那要读一次存档点索引）。
         *
         * <p>放在 {@code tick} 而不是 {@code updateButtonStatus} 上，是因为 1.20.1 那个方法只收两个
         * 布尔量、拿不到存档；{@code SelectWorldScreen} 自己就重写了 {@code tick()}（喂搜索框），
         * 挂在它的尾巴上最省事。
         */
        @Inject(method = "tick()V", at = @At("TAIL"))
        private void rewind$refreshRepairButton(CallbackInfo ci) {
            if (this.rewind$repairButton == null) {
                return;
            }
            // 不用 lambda / 匿名类：它们会编成挂在 mixin 类上的合成成员，合并进目标类之后不保证还对得上
            WorldSelectionList.WorldListEntry selected = this.list == null
                    ? null
                    : this.list.getSelectedOpt().orElse(null);
            // WorldListEntry 是 final 类，直接往接口转编不过：先走 Object
            LevelSummary summary = selected == null
                    ? null
                    : ((WorldListEntryAccess) (Object) selected).rewind$summary();
            String id = summary == null ? "" : summary.getLevelId();
            if (id.equals(this.rewind$selectedId)) {
                return;
            }
            this.rewind$selectedId = id;
            WorldRepair.rememberSelection(summary);
            this.rewind$repairButton.active = WorldRepair.canRepair(summary);
        }
    }
}
