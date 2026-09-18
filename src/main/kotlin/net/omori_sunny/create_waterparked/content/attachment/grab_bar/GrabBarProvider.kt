package net.omori_sunny.create_waterparked.content.attachment.grab_bar

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.AABB
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentGeometry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelContext
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelProvider

// single bar spanning the tube opening at the height of the red handle
class GrabBarProvider : SlideAttachmentModelProvider() {

    companion object {
        private const val BAR_THICKNESS = 0.15
        private const val WALL_INSET = 0.1f
    }

    override fun boundingBox(ctx: SlideAttachmentModelContext): AABB = barBox(ctx)

    override fun parts(ctx: SlideAttachmentModelContext): List<Part> {
        val inner = (ctx.radius - WALL_INSET).coerceAtLeast(0.05f).toDouble()
        val (axisX, axisY) = axis(ctx)
        val height = GrabBarAttachment.height(ctx.data).toDouble()
        return listOf(
            BoxPart(
                axisX, axisY + height, 0.0,
                inner * 2.0, BAR_THICKNESS, BAR_THICKNESS
            )
        )
    }

    private fun barBox(ctx: SlideAttachmentModelContext): AABB {
        val inner = (ctx.radius - WALL_INSET).coerceAtLeast(0.05f).toDouble()
        val (axisX, axisY) = axis(ctx)
        val half = BAR_THICKNESS / 2.0
        val height = GrabBarAttachment.height(ctx.data).toDouble()
        return ctx.localAABB(
            axisX - inner, axisY + height - half, -half,
            axisX + inner, axisY + height + half, half
        )
    }

    private fun axis(ctx: SlideAttachmentModelContext): Pair<Double, Double> =
        SlideAttachmentGeometry.axisOffset(
            ctx, (ctx.radius + ctx.wallThickness - WALL_INSET).toDouble()
        )
}
