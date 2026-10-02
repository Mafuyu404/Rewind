package cc.sighs.rewind.client;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import cc.sighs.rewind.common.CoverRequest;
import cc.sighs.rewind.Rewind;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;

/**
 * 存档点的封面图：建点时抓一张**没有界面**的画面，存成 PNG，时间树上的卡片封面读它。
 *
 * <p><b>抓帧的时机</b>：建点的人在服务端线程上（{@link CoverRequest}），这里在下一帧的
 * {@code RenderFrameEvent} 里兑现。这段时间服务端正忙在落盘和拷贝上、世界不会 tick，所以抓到的
 * 就是存档点那一刻的画面。
 *
 * <p><b>没有界面</b>：抓帧那一帧把 HUD（{@code options.hideGui}）和当前界面都临时摘掉，抓完立刻还原——
 * 只影响这一帧。界面是直接换 {@code Minecraft.screen} 字段摘的（不走 {@code setScreen}，免得触发
 * {@code removed()/init()} 把界面状态清掉、把 AUI 文档重建一遍），所以不管在不在界面里建点
 * （F7 会先把界面摘掉，界面上的「覆盖」则要求界面一直开着），封面都是干净的世界画面。
 *
 * <p><b>存哪儿</b>：{@code <gameDir>/apricity/rewind/covers/<存档目录名>/<槽位>-<毫秒>.png}。
 * 放这儿是因为 AUI 的加载根之一就是 {@code <gameDir>/apricity/}，页面里写 {@code /rewind/covers/...}
 * 就能直接当 {@code background-image} 读；放存档目录里反而读不到——AUI 只认资源包和
 * {@code <gameDir>/apricity/}，任意绝对路径解析不了。
 *
 * <p>文件名带建点时刻，是因为 AUI 按路径缓存贴图、同路径换内容不会重读：每建一次点就换一个文件名，
 * 页面按索引里的 {@code savedAtMillis} 拼出当前那一个。同一个槽位的旧封面在写新的时一起删掉。
 *
 * <p><b>26.1 的改动</b>：原版截图 API 从「同步返回 {@code NativeImage}」改成了
 * {@code Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)}：像素在调用那一刻就读回来了
 * （和以前一样，所以封面不会带上同一帧稍后才做的过渡后处理），但回调是延后一帧执行的，
 * 图片的写盘也就跟着推后——界面那边本来就要等封面落地再重画，容得下这点延迟。
 */
public final class CoverCapture {
    /** 封面目录名，相对 {@code <gameDir>/apricity/}。 */
    private static final String COVER_DIR = "rewind/covers";

    /** 本帧要抓的那条请求。 */
    private static CoverRequest.Pending pending;
    private static boolean hidGui;
    private static boolean previousHideGui;
    /** 抓帧那一帧临时摘掉的界面；只摘一帧，不碰它的生命周期。 */
    private static Screen hiddenScreen;

    private CoverCapture() {
    }

    /**
     * 帧开始：有请求就把界面和 HUD 都藏起来。
     *
     * <p>必须都在**这一帧画之前**摘掉，抓到的帧里才没有界面、也没有 HUD。界面是直接换字段摘的：
     * 走 {@code setScreen(null)} 会触发 {@code removed()}（AUI 会把文档整个丢掉），再挂回来
     * 就要重建一遍，界面的滚动位置、选中项全没了，玩家还会看到闪一下。
     */
    public static void onFramePre(Minecraft minecraft) {
        if (pending != null) {
            return;
        }
        CoverRequest.Pending request = CoverRequest.poll();
        if (request == null) {
            return;
        }
        if (minecraft.screen != null) {
            hiddenScreen = minecraft.screen;
            minecraft.screen = null;
        }
        previousHideGui = minecraft.options.hideGui;
        minecraft.options.hideGui = true;
        hidGui = true;
        pending = request;
    }

    /**
     * 帧结束：抓帧、写文件、还原界面与 HUD。
     *
     * <p>注册成 HIGHEST 优先级是为了抢在过渡后处理之前读到**没被效果处理过**的那一帧——
     * 建点时的饱和度过渡刚好也在这一帧开始，晚一步封面就会带上饱和度。
     */
    public static void onFramePost(Minecraft minecraft) {
        CoverRequest.Pending request = pending;
        if (request == null) {
            return;
        }
        pending = null;
        if (hidGui) {
            minecraft.options.hideGui = previousHideGui;
            hidGui = false;
        }
        if (hiddenScreen != null) {
            minecraft.screen = hiddenScreen;
            hiddenScreen = null;
        }
        try {
            RenderTarget target = minecraft.getMainRenderTarget();
            // 像素在这一次调用里就被读回来了；回调（拿到 NativeImage）会稍后执行，写盘放在回调里。
            Screenshot.takeScreenshot(target, image -> writeCover(minecraft, request, image));
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to capture the cover for {}", request.slot, t);
        }
    }

    private static void writeCover(Minecraft minecraft, CoverRequest.Pending request, NativeImage image) {
        Path file = coverFile(minecraft, request.worldDir, request.slot, request.millis);
        Util.ioPool().execute(() -> {
            try {
                deleteCovers(minecraft, request.worldDir, request.slot, request.millis);
                Files.createDirectories(file.getParent());
                image.writeToFile(file);
                Rewind.LOGGER.info("Rewind: cover for {} written to {} ({}x{})", request.slot, file,
                        image.getWidth(), image.getHeight());
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to write the cover for {}", request.slot, t);
            } finally {
                image.close();
            }
        });
    }

    /** 封面文件：{@code <gameDir>/apricity/rewind/covers/<存档目录名>/<槽位>-<毫秒>.png}。 */
    public static Path coverFile(Minecraft minecraft, String worldDir, String slot, long millis) {
        return coverDir(minecraft, worldDir).resolve(slot + "-" + millis + ".png");
    }

    /** 页面里引用封面的路径；`/` 开头 = 相对 AUI 的 {@code apricity} 根。 */
    public static String coverUrl(String worldDir, String slot, long millis) {
        return "/" + COVER_DIR + "/" + worldDir + "/" + slot + "-" + millis + ".png";
    }

    /** 这个槽位这一版有没有封面图（页面按它决定用截图还是退回纯色块）。 */
    public static boolean hasCover(String worldDir, String slot, long millis) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && Files.isRegularFile(coverFile(minecraft, worldDir, slot, millis));
    }

    /** 槽位被删掉时把它的封面一起清掉——封面不在存档目录里，删槽位那条路带不上它。 */
    public static void deleteCovers(String worldDir, String slot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        deleteCovers(minecraft, worldDir, slot, 0L);
    }

    /** 删掉这个槽位的封面；{@code keepMillis > 0} 时留下那一版（刚写好的那张）。 */
    private static void deleteCovers(Minecraft minecraft, String worldDir, String slot, long keepMillis) {
        Path dir = coverDir(minecraft, worldDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        String keep = slot + "-" + keepMillis + ".png";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, slot + "-*.png")) {
            for (Path path : stream) {
                if (!path.getFileName().toString().equals(keep)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            Rewind.LOGGER.warn("Rewind: failed to clean up old covers for {}", slot, e);
        }
    }

    private static Path coverDir(Minecraft minecraft, String worldDir) {
        return minecraft.gameDirectory.toPath().resolve("apricity").resolve(COVER_DIR).resolve(worldDir);
    }
}
