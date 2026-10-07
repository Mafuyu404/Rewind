package cc.sighs.rewind.client;

import java.util.OptionalInt;
import cc.sighs.rewind.Rewind;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * 存档点 / 回溯的过渡后处理。每帧结束时把整幅画面（或它的冻结副本）重新采样一遍画回主渲染目标：
 * 存档是饱和度提高，读档是高斯模糊，死亡回溯是模糊 + 视野红边，强度由 {@link RewindTransition} 的包络给出。
 *
 * <p>为什么要有「冻结副本」：回溯要走原版那条「关世界 → 换文件 → 重开世界」的路，
 * 中间有一段客户端根本没有 {@code ClientLevel} 的时间（原版用加载界面盖住它）。
 * 我们在过渡开始时对主渲染目标做一次拷贝存下来，世界消失后就继续重采样这张副本，
 * 于是整个重载过程都被「糊住的最后一帧」盖住——既不出现任何界面，也没有黑屏断层。
 *
 * <h2>26.1 的重写</h2>
 * 1.21.11 起 Blaze3d 把直接 OpenGL 调用整个收掉了：{@code ShaderInstance} / {@code Uniform} /
 * {@code RenderSystem#setShader} / {@code setShaderTexture} / {@code GlStateManager} 这一套都没了，
 * 后处理现在是「{@code RenderPipeline} + 一次全屏 pass」。所以这里照原版 {@code PostPass} 的做法改写：
 *
 * <ul>
 *   <li>管线用 {@code RenderPipelines.POST_PROCESSING_SNIPPET}（空顶点格式 + TRIANGLES）搭出来，
 *       顶点着色器直接复用原版的 {@code minecraft:core/screenquad}（靠 {@code gl_VertexID} 出全屏三角形），
 *       片元着色器是本模组的 {@code rewind:post/rewind_transition}。</li>
 *   <li>顶点不再自己塞 {@code BLIT_SCREEN} 四边形，改成 {@code RenderPass#draw(0, 3)} 画那个三角形。</li>
 *   <li>原来的 {@code EffectStrength} 等散装 uniform 变成一个 std140 uniform buffer
 *       （{@code RewindConfig} 块），每帧重新填一次；用 {@link MappableRingBuffer} 轮转三份，
 *       免得写进正在被上一帧读的那一份。</li>
 *   <li>「读副本、写主目标」仍然成立：副本是 {@link TextureTarget}，用
 *       {@code CommandEncoder#copyTextureToTexture} 拷主目标，再作为 {@code InSampler} 绑给那次 pass。</li>
 *   <li>采样器（线性过滤）现在是绑贴图时给的那个 {@code GpuSampler}，不再是贴图自己的属性。</li>
 * </ul>
 *
 * <p>管线是第一次用到时才搭的：着色器编译失败在原版那边只会打一条日志、拿到一个无效管线，
 * 我们自己抓异常并把过渡整个关掉（{@link #isEffectAvailable()} 会跟着返回 false，
 * 于是 {@link RewindScreens} 不再拦原版加载屏，避免留下一段黑屏）。
 */
public final class RewindTransitionRenderer {
    /** 单帧最多按多少秒推进包络，避免卡顿后效果跳变。 */
    private static final float MAX_FRAME_SECONDS = 0.1F;
    /** 着色器里的效果编号，必须和 rewind_transition.fsh 保持一致。 */
    private static final int MODE_BLUR = 0;
    private static final int MODE_SATURATION = 1;
    private static final int MODE_DEATH = 2;
    /** 顶点着色器：原版的全屏三角形。 */
    private static final Identifier VERTEX_SHADER = Identifier.withDefaultNamespace("core/screenquad");
    /** 片元着色器：{@code assets/rewind/shaders/post/rewind_transition.fsh}。 */
    private static final Identifier FRAGMENT_SHADER = Identifier.fromNamespaceAndPath("rewind", "post/rewind_transition");
    /** uniform 块名，必须和 fsh 里的 {@code layout(std140) uniform RewindConfig} 一致。 */
    private static final String UNIFORM_GROUP = "RewindConfig";
    /** 五个 float + 一个 vec2，见 fsh 里 uniform 块的字段顺序。 */
    private static final int UNIFORM_SIZE = new Std140SizeCalculator()
            .putFloat().putFloat().putFloat().putFloat().putFloat().putVec2().get();

    private static RenderPipeline pipeline;
    private static boolean pipelineFailed;
    private static MappableRingBuffer uniformRing;
    private static boolean uniformFailed;
    private static TextureTarget capturedFrame;
    private static long lastFrameNanos;
    private static boolean failedThisFrame;

    private RewindTransitionRenderer() {
    }

    public static void onRenderFramePost(Minecraft minecraft) {
        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos == 0L ? 0.0F : (now - lastFrameNanos) / 1.0E9F;
        lastFrameNanos = now;
        RewindTransition.tick(Math.min(deltaSeconds, MAX_FRAME_SECONDS));

        boolean active = RewindTransition.isActive();
        if (active) {
            // 各种过渡都是后处理：存档提高饱和度，读档 / 死亡回溯做高斯模糊（世界中间会消失，靠「模糊 + 最后一帧
            // 遮罩」把那段盖过去），死亡回溯再叠一层视野红边
            renderTransition(minecraft);
        }
    }

    private static void renderTransition(Minecraft minecraft) {
        // 过渡期间只允许我们自己那个什么都不画的逻辑屏；出现别的界面就说明情况不对，别去覆盖它
        if (minecraft.screen != null && !(minecraft.screen instanceof RewindBlankScreen)) {
            return;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main == null) {
            return;
        }
        int width = main.width;
        int height = main.height;
        if (width <= 0 || height <= 0) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        failedThisFrame = false;
        try {
            // 每帧把当前帧拷进自己的贴图：
            //  1) 后处理必须读「副本」写「主目标」，否则是在读写同一张贴图；
            //  2) 世界消失之后，这张副本就是唯一还能显示的东西（主目标此时只剩上一帧的残留）。
            if (minecraft.level != null) {
                copyFrame(main);
            }
            if (capturedFrame == null) {
                return;
            }
            RenderPipeline currentPipeline = pipeline();
            if (currentPipeline == null) {
                return;
            }
            GpuBuffer uniforms = uploadUniforms(width, height);
            if (uniforms == null) {
                return;
            }

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            // 这一帧还没被 blit 到屏幕（原版在 RenderFrameEvent.Post 之后才做），
            // 所以效果要画进主渲染目标，原版随后会把它显示出来。
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Rewind transition",
                    main.getColorTextureView(),
                    OptionalInt.empty())) {
                pass.setPipeline(currentPipeline);
                pass.bindTexture("InSampler", capturedFrame.getColorTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform(UNIFORM_GROUP, uniforms);
                pass.draw(0, 3);
            }
            uniformRing.rotate();
        } catch (Throwable t) {
            if (!failedThisFrame) {
                failedThisFrame = true;
                Rewind.LOGGER.error("Rewind: transition post-processing failed, disabling it", t);
            }
            pipelineFailed = true;
        }
    }

    /** 把主渲染目标的当前画面复制到我们自己的贴图里。 */
    private static void copyFrame(RenderTarget main) {
        if (capturedFrame == null || capturedFrame.width != main.width || capturedFrame.height != main.height) {
            if (capturedFrame != null) {
                capturedFrame.destroyBuffers();
            }
            capturedFrame = new TextureTarget("Rewind transition frame", main.width, main.height, false);
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                main.getColorTexture(), capturedFrame.getColorTexture(), 0,
                0, 0, 0, 0, main.width, main.height);
    }

    /** 当前过渡对应的着色器效果编号。 */
    private static int effectMode() {
        switch (RewindTransition.effect()) {
            case SATURATION:
                return MODE_SATURATION;
            case DEATH:
                return MODE_DEATH;
            default:
                return MODE_BLUR;
        }
    }

    /** 每帧把包络强度、效果编号、配置里的浓度/半径以及像素尺寸写进 uniform buffer。 */
    private static GpuBuffer uploadUniforms(int width, int height) {
        if (uniformRing == null) {
            if (uniformFailed) {
                return null;
            }
            try {
                uniformRing = new MappableRingBuffer(() -> "Rewind transition uniforms",
                        GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM, UNIFORM_SIZE);
            } catch (Throwable t) {
                uniformFailed = true;
                Rewind.LOGGER.error("Rewind: cannot allocate the transition uniform buffer, transitions are disabled", t);
                return null;
            }
        }
        GpuBuffer buffer = uniformRing.currentBuffer();
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(buffer, false, true)) {
            Std140Builder builder = Std140Builder.intoBuffer(view.data());
            builder.putFloat(RewindTransition.strength())
                    .putFloat((float) effectMode())
                    .putFloat(RewindClientConfig.saturationBoost())
                    .putFloat(RewindClientConfig.blurRadius())
                    .putFloat(RewindTransition.deathReach())
                    .putVec2(1.0F / width, 1.0F / height);
        }
        return buffer;
    }

    /**
     * 后处理是否可用。着色器加载/编译失败时过渡没有画面可依靠，这时候要让原版加载屏正常工作，
     * 否则玩家会看到一段黑屏空档。
     */
    public static boolean isEffectAvailable() {
        if (pipelineFailed) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        try {
            // 片元着色器在资源包里有货才算可用（编译失败原版只会打日志、给个无效管线，问不出来）
            return pipeline() != null && minecraft.getShaderManager().getShader(FRAGMENT_SHADER, ShaderType.FRAGMENT) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 延迟搭后处理管线；搭不出来只记一次日志，效果静默不生效，不影响正常游玩。 */
    private static RenderPipeline pipeline() {
        if (pipeline == null && !pipelineFailed) {
            try {
                pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("rewind", "pipeline/rewind_transition"))
                        .withVertexShader(VERTEX_SHADER)
                        .withFragmentShader(FRAGMENT_SHADER)
                        .withSampler("InSampler")
                        .withUniform(UNIFORM_GROUP, UniformType.UNIFORM_BUFFER)
                        .build();
            } catch (Throwable e) {
                pipelineFailed = true;
                Rewind.LOGGER.error("Rewind: cannot build the transition pipeline, transitions are disabled", e);
            }
        }
        return pipeline;
    }
}
