package net.omori_sunny.create_waterparked.client.editor

import com.mojang.blaze3d.vertex.PoseStack
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import net.omori_sunny.create_waterparked.game.WaterslideSectorBlockEdit
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.util.Mth
import net.minecraft.world.item.DyeItem
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import org.joml.Matrix4f
import kotlin.math.max

// dyeable sector outline
@OnlyIn(Dist.CLIENT)
object WaterslideDyeOutline {

    private const val ARC_SEGMENTS = 48

    @JvmStatic
    fun render(
        mc: Minecraft,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraPos: Vec3,
        cameraRotation: Matrix4f
    ) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val dye = (player.mainHandItem.item as? DyeItem) ?: (player.offhandItem.item as? DyeItem) ?: return
        val hit = WaterslideSectorEdit.sectorUnderCursor(mc) ?: return
        val blockId = hit.blockId ?: return
        val newBlock = WaterslideSectorBlockEdit.dyedBlockFor(blockId, dye.dyeColor) ?: return

        val bc = hit.curve
        val r0 = (level.getBlockEntity(bc.bePositions.getFirst()) as? net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity)
            ?.radius ?: net.omori_sunny.create_waterparked.config.ModConfig.defaultSlideRadius()
        val r1 = (level.getBlockEntity(bc.bePositions.getSecond()) as? net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity)
            ?.radius ?: net.omori_sunny.create_waterparked.config.ModConfig.defaultSlideRadius()
        val rgb = dye.dyeColor.getTextureDiffuseColor()
        val r = ((rgb shr 16) and 255) / 255f
        val g = ((rgb shr 8) and 255) / 255f
        val b = (rgb and 255) / 255f

// sample the whole tube
        val count = bc.getSegmentCount().coerceAtLeast(1)
        val ts = FloatArray(count + 1) { i ->
            if (i == 0) 0f else if (i == count) 1f else bc.getSegmentT(i)
        }
        val centers = Array(count + 1) { bc.getPosition(ts[it].toDouble()) }
        val lats = arrayOfNulls<Vec3>(count + 1)
        val ups = arrayOfNulls<Vec3>(count + 1)
        var prevLat: Vec3? = null
        for (i in 0..count) {
            var lat = CoasterBezierRailFrames.lateralAt(bc, ts[i], level)
            var up = CoasterBezierRailFrames.faceUpAt(bc, ts[i], level)
            if (lat.lengthSqr() < 1.0E-12 || up.lengthSqr() < 1.0E-12) return
            lat = lat.normalize()
            up = up.normalize()
            if (prevLat != null && lat.dot(prevLat!!) < 0.0) {
                lat = lat.scale(-1.0)
                up = up.scale(-1.0)
            }
            lats[i] = lat
            ups[i] = up
            prevLat = lat
        }
        val radii = FloatArray(count + 1) { Mth.lerp(ts[it], r0, r1) }

        val consumer = bufferSource.getBuffer(WaterslideEditorRenderTypes.COLORED_QUADS)
        val start = Math.toRadians(hit.startAngleDegrees.toDouble())
        val end = Math.toRadians(hit.endAngleDegrees.toDouble())

// longitudinal edges
        for (angle in doubleArrayOf(start, end)) {
            var prev = ringPoint(centers[0], lats[0]!!, ups[0]!!, radii[0], angle)
            for (i in 1..count) {
                val curr = ringPoint(centers[i], lats[i]!!, ups[i]!!, radii[i], angle)
                WaterslideEditorRenderTypes.billboardStrip(
                    poseStack, consumer, cameraPos, cameraRotation, prev, curr, 0.05f, r, g, b, 0.9f
                )
                prev = curr
            }
        }
// end arcs
        drawArc(
            poseStack, consumer, cameraPos, cameraRotation,
            centers[0], lats[0]!!, ups[0]!!, radii[0], start, end, r, g, b
        )
        drawArc(
            poseStack, consumer, cameraPos, cameraRotation,
            centers[count], lats[count]!!, ups[count]!!, radii[count], start, end, r, g, b
        )
    }

    private fun drawArc(
        poseStack: PoseStack,
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        cameraPos: Vec3,
        cameraRotation: Matrix4f,
        center: Vec3,
        lat: Vec3,
        up: Vec3,
        radius: Float,
        start: Double,
        end: Double,
        r: Float,
        g: Float,
        b: Float
    ) {
        val steps = max(1, (ARC_SEGMENTS * Math.abs(end - start) / (2.0 * Math.PI)).toInt())
        var prev = ringPoint(center, lat, up, radius, start)
        for (i in 1..steps) {
            val a = start + (end - start) * i / steps
            val curr = ringPoint(center, lat, up, radius, a)
            WaterslideEditorRenderTypes.billboardStrip(
                poseStack, consumer, cameraPos, cameraRotation, prev, curr, 0.05f, r, g, b, 0.9f
            )
            prev = curr
        }
    }

    private fun ringPoint(center: Vec3, lat: Vec3, up: Vec3, radius: Float, rad: Double): Vec3 =
        center
            .add(lat.scale(Math.cos(rad) * radius))
            .add(up.scale(Math.sin(rad) * radius))
}
