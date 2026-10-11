package cc.sighs.rewind.event;

import cc.sighs.rewind.api.RewindResult;
import cc.sighs.rewind.api.RollbackRequest;
import net.minecraftforge.eventbus.api.Event;

/**
 * 回溯结束：**不可取消**（故意不带 {@code @Cancelable}——覆写它的 {@code setCanceled} 会直接抛
 * {@code UnsupportedOperationException}）。
 *
 * <p>成功、失败都会来一次；被 {@link RollbackStartEvent} 取消的那次没有它。
 * {@link #result} 就是调用方拿到的那份 {@link RewindResult}（{@code success} / {@code failure} /
 * {@code summary} / {@code copiedFiles} / {@code millis}），{@link #request} 与开始事件收到的是
 * 同一个对象。
 *
 * <p>典型用途是「磁盘回到过去了，自己模组缓存的那份世界状态也得跟着回」——那种缓存不归世界
 * 生命周期管，Rewind 自己也要为精妙核心做一次（见 {@code SophisticatedCoreCompat}）：
 *
 * <pre>{@code
 * &#64;SubscribeEvent
 * public void onRollbackEnd(RollbackEndEvent event) {
 *     if (event.result.success) {
 *         invalidateMyDecodedCaches();
 *     }
 * }
 * }</pre>
 *
 * <p>线程同 {@link RollbackStartEvent}。
 */
public class RollbackEndEvent extends Event {
    /** 这次回溯的请求（与开始事件同一个对象）。 */
    public final RollbackRequest request;
    /** 这次回溯的结果：成功与失败都在这里。 */
    public final RewindResult result;

    public RollbackEndEvent(RollbackRequest request, RewindResult result) {
        this.request = request;
        this.result = result;
    }
}
