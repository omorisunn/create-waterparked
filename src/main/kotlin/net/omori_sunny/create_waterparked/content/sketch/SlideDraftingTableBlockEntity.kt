package net.omori_sunny.create_waterparked.content.sketch

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.omori_sunny.create_waterparked.content.registry.ModItems

class SlideDraftingTableBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) :
    BlockEntity(type, pos, state), MenuProvider, net.minecraft.world.Container {

    val sketchSlot = arrayOf(ItemStack.EMPTY)

    override fun createMenu(containerId: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        SlideDraftingTableMenu(containerId, inventory, this)

    override fun getDisplayName(): Component =
        Component.translatable("block.create_waterparked.slide_drafting_table")

    override fun getContainerSize() = 1
    override fun isEmpty() = sketchSlot[0].isEmpty
    override fun getItem(slot: Int): ItemStack = if (slot == 0) sketchSlot[0] else ItemStack.EMPTY
    override fun removeItem(slot: Int, amount: Int): ItemStack {
        if (slot != 0 || sketchSlot[0].isEmpty) return ItemStack.EMPTY
        val taken = sketchSlot[0].split(amount)
        if (sketchSlot[0].isEmpty) sketchSlot[0] = ItemStack.EMPTY
        setChanged()
        return taken
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        if (slot != 0) return ItemStack.EMPTY
        val out = sketchSlot[0]
        sketchSlot[0] = ItemStack.EMPTY
        return out
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot != 0) return
        sketchSlot[0] = stack
        setChanged()
    }

    override fun setChanged() {
        super.setChanged()
        if (level != null && !level!!.isClientSide) {
            level!!.sendBlockUpdated(worldPosition, blockState, blockState, 3)
        }
    }

    override fun stillValid(player: Player): Boolean =
        player.blockPosition().distSqr(blockPos) <= 64.0

    override fun clearContent() {
        sketchSlot[0] = ItemStack.EMPTY
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean =
        slot == 0 && stack.item === ModItems.SLIDE_SKETCH

    override fun getMaxStackSize(): Int = 1

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        if (!sketchSlot[0].isEmpty) {
            tag.put("Sketch", sketchSlot[0].save(registries))
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        sketchSlot[0] = if (tag.contains("Sketch", 10))
            ItemStack.parse(registries, tag.getCompound("Sketch")).orElse(ItemStack.EMPTY)
        else ItemStack.EMPTY
    }

    companion object {
        val DUMMY_DATA = object : ContainerData {
            override fun get(index: Int) = 0
            override fun set(index: Int, value: Int) {}
            override fun getCount() = 0
        }
    }
}
