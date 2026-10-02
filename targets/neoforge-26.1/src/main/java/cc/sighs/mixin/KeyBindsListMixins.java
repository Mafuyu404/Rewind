package cc.sighs.mixin;

import cc.sighs.rewind.client.RewindClient;
import cc.sighs.rewind.client.RewindKeyBindsScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把原版按键绑定列表筛成「只有 Rewind 那两个热键」。
 *
 * <p>列表是在 {@code KeyBindsList} 的构造函数里照着 {@code Options.keyMappings} 这一整份数组铺出来的
 * （26.1 里是 {@code ArrayUtils.clone(minecraft.options.keyMappings)}），所以「只列两个热键」最省事的
 * 做法不是铺完再删（{@code addEntry} / {@code clearEntries} 都是 {@code AbstractSelectionList} 的
 * protected 方法，跨包调不到，{@code @WrapOperation} 又要求处理器参数类型与原方法完全一致、
 * 那个 {@code AbstractSelectionList$Entry} 同样够不着），而是**在这一趟里把数据源换掉**：
 * 读到数组时返回一个只装着两个热键的新数组。
 *
 * <p>这样连分类标题都自动只出现一次（两个键同属 Rewind 那个分类），别的按键连条目都不会建，
 * {@code maxNameWidth} 也只按这两条算。从原版「选项 → 按键」进来的那一页走的是同一个构造函数，
 * 但那时 {@code keyBindsScreen} 不是 {@link RewindKeyBindsScreen}，所以原样放行。
 *
 * <p>只按**身份**（{@code ==}）挑那两个热键：按分类挑会把 F9「打开时间树」也带进来。
 *
 * <p>26.1 里 {@code KeyBindsList#keyBindsScreen} 变成了 {@code private final}（构造完就不换），
 * 对它只读不写，所以 {@code @Shadow} 照旧成立。
 *
 * <p>{@link KeyBindsScreenAccess} 是给自测看的：{@code KeyBindsScreen#keyBindsList}
 * 在 26.1 上仍是 private 非 final 字段，所以 {@code @Accessor} 直接可用。
 */
public final class KeyBindsListMixins {
    private KeyBindsListMixins() {
    }

    @Mixin(KeyBindsList.class)
    public abstract static class OnlyRewindKeys {
        /** 这个列表是为哪一页铺的——只有我们自己那一页才筛。 */
        @Shadow
        KeyBindsScreen keyBindsScreen;

        @Redirect(
                method = "<init>",
                at = @At(
                        value = "FIELD",
                        target = "Lnet/minecraft/client/Options;keyMappings:[Lnet/minecraft/client/KeyMapping;",
                        opcode = Opcodes.GETFIELD))
        private KeyMapping[] rewind$onlyRewindKeys(Options options) {
            if (!(this.keyBindsScreen instanceof RewindKeyBindsScreen)) {
                return options.keyMappings;
            }
            KeyMapping snapshot = RewindClient.snapshotKey();
            KeyMapping restore = RewindClient.restoreKey();
            if (snapshot == null || restore == null) {
                return options.keyMappings;
            }
            // 每次都要给新数组：构造函数会就地排序它
            return new KeyMapping[]{snapshot, restore};
        }
    }

    /** 给自测看：这一页的列表里到底铺了几条。 */
    @Mixin(KeyBindsScreen.class)
    public interface KeyBindsScreenAccess {
        @Accessor("keyBindsList")
        KeyBindsList rewind$list();
    }
}
