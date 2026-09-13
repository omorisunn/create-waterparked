package net.omori_sunny.create_waterparked.content.attachment.accelerator

import com.mojang.blaze3d.vertex.PoseStack
import dev.silvergold.simulatedcoasters.track.CoasterBezierHandleEdit
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditorRenderTypes
import org.joml.Matrix4f
import net.omori_sunny.create_waterparked.client.editor.controlpoint.SlideDistanceHandleEditor
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry

// the shared distance handles plus a 2D boost direction handle
class AcceleratorDistanceEditor(bePos: BlockPos) :
    SlideDistanceHandleEditor(bePos, EDITOR_KEY) {

    companion object {
        const val EDITOR_KEY = "accelerator_direction"
        private const val SNAP_DEGREES = 5.0
        private const val HANDLE_RADIUS = 1.5
        private const val DEGREES = "\u00B0"
        private const val LINE_HALF = 0.035f
        private const val HEAD_LENGTH = 0.45
        private const val HEAD_HALF = 0.3

        private fun handle(name: String): ResourceLocation =
            ResourceLocation.fromNamespaceAndPath("create_waterparked", "textures/ui/$name.png")

        // the billboard marks the arrow tip; the arrow itself is geometry
        private val ARROW_HANDLE = CPTextures(
            handle("accelerator_handle"),
            handle("accelerator_handle_hover"),
            handle("accelerator_handle_drag")
        )
    }

    private var previewDir: Double? = null

    override fun handleTag(side: Int): String =
        if (side < 0) AcceleratorAttachment.TAG_DIST_L else AcceleratorAttachment.TAG_DIST_R

    override val commitPrefix: String = "accel"

    override val readoutKey: String = "create_waterparked.track.accelerator_edit_readout"

    private fun direction(): Double = previewDir
        ?: clientBe()?.entry?.data?.let { AcceleratorAttachment.directionDegrees(it).toDouble() }
        ?: 0.0

    private fun angleText(): String = "%.0f".format(direction()) + DEGREES

    // direction angle of the boost, measured from the tangent toward the lateral axis
    private fun handlePos(): Vec3? {
        val (centre, tangent) = frame() ?: return null
        val (lateral, _) = SlideCurveGeometry.stableFrame(tangent)
        val radians = Math.toRadians(direction())
        return centre
            .add(tangent.scale(Math.cos(radians) * HANDLE_RADIUS))
            .add(lateral.scale(Math.sin(radians) * HANDLE_RADIUS))
    }

    override fun points(): List<ControlPoint> {
        val list = ArrayList(super.points())
        val (_, tangent) = frame() ?: return list
        val pos = handlePos() ?: return list
        val (_, up) = SlideCurveGeometry.stableFrame(tangent)
        list.add(ControlPoint("D", pos, 0.3, up, ARROW_HANDLE))
        return list
    }

    // a 5 degree grid measured from the tangent, so the local axes land on exact multiples
    override fun snap(point: ControlPoint, raw: Double): Double =
        if (point.key != "D") super.snap(point, raw)
        else if (!snapEnabled()) raw
        else Math.round(raw / SNAP_DEGREES) * SNAP_DEGREES

    // the direction control point is a green arrow pointing along the boost
    override fun renderExtra(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f
    ) {
        super.renderExtra(poseStack, bufferSource, cameraPos, cameraRotation)
        val (centre, tangent) = frame() ?: return
        val pos = handlePos() ?: return
        val root = toRender(centre)
        val tip = toRender(pos)
        val span = tip.subtract(root)
        if (span.lengthSqr() < 1.0E-8) return
        val unit = span.normalize()
        val (lateral, _) = SlideCurveGeometry.stableFrame(tangent)
        val side = toRenderDir(lateral).scale(HEAD_HALF)
        val back = tip.subtract(unit.scale(HEAD_LENGTH))
        val consumer = bufferSource.getBuffer(WaterslideEditorRenderTypes.COLORED_QUADS)
        fun stroke(a: Vec3, b: Vec3) {
            WaterslideEditorRenderTypes.worldLine(
                poseStack, consumer, cameraPos, cameraRotation, a, b,
                LINE_HALF, 0.2f, 1.0f, 0.3f, 1.0f
            )
        }
        stroke(root, tip)
        stroke(tip, back.add(side))
        stroke(tip, back.subtract(side))
    }

    override fun rawValue(point: ControlPoint, eye: Vec3, view: Vec3): Double? {
        if (point.key != "D") return super.rawValue(point, eye, view)
        val (centre, tangent) = frame() ?: return null
        val (lateral, up) = SlideCurveGeometry.stableFrame(tangent)
        val denom = view.dot(up)
        if (kotlin.math.abs(denom) < 1.0E-4) return null
        val hit = eye.add(view.scale(centre.subtract(eye).dot(up) / denom))
        val offset = hit.subtract(centre)
        if (offset.lengthSqr() < 1.0E-8) return null
        return Math.toDegrees(Math.atan2(offset.dot(lateral), offset.dot(tangent)))
    }

    override fun applyValue(point: ControlPoint, value: Double) {
        if (point.key != "D") {
            super.applyValue(point, value)
            return
        }
        previewDir = ((value % 360.0) + 360.0) % 360.0
    }

    override fun currentValue(point: ControlPoint): Double =
        if (point.key == "D") direction() else super.currentValue(point)

    override fun onClear() {
        super.onClear()
        previewDir = null
    }

    override fun commit(point: ControlPoint) {
        if (point.key != "D") {
            super.commit(point)
            return
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            net.omori_sunny.create_waterparked.network.SlideAttachmentEditPayload(
                bePos, "accelDir", currentValue(point).toFloat()
            )
        )
    }

    override fun readout(point: ControlPoint, value: Double): Component =
        if (point.key == "D") directionLine() else line()

    private fun directionLine(): Component = Component.translatable(
        "create_waterparked.track.accelerator_direction",
        angleText()
    )

    override fun line(): Component = Component.translatable(
        readoutKey,
        CoasterBezierHandleEdit.formatLiftMetersReadout(positionMeters()),
        CoasterBezierHandleEdit.formatLiftMetersReadout(value("L").toFloat()),
        CoasterBezierHandleEdit.formatLiftMetersReadout(value("R").toFloat()),
        angleText()
    )
}
