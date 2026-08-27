package net.omori_sunny.create_waterparked.content.registry;

import dev.silvergold.simulatedcoasters.SimulatedCoasters;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.omori_sunny.create_waterparked.content.registry.ModItems;

import java.util.Collection;

// shared layout helper for the Waterparked banner in the Simulated Coasters tab
public class WaterparkedCreativeTabLayout {

    private WaterparkedCreativeTabLayout() {
    }

    public static boolean isSimulatedCoastersTab(CreativeModeTab tab) {
        return tab == SimulatedCoasters.MAIN_CREATIVE_TAB.get();
    }

    public static boolean isWaterparkedItem(ItemStack stack) {
        Item item = stack.getItem();
        return item == ModItems.INSTANCE.getWATERSLIDE_TRACK() ||
            item == ModItems.INSTANCE.getWATERSLIDE_ANCHOR();
    }

    // banner row index, the empty row right before the first Waterparked item
    public static int bannerRow(CreativeModeTab tab) {
        Collection<ItemStack> items = tab.getDisplayItems();
        int index = 0;
        for (ItemStack stack : items) {
            if (isWaterparkedItem(stack)) {
                return index / 9 - 1;
            }
            index++;
        }
        return -1;
    }
}
