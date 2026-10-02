package net.omori_sunny.create_waterparked.client.gui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.resources.ResourceLocation
import net.omori_sunny.create_waterparked.content.sketch.SlideSketchPreviewTooltip
import net.omori_sunny.create_waterparked.game.SlideProfile
import kotlin.math.abs

class SlideSketchPreviewClientTooltip(
    private val marker: SlideSketchPreviewTooltip
) : ClientTooltipComponent {

    private val profile: SlideProfile = marker.profile

    override fun getWidth(font: Font): Int = BOX

    override fun getHeight(): Int = BOX + 2

    override fun renderImage(font: Font, x: Int, y: Int, graphics: GuiGraphics) {
        graphics.blit(MAP_BACKGROUND, x, y, 0f, 0f, BOX, BOX, BOX, BOX)
        val ink = bufferCache.getOrPut(profile.signature()) { buildInk(profile) }
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate((x + BOX / 2).toFloat(), (y + BOX / 2).toFloat(), 0f)
        ink.flush(graphics, INK)
        pose.popPose()
    }

    private fun buildInk(profile: SlideProfile): RoughCanvas.PixelBuffer {
        var maxAbs = 0f
        for (v in profile.anchors) maxAbs = maxOf(maxAbs, abs(v))
        if (maxAbs < 1.0E-4f) maxAbs = 1f
        val scale = (INNER / 4f) / maxAbs
        val count = profile.anchors.size / 2
        fun sx(v: Float) = v * scale
        fun sy(v: Float) = -v * scale
        val pts = (0 until count).map { i ->
            sx(profile.anchors[i * 2]) to sy(profile.anchors[i * 2 + 1])
        }
        fun outHandle(i: Int): Pair<Float, Float> {
            val hx = profile.handles.getOrNull(i * 2) ?: 0f
            val hy = profile.handles.getOrNull(i * 2 + 1) ?: 0f
            return sx(hx) to sy(hy)
        }
        val outVecs = (0 until count).map { outHandle(it) }
        val inVecs = (0 until count).map { i ->
            if (profile.inHandles != null) {
                sx(profile.inHandles!![i * 2]) to sy(profile.inHandles!![i * 2 + 1])
            } else {
                val (hx, hy) = outHandle(i)
                -hx to -hy
            }
        }
        val modes = profile.modes
        val curved = (0 until count).map { i ->
            modes == null || modes[i] != SlideProfile.MODE_NONE
        }
        val buf = RoughCanvas.PixelBuffer()
        if (curved.any { it }) {
            buf.ops(RoughCanvas.bezierLoopOps(pts, outVecs, inVecs, curved, RoughCanvas.Options(1001L)))
        } else {
            buf.ops(RoughCanvas.polygonOps(pts, RoughCanvas.Options(1001L)))
        }
        return buf
    }

    companion object {
        private const val BOX = 64
        private const val INNER = 48
        private val MAP_BACKGROUND = ResourceLocation.withDefaultNamespace("textures/map/map_background.png")
        private val INK = 0xFF3B3025.toInt()
        private val bufferCache = object : LinkedHashMap<String, RoughCanvas.PixelBuffer>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, RoughCanvas.PixelBuffer>
            ): Boolean = size > 16
        }
    }
}
