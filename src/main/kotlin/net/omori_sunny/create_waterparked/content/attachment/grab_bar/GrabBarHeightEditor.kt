package net.omori_sunny.create_waterparked.content.attachment.grab_bar

import com.mojang.blaze3d.vertex.PoseStack
import dev.silvergold.simulatedcoasters.client.track.CoasterAnchorClientSpace
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.client.editor.SableClientEdit
import net.omori_sunny.create_waterparked.client.editor.SlideAttachmentEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditorRenderTypes
import net.omori_sunny.create_waterparked.client.editor.controlpoint.SlideControlPointEditor
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentGeometry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelContext
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.network.SlideAttachmentEditPayload
import net.neoforged.neoforge.network.PacketDistributor
import org.joml.Matrix4f
import java.util.Locale
import kotlin.math.abs

// the single red height handle of a grab bar
class GrabBarHeightEditor(private val bePos: BlockPos) :
    SlideControlPointEditor(EDITOR_KEY + "#" + bePos.asLong()) {

    companion object {
        const val EDITOR_KEY = "grab_bar_height"
        private const val SNAP = 0.1
        private const val METERS = "m"

        private fun tex(name: String): ResourceLocation =
            ResourceLocation.fromNamespaceAndPath("create_waterparked", "textures/ui/" + name + ".png")

        private val HANDLE = CPTextures(
            tex("attachment_handle"),
            tex("attachment_handle_hover"),
            tex("attachment_handle_drag")
        )
    }

    private var preview: Double? = null

    override fun active(): Boolean =
        SlideAttachmentEdit.isEditing() && SlideAttachmentEdit.editingBe()?.blockPos == bePos

    override fun activePos(): BlockPos? = if (active()) bePos else null

    override fun textures(): CPTextures = HANDLE

    override fun snapUnit(): Double = SNAP

    override fun orientedHandles(): Boolean = false

    private fun clientBe(): SlideAttachmentBlockEntity? =
        SlideAttachmentEdit.editingBe()?.takeIf { it.blockPos == bePos }
            ?: (Minecraft.getInstance().level?.getBlockEntity(bePos) as? SlideAttachmentBlockEntity)

    private fun resolve(): SlideAttachmentModelContext? {
        val level = Minecraft.getInstance().level ?: return null
        val be = clientBe() ?: return null
        val entry = be.entry ?: return null
        val anchor = SableClientEdit.resolve(level, entry.curveA)?.be
            ?: level.getBlockEntity(entry.curveA) as? WaterslideAnchorBlockEntity
            ?: return null
        val raw = anchor.anchorPeerCurvesView[entry.curveB.immutable()] ?: return null
        val curve = if (raw.isPrimary) raw else raw.secondary() ?: return null
        return SlideAttachmentGeometry.contextAt(level, be.blockPos, curve, entry.t, entry.angle, entry.data)
    }

    override fun debugResolveOk(): Boolean = resolve() != null

    // the axis point of the frame, in plot space
    private fun axisPoint(ctx: SlideAttachmentModelContext): Vec3 {
        val outer = (ctx.radius + ctx.wallThickness - 0.1f).toDouble()
        val (x, y) = SlideAttachmentGeometry.axisOffset(ctx, outer)
        val (lat, up, _) = SlideAttachmentGeometry.basis(ctx)
        return ctx.position.add(lat.scale(x)).add(up.scale(y))
    }

    private fun value(): Double =
        preview ?: clientBe()?.entry?.data?.let { GrabBarAttachment.height(it).toDouble() } ?: 0.0

    override fun points(): List<ControlPoint> {
        val ctx = resolve() ?: return emptyList()
        val (_, up, _) = SlideAttachmentGeometry.basis(ctx)
        val pos = axisPoint(ctx).add(up.scale(value()))
        return listOf(ControlPoint("H", pos, 0.3, up, HANDLE))
    }

    override fun rawValue(point: ControlPoint, eye: Vec3, view: Vec3): Double? {
        val ctx = resolve() ?: return null
        val (_, up, _) = SlideAttachmentGeometry.basis(ctx)
        val ref = axisPoint(ctx)
        val w0 = eye.subtract(ref)
        val av = up.dot(view)
        val denom = 1.0 - av * av
        if (abs(denom) < 1.0E-4) return null
        return (w0.dot(up) - w0.dot(view) * av) / denom
    }

    override fun applyValue(point: ControlPoint, value: Double) {
        val clamped = value.coerceIn(
            GrabBarAttachment.MIN_HEIGHT.toDouble(), GrabBarAttachment.MAX_HEIGHT.toDouble()
        )
        preview = clamped
        // client only peek so the bar follows the drag, the commit still comes from the server
        clientBe()?.entry?.data?.putFloat(GrabBarAttachment.TAG_HEIGHT, clamped.toFloat())
    }

    override fun currentValue(point: ControlPoint): Double = value()

    override fun onClear() {
        preview = null
    }

    override fun commit(point: ControlPoint) {
        PacketDistributor.sendToServer(
            SlideAttachmentEditPayload(bePos, "grabHeight", currentValue(point).toFloat())
        )
    }

    override fun statusReadout(): Component = line()

    override fun readout(point: ControlPoint, value: Double): Component = line()

    private fun line(): Component = Component.translatable(
        "create_waterparked.track.grab_bar_height",
        "%.1f".format(Locale.ROOT, value()) + METERS
    )

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

    // the height guide line runs along the tube up axis
    override fun renderExtra(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f
    ) {
        val ctx = resolve() ?: return
        val (_, up, _) = SlideAttachmentGeometry.basis(ctx)
        val ref = axisPoint(ctx)
        val consumer = bufferSource.getBuffer(WaterslideEditorRenderTypes.COLORED_QUADS)
        WaterslideEditorRenderTypes.worldLine(
            poseStack, consumer, cameraPos, cameraRotation,
            toRender(ref.add(up.scale(GrabBarAttachment.MIN_HEIGHT.toDouble()))),
            toRender(ref.add(up.scale(GrabBarAttachment.MAX_HEIGHT.toDouble()))),
            0.03f, 0.9f, 0.2f, 0.2f, 1.0f
        )
    }
}
