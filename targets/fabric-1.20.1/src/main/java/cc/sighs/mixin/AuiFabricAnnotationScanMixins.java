package cc.sighs.mixin;

import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.ServiceLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 绕开 ApricityUI fabric 的一个上游 bug。
 *
 * <p>AUI 的 {@code AnnotationScanUtil} 用
 * {@code ServiceLoader.load(IAnnotationScanner.class).findFirst()} 取扫描器实现，而 fabric 那份
 * provider（{@code FabricAnnotationScanner}）**只有 private 无参构造器**——{@code ServiceLoader}
 * 只认 public 无参构造器，于是 AUI 的 main entrypoint 一初始化就抛 {@code NoSuchMethodException}，
 * 整个客户端 / 专用服务器都起不来。neoforge / forge 两份 AUI 的同名 provider 都是 public，
 * 只有 fabric 这份是 private（1.2.4 与 1.2.6 都一样）。
 *
 * <p>这里把那次 {@code findFirst()} 换掉，改成反射构造同一个类并 {@code setAccessible(true)}，
 * 复用 AUI 自己的实现、不动它的逻辑。AUI 上游修好之后可以删掉本类与
 * {@code rewind.mixins.json} 里的登记。
 */
@Mixin(targets = "com.sighs.apricityui.util.AnnotationScanUtil")
public class AuiFabricAnnotationScanMixins {
    private AuiFabricAnnotationScanMixins() {
    }

    @Redirect(method = "<clinit>",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/ServiceLoader;findFirst()Ljava/util/Optional;"))
    private static Optional<?> rewind$instantiateFabricScanner(ServiceLoader<?> loader) {
        try {
            Constructor<?> constructor = Class
                    .forName("com.sighs.apricityui.network.fabricutil.FabricAnnotationScanner")
                    .getDeclaredConstructor();
            constructor.setAccessible(true);
            return Optional.of(constructor.newInstance());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
