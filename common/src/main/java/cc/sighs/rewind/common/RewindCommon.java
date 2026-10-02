package cc.sighs.rewind.common;

/**
 * Shared loader-independent code belongs in this module.
 *
 * <p>注意：本模块的包名不要与任何 target 的包重名（target 的入口类都在 {@code cc.sighs}）。
 * dev 运行时 moddev 会把 common 的 jar 作为独立的自动模块放进模块层，如果和 target 的模组模块
 * 导出同一个包，ModLauncher 会直接报 {@code ResolutionException: Modules ... export package ...}。
 */
public final class RewindCommon {
    /** 模组 id，与根 {@code gradle.properties} 的 {@code mod_id} 一致。 */
    public static final String MOD_ID = "rewind";

    private RewindCommon() {
    }
}
