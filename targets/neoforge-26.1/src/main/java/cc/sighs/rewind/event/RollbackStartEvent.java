package cc.sighs.rewind.event;

import cc.sighs.rewind.api.RollbackRequest;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * 回溯开始之前：**可以取消**（在 {@code NeoForge.EVENT_BUS} 上发布）。
 *
 * <p>取消之后这次回溯完全不做：引擎一次都不跑，调用方拿到一条 {@code failure} 为
 * {@link cc.sighs.rewind.api.RollbackCanceledException} 的失败结果，也就不会有
 * {@link RollbackEndEvent}（没开始就没有结束）。客户端认得出这个结果，所以**不会**退回
 * 「关世界 → 覆盖 → 重开」那条路——那等于绕过取消、把文件照样盖回去。
 *
 * <pre>{@code
 * &#64;SubscribeEvent
 * public void onRollbackStart(RollbackStartEvent event) {
 *     if (bossFightInProgress()) {
 *         event.setCanceled(true);
 *         event.setCancelReason("正在打 BOSS");
 *     }
 * }
 * }</pre>
 *
 * <p><b>一次用户可见的回溯可能发不止一次</b>：原地回滚失败之后客户端会退回文件覆盖那条路，
 * 那条路会再发一次开始 / 结束（{@link RollbackRequest#inPlace} 为 {@code false}）。要取消就按
 * 「这次尝试」判断，别假设一个玩家动作只会来一次。
 *
 * <p><b>线程</b>：原地回滚这条路上它在**服务端线程**上发布；只覆盖文件那条路（世界已经关了，
 * 没有服务端线程可言）在调用它的那条线程上——客户端是它自己那条重开线程。
 */
public class RollbackStartEvent extends Event implements ICancellableEvent {
    /** 这次回溯的请求：槽位、来源、走哪条路、目标存档点的元数据。 */
    public final RollbackRequest request;

    public RollbackStartEvent(RollbackRequest request) {
        this.request = request;
    }

    /**
     * 取消原因（可选，建议写）：进日志，也会跟着失败信息回到调用方。
     *
     * <p>它只是把原因写在 {@link RollbackRequest#cancelReason} 上，**不会替你取消**——
     * 取消仍然是 {@code setCanceled(true)}（就是这个事件类型上的
     * {@link ICancellableEvent#setCanceled(boolean)}）。
     */
    public void setCancelReason(String reason) {
        request.cancelReason = reason == null ? "" : reason;
    }
}
