package net.omori_sunny.create_waterparked.client.editor

import com.mojang.blaze3d.vertex.PoseStack
import com.simibubi.create.AllItems
import dev.silvergold.simulatedcoasters.client.track.BezierHandleLiftTextures
import dev.silvergold.simulatedcoasters.client.track.BezierHandleOverlayRenderTypes
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.CoasterBezierHandleEdit
import dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.network.WaterslideRadiusEditPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.network.PacketDistributor
import org.joml.Matrix4f
import kotlin.math.abs

// radius edit handle
@OnlyIn(Dist.CLIENT)
object WaterslideRadiusEdit {

    private const val PICK_RADIUS = 0.35
    private const val WALL_SEGMENTS = 24

    private val previewRadii = mutableMapOf<BlockPos, Float>()
    private var dragging = false
    private var dragAnchor: BlockPos? = null

    private data class CircleFrame(val lateral: Vec3, val up: Vec3)

    // dragging state
    @JvmStatic
    fun isDragging(): Boolean = dragging

    // preview radius
    fun radiusAt(level: Level, anchorPos: BlockPos, fallback: Float): Float {
        previewRadii[anchorPos]?.let { return it }
        return (level.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity)?.radius ?: fallback
    }

    @JvmStatic
    fun mixinClientTick(mc: Minecraft) {
        val player = mc.player ?: return clear()
        val level = mc.level ?: return clear()
        if (!BezierHandleEditMode.isActive()) return clear()
        if (!AllItems.WRENCH.isIn(player.mainHandItem) && !AllItems.WRENCH.isIn(player.offhandItem)) return clear()
        val anchor = BezierHandleEditMode.getActiveAnchor() ?: return clear()
        val be = level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity ?: return clear()

        val useDown = mc.options.keyUse.isDown
        if (dragging) {
            val target = dragTargetWorld(mc, level, anchor) ?: return clear()
            val radius = radiusFromDistance(target.distanceTo(anchorCenter(level, anchor)))
            previewRadii[anchor] = radius
            if (!useDown) {
                PacketDistributor.sendToServer(WaterslideRadiusEditPayload(anchor, radius))
                previewRadii.remove(anchor)
                dragging = false
                dragAnchor = null
            }
        } else if (useDown) {
            val tip = handleTipWorld(level, anchor, be.radius)
            val hit = raySphere(player.eyePosition, player.getViewVector(1f), tip, PICK_RADIUS)
            if (hit) {
                dragging = true
                dragAnchor = anchor.immutable()
                previewRadii[anchor] = be.radius
            }
        }
    }

    @JvmStatic
    fun renderHandle(
        mc: Minecraft,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f
    ) {
        val level = mc.level ?: return
        if (!BezierHandleEditMode.isActive()) return
        val anchor = BezierHandleEditMode.getActiveAnchor() ?: return
        val be = level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity ?: return

        poseStack.pushPose()

        val radius = radiusAt(level, anchor, be.radius)
        drawAnchorCircle(level, anchor, be, poseStack, bufferSource, cameraPos, cameraRotation, radius, 0.2f, 0.9f, 1.0f)
        val tip = handleTipWorld(level, anchor, radius)
        val hovering = isHovering(mc, level, anchor, be.radius)
        drawHandleTip(
            poseStack, bufferSource, cameraPos, cameraRotation, tip,
            handleLateral(level, anchor), dragging, hovering
        )

        poseStack.popPose()
    }

    private fun isHovering(mc: Minecraft, level: Level, anchor: BlockPos, radius: Float): Boolean {
        val player = mc.player ?: return false
        return raySphere(player.eyePosition, player.getViewVector(1f), handleTipWorld(level, anchor, radius), PICK_RADIUS)
    }

    private fun drawAnchorCircle(
        level: Level,
        anchor: BlockPos,
        be: WaterslideAnchorBlockEntity,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        radius: Float,
        r: Float,
        g: Float,
        b: Float
    ) {
        val center = anchorCenter(level, anchor)
        val frame = openingFrame(level, be, anchor)?.second?.let { (lat, up, _) -> CircleFrame(lat, up) }
            ?: run {
                val up = CoasterAnchorpointBlockEntity.localUp(level, anchor)
                val ref = if (abs(up.y) < 0.9f) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
                CircleFrame(up.cross(ref).normalize(), up)
            }
        val consumer = buffers.getBuffer(WaterslideEditorRenderTypes.COLORED_QUADS)
        for (i in 0 until WALL_SEGMENTS) {
            val a0 = i.toDouble() / WALL_SEGMENTS * 2.0 * Math.PI
            val a1 = (i + 1).toDouble() / WALL_SEGMENTS * 2.0 * Math.PI
            val p0 = circlePoint(center, frame, radius, a0)
            val p1 = circlePoint(center, frame, radius, a1)
            WaterslideEditorRenderTypes.billboardStrip(
                poseStack, consumer, cameraPos, cameraRotation, p0, p1, 0.045f, r, g, b, 1.0f
            )
        }
    }

    private fun circlePoint(center: Vec3, frame: CircleFrame, radius: Float, rad: Double): Vec3 =
        center
            .add(frame.lateral.scale(Math.cos(rad) * radius))
            .add(frame.up.scale(Math.sin(rad) * radius))

    private fun drawHandleTip(
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        tip: Vec3,
        upWorld: Vec3,
        draggingThis: Boolean,
        hovering: Boolean
    ) {
        val tex = when {
            draggingThis -> BezierHandleLiftTextures.DRAGGING
            hovering -> BezierHandleLiftTextures.HOVER
            else -> BezierHandleLiftTextures.DEFAULT
        }
        WaterslideEditorRenderTypes.billboardOrientedTexturedQuad(
            poseStack,
            buffers.getBuffer(BezierHandleOverlayRenderTypes.tangentHandleBillboard(tex)),
            cameraPos, cameraRotation, tip, upWorld, 0.22f, 0.22f
        )
    }

    // opening center and face
    private fun openingFrame(
        level: Level,
        be: WaterslideAnchorBlockEntity,
        anchor: BlockPos
    ): Pair<Vec3, Triple<Vec3, Vec3, Vec3>>? {
        // live bezier preview frame
        WaterslideSectorEdit.liveAnchorFrame(level, anchor)?.let { live ->
            val face = live.lateral.cross(live.up).normalize()
            return live.center to Triple(live.lateral, live.up, face)
        }
        for ((_, raw) in be.anchorPeerCurvesView) {
            val primary = if (raw.isPrimary) raw else raw.secondary()
            if (!WaterslideTrackMaterials.isWaterslide(primary)) continue
            val t = if (primary.bePositions.getFirst() == anchor) 0f else 1f
            val center = primary.getPosition(t.toDouble())
            val lateral = CoasterBezierRailFrames.lateralAt(primary, t, level)
            val up = CoasterBezierRailFrames.faceUpAt(primary, t, level)
            val tangentInto = CoasterBezierHandleEdit.tangentIntoFromAnchor(primary, anchor)
            val faceNormal = if (tangentInto != null) {
                CoasterTrackPlacement.anchorFaceNormalForAxis(level, anchor, tangentInto)
            } else {
                CoasterAnchorpointBlockEntity.localUp(level, anchor)
            }
            return center to Triple(lateral, up, faceNormal)
        }
        return null
    }

    private fun anchorCenter(level: Level, pos: BlockPos): Vec3 {
        val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity
        return be?.let { openingFrame(level, it, pos)?.first } ?: CoasterAnchorpointBlockEntity.worldCenter(level, pos)
    }

    private fun handleLateral(level: Level, pos: BlockPos): Vec3 {
        val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity
        return be?.let { openingFrame(level, it, pos)?.second?.first }
            ?: run {
                val up = CoasterAnchorpointBlockEntity.localUp(level, pos)
                val ref = if (abs(up.y) < 0.9f) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
                up.cross(ref).normalize()
            }
    }

    private fun handleTipWorld(level: Level, pos: BlockPos, radius: Float): Vec3 {
        val center = anchorCenter(level, pos)
        // tip on the ring
        return center.add(handleLateral(level, pos).scale(radius.toDouble()))
    }

    private fun dragTargetWorld(mc: Minecraft, level: Level, pos: BlockPos): Vec3? {
        val player = mc.player ?: return null
        val eye = player.eyePosition
        val view = player.getViewVector(1f)
        val center = anchorCenter(level, pos)
        val t = view.dot(center.subtract(eye))
        return eye.add(view.scale(t))
    }

    private fun radiusFromDistance(dist: Double): Float {
        return ModConfig.clampSlideRadius(dist.toFloat())
    }

    private fun raySphere(ro: Vec3, rd: Vec3, center: Vec3, radius: Double): Boolean {
        val oc = ro.subtract(center)
        val b = oc.dot(rd)
        val c = oc.dot(oc) - radius * radius
        val disc = b * b - c
        if (disc < 0.0) return false
        val sqrt = Math.sqrt(disc)
        val t0 = -b - sqrt
        val t1 = -b + sqrt
        return (t0 >= 1.0E-4) || (t1 >= 1.0E-4)
    }

    private fun clear() {
        previewRadii.clear()
        dragging = false
        dragAnchor = null
    }
}
