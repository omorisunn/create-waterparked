package net.omori_sunny.create_waterparked.content.roller

import com.simibubi.create.content.kinetics.base.KineticBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig

// the run of roller segments a deck is made of, gathered the way its segments face
object RollerDeck {

    private var chainDiag = ""
    private var cutWarn = ""

    // re-gathers the run a segment belongs to, the way that segment itself faces
    @JvmStatic
    fun init(level: Level, pos: BlockPos) {
        val state = level.getBlockState(pos)
        if (state.block !is RollerConveyorBlock) return
        refresh(level, pos, state.getValue(RollerConveyorBlock.HORIZONTAL_FACING), false)
    }

    // a segment that was just placed or turned decides the direction, and the whole run is turned to match
    @JvmStatic
    fun turnRun(level: Level, pos: BlockPos, facing: Direction) {
        refresh(level, pos, facing, true)
    }

    fun segments(level: LevelReader, pos: BlockPos): List<BlockPos> {
        if (!isSegment(level, pos)) return emptyList()
        return segments(level, pos, level.getBlockState(pos).getValue(RollerConveyorBlock.HORIZONTAL_FACING))
    }

    fun segments(level: LevelReader, pos: BlockPos, facing: Direction): List<BlockPos> {
        if (!isSegment(level, pos)) return emptyList()
        var head = pos
        var guard = ModConfig.rollerDeckMaxLength()
        while (guard-- > 0) {
            val candidate = head.relative(facing.opposite)
            if (!level.hasChunkAt(candidate)) return emptyList()
            if (!isSegment(level, candidate)) break
            head = candidate
        }
        val positions = ArrayList<BlockPos>()
        var current = head
        guard = ModConfig.rollerDeckMaxLength()
        while (guard-- > 0) {
            positions.add(current)
            val next = current.relative(facing)
            if (!level.hasChunkAt(next)) return emptyList()
            if (!isSegment(level, next)) break
            current = next
        }
        reportCut(level, pos, facing, current)
        return positions
    }

    fun controllerOf(level: LevelReader, pos: BlockPos): BlockPos? =
        (level.getBlockEntity(pos) as? RollerConveyorBlockEntity)?.controllerPosition()

    // a segment carries a shaft where the run ends, so a lone block carries a pulley on both faces
    fun isEnd(level: LevelReader, pos: BlockPos, facing: Direction): Boolean =
        !isSegment(level, pos.relative(facing)) || !isSegment(level, pos.relative(facing.opposite))

    // a hinged run turns about its shaft, so its travel direction is fixed until the shaft comes back out
    fun isHinged(level: LevelReader, pos: BlockPos): Boolean {
        val candidates = ArrayList<BlockPos>(5)
        candidates.add(pos)
        for (side in Direction.Plane.HORIZONTAL) candidates.add(pos.relative(side))
        for (candidate in candidates) {
            val segment = level.getBlockEntity(candidate) as? RollerConveyorBlockEntity ?: continue
            val controllerPos = segment.controllerPosition() ?: candidate
            val controller = level.getBlockEntity(controllerPos) as? RollerConveyorBlockEntity ?: continue
            if (controller.hingeSide != RollerHinge.Side.NONE) return true
        }
        return false
    }

    fun isSegment(level: LevelReader, pos: BlockPos): Boolean =
        level.hasChunkAt(pos) && level.getBlockState(pos).block is RollerConveyorBlock

    // one run travels one way: every member gets its controller, its place, its length, and that direction
    private fun refresh(level: Level, pos: BlockPos, facing: Direction, alignRun: Boolean) {
        if (level.isClientSide) return
        val positions = segments(level, pos, facing)
        reportChain(level, pos, facing, positions)
        if (positions.isEmpty()) return
        val controllerPos = positions.first()
        val facingBefore = HashMap<BlockPos, Direction>()
        val controllersBefore = ArrayList<BlockPos>()
        for (member in positions) {
            facingBefore[member] = level.getBlockState(member).getValue(RollerConveyorBlock.HORIZONTAL_FACING)
            val segment = level.getBlockEntity(member) as? RollerConveyorBlockEntity ?: continue
            val controller = segment.controllerPosition() ?: continue
            if (controller !in controllersBefore) controllersBefore.add(controller)
        }
        for ((chainIndex, member) in positions.withIndex()) {
            val segment = level.getBlockEntity(member) as? RollerConveyorBlockEntity ?: continue
            segment.setDeck(controllerPos, chainIndex, positions.size)
        }
        if (alignRun) for (member in positions) align(level, member, facing)
        val controller = level.getBlockEntity(controllerPos) as? RollerConveyorBlockEntity ?: return
        for (old in controllersBefore) {
            if (old == controllerPos || old !in positions) continue
            val oldSegment = level.getBlockEntity(old) as? RollerConveyorBlockEntity ?: continue
            if (oldSegment === controller) continue
            val released = oldSegment.releaseInventory() ?: continue
            // a run turned end for end mirrors its loads about its old front edge, any other rebuild shifts them
            if (facingBefore[old] == facing.opposite) released.mirrorAbout(oldSegment.index + 1)
            else released.shiftBy(oldSegment.index)
            val target = controller.inventory ?: continue
            released.mergeInto(target)
        }
    }

    // a run longer than the deck cap is cut, and that is never silent
    private fun reportCut(level: LevelReader, pos: BlockPos, facing: Direction, tail: BlockPos) {
        if (!isSegment(level, tail.relative(facing))) return
        val limit = ModConfig.rollerDeckMaxLength()
        val line = "$pos|$tail|$limit"
        if (line == cutWarn) return
        cutWarn = line
        CreateWaterparked.LOGGER.warn(
            "[roller chain] run at {} is longer than deckMaxLength {} and was cut at {}",
            pos, limit, tail
        )
    }

    // temporary: where a run's own walk stopped, so a short count names the cell that ended it
    private fun reportChain(level: Level, pos: BlockPos, facing: Direction, positions: List<BlockPos>) {
        if (level.isClientSide) return
        val tail = positions.lastOrNull() ?: pos
        val next = tail.relative(facing)
        val line = "${positions.size}|${next}|${level.hasChunkAt(next)}|${isSegment(level, next)}"
        if (line == chainDiag) return
        chainDiag = line
        CreateWaterparked.LOGGER.debug(
            "[roller chain] from={} facing={} count={} tail={} next={} chunk={} deck={}",
            pos, facing, positions.size, tail, next, level.hasChunkAt(next), isSegment(level, next)
        )
    }

    // one run travels one way, so every member is turned to the direction its controller keeps
    private fun align(level: Level, pos: BlockPos, facing: Direction) {
        val state = level.getBlockState(pos)
        if (state.getValue(RollerConveyorBlock.HORIZONTAL_FACING) == facing) return
        KineticBlockEntity.switchToBlockState(level, pos, state.setValue(RollerConveyorBlock.HORIZONTAL_FACING, facing))
    }

}
