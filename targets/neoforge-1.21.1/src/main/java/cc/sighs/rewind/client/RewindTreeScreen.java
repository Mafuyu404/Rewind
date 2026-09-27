package cc.sighs.rewind.client;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.api.RewindResult;
import cc.sighs.rewind.server.RewindServerConfig;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.KeyEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
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
 * <p>读取 / 覆盖不在这里执行：它们转交 {@link RewindApi#requestRollback}/{@link RewindApi#requestCheckpoint}，
 * 也就是 F7 / F8 那条带过渡的管线。过渡是整帧后处理，界面开着会挡在效果上面，所以
 * {@code CheckpointController} 动手前会先把本界面摘掉——玩家看到的是「点一下 → 界面收起 → 世界糊住 → 换回来」。
 *
 * <p>只有重命名和删除是就地完成的（纯文件操作，走 {@link RewindApi#renameCheckpoint} /
 * {@link RewindApi#deleteCheckpoint}），做完直接重画当前页。
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
    /** 提示条停留的 tick 数（50 tick ≈ 2.5 秒，和模板里 CSS 的节奏一致）。 */
    private static final int TOAST_TICKS = 50;
    private static final int MAX_TOASTS = 4;
    /** 主背包的栏位数（原版 36 格，9 × 4）。 */
    /** 快捷栏格数（原版物品栏的前 9 格）。折叠状态下只显示这一行。 */
    private static final int HOTBAR_SLOTS = 9;
    /** 主背包的格数（9 × 4，含快捷栏）。 */
    private static final int MAIN_INVENTORY_SLOTS = 36;

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

    /** 一条提示条：元素 + 还剩多少 tick 撤掉。 */
    private static final class Toast {
        private final Element element;
        private int ticksLeft;

        Toast(Element element) {
            this.element = element;
            this.ticksLeft = TOAST_TICKS;
        }
    }

    private final List<Toast> toasts = new ArrayList<>();

    private String selectedSlot = RewindApi.DEFAULT_SLOT;
    private String filter = "";
    /** 列表排序方式。默认按槽位序号，槽位卡片的顺序默认就是固定的 1..8。 */
    private SortMode sort = SortMode.INDEX;
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
        // 文档是重建出来的，旧文档上的提示条元素已经没意义了
        toasts.clear();
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
            sortBox.addEventListener("input", event -> {
                sort = SortMode.of(sortBox.getValue());
                render(document);
            });
        }

        Element renameInput = document.querySelector("#renameInput");
        if (renameInput != null) {
            renameInput.addEventListener("keydown", event -> {
                if (event instanceof KeyEvent key && key.keyCode == GLFW.GLFW_KEY_ENTER) {
                    commitRename(document);
                }
            });
        }

        render(document);
    }

    @Override
    public void tick() {
        super.tick();
        flushFinishedSave();
        if (toasts.isEmpty()) {
            return;
        }
        for (Iterator<Toast> iterator = toasts.iterator(); iterator.hasNext(); ) {
            Toast toast = iterator.next();
            if (--toast.ticksLeft <= 0) {
                toast.element.remove();
                iterator.remove();
            }
        }
    }

    /** 后台写盘做完了就收尾：给一条提示条，然后刷新界面（卡片上的时间、大小都会变）。 */
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
            toast(document, "rewind.ui.toast.saved", title(slot, RewindApi.describe(worldRoot(), slot)));
        } else {
            toast(document, "rewind.ui.toast.save_failed", String.valueOf(result.failure));
        }
        render(document);
    }

    // ------------------------------------------------------------------ 交互

    private void onClick(Event event) {
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
            toast(document, "rewind.ui.toast.no_snapshot");
            return;
        }
        toast(document, "rewind.ui.toast.loading", title(slot, meta));
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
                toast(document, "rewind.ui.toast.deleted", name);
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
     */
    private void startBackgroundSave(Document document, String slot) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            toast(document, "rewind.ui.toast.no_world");
            return;
        }
        savingSlot = slot;
        savingResult = null;
        toast(document, "rewind.ui.toast.saving", title(slot, RewindApi.describe(worldRoot(), slot)));
        server.execute(() -> savingResult = RewindApi.createCheckpoint(server, slot, "ui"));
    }

    /**
     * 特殊槽位（自动 / 快速）的「设置」弹窗。
     *
     * <p>这两张卡是固定角色，没有名字可改，所以它们的第三个按钮是设置：里面是这张卡当前的状态，
     * 以及它到底代表什么。**目前没有可调的项**——想往里加什么（比如原版自动保存的间隔、
     * 要不要跟着原版自动保存建点）是另一件事，先说清楚再动。
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
                // 自动存档这张卡唯一的可调项：跟着原版自动保存建点
                boolean enabled = RewindServerConfig.autoCheckpointEnabled();
                html.append("<li class=\"list-group-item\"><span class=\"text-muted\">")
                        .append(text(tr("rewind.ui.settings.auto.toggle"))).append("</span>")
                        .append("<button class=\"button button-small ")
                        .append(enabled ? "button-secondary" : "button-tertiary")
                        .append("\" data-act=\"toggle-auto\">")
                        .append(text(tr(enabled ? "rewind.ui.settings.auto.on" : "rewind.ui.settings.auto.off")))
                        .append("</button></li>");
            }
            if (meta == null) {
                html.append(row("rewind.ui.detail.status", tr("rewind.ui.slot.unused")));
            } else {
                html.append(row("rewind.ui.detail.world", meta.worldName));
                html.append(row("rewind.ui.detail.saved_at",
                        absoluteTime(meta.savedAtMillis) + "（" + relativeTime(meta.savedAtMillis) + "）"));
                html.append(row("rewind.ui.detail.playtime", playtime(meta.playtimeTicks)));
                html.append(row("rewind.ui.detail.biome", biomeName(meta.biomeId)));
                html.append(row("rewind.ui.detail.size", sizeText(meta.totalBytes)));
                html.append(row("rewind.ui.detail.status", meta.status));
            }
            info.setInnerHTML(html.toString());
        }
        Element note = document.querySelector("#settingsNote");
        if (note != null) {
            note.setTextContent(tr(SnapshotLayout.SLOT_AUTO.equals(slot)
                    ? "rewind.ui.settings.note.auto"
                    : "rewind.ui.settings.note.quick"));
        }
        openModal(document, "modalSettings");
    }

    /** 「自动存档」卡上那个开关：跟着原版自动保存建点。改完立刻落盘，并重画弹窗里那一行。 */
    private void toggleAutoCheckpoint(Document document) {
        boolean enabled = !RewindServerConfig.autoCheckpointEnabled();
        RewindServerConfig.setAutoCheckpointEnabled(enabled);
        toast(document, enabled ? "rewind.ui.toast.auto_on" : "rewind.ui.toast.auto_off");
        // 弹窗里那一行（开关状态 + 按钮配色）要跟着变，重开一次最省事
        openSettings(document, SnapshotLayout.SLOT_AUTO);
    }

    private void askRename(Document document, String slot) {
        if (slot == null || !usable(document)) {
            return;
        }
        SnapshotMeta meta = RewindApi.describe(worldRoot(), slot);
        if (meta == null) {
            toast(document, "rewind.ui.toast.no_snapshot");
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
            toast(document, "rewind.ui.toast.name_invalid");
            return;
        }
        if (RewindApi.renameCheckpoint(worldRoot(), slot, name)) {
            toast(document, "rewind.ui.toast.renamed", name);
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
            toast(document, "rewind.ui.toast.busy");
            return false;
        }
        if (RewindApi.isBusy()) {
            toast(document, "rewind.ui.toast.busy");
            return false;
        }
        if (!minecraft.hasSingleplayerServer() || minecraft.level == null) {
            toast(document, "rewind.ui.toast.no_world");
            return false;
        }
        if (minecraft.getSingleplayerServer().isPublished()) {
            toast(document, "rewind.ui.toast.lan");
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

    private void toast(Document document, String messageKey, Object... args) {
        Element area = document.querySelector("#toastArea");
        if (area == null) {
            return;
        }
        Element element = document.createElement("div");
        // 模板里的 .toast-2 靠 .show 从透明渐变到不透明；直接带上，免得过渡没生效时看不见
        element.setClassName("toast-2 show");
        element.setTextContent(tr(messageKey, args));
        area.appendChild(element);
        while (area.children.size() > MAX_TOASTS) {
            area.children.get(0).remove();
        }
        toasts.add(new Toast(element));
    }

    // ------------------------------------------------------------------ 渲染

    private void render(Document document) {
        Map<String, SnapshotMeta> metas = loadMetas();
        renderHud(document);
        renderSlots(document, metas);
        renderDetail(document, metas);
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
            count.setInnerText(tr("rewind.ui.count", used, SnapshotLayout.MANUAL_SLOT_COUNT));
        }
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
        html.append(row("rewind.ui.detail.saved_at",
                absoluteTime(meta.savedAtMillis) + "（" + relativeTime(meta.savedAtMillis) + "）"));
        html.append(row("rewind.ui.detail.playtime", playtime(meta.playtimeTicks)));
        html.append(row("rewind.ui.detail.biome", biomeName(meta.biomeId)));
        html.append(row("rewind.ui.detail.position",
                String.format(Locale.ROOT, "%.0f, %.0f, %.0f", meta.playerX, meta.playerY, meta.playerZ)));
        html.append(row("rewind.ui.detail.size", sizeText(meta.totalBytes)));
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

    private String specialCard(String slot, SnapshotMeta meta) {
        boolean quick = SnapshotLayout.SLOT_QUICK.equals(slot);
        StringBuilder html = new StringBuilder();
        html.append("<article class=\"card special-card").append(slot.equals(selectedSlot) ? " selected" : "")
                .append("\" data-act=\"select\" data-slot=\"").append(slot).append("\" tabindex=\"0\" role=\"button\">");
        html.append("<div class=\"special-cover\" style=\"background:").append(coverColor(slot)).append("\">")
                .append(coverImage(slot, meta)).append("</div>");
        html.append("<div class=\"special-body\">");
        html.append("<div class=\"special-head\"><span class=\"badge ").append(quick ? "badge-purple" : "badge-warning")
                .append("\">").append(text(tr(quick ? "rewind.ui.slot.badge.quick" : "rewind.ui.slot.badge.auto")))
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

    private static String title(String slot, @Nullable SnapshotMeta meta) {
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

    private static String absoluteTime(long savedAtMillis) {
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
