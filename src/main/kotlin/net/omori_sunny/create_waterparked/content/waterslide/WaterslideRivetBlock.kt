package net.omori_sunny.create_waterparked.content.waterslide
// extended CCS rivet block for slide tube walls: curve-relative binding
// (T along the curve, angle around it) recomputed against the live curve, so
// radius/shape edits move the rivet along; rendering, drops, wrenching and
// the sub-level placement mechanism all come from the CCS parent classes.

import dev.silvergold.simulatedcoasters.rivet.RivetBlock
import dev.silvergold.simulatedcoasters.SimulatedCoasters
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

class WaterslideRivetBlock(properties: Properties) : RivetBlock(properties) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        WaterslideRivetBlockEntity(pos, state)

    override fun getCloneItemStack(level: net.minecraft.world.level.LevelReader, pos: BlockPos, state: BlockState): ItemStack =
        ItemStack(SimulatedCoasters.RIVET.get())

}
