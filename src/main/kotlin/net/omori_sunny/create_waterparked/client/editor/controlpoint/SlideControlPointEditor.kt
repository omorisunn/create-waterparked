package net.omori_sunny.create_waterparked.client.editor.controlpoint

import com.mojang.blaze3d.vertex.PoseStack
import com.simibubi.create.AllItems
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditSounds
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditorRenderTypes
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit
import org.joml.Matrix4f
import kotlin.math.abs

@OnlyIn(Dist.CLIENT)
abstract class SlideControlPointEditor(val id: String) {

    class CPTextures(
        val default: ResourceLocation,
        val hover: ResourceLocation,
        val drag: ResourceLocation
    )

    // plot space
    data class ControlPoint(
        val key: String,
        val pos: Vec3,
        val pickRadius: Double = 0.2,
        val up: Vec3? = null,
        val textures: CPTextures? = null
    )

    protected abstract fun active(): Boolean

    protected open fun activePos(): net.minecraft.core.BlockPos? = null

    protected abstract fun points(): List<ControlPoint>

    protected abstract fun textures(): CPTextures

    // plot space; null aborts the drag
    protected abstract fun rawValue(point: ControlPoint, eye: Vec3, view: Vec3): Double?

    protected abstract fun applyValue(point: ControlPoint, value: Double)

    protected abstract fun currentValue(point: ControlPoint): Double

    protected abstract fun commit(point: ControlPoint)

    protected abstract fun onClear()

    protected open fun onDragStart(point: ControlPoint) {}

    // snap step, also the drag-sound cell size
    protected open fun snapUnit(): Double = 0.05

    protected open fun snapEnabled(): Boolean = !com.simibubi.create.AllKeys.altDown()

    protected open fun snap(point: ControlPoint, raw: Double): Double =
        if (!snapEnabled()) raw else Math.round(raw / snapUnit()) * snapUnit()

    protected open fun readout(point: ControlPoint, value: Double): Component? = null

    protected open fun statusReadout(): Component? = null

    protected open fun handleHalfSize(): Float = 0.11f

    protected open fun orientedHandles(): Boolean = true

    protected open fun renderExtra(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f
    ) {}

    // identity unless the slide sits in a sub level
    protected open fun toLocal(p: Vec3): Vec3 = p
    protected open fun toLocalDir(v: Vec3): Vec3 = v
    protected open fun toRender(p: Vec3): Vec3 = p
    protected open fun toRenderDir(v: Vec3): Vec3 = v

    private var dragging = false
    private var dragKey: String? = null
    private var lastSoundValue = Double.NaN

    private var currentReadout: Component? = null

    private fun dragReadout(): Component? = currentReadout

    fun isDragging(): Boolean = dragging

    protected fun draggedKey(): String? = if (dragging) dragKey else null

    fun debugPointCount(): Int = try { points().size } catch (t: Throwable) { -1 }

    open fun debugResolveOk(): Boolean = try { points().isNotEmpty() } catch (t: Throwable) { false }

    private fun clear() {
        onClear()
        dragging = false
        dragKey = null
    }

    private fun pick(eye: Vec3, view: Vec3): ControlPoint? =
        points()
            .filter { raySphere(eye, view, it.pos, it.pickRadius) }
            .minByOrNull { it.pos.distanceToSqr(eye) }

    fun clientTick(mc: Minecraft) {
        val player = mc.player ?: return clear()
        val level = mc.level ?: return clear()
        if (!active()) {
            currentReadout = null
            return clear()
        }
        if (!holdsWrench(player)) return clear()
        if (ghostComboOwnsClick(player)) return clear()
        if (otherDragging()) return

        val eye = toLocal(player.eyePosition)
        val view = toLocalDir(player.getViewVector(1f)).normalize()
        val useDown = mc.options.keyUse.isDown
        if (suppressGrabUntilUseRelease && !useDown) suppressGrabUntilUseRelease = false

        if (dragging) {
            val point = points().firstOrNull { it.key == dragKey } ?: return clear()
            val raw = rawValue(point, eye, view) ?: return clear()
            val snapped = snap(point, raw)
            applyValue(point, snapped)
            currentReadout = readout(point, snapped) ?: statusReadout()
            dragSound(snapped)
            if (!useDown) {
                commit(point)
                clear()
                lastSoundValue = Double.NaN
                WaterslideEditSounds.playCommitSuccess()
            }
            return
        }

        currentReadout = null
        if (!useDown || suppressGrabUntilUseRelease) return
        val hit = pick(eye, view) ?: return
        dragging = true
        dragKey = hit.key
        lastSoundValue = Double.NaN
        onDragStart(hit)
    }

    private fun dragSound(value: Double) {
        val unit = snapUnit()
        if (lastSoundValue.isNaN() || abs(value - lastSoundValue) >= unit) {
            val inCell = if (lastSoundValue.isNaN()) 0f
            else ((abs(value - lastSoundValue) % unit).coerceIn(0.0, unit) / unit).toFloat()
            WaterslideEditSounds.playDragTick(inCell)
            lastSoundValue = value
        }
    }

    fun isHoveringOrDragging(mc: Minecraft): Boolean {
        if (dragging) return true
        val player = mc.player ?: return false
        if (!mc.options.keyUse.isDown) return false
        if (!active()) return false
        if (!holdsWrench(player)) return false
        val eye = toLocal(player.eyePosition)
        val view = toLocalDir(player.getViewVector(1f)).normalize()
        return pick(eye, view) != null
    }

    fun renderControlPoints(
        mc: Minecraft,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f
    ) {
        if (!active()) return
        poseStack.pushPose()
        renderExtra(poseStack, bufferSource, cameraPos, cameraRotation)

        val player = mc.player
        val eye = player?.let { toLocal(it.eyePosition) }
        val view = player?.let { toLocalDir(it.getViewVector(1f)).normalize() }
        val hover = if (eye != null && view != null && !dragging) pick(eye, view) else null

        val tex = textures()
        for (cp in points()) {
            val draggingThis = cp.key == dragKey
            val hoveringThis = !draggingThis && hover != null && hover.key == cp.key
            val pointTex = cp.textures ?: tex
            val t = when {
                draggingThis -> pointTex.drag
                hoveringThis -> pointTex.hover
                else -> pointTex.default
            }
            val world = toRender(cp.pos)
            val half = handleHalfSize()
            if (orientedHandles()) {
                val up = cp.up?.let { toRenderDir(it) } ?: Vec3(0.0, 1.0, 0.0)
                WaterslideEditorRenderTypes.billboardOrientedTexturedQuad(
                    poseStack,
                    bufferSource.getBuffer(WaterslideEditorRenderTypes.boundaryHandleBillboard(t)),
                    cameraPos, cameraRotation, world, up, half, half
                )
            } else {
                WaterslideEditorRenderTypes.billboardTexturedQuad(
                    poseStack,
                    bufferSource.getBuffer(WaterslideEditorRenderTypes.boundaryHandleBillboard(t)),
                    cameraPos, cameraRotation, world, half
                )
            }
        }
        poseStack.popPose()
    }

    companion object {
        private val editors = LinkedHashMap<String, SlideControlPointEditor>()

        private var suppressGrabUntilUseRelease = false

        @JvmStatic
        fun suppressNextGrab() {
            suppressGrabUntilUseRelease = true
        }

        @JvmStatic
        fun register(editor: SlideControlPointEditor) {
            editors[editor.id] = editor
        }

        @JvmStatic
        fun tickAll(mc: Minecraft) {
            for (e in editors.values.toList()) e.clientTick(mc)
        }

        @JvmStatic
        fun renderAll(
            mc: Minecraft,
            poseStack: PoseStack,
            bufferSource: MultiBufferSource,
            cameraPos: Vec3,
            cameraRotation: Matrix4f
        ) {
            for (e in editors.values.toList()) {
                e.renderControlPoints(mc, poseStack, bufferSource, cameraPos, cameraRotation)
            }
        }

        @JvmStatic
        fun anyHoveringOrDragging(mc: Minecraft): Boolean =
            editors.values.any { it.isHoveringOrDragging(mc) }

        @JvmStatic
        fun anyDragging(): Boolean = editors.values.any { it.isDragging() }

        @JvmStatic
        fun isEditingAt(pos: net.minecraft.core.BlockPos): Boolean =
            editors.values.any { it.activePos() == pos }

        @JvmStatic
        fun statusLine(): Component? {
            val editor = editors.values.firstOrNull { it.active() } ?: return null
            return editor.dragReadout() ?: editor.statusReadout()
        }

        @JvmStatic
        fun renderStatusHud(mc: Minecraft, gui: net.minecraft.client.gui.GuiGraphics) {
            val text = statusLine() ?: return
            if (mc.options.hideGui || mc.player == null || mc.screen != null) return
            val font = mc.font
            val width = font.width(text)
            val x = (gui.guiWidth() - width) / 2
            val y = dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager
                .editHudRowAboveHotbarY(mc, gui)
            gui.drawStringWithBackdrop(
                font, text, x, y, width,
                com.mojang.blaze3d.systems.RenderSystem.getShaderColor().let {
                    net.minecraft.util.FastColor.ARGB32.color(255, 0xFFFFFF)
                }
            )
        }

        @Volatile
        @JvmStatic
        var renderAllCalls: Long = 0L
            private set

        @JvmStatic
        fun registeredCount(): Int = editors.size
    }

    private fun otherDragging(): Boolean =
        WaterslideRadiusEdit.isDragging() ||
            WaterslideSectorEdit.isDraggingControlPoint() ||
            dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager.isDraggingHandle() ||
            editors.values.any { it !== this && it.isDragging() }

    private fun holdsWrench(player: Player): Boolean =
        AllItems.WRENCH.isIn(player.mainHandItem) || AllItems.WRENCH.isIn(player.offhandItem)

    private fun ghostComboOwnsClick(player: Player): Boolean =
        player.mainHandItem.`is`(AllItems.WRENCH.get()) &&
            player.offhandItem.item is net.minecraft.world.item.BlockItem

    private fun raySphere(ro: Vec3, rd: Vec3, center: Vec3, radius: Double): Boolean {
        val oc = ro.subtract(center)
        val b = oc.dot(rd)
        val c = oc.dot(oc) - radius * radius
        val disc = b * b - c
        if (disc < 0.0) return false
        val sqrt = Math.sqrt(disc)
        val t0 = -b - sqrt
        val t1 = -b + sqrt
        return t0 >= 1.0E-4 || t1 >= 1.0E-4
    }
}
