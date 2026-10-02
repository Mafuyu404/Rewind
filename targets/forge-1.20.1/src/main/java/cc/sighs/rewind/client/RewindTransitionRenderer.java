package cc.sighs.rewind.client;

import cc.sighs.rewind.Rewind;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 存档点 / 回溯的过渡后处理。每帧结束时把整幅画面（或它的冻结副本）重新采样一遍画回屏幕：
 * 存档是饱和度提高，读档是高斯模糊，强度由 {@link RewindTransition} 的包络给出。
 *
 * <p>为什么要有「冻结副本」：回溯要走原版那条「关世界 → 换文件 → 重开世界」的路，
 * 中间有一段客户端根本没有 {@code ClientLevel} 的时间（原版用加载界面盖住它）。
 * 我们在过渡开始时对主渲染目标做一次 blit 存下来，世界消失后就继续重采样这张副本，
 * 于是整个重载过程都被「糊住的最后一帧」盖住——既不出现任何界面，也没有黑屏断层。
 *
 * <p><b>调用时机（Forge 1.20.1）</b>：{@code TickEvent.RenderTickEvent} 的 {@code Phase.END}，
 * 也就是 {@code Minecraft.runTick(boolean)} 里 {@code gameRenderer.render(...)} 之后、
 * {@code mainRenderTarget.blitToScreen(...)} 之前——同一刻 NeoForge 1.21.1 会 post
 * {@code RenderFrameEvent.Post}。所以这一帧确实还没上屏，效果要画进**主渲染目标**。
 */
public final class RewindTransitionRenderer {
    /** 单帧最多按多少秒推进包络，避免卡顿后效果跳变。 */
    private static final float MAX_FRAME_SECONDS = 0.1F;
    /** 着色器里的效果编号，必须和 rewind_transition.fsh 保持一致。 */
    private static final int MODE_SATURATION = 1;

    private static ShaderInstance shader;
    private static boolean shaderFailed;
    private static TextureTarget capturedFrame;
    /** 自测里每段过渡只存一张截图。 */
    private static boolean screenshotTaken;
    private static long lastFrameNanos;
    private static boolean wasActive;

    private RewindTransitionRenderer() {
    }

    public static void onRenderFramePost(Minecraft minecraft) {
        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos == 0L ? 0.0F : (now - lastFrameNanos) / 1.0E9F;
        lastFrameNanos = now;
        RewindTransition.tick(Math.min(deltaSeconds, MAX_FRAME_SECONDS));

        boolean active = RewindTransition.isActive();
        if (active != wasActive) {
            wasActive = active;
            if (active) {
                // 每段过渡重新允许存一张自测截图
                screenshotTaken = false;
            }
        }
        if (active) {
            // 两种效果都是后处理：存档提高饱和度，读档高斯模糊（世界中间会消失，靠「模糊 + 最后一帧
            // 遮罩」把那段盖过去）
            renderTransition(minecraft);
        }
    }

    private static void renderTransition(Minecraft minecraft) {
        // 过渡期间只允许我们自己那个什么都不画的逻辑屏；出现别的界面就说明情况不对，别去覆盖它
        if (minecraft.screen != null && !(minecraft.screen instanceof RewindBlankScreen)) {
            return;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        int width = main.width;
        int height = main.height;
        if (width <= 0 || height <= 0) {
            return;
        }
        // 每帧把当前帧拷进自己的贴图：
        //  1) 后处理必须读「副本」写「主目标」，否则是在读写同一张贴图；
        //  2) 世界消失之后，这张副本就是唯一还能显示的东西（主目标此时只剩上一帧的残留）。
        if (minecraft.level != null) {
            copyFrame(main);
        }
        if (capturedFrame == null) {
            return;
        }
        ShaderInstance instance = shader(minecraft);
        if (instance == null) {
            return;
        }

        // 这一帧还没被 blit 到屏幕，所以效果要画进主渲染目标，原版随后会把它显示出来
        RenderSystem.assertOnRenderThread();
        main.bindWrite(false);
        RenderSystem.viewport(0, 0, width, height);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderTexture(0, capturedFrame.getColorTextureId());
        RenderSystem.setShader(() -> instance);
        setFloat(instance, "EffectStrength", RewindTransition.strength());
        setInt(instance, "EffectMode",
                RewindTransition.effect() == RewindTransition.Effect.SATURATION ? MODE_SATURATION : 0);
        // 效果的浓度/半径都来自 run/config/rewind-client.toml
        setFloat(instance, "SaturationBoost", RewindClientConfig.saturationBoost());
        setFloat(instance, "BlurRadius", RewindClientConfig.blurRadius());
        setVec2(instance, "TexelSize", 1.0F / width, 1.0F / height);

        // 1.20.1 没有 `BufferBuilder.addVertex(...)`：四个分量要显式填满，`endVertex()` 才会通过
        // （原版 RenderTarget._blitToScreen 也是这么写的）。BLIT_SCREEN 的 Position 就是 0..1 的
        // 屏幕坐标，顶点着色器再把它换算成 NDC。
        BufferBuilder builder = RenderSystem.renderThreadTesselator().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        builder.vertex(0.0D, 0.0D, 0.0D).uv(0.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        builder.vertex(1.0D, 0.0D, 0.0D).uv(1.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        builder.vertex(1.0D, 1.0D, 0.0D).uv(1.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        builder.vertex(0.0D, 1.0D, 0.0D).uv(0.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        BufferUploader.drawWithShader(builder.end());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        // 别把自定义着色器留给下一步的 blit
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        maybeScreenshot(minecraft);
    }

    /**
     * 自测时在满强度存一张截图（{@code run/screenshots/rewind-<效果>.png}）。
     *
     * <p>过渡是视觉效果，日志看不出对错：结构由 {@code RewindSelfTest} 那套断言守着，
     * 「画面长什么样」只能留张图给人眼（或视觉模型）确认。每段过渡只存一张——一段过渡会连着
     * 几十帧都满足「满强度」，不设这个闩会刷一屏文件。
     */
    private static void maybeScreenshot(Minecraft minecraft) {
        if (!RewindSelfTest.isEnabled() || screenshotTaken || RewindTransition.strength() < 0.95F) {
            return;
        }
        screenshotTaken = true;
        Screenshot.grab(minecraft.gameDirectory, "rewind-" + RewindTransition.effect() + ".png",
                minecraft.getMainRenderTarget(), component -> {
                });
    }

    /** 把主渲染目标的当前画面复制到我们自己的贴图里。 */
    private static void copyFrame(RenderTarget main) {
        if (capturedFrame == null || capturedFrame.width != main.width || capturedFrame.height != main.height) {
            if (capturedFrame != null) {
                capturedFrame.destroyBuffers();
            }
            capturedFrame = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            capturedFrame.setFilterMode(GL11.GL_LINEAR);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, capturedFrame.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.frameBufferId);
    }

    /**
     * 后处理是否可用。着色器加载失败时过渡没有画面可依靠，这时候要让原版加载屏正常工作，
     * 否则玩家会看到一段黑屏空档。
     */
    public static boolean isEffectAvailable() {
        return shader(Minecraft.getInstance()) != null;
    }

    /** 延迟加载后处理着色器；加载失败只记一次日志，效果静默不生效，不影响正常游玩。 */
    private static ShaderInstance shader(Minecraft minecraft) {
        if (shader == null && !shaderFailed) {
            try {
                // 1.20.1 没有 ResourceLocation.fromNamespaceAndPath，用构造函数
                shader = new ShaderInstance(minecraft.getResourceManager(),
                        new ResourceLocation("rewind", "rewind_transition"),
                        DefaultVertexFormat.BLIT_SCREEN);
            } catch (Throwable e) {
                shaderFailed = true;
                Rewind.LOGGER.error("Rewind: cannot load the transition shader, transitions are disabled", e);
            }
        }
        return shader;
    }

    private static void setFloat(ShaderInstance instance, String name, float value) {
        Uniform uniform = instance.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void setInt(ShaderInstance instance, String name, int value) {
        Uniform uniform = instance.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void setVec2(ShaderInstance instance, String name, float x, float y) {
        Uniform uniform = instance.getUniform(name);
        if (uniform != null) {
            uniform.set(x, y);
        }
    }
}
