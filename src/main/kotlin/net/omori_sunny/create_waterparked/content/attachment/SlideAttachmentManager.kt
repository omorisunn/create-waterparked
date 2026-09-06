package net.omori_sunny.create_waterparked.content.attachment

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import java.util.concurrent.ConcurrentHashMap

// server-side index of all loaded SAB block entities, keyed by the anchor
// their attachment rides on; rebuilt lazily from BE onLoad/remove events
// (SlideAnchorIndex pattern). Detectors and render queries walk this index.
object SlideAttachmentManager {

    private val byAnchor = ConcurrentHashMap<Long, MutableSet<SlideAttachmentBlockEntity>>()
    // per-tick distance-aware speed demands keyed by rider uuid
    private val pathDemands = ConcurrentHashMap<java.util.UUID, Double>()
    private val all = ConcurrentHashMap.newKeySet<SlideAttachmentBlockEntity>()

    fun register(be: SlideAttachmentBlockEntity) {
        val entry = be.entry ?: return
        if (!all.add(be)) return
        byAnchor.getOrPut(entry.curveA.asLong()) { ConcurrentHashMap.newKeySet() }.add(be)
    }

    fun unregister(be: SlideAttachmentBlockEntity) {
        if (all.remove(be)) {
            byAnchor.values.forEach { it.remove(be) }
        }
    }

    fun allAttachments(): Collection<SlideAttachmentBlockEntity> = all

    fun forAnchor(anchorPos: BlockPos): Collection<SlideAttachmentBlockEntity> =
        byAnchor[anchorPos.asLong()] ?: emptyList()

    // ---- detector + speed control entry points ----

    /** aggregated speed scale a slide session should run at for this rider */
    fun sessionSpeedScale(rider: Entity): Double {
        // consume the demand recorded by this tick's path detectors
        val demanded = pathDemands.remove(rider.uuid) ?: return if (all.isEmpty()) 1.0 else 1.0
        return demanded.coerceIn(0.0, 1.0)
        var scale = 1.0
        for (be in all) {
            if (be.isRemoved || be.level !is ServerLevel) continue
            val attachment = be.attachment() ?: continue
            val demanded = attachment.speedScale(rider) ?: continue
            if (demanded < scale) scale = demanded.coerceIn(0.0, 1.0)
        }
        return scale
    }

    /**
     * per-tick session detector: proximity and path triggers fire while the
     * rider is anywhere near an attachment. worldPos/worldVel = the rider's
     * live session position/velocity.
     */
    fun onSessionTick(level: ServerLevel, rider: Entity, worldPos: Vec3, worldVel: Vec3) {
        if (all.isEmpty()) return
        for (be in all.toList()) {
            if (be.isRemoved || be.level !== level) continue
            val type = be.type() ?: continue
            when (val trigger = type.trigger) {
                is SlideAttachmentTriggerSpec.Path ->
                    pathCheck(level, be, rider, worldPos, worldVel, trigger)
                is SlideAttachmentTriggerSpec.Proximity -> {
                    // proximity scans living entities around the attachment
                    proximityCheck(level, be, trigger)
                }
                SlideAttachmentTriggerSpec.Custom -> {
                    // custom attachments watch on their own from serverTick
                }
            }
        }
    }

    private fun proximityCheck(
        level: ServerLevel,
        be: SlideAttachmentBlockEntity,
        trigger: SlideAttachmentTriggerSpec.Proximity
    ) {
        val entry = be.entry ?: return
        val resolved = SlideAttachmentGeometry.resolve(
            level, entry.curveA, entry.curveB, entry.t, entry.angle, be.blockPos, entry.data
        ) ?: return
        val pos = resolved.context.position
        val box = AABB(pos.subtract(trigger.range, trigger.range, trigger.range),
            pos.add(trigger.range, trigger.range, trigger.range))
        for (entity in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
            if (entity.isSpectator || entity.isRemoved) continue
            if (entity.distanceToSqr(pos) > trigger.range * trigger.range) continue
            be.attachment()?.onTrigger(level, be, entity)
        }
    }

    private fun pathCheck(
        level: ServerLevel,
        be: SlideAttachmentBlockEntity,
        rider: Entity,
        worldPos: Vec3,
        worldVel: Vec3,
        trigger: SlideAttachmentTriggerSpec.Path
    ) {
        val entry = be.entry ?: return
        val resolved = SlideAttachmentGeometry.resolve(
            level, entry.curveA, entry.curveB, entry.t, entry.angle, be.blockPos, entry.data
        ) ?: return
        val curve = resolved.curve
        val ctx = resolved.context

        // project the rider onto the attachment's curve (coarse nearest-t)
        val steps = 48
        var bestT = -1.0
        var bestDist = Double.MAX_VALUE
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val p = curve.getPosition(t)
            val d = p.distanceToSqr(worldPos)
            if (d < bestDist) {
                bestDist = d
                bestT = t
            }
        }
        if (bestDist > 16.0 * 16.0) return

        // arc length between rider and attachment along the curve
        val arc = arcLengthBetween(curve, bestT, entry.t.toDouble())
        if (arc > trigger.distanceBlocks) return

        // no direction gate: the velocity sign destabilises while braking
        // (near-zero speed flips the dot product) and made demands oscillate.
        // A closed door brakes riders from either side purely by distance.
        if (level.gameTime % 20L == 0L) {
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                "[DoorPath] rider={} arc={} t={} doorT={} demand={}",
                rider.uuid, arc.toInt(), bestT, entry.t,
                be.attachment()?.speedScaleAt(arc)
            )
        }

        be.attachment()?.onTrigger(level, be, rider)
        // record the distance-aware braking demand for this rider
        be.attachment()?.speedScaleAt(arc)?.let { scale ->
            pathDemands.merge(rider.uuid, scale.coerceIn(0.0, 1.0)) { a, b -> kotlin.math.min(a, b) }
        }
    }

    /** sampled arc length between two curve parameters */
    private fun arcLengthBetween(
        curve: com.simibubi.create.content.trains.track.BezierConnection,
        t0: Double,
        t1: Double
    ): Double {
        val lo = kotlin.math.min(t0, t1)
        val hi = kotlin.math.max(t0, t1)
        var prev = curve.getPosition(lo)
        var sum = 0.0
        val steps = 16
        for (i in 1..steps) {
            val t = lo + (hi - lo) * i / steps
            val p = curve.getPosition(t)
            sum += p.distanceTo(prev)
            prev = p
        }
        return sum
    }

    /** per-tick validation pass host: drops SABs whose curve disappeared */
    fun onServerTick(event: net.neoforged.neoforge.event.tick.ServerTickEvent.Post) {
        if (all.isEmpty()) return
        for (level in event.server.allLevels) {
            serverTick(level)
        }
    }

    fun serverTick(level: ServerLevel) {
        for (be in all.toList()) {
            if (be.isRemoved || be.level !== level) continue
            val entry = be.entry ?: continue
            val anchor = level.getBlockEntity(entry.curveA) as?
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
            val alive = anchor != null && anchor.anchorPeerCurvesView.containsKey(entry.curveB.immutable())
            if (!alive) {
                // the slide is gone: break the binding block naturally
                level.destroyBlock(be.blockPos, true)
            }
        }
    }
}
