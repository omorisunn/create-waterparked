package net.omori_sunny.create_waterparked.ponder

import net.createmod.ponder.foundation.PonderScene
import net.createmod.ponder.foundation.instruction.TickingInstruction
import net.minecraft.core.BlockPos
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlockEntity

// client only; occupies exactly the given scene ticks
class PonderRollerLoadInstruction(
    private val controller: BlockPos,
    ticks: Int
) : TickingInstruction(true, ticks.coerceAtLeast(1)) {

    private var speed = 0f

    override fun firstTick(scene: PonderScene) {
        super.firstTick(scene)
        val be = scene.world.getBlockEntity(controller) as? RollerConveyorBlockEntity ?: return
        speed = be.runDeckSpeed()
    }

    override fun tick(scene: PonderScene) {
        super.tick(scene)
        val be = scene.world.getBlockEntity(controller) as? RollerConveyorBlockEntity ?: return
        val loads = be.inventory ?: return
        val length = be.deckLength.toFloat()
        for (transported in loads.transportedItems) {
            // one belt step per scene tick, both endpoints written so the renderer's own lerp stays smooth; a
            // load that reaches an end waits there, which is exactly what a client copy does with it
            transported.prevBeltPosition = transported.beltPosition
            transported.beltPosition = (transported.beltPosition + speed).coerceIn(0f, length)
        }
    }
}
