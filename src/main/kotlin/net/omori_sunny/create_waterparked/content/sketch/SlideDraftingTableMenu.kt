package net.omori_sunny.create_waterparked.content.sketch

import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.omori_sunny.create_waterparked.content.registry.ModItems
import net.omori_sunny.create_waterparked.content.registry.ModMenus
import net.omori_sunny.create_waterparked.game.SlideProfile
import net.omori_sunny.create_waterparked.game.SlideSketchData

class SlideDraftingTableMenu(
    containerId: Int,
    inventory: Inventory,
    val table: SlideDraftingTableBlockEntity
) : AbstractContainerMenu(ModMenus.SLIDE_DRAFTING_TABLE, containerId) {

    init {
        addSlot(object : Slot(table, SKETCH_SLOT, SKETCH_SLOT_X, SKETCH_SLOT_Y) {
            override fun mayPlace(stack: ItemStack) = stack.item === ModItems.SLIDE_SKETCH
        })
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(inventory, col + row * 9 + 9, PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(inventory, col, PLAYER_INV_X + col * 18, PLAYER_HOTBAR_Y))
        }
    }

    fun sketchStack(): ItemStack = getSlot(SKETCH_SLOT).item

    fun saveSketch(profile: SlideProfile, name: String) {
        val stack = getSlot(SKETCH_SLOT).item
        if (stack.item !== ModItems.SLIDE_SKETCH) return
        stack.set(
            net.omori_sunny.create_waterparked.content.registry.ModDataComponents.SLIDE_SKETCH,
            SlideSketchData(profile, name)
        )
        setCraftPlayerChanged(table)
        broadcastChanges()
    }

    private fun setCraftPlayerChanged(table: SlideDraftingTableBlockEntity) {
        table.setChanged()
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val copy = stack.copy()
        if (index == SKETCH_SLOT) {
            if (moveItemStackTo(stack, 1, slots.size, false)) {
                slot.onQuickCraft(stack, copy)
            }
        } else {
            if (stack.item === ModItems.SLIDE_SKETCH && !getSlot(SKETCH_SLOT).hasItem()) {
                if (moveItemStackTo(stack, SKETCH_SLOT, SKETCH_SLOT + 1, false)) {
                    slot.onQuickCraft(stack, copy)
                }
            } else {
                return ItemStack.EMPTY
            }
        }
        return if (stack.isEmpty) ItemStack.EMPTY else copy
    }

    override fun stillValid(player: Player): Boolean =
        player.blockPosition().distSqr(table.blockPos) <= 64.0

    companion object {
        const val SKETCH_SLOT = 0
        const val SKETCH_SLOT_X = 132
        const val SKETCH_SLOT_Y = 90
        const val PLAYER_INV_X = 8
        const val INV_TEX_Y = 126 + 4
        const val PLAYER_INV_Y = INV_TEX_Y + 18
        const val PLAYER_HOTBAR_Y = INV_TEX_Y + 76
    }
}
