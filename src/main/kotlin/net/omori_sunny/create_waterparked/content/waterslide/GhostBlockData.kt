package net.omori_sunny.create_waterparked.content.waterslide
// Ghost block entry record (cell, t/angle, held stack).

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState

data class GhostBlockEntry(
    val id: Int,
    val cell: BlockPos,
    val state: BlockState,
    val stack: ItemStack,
    val t: Float,
    val angle: Float
) {
    fun write(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.putInt("Id", id)
        tag.putLong("Cell", cell.asLong())
        tag.put("State", NbtUtils.writeBlockState(state))
        tag.put("Item", stack.saveOptional(registries))
        tag.putFloat("T", t)
        tag.putFloat("Angle", angle)
    }

    companion object {
        fun read(tag: CompoundTag, state: BlockState, registries: HolderLookup.Provider): GhostBlockEntry? {
            if (state.isAir) return null
            var stack = ItemStack.parseOptional(registries, tag.getCompound("Item"))
            if (stack.isEmpty) stack = ItemStack(state.block)
            return GhostBlockEntry(
                id = tag.getInt("Id"),
                cell = BlockPos.of(tag.getLong("Cell")),
                state = state,
                stack = stack,
                t = if (tag.contains("T", 5)) tag.getFloat("T") else 0f,
                angle = if (tag.contains("Angle", 5)) tag.getFloat("Angle") else 0f
            )
        }

        fun of(id: Int, cell: BlockPos, stack: ItemStack, t: Float, angle: Float): GhostBlockEntry? {
            val blockItem = stack.item as? BlockItem ?: return null
            val state = blockItem.block.defaultBlockState()
            if (state.isAir) return null
            return GhostBlockEntry(id, cell.immutable(), state, stack.copyWithCount(1), t, angle)
        }
    }
}