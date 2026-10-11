package cc.sighs.rewind.api;

/**
 * 这次回溯被「开始事件」的监听者取消了：什么都没做。
 *
 * <p>{@link RewindApi} 的两个回溯入口在这时候返回一条 {@code success=false} 的结果，
 * {@link RewindResult#failure} 就是它，{@link #getMessage()} 是取消原因（监听者写在
 * {@link RollbackRequest#cancelReason} 里的那句，没写就是「canceled by a listener」）。
 *
 * <p>单独一个类型是为了让调用方能把「被取消」和「失败了」分开——典型是客户端控制器：
 * 被取消时**不能**退回「关世界 → 覆盖 → 重开」那条路，那等于绕过取消、把文件照样盖回去。
 *
 * <p>{@link #toString()} 返回原因本身，所以日志与失败信息里不会多出一串类名。
 */
public final class RollbackCanceledException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public RollbackCanceledException(String reason) {
        super(reason);
    }

    @Override
    public String toString() {
        return String.valueOf(getMessage());
    }
}
