package me.kalmemarq;

import me.kalmemarq.block.Blocks;
import me.kalmemarq.entity.PlayerEntity;
import me.kalmemarq.entity.ZombieEntity;
import me.kalmemarq.entity.model.ZombieModel;
import me.kalmemarq.render.*;
import me.kalmemarq.render.NativeImage.Mirroring;
import me.kalmemarq.render.NativeImage.PixelFormat;
import me.kalmemarq.render.vertex.BufferBuilder;
import me.kalmemarq.render.vertex.VertexBuffer;
import me.kalmemarq.render.vertex.VertexLayout;
import me.kalmemarq.util.BlockHitResult;
import me.kalmemarq.util.IOUtils;
import me.kalmemarq.util.Keybinding;
import me.kalmemarq.util.TimeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.Version;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Game implements Runnable, Window.EventHandler {
    private static final Logger LOGGER = LogManager.getLogger("Main");
    private static final String VERSION = "rd20090515";
    private static final float MOUSE_SENSITIVITY = 0.08f;
    private static final DateTimeFormatter SCREENSHOT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    private static int entityRenderCount;

    private static Game instance;
    private Window window;
    private Texture terrainTexture;
    private Texture charTexture;
    private Shader selectionShader;
    private Shader terrainShader;
    private Shader terrainShadowShader;
    private Shader entityShader;
    private final double[] mouse = {0, 0, 0, 0};
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private World world;
    private WorldRenderer worldRenderer;
    private final Frustum frustum = new Frustum();
    private PlayerEntity player;
    private BlockHitResult blockHitResult;
    private VertexBuffer blockSelectionVertexBuffer;
    private Framebuffer framebuffer;
    private final List<ZombieEntity> zombies = new ArrayList<>();
    private final ZombieModel zombieModel = new ZombieModel();
    private boolean renderEntityHitboxes = false;
    private int selectedBlockId = 1;
    private boolean rendeInfoOverlay;
    private int fps;
    private int tps;

    public Game() {
        instance = this;
    }

    public static Game getInstance() {
        return instance;
    }

    public Window getWindow() {
        return this.window;
    }

    @Override
    public void run() {
        this.window = new Window(1024, 768, VERSION);
        this.window.setIcon();
        this.window.addEventHandler(this);

        LOGGER.info("LWJGL {}", Version.getVersion());
        LOGGER.info("GLFW {}", GLFW.glfwGetVersionString());
        LOGGER.info("OpenGL {}", GL11.glGetString(GL11.GL_VERSION));
        LOGGER.info("Renderer {}", GL11.glGetString(GL11.GL_RENDERER));
        LOGGER.info("Java {}", System.getProperty("java.version"));
        Callback debugMessageCallback = GLUtil.setupDebugMessageCallback(System.err);
        GL45.glDebugMessageControl(GL45.GL_DEBUG_SOURCE_API, GL45.GL_DEBUG_TYPE_OTHER, GL45.GL_DONT_CARE, 0x20071, false);

        this.framebuffer = new Framebuffer(this.window.getWidth(), this.window.getHeight());

        this.terrainTexture = new Texture();
        this.terrainTexture.load(IOUtils.getResourcesPath().resolve("textures/terrain.png"));
        this.charTexture = new Texture();
        this.charTexture.load(IOUtils.getResourcesPath().resolve("textures/char.png"));

        this.selectionShader = new Shader("selection");
        this.terrainShader = new Shader("terrain");
        this.terrainShadowShader = new Shader("terrain_fog");
        this.entityShader = new Shader("entity");

        this.blockSelectionVertexBuffer = this.createBlockSelectionVertexBuffer();

        this.world = new World(256, 256, 64);
        this.worldRenderer = new WorldRenderer(this.world);
        this.world.setStateListener(this.worldRenderer);

        this.player = new PlayerEntity(this.world);
        for (int i = 0; i < 10; ++i) {
            ZombieEntity zombie = new ZombieEntity(this.world);
            zombie.setPosition(128f, zombie.position.y, 128f);
            this.zombies.add(zombie);
        }

        this.window.grabMouse();

        long lastTime = TimeUtils.getCurrentMillis();
        int frameCounter = 0;

        try {
            GL11.glClearColor(0.5f, 0.8f, 1f, 1f);

            int tickCounter = 0;
            long prevTimeMillis = TimeUtils.getCurrentMillis();
            int ticksPerSecond = 20;
            float tickDelta = 0;

            while (!this.window.shouldClose()) {
                long now = TimeUtils.getCurrentMillis();
                float lastFrameDuration = (float)(now - prevTimeMillis) / (1000f / ticksPerSecond);
                prevTimeMillis = now;
                tickDelta += lastFrameDuration;
                int i = (int) tickDelta;
                tickDelta -= (float) i;

                for (; i > 0; --i) {
                    ++tickCounter;
                    this.update();
                }

                this.render(tickDelta);

                if (this.rendeInfoOverlay) {
                    ImGuiLayer imGuiLayer = this.window.getImGuiLayer();
                    imGuiLayer.startFrame();
                    ImGui.setNextWindowPos(6, 6);
                    ImGui.setNextWindowBgAlpha(0.35f);
                    if (ImGui.begin("Info", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoFocusOnAppearing | ImGuiWindowFlags.NoNav)) {
                        ImGui.text(this.fps + " FPS " + this.tps + " TPS");
                        ImGui.text("E: " + entityRenderCount + "/" + this.zombies.size() + "C: " + WorldRenderer.chunksRendererPerFrame + "/" + this.worldRenderer.getChunkCount() + " x=" + String.format("%.3f", this.player.position.x) + ",y=" + String.format("%.4f", this.player.position.y) + ",z=" + String.format("%.3f", this.player.position.z));
                    }
                    ImGui.end();
                    imGuiLayer.endFrame();
                }

                this.window.update();
                ++frameCounter;

                while (TimeUtils.getCurrentMillis() - lastTime > 1000L) {
                    lastTime += 1000L;
                    this.fps = frameCounter;
                    this.tps = tickCounter;
                    frameCounter = 0;
                    tickCounter = 0;
                }

                entityRenderCount = 0;
                WorldRenderer.chunksRendererPerFrame = 0;
            }
        } catch (Exception e) {
            LOGGER.throwing(e);
        } finally {
            this.world.save();

            LOGGER.info("Closing");
            this.selectionShader.close();
            this.terrainShader.close();
            this.terrainShadowShader.close();
            this.entityShader.close();
            this.worldRenderer.close();
            this.terrainTexture.close();
            this.charTexture.close();
            this.blockSelectionVertexBuffer.close();
            this.framebuffer.close();
            this.window.getImGuiLayer().close();
            Tessellator.cleanup();

            GL30.glBindVertexArray(0);
            GL30.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
            GL30.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, 0);
            if (debugMessageCallback != null) debugMessageCallback.free();
            this.window.close();
        }
    }

    private void update() {
        this.blockHitResult = this.player.raytrace(8);

        this.world.tick();
        this.player.tick();

        Iterator<ZombieEntity> iter = this.zombies.iterator();
        while (iter.hasNext()) {
            ZombieEntity zombie = iter.next();
            zombie.tick();
            if (zombie.position.y < -100) {
                iter.remove();
            }
        }
    }

    private void render(float tickDelta) {
        this.framebuffer.resize(this.window.getWidth(), this.window.getHeight());

        this.framebuffer.bind();
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            System.out.println(error);
        }

        GL11.glViewport(0, 0, this.window.getWidth(), this.window.getHeight());
        this.projection.setPerspective((float) Math.toRadians(70.0f), this.window.getWidth() / (float) this.window.getHeight(), 0.01f, 1000.0f);

        float cameraPosX = org.joml.Math.lerp(this.player.prevPosition.x, this.player.position.x, tickDelta);
        float cameraPosY = org.joml.Math.lerp(this.player.prevPosition.y, this.player.position.y, tickDelta);
        float cameraPosZ = org.joml.Math.lerp(this.player.prevPosition.z, this.player.position.z, tickDelta);

        this.modelView.identity();
        this.modelView.rotate((float) Math.toRadians(this.player.pitch), 1, 0, 0);
        this.modelView.rotate((float) Math.toRadians(this.player.yaw), 0, 1, 0);
        this.modelView.translate(-cameraPosX, -(cameraPosY + this.player.eyeHeight), -cameraPosZ);

        this.frustum.set(this.projection, this.modelView);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);

        this.terrainTexture.bind(0);

        this.terrainShader.bind();
        this.terrainShader.setUniform("uProjection", this.projection);
        this.terrainShader.setUniform("uModelView", this.modelView);
        this.terrainShader.setUniform("uColor", 1f, 1f, 1f, 1f);
        this.terrainShader.setUniform("uSampler0", 0);

        this.worldRenderer.render(this.terrainShader, this.frustum, 0);

        this.terrainShadowShader.bind();
        this.terrainShadowShader.setUniform("uProjection", this.projection);
        this.terrainShadowShader.setUniform("uModelView", this.modelView);
        this.terrainShadowShader.setUniform("uColor", 1f, 1f, 1f, 1f);
        this.terrainShadowShader.setUniform("uFogDensity", 0.04f);
        this.terrainShadowShader.setUniform("uFogColor", 0.0f, 0.0f, 0.0f, 1f);
        this.terrainShadowShader.setUniform("uSampler0", 0);

        this.worldRenderer.render(this.terrainShadowShader, this.frustum,  1);

        if (this.blockHitResult != null) {
            this.modelView.identity();
            this.modelView.rotate((float) Math.toRadians(this.player.pitch), 1, 0, 0);
            this.modelView.rotate((float) Math.toRadians(this.player.yaw), 0, 1, 0);
            this.modelView.translate(-cameraPosX, -(cameraPosY + this.player.eyeHeight), -cameraPosZ);

            this.selectionShader.bind();
            this.selectionShader.setUniform("uProjection", this.projection);
            this.selectionShader.setUniform("uColor", 1f, 1f, 1f, (float)Math.sin((double)TimeUtils.getCurrentMillis() / 100.0d) * 0.2f + 0.4f);
            this.modelView.translate(this.blockHitResult.x(), this.blockHitResult.y(), this.blockHitResult.z());
            this.selectionShader.setUniform("uModelView", this.modelView);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            this.blockSelectionVertexBuffer.bind();
            this.blockSelectionVertexBuffer.draw(6, (6 * this.blockHitResult.face().index) * 4);
            GL11.glDisable(GL11.GL_BLEND);
        }

        this.modelView.identity();
        this.modelView.rotate((float) Math.toRadians(this.player.pitch), 1, 0, 0);
        this.modelView.rotate((float) Math.toRadians(this.player.yaw), 0, 1, 0);
        this.modelView.translate(-cameraPosX, -(cameraPosY + this.player.eyeHeight), -cameraPosZ);

        this.entityShader.bind();
        this.entityShader.setUniform("uProjection", this.projection);
        this.entityShader.setUniform("uModelView", this.modelView);
        this.entityShader.setUniform("uColor", 1f, 1f, 1f, 1f);
        this.charTexture.bind(0);
        this.entityShader.setUniform("uSampler0", 0);

        Tessellator tessellator = Tessellator.getInstance();
        tessellator.begin(DrawMode.QUADS, VertexLayout.POS_UV_COLOR);
        BufferBuilder builder = tessellator.getBufferBuilder();

        for (ZombieEntity zombie : this.zombies) {
            if (!this.frustum.isVisible(zombie.box)) continue;
            this.zombieModel.render(builder, zombie, zombie.isLit() ? 1f : 0.6f, tickDelta);
            entityRenderCount++;
        }

        tessellator.draw();

        if (this.renderEntityHitboxes) {
            this.selectionShader.bind();
            this.selectionShader.setUniform("uProjection", this.projection);
            this.selectionShader.setUniform("uModelView", this.modelView);
            this.selectionShader.setUniform("uColor", 1f, 1f, 1f, 1f);

            tessellator.begin(DrawMode.LINES, VertexLayout.POS);
            for (ZombieEntity zombie : this.zombies) {
                if (!this.frustum.isVisible(zombie.box)) continue;
                float x = org.joml.Math.lerp(zombie.prevPosition.x, zombie.position.x, tickDelta);
                float y = org.joml.Math.lerp(zombie.prevPosition.y, zombie.position.y, tickDelta);
                float z = org.joml.Math.lerp(zombie.prevPosition.z, zombie.position.z, tickDelta);
                this.renderBox(builder, x - zombie.size.x / 2, y, z - zombie.size.x / 2, x + zombie.size.x / 2, y + zombie.eyeHeight, z + zombie.size.x / 2);
            }
            tessellator.draw();
        }

        this.renderHud();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        GL11.glViewport(0, 0, this.window.getWidth(), this.window.getHeight());
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        this.framebuffer.draw();
    }

    private void renderHud() {
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

        int width = this.window.getWidth();
        int height = this.window.getHeight();
        this.projection.setOrtho(0, width, height, 0, -200, 300);
        this.modelView.identity();

        MatrixStack matrices = MatrixStack.INSTANCE;
        matrices.push();
        matrices.translate(width - 48, 48, 0);
        matrices.scale(48, 48, 48);
        matrices.rotateXDegrees(30);
        matrices.rotateYDegrees(45);
        matrices.translate(-1.5f, 0.5f, 0);

        this.entityShader.bind();
        this.entityShader.setUniform("uProjection", this.projection);
        this.entityShader.setUniform("uModelView", this.modelView);
        this.entityShader.setUniform("uColor", 1f, 1f, 1f, 1f);
        this.terrainTexture.bind(0);
        this.entityShader.setUniform("uSampler0", 0);

        Tessellator tessellator = Tessellator.getInstance();
        tessellator.begin(DrawMode.QUADS, VertexLayout.POS_UV_COLOR);
        BufferBuilder builder = tessellator.getBufferBuilder();

        Blocks.blocks[this.selectedBlockId].render(this.world, matrices, builder, 0, -2, 0, 0);

        tessellator.draw();
        matrices.pop();

        GL30.glDisable(GL30.GL_CULL_FACE);
        GL30.glDisable(GL30.GL_DEPTH_TEST);

        this.selectionShader.bind();
        this.selectionShader.setUniform("uProjection", this.projection);
        this.selectionShader.setUniform("uModelView", this.modelView);
        this.selectionShader.setUniform("uColor", 1f, 1f, 1f, 1f);

        tessellator.begin(DrawMode.QUADS, VertexLayout.POS);
        builder.vertex((float) (width / 2), (float) (height / 2 - 8), 0);
        builder.vertex((float) (width / 2), (float) (height / 2 + 9), 0);
        builder.vertex((float) (width / 2 + 1), (float) (height / 2 + 9), 0);
        builder.vertex((float) (width / 2 + 1), (float) (height / 2 - 8), 0);

        builder.vertex((float) (width / 2 - 8), (float) (height / 2 - 1), 0);
        builder.vertex((float) (width / 2 - 8), (float) (height / 2 + 1), 0);
        builder.vertex((float) (width / 2 + 9), (float) (height / 2 + 1), 0);
        builder.vertex((float) (width / 2 + 9), (float) (height / 2 - 1), 0);
        tessellator.draw();
    }

    private void renderBox(BufferBuilder builder, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        builder.vertex(minX, minY, minZ); builder.vertex(minX, maxY, minZ);
        builder.vertex(maxX, minY, minZ); builder.vertex(maxX, maxY, minZ);
        builder.vertex(minX, minY, maxZ); builder.vertex(minX, maxY, maxZ);
        builder.vertex(maxX, minY, maxZ); builder.vertex(maxX, maxY, maxZ);

        builder.vertex(minX, minY, minZ); builder.vertex(maxX, minY, minZ);
        builder.vertex(minX, minY, minZ); builder.vertex(minX, minY, maxZ);
        builder.vertex(minX, minY, maxZ); builder.vertex(maxX, minY, maxZ);
        builder.vertex(maxX, minY, minZ); builder.vertex(maxX, minY, maxZ);

        builder.vertex(minX, maxY, minZ); builder.vertex(maxX, maxY, minZ);
        builder.vertex(minX, maxY, minZ); builder.vertex(minX, maxY, maxZ);
        builder.vertex(minX, maxY, maxZ); builder.vertex(maxX, maxY, maxZ);
        builder.vertex(maxX, maxY, minZ); builder.vertex(maxX, maxY, maxZ);
    }

    @Override
    public void onCursorPos(double x, double y) {
        this.mouse[2] = x - this.mouse[0];
        this.mouse[3] = y - this.mouse[1];
        this.mouse[0] = x;
        this.mouse[1] = y;

        float dx = (float) this.mouse[2];
        float dy = (float) this.mouse[3];
        this.player.turn(dx * MOUSE_SENSITIVITY, dy * MOUSE_SENSITIVITY);

        this.mouse[2] = 0;
        this.mouse[3] = 0;
    }

    @Override
    public void onMouseButton(int button, int action) {
        if (action != GLFW.GLFW_RELEASE && this.blockHitResult != null) {
            if (button == 1) {
                this.world.setBlockId(this.blockHitResult.x(), this.blockHitResult.y(), this.blockHitResult.z(), 0);
            } else if (button == 0) {
                int x = this.blockHitResult.x() + this.blockHitResult.face().normalX;
                int y = this.blockHitResult.y() + this.blockHitResult.face().normalY;
                int z = this.blockHitResult.z() + this.blockHitResult.face().normalZ;
                if (!this.player.box.intersects(x, y, z, x + 1, y + 1, z + 1)) {
                    this.world.setBlockId(x, y, z, this.selectedBlockId);
                }
            }
        }
    }

    @Override
    public void onKey(int key, int action) {
        if (action == GLFW.GLFW_PRESS) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                GLFW.glfwSetWindowShouldClose(this.window.getHandle(), true);
            } else if (Keybinding.TOGGLE_VSYNC.test(key)) {
                this.window.toggleVsync();
            } else if (Keybinding.TOGGLE_FULLSCREEN.test(key)) {
                this.window.toggleFullscreen();
            } else if (Keybinding.SAVE_WORLD_TO_DISK.test(key)) {
                this.world.save();
            } else if (Keybinding.GO_TO_RANDOM_POS.test(key)) {
                this.player.goToRandomPosition();
            } else if (Keybinding.FLY.test(key)) {
                this.player.canFly = !this.player.canFly;
            } else if (Keybinding.NO_CLIP.test(key)) {
                this.player.noClip = !this.player.noClip;
            } else if (key == GLFW.GLFW_KEY_F8) {
                this.renderEntityHitboxes = !this.renderEntityHitboxes;
            } else if (key == GLFW.GLFW_KEY_1) {
                this.selectedBlockId = 1;
            } else if (key == GLFW.GLFW_KEY_2) {
                this.selectedBlockId = 3;
            } else if (key == GLFW.GLFW_KEY_3) {
                this.selectedBlockId = 4;
            } else if (key == GLFW.GLFW_KEY_4) {
                this.selectedBlockId = 5;
            } else if (key == GLFW.GLFW_KEY_G) {
                ZombieEntity zombie = new ZombieEntity(this.world);
                zombie.setPosition(this.player.position.x, this.player.position.y, this.player.position.z);
                this.zombies.add(zombie);
            } else if (key == GLFW.GLFW_KEY_F3) {
                this.rendeInfoOverlay = !this.rendeInfoOverlay;
            } else if (key == GLFW.GLFW_KEY_F2) {
                NativeImage image = NativeImage.readFromTexture(this.framebuffer.getColorAttachmentTxr(), this.framebuffer.getWidth(), this.framebuffer.getHeight(), PixelFormat.RGB);
                image.flip(Mirroring.VERTICAL);
                Path screenshotsPath = Path.of("screenshots");
                if (IOUtils.ensureDirectory(screenshotsPath)) {
                    image.saveTo(screenshotsPath.resolve(SCREENSHOT_DATE_FORMATTER.format(LocalDateTime.now()) + ".png"));
                }
                image.close();
            }
        }
    }

    private VertexBuffer createBlockSelectionVertexBuffer() {
        VertexBuffer blockSelectionVertexBuffer = new VertexBuffer();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(VertexLayout.POS.stride * 4 * 6);
            BufferBuilder builder = new BufferBuilder(MemoryUtil.memAddress(buffer));

            float x0 = 0f;
            float y0 = 0f;
            float z0 = 0f;
            float x1 = 1f;
            float y1 = 1f;
            float z1 = 1f;
            float offset = 0.0009f;

            builder.begin();
            builder.vertex(x0, y0 - offset, z0);
            builder.vertex(x1, y0 - offset, z0);
            builder.vertex(x1, y0 - offset, z1);
            builder.vertex(x0, y0 - offset, z1);

            builder.vertex(x0, y1 + offset, z0);
            builder.vertex(x0, y1 + offset, z1);
            builder.vertex(x1, y1 + offset, z1);
            builder.vertex(x1, y1 + offset, z0);

            builder.vertex(x0, y0, z0 - offset);
            builder.vertex(x0, y1, z0 - offset);
            builder.vertex(x1, y1, z0 - offset);
            builder.vertex(x1, y0, z0 - offset);

            builder.vertex(x0, y0, z1 + offset);
            builder.vertex(x1, y0, z1 + offset);
            builder.vertex(x1, y1, z1 + offset);
            builder.vertex(x0, y1, z1 + offset);

            builder.vertex(x0 - offset, y0, z0);
            builder.vertex(x0 - offset, y0, z1);
            builder.vertex(x0 - offset, y1, z1);
            builder.vertex(x0 - offset, y1, z0);

            builder.vertex(x1 + offset, y0, z0);
            builder.vertex(x1 + offset, y1, z0);
            builder.vertex(x1 + offset, y1, z1);
            builder.vertex(x1 + offset, y0, z1);

            blockSelectionVertexBuffer.upload(DrawMode.QUADS, VertexLayout.POS, buffer, builder.end());
        }
        return blockSelectionVertexBuffer;
    }
}
