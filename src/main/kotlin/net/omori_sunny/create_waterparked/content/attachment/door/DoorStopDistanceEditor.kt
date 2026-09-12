package net.omori_sunny.create_waterparked.content.attachment.door

import com.mojang.blaze3d.vertex.PoseStack
import dev.silvergold.simulatedcoasters.client.track.CoasterAnchorClientSpace
import dev.silvergold.simulatedcoasters.track.CoasterBezierHandleEdit
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.editor.SlideAttachmentEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditorRenderTypes
import net.omori_sunny.create_waterparked.client.editor.controlpoint.SlideControlPointEditor
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentGeometry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelContext
import org.joml.Matrix4f
import kotlin.math.abs

class DoorStopDistanceEditor(
    private val bePos: BlockPos
) : SlideControlPointEditor("$EDITOR_KEY#${bePos.asLong()}") {

    companion object {
        const val EDITOR_KEY = "mechanical_door_stop"
        const val TAG_STOP_L = "DoorStopL"
        const val TAG_STOP_R = "DoorStopR"
        const val DEFAULT_STOP = 1.2f
        const val MIN_STOP = 0.5f
        const val MAX_STOP = 5.0f

        const val MIN_T = 0.02f
        const val MAX_T = 0.98f

        private const val OUTLINE_KEY = "waterparked:attachment_position_outline"

        fun stopL(data: CompoundTag): Float =
            data.getFloat(TAG_STOP_L).let { if (it <= 0f) DEFAULT_STOP else it.coerceIn(MIN_STOP, MAX_STOP) }

        fun stopR(data: CompoundTag): Float =
            data.getFloat(TAG_STOP_R).let { if (it <= 0f) DEFAULT_STOP else it.coerceIn(MIN_STOP, MAX_STOP) }

        private fun tex(name: String): ResourceLocation =
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "textures/ui/$name.png")

        private val TEX_DEFAULT = tex("attachment_handle")
        private val TEX_HOVER = tex("attachment_handle_hover")
        private val TEX_DRAG = tex("attachment_handle_drag")

        // falls back to our textures when the stock ones are missing
        private fun ccsStyle(): CPTextures {
            val mc = Minecraft.getInstance()
            val hasCcs = mc.resourceManager.getResource(
                dev.silvergold.simulatedcoasters.client.track.BezierHandleTangentTextures.DEFAULT
            ).isPresent
            return if (hasCcs) CCS_STYLE else CPTextures(TEX_DEFAULT, TEX_HOVER, TEX_DRAG)
        }

        private val CCS_STYLE = CPTextures(
            dev.silvergold.simulatedcoasters.client.track.BezierHandleTangentTextures.DEFAULT,
            dev.silvergold.simulatedcoasters.client.track.BezierHandleTangentTextures.HOVER,
            dev.silvergold.simulatedcoasters.client.track.BezierHandleTangentTextures.DRAGGING
        )
    }

    private var previewL: Double? = null
    private var previewR: Double? = null
    private var previewT: Float? = null

    private var dragStartT: Float = 0.5f
    private var dragStartCentre: Vec3? = null
    private var dragAxis: Vec3? = null

    private class Resolved(val ctx: SlideAttachmentModelContext, val curve: com.simibubi.create.content.trains.track.BezierConnection)

    private fun clientBe(): SlideAttachmentBlockEntity? =
        SlideAttachmentEdit.editingBe()?.takeIf { it.blockPos == bePos }
            ?: (Minecraft.getInstance().level?.getBlockEntity(bePos) as? SlideAttachmentBlockEntity)

    private fun resolveFor(be: SlideAttachmentBlockEntity, t: Float): Resolved? {
        val level = Minecraft.getInstance().level ?: return null
        val entry = be.entry ?: return null
        val anchor = net.omori_sunny.create_waterparked.client.editor.SableClientEdit
            .resolve(level, entry.curveA)?.be
            ?: level.getBlockEntity(entry.curveA)
                as? net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
            ?: return null
        val raw = anchor.anchorPeerCurvesView[entry.curveB.immutable()] ?: return null
        val curve = if (raw.isPrimary) raw else raw.secondary() ?: return null
        val ctx = SlideAttachmentGeometry.contextAt(level, be.blockPos, curve, t, entry.angle, entry.data)
        return Resolved(ctx, curve)
    }

    // centre and axis, in plot space
    private fun frameFor(be: SlideAttachmentBlockEntity, t: Float): Pair<Vec3, Vec3>? {
        val r = resolveFor(be, t) ?: return null
        val outer = r.ctx.radius + r.ctx.wallThickness - 0.1f
        val centre = r.ctx.position.subtract(r.ctx.radialOut.scale(outer.toDouble()))
        return centre to r.ctx.tangent
    }

    override fun debugResolveOk(): Boolean {
        val be = net.omori_sunny.create_waterparked.client.attachment.SlideAttachmentClientIndex
            .all().firstOrNull { it.blockPos == bePos } ?: return false
        return frameFor(be, be.entry?.t ?: 0.5f) != null
    }

    private fun currentT(): Float = previewT ?: (clientBe()?.entry?.t ?: 0.5f)

    private fun frame(): Pair<Vec3, Vec3>? {
        val be = clientBe() ?: return null
        return frameFor(be, currentT())
    }

    // in blocks, not a curve parameter
    private fun curveArcLength(curve: com.simibubi.create.content.trains.track.BezierConnection): Double {
        var prev = curve.getPosition(0.0)
        var sum = 0.0
        val steps = 64
        for (i in 1..steps) {
            val p = curve.getPosition(i.toDouble() / steps)
            sum += p.distanceTo(prev)
            prev = p
        }
        return sum.coerceAtLeast(1.0E-3)
    }

    private fun value(key: String): Double = when (key) {
        "L" -> previewL ?: clientBe()?.entry?.data?.let { stopL(it).toDouble() } ?: DEFAULT_STOP.toDouble()
        "R" -> previewR ?: clientBe()?.entry?.data?.let { stopR(it).toDouble() } ?: DEFAULT_STOP.toDouble()
        else -> currentT().toDouble()
    }

    override fun active(): Boolean =
        SlideAttachmentEdit.isEditing() && SlideAttachmentEdit.editingBe()?.blockPos == bePos

    override fun activePos(): BlockPos? = if (active()) bePos else null

    override fun points(): List<ControlPoint> {
        val (centre, tangent) = frame() ?: return emptyList()
        val list = ArrayList<ControlPoint>(3)
        list.add(ControlPoint("L", centre.subtract(tangent.scale(value("L"))), 0.3, tangent))
        list.add(ControlPoint("R", centre.add(tangent.scale(value("R"))), 0.3, tangent))
        if (clientBe() != null) {
            list.add(ControlPoint("T", centre, 0.3, tangent, ccsStyle()))
        }
        return list
    }

    override fun textures(): CPTextures = CPTextures(TEX_DEFAULT, TEX_HOVER, TEX_DRAG)

    override fun snapUnit(): Double = 0.1

    // false makes the handles always face the camera
    override fun orientedHandles(): Boolean = false

    override fun onDragStart(point: ControlPoint) {
        if (point.key != "T") return
        val be = clientBe() ?: return
        dragStartT = currentT()
        val f = frameFor(be, dragStartT) ?: return
        dragStartCentre = f.first
        dragAxis = f.second
    }

    override fun rawValue(point: ControlPoint, eye: Vec3, view: Vec3): Double? {
        val (centre, tangent) = frame() ?: return null
        return if (point.key == "T") {
            val ref = dragStartCentre ?: return null
            val axis = dragAxis ?: tangent
            lineLineDistance(eye, view, ref, axis) ?: value("T") * arcLen()
        } else {
            val d = lineLineDistance(eye, view, centre, tangent) ?: return value(point.key)
            abs(d).coerceIn(MIN_STOP.toDouble(), MAX_STOP.toDouble())
        }
    }

    private fun arcLen(): Double {
        val be = clientBe() ?: return 1.0
        val r = resolveFor(be, currentT()) ?: return 1.0
        return curveArcLength(r.curve)
    }

    override fun applyValue(point: ControlPoint, value: Double) {
        when (point.key) {
            "L" -> previewL = value.coerceIn(MIN_STOP.toDouble(), MAX_STOP.toDouble())
            "R" -> previewR = value.coerceIn(MIN_STOP.toDouble(), MAX_STOP.toDouble())
            else -> {
                val dt = value / arcLen()
                previewT = (dragStartT + dt).toFloat().coerceIn(MIN_T, MAX_T)
            }
        }
    }

    override fun currentValue(point: ControlPoint): Double = value(point.key)

    override fun onClear() {
        previewL = null
        previewR = null
        previewT = null
        dragStartCentre = null
        dragAxis = null
    }

    override fun statusReadout(): Component = line()

    override fun readout(point: ControlPoint, value: Double): Component = line()

    private fun line(): Component =
        Component.translatable(
            "create_waterparked.track.attachment_edit_readout",
            CoasterBezierHandleEdit.formatLiftMetersReadout(positionMeters()),
            CoasterBezierHandleEdit.formatLiftMetersReadout(value("L").toFloat()),
            CoasterBezierHandleEdit.formatLiftMetersReadout(value("R").toFloat())
        )

    // in blocks, despite the function name
    private fun positionMeters(): Float {
        val be = clientBe() ?: return 0f
        val r = resolveFor(be, currentT()) ?: return 0f
        return arcLengthTo(r.curve, currentT())
    }

    private fun arcLengthTo(
        curve: com.simibubi.create.content.trains.track.BezierConnection,
        t: Float
    ): Float {
        val end = t.coerceIn(0f, 1f).toDouble()
        var prev = curve.getPosition(0.0)
        var sum = 0.0
        val steps = 64
        for (i in 1..steps) {
            val p = curve.getPosition(end * i / steps)
            sum += p.distanceTo(prev)
            prev = p
        }
        return sum.toFloat()
    }

    override fun commit(point: ControlPoint) {
        val kind = when (point.key) {
            "L" -> "stopL"
            "R" -> "stopR"
            else -> "t"
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            net.omori_sunny.create_waterparked.network.SlideAttachmentEditPayload(
                bePos, kind, value(point.key).toFloat()
            )
        )
    }

    private fun anchorPos(): BlockPos? = clientBe()?.entry?.curveA

    override fun toLocal(p: Vec3): Vec3 {
        val level = Minecraft.getInstance().level ?: return p
        val anchor = anchorPos() ?: return p
        return CoasterAnchorClientSpace.toPlotLocal(level, anchor, p)
    }

    override fun toLocalDir(v: Vec3): Vec3 {
        val level = Minecraft.getInstance().level ?: return v
        val anchor = anchorPos() ?: return v
        return CoasterAnchorClientSpace.toPlotLocalDirection(level, anchor, v).normalize()
    }

    override fun toRender(p: Vec3): Vec3 {
        val level = Minecraft.getInstance().level ?: return p
        val anchor = anchorPos() ?: return p
        return CoasterAnchorClientSpace.toRenderWorld(level, anchor, p)
    }

    override fun toRenderDir(v: Vec3): Vec3 {
        val level = Minecraft.getInstance().level ?: return v
        val anchor = anchorPos() ?: return v
        val d = CoasterAnchorClientSpace.toRenderDirection(level, anchor, v)
        return if (d.lengthSqr() < 1.0E-12) v.normalize() else d.normalize()
    }

    // drawn before the handles
    override fun renderExtra(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f
    ) {
        val (centre, tangent) = frame() ?: return
        val a = centre.subtract(tangent.scale(value("L")))
        val b = centre.add(tangent.scale(value("R")))
        val consumer = bufferSource.getBuffer(WaterslideEditorRenderTypes.COLORED_QUADS)
        WaterslideEditorRenderTypes.worldLine(
            poseStack, consumer, cameraPos, cameraRotation,
            toRender(a), toRender(b), 0.045f, 0.9f, 0.2f, 0.2f, 1.0f
        )
        renderPositionOutline()
    }

    private var outlineSegments = 0

    private fun renderPositionOutline() {
        if (draggedKey() != "T") {
            if (outlineSegments > 0) {
                dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement
                    .clearCoasterCurveOutlinePreview(OUTLINE_KEY, outlineSegments)
                outlineSegments = 0
            }
            return
        }
        val be = clientBe() ?: return
        val r = resolveFor(be, currentT()) ?: return
        outlineSegments = dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement
            .drawCoasterCurveOutlinePreview(
                r.curve, OUTLINE_KEY,
                dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement.CONNECTION_VALID_GREEN,
                0.0, outlineSegments
            )
    }

    // returns null when the ray and the line are parallel
    private fun lineLineDistance(eye: Vec3, view: Vec3, c: Vec3, axis: Vec3): Double? {
        val w0 = eye.subtract(c)
        val av = axis.dot(view)
        val denom = 1.0 - av * av
        if (abs(denom) < 1.0E-4) return null
        return (w0.dot(axis) - w0.dot(view) * av) / denom
    }
}
