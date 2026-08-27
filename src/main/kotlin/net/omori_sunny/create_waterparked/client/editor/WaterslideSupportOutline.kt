package net.omori_sunny.create_waterparked.client.editor

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.item.AxeItem
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import org.joml.Matrix4f

// support beam / bracket hover outline, same style as the slide dye outline:
// billboard strips along the actually rendered part shape, no depth test so
// it stays visible through the tube, thick constant camera-facing quads.
@OnlyIn(Dist.CLIENT)
object WaterslideSupportOutline {

    private const val HALF_WIDTH = 0.05f

    @JvmStatic
    fun render(
        mc: Minecraft,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f
    ) {
        val pick = WaterslideSupportEdit.hoveredPick() ?: return
        // only draw when the hover outline would be shown (held item rules)
        if (!WaterslideSupportEdit.outlineShownForHover(pick, mc)) return
        val points = pick.outline ?: return
        if (points.size < 3) return
        // axe = delete hint: red outline; otherwise the amber hover
        val axe = mc.player?.mainHandItem?.item is AxeItem ||
            mc.player?.offhandItem?.item is AxeItem
        val color = if (axe) 0xFF4444 else WaterslideSupportEdit.HOVER_COLOR
        val r = ((color shr 16) and 255) / 255f
        val g = ((color shr 8) and 255) / 255f
        val b = (color and 255) / 255f
        val consumer = bufferSource.getBuffer(WaterslideEditorRenderTypes.COLORED_QUADS)
        if (pick.part == 0) {
            // beam: 8 corners [base0..base3, top0..top3] -> 12 edges:
            // bottom ring, top ring and the four long corner columns
            for (i in 0..3) {
                val j = (i + 1) % 4
                strip(poseStack, consumer, cameraPos, cameraRotation, points[i], points[j], r, g, b)
                strip(poseStack, consumer, cameraPos, cameraRotation, points[4 + i], points[4 + j], r, g, b)
                strip(poseStack, consumer, cameraPos, cameraRotation, points[i], points[4 + i], r, g, b)
            }
        } else {
            // bracket: closed band loop around the shell
            for (i in points.indices) {
                val next = points[(i + 1) % points.size]
                strip(poseStack, consumer, cameraPos, cameraRotation, points[i], next, r, g, b)
            }
        }
    }

    private fun strip(
        poseStack: PoseStack,
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        a: Vec3,
        b2: Vec3,
        r: Float,
        g: Float,
        b: Float
    ) {
        WaterslideEditorRenderTypes.billboardStrip(
            poseStack, consumer, cameraPos, cameraRotation, a, b2,
            HALF_WIDTH, r, g, b, 0.9f
        )
    }
}
