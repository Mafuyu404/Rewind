package cc.sighs.rewind.client;

import com.mojang.blaze3d.platform.InputConstants;
import cc.sighs.rewind.Rewind;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 客户端侧的注册入口。只在物理客户端加载（由主类用 {@code FMLEnvironment.getDist()} 判定），
 * 这样专用服务器不会因为引用客户端类而炸掉。
 */
public final class RewindClient {
    private static final String CATEGORY = "key.categories.rewind";

    private static KeyMapping snapshotKey;
    private static KeyMapping restoreKey;

    private RewindClient() {
    }

    public static void setup(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, RewindClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, RewindClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, RewindCommands::register);
    }

    public static KeyMapping snapshotKey() {
        return snapshotKey;
    }

    public static KeyMapping restoreKey() {
        return restoreKey;
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        snapshotKey = new KeyMapping("key.rewind.snapshot", InputConstants.KEY_F7, CATEGORY);
        restoreKey = new KeyMapping("key.rewind.restore", InputConstants.KEY_F8, CATEGORY);
        event.register(snapshotKey);
        event.register(restoreKey);
        Rewind.LOGGER.info("Rewind: registered key mappings (F7=checkpoint, F8=restore)");
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        CheckpointController.tick(minecraft);
        RewindSelfTest.tick(minecraft);

        // 原版只在没有界面时给 KeyMapping 计数，这里再加一道保险：进度屏期间不响应热键
        if (minecraft.screen != null) {
            return;
        }
        if (snapshotKey != null && snapshotKey.consumeClick()) {
            CheckpointController.requestSnapshot("key");
        }
        if (restoreKey != null && restoreKey.consumeClick()) {
            CheckpointController.requestRestore("key", true);
        }
    }
}
