package cc.sighs.mixin;

import org.jetbrains.annotations.Nullable;
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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import cc.sighs.rewind.client.WorldRepair;

/**
 * 世界选择界面上的「读取存档点」——进世界之前就能打开的那条回溯入口（打开的是完整的回溯页面，见 {@code RewindTreeScreen} 的离线模式）。
 *
 * <p><b>第一排三颗等宽</b>：原版第一排是 {@code [进入世界 150][创建新的世界 150]}（整排 308 像素宽），
 * 这里把两颗都收成 100 宽，中间插进「读取存档点」——于是
 * {@code [进入世界 100][读取存档点 100][创建新的世界 100]}，三颗等宽、间隔 4 像素
 * （3 * 100 + 2 * 4 = 308），整排总宽与原来一样，也不用挪动下面那排或改列表高度。
 * 三颗等宽之后第一排的竖缝不会与第二排那四颗（都是 72 宽）对齐——这是等宽的必然结果。
 *
 * <p><b>为什么是底部按钮区</b>：1.20.1 的存档行（{@code WorldSelectionList$WorldListEntry}）里一颗
 * 按钮都没有——它只画图标与三行文字，「进入世界 / 编辑 / 删除 / 重建」全在
 * {@code SelectWorldScreen.init()} 建的底部按钮上，作用对象是当前选中的那一行。这颗按钮跟着同样的
 * 规矩来，所以它只在选中了某个存档之后才可用（与「进入世界」那颗一致的语义）。
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
        /** 第一排三颗按钮的宽度。3 * 100 + 2 * 4 = 308，与原版第一排的总宽一致。 */
        private static final int BUTTON_WIDTH = 100;
        /** 第一排到屏幕底边的距离，与原版一致（原版那两颗在 {@code height - 52}）。 */
        private static final int ROW_BOTTOM_OFFSET = 52;
        private static final int BUTTON_HEIGHT = 20;

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

        /** 「进入世界」收成等宽的一格：它原本 150 宽，左边缘不动。 */
        @Redirect(
                method = "init()V",
                at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/client/gui/components/Button$Builder;bounds(IIII)Lnet/minecraft/client/gui/components/Button$Builder;",
                        ordinal = 0))
        private Button.Builder rewind$shrinkSelectButton(Button.Builder builder, int x, int y, int width, int height) {
            return builder.bounds(x, y, BUTTON_WIDTH, height);
        }

        /** 「创建新的世界」收成等宽的一格并右移：右边缘不动，空出来的那格给「读取存档点」。 */
        @Redirect(
                method = "init()V",
                at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/client/gui/components/Button$Builder;bounds(IIII)Lnet/minecraft/client/gui/components/Button$Builder;",
                        ordinal = 1))
        private Button.Builder rewind$shrinkCreateButton(Button.Builder builder, int x, int y, int width, int height) {
            return builder.bounds(x + width - BUTTON_WIDTH, y, BUTTON_WIDTH, height);
        }

        @Inject(method = "init()V", at = @At("TAIL"))
        private void rewind$addRepairButton(CallbackInfo ci) {
            // 我们占中间那一格：整排左右对称，左右各留一格给「进入世界 / 创建新的世界」
            Button button = Button.builder(Component.translatable("rewind.repair.button"), WorldRepair.PRESS)
                    .bounds(this.width / 2 - BUTTON_WIDTH / 2, this.height - ROW_BOTTOM_OFFSET,
                            BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build();
            this.addRenderableWidget(button);
            // init 末尾那次 updateButtonStatus(false, false) 跑在我们前面，所以初始一定是关的
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
