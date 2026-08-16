package net.omori_sunny.create_waterparked.content.registry;

import dev.silvergold.simulatedcoasters.SimulatedCoasters;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.omori_sunny.create_waterparked.content.registry.ModItems;

import java.util.Collection;

// Shared layout helper for the Waterparked banner in the Simulated Coasters
// creative tab. The tab keeps SC's own items first, then one empty banner row,
// then the Waterparked items; both the build-contents mixin and the banner
// renderer derive their positions from this helper so later item additions to
// the SC tab are picked up automatically.
public final class WaterparkedCreativeTabLayout {

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

    // 0-based creative-inventory row that should show the banner, or -1 when
    // no Waterparked items are present. The banner row is the empty row
    // immediately before the first Waterparked item.
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
