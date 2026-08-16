package net.omori_sunny.create_waterparked.game

import dev.silvergold.simulatedcoasters.track.CoasterAnchorBezierOptimizer
import dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlock
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

// Anchor connection validation.
object WaterslideConnectionRules {

    data class Result(val valid: Boolean, val messageKey: String? = null)

    // Cheap checks; do these before touching block entities / building preview geometry.
    // anchorConnectionExceedsMaxSpan only looks up block entities when the raw distance is
    // inside the allowed span, so acrossSubLevels short-circuits the far-coordinate case.
    fun acrossSubLevels(level: Level, a: BlockPos, b: BlockPos): Boolean =
        CoasterTrackPlacement.anchorConnectionAcrossSubLevels(level, a, b)

    fun shouldSkipPreview(level: Level, a: BlockPos, b: BlockPos): Boolean =
        acrossSubLevels(level, a, b) || CoasterTrackPlacement.anchorConnectionExceedsMaxSpan(level, a, b)

    fun validate(level: Level, a: BlockPos, b: BlockPos): Result {
        if (a == b) return Result(false, "create.track.second_point")

        // These run before any block-entity lookup: querying a far plot-global sub-level
        // anchor from the main world can force-load chunks and hang the client.
        if (CoasterTrackPlacement.anchorConnectionAcrossSubLevels(level, a, b)) {
            return Result(false, "create_waterparked.connect.cross_sublevel")
        }
        if (CoasterTrackPlacement.anchorConnectionExceedsMaxSpan(level, a, b)) {
            return Result(false, "create.track.too_far")
        }

        val beA = level.getBlockEntity(a) as? WaterslideAnchorBlockEntity
        val beB = level.getBlockEntity(b) as? WaterslideAnchorBlockEntity
        if (beA == null || beB == null) return Result(false, "create.track.original_missing")

        val peersA = beA.viewAnchorPeerCurvesSnapshot()
        val peersB = beB.viewAnchorPeerCurvesSnapshot()
        if (peersA.containsKey(b) || peersB.containsKey(a)) {
            return Result(false, "create_waterparked.connect.already_connected")
        }
        if (!beA.canAcceptAnchorPeer(b) || !beB.canAcceptAnchorPeer(a)) {
            if (!level.isClientSide) {
                CreateWaterparked.LOGGER.info(
                    "Slide connect rejected (anchor full): a={} legs={} b={} legs={}",
                    a, beA.legCount(), b, beB.legCount()
                )
            }
            return Result(false, "create_waterparked.connect.cannot_accept")
        }

        val stateA = level.getBlockState(a)
        val stateB = level.getBlockState(b)
        if (CoasterAnchorpointBlock.haveOppositeFacing(stateA, stateB)) {
            return Result(false, "simulatedcoasters.track.anchor_opposite_facing")
        }
        if (CoasterAnchorpointBlock.haveSameFacingCollinearAlongFacing(stateA, stateB, a, b)) {
            return Result(false, "simulatedcoasters.track.anchor_same_facing_collinear")
        }
        if (CoasterAnchorpointBlock.connectionHasImpossibleBend(stateA, stateB, a, b)) {
            return Result(false, "simulatedcoasters.track.anchor_impossible_bend")
        }
        if (CoasterAnchorpointBlock.connectionReversesOnSameFacingCoplanarPlane(level, a, b)) {
            return Result(false, "simulatedcoasters.track.anchor_coplanar_plane_reversal")
        }

        val bc = CoasterAnchorBezierOptimizer.buildAnchorAnchorBezier(
            level, a, b, WaterslideTrackMaterials.WATERSLIDE, false
        )
        if (bc == null || !CoasterAnchorBezierOptimizer.isBuiltPlacementCurveValid(bc)) {
            return Result(false, "create.track.too_sharp")
        }
        if (CoasterAnchorpointBlock.connectionBelowMinLegAngle(level, a, b, bc)) {
            return Result(false, "simulatedcoasters.track.anchor_leg_angle_too_sharp")
        }

        return Result(true)
    }
}
