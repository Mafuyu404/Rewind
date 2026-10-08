package cc.sighs.rewind.client;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.api.RewindResult;
import cc.sighs.rewind.server.RewindServerConfig;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import cc.sighs.rewind.snapshot.SnapshotUsage;
import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.KeyEvent;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.screen.ApricityScreen;
import com.sighs.apricityui.ui.Tooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import org.lwjgl.glfw.GLFW;

/**
 * 「时间树」界面：存档槽位的管理页（F9 / {@code /rewind ui}）。
 *
 * <p>页面是 {@code screens/rewind_screen.html}，但**没有任何页面脚本参与**——AUI 的页面
 * {@code <script>} 需要 KubeJS 才会执行，所以这里从 Java 侧整页驱动：{@code setInnerHTML} 铺数据、
 * {@code addEventListener} 接交互、{@code classList} 开关弹窗。模板里那些静态卡片只是「没接上数据
 * 时的样子」，每次渲染都会被整体替换。
 *
 * <p><b>读取</b>转交 {@link RewindApi#requestRollback}，也就是 F8 那条带过渡的管线。过渡是整帧后处理，
 * 界面开着会挡在效果上面，所以 {@code CheckpointController} 动手前会先把本界面摘掉——玩家看到的是
 * 「点一下 → 界面收起 → 世界糊住 → 换回来」。
 *
 * <p><b>覆盖不走那条路</b>：它要界面一直开着，所以 {@link #startBackgroundSave} 直接把
 * {@link RewindApi#createCheckpoint} 丢到服务端线程上跑（一致性保证和 F7 一样：那段时间服务端线程
 * 被占着，世界不 tick），做完由 {@link #tick()} 把结果收回来刷新界面——不放过渡、不动界面。
 * 封面照样抓，而且抓帧那一帧会把界面和 HUD 临时摘掉，所以封面里没有界面。
 *
 * <p>只有重命名和删除是就地完成的（纯文件操作，走 {@link RewindApi#renameCheckpoint} /
 * {@link RewindApi#deleteCheckpoint}），做完直接重画当前页。
 *
 * <p>页面有两套布局，由标题旁边的开关切换（{@code body.tree-mode}）：
 *
 * <ul>
 *   <li><b>档案布局</b>：自动 / 快速两张卡片 + 8 个手动槽位，一眼看清每个槽位里是什么。</li>
 *   <li><b>节点树布局</b>：同样的存档点，按 {@link SnapshotMeta#parentSlot} 拼成一条时间线，
 *       画在可以拖拽平移、滚轮缩放的流程图画布上——存档点之间的先后与派生关系在这里看得见。
 *       方向还能整体旋转 90°（{@code #treeView[data-dir]}），树长了就换个方向看。</li>
 * </ul>
 *
 * <p>两套布局共用同一个「选中槽位」，切过去切回来选的还是那一个；右侧详情面板始终跟着它。
 *
 * <p><b>与 NeoForge 1.21.1 的差异</b>：本体几乎逐行照搬——它只用 {@code Minecraft} /
 * {@code IntegratedServer} / {@code LocalPlayer} / {@code Language} / {@code Stats} 这些两边同形的
 * 原版 API，加上 AUI 的界面 API（{@code ApricityScreen} / {@code Document} / {@code Element} /
 * {@code Event}），没有一处框架事件。差异只在一处配件：「改键」打开的是 1.20.1 的
 * {@code ...screens.controls.KeyBindsScreen}（见 {@link RewindKeyBindsScreen}）；AUI 版本与另外
 * 三个 target 相同，没有版本差异。
 */
public final class RewindTreeScreen extends ApricityScreen {
    /** 模板路径，相对 AUI 的 {@code assets/apricityui/apricity/} 基准目录。 */
    private static final String TEMPLATE = "screens/rewind_screen.html";
    /**
     * 卡片封面颜色，按 {@link RewindApi#slots()} 的顺序取。
     *
     * <p>模板里封面就是纯色块（{@code .slot-cover} 的注释写了这件事），颜色直接写在行内样式上，
     * 所以这里只是一张调色板，没有截图、没有额外的资源文件。
     */
    private static final String[] COVER_COLORS = {
            "#1d3f5c", "#8f2410",
            "#5b2a63", "#141c26", "#0c4f68", "#2b1b3f", "#d99b5c", "#6b2f8f", "#3f4a24", "#55324a"
    };
    /** 快捷栏格数（原版物品栏的前 9 格）。折叠状态下只显示这一行。 */
    private static final int HOTBAR_SLOTS = 9;
    /** 主背包的格数（9 × 4，含快捷栏）。 */
    private static final int MAIN_INVENTORY_SLOTS = 36;

    /** 时间线的四个方向，与模板里 {@code #treeView[data-dir]} 的取值一一对应，按顺时针排。 */
    private static final String[] TREE_DIRS = {"down", "right", "up", "left"};
    /**
     * 「当前进度」这个额外节点的内部标记。
     *
     * <p>它不是一个槽位：以 {@code @} 开头，过不了 {@link SnapshotLayout#isValidSlotName}，
     * 所以永远不会和磁盘上的槽位撞名，也不会被写进索引。
     */
    private static final String NOW_NODE = "@now";
    /** 流程图画布四周留白：装得下就居中，装不下就贴着根节点那一端。 */
    private static final double FLOW_PADDING = 20.0D;
    /** 自动装树时允许缩到的最小倍数（再小就看不清卡片上的字了，剩下靠拖拽）。 */
    private static final double FLOW_FIT_MIN_SCALE = 0.5D;
    /** 滚轮缩放的范围。 */
    private static final double FLOW_ZOOM_MIN = 0.3D;
    private static final double FLOW_ZOOM_MAX = 2.5D;
    /** 画布高度 = 视口高度 − 这块（页面头、工具条、上下留白）。 */
    private static final double FLOW_CHROME_HEIGHT = 200.0D;
    /** 画布高度的下限：窗口再矮也得能看见一块画布。 */
    private static final double FLOW_MIN_CANVAS_HEIGHT = 240.0D;
    /** 视图刚显示时尺寸可能还是 0，最多等这么多 tick 再量一次。 */
    private static final int FLOW_FIT_TRIES = 30;
    /** 拖过这么多像素就不把 mouseup 之后的 click 当成点卡片。 */
    private static final double FLOW_DRAG_SLOP = 4.0D;

    private static final DateTimeFormatter ABSOLUTE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 列表排序方式，取值与模板 {@code #sort} 的 option value 一一对应。 */
    private enum SortMode {
        RECENT, OLDEST, NAME, PLAYTIME, INDEX;

        static SortMode of(String value) {
            if (value == null) {
                return INDEX;
            }
            switch (value) {
                case "recent":
                    return RECENT;
                case "oldest":
                    return OLDEST;
                case "name":
                    return NAME;
                case "playtime":
                    return PLAYTIME;
                default:
                    return INDEX;
            }
        }
    }

    private String selectedSlot = RewindApi.DEFAULT_SLOT;
    /** 死亡后会自动回溯到的槽位（时间线的头）；空 = 不标记（开关关着，或还没有节点）。 */
    private String deathTargetSlot = "";
    private String filter = "";
    /** 列表排序方式。默认按槽位序号；上次用的会记住（客户端配置 {@code [gui] sortMode}）。 */
    private SortMode sort = SortMode.of(RewindClientConfig.sortMode());
    /** 确认弹窗待执行的动作（{@code save} / {@code delete}）；null 表示弹窗没开。 */
    private String pendingAction;
    private String pendingSlot;
    /** 重命名弹窗正在编辑的槽位；null 表示弹窗没开。 */
    private String renamingSlot;
    /** 详情面板里的背包快照是否展开（默认折叠，只显示一行快捷栏）。 */
    private boolean inventoryExpanded;
    /** 正在后台写的槽位；null 表示没有写盘在进行。 */
    private String savingSlot;
    /** 服务端线程回填的写盘结果。 */
    private volatile RewindResult savingResult;
    /** 刚写完、还在等封面文件落地的槽位（封面比索引晚几十毫秒，见 {@link #flushWaitingCover}）。 */
    private String waitingCoverSlot;
    private long waitingCoverMillis;
    private int waitingCoverTicks;
    /** 等封面的上限（封面是抓帧之后异步落盘的，正常 2-6 tick 就好）。 */
    private static final int COVER_WAIT_TICKS = 20;

    // ------------------------------------------------------------------ 时间线视图的状态
    /** 现在是不是「节点树布局」（对应 body 上的 tree-mode）；默认档案布局，上次用的会记住。 */
    private boolean treeMode = RewindClientConfig.treeLayout();
    /** 时间线方向：{@link #TREE_DIRS} 里的一个；默认从上到下，上次用的会记住。 */
    private String treeDir = TREE_DIRS[indexOfDir(RewindClientConfig.treeDirection())];
    /** 流程图平移量（相对画布左上角，像素）。 */
    private double flowX;
    private double flowY;
    /** 流程图缩放倍数。 */
    private double flowScale = 1.0D;
    /** 按下时记下的光标位置与当时的平移量。 */
    private double flowDragX;
    private double flowDragY;
    private double flowDragOriginX;
    private double flowDragOriginY;
    private boolean flowDragging;
    /** 刚刚拖过画布：那一下松手带出来的 click 不算点卡片。 */
    private boolean flowMoved;
    /** 还没量到尺寸、等下一 tick 再装树的次数。 */
    private int flowFitTries;
    /** 需要重新装一次树（刚切过来 / 转了方向 / 点了重置 / 数据变了）。 */
    private boolean flowFitPending;
    /** 上一次铺进树里的节点数：变了才重新装一次视图，免得每次重画都把玩家的平移缩放抹掉。 */
    private int treeNodes = -1;
    /**
     * 「在此存档」那个节点这一次要写进的槽位（序号最小的空手动槽位）；全满了就是 null。
     *
     * <p>只在一次 {@link #renderTree} 里用：渲染前算好，节点卡片照着它决定标题与能不能点。
     */
    private String freeManualSlot;

    public RewindTreeScreen() {
        super(TEMPLATE);
    }

    /**
     * 平时把世界停住（管理界面不该让世界继续跑），**但正在后台写存档点时必须放行**：
     * 写盘任务跑在服务端线程上，世界停着它就永远不会被执行。
     */
    @Override
    public boolean isPauseScreen() {
        return savingSlot == null;
    }

    /** F9 / {@code /rewind ui}：打开时间树；已经开着就关掉。 */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RewindTreeScreen) {
            minecraft.setScreen(null);
            return;
        }
        if (!minecraft.hasSingleplayerServer() || minecraft.level == null || minecraft.player == null) {
            Rewind.LOGGER.warn("Rewind: the tree screen is only available in-game in a singleplayer world");
            return;
        }
        minecraft.setScreen(new RewindTreeScreen());
    }

    // ------------------------------------------------------------------ 装配

    /**
     * 文档每次重建（打开、以及窗口尺寸变化触发的 {@code resize} → {@code init}）都会走这里，
     * 所以监听器与数据都在这里重新挂一遍——旧文档已经被 AUI 丢掉，不会重复叠加。
     */
    @Override
    protected void init() {
        super.init();
        Document document = getLinkedDocument();
        if (document == null) {
            Rewind.LOGGER.error("Rewind: cannot open the tree screen, template {} was not found", TEMPLATE);
            return;
        }
        bind(document);
    }

    private void bind(Document document) {
        waitingCoverSlot = null;
        document.addEventListener("click", this::onClick);

        Element search = document.querySelector("#search");
        if (search != null) {
            search.setAttribute("placeholder", tr("rewind.ui.search_placeholder"));
            search.addEventListener("input", event -> {
                filter = search.getValue() == null ? "" : search.getValue();
                render(document);
            });
        }

        Element sortBox = document.querySelector("#sort");
        if (sortBox != null) {
            // option 的标签进不了 <translation>（getOptionLabel 只认 label 属性 / 文本），
            // 这里按 value 取语言文件塞进 label
            for (Element option : sortBox.querySelectorAll("option")) {
                String value = option.getAttribute("value");
                if (value != null) {
                    option.setOptionLabel(tr("rewind.ui.sort." + value));
                }
            }
            // 记住的排序方式要写回控件，不然会出现「控件显示 index、实际按 recent 排」
            sortBox.setValue(RewindClientConfig.sortMode());
            sortBox.addEventListener("input", event -> {
                sort = SortMode.of(sortBox.getValue());
                RewindClientConfig.setSortMode(sortBox.getValue());
                render(document);
            });
        }

        // 属性没法用 <translation>（那是元素），这几处只能在这里按语言文件设
        Element layoutGroup = document.querySelector(".layout-switch");
        if (layoutGroup != null) {
            layoutGroup.setAttribute("aria-label", tr("rewind.ui.layout.group"));
        }
        Element layoutSwitch = document.querySelector(".layout-switch .switch");
        if (layoutSwitch != null) {
            layoutSwitch.setAttribute("aria-label", tr("rewind.ui.layout.switch"));
        }
        Element rotateButton = document.querySelector("[data-act=\"rotate-tree\"]");
        if (rotateButton != null) {
            rotateButton.setAttribute("title", tr("rewind.ui.timeline.rotate"));
        }
        if (search != null) {
            search.setAttribute("aria-label", tr("rewind.ui.search"));
        }
        if (sortBox != null) {
            sortBox.setAttribute("aria-label", tr("rewind.ui.sort"));
        }
        Element renameBox = document.querySelector("#renameInput");
        if (renameBox != null) {
            renameBox.setAttribute("placeholder", tr("rewind.ui.modal.rename_placeholder"));
        }

        Element renameInput = document.querySelector("#renameInput");
        if (renameInput != null) {
            renameInput.addEventListener("keydown", event -> {
                if (event instanceof KeyEvent key && key.keyCode == GLFW.GLFW_KEY_ENTER) {
                    commitRename(document);
                }
            });
        }

        // 流程图画布：拖拽平移、滚轮以光标为中心缩放。四个监听都挂在画布上——
        // 按下之后 AUI 会把 mousemove / mouseup 重派发给「按下的那个元素」，
        // 所以光标拖到画布外面也不会丢掉这两个事件。
        Element canvas = document.querySelector("#flowCanvas");
        if (canvas != null) {
            canvas.addEventListener("wheel", this::onFlowWheel);
            canvas.addEventListener("mousedown", this::onFlowMouseDown);
            canvas.addEventListener("mousemove", this::onFlowMouseMove);
            canvas.addEventListener("mouseup", this::onFlowMouseUp);
        }
        // 文档每次重建（打开、窗口尺寸变化）都会走 bind：把布局与方向重新写回页面
        setTreeMode(document, treeMode);
        applyLayout(document);

        render(document);
    }

    @Override
    public void tick() {
        super.tick();
        flushFinishedSave();
        flushWaitingCover();
        fitFlow();
    }

    /** 后台写盘做完了就收尾：给一条日志，然后刷新界面（卡片上的时间、大小都会变）。 */
    private void flushFinishedSave() {
        String slot = savingSlot;
        RewindResult result = savingResult;
        if (slot == null || result == null) {
            return;
        }
        savingSlot = null;
        savingResult = null;
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        if (result.success) {
            log("rewind.ui.log.saved", title(slot, RewindApi.describe(worldRoot(), slot)));
        } else {
            log("rewind.ui.log.save_failed", String.valueOf(result.failure));
        }
        render(document);
        if (result.success && result.meta != null) {
            // 封面是抓帧之后异步落盘的，比索引晚几十毫秒：这一刻卡片会先退回纯色块，
            // 等文件出现再重画一次（没有这一步，覆盖完之后卡片要等玩家重开页面才有图）。
            waitingCoverSlot = slot;
            waitingCoverMillis = result.meta.savedAtMillis;
            waitingCoverTicks = COVER_WAIT_TICKS;
        }
    }

    /** 等封面文件落地，然后重画一次卡片。 */
    private void flushWaitingCover() {
        String slot = waitingCoverSlot;
        if (slot == null) {
            return;
        }
        Document document = getLinkedDocument();
        String world = worldDirName();
        if (document == null || world == null) {
            waitingCoverSlot = null;
            return;
        }
        if (CoverCapture.hasCover(world, slot, waitingCoverMillis)) {
            waitingCoverSlot = null;
            Rewind.LOGGER.info("Rewind: cover for {} landed, repainting the tree", slot);
            render(document);
            return;
        }
        if (--waitingCoverTicks <= 0) {
            waitingCoverSlot = null;
            Rewind.LOGGER.warn("Rewind: cover for {} never showed up; the card keeps the plain colour", slot);
        }
    }

    // ------------------------------------------------------------------ 交互

    private void onClick(Event event) {
        // 刚才是拖画布：松手带出来的这一下 click 不该被当成点卡片
        if (flowMoved) {
            flowMoved = false;
            return;
        }
        if (!(event.target instanceof Element target)) {
            return;
        }
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        // 点弹窗背景 = 关掉弹窗
        if (target.getClassList().contains("modal-backdrop")) {
            closeModals(document);
            return;
        }
        Element action = target.closest("[data-act]");
        if (action == null) {
            return;
        }
        String act = action.getDataset().get("act");
        String slot = action.getDataset().get("slot");
        if (act == null) {
            return;
        }
        switch (act) {
            case "select":
                if (slot != null) {
                    selectedSlot = slot;
                    render(document);
                }
                break;
            case "load":
                load(document, slot);
                break;
            case "save":
                askConfirm(document, "save", slot);
                break;
            case "delete":
                askConfirm(document, "delete", slot);
                break;
            case "rename":
                askRename(document, slot);
                break;
            case "settings":
                openSettings(document, slot);
                break;
            case "toggle-auto":
                toggleAutoCheckpoint(document);
                break;
            case "toggle-death":
                toggleDeathRollback(document);
                break;
            case "apply-interval":
                applyInterval(document);
                break;
            case "open-keys":
                openKeyBinds(document);
                break;
            case "toggle-inventory":
                // 背包快照：默认只显示一行快捷栏，点标题展开完整背包
                inventoryExpanded = !inventoryExpanded;
                render(document);
                break;
            case "confirm-ok":
                commitConfirm(document);
                break;
            case "rename-ok":
                commitRename(document);
                break;
            case "modal-close":
                closeModals(document);
                break;
            case "layout":
                setTreeMode(document, "tree".equals(action.getDataset().get("layout")));
                RewindClientConfig.setTreeLayout(treeMode);
                break;
            case "switch-layout":
                setTreeMode(document, !treeMode);
                RewindClientConfig.setTreeLayout(treeMode);
                break;
            case "rotate-tree":
                rotateTree(document);
                break;
            case "flow-reset":
                flowFitPending = true;
                break;
            case "save-here":
                saveToFirstFreeSlot(document);
                break;
            default:
                break;
        }
    }

    /** 「读取」：交给 F8 那条带过渡的管线，界面会被 {@code CheckpointController} 摘掉。 */
    private void load(Document document, String slot) {
        if (slot == null || !usable(document)) {
            return;
        }
        SnapshotMeta meta = RewindApi.describe(worldRoot(), slot);
        if (meta == null || !meta.isComplete()) {
            log("rewind.ui.log.no_snapshot");
            return;
        }
        log("rewind.ui.log.loading", title(slot, meta));
        RewindApi.requestRollback(slot, "ui");
    }

    private void askConfirm(Document document, String action, String slot) {
        if (slot == null || !usable(document)) {
            return;
        }
        pendingAction = action;
        pendingSlot = slot;
        SnapshotMeta meta = RewindApi.describe(worldRoot(), slot);
        Element body = document.querySelector("#confirmBody");
        if (body != null) {
            body.setTextContent("delete".equals(action)
                    ? tr("rewind.ui.modal.delete", title(slot, meta))
                    : tr("rewind.ui.modal.overwrite", title(slot, meta)));
        }
        Element icon = document.querySelector("#confirmIcon");
        if (icon != null) {
            icon.setTextContent("delete".equals(action) ? "!" : "?");
        }
        openModal(document, "modalConfirm");
    }

    private void commitConfirm(Document document) {
        String action = pendingAction;
        String slot = pendingSlot;
        pendingAction = null;
        pendingSlot = null;
        closeModals(document);
        if (action == null || slot == null || !usable(document)) {
            return;
        }
        if ("delete".equals(action)) {
            Path world = worldRoot();
            String name = title(slot, RewindApi.describe(world, slot));
            if (RewindApi.deleteCheckpoint(world, slot)) {
                // 封面不在存档目录里，删槽位那条路带不上它，这里单独清
                String worldDir = worldDirName();
                if (worldDir != null) {
                    CoverCapture.deleteCovers(worldDir, slot);
                }
                log("rewind.ui.log.deleted", name);
            }
            render(document);
            return;
        }
        // 覆盖：世界不关、界面不退。任务跑在服务端线程上（那段时间世界不 tick，所以
        // 「拷贝期间没人写盘」这条保证和 F7 一样成立），只是不放过渡、不动界面。
        startBackgroundSave(document, slot);
    }

    /**
     * 后台写一个存档点：不动界面、不放过渡。
     *
     * <p>和 F7 那条路只差「界面」和「过渡」两件事：建点本身仍然是
     * {@link RewindApi#createCheckpoint}，仍然跑在服务端线程上，所以一致性保证不变。
     * 写盘期间 {@link #isPauseScreen()} 会放行世界（否则服务端线程永远轮不到这个任务），
     * 做完由 {@link #tick()} 把结果收回来、刷新界面。
     *
     * <p>封面照抓：{@code CoverCapture} 会在抓帧那一帧把本界面和 HUD 临时摘掉，
     * 所以「界面一直开着」和「封面里没有界面」这两件事不冲突。
     */
    private void startBackgroundSave(Document document, String slot) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            log("rewind.ui.log.no_world");
            return;
        }
        savingSlot = slot;
        savingResult = null;
        log("rewind.ui.log.saving", title(slot, RewindApi.describe(worldRoot(), slot)));
        server.execute(() -> savingResult = RewindApi.createCheckpoint(server, slot, "ui"));
    }

    /**
     * 特殊槽位（自动 / 快速）的「设置」弹窗。
     *
     * <p>这两张卡是固定角色，没有名字可改，所以它们的第三个按钮是设置：里面是这张卡当前的状态，
     * 以及它到底代表什么。
     */
    private void openSettings(Document document, String slot) {
        if (slot == null) {
            return;
        }
        SnapshotMeta meta = RewindApi.describe(worldRoot(), slot);
        Element title = document.querySelector("#settingsTitle");
        if (title != null) {
            title.setInnerText(tr("rewind.ui.settings.title", title(slot, meta)));
        }
        Element info = document.querySelector("#settingsInfo");
        if (info != null) {
            StringBuilder html = new StringBuilder();
            if (SnapshotLayout.SLOT_AUTO.equals(slot)) {
                // 自动存档这张卡的两个可调项：跟着原版自动保存建点、以及自动保存间隔
                html.append("<div class=\"form-group\"><label class=\"form-label\">")
                        .append(text(tr("rewind.ui.settings.auto.toggle"))).append("</label>")
                        .append(switchControl("toggle-auto", RewindServerConfig.autoCheckpointEnabled()))
                        .append("</div>");
                html.append("<div class=\"form-group\"><label class=\"form-label\" for=\"autoInterval\">")
                        .append(text(tr("rewind.ui.settings.auto.interval"))).append("</label>")
                        .append("<div class=\"input-group\">")
                        .append("<input class=\"form-input\" id=\"autoInterval\" maxlength=\"2\" value=\"")
                        .append(RewindServerConfig.autoSaveIntervalMinutes()).append("\">")
                        .append("<button class=\"button button-secondary\" data-act=\"apply-interval\">")
                        .append(text(tr("rewind.ui.action.apply"))).append("</button>")
                        .append("</div></div>");
            }
            if (SnapshotLayout.SLOT_QUICK.equals(slot)) {
                // 快速槽的四项：两种过渡的强度（主题的滑块）+ 改键入口 + 死亡后自动回溯
                html.append(sliderGroup("rewind.ui.settings.quick.saturation", "saturationBoost",
                        RewindClientConfig.saturationBoost(),
                        RewindClientConfig.MIN_SATURATION_BOOST, RewindClientConfig.MAX_SATURATION_BOOST));
                html.append(sliderGroup("rewind.ui.settings.quick.blur", "blurRadius",
                        RewindClientConfig.blurRadius(),
                        RewindClientConfig.MIN_BLUR_RADIUS, RewindClientConfig.MAX_BLUR_RADIUS));
                html.append("<div class=\"form-group\"><label class=\"form-label\">")
                        .append(text(tr("rewind.ui.settings.quick.keys"))).append("</label>")
                        .append("<button class=\"button button-small button-tertiary\" data-act=\"open-keys\">")
                        .append(text(tr("rewind.ui.settings.quick.keys_button"))).append("</button></div>");
                html.append("<div class=\"form-group\"><label class=\"form-label\">")
                        .append(text(tr("rewind.ui.settings.quick.death_rollback"))).append("</label>")
                        .append(switchControl("toggle-death", RewindServerConfig.rollbackOnDeath()))
                        .append("</div>");
            }
            info.setInnerHTML(html.toString());
            // 间隔那个输入框是这一趟刚铺进去的，回车提交要在这里现挂（和重命名弹窗那个不一样，
            // 那个是模板里的静态元素，在 bind() 里挂过了）
            Element interval = document.querySelector("#autoInterval");
            if (interval != null) {
                interval.addEventListener("keydown", event -> {
                    if (event instanceof KeyEvent key && key.keyCode == GLFW.GLFW_KEY_ENTER) {
                        applyInterval(document);
                    }
                });
            }
            // 主题那条滑块是纯视觉的 div，拖动得自己驱动（按下 / 拖动 / 松手）
            for (Element slider : document.querySelectorAll("#settingsInfo .slider")) {
                slider.addEventListener("mousedown", this::onSliderMouseDown);
                slider.addEventListener("mousemove", this::onSliderMouseMove);
                slider.addEventListener("mouseup", this::onSliderMouseUp);
            }
        }
        Element note = document.querySelector("#settingsNote");
        if (note != null) {
            note.setTextContent(tr(SnapshotLayout.SLOT_AUTO.equals(slot)
                    ? "rewind.ui.settings.note.auto"
                    : "rewind.ui.settings.note.quick"));
        }
        openModal(document, "modalSettings");
    }

    /** 正在拖的那条过渡强度滑块；null 表示没在拖。 */
    private Element draggingSlider;

    /**
     * 设置弹窗里一条「过渡强度」：用主题自带的那条滑块（{@code .slider} 的 div 版），
     * 当前值写在它下面的 {@code .form-help} 里。
     *
     * <p>主题的滑块只是**视觉**（轨道 + 进度 + 滑块头），拖动得自己驱动——见 {@link #onSliderMouseDown}。
     * 上下限写在 {@code data-min} / {@code data-max} 上，拖的时候照着算。
     */
    private static String sliderGroup(String labelKey, String id, float value, float min, float max) {
        String percent = percentOf(value, min, max);
        String shown = formatSliderValue(value);
        return "<div class=\"form-group\"><label class=\"form-label\">" + text(tr(labelKey))
                + "</label><div class=\"slider\" id=\"" + id + "\" role=\"slider\""
                + " data-min=\"" + formatSliderValue(min) + "\" data-max=\"" + formatSliderValue(max) + "\""
                + " aria-valuenow=\"" + shown + "\">"
                + "<div class=\"slider-process\" style=\"width:" + percent + "\"></div>"
                + "<span class=\"slider-thumb\" style=\"left:" + percent + "\"></span>"
                + "</div><div class=\"form-help\" id=\"" + id + "Value\">" + shown + "</div></div>";
    }

    /** 滚动条上的数字统一一位小数（1.5 / 13.0）。 */
    private static String formatSliderValue(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 值 → 滑块上的百分比（主题的 {@code .slider-process} 宽度 / {@code .slider-thumb} 位置）。 */
    private static String percentOf(double value, double min, double max) {
        double fraction = max <= min ? 0.0D : Math.max(0.0D, Math.min(1.0D, (value - min) / (max - min)));
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0D);
    }

    /** 强度只留一位小数：拖着走的时候别让数值抖出一长串小数。 */
    private static double roundSliderValue(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    /**
     * 按光标位置把滑块拖到新位置：改主题那条滑块的进度 / 滑块头、下面那个数字，以及内存里的值。
     *
     * <p>拖动期间只改内存（{@code previewXxx}），松手才落盘——拖一次会来一串事件，每个都写配置
     * 会刷一屏日志。
     */
    private void dragSliderTo(Element slider, double clientX) {
        Element.DOMRect rect = slider.getBoundingClientRect();
        if (rect.width <= 0.0D) {
            return;
        }
        double min = parseSlider(slider.getDataset().get("min"));
        double max = parseSlider(slider.getDataset().get("max"));
        double fraction = Math.max(0.0D, Math.min(1.0D, (clientX - rect.x) / rect.width));
        float value = (float) roundSliderValue(min + fraction * (max - min));
        String percent = percentOf(value, min, max);
        slider.setAttribute("aria-valuenow", formatSliderValue(value));
        Element process = slider.querySelector(".slider-process");
        if (process != null) {
            process.setInlineStyleProperty("width", percent);
        }
        Element thumb = slider.querySelector(".slider-thumb");
        if (thumb != null) {
            thumb.setInlineStyleProperty("left", percent);
        }
        Element readout = slider.getNextElementSibling();
        if (readout != null) {
            readout.setTextContent(formatSliderValue(value));
        }
        previewTransition(slider, value);
    }

    private void onSliderMouseDown(Event event) {
        if (!(event instanceof MouseEvent mouse) || !(event.target instanceof Element target)) {
            return;
        }
        Element slider = target.closest(".slider");
        if (slider == null) {
            return;
        }
        draggingSlider = slider;
        dragSliderTo(slider, mouse.clientX);
    }

    private void onSliderMouseMove(Event event) {
        Element slider = draggingSlider;
        if (slider == null || !(event instanceof MouseEvent mouse)) {
            return;
        }
        dragSliderTo(slider, mouse.clientX);
    }

    /** 松手：把滑块当前的值写进配置并落盘。 */
    private void onSliderMouseUp(Event event) {
        Element slider = draggingSlider;
        draggingSlider = null;
        if (slider == null) {
            return;
        }
        commitTransition(slider, (float) parseSlider(slider.getAttribute("aria-valuenow")));
    }

    private static void previewTransition(Element slider, float value) {
        if ("saturationBoost".equals(slider.id)) {
            RewindClientConfig.previewSaturationBoost(value);
        } else if ("blurRadius".equals(slider.id)) {
            RewindClientConfig.previewBlurRadius(value);
        }
    }

    private static void commitTransition(Element slider, float value) {
        if ("saturationBoost".equals(slider.id)) {
            RewindClientConfig.commitSaturationBoost(value);
        } else if ("blurRadius".equals(slider.id)) {
            RewindClientConfig.commitBlurRadius(value);
        }
    }

    private static double parseSlider(String raw) {
        try {
            return Double.parseDouble(raw == null ? "" : raw.trim().replace("%", ""));
        } catch (NumberFormatException e) {
            return 0.0D;
        }
    }

    /**
     * 打开按键绑定页，只列 Rewind 那两个热键。
     *
     * <p>先把设置弹窗收掉：那一页会盖住整个界面，按 Esc 回来时时间树会重新 {@code init} 一遍，
     * 弹窗留着反而会以「开着」的样子回来。
     */
    private void openKeyBinds(Document document) {
        closeModals(document);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new RewindKeyBindsScreen(minecraft.screen, minecraft.options));
    }

    /** 「自动存档」卡上那个开关：跟着原版自动保存建点。改完立刻落盘，并重画弹窗里那一行。 */
    private void toggleAutoCheckpoint(Document document) {
        boolean enabled = !RewindServerConfig.autoCheckpointEnabled();
        RewindServerConfig.setAutoCheckpointEnabled(enabled);
        log(enabled ? "rewind.ui.log.auto_on" : "rewind.ui.log.auto_off");
        // 弹窗里那一行（开关状态 + 按钮配色）要跟着变，重开一次最省事
        openSettings(document, SnapshotLayout.SLOT_AUTO);
    }

    /** 「快速存档」设置里的开关：死亡后要不要回溯到时间线上最近的那个节点。改完立刻落盘并重画。 */
    private void toggleDeathRollback(Document document) {
        boolean enabled = !RewindServerConfig.rollbackOnDeath();
        RewindServerConfig.setRollbackOnDeath(enabled);
        log(enabled ? "rewind.ui.log.death_on" : "rewind.ui.log.death_off");
        // 红框要跟着开关变：整页重画 + 重开弹窗里那一行
        render(document);
        openSettings(document, SnapshotLayout.SLOT_QUICK);
    }

    /**
     * 「自动保存间隔」那一项：把输入框里的分钟数写进配置（立刻落盘）。
     *
     * <p>这个值只在「跟着原版自动保存建点」开着时生效——那会儿它会直接改原版的自动保存间隔
     * （见 {@code AutoCheckpointMixins$MinecraftServerAutosave}）；关着的时候原版还是它自己的 5 分钟。
     */
    private void applyInterval(Document document) {
        Element input = document.querySelector("#autoInterval");
        String raw = input == null || input.getValue() == null ? "" : input.getValue().trim();
        int minutes;
        try {
            minutes = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            log("rewind.ui.log.interval_invalid",
                    RewindServerConfig.MIN_AUTO_SAVE_INTERVAL_MINUTES,
                    RewindServerConfig.MAX_AUTO_SAVE_INTERVAL_MINUTES);
            openSettings(document, SnapshotLayout.SLOT_AUTO);
            return;
        }
        RewindServerConfig.setAutoSaveIntervalMinutes(minutes);
        log("rewind.ui.log.interval_set", RewindServerConfig.autoSaveIntervalMinutes());
        // 夹过范围之后输入框里该显示的是夹过的值，重开一次最省事
        openSettings(document, SnapshotLayout.SLOT_AUTO);
    }

    private void askRename(Document document, String slot) {
        if (slot == null || !usable(document)) {
            return;
        }
        SnapshotMeta meta = RewindApi.describe(worldRoot(), slot);
        if (meta == null) {
            log("rewind.ui.log.no_snapshot");
            return;
        }
        renamingSlot = slot;
        Element input = document.querySelector("#renameInput");
        if (input != null) {
            input.setValue(meta.displayName);
        }
        Element label = document.querySelector("#renameTitle");
        if (label != null) {
            label.setTextContent(tr("rewind.ui.modal.rename_title"));
        }
        openModal(document, "modalRename");
    }

    private void commitRename(Document document) {
        String slot = renamingSlot;
        Element input = document.querySelector("#renameInput");
        String name = input == null || input.getValue() == null ? "" : input.getValue().trim();
        renamingSlot = null;
        closeModals(document);
        if (slot == null) {
            return;
        }
        if (!SnapshotLayout.isValidDisplayName(name)) {
            log("rewind.ui.log.name_invalid");
            return;
        }
        if (RewindApi.renameCheckpoint(worldRoot(), slot, name)) {
            log("rewind.ui.log.renamed", name);
        }
        render(document);
    }

    /**
     * 动手之前先看能不能动手。
     *
     * <p>这些判断 {@code CheckpointController} 也会自己做一遍；在这里做是为了能在界面上说清楚
     * 为什么不动作——那边的约定是只写日志、不打扰玩家。
     */
    private boolean usable(Document document) {
        Minecraft minecraft = Minecraft.getInstance();
        if (savingSlot != null) {
            // 写盘期间世界是放行的：这时候再动索引（删除 / 改名）会和服务端线程上的写盘抢同一张索引
            log("rewind.ui.log.busy");
            return false;
        }
        if (RewindApi.isBusy()) {
            log("rewind.ui.log.busy");
            return false;
        }
        if (!minecraft.hasSingleplayerServer() || minecraft.level == null) {
            log("rewind.ui.log.no_world");
            return false;
        }
        if (minecraft.getSingleplayerServer().isPublished()) {
            log("rewind.ui.log.lan");
            return false;
        }
        return true;
    }

    private static void openModal(Document document, String id) {
        Element modal = document.querySelector("#" + id);
        if (modal != null) {
            modal.getClassList().add("open");
        }
    }

    private static void closeModals(Document document) {
        for (Element modal : document.querySelectorAll(".modal-backdrop")) {
            modal.getClassList().remove("open");
        }
    }

    /**
     * 界面上的反馈**只写日志，不往页面上放任何提示**——和模组别处一致（F7/F8 也是只写日志）。
     *
     * <p>文案仍然走 lang（{@code rewind.ui.log.*}），所以日志里看到的是玩家语言。
     */
    private static void log(String messageKey, Object... args) {
        Rewind.LOGGER.info("Rewind: {}", tr(messageKey, args));
    }

    // ------------------------------------------------------------------ 渲染

    private void render(Document document) {
        Map<String, SnapshotMeta> metas = loadMetas();
        // 「死亡后回溯的目标」= 时间线的头（世界当前站着的存档点）。开关关着时不标记——
        // 那种情况下死亡不会回溯，标红反而是误导。
        Path world = worldRoot();
        deathTargetSlot = RewindServerConfig.rollbackOnDeath() && world != null
                ? RewindApi.currentSlot(world) : "";
        renderHud(document);
        renderSlots(document, metas);
        renderTree(document, metas);
        renderDetail(document, metas);
        bindDeathTargetTooltip(document);
    }

    /** 给标了红框（{@code .death-target}）的卡片挂 AUI 自带的悬浮提示，说明红框是什么意思。 */
    private void bindDeathTargetTooltip(Document document) {
        if (deathTargetSlot.isEmpty()) {
            return;
        }
        for (Element card : document.querySelectorAll(".death-target")) {
            Tooltip.bindTranslation(card, "rewind.ui.slot.death_target.tooltip");
        }
    }

    private void renderHud(Document document) {
        Element bar = document.querySelector("#hudBar");
        if (bar == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            bar.setInnerHTML(chip("rewind.ui.hud.biome", tr("rewind.ui.hud.unavailable")));
            return;
        }
        BlockPos position = player.blockPosition();
        String biome = minecraft.level.getBiome(position)
                .unwrapKey()
                .map(key -> biomeName(key.location().toString()))
                .orElseGet(() -> tr("rewind.ui.biome.unknown"));
        StringBuilder html = new StringBuilder();
        html.append(chip("rewind.ui.hud.biome", biome));
        html.append(chip("rewind.ui.hud.position", position.getX() + ", " + position.getY() + ", " + position.getZ()));
        html.append(chip("rewind.ui.hud.time", gameClock(minecraft.level.getGameTime())));
        html.append(chip("rewind.ui.hud.playtime", playtime(currentPlaytimeTicks())));
        bar.setInnerHTML(html.toString());
    }

    private void renderSlots(Document document, Map<String, SnapshotMeta> metas) {
        Element specialGrid = document.querySelector("#specialGrid");
        Element slotGrid = document.querySelector("#slotGrid");
        if (specialGrid == null || slotGrid == null) {
            return;
        }
        long newest = newestSavedAt(metas);
        StringBuilder special = new StringBuilder();
        List<String> manual = new ArrayList<>();
        int used = 0;
        for (String slot : RewindApi.slots()) {
            SnapshotMeta meta = metas.get(slot);
            if (SnapshotLayout.isSpecialSlot(slot)) {
                special.append(specialCard(slot, meta));
            } else {
                if (meta != null) {
                    used++;
                }
                if (matchesFilter(slot, meta)) {
                    manual.add(slot);
                }
            }
        }
        sortManual(manual, metas);
        StringBuilder cards = new StringBuilder();
        for (String slot : manual) {
            cards.append(manualCard(slot, metas.get(slot), newest));
        }
        specialGrid.setInnerHTML(special.toString());
        slotGrid.setInnerHTML(cards.length() == 0
                ? "<div class=\"empty-note\">" + text(tr("rewind.ui.no_match")) + "</div>"
                : cards.toString());

        Element count = document.querySelector("#slotCount");
        if (count != null) {
            count.setInnerText(tr("rewind.ui.count", used, SnapshotLayout.manualSlotCount()));
        }
    }

    // ------------------------------------------------------------------ 时间线（节点树布局）

    /**
     * 把存档点按 {@link SnapshotMeta#parentSlot} 拼成一棵树，铺进 {@code #treeChart}。
     *
     * <p>节点就是「有存档点的槽位」——空槽位不在时间线上。父槽位不存在、指向自己、
     * 或者父链绕成环的，都当成根：宁可断一条边，也不能让递归转不出来。
     *
     * <p>树上**永远多一个** {@link #NOW_NODE}「当前进度」节点：它挂在时间线的头
     * （{@link RewindApi#currentSlot}）下面，一眼就能看出当前这一局是从哪个分叉岔出来的；
     * 头还没立起来（新世界、或头所在的槽位被删了）时它自己当根。
     *
     * <p>顺带把画布高度按视口算好，并在节点数变了的时候请求重新装一次树。
     */
    private void renderTree(Document document, Map<String, SnapshotMeta> metas) {
        Element chart = document.querySelector("#treeChart");
        Element canvas = document.querySelector("#flowCanvas");
        if (chart == null) {
            return;
        }
        if (canvas != null && treeMode) {
            applyCanvasHeight(document, canvas);
        }
        Map<String, List<String>> children = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        int total = 0;
        for (String slot : RewindApi.slots()) {
            if (!metas.containsKey(slot)) {
                continue;
            }
            total++;
            String parent = parentOf(slot, metas);
            if (parent == null) {
                roots.add(slot);
            } else {
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(slot);
            }
        }
        Path world = worldRoot();
        String head = world == null ? "" : RewindApi.currentSlot(world);
        boolean headInTree = !head.isEmpty() && metas.containsKey(head);
        if (headInTree) {
            // 「在此存档」是头这一支的末端，排在它的其他子节点后面
            children.computeIfAbsent(head, key -> new ArrayList<>()).add(NOW_NODE);
        }
        freeManualSlot = firstFreeSlot(metas);
        Element count = document.querySelector("#treeCount");
        if (count != null) {
            count.setInnerText(tr("rewind.ui.tree.count", total));
        }
        // 同一层里按建点时间排：时间线的先后顺序才看得出来
        Comparator<String> bySavedAt = Comparator.comparingLong(slot -> savedAt(metas, slot));
        roots.sort(bySavedAt);
        for (List<String> siblings : children.values()) {
            siblings.sort(bySavedAt);
            if (siblings.remove(NOW_NODE)) {
                siblings.add(NOW_NODE);
            }
        }
        StringBuilder html = new StringBuilder("<ul class=\"tree-branch\">");
        Set<String> emitted = new HashSet<>();
        for (String root : roots) {
            appendTreeNode(html, root, metas, children, emitted);
        }
        // 父链成环时兜底：没被画出来的节点自己当根，保证每个存档点都看得见
        for (String slot : RewindApi.slots()) {
            if (metas.containsKey(slot) && !emitted.contains(slot)) {
                appendTreeNode(html, slot, metas, children, emitted);
            }
        }
        if (!headInTree) {
            html.append(nowNode());
        }
        html.append("</ul>");
        chart.setInnerHTML(html.toString());
        if (total != treeNodes) {
            treeNodes = total;
            flowFitPending = treeMode;
        }
    }

    /** 一个节点 = 卡片 + 挂在自己下面的子树。 */
    private void appendTreeNode(StringBuilder html, String slot, Map<String, SnapshotMeta> metas,
                                Map<String, List<String>> children, Set<String> emitted) {
        if (!emitted.add(slot)) {
            return;
        }
        html.append("<li class=\"tree-node\">");
        html.append(NOW_NODE.equals(slot) ? nowCard() : treeCard(slot, metas.get(slot)));
        List<String> kids = children.get(slot);
        if (kids != null && !kids.isEmpty()) {
            html.append("<ul class=\"tree-branch\">");
            for (String kid : kids) {
                appendTreeNode(html, kid, metas, children, emitted);
            }
            html.append("</ul>");
        }
        html.append("</li>");
    }

    /** {@link #NOW_NODE} 那一格的 HTML（含 {@code <li>}）。 */
    private String nowNode() {
        return "<li class=\"tree-node\">" + nowCard() + "</li>";
    }

    /**
     * 「在此存档」这个额外节点：世界现在站在哪儿，以及点一下就能把当前进度存到哪儿。
     *
     * <p>它不是一个槽位——没有存档点可读可写，所以它不可选（点了不会进详情面板）。但它**可以点**：
     * 写进 {@link #freeManualSlot}（序号最小的空手动槽位），走的是「界面里覆盖」那条后台写盘路径
     * （界面不退、不放过渡）。槽位全满时它变成一句「无空槽位」且不可点。
     */
    private String nowCard() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        boolean canSave = freeManualSlot != null;
        StringBuilder html = new StringBuilder();
        html.append("<article class=\"card slot-card tree-card now-card")
                .append(canSave ? "" : " is-full").append('"');
        if (canSave) {
            html.append(" data-act=\"save-here\" tabindex=\"0\" role=\"button\"");
        }
        html.append('>');
        html.append("<div class=\"tree-head\"><span class=\"badge badge-success\">")
                .append(text(tr("rewind.ui.tree.now.badge"))).append("</span><h4 class=\"slot-title\">")
                .append(text(tr(canSave ? "rewind.ui.tree.save_here" : "rewind.ui.tree.no_slot")))
                .append("</h4></div>");
        html.append("<p class=\"tree-meta\">");
        if (player == null || minecraft.level == null) {
            html.append(text(tr("rewind.ui.hud.unavailable")));
        } else {
            BlockPos position = player.blockPosition();
            String biome = minecraft.level.getBiome(position)
                    .unwrapKey()
                    .map(key -> biomeName(key.location().toString()))
                    .orElseGet(() -> tr("rewind.ui.biome.unknown"));
            html.append(text(biome)).append("<span>·</span>")
                    .append(text(position.getX() + ", " + position.getY() + ", " + position.getZ()));
        }
        html.append("</p><span class=\"slot-clock\">")
                .append(text(minecraft.level == null ? "" : gameClock(minecraft.level.getGameTime())))
                .append("</span></article>");
        return html.toString();
    }

    /** 序号最小的空手动槽位（{@code s1} 起）；当前数量的手动槽位都满了返回 null。 */
    @Nullable
    private static String firstFreeSlot(Map<String, SnapshotMeta> metas) {
        for (int index = 1; index <= SnapshotLayout.manualSlotCount(); index++) {
            String slot = SnapshotLayout.manualSlot(index);
            if (!metas.containsKey(slot)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * 时间线上那个「在此存档」被点了：把当前进度写进序号最小的空手动槽位。
     *
     * <p>没有空槽位时不动作（那个节点这会儿本来就不可点，这里只是兜底）。写盘走
     * {@link #startBackgroundSave}：界面不退、不放过渡，做完由 {@link #tick()} 收回来重画。
     */
    private void saveToFirstFreeSlot(Document document) {
        if (!usable(document)) {
            return;
        }
        String slot = firstFreeSlot(loadMetas());
        if (slot == null) {
            log("rewind.ui.log.no_free_slot");
            return;
        }
        startBackgroundSave(document, slot);
    }

    /** 时间线上的节点卡片：角色 / 序号 + 名字 + 一行摘要 + 游戏内时间，比槽位卡片小一号。 */
    private String treeCard(String slot, @Nullable SnapshotMeta meta) {
        StringBuilder html = new StringBuilder();
        html.append("<article class=\"card slot-card tree-card").append(slot.equals(selectedSlot) ? " selected" : "")
                .append(slot.equals(deathTargetSlot) ? " death-target" : "")
                .append("\" data-act=\"select\" data-slot=\"").append(slot).append("\" tabindex=\"0\" role=\"button\">");
        html.append("<div class=\"tree-head\"><span class=\"badge ").append(slotBadgeClass(slot)).append("\">")
                .append(text(slotBadgeLabel(slot))).append("</span><h4 class=\"slot-title\">")
                .append(text(title(slot, meta))).append("</h4></div>");
        html.append("<p class=\"tree-meta\">");
        if (meta == null) {
            html.append(text(tr("rewind.ui.slot.unused")));
        } else {
            html.append(text(relativeTime(meta.savedAtMillis))).append("<span>·</span>")
                    .append(text(sizeText(meta.totalBytes))).append("<span>·</span>")
                    .append(text(biomeName(meta.biomeId)));
        }
        html.append("</p><span class=\"slot-clock\">")
                .append(text(meta == null ? "" : gameClock(meta.gameTime))).append("</span></article>");
        return html.toString();
    }

    /**
     * 时间线上的父节点；根、或者父节点已经不在了（被覆盖 / 删掉）时返回 null。
     *
     * <p>指向自己也算根——单靠这一条就能挡掉最直接的那种环。
     */
    @Nullable
    private static String parentOf(String slot, Map<String, SnapshotMeta> metas) {
        SnapshotMeta meta = metas.get(slot);
        if (meta == null || meta.parentSlot.isEmpty() || meta.parentSlot.equals(slot)) {
            return null;
        }
        return metas.containsKey(meta.parentSlot) ? meta.parentSlot : null;
    }

    /** 两套布局之间切换：body 上挂 tree-mode，开关与两侧文字跟着亮。 */
    private void setTreeMode(Document document, boolean tree) {
        treeMode = tree;
        document.body.getClassList().toggle("tree-mode", tree);
        for (Element label : document.querySelectorAll(".layout-switch-label")) {
            label.getClassList().toggle("active", (tree ? "tree" : "archive").equals(label.getDataset().get("layout")));
        }
        Element toggle = document.querySelector(".layout-switch .switch");
        if (toggle != null) {
            toggle.getClassList().toggle("on", tree);
            toggle.setAttribute("aria-checked", tree ? "true" : "false");
        }
        if (tree) {
            flowFitPending = true;
        }
    }

    /** 顺时针转 90°：从上到下 → 从左到右 → 从下到上 → 从右到左。 */
    private void rotateTree(Document document) {
        treeDir = TREE_DIRS[(indexOfDir(treeDir) + 1) % TREE_DIRS.length];
        RewindClientConfig.setTreeDirection(treeDir);
        applyLayout(document);
        flowFitPending = true;
    }

    /** 把当前方向写到 {@code #treeView} 上，并更新旋转按钮上的文字。 */
    private void applyLayout(Document document) {
        Element view = document.querySelector("#treeView");
        if (view != null) {
            view.setAttribute("data-dir", treeDir);
        }
        Element label = document.querySelector(".rotate-label");
        if (label != null) {
            label.setTextContent(tr(dirLabelKey(treeDir)));
        }
    }

    private static int indexOfDir(String dir) {
        for (int i = 0; i < TREE_DIRS.length; i++) {
            if (TREE_DIRS[i].equals(dir)) {
                return i;
            }
        }
        return 0;
    }

    private static String dirLabelKey(String dir) {
        switch (dir) {
            case "right":
                return "rewind.ui.tree.dir.right";
            case "up":
                return "rewind.ui.tree.dir.up";
            case "left":
                return "rewind.ui.tree.dir.left";
            default:
                return "rewind.ui.tree.dir.down";
        }
    }

    /** 滚轮缩放，以光标底下那个点为锚——缩放前后它待在原地。 */
    private void onFlowWheel(Event event) {
        if (!(event instanceof MouseEvent mouse)) {
            return;
        }
        Document document = getLinkedDocument();
        Element canvas = document == null ? null : document.querySelector("#flowCanvas");
        if (canvas == null) {
            return;
        }
        double delta = mouse.deltaY != 0.0D ? mouse.deltaY : mouse.scrollDelta;
        double next = Math.min(FLOW_ZOOM_MAX, Math.max(FLOW_ZOOM_MIN, flowScale * (delta > 0.0D ? 0.87D : 1.15D)));
        if (next == flowScale) {
            return;
        }
        Element.DOMRect rect = canvas.getBoundingClientRect();
        double pointerX = mouse.clientX - rect.x;
        double pointerY = mouse.clientY - rect.y;
        flowX = pointerX - (pointerX - flowX) * (next / flowScale);
        flowY = pointerY - (pointerY - flowY) * (next / flowScale);
        flowScale = next;
        mouse.preventDefault();
        applyFlowTransform(document);
    }

    private void onFlowMouseDown(Event event) {
        if (!(event instanceof MouseEvent mouse)) {
            return;
        }
        // 右上角那颗旋转按钮不参与拖拽
        if (event.target instanceof Element target && target.closest(".flow-rotate") != null) {
            return;
        }
        flowDragX = mouse.clientX;
        flowDragY = mouse.clientY;
        flowDragOriginX = flowX;
        flowDragOriginY = flowY;
        flowDragging = true;
        flowMoved = false;
    }

    private void onFlowMouseMove(Event event) {
        if (!flowDragging || !(event instanceof MouseEvent mouse)) {
            return;
        }
        Document document = getLinkedDocument();
        if (document == null) {
            return;
        }
        double dx = mouse.clientX - flowDragX;
        double dy = mouse.clientY - flowDragY;
        if (Math.abs(dx) + Math.abs(dy) > FLOW_DRAG_SLOP) {
            flowMoved = true;
        }
        flowX = flowDragOriginX + dx;
        flowY = flowDragOriginY + dy;
        Element canvas = document.querySelector("#flowCanvas");
        if (canvas != null) {
            canvas.getClassList().add("is-panning");
        }
        applyFlowTransform(document);
    }

    private void onFlowMouseUp(Event event) {
        flowDragging = false;
        Document document = getLinkedDocument();
        Element canvas = document == null ? null : document.querySelector("#flowCanvas");
        if (canvas != null) {
            canvas.getClassList().remove("is-panning");
        }
    }

    /** 把平移与缩放写到 {@code #flowWorld} 的行内样式上——整棵树的位移就靠这一条。 */
    private void applyFlowTransform(Document document) {
        Element world = document.querySelector("#flowWorld");
        if (world == null) {
            return;
        }
        world.setInlineStyleProperty("transform",
                String.format(Locale.ROOT, "translate(%.2fpx,%.2fpx) scale(%.4f)", flowX, flowY, flowScale));
    }

    /**
     * 把整棵树装进画布：装得下就居中，装不下就按最小 0.5 倍、贴着根节点那一端，剩下的靠拖拽看。
     *
     * <p>树的范围是把每个节点卡片的盒子并起来算的——{@code #flowWorld} 本身会被拉满画布宽，
     * 量它只会得到画布宽度。卡片盒子是**带 transform 的视觉盒**（`getBoundingClientRect` 按
     * CSSOM 语义返回变换后的盒子），所以除以当前 scale 才是没缩放的尺寸与偏移。
     *
     * <p>视图刚显示时盒子可能还是 0（布局没算完），那就下一 tick 再量，最多 {@link #FLOW_FIT_TRIES} 次。
     */
    private void fitFlow() {
        if (!flowFitPending) {
            return;
        }
        Document document = getLinkedDocument();
        Element canvas = document == null ? null : document.querySelector("#flowCanvas");
        Element world = document == null ? null : document.querySelector("#flowWorld");
        if (canvas == null || world == null) {
            flowFitPending = false;
            return;
        }
        applyCanvasHeight(document, canvas);
        Element.DOMRect canvasRect = canvas.getBoundingClientRect();
        Element.DOMRect worldRect = world.getBoundingClientRect();
        double[] bounds = treeBounds(document);
        double canvasWidth = canvasRect.width;
        double canvasHeight = canvasRect.height;
        boolean measured = bounds != null && canvasWidth > 0.0D && canvasHeight > 0.0D && flowScale != 0.0D;
        double worldWidth = measured ? bounds[2] / flowScale : 0.0D;
        double worldHeight = measured ? bounds[3] / flowScale : 0.0D;
        if (!measured || worldWidth <= 0.0D || worldHeight <= 0.0D) {
            if (++flowFitTries < FLOW_FIT_TRIES) {
                return;
            }
            flowFitTries = 0;
            flowFitPending = false;
            return;
        }
        flowFitTries = 0;
        flowFitPending = false;
        // 树在 world 坐标系里的左上角：卡片并集的左上角减掉 world 自己的原点，再除掉缩放
        double originX = (bounds[0] - worldRect.x) / flowScale;
        double originY = (bounds[1] - worldRect.y) / flowScale;
        double room = 2.0D * FLOW_PADDING;
        flowScale = Math.max(FLOW_FIT_MIN_SCALE, Math.min(1.0D,
                Math.min((canvasWidth - room) / worldWidth, (canvasHeight - room) / worldHeight)));
        double scaledWidth = worldWidth * flowScale;
        double scaledHeight = worldHeight * flowScale;
        // 装得下就把树居中；装不下就把根节点那一端贴在画布边上（方向决定是哪一边）
        flowX = (scaledWidth <= canvasWidth - room
                ? (canvasWidth - scaledWidth) / 2.0D
                : ("left".equals(treeDir) ? canvasWidth - FLOW_PADDING - scaledWidth : FLOW_PADDING)) - originX * flowScale;
        flowY = (scaledHeight <= canvasHeight - room
                ? (canvasHeight - scaledHeight) / 2.0D
                : ("up".equals(treeDir) ? canvasHeight - FLOW_PADDING - scaledHeight : FLOW_PADDING)) - originY * flowScale;
        applyFlowTransform(document);
    }

    /**
     * 整棵树的视觉范围（{@code {x, y, 宽, 高}}）；一个节点都没有时返回 null。
     *
     * <p>用卡片并集而不是某个容器：容器会被拉满画布，只有卡片自己是有实际大小的。
     */
    @Nullable
    private static double[] treeBounds(Document document) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean any = false;
        for (Element card : document.querySelectorAll("#treeChart .tree-card")) {
            Element.DOMRect rect = card.getBoundingClientRect();
            if (rect.width <= 0.0D || rect.height <= 0.0D) {
                continue;
            }
            minX = Math.min(minX, rect.x);
            minY = Math.min(minY, rect.y);
            maxX = Math.max(maxX, rect.x + rect.width);
            maxY = Math.max(maxY, rect.y + rect.height);
            any = true;
        }
        return any ? new double[]{minX, minY, maxX - minX, maxY - minY} : null;
    }

    /**
     * 画布高度按视口算：占满页面剩下的那块地方，别让画布长到窗口外面去。
     *
     * <p>写的是行内样式（盖过 CSS 里那个兜底值）；值没变就不写，免得每帧都触发一次样式重算。
     */
    private void applyCanvasHeight(Document document, Element canvas) {
        Size viewport = document.getViewportSize();
        double height = Math.max(FLOW_MIN_CANVAS_HEIGHT, viewport.height() - FLOW_CHROME_HEIGHT);
        String value = String.format(Locale.ROOT, "%.0fpx", height);
        if (value.equals(canvas.getInlineStylePropertyValue("height"))) {
            return;
        }
        canvas.setInlineStyleProperty("height", value);
    }

    private void renderDetail(Document document, Map<String, SnapshotMeta> metas) {
        Element body = document.querySelector("#detailBody");
        if (body == null) {
            return;
        }
        SnapshotMeta meta = metas.get(selectedSlot);
        Element badge = document.querySelector("#detailBadge");
        if (badge != null) {
            badge.setInnerText(defaultTitle(selectedSlot));
        }
        Element title = document.querySelector("#detailTitle");
        if (title != null) {
            title.setInnerText(tr("rewind.ui.detail.title"));
        }
        if (meta == null) {
            body.setInnerHTML("<div class=\"empty-note\">" + text(tr("rewind.ui.detail.empty")) + "</div>");
            return;
        }

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"detail-cover\" style=\"background:").append(coverColor(selectedSlot)).append("\">")
                .append(coverImage(selectedSlot, meta)).append("</div>");
        html.append("<div class=\"detail-head\"><h3 class=\"detail-title\">").append(text(title(selectedSlot, meta)))
                .append("</h3><span class=\"detail-clock\">").append(text(gameClock(meta.gameTime))).append("</span></div>");
        html.append("<div class=\"inv-title\" data-act=\"toggle-inventory\"><span class=\"inv-caret\">")
                .append(inventoryExpanded ? "▼" : "▶").append("</span>")
                .append(text(tr("rewind.ui.detail.inventory"))).append("</div>");
        html.append(inventoryHtml());
        html.append("<ul class=\"list-group mt-3\">");
        html.append(row("rewind.ui.detail.saved_at", absoluteTime(meta.savedAtMillis)));
        html.append(row("rewind.ui.detail.playtime", playtime(meta.playtimeTicks)));
        html.append(row("rewind.ui.detail.biome", biomeName(meta.biomeId)));
        html.append(row("rewind.ui.detail.position",
                String.format(Locale.ROOT, "%.0f, %.0f, %.0f", meta.playerX, meta.playerY, meta.playerZ)));
        long occupied = occupiedBytes(selectedSlot, meta);
        html.append(row("rewind.ui.detail.size", sizeText(occupied < 0L ? meta.totalBytes : occupied)));
        html.append(row("rewind.ui.detail.save_size", sizeText(meta.totalBytes)));
        html.append("</ul>");
        html.append("<div class=\"detail-actions\">");
        html.append(actionButton(selectedSlot, "load", "button-secondary", tr("rewind.ui.action.load_full"), !meta.isComplete()));
        html.append(actionButton(selectedSlot, "save", "", tr("rewind.ui.action.save_full"), false));
        html.append(actionButton(selectedSlot, "rename", "button-tertiary", tr("rewind.ui.action.rename"), false));
        html.append(actionButton(selectedSlot, "delete", "button-danger", tr("rewind.ui.action.delete"), false));
        html.append("</div>");
        body.setInnerHTML(html.toString());
    }

    /**
     * 背包快照：**默认只显示一行快捷栏**，点标题上那个箭头才展开成完整背包。
     *
     * <p>展开时四行是「背包三行 + 快捷栏一行」——快捷栏按原版习惯放在最下面（第四行），
     * 中间留一段间隔把它和背包槽位分开；护甲与副手有东西才在快捷栏下面再起一行。
     *
     * <p>每格里的物品交给 AUI 的 {@code <item>} 元素——它认的就是原版 SNBT，而且数量由它自己画，
     * 所以这里不需要再写一个数量角标。
     */
    private String inventoryHtml() {
        Path world = worldRoot();
        if (world == null) {
            return "";
        }
        SnapshotInventory inventory = RewindApi.readInventory(world, selectedSlot);
        if (inventory.isEmpty()) {
            return "<div class=\"empty-note\">" + text(tr("rewind.ui.detail.inventory_empty")) + "</div>";
        }
        Map<Integer, String> byIndex = new HashMap<>();
        for (SnapshotInventory.Entry entry : inventory.entries()) {
            byIndex.put(entry.index, entry.item);
        }
        StringBuilder hotbar = new StringBuilder();
        for (int i = 0; i < HOTBAR_SLOTS; i++) {
            hotbar.append(cell(byIndex.get(i)));
        }
        StringBuilder html = new StringBuilder();
        if (inventoryExpanded) {
            StringBuilder body = new StringBuilder();
            for (int i = HOTBAR_SLOTS; i < MAIN_INVENTORY_SLOTS; i++) {
                body.append(cell(byIndex.get(i)));
            }
            html.append("<div class=\"inventory-grid\">").append(body).append("</div>");
            // 展开时快捷栏是第四行：上面三行背包和它之间留一段间隔
            html.append("<div class=\"inv-gap\"></div>");
        }
        html.append("<div class=\"inventory-grid\">").append(hotbar).append("</div>");
        if (inventoryExpanded) {
            StringBuilder equipment = new StringBuilder();
            boolean hasEquipment = false;
            for (int i = MAIN_INVENTORY_SLOTS; i < SnapshotInventory.SLOT_COUNT; i++) {
                String item = byIndex.get(i);
                if (item != null) {
                    hasEquipment = true;
                }
                equipment.append(cell(item));
            }
            if (hasEquipment) {
                html.append("<div class=\"inventory-grid mt-2\">").append(equipment).append("</div>");
            }
        }
        return html.toString();
    }

    // ------------------------------------------------------------------ 卡片

    /** 卡片左上角那个角色 / 序号徽章：自动是金色、快速是紫色，手动槽位是灰底的序号。 */
    private static String slotBadgeClass(String slot) {
        if (SnapshotLayout.SLOT_QUICK.equals(slot)) {
            return "badge-purple";
        }
        if (SnapshotLayout.SLOT_AUTO.equals(slot)) {
            return "badge-warning";
        }
        return "badge-ghost";
    }

    private static String slotBadgeLabel(String slot) {
        if (SnapshotLayout.SLOT_QUICK.equals(slot)) {
            return tr("rewind.ui.slot.badge.quick");
        }
        if (SnapshotLayout.SLOT_AUTO.equals(slot)) {
            return tr("rewind.ui.slot.badge.auto");
        }
        return String.format(Locale.ROOT, "%02d", SnapshotLayout.manualIndex(slot));
    }

    private String specialCard(String slot, SnapshotMeta meta) {
        StringBuilder html = new StringBuilder();
        html.append("<article class=\"card special-card").append(slot.equals(selectedSlot) ? " selected" : "")
                .append(slot.equals(deathTargetSlot) ? " death-target" : "")
                .append("\" data-act=\"select\" data-slot=\"").append(slot).append("\" tabindex=\"0\" role=\"button\">");
        html.append("<div class=\"special-cover\" style=\"background:").append(coverColor(slot)).append("\">")
                .append(coverImage(slot, meta)).append("</div>");
        html.append("<div class=\"special-body\">");
        html.append("<div class=\"special-head\"><span class=\"badge ").append(slotBadgeClass(slot))
                .append("\">").append(text(slotBadgeLabel(slot)))
                .append("</span><h4>").append(text(title(slot, meta))).append("</h4></div>");
        if (meta == null) {
            html.append("<p class=\"special-sub\">").append(text(tr("rewind.ui.slot.unused"))).append("</p>");
        } else {
            html.append("<p class=\"special-sub\">").append(text(meta.worldName)).append(" · ")
                    .append(text(gameClock(meta.gameTime))).append("</p>");
            html.append("<p class=\"special-sub\">").append(text(relativeTime(meta.savedAtMillis))).append(" · ")
                    .append(text(sizeText(meta.totalBytes))).append(" · ").append(text(biomeName(meta.biomeId))).append("</p>");
        }
        html.append("<div class=\"special-actions\">");
        html.append(actionButton(slot, "load", "button-secondary", tr("rewind.ui.action.load"), !isRestorable(meta)));
        html.append(actionButton(slot, "save", "", tr("rewind.ui.action.save"), false));
        // 自动 / 快速是固定角色的槽位，没有名字可改：第三个按钮是设置
        html.append(actionButton(slot, "settings", "button-tertiary", tr("rewind.ui.action.settings"), false));
        html.append("</div></div></article>");
        return html.toString();
    }

    private String manualCard(String slot, SnapshotMeta meta, long newestSavedAt) {
        String index = String.format(Locale.ROOT, "%02d", SnapshotLayout.manualIndex(slot));
        StringBuilder html = new StringBuilder();
        html.append("<article class=\"card slot-card").append(slot.equals(selectedSlot) ? " selected" : "")
                .append(slot.equals(deathTargetSlot) ? " death-target" : "")
                .append("\" data-act=\"select\" data-slot=\"").append(slot).append("\" tabindex=\"0\" role=\"button\">");
        if (meta == null) {
            html.append("<div class=\"slot-cover empty\"><span class=\"plus\">＋</span><span class=\"empty-text\">")
                    .append(text(tr("rewind.ui.slot.empty"))).append("</span><span class=\"slot-index\">").append(index).append("</span></div>");
            html.append("<div class=\"slot-empty-body\"><h4 class=\"slot-title text-muted\">")
                    .append(text(defaultTitle(slot))).append("</h4><p class=\"slot-line\">")
                    .append(text(tr("rewind.ui.slot.unused"))).append("</p></div>");
        } else {
            html.append("<div class=\"slot-cover\" style=\"background:").append(coverColor(slot)).append("\">")
                    .append(coverImage(slot, meta));
            html.append("<span class=\"slot-index\">").append(index).append("</span>");
            html.append("<span class=\"slot-tags\">");
            if (!meta.isComplete()) {
                html.append("<span class=\"badge badge-ghost\">").append(text(tr("rewind.ui.slot.badge.incomplete"))).append("</span>");
            }
            html.append("</span></div>");
            html.append("<div class=\"slot-body\"><div class=\"slot-head\"><h4 class=\"slot-title\">")
                    .append(text(title(slot, meta))).append("</h4><span class=\"slot-clock\">")
                    .append(text(gameClock(meta.gameTime))).append("</span></div>");
            html.append("<p class=\"slot-line\">").append(text(relativeTime(meta.savedAtMillis))).append("<span>·</span>")
                    .append(text(biomeName(meta.biomeId))).append("<span>·</span>").append(text(sizeText(meta.totalBytes)))
                    .append("</p></div>");
        }
        if (meta != null && meta.savedAtMillis > 0 && meta.savedAtMillis == newestSavedAt) {
            html.append("<span class=\"newest-flag\">").append(text(tr("rewind.ui.slot.badge.newest"))).append("</span>");
        }
        html.append("<div class=\"slot-actions\">");
        html.append(actionButton(slot, "load", "button-secondary", tr("rewind.ui.action.load"), !isRestorable(meta)));
        html.append(actionButton(slot, "save", "", tr("rewind.ui.action.save"), false));
        html.append(actionButton(slot, "rename", "button-tertiary", tr("rewind.ui.action.rename"), meta == null));
        html.append("</div></article>");
        return html.toString();
    }

    /**
     * 主题自带的开关控件（和标题旁边那个布局开关同一套）：{@code aria-checked} 与 {@code .on} 一起
     * 决定它开还是关，点击由调用方给的 {@code data-act} 接。
     */
    private static String switchControl(String action, boolean on) {
        return "<span class=\"switch" + (on ? " on" : "")
                + "\" data-act=\"" + action + "\" role=\"switch\" tabindex=\"0\" aria-checked=\"" + on + "\">"
                + "<span class=\"switch-control\"><span class=\"switch-status\"></span>"
                + "<span class=\"switch-button\"></span></span></span>";
    }

    private static String actionButton(String slot, String action, String extraClass, String label, boolean disabled) {
        StringBuilder html = new StringBuilder();
        html.append("<button class=\"button button-small");
        if (!extraClass.isEmpty()) {
            html.append(' ').append(extraClass);
        }
        html.append("\" data-act=\"").append(action).append("\" data-slot=\"").append(slot).append('"');
        if (disabled) {
            html.append(" disabled");
        }
        html.append('>').append(text(label)).append("</button>");
        return html.toString();
    }

    private static String chip(String labelKey, String value) {
        return "<span class=\"hud-chip\">" + text(tr(labelKey)) + " <b>" + text(value) + "</b></span>";
    }

    private static String row(String labelKey, String value) {
        return "<li class=\"list-group-item\"><span class=\"text-muted\">" + text(tr(labelKey))
                + "</span><span>" + text(value) + "</span></li>";
    }

    /** 一格背包；没有物品时留一个空格子。 */
    private static String cell(@Nullable String itemExpression) {
        if (itemExpression == null) {
            return "<div class=\"slot\"></div>";
        }
        return "<div class=\"slot\"><item>" + text(itemExpression) + "</item></div>";
    }

    /**
     * 动态文本进 HTML 串之前先摘掉尖括号。
     *
     * <p>AUI 的 HTML 解析器**不做实体解码**（{@code &lt;} 会原样显示出来），所以这里不能用转义，
     * 只能把会破坏标签结构的字符去掉。世界名、玩家起的槽位名、物品 SNBT 都要经过这里。
     */
    private static String text(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("<", "").replace(">", "");
    }

    // ------------------------------------------------------------------ 数据

    @Nullable
    private static Path worldRoot() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        return server == null ? null : RewindApi.worldRoot(server);
    }

    private static Map<String, SnapshotMeta> loadMetas() {
        Path world = worldRoot();
        return world == null ? Map.of() : RewindApi.describeAll(world);
    }

    /** 「这个槽位实际占了多少磁盘」的缓存：键是槽位 + 它的建点时刻，变了才重新量。 */
    private static String occupiedKey = "";
    private static long occupiedValue = -1L;

    /**
     * 这个槽位自己的文件占了磁盘多少字节。
     *
     * <p>就是它目录里那些文件（外加清单 / 块映射 / 背包快照）——region 那些内容已经搬进跨槽位共享的
     * 4 KiB 块存储，不属于任何一个槽位，所以这里只有几十 KB。内容合计看 {@link SnapshotMeta#totalBytes}。
     * 一次目录遍历很便宜，但还是按「槽位 + 建点时刻」缓存，免得每帧都走一遍。
     *
     * @return 字节数；量不出来时返回 -1，调用方退回显示内容合计
     */
    private static long occupiedBytes(String slot, SnapshotMeta meta) {
        if (slot == null || meta == null) {
            return -1L;
        }
        String key = slot + "@" + meta.savedAtMillis;
        if (key.equals(occupiedKey)) {
            return occupiedValue;
        }
        long value = -1L;
        Path world = worldRoot();
        if (world != null) {
            try {
                Long measured = SnapshotUsage.occupiedBytes(world).get(slot);
                if (measured != null) {
                    value = measured;
                }
            } catch (Exception e) {
                // 量不出来不是错误：界面退回显示内容合计就行
                Rewind.LOGGER.warn("Rewind: failed to measure the disk usage of slot {}", slot, e);
            }
        }
        occupiedKey = key;
        occupiedValue = value;
        return value;
    }

    /**
     * 当前世界的游玩时长（tick）。
     *
     * <p>原版的 {@code play_time} 是服务端玩家的统计，客户端的 {@code LocalPlayer} 身上没有，
     * 所以从集成服务端的玩家读。界面开着时世界是暂停的（{@code isPauseScreen()}），读一个 int 不会打架。
     *
     * @return 取不到时 -1
     */
    private static long currentPlaytimeTicks() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || server.getPlayerList().getPlayers().isEmpty()) {
            return -1L;
        }
        return server.getPlayerList().getPlayers().get(0).getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
    }

    private static boolean isRestorable(@Nullable SnapshotMeta meta) {
        return meta != null && meta.isComplete();
    }

    /** 最新写入的手动槽位时间戳；只有手动卡片会挂 {@code newest-flag}，所以特殊槽位不参与。 */
    private static long newestSavedAt(Map<String, SnapshotMeta> metas) {
        long newest = 0L;
        for (String slot : RewindApi.slots()) {
            if (SnapshotLayout.isSpecialSlot(slot)) {
                continue;
            }
            SnapshotMeta meta = metas.get(slot);
            if (meta != null && meta.savedAtMillis > newest) {
                newest = meta.savedAtMillis;
            }
        }
        return newest;
    }

    private boolean matchesFilter(String slot, @Nullable SnapshotMeta meta) {
        if (filter.isEmpty()) {
            return true;
        }
        return title(slot, meta).toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }

    private void sortManual(List<String> slots, Map<String, SnapshotMeta> metas) {
        Comparator<String> byIndex = Comparator.comparingInt(SnapshotLayout::manualIndex);
        Comparator<String> comparator;
        switch (sort) {
            case OLDEST:
                comparator = Comparator.comparingLong(slot -> savedAt(metas, slot));
                break;
            case NAME:
                comparator = Comparator.comparing((String slot) -> title(slot, metas.get(slot)), String.CASE_INSENSITIVE_ORDER);
                break;
            case PLAYTIME:
                comparator = Comparator.comparingLong((String slot) -> playtime(metas, slot)).reversed();
                break;
            case INDEX:
                comparator = byIndex;
                break;
            case RECENT:
            default:
                comparator = Comparator.comparingLong((String slot) -> savedAt(metas, slot)).reversed();
                break;
        }
        slots.sort(comparator.thenComparing(byIndex));
    }

    private static long savedAt(Map<String, SnapshotMeta> metas, String slot) {
        SnapshotMeta meta = metas.get(slot);
        return meta == null ? 0L : meta.savedAtMillis;
    }

    private static long playtime(Map<String, SnapshotMeta> metas, String slot) {
        SnapshotMeta meta = metas.get(slot);
        return meta == null ? -1L : meta.playtimeTicks;
    }

    // ------------------------------------------------------------------ 文案

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static String defaultTitle(String slot) {
        if (SnapshotLayout.SLOT_AUTO.equals(slot)) {
            return tr("rewind.ui.slot.auto");
        }
        if (SnapshotLayout.SLOT_QUICK.equals(slot)) {
            return tr("rewind.ui.slot.quick");
        }
        return tr("rewind.ui.slot.manual", SnapshotLayout.manualIndex(slot));
    }

    static String title(String slot, @Nullable SnapshotMeta meta) {
        if (meta != null && !meta.displayName.isEmpty()) {
            return meta.displayName;
        }
        return defaultTitle(slot);
    }

    private static String coverColor(String slot) {
        int index = RewindApi.slots().indexOf(slot);
        return COVER_COLORS[Math.floorMod(index, COVER_COLORS.length)];
    }

    /**
     * 封面截图那个 `<img>`：没有截图时返回空串，封面就只剩底色。
     *
     * <p>用 `<img>` 而不是 CSS `background-image`，是因为后者在屏幕文档里画不出来——样式、图层、
     * 贴图（`ImageDrawer.isTextureReady` 返回 true）都正常，就是渲染时看不到；`<img>` 走的是 AUI
     * 自己页面里一直在用的那条绘制路径。截图比封面框大，靠封面框的 `overflow:hidden` 裁掉。
     */
    private static String coverImage(String slot, @Nullable SnapshotMeta meta) {
        String world = worldDirName();
        if (meta == null || world == null || !CoverCapture.hasCover(world, slot, meta.savedAtMillis)) {
            return "";
        }
        return "<img class=\"cover-shot\" src=\"" + CoverCapture.coverUrl(world, slot, meta.savedAtMillis) + "\">";
    }

    /** 当前存档的目录名（封面按它分目录放）。 */
    @Nullable
    private static String worldDirName() {
        Path world = worldRoot();
        return world == null ? null : String.valueOf(world.getFileName());
    }

    /** 生物群系 id → 本地化名字；没有对应翻译时退化成 id 本身。 */
    private static String biomeName(@Nullable String biomeId) {
        if (biomeId == null || biomeId.isEmpty()) {
            return tr("rewind.ui.biome.unknown");
        }
        String key = "biome." + biomeId.replace(':', '.');
        return Language.getInstance().has(key) ? Component.translatable(key).getString() : biomeId;
    }

    /** 游戏内时间：第 N 天 HH:MM（从第 1 天开始数，玩家看到的就是这个）。 */
    private static String gameClock(long gameTime) {
        long days = gameTime / 24000L;
        long timeOfDay = gameTime % 24000L;
        long hours = (timeOfDay / 1000L + 6L) % 24L;
        long minutes = (timeOfDay % 1000L) * 60L / 1000L;
        return tr("rewind.ui.time.clock", days + 1, String.format(Locale.ROOT, "%02d:%02d", hours, minutes));
    }

    private static String playtime(long ticks) {
        if (ticks < 0) {
            return tr("rewind.ui.hud.unavailable");
        }
        long seconds = ticks / 20L;
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return tr("rewind.ui.playtime.minutes", minutes);
        }
        return tr("rewind.ui.playtime.hours", minutes / 60L, minutes % 60L);
    }

    private static String relativeTime(long savedAtMillis) {
        if (savedAtMillis <= 0L) {
            return "";
        }
        long minutes = (System.currentTimeMillis() - savedAtMillis) / 60000L;
        if (minutes < 1L) {
            return tr("rewind.ui.time.just_now");
        }
        if (minutes < 60L) {
            return tr("rewind.ui.time.minutes", minutes);
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return tr("rewind.ui.time.hours", hours);
        }
        return tr("rewind.ui.time.days", hours / 24L);
    }

    static String absoluteTime(long savedAtMillis) {
        return ABSOLUTE_TIME.format(Instant.ofEpochMilli(savedAtMillis));
    }

    private static String sizeText(long bytes) {
        if (bytes < 1024L) {
            return tr("rewind.ui.size.bytes", bytes);
        }
        if (bytes < 1024L * 1024L) {
            return tr("rewind.ui.size.kb", String.format(Locale.ROOT, "%.1f", bytes / 1024.0));
        }
        return tr("rewind.ui.size.mb", String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0)));
    }
}
