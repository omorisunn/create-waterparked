package net.omori_sunny.create_waterparked.content.attachment

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import java.util.concurrent.ConcurrentHashMap

// server-side index of loaded attachments, keyed by the anchor they ride on
object SlideAttachmentManager {

    private val byAnchor = ConcurrentHashMap<Long, MutableSet<SlideAttachmentBlockEntity>>()
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

    fun sessionSpeedScale(rider: Entity): Double {
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

    // worldPos and worldVel are the live session values, not the entity's
    fun onSessionTick(level: ServerLevel, rider: Entity, worldPos: Vec3, worldVel: Vec3) {
        if (all.isEmpty()) return
        for (be in all.toList()) {
            if (be.isRemoved || be.level !== level) continue
            val type = be.type() ?: continue
            when (val trigger = type.trigger) {
                is SlideAttachmentTriggerSpec.Path ->
                    pathCheck(level, be, rider, worldPos, worldVel, trigger)
                is SlideAttachmentTriggerSpec.Proximity -> {
                    proximityCheck(level, be, trigger)
                }
                SlideAttachmentTriggerSpec.Custom -> {
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

        val arc = arcLengthBetween(curve, bestT, entry.t.toDouble())
        if (arc > trigger.distanceBlocks) return

        be.attachment()?.onTrigger(level, be, rider)
        be.attachment()?.let { att ->
            if (att is net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorAttachment) {
                att.riderSide = if (bestT < entry.t) -1 else 1
            }
        }
        be.attachment()?.speedScaleAt(arc)?.let { scale ->
            pathDemands.merge(rider.uuid, scale.coerceIn(0.0, 1.0)) { a, b -> kotlin.math.min(a, b) }
        }
    }

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

    // per-tick validation pass: attachments whose curve is gone are dropped
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
                level.destroyBlock(be.blockPos, true)
            }
        }
    }
}
