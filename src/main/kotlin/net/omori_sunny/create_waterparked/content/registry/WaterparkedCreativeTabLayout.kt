package net.omori_sunny.create_waterparked.content.registry

import dev.silvergold.simulatedcoasters.SimulatedCoasters
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockItem

// shared layout helper for the Waterparked banner in the Simulated Coasters tab
object WaterparkedCreativeTabLayout {

    @JvmStatic
    fun isSimulatedCoastersTab(tab: CreativeModeTab): Boolean =
        tab === SimulatedCoasters.MAIN_CREATIVE_TAB.get()

    @JvmStatic
    fun isWaterparkedItem(stack: ItemStack): Boolean {
        val item = stack.item
        return item === ModItems.WATERSLIDE_TRACK ||
            item === ModItems.WATERSLIDE_ANCHOR ||
            item === ModItems.INFLATABLE_BOAT_1X2 ||
            item is SlideAttachmentBlockItem
    }

    // banner row index, the empty row right before the first Waterparked item
    @JvmStatic
    fun bannerRow(tab: CreativeModeTab): Int {
        val items = tab.displayItems
        var index = 0
        for (stack in items) {
            if (isWaterparkedItem(stack)) {
                return index / 9 - 1
            }
            index++
        }
        return -1
    }
}
