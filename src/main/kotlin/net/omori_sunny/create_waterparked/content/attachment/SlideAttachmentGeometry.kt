package net.omori_sunny.create_waterparked.content.attachment

import com.simibubi.create.content.trains.track.BezierConnection
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import kotlin.math.cos
import kotlin.math.sin

// resolves an attachment's (site, t, angle) against the LIVE curve every
// call: radius and curve edits move the attachment along automatically and
// nothing ever stores a stale world position.
//
// slides inside Sable sub-levels live in plot space; client callers pass a
// transform pair (point, direction) -> render space so render positions come
// out in world space.
object SlideAttachmentGeometry {

    class Resolved(
        val anchor: WaterslideAnchorBlockEntity,
        val curve: BezierConnection,
        val context: SlideAttachmentModelContext
    )

    fun resolve(
        level: Level,
        anchorPos: BlockPos,
        peerPos: BlockPos,
        t: Float,
        angle: Float,
        sabPos: BlockPos,
        data: CompoundTag = CompoundTag(),
        renderTransform: SlideAttachmentRenderTransform? = null
    ): Resolved? {
        val anchor = level.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity ?: return null
        val raw = anchor.anchorPeerCurvesView[peerPos.immutable()] ?: return null
        val curve = if (raw.isPrimary) raw else raw.secondary() ?: return null
        val ctx = contextAt(level, sabPos, curve, t, angle, data, renderTransform)
        return Resolved(anchor, curve, ctx)
    }

    /** model context for an explicit curve + (t, angle) */
    fun contextAt(
        level: Level,
        sabPos: BlockPos,
        curve: BezierConnection,
        t: Float,
        angle: Float,
        data: CompoundTag = CompoundTag(),
        renderTransform: SlideAttachmentRenderTransform? = null
    ): SlideAttachmentModelContext {
        var center = curve.getPosition(t.toDouble())
        var tangent = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
            .unitTangentAt(curve, t)
        if (renderTransform != null) {
            center = renderTransform.point(center)
            tangent = renderTransform.direction(tangent).normalize()
        } else {
            tangent = tangent.normalize()
        }
        val (lateral, up) = SlideCurveGeometry.stableFrame(tangent)
        val a = curve.bePositions.getFirst()
        val b = curve.bePositions.getSecond()
        val radius = net.minecraft.util.Mth.lerp(
            t,
            SlideCurveGeometry.radiusAt(level, a),
            SlideCurveGeometry.radiusAt(level, b)
        )
        val rad = Math.toRadians(angle.toDouble())
        val radialOut = lateral.scale(cos(rad)).add(up.scale(sin(rad)))
        val wall = ModConfig.wallThickness() - 0.1f
        val position = center.add(radialOut.scale((radius + wall).toDouble()))
        return SlideAttachmentModelContext(
            level, sabPos, position, tangent, lateral, up,
            radialOut, radius, ModConfig.wallThickness(), data
        )
    }

    /** world-space hull of a local attachment-space box under the frame */
    fun worldBounds(ctx: SlideAttachmentModelContext, local: AABB): AABB {
        val (lat, up, tan) = basis(ctx)
        val corners = listOf(
            Vec3(local.minX, local.minY, local.minZ),
            Vec3(local.maxX, local.minY, local.minZ),
            Vec3(local.minX, local.maxY, local.minZ),
            Vec3(local.maxX, local.maxY, local.minZ),
            Vec3(local.minX, local.minY, local.maxZ),
            Vec3(local.maxX, local.minY, local.maxZ),
            Vec3(local.minX, local.maxY, local.maxZ),
            Vec3(local.maxX, local.maxY, local.maxZ)
        )
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (c in corners) {
            val w = ctx.position
                .add(lat.scale(c.x))
                .add(up.scale(c.y))
                .add(tan.scale(c.z))
            if (w.x < minX) minX = w.x
            if (w.y < minY) minY = w.y
            if (w.z < minZ) minZ = w.z
            if (w.x > maxX) maxX = w.x
            if (w.y > maxY) maxY = w.y
            if (w.z > maxZ) maxZ = w.z
        }
        return AABB(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /** right-handed basis: cross(lateral, up) aligned with the tangent */
    fun basis(ctx: SlideAttachmentModelContext): Triple<Vec3, Vec3, Vec3> {
        val lat = ctx.lateral.normalize()
        var up = ctx.up.normalize()
        val tan = ctx.tangent.normalize()
        if (lat.cross(up).dot(tan) < 0) up = up.scale(-1.0)
        return Triple(lat, up, tan)
    }
}
