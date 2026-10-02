package cc.sighs.rewind.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 一个「什么都不画」的逻辑屏。
 *
 * <p>它不是给玩家看的界面，而是原版状态机的一个前提：原版在「没有世界」时总是挂着某个加载屏，
 * 因此 {@code Minecraft.tick()} 里处处假设「没有界面就一定有玩家」（例如
 * {@code handleKeybinds} 里直接访问 {@code this.player.isUsingItem()}）。回溯要经历一段
 * 没有 {@code ClientLevel} 的时间，如果那时既没有世界也没有界面，就会在那些假设上崩掉。
 *
 * <p>所以过渡期间挂上它：不画任何东西、不接管输入、也不暂停世界
 * （{@code isPauseScreen()} 返回 false），画面完全交给
 * {@link RewindTransitionRenderer} 的后处理效果。世界一回来就把它摘掉。
 *
 * <p><b>与 NeoForge 1.21.1 的差异</b>：1.20.1 的 {@code Screen} 只有
 * {@code renderBackground(GuiGraphics)} 这一个（没有 1.21.1 那个带鼠标坐标与 partialTick 的三参数重载），
 * 所以压掉背景改成重写这一个。
 */
public class RewindBlankScreen extends Screen {
    public RewindBlankScreen() {
        super(Component.empty());
    }

    @Override
    protected void init() {
        // 不需要任何控件
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 什么都不画：过渡画面由后处理负责
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 也压掉原版的背景（没有世界时它会把全景图铺上来）
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
