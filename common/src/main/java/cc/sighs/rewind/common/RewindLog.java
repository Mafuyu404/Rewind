package cc.sighs.rewind.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * common 的统一日志出口。
 *
 * <p>common 不依赖任何加载器，日志走 slf4j 门面；具体后端（Minecraft 自带的 log4j 配置）由运行时提供。
 * 各 target 里的 {@code Rewind.LOGGER} 现在转发到这里，消息里的 {@code Rewind: } 前缀保持不变。
 */
public final class RewindLog {
    public static final Logger LOGGER = LoggerFactory.getLogger("Rewind");

    private RewindLog() {
    }
}
