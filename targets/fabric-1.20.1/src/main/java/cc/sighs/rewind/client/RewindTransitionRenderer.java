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
 * <p>实现照抄原版 {@code RenderTarget#_blitToScreen} 的约定（同样的 NDC 顶点、同样的
 * {@code BLIT_SCREEN} 顶点格式与 {@code setSampler(name, glTextureId)}），保证朝向与缩放一致。
 *
 * <p><b>与 NeoForge 1.21.1 的差异</b>：那边挂在 {@code RenderFrameEvent.Post}（NeoForge 事件），
 * Fabric 没有对应事件，改由 {@code cc.sighs.mixin.ClientFrameMixins} 注入
 * {@code Minecraft.runTick(boolean)} 里 {@code mainRenderTarget.unbindWrite()} 之前的那一刻
 * （1.20.1 的原版在那个位置之后才 {@code blitToScreen}），语义完全一致。
 * 顶点格式与顶点缓冲的写法也按 1.20.1 的原版 API 各写一份（见 {@link #VERTEX_FORMAT} 与
 * {@link #renderTransition}）。自测那句「满强度存一张截图」的 {@code maybeScreenshot} 与主版本相同。
 */
public final class RewindTransitionRenderer {
    /** 单帧最多按多少秒推进包络，避免卡顿后效果跳变。 */
    private static final float MAX_FRAME_SECONDS = 0.1F;
    /** 着色器里的效果编号，必须和 rewind_transition.fsh 保持一致。 */
    private static final int MODE_SATURATION = 1;
    /** 着色器资源名；1.20.1 的 {@code ShaderInstance} 直接吃这个字符串。 */
    private static final String SHADER_LOCATION = "rewind:rewind_transition";
    /**
     * 顶点格式：只有 Position。
     *
     * <p>1.20.1 的 {@code shaders/core/*.json} **要显式列一份 {@code attributes}**（原版每个核心着色器都写了），
     * 着色器会照这个列表名字对号入座地 {@code glBindAttribLocation}；顶点缓冲则按 {@code VertexFormat}
     * 里的顺序当作 attribute index。主版本 1.21.1 的 {@code BLIT_SCREEN} 只有 Position，而 1.20.1 的
     * {@code BLIT_SCREEN} 是 Position + UV + Color 三件套，和这里只声明 Position 的 vsh 对不上，
     * 所以两边统一用 {@code POSITION}（见 target 资源里的 rewind_transition.json）。
     */
    private static final VertexFormat VERTEX_FORMAT = DefaultVertexFormat.POSITION;

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

        // 这一帧还没被 blit 到屏幕（原版在 runTick 的 unbindWrite / blitToScreen 那一步才做），
        // 所以效果要画进主渲染目标，原版随后会把它显示出来。
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
        // 效果的浓度/半径都来自 config/rewind-client.properties
        setFloat(instance, "SaturationBoost", RewindClientConfig.saturationBoost());
        setFloat(instance, "BlurRadius", RewindClientConfig.blurRadius());
        setVec2(instance, "TexelSize", 1.0F / width, 1.0F / height);

        // 1.20.1 的顶点缓冲是「拿共享 tesselator 的 builder → begin → 逐个 vertex().endVertex() → end()」，
        // 没有 1.21.1 的 Tesselator.begin(...) / BufferBuilder.buildOrThrow()。
        BufferBuilder builder = RenderSystem.renderThreadTesselator().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, VERTEX_FORMAT);
        builder.vertex(0.0D, 0.0D, 0.0D).endVertex();
        builder.vertex(1.0D, 0.0D, 0.0D).endVertex();
        builder.vertex(1.0D, 1.0D, 0.0D).endVertex();
        builder.vertex(0.0D, 1.0D, 0.0D).endVertex();
        BufferUploader.drawWithShader(builder.end());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        // 别把自定义着色器留给下一步的 blit
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        maybeScreenshot(minecraft);
    }

    /** 自测时在满强度存一张截图：过渡是视觉效果，日志看不出对错，留张图给人眼确认。 */
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
                shader = new ShaderInstance(minecraft.getResourceManager(), SHADER_LOCATION, VERTEX_FORMAT);
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
