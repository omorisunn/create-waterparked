package net.omori_sunny.create_waterparked.content.roller

import dev.ryanhcode.sable.companion.math.Pose3d
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

// the roller run's own sub-level, carrying the run bookkeeping no single block entity can hold
class RollerSubLevel(level: ServerLevel, plotX: Int, plotY: Int, pose: Pose3d) :
    ServerSubLevel(level, plotX, plotY, pose) {

    var controller: BlockPos? = null
        private set
    var pivot: BlockPos? = null
        private set
    var side: RollerHinge.Side = RollerHinge.Side.NONE
        private set
    var facing: Direction? = null
        private set
    var length: Int = 0
        private set
    var index: Int = 0
        private set
    private var cells: List<BlockPos> = emptyList()

    // the run belongs to this sub-level only once its chain has been handed over
    val claimed: Boolean get() = controller != null

    // the chain is the run's plot cells in travel order, the pivot cell included
    val chain: List<BlockPos> get() = cells

    fun setRun(controller: BlockPos, pivot: BlockPos, side: RollerHinge.Side, facing: Direction, index: Int, chain: List<BlockPos>) {
        this.controller = controller
        this.pivot = pivot
        this.side = side
        this.facing = facing
        this.index = index
        this.length = chain.size
        this.cells = chain.toList()
    }

    fun clearRun() {
        controller = null
        pivot = null
        side = RollerHinge.Side.NONE
        facing = null
        length = 0
        index = 0
        cells = emptyList()
    }

    fun carries(pos: BlockPos): Boolean = cells.contains(pos)
}
