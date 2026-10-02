package cc.sighs.rewind.client;

import com.mojang.blaze3d.platform.InputConstants;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.api.RewindApi;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 客户端侧的注册入口。只在物理客户端加载（由主类 {@code RewindForge} 用
 * {@code FMLEnvironment.dist} 判定后才调用），这样专用服务器不会因为引用客户端类而炸掉。
 *
 * <p>与 NeoForge 1.21.1 那份的差别全在「事件从哪来」——1.20.1 的 Forge 没有 NeoForge 那套
 * {@code RenderFrameEvent} / {@code ClientTickEvent.Post}：
 * <ul>
 *   <li>{@code ClientTickEvent.Post} → {@code TickEvent.ClientTickEvent} + {@code Phase.END}（游戏总线）；</li>
 *   <li>{@code RenderFrameEvent.Pre} → {@code TickEvent.RenderTickEvent} + {@code Phase.START}（游戏总线）；</li>
 *   <li>{@code RenderFrameEvent.Post} → {@code TickEvent.RenderTickEvent} + {@code Phase.END}（游戏总线）；</li>
 *   <li>{@code ScreenEvent.Opening} / {@code InputEvent.Key} / {@code RegisterCommandsEvent}
 *       三个类同名同语义，只是包名从 {@code net.neoforged.neoforge.*.event} 换成
 *       {@code net.minecraftforge.*.event}，都挂在游戏总线上；</li>
 *   <li>{@code RegisterKeyMappingsEvent} 同名，挂在模组事件总线上；</li>
 *   <li>Forge 的事件总线没有 NeoForge 那种 {@code addListener(Class&lt;T&gt;, Consumer&lt;T&gt;)} 重载，
 *       所以这里统一用 {@code addListener(EventPriority, boolean, Class&lt;T&gt;, Consumer&lt;T&gt;)}
 *       这个显式点名事件类的形式；需要优先级（抓封面）时也就有地方写。</li>
 * </ul>
 *
 * <p><b>为什么 {@code RenderTickEvent} 能替代 {@code RenderFrameEvent}</b>：Forge 1.20.1 在
 * {@code Minecraft.runTick(boolean)} 里两处各 post 一次它——
 * {@code onRenderTickStart} 在 {@code gameRenderer.render(...)} **之前**（这一帧还没开始画），
 * {@code onRenderTickEnd} 在它**之后**、而 {@code mainRenderTarget.blitToScreen(...)} **之前**。
 * 也就是说 {@code Phase.END} 那一刻：整帧（世界 + 界面 + HUD）都画进主渲染目标了，但还没上屏。
 * 抓封面要的正是这一刻（抓到的就是完整的这一帧），过渡后处理要的也正是这一刻（画进主目标，
 * 随后被原版 blit 到屏幕上）。语义与 NeoForge 的 {@code RenderFrameEvent.Pre/Post} 一一对应。
 */
public final class RewindClient {
    private static final String CATEGORY = "key.categories.rewind";
    private static final int SNAPSHOT_REQUEST = 1;
    private static final int RESTORE_REQUEST = 2;
    private static final int TREE_REQUEST = 3;

    private static KeyMapping snapshotKey;
    private static KeyMapping restoreKey;
    private static KeyMapping treeKey;
    /** 待执行的热键请求：0 = 没有，见 {@link #flushPendingRequest()}。 */
    private static int pendingRequest;

    private RewindClient() {
    }

    public static void setup(IEventBus modBus) {
        // 过渡参数（两种效果的所有可调项）落在 run/config/rewind-client.toml
        RewindClientConfig.register(modBus);
        // 把「带过渡的异步入口」接到状态机上：RewindApi.requestCheckpoint/requestRollback 从此等价于按 F7/F8
        CheckpointController.installApiBridge();
        modBus.addListener(RewindClient::onRegisterKeyMappings);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                TickEvent.ClientTickEvent.class, RewindClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                RegisterCommandsEvent.class, RewindCommands::register);
        // 存档点的封面：建点的人（服务端线程）留一条待办，这里在下一帧把画面抓下来。
        // 帧开始这一下要把当前界面与 HUD 临时摘掉，所以必须排在这一帧渲染之前——RenderTickEvent 的
        // START 正是 gameRenderer.render 之前那一点。
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                TickEvent.RenderTickEvent.class, RewindClient::onRenderTickStart);
        // 抓帧要在 HIGHEST：必须抢在下面的过渡后处理之前读那一帧，否则封面会带上存档过渡的饱和度
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false,
                TickEvent.RenderTickEvent.class, RewindClient::onRenderTickEndCapture);
        // 过渡后处理：每帧结束时重采样整幅画面（存档饱和度 / 回溯高斯模糊）
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                TickEvent.RenderTickEvent.class, RewindClient::onRenderTickEndTransition);
        // 过渡期间拦掉原版加载屏，保证全程不出现任何界面
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                ScreenEvent.Opening.class, RewindScreens::onScreenOpening);
        // F7 / F8 / F9 走原始按键输入，而不是 KeyMapping 的点击计数：原版只在没有界面时才累计
        // 点击（KeyboardHandler 里 flag4 = screen == null），界面开着就永远收不到。
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                InputEvent.Key.class, RewindClient::onKeyInput);
    }

    public static KeyMapping snapshotKey() {
        return snapshotKey;
    }

    public static KeyMapping restoreKey() {
        return restoreKey;
    }

    public static KeyMapping treeKey() {
        return treeKey;
    }

    /** 帧开始 / 帧结束共用同一个事件类，靠 {@code Phase} 区分；这里按相位分流。 */
    private static void onRenderTickStart(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        CoverCapture.onFramePre(Minecraft.getInstance());
    }

    private static void onRenderTickEndCapture(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        CoverCapture.onFramePost(Minecraft.getInstance());
    }

    private static void onRenderTickEndTransition(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        RewindTransitionRenderer.onRenderFramePost(Minecraft.getInstance());
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        snapshotKey = new KeyMapping("key.rewind.snapshot", InputConstants.KEY_F7, CATEGORY);
        restoreKey = new KeyMapping("key.rewind.restore", InputConstants.KEY_F8, CATEGORY);
        treeKey = new KeyMapping("key.rewind.tree", InputConstants.KEY_F9, CATEGORY);
        event.register(snapshotKey);
        event.register(restoreKey);
        event.register(treeKey);
        Rewind.LOGGER.info("Rewind: registered key mappings (F7=checkpoint, F8=restore, F9=time tree)");
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        CheckpointController.tick(minecraft);
        // 端到端自测（-Drewind.selftest=true）：阶段机挂在客户端 tick 上；没打开时它自己直接返回
        RewindSelfTest.tick(minecraft);
        flushPendingRequest();
    }

    /**
     * 原始按键：无论有没有界面都会走到这里（原版只在 {@code screen == null} 时给 KeyMapping 累计点击），
     * 所以 F7 / F8 / F9 在世界内任何地方都能生效，包括 GUI 里。
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
        } else if (treeKey != null && treeKey.matches(event.getKey(), event.getScanCode())) {
            pendingRequest = TREE_REQUEST;
        }
    }

    private static void flushPendingRequest() {
        if (pendingRequest == 0 || RewindTransition.isActive() || RewindApi.isBusy()) {
            return;
        }
        int request = pendingRequest;
        pendingRequest = 0;
        if (request == SNAPSHOT_REQUEST) {
            RewindApi.requestCheckpoint("key");
        } else if (request == RESTORE_REQUEST) {
            RewindApi.requestRollback("key");
        } else {
            RewindTreeScreen.open();
        }
    }
}
