package cc.sighs.rewind.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 存档点操作的进度屏。
 *
 * <p>基类 {@link Screen#isPauseScreen()} 默认返回 true，因此在存档点写入期间集成服务器会被暂停
 * （{@code Minecraft.pause} → {@code IntegratedServer.paused}），世界不再 tick、不再产生新写入，
 * 后台线程可以放心地镜像存档目录。客户端 tick / 渲染不受影响，所以进度可以实时刷新。
 */
public class RewindProgressScreen extends Screen {
    public RewindProgressScreen() {
        super(Component.translatable("rewind.screen.title"));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        graphics.drawCenteredString(this.font, CheckpointController.statusComponent(), centerX, centerY - 34, 0xFFFFFF);

        int barWidth = Math.max(120, Math.min(320, this.width - 80));
        int left = centerX - barWidth / 2;
        int top = centerY - 8;
        graphics.fill(left - 1, top - 1, left + barWidth + 1, top + 13, 0xFF000000);
        graphics.fill(left, top, left + barWidth, top + 12, 0xFF303030);
        int percent = CheckpointController.progressPercent();
        if (percent >= 0) {
            graphics.fill(left, top, left + barWidth * percent / 100, top + 12, 0xFF55FF55);
        }

        Component detail = CheckpointController.detailComponent();
        if (detail != null) {
            graphics.drawCenteredString(this.font, detail, centerX, centerY + 22, 0xFFAAAAAA);
        }
    }
}
