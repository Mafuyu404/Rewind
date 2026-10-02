package cc.sighs.rewind.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;

/**
 * 只列 Rewind 那两个热键的按键绑定页——本质就是原版那一页，只是列表被筛过。
 *
 * <p>筛的地方在 {@code cc.sighs.mixin.KeyBindsListMixins}：它认的就是本类（列表拿到的是哪个
 * {@code KeyBindsScreen}），所以只有从这儿打开的按键页会被筛，玩家从原版「选项 → 按键」进来看到的
 * 还是完整的列表。
 *
 * <p>为什么要筛而不是自己画一页：改键那一套交互（点一下等按键、Esc 取消、撞键标红、重置）
 * 全在 {@code KeyBindsList$KeyEntry} 里，自己重写一遍只会走样。
 */
public final class RewindKeyBindsScreen extends KeyBindsScreen {
    public RewindKeyBindsScreen(Screen parent, Options options) {
        super(parent, options);
    }

    /** 这一页只显示这两个热键。 */
    public static boolean shows(KeyMapping mapping) {
        return mapping != null && (mapping == RewindClient.snapshotKey() || mapping == RewindClient.restoreKey());
    }
}
