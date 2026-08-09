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

    fun validate(level: Level, a: BlockPos, b: BlockPos): Result {
        if (a == b) return Result(false, "create.track.second_point")

        val beA = level.getBlockEntity(a) as? WaterslideAnchorBlockEntity
        val beB = level.getBlockEntity(b) as? WaterslideAnchorBlockEntity
        if (beA == null || beB == null) return Result(false, "create.track.original_missing")

        if (CoasterTrackPlacement.anchorConnectionAcrossSubLevels(level, a, b)) {
            return Result(false, "simulatedcoasters.track.anchor_sublevel_connection")
        }
        if (CoasterTrackPlacement.anchorConnectionExceedsMaxSpan(level, a, b)) {
            return Result(false, "create.track.too_far")
        }

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
