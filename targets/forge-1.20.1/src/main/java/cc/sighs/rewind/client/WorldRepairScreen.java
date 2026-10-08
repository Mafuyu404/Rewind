package cc.sighs.rewind.client;

import java.nio.file.Path;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.api.RewindResult;

/**
 * 「用存档点修复这个世界」的界面：确认目标存档点 → 跑还原 → 把结果说出来。
 *
 * <p>它是**世界选择界面之上**的一个界面，干的是纯文件活，所以要人确认一下——这一下会用存档点覆盖
 * 磁盘上的世界文件。还原跑在后台线程上（拷大存档要几秒，占着渲染线程会把窗口冻住），结果回到渲染线程
 * 再显示。
 *
 * <p>与模组别处「界面不放提示、只写日志」的规矩不同，这个界面**必须**把状态说出来：它是玩家主动
 * 打开的修复入口，做完那一下不告诉人，玩家只能去翻日志。所以它显示三行——目标存档点、会覆盖什么、
 * 以及进行到哪一步/结果；聊天框与提示条一概不碰，日志照写。
 */
public final class WorldRepairScreen extends Screen {
    /** 正文颜色。 */
    private static final int TEXT_COLOR = 0xFFFFFF;
    /** 次要信息（目标存档点）。 */
    private static final int DIM_COLOR = 0xA0A0A0;
    /** 警告与失败。 */
    private static final int WARN_COLOR = 0xFF8080;

    /** 这个界面当前处在哪一步。 */
    private enum Phase {
        /** 等玩家确认。 */
        READY,
        /** 后台线程正在回拷文件。 */
        RUNNING,
        /** 还原成功。 */
        DONE,
        /** 还原失败（含冷却中被拒）。 */
        FAILED
    }

    private final Screen parent;
    private final Path worldRoot;
    private final String slot;
    /** 目标存档点的一行描述，构造时就定下来——它在整个流程里不会变。 */
    private final String target;

    private Phase phase = Phase.READY;
    @Nullable private Component status;
    @Nullable private Button startButton;

    public WorldRepairScreen(Screen parent, Path worldRoot, String worldName, String slot) {
        super(Component.translatable("rewind.repair.title", worldName));
        this.parent = parent;
        this.worldRoot = worldRoot;
        this.slot = slot;
        this.target = WorldRepair.describeTarget(worldRoot, slot);
    }

    @Override
    protected void init() {
        this.startButton = this.addRenderableWidget(
                Button.builder(Component.translatable("rewind.repair.start"), button -> this.startRestore())
                        .bounds(this.width / 2 - 154, this.height - 32, 150, 20)
                        .build());
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
                        .bounds(this.width / 2 + 4, this.height - 32, 150, 20)
                        .build());
        this.refreshButtons();
    }

    /** 按下「开始还原」：把活丢给后台线程，界面留在原地显示进度。 */
    private void startRestore() {
        if (this.phase == Phase.RUNNING) {
            return;
        }
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            Rewind.LOGGER.warn("Rewind: no client, cannot repair {} from checkpoint {}", this.worldRoot, this.slot);
            return;
        }
        this.phase = Phase.RUNNING;
        this.status = Component.translatable("rewind.repair.running");
        this.refreshButtons();
        Thread worker = new Thread(() -> this.restore(minecraft), "rewind-repair");
        worker.setDaemon(true);
        worker.start();
    }

    /** 后台线程：纯文件操作，一个界面字段都不改。 */
    private void restore(Minecraft minecraft) {
        String summary = "";
        String failure = null;
        try {
            RewindResult result = RewindApi.restoreFiles(this.worldRoot, this.slot);
            if (result.success) {
                summary = result.summary;
            } else {
                failure = String.valueOf(result.failure);
            }
        } catch (Throwable t) {
            failure = String.valueOf(t);
        }
        final String doneSummary = summary;
        final String doneFailure = failure;
        // 回渲染线程再动界面状态
        minecraft.execute(() -> this.finish(doneSummary, doneFailure));
    }

    private void finish(String summary, String failure) {
        if (failure == null) {
            this.phase = Phase.DONE;
            this.status = Component.translatable("rewind.repair.done", summary);
            Rewind.LOGGER.info("Rewind: repaired \"{}\" from checkpoint {} ({})",
                    this.worldRoot.getFileName(), this.slot, summary);
        } else {
            this.phase = Phase.FAILED;
            this.status = Component.translatable("rewind.repair.failed", failure);
            Rewind.LOGGER.error("Rewind: repairing \"{}\" from checkpoint {} failed: {}",
                    this.worldRoot.getFileName(), this.slot, failure);
        }
        this.refreshButtons();
    }

    /** 「开始还原」只在还没动手时可用；一旦开始就不给再点一次。 */
    private void refreshButtons() {
        if (this.startButton != null) {
            this.startButton.active = this.phase == Phase.READY;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.20.1 的背景是每个界面自己画的（Screen.render 只管控件），所以这里先补上
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int center = this.width / 2;
        int y = this.height / 2 - 50;
        graphics.drawCenteredString(this.font, this.title, center, y, TEXT_COLOR);
        graphics.drawCenteredString(this.font,
                Component.translatable("rewind.repair.target", this.target), center, y + 20, DIM_COLOR);
        graphics.drawCenteredString(this.font,
                Component.translatable("rewind.repair.warning"), center, y + 36, WARN_COLOR);
        if (this.status != null) {
            graphics.drawCenteredString(this.font, this.status, center, y + 64,
                    this.phase == Phase.FAILED ? WARN_COLOR : TEXT_COLOR);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
