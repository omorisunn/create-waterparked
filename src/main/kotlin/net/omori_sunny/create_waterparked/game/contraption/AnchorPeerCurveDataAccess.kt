package net.omori_sunny.create_waterparked.game.contraption

import com.simibubi.create.content.trains.track.BezierConnection
import net.minecraft.core.BlockPos

// read and write access to the inherited peer curve maps
interface AnchorPeerCurveDataAccess {
    fun `waterparked$anchorPeerCurves`(): MutableMap<BlockPos, BezierConnection>
    fun `waterparked$railRgb`(): MutableMap<BlockPos, Int>
    fun `waterparked$beamRgb`(): MutableMap<BlockPos, Int>
}
