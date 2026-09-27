package cc.sighs.rewind.client;

import com.mojang.blaze3d.platform.InputConstants;
import cc.sighs.rewind.Rewind;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 客户端侧的注册入口。只在物理客户端加载（由主类用 {@code FMLEnvironment.getDist()} 判定），
 * 这样专用服务器不会因为引用客户端类而炸掉。
 */
public final class RewindClient {
    private static final String CATEGORY = "key.categories.rewind";
    private static final int SNAPSHOT_REQUEST = 1;
    private static final int RESTORE_REQUEST = 2;

    private static KeyMapping snapshotKey;
    private static KeyMapping restoreKey;
    /** 待执行的热键请求：0 = 没有，见 {@link #flushPendingRequest()}。 */
    private static int pendingRequest;

    private RewindClient() {
    }

    public static void setup(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, RewindClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, RewindClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, RewindCommands::register);
        // 过渡后处理：每帧结束时重采样整幅画面（存档广角 / 回溯高斯模糊）
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, RewindClient::onRenderFramePost);
        // 过渡期间拦掉原版加载屏，保证全程不出现任何界面
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, RewindScreens::onScreenOpening);
        // F7 / F8 走原始按键输入，而不是 KeyMapping 的点击计数：原版只在没有界面时才累计
        // 点击（KeyboardHandler 里 flag4 = screen == null），界面开着就永远收不到。
        NeoForge.EVENT_BUS.addListener(InputEvent.Key.class, RewindClient::onKeyInput);
    }

    public static KeyMapping snapshotKey() {
        return snapshotKey;
    }

    public static KeyMapping restoreKey() {
        return restoreKey;
    }

    private static void onRenderFramePost(RenderFrameEvent.Post event) {
        RewindTransitionRenderer.onRenderFramePost(Minecraft.getInstance());
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        snapshotKey = new KeyMapping("key.rewind.snapshot", InputConstants.KEY_F7, CATEGORY);
        restoreKey = new KeyMapping("key.rewind.restore", InputConstants.KEY_F8, CATEGORY);
        event.register(snapshotKey);
        event.register(restoreKey);
        Rewind.LOGGER.info("Rewind: registered key mappings (F7=checkpoint, F8=restore)");
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        CheckpointController.tick(minecraft);
        RewindSelfTest.tick(minecraft);
        flushPendingRequest();
    }

    /**
     * 原始按键：无论有没有界面都会走到这里（原版只在 {@code screen == null} 时给 KeyMapping 累计点击），
     * 所以 F7 / F8 在世界内任何地方都能生效，包括 GUI 里。
     *
     * <p>用 {@link KeyMapping#matches} 判定，所以玩家在按键设置里改绑依然有效。按下的时机如果正好撞上
     * 上一次过渡或还没结束的操作，就记下来等空下来再执行——等价于原版 {@code KeyMapping} 点击计数
     * 那种「攒着等界面关掉」的语义，而不是把按键直接丢掉。
     */
    private static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != InputConstants.PRESS) {
            return;
        }
        if (snapshotKey != null && snapshotKey.matches(event.getKey(), event.getScanCode())) {
            pendingRequest = SNAPSHOT_REQUEST;
        } else if (restoreKey != null && restoreKey.matches(event.getKey(), event.getScanCode())) {
            pendingRequest = RESTORE_REQUEST;
        }
    }

    private static void flushPendingRequest() {
        if (pendingRequest == 0 || RewindTransition.isActive() || CheckpointController.isBusy()) {
            return;
        }
        int request = pendingRequest;
        pendingRequest = 0;
        if (request == SNAPSHOT_REQUEST) {
            CheckpointController.requestSnapshot("key");
        } else {
            CheckpointController.requestRestore("key");
        }
    }
}
