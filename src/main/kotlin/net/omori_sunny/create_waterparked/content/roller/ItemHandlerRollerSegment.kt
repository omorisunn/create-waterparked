package net.omori_sunny.create_waterparked.content.roller

import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.items.IItemHandler

// one slot per deck segment, filled at the place the segment's index sits
class ItemHandlerRollerSegment(
    private val inventory: RollerDeckInventory,
    private val offset: Int
) : IItemHandler {

    override fun getSlots(): Int = 1

    override fun getStackInSlot(slot: Int): ItemStack =
        inventory.getStackAtOffset(offset)?.stack ?: ItemStack.EMPTY

    // a taken in stack leaves the caller an empty remainder, so the entity that carried it is discarded
    override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
        if (stack.isEmpty) return ItemStack.EMPTY
        if (!inventory.canInsertAt(offset)) return stack
        val count = minOf(stack.count, stack.maxStackSize)
        val toInsert = stack.copyWithCount(count)
        val leftover = if (stack.count > count) stack.copyWithCount(stack.count - count) else ItemStack.EMPTY
        if (simulate) return leftover
        if (!inventory.insertOn(RollerDeckFace.UPPER, offset, toInsert, Direction.UP).isEmpty) return stack
        return leftover
    }

    override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
        val transported = inventory.getStackAtOffset(offset) ?: return ItemStack.EMPTY
        val taken = minOf(amount, transported.stack.count)
        val extracted = if (simulate) transported.stack.copy().split(taken) else transported.stack.split(taken)
        if (simulate) return extracted
        if (transported.stack.isEmpty) inventory.removeItem(transported) else inventory.deck.notifyUpdate()
        return extracted
    }

    override fun getSlotLimit(slot: Int): Int =
        getStackInSlot(slot).getOrDefault(DataComponents.MAX_STACK_SIZE, 64)

    override fun isItemValid(slot: Int, stack: ItemStack): Boolean = true
}
