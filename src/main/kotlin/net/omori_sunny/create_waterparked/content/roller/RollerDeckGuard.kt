package net.omori_sunny.create_waterparked.content.roller

import net.minecraft.world.level.Level
import net.neoforged.neoforge.event.level.BlockEvent

// players cannot break a deck inside a sub-level, whatever game mode they are in
object RollerDeckGuard {

    @JvmStatic
    fun onBreak(event: BlockEvent.BreakEvent) {
        if (event.state.block !is RollerConveyorBlock) return
        if (!RollerConveyorBlock.insideSubLevel(event.level as? Level, event.pos)) return
        event.isCanceled = true
    }
}
