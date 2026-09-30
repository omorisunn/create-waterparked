package net.omori_sunny.create_waterparked.content.roller

import net.minecraft.core.Direction
import kotlin.math.abs

// the deck's two surfaces and the band a rider counts as riding in, both measured from the deck's own base
private const val UPPER_SURFACE = 3.0 / 16.0
private const val LOWER_SURFACE = UPPER_SURFACE - RollerDeckInventory.LOWER_LANE_DROP
private const val RIDE_BAND = 0.5

// the deck's two faces are one lane mirrored through it, so every path below reads this instead of branching
enum class RollerDeckFace(
    val lane: Int,
    val side: Direction,
    val surface: Double,
    val lift: Double,
    val flip: Float,
    val drop: Double
) {
    UPPER(RollerDeckInventory.UPPER_LANE, Direction.UP, UPPER_SURFACE, 1.0, 0f, 0.0),
    LOWER(RollerDeckInventory.LOWER_LANE, Direction.DOWN, LOWER_SURFACE, -1.0, 180f, RollerDeckInventory.LOWER_LANE_DROP);

    companion object {
        const val BAND = RIDE_BAND

        fun of(side: Direction): RollerDeckFace = if (side == LOWER.side) LOWER else UPPER

        fun ofLane(lane: Int): RollerDeckFace = if (lane == LOWER.lane) LOWER else UPPER

        // the face whose surface the given height above the deck base sits nearest
        fun at(height: Double): RollerDeckFace =
            if (abs(height - UPPER.surface) <= abs(height - LOWER.surface)) UPPER else LOWER
    }
}
