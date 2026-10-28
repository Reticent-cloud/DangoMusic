package me.sorihiko.dango.modern

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.platform.PlayerAction
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL30

/** 这个版本的 GlStateManager 在 platform 包里 */
internal typealias GlStateManager = com.mojang.blaze3d.platform.GlStateManager

/** 同步原版 GlStateManager 记的混合 / 颜色写入状态（各版本参数不一样） */
internal object GlCache {
    fun blend(on: Boolean) {
        if (on) GlStateManager._enableBlend() else GlStateManager._disableBlend()
    }

    fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) {
        GlStateManager._colorMask(r, g, b, a)
    }
}

/**
 * 1.19.2 专用的几处写法（同一件事在不同 Minecraft 版本里 API 不一样）。
 * 每个版本在 src/mc<版本>/ 里放一份，构建时按版本选用，对外的样子完全一样。
 *
 * 这个版本和 1.20.1 的差别集中在矩阵类型上：
 * Minecraft 从 1.19.3 才开始依赖 org.joml，1.19.2 的原版 API 收发的还是
 * com.mojang.math.Matrix4f（RenderSystem 的投影 / 视图矩阵、Font#drawInBatch 都是），
 * 而共用代码（Fixed / Projection / GlassRenderContext / 着色器）算的是 joml 的矩阵，
 * 所以在边界上做一次列主序的 float 拷贝互转。
 */
internal object McCompat {
    /**
     * 主菜单上的「正在播放」浮层（1.6.0 新增）。
     * 高版本由 GuiGraphics 直接交给 DangoClient 画；1.19.2 的主菜单渲染走
     * ScreenEvent.Render.Post + PoseStack，没有 GuiGraphics，这里保持空实现，
     * 1.19.2 的入口也不注册这段渲染 —— 其余功能不受影响。
     */
    fun mainMenuNowPlaying(graphics: Any) {}

    /**
     * 播放器在主菜单打开时的背景（原版全景 + 遮罩，1.6.0 新增）。
     * 1.19.2 要自己用 PanoramaRenderer 和 Tesselator 重画，暂未移植；
     * 返回 false 时 MusicScreen 会先铺一层纯色底，玻璃不会反复采样自身。
     */
    fun renderMenuBackdrop(parent: Screen?): Boolean = false

    /** 16 个 float 的临时缓冲（列主序）。com.mojang 那两个方法用的是绝对索引，拷完 position 要回 0 */
    private val matBuf = BufferUtils.createFloatBuffer(16)

    /** joml 侧的结果（复用，避免每帧分配）。Fixed 只读，不会改 */
    private val jomlProjection = Matrix4f()
    private val jomlModelView = Matrix4f()

    /** com.mojang 侧的结果（复用）。原版这两个 API 内部都会先拷贝一份，所以复用是安全的 */
    private val mojangProjection = com.mojang.math.Matrix4f()
    private val mojangText = com.mojang.math.Matrix4f()

    fun createPlayerKey(action: PlayerAction): KeyMapping =
        KeyMapping(action.translationKey, gameBindingKey(action.defaultKey).value, "key.categories.dangomusic")

    fun gameBindingKey(code: Int): InputConstants.Key = when {
        code == Keys.NONE -> InputConstants.UNKNOWN
        code < 0 -> InputConstants.Type.MOUSE.getOrCreate(code + 100)
        else -> InputConstants.Type.KEYSYM.getOrCreate(KeyMap.toGlfw(code))
    }

    fun commonBindingKey(key: InputConstants.Key): Int =
        if (key.type == InputConstants.Type.MOUSE) Keys.mouse(key.value) else KeyMap.fromGlfw(key.value)


    /** 把原版的视图矩阵临时换成单位矩阵（1.19.2 和 1.20.1 一样还是 PoseStack） */
    fun pushIdentityModelView() {
        val mv: PoseStack = RenderSystem.getModelViewStack()
        mv.pushPose()
        mv.setIdentity()
        RenderSystem.applyModelViewMatrix()
    }

    fun popModelView() {
        RenderSystem.getModelViewStack().popPose()
        RenderSystem.applyModelViewMatrix()
    }

    /** 当前的投影矩阵（[Fixed] 算 MVP 用）：这个版本要自己从 com.mojang.math 转成 joml */
    fun projection(): Matrix4f = toJoml(RenderSystem.getProjectionMatrix(), jomlProjection)

    /** 换成自己的正交投影（画屏幕上的东西用），原版的字也跟着用它 */
    fun pushScreenProjection(m: Matrix4f) {
        RenderSystem.backupProjectionMatrix()
        // 1.20.1 还要传 VertexSorting.ORTHOGRAPHIC_Z；1.19.2 的 setProjectionMatrix 只有一个参数，
        // VertexSorting 这个类 1.19.3 才出现
        RenderSystem.setProjectionMatrix(toMojang(m, mojangProjection))
    }

    fun popScreenProjection() {
        RenderSystem.restoreProjectionMatrix()
    }

    /** 世界画完、开始画我们的东西之前（这些版本当前帧缓冲就是主画面，不用做什么） */
    fun beginWorld() {}

    /** 绑定帧缓冲（这些版本原版不记帧缓冲，直接绑） */
    fun bindFramebuffer(target: Int, id: Int) {
        GL30.glBindFramebuffer(target, id)
    }

    /** 让原版画一批东西（这些版本原版直接画在当前绑定的帧缓冲上，不用做什么） */
    inline fun vanillaDraw(block: () -> Unit) = block()

    /** 每次画之前（这些版本不用做什么） */
    fun beforeDraw() {}

    fun cameraXRot(): Float = camera().xRot
    fun cameraYRot(): Float = camera().yRot
    fun cameraPosition(): Vec3 = camera().position
    private fun camera() = Minecraft.getInstance().gameRenderer.mainCamera

    fun windowHandle(): Long = Minecraft.getInstance().window.window

    /** 鼠标键 [button]（GLFW 编号：0 左、1 右、2 中）是否按着 */
    fun isMouseDown(button: Int): Boolean =
        org.lwjgl.glfw.GLFW.glfwGetMouseButton(windowHandle(), button) == org.lwjgl.glfw.GLFW.GLFW_PRESS

    /** 键盘键（GLFW 键码）是否按着 */
    fun isGlfwKeyDown(key: Int): Boolean =
        org.lwjgl.glfw.GLFW.glfwGetKey(windowHandle(), key) == org.lwjgl.glfw.GLFW.GLFW_PRESS

    /** 原版视图矩阵的当前值（[Fixed] 算 MVP 用）：同样要从 com.mojang.math 转成 joml */
    fun modelView(): Matrix4fc = toJoml(RenderSystem.getModelViewMatrix(), jomlModelView)

    /**
     * 用原版字体画一段像素字（[pose] 是模型矩阵），画在当前绑定的帧缓冲上。
     *
     * 1.20.1：drawInBatch(..., org.joml.Matrix4f, ..., Font.DisplayMode.SEE_THROUGH, ...)
     * 1.19.2：drawInBatch(..., com.mojang.math.Matrix4f, ..., transparent: Boolean, ...)
     */
    fun drawText(text: String, x: Float, y: Float, argb: Int, pose: Matrix4f) {
        val mc = Minecraft.getInstance()
        val buffers = mc.renderBuffers().bufferSource()
        mc.font.drawInBatch(
            text, x, y, argb, false, toMojang(pose, mojangText), buffers,
            true, 0, LightTexture.FULL_BRIGHT,
        )
        vanillaDraw { buffers.endBatch() }
    }

    fun currentScreen(): Screen? = Minecraft.getInstance().screen

    fun setScreen(screen: Screen?) {
        Minecraft.getInstance().setScreen(screen)
    }

    /** 能不能用 OpenGL 画（1.19.2 只有 OpenGL，永远能画） */
    val glAvailable: Boolean get() = true

    /** 原版开始画界面之前（只有 26.x 用到） */
    fun beforeGui() {}

    /** 不能用 OpenGL 时提示玩家（这些版本一定能用） */
    fun notifyNoGl(again: Boolean = false) {}

    /**
     * 帧率。
     *
     * Minecraft#getFps() 是 1.20.1 才有的，1.19.2 只有一个 private static int 字段（没有 getter），
     * 所以这里自己按 1 秒窗口统计：入口在每帧渲染回调里调 [onFrame]。
     */
    val fps: Int get() = fpsValue

    private var fpsValue = 0
    private var frames = 0
    private var windowStart = System.nanoTime()

    fun onFrame() {
        frames++
        val now = System.nanoTime()
        val elapsed = now - windowStart
        if (elapsed >= 1_000_000_000L) {
            fpsValue = (frames * (1_000_000_000.0 / elapsed)).toInt()
            frames = 0
            windowStart = now
        }
    }

    /** 新建一张贴图交给原版管理，[abgr] 是逐行的像素 */
    fun createTexture(name: String, w: Int, h: Int, abgr: IntArray): ModernTexture {
        val native = NativeImage(w, h, false)
        for (y in 0 until h) for (x in 0 until w) native.setPixelRGBA(x, y, abgr[y * w + x])
        val texture = DynamicTexture(native)
        val manager = Minecraft.getInstance().textureManager
        val location = manager.register(name, texture)
        return ModernTexture({ texture.id }, { manager.release(location) })
    }

    // ---------------------------------------------------------------- 矩阵互转

    /**
     * com.mojang.math.Matrix4f -> joml。
     *
     * 两边都是列主序（com.mojang 的 load / store 用 col * 4 + row 的绝对索引），
     * 直接拷 16 个 float 就行，不需要转置。
     */
    private fun toJoml(src: com.mojang.math.Matrix4f, dst: Matrix4f): Matrix4f {
        matBuf.clear()
        src.store(matBuf)
        matBuf.position(0)
        return dst.set(matBuf)
    }

    /** joml -> com.mojang.math.Matrix4f */
    private fun toMojang(src: Matrix4f, dst: com.mojang.math.Matrix4f): com.mojang.math.Matrix4f {
        matBuf.clear()
        src.get(matBuf)
        matBuf.position(0)
        dst.load(matBuf)
        return dst
    }
}

/** 界面基类：把原版界面的各种事件统一成简单的几个方法（1.20.1 的滚轮事件只有一个方向） */
abstract class CompatScreen(title: Component) : Screen(title) {
    abstract fun onRender()
    abstract fun onMouseClicked(button: Int)
    abstract fun onMouseDragged()
    abstract fun onMouseReleased()
    abstract fun onScroll(delta: Double)
    abstract fun onKeyPressed(glfwKey: Int)
    abstract fun onKeyReleased(glfwKey: Int)
    abstract fun onCharTyped(c: Char)

    // 1.19.2 还没有 GuiGraphics，render 收到的是 PoseStack；也没有它的 scissor 栈和 flush()。
    // 我们的界面全是自己画的（自建正交投影 + Fixed 着色器），所以这里不需要动 PoseStack
    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTick: Float) {
        onRender()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        onMouseClicked(button)
        return true
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        onMouseDragged()
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        onMouseReleased()
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        onKeyPressed(keyCode)
        return true
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        onKeyReleased(keyCode)
        return true
    }

    override fun charTyped(c: Char, modifiers: Int): Boolean {
        onCharTyped(c)
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        onScroll(delta)
        return true
    }
}
