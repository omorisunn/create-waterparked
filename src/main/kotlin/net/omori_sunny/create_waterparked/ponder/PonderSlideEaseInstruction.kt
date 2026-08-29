package net.omori_sunny.create_waterparked.ponder

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.track.anchor.AnchorJunctionVisualRefresh
import net.createmod.catnip.data.Couple
import net.createmod.ponder.foundation.PonderScene
import net.createmod.ponder.foundation.instruction.TickingInstruction
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.game.contraption.AnchorPeerCurveDataAccess

// Smooth-eased spline control-point movement for Ponder storyboards (moves BezierConnection starts).
class PonderSlideEaseInstruction(
    private val be: WaterslideAnchorBlockEntity,
    private val dx: Double,
    private val dy: Double,
    private val dz: Double,
    private val ticks: Int,
    private val startTicks: Int
) : TickingInstruction(false, ticks + startTicks) {

    private var basePrimary: BezierConnection? = null
    private var peer: BlockPos? = null
    private var remote: WaterslideAnchorBlockEntity? = null

    override fun firstTick(scene: PonderScene) {
        if (basePrimary != null) return
        for ((p, raw) in be.getAnchorPeerCurvesView()) {
            if (raw == null) continue
            // capture the curve in its canonical (primary) orientation
            basePrimary = if (raw.isPrimary()) raw else raw.secondary()
            peer = p
            remote = be.level?.getBlockEntity(p) as? WaterslideAnchorBlockEntity
            break
        }
    }

    override fun tick(scene: PonderScene) {
        super.tick(scene)
        val src = basePrimary ?: return
        val p = peer ?: return
        val elapsed = totalTicks - remainingTicks
        val active = elapsed - startTicks
        if (active <= 0) return
        val t = (active.toDouble() / ticks.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val ease = t * t * (3.0 - 2.0 * t)
        val shifted = shifted(src, dx * ease, dy * ease, dz * ease)

        store(be, p, shifted)
        remote?.let { store(it, be.blockPos, shifted) }

        // the BER signature holds the spline geometry, so edits invalidate its cache
        if (active >= ticks) {
            val level = be.level ?: return
            AnchorJunctionVisualRefresh.refreshAround(level, be.blockPos, p)
        }
    }

    private fun store(anchor: WaterslideAnchorBlockEntity, key: BlockPos, shiftedPrimary: BezierConnection) {
        val access = anchor as? AnchorPeerCurveDataAccess ?: return
        val map = access.`waterparked$anchorPeerCurves`()
        val existing = map[key.immutable()] ?: return
        // keep the direction/order each anchor already stores under its key
        map[key.immutable()] = if (existing.isPrimary()) shiftedPrimary else shiftedPrimary.secondary()
    }

    private fun shifted(src: BezierConnection, sx: Double, sy: Double, sz: Double): BezierConnection {
        val off = Vec3(sx, sy, sz)
        val out = BezierConnection(
            src.bePositions,
            Couple.create(src.starts.getFirst().add(off), src.starts.getSecond().add(off)),
            src.axes,
            src.normals,
            src.isPrimary(),
            src.hasGirder,
            src.getMaterial()
        )
        if (src.smoothing != null) {
            out.smoothing = src.smoothing?.copy()
        }
        return out
    }
}
