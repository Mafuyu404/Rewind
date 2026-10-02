package cc.sighs.rewind.common;

import javax.annotation.Nullable;

/**
 * 「给这个槽位抓一张封面」的待办请求：建点的人写，客户端下一帧取走。
 *
 * <p>为什么要这么个中立的小盒子：建点在服务端线程（{@code CheckpointWriter}），抓帧在客户端渲染线程
 * （{@code cc.sighs.rewind.client.CoverCapture}），两边不能互相引用——服务端那边不许碰
 * {@code net.minecraft.client.*}（专用服务器上也要能加载）。这里只有三个字段，谁都能碰。
 *
 * <p>时间戳由**建点方**给定（就是索引里的 {@code savedAtMillis}），因为封面文件名里带着它：
 * {@code <槽位>-<毫秒>.png}。AUI 按路径缓存贴图、同一个路径换内容不会重读，所以每次建点都得换个新文件名，
 * 而页面要靠索引里的时刻才能拼出这个文件名。
 */
public final class CoverRequest {
    private static volatile String worldDir;
    private static volatile String slot;
    private static volatile long millis;

    private CoverRequest() {
    }

    /**
     * 请求给 {@code worldDir} 存档里的 {@code slot} 抓一张封面，文件名用 {@code millis}。
     * 任意线程可调（建点就在服务端线程上）。
     */
    public static void request(String worldDir, String slot, long millis) {
        CoverRequest.worldDir = worldDir;
        CoverRequest.slot = slot;
        CoverRequest.millis = millis;
    }

    /** 取走待办请求并清空；没有待办时返回 null。 */
    @Nullable
    public static Pending poll() {
        String world = worldDir;
        String pendingSlot = slot;
        long pendingMillis = millis;
        if (world == null || pendingSlot == null) {
            return null;
        }
        worldDir = null;
        slot = null;
        return new Pending(world, pendingSlot, pendingMillis);
    }

    /** 一条待办请求。 */
    public static final class Pending {
        public final String worldDir;
        public final String slot;
        public final long millis;

        Pending(String worldDir, String slot, long millis) {
            this.worldDir = worldDir;
            this.slot = slot;
            this.millis = millis;
        }
    }
}
