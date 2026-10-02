package cc.sighs.rewind.server;

import cc.sighs.rewind.Rewind;
import net.fabricmc.loader.api.FabricLoader;

/** 从 mod 容器读取版本号，失败时退化为 unknown（只用于元数据展示）。 */
final class RewindVersion {
    private RewindVersion() {
    }

    static String of() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(Rewind.MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
