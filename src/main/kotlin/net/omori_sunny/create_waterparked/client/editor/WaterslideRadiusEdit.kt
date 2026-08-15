package net.omori_sunny.create_waterparked.client.editor

import com.mojang.blaze3d.vertex.PoseStack
import com.simibubi.create.AllItems
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager
import dev.silvergold.simulatedcoasters.client.track.BezierHandleLiftTextures
import dev.silvergold.simulatedcoasters.client.track.BezierHandleOverlayRenderTypes
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.CoasterBezierHandleEdit
import dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.network.WaterslideRadiusEditPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.network.PacketDistributor
import org.joml.Matrix4f
import org.joml.Vector3d
import kotlin.math.abs

// radius edit handle
@OnlyIn(Dist.CLIENT)
object WaterslideRadiusEdit {

    private const val PICK_RADIUS = 0.35
    private const val WALL_SEGMENTS = 24

    private val previewRadii = mutableMapOf<BlockPos, Float>()
    private var dragging = false
    private var dragAnchor: BlockPos? = null
    private var lastChainRefreshRadius = -1f

    private data class CircleFrame(val lateral: Vec3, val up: Vec3)

    // dragging state
    @JvmStatic
    fun isDragging(): Boolean = dragging

    // hover or drag, for CCS suppression
    @JvmStatic
    fun isHoveringOrDragging(mc: Minecraft): Boolean {
        if (dragging) return true
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        if (!BezierHandleEditMode.isActive()) return false
        val anchor = SubLevelEditFocus.activeAnchor(level) ?: return false
        val ctx = SableClientEdit.resolve(level, anchor) ?: return false
        val eye = if (ctx.sub == null) player.eyePosition else SableClientEdit.worldToPlot(ctx.sub!!, player.eyePosition)
        val view = if (ctx.sub == null) player.getViewVector(1f)
        else ctx.sub!!.logicalPose().transformNormal(
            JOMLConversion.toJOML(player.getViewVector(1f)), Vector3d()
        ).let { JOMLConversion.toMojang(it) }
        return raySphere(eye, view, handleTipWorld(level, ctx.globalPos, ctx.be.radius), PICK_RADIUS)
    }

    // preview radius
    @JvmStatic
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
        val anchor = SubLevelEditFocus.activeAnchor(level) ?: return clear()
        val ctx = SableClientEdit.resolve(level, anchor) ?: return clear()
        val be = ctx.be
        if (WaterslideSectorEdit.isDraggingControlPoint() || BezierHandleDragManager.isDraggingHandle()) return

        val eye = if (ctx.sub == null) player.eyePosition else SableClientEdit.worldToPlot(ctx.sub!!, player.eyePosition)
        val view = if (ctx.sub == null) player.getViewVector(1f)
        else ctx.sub!!.logicalPose().transformNormal(
            JOMLConversion.toJOML(player.getViewVector(1f)), Vector3d()
        ).let { JOMLConversion.toMojang(it) }

        val useDown = mc.options.keyUse.isDown
        if (dragging) {
            val target = dragTarget(eye, view, level, ctx.globalPos) ?: return clear()
            val radius = radiusFromDistance(target.distanceTo(anchorCenter(level, ctx.globalPos)))
            previewRadii[anchor] = radius
            player.displayClientMessage(
                Component.translatable(
                    "create_waterparked.track.bezier_edit_radius_meters",
                    CoasterBezierHandleEdit.formatLiftMetersReadout(radius)
                ).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF))),
                true
            )
            if (!useDown) {
                PacketDistributor.sendToServer(WaterslideRadiusEditPayload(anchor, radius))
                previewRadii.remove(anchor)
                dragging = false
                dragAnchor = null
            }
        } else if (useDown) {
            val tip = handleTipWorld(level, ctx.globalPos, be.radius)
            if (raySphere(eye, view, tip, PICK_RADIUS)) {
                dragging = true
                dragAnchor = anchor.immutable()
                previewRadii[anchor] = be.radius
                lastChainRefreshRadius = -1f
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
        val anchor = SubLevelEditFocus.activeAnchor(level) ?: return
        val ctx = SableClientEdit.resolve(level, anchor) ?: return
        val be = ctx.be

        poseStack.pushPose()

        val radius = previewRadii[anchor] ?: be.radius
        drawAnchorCircle(level, ctx, poseStack, bufferSource, cameraPos, cameraRotation, radius, 0.2f, 0.9f, 1.0f)
        val tipPlot = handleTipWorld(level, ctx.globalPos, radius)
        val tip = if (ctx.sub == null) tipPlot else SableClientEdit.toWorld(ctx.sub!!, tipPlot)
        val lateralPlot = handleLateral(level, ctx.globalPos)
        val lateral = if (ctx.sub == null) lateralPlot else SableClientEdit.toWorldNormal(ctx.sub!!, lateralPlot)
        val hovering = isHovering(mc, level, ctx.globalPos, radius)
        drawHandleTip(
            poseStack, bufferSource, cameraPos, cameraRotation, tip,
            lateral, dragging, hovering
        )

        poseStack.popPose()
    }

    private fun isHovering(mc: Minecraft, level: Level, anchor: BlockPos, radius: Float): Boolean {
        val player = mc.player ?: return false
        val ctx = SableClientEdit.resolve(level, anchor) ?: return false
        val eye = if (ctx.sub == null) player.eyePosition else SableClientEdit.worldToPlot(ctx.sub!!, player.eyePosition)
        val view = if (ctx.sub == null) player.getViewVector(1f)
        else ctx.sub!!.logicalPose().transformNormal(
            JOMLConversion.toJOML(player.getViewVector(1f)), Vector3d()
        ).let { JOMLConversion.toMojang(it) }
        return raySphere(eye, view, handleTipWorld(level, ctx.globalPos, radius), PICK_RADIUS)
    }

    private fun dragTarget(eye: Vec3, view: Vec3, level: Level, pos: BlockPos): Vec3? {
        val center = anchorCenter(level, pos)
        val t = view.dot(center.subtract(eye))
        return eye.add(view.scale(t))
    }

    private fun drawAnchorCircle(
        level: Level,
        ctx: SableClientEdit.AnchorCtx,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        radius: Float,
        r: Float,
        g: Float,
        b: Float
    ) {
        val centerPlot = anchorCenter(level, ctx.globalPos)
        val center = if (ctx.sub == null) centerPlot else SableClientEdit.toWorld(ctx.sub!!, centerPlot)
        val framePlot = openingFrame(level, ctx.be, ctx.globalPos)?.second?.let { (lat, up, _) -> CircleFrame(lat, up) }
            ?: run {
                val up = CoasterAnchorpointBlockEntity.localUp(level, ctx.globalPos)
                val ref = if (abs(up.y) < 0.9f) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
                CircleFrame(up.cross(ref).normalize(), up)
            }
        val frame = if (ctx.sub == null) framePlot else CircleFrame(
            SableClientEdit.toWorldNormal(ctx.sub!!, framePlot.lateral),
            SableClientEdit.toWorldNormal(ctx.sub!!, framePlot.up)
        )
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
