package net.omori_sunny.create_waterparked.content.waterslide

import com.simibubi.create.AllTags.AllBlockTags
import com.simibubi.create.content.decoration.copycat.CopycatBlock
import com.simibubi.create.content.redstone.RoseQuartzLampBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.shapes.Shapes

// Copycat parity helpers for the anchor-stored support materials. The accepted
// block checks and the property cycling are intentionally identical to Create's
// CopycatBlock.getAcceptedBlockState / CopycatBlockEntity.cycleMaterial, so the
// support beam/bracket behave exactly like placing material on a copycat block.
object WaterslideSupportMaterials {

    @JvmStatic
    fun acceptedBlockState(level: Level, pos: BlockPos, stack: ItemStack, face: Direction?): BlockState? {
        val blockItem = stack.item as? BlockItem ?: return null
        val block = blockItem.block
        if (block is CopycatBlock) return null

        var applied = block.defaultBlockState()
        val hardCodedAllow = isAcceptedRegardless(applied)

        if (!AllBlockTags.COPYCAT_ALLOW.matches(block) && !hardCodedAllow) {
            if (AllBlockTags.COPYCAT_DENY.matches(block)) return null
            if (block is EntityBlock) return null
            if (block is StairBlock) return null

            val shape = applied.getShape(level, pos)
            if (shape.isEmpty || !shape.bounds().equals(Shapes.block().bounds())) return null
            if (applied.getCollisionShape(level, pos).isEmpty) return null
        }

        if (face != null) {
            val axis = face.axis
            if (applied.hasProperty(BlockStateProperties.FACING))
                applied = applied.setValue(BlockStateProperties.FACING, face)
            if (applied.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && axis != Direction.Axis.Y)
                applied = applied.setValue(BlockStateProperties.HORIZONTAL_FACING, face)
            if (applied.hasProperty(BlockStateProperties.AXIS))
                applied = applied.setValue(BlockStateProperties.AXIS, axis)
            if (applied.hasProperty(BlockStateProperties.HORIZONTAL_AXIS) && axis != Direction.Axis.Y)
                applied = applied.setValue(BlockStateProperties.HORIZONTAL_AXIS, axis)
        }

        return applied
    }

    @JvmStatic
    fun cycleMaterial(state: BlockState): BlockState? {
        if (state.hasProperty(TrapDoorBlock.HALF) && state.getOptionalValue(TrapDoorBlock.OPEN).orElse(false))
            return state.cycle(TrapDoorBlock.HALF)
        if (state.hasProperty(BlockStateProperties.FACING))
            return state.cycle(BlockStateProperties.FACING)
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            return state.setValue(
                BlockStateProperties.HORIZONTAL_FACING,
                state.getValue(BlockStateProperties.HORIZONTAL_FACING).clockWise
            )
        if (state.hasProperty(BlockStateProperties.AXIS))
            return state.cycle(BlockStateProperties.AXIS)
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS))
            return state.cycle(BlockStateProperties.HORIZONTAL_AXIS)
        if (state.hasProperty(BlockStateProperties.LIT))
            return state.cycle(BlockStateProperties.LIT)
        if (state.hasProperty(RoseQuartzLampBlock.POWERING))
            return state.cycle(RoseQuartzLampBlock.POWERING)
        return null
    }

    @JvmStatic
    fun isAcceptedRegardless(state: BlockState): Boolean = false
}
