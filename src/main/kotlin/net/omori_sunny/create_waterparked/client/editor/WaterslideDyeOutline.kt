package net.omori_sunny.create_waterparked.client.editor

import com.mojang.blaze3d.vertex.PoseStack
import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.client.track.CoasterAnchorClientSpace
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.CoasterOpenEndExtension
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.level.Level
import net.minecraft.util.Mth
import net.minecraft.world.item.AxeItem
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
        val dye = (player.mainHandItem.item as? DyeItem) ?: (player.offhandItem.item as? DyeItem)
        val axeDelete = player.mainHandItem.item is AxeItem && player.isShiftKeyDown
        if (dye == null && !axeDelete) return
        val hit = WaterslideSectorEdit.sectorUnderCursor(mc) ?: return
        if (dye != null) {
            val blockId = hit.blockId ?: return
            if (WaterslideDyeRules.dyedBlockFor(blockId, dye.dyeColor) == null) return
        }

        val bc = hit.curve
        val spaceAnchor = bc.bePositions.getFirst()
        val ctx = SableClientEdit.resolve(level, spaceAnchor)
        val sub = ctx?.sub
        val worldScale = sub?.let {
            val sc = it.logicalPose().scale()
            maxOf(sc.x(), sc.y(), sc.z()).toFloat().coerceAtLeast(0.1f)
        } ?: 1f
        fun worldPos(v: Vec3): Vec3 =
            if (sub == null) v else CoasterAnchorClientSpace.toRenderWorld(level, spaceAnchor, v)
        fun worldDir(v: Vec3): Vec3 =
            if (sub == null) v else CoasterAnchorClientSpace.toRenderDirection(level, spaceAnchor, v)
        val r0 = (SableClientEdit.resolve(level, bc.bePositions.getFirst())?.be?.radius
            ?: net.omori_sunny.create_waterparked.config.ModConfig.defaultSlideRadius())
        val r1 = (SableClientEdit.resolve(level, bc.bePositions.getSecond())?.be?.radius
            ?: net.omori_sunny.create_waterparked.config.ModConfig.defaultSlideRadius())
        val rgb = dye?.dyeColor?.getTextureDiffuseColor() ?: 0xFF3030
        val r = ((rgb shr 16) and 255) / 255f
        val g = ((rgb shr 8) and 255) / 255f
        val b = (rgb and 255) / 255f

// sample the whole tube
        val count = bc.getSegmentCount().coerceAtLeast(1)
        val ts = FloatArray(count + 1) { i ->
            if (i == 0) 0f else if (i == count) 1f else bc.getSegmentT(i)
        }
        val centers = Array(count + 1) { worldPos(bc.getPosition(ts[it].toDouble())) }
        val lats = arrayOfNulls<Vec3>(count + 1)
        val ups = arrayOfNulls<Vec3>(count + 1)
        var prevLat: Vec3? = null
        for (i in 0..count) {
            var lat = worldDir(CoasterBezierRailFrames.lateralAt(bc, ts[i], level))
            var up = worldDir(CoasterBezierRailFrames.faceUpAt(bc, ts[i], level))
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
        val radii = FloatArray(count + 1) { Mth.lerp(ts[it], r0, r1) * worldScale }

// include the open-end extensions
        val ext0 = openEndExtension(level, bc, atFirst = true)
        val ext1 = openEndExtension(level, bc, atFirst = false)
        val pointCount = count + 1 + (if (ext0 > 0.01f) 1 else 0) + (if (ext1 > 0.01f) 1 else 0)
        val c = arrayOfNulls<Vec3>(pointCount)
        val la = arrayOfNulls<Vec3>(pointCount)
        val u = arrayOfNulls<Vec3>(pointCount)
        val ra = FloatArray(pointCount)
        var idx = 0
        if (ext0 > 0.01f) {
            c[idx] = worldPos(bc.getPosition(0.0).subtract(
                CoasterBezierRailFrames.unitTangentAt(bc, 0f).scale(ext0.toDouble())
            ))
            la[idx] = lats[0]!!
            u[idx] = ups[0]!!
            ra[idx] = radii[0]
            idx++
        }
        for (i in 0..count) {
            c[idx] = centers[i]
            la[idx] = lats[i]!!
            u[idx] = ups[i]!!
            ra[idx] = radii[i]
            idx++
        }
        if (ext1 > 0.01f) {
            c[idx] = worldPos(bc.getPosition(1.0).add(
                CoasterBezierRailFrames.unitTangentAt(bc, 1f).scale(ext1.toDouble())
            ))
            la[idx] = lats[count]!!
            u[idx] = ups[count]!!
            ra[idx] = radii[count]
        }

        val consumer = bufferSource.getBuffer(WaterslideEditorRenderTypes.COLORED_QUADS)
        val start = Math.toRadians(hit.startAngleDegrees.toDouble())
        val end = Math.toRadians(hit.endAngleDegrees.toDouble())

// longitudinal edges
        for (angle in doubleArrayOf(start, end)) {
            var prev = ringPoint(c[0]!!, la[0]!!, u[0]!!, ra[0], angle)
            for (i in 1 until pointCount) {
                val curr = ringPoint(c[i]!!, la[i]!!, u[i]!!, ra[i], angle)
                WaterslideEditorRenderTypes.billboardStrip(
                    poseStack, consumer, cameraPos, cameraRotation, prev, curr, 0.05f, r, g, b, 0.9f
                )
                prev = curr
            }
        }
// end arcs
        drawArc(
            poseStack, consumer, cameraPos, cameraRotation,
            c[0]!!, la[0]!!, u[0]!!, ra[0], start, end, r, g, b
        )
        drawArc(
            poseStack, consumer, cameraPos, cameraRotation,
            c[pointCount - 1]!!, la[pointCount - 1]!!, u[pointCount - 1]!!,
            ra[pointCount - 1], start, end, r, g, b
        )
    }

    private fun openEndExtension(level: Level, bc: BezierConnection, atFirst: Boolean): Float {
        val anchor = if (atFirst) bc.bePositions.getFirst() else bc.bePositions.getSecond()
        val be = level.getBlockEntity(anchor) as? CoasterAnchorpointBlockEntity ?: return 0f
        if (be.legCount() != 1) return 0f
        return CoasterOpenEndExtension.extensionBlocks(level, anchor)
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
