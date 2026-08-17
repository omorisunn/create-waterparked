package net.omori_sunny.create_waterparked.game.water

import com.simibubi.create.content.contraptions.AbstractContraptionEntity
import com.simibubi.create.content.trains.track.BezierConnection
import com.simibubi.create.content.contraptions.Contraption
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.omori_sunny.create_waterparked.game.physics.ContraptionSlideSpaceAccess
import net.omori_sunny.create_waterparked.game.physics.ContraptionSlideSpaces
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.sqrt

// One-time, contraption-internal water-physics computation.
//
// When a contraption carrying a waterslide is assembled, the water-particle
// field for ITS OWN tubes is computed exactly once (this is what feeds the
// running-water / thrown-water visuals on the move). The slides mounted on a
// contraption deliberately do NOT take part in the per-tick water simulation
// of other carriers (main-world / Sable sub-levels): ServerWaterSimulation
// only ever visits Main + SubLevel spaces, and everything contraption-side
// lives here.
//
// Water source = the (auto-watered) captured anchors; autonomous water: the
// field is static and water is never consumed, so recomputation happens only
// when the captured slide data changes.
object ContraptionWaterSimulation {

    private const val WATER_LAUNCH = 2.0 // initial block/s at the source mouth
    private const val GRAVITY = 32.0

    private data class Cached(
        val sig: Int,
        val fields: Map<Pair<Long, Long>, ServerWaterSimulation.CurveField>
    )

    // contraption entity id -> cached field (kept in the parent object's cache
    // so Sessions and the render path share one copy)
    private val cache = HashMap<Int, Cached>()

    fun edgeKey(a: BlockPos, b: BlockPos): Pair<Long, Long> =
        if (a.asLong() <= b.asLong()) a.asLong() to b.asLong() else b.asLong() to a.asLong()

    fun invalidateAll() { cache.clear() }

    fun invalidate(entityId: Int) { cache.remove(entityId) }

    // /waterparked refresh: recompute + resync every loaded contraption slide.
    fun refresh(level: ServerLevel) {
        invalidateAll()
        for (id in ContraptionSlideSpaces.carriersIn(level)) {
            val cp = level.getEntity(id) as? AbstractContraptionEntity ?: continue
            fieldsFor(level, cp)
            syncToPlayers(level, cp)
        }
    }

    // Send the once-computed contraption water field to every player in the
    // dimension so the client can render flowing water / the thrown stream.
    fun syncToPlayers(level: ServerLevel, entity: AbstractContraptionEntity) {
        if (level.isClientSide) return
        val fields = fieldsFor(level, entity)
        if (fields.isEmpty()) return
        val entries = fields.map { (edge, f) ->
            net.omori_sunny.create_waterparked.network.WaterslideWaterSyncPayload.Entry(
                edge.first, edge.second,
                f.segments.map { net.omori_sunny.create_waterparked.network.WaterslideWaterSyncPayload.Segment(it.arc, it.speed) },
                f.exit?.pos, f.exit?.vel
            )
        }
        val payload = net.omori_sunny.create_waterparked.network.WaterslideWaterSyncPayload(entries, null, entity.id)
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersInDimension(level, payload)
        CreateWaterparked.LOGGER.debug("[ContraptionWater] synced {} field(s) for contraption {}", entries.size, entity.id)
    }

    fun fieldsFor(level: ServerLevel, entity: AbstractContraptionEntity): Map<Pair<Long, Long>, ServerWaterSimulation.CurveField> {
        if (level.isClientSide) return emptyMap()
        val contraption = entity.contraption ?: return emptyMap()
        if (!ContraptionSlideSpaces.carriesSlides(entity)) return emptyMap()
        val sig = dataSignature(contraption)
        val cached = cache[entity.id]
        if (cached != null && cached.sig == sig) return cached.fields
        val fields = compute(level, entity)
        cache[entity.id] = Cached(sig, fields)
        if (fields.isEmpty()) {
            val watered = ContraptionSlideSpaces.decode(entity).values.count { it.hasWater() }
            CreateWaterparked.LOGGER.debug(
                "[ContraptionWater] entity={} computed EMPTY field (wateredAnchors={}) - no water to flow",
                entity.id, watered
            )
        } else {
            CreateWaterparked.LOGGER.debug(
                "[ContraptionWater] entity={} computed {} field(s)",
                entity.id, fields.size
            )
        }
        return fields
    }

    private fun dataSignature(contraption: Contraption): Int {
        var key = 0
        for (info in contraption.blocks.values) {
            val nbt = info.nbt() ?: continue
            key = key * 31 + nbt.hashCode()
        }
        return key
    }

    // Water flows from every watered anchor down its curves; since water is
    // not consumed the whole tube becomes wet and the far (open) mouth throws.
    private fun compute(level: ServerLevel, entity: AbstractContraptionEntity): Map<Pair<Long, Long>, ServerWaterSimulation.CurveField> {
        val out = HashMap<Pair<Long, Long>, ServerWaterSimulation.CurveField>()
        try {
            val access = ContraptionSlideSpaceAccess(level, entity)
            for ((_, be) in ContraptionSlideSpaces.decode(entity)) {
                if (!be.hasWater()) continue
                val radius = be.radius
                for (raw in be.anchorPeerCurvesView.values) {
                    val bc = if (raw.isPrimary) raw else raw.secondary()
                    if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                    val a = bc.bePositions.getFirst()
                    val b = bc.bePositions.getSecond()
                    val edge = edgeKey(a, b)
                    if (out.containsKey(edge)) continue
                    out[edge] = flow(access, bc, radius, radius)
                }
            }
        } catch (t: Throwable) {
            CreateWaterparked.LOGGER.warn("[ContraptionWater] compute failed for entity {}: {}", entity.id, t.toString())
        }
        return out
    }

    // Ballistic water along one curve in contraption-local space: the whole
    // tube is watered from the source mouth onward and the far open end throws.
    private fun flow(
        access: ContraptionSlideSpaceAccess,
        bc: BezierConnection,
        r0: Float,
        r1: Float
    ): ServerWaterSimulation.CurveField {
        val frames = SlideCurveGeometry.sampleFrames(access, bc, r0, r1, includeExtensions = true)
        if (frames.size < 2) return ServerWaterSimulation.CurveField(emptyList(), null)

        // downward direction in contraption-local space: -localGravity, else world-down
        val grav = access.localGravity()
        val downLocal = if (grav.lengthSqr() > 1.0E-12) grav.scale(-1.0).normalize()
        else Vec3(0.0, 1.0, 0.0)

        val segments = ArrayList<ServerWaterSimulation.WaterSegment>()
        var speed = WATER_LAUNCH
        var arc = 0f
        for (i in 0 until frames.size - 1) {
            val fa = frames[i]
            val fb = frames[i + 1]
            val len = fa.center.distanceTo(fb.center)
            if (len < 1.0E-6) continue
            // gain from falling along the downward component of travel
            val along = (fb.center.subtract(fa.center)).normalize()
            val fall = max(0.0, along.dot(downLocal))
            speed = sqrt(max(1.0E-6, speed * speed + 2.0 * GRAVITY * fall * len))
            arc += len.toFloat()
            segments += ServerWaterSimulation.WaterSegment(arc, speed.toFloat())
        }

        // exit throw at the far mouth (extension frames included)
        val lastFrame = frames.last()
        val exitPos = lastFrame.center
        val exitVel = lastFrame.tangent.scale(speed)
        val exit = ServerWaterSimulation.ExitInfo(exitPos, exitVel)
        return ServerWaterSimulation.CurveField(segments, exit)
    }
}