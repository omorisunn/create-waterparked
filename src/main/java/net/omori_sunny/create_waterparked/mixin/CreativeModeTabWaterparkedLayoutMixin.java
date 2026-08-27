package net.omori_sunny.create_waterparked.mixin;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.omori_sunny.create_waterparked.content.registry.WaterparkedCreativeTabLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

// pad SC rows, add a banner row, then place Waterparked items
@Mixin(CreativeModeTab.class)
public abstract class CreativeModeTabWaterparkedLayoutMixin {

    private static final int TAB_COLUMNS = 9;

    @Shadow
    private Collection<ItemStack> displayItems;

    @Inject(method = "buildContents", at = @At("RETURN"))
    private void waterparked$layoutSimulatedCoastersTab(CallbackInfo ci) {
        CreativeModeTab tab = (CreativeModeTab) (Object) this;
        if (!WaterparkedCreativeTabLayout.isSimulatedCoastersTab(tab)) return;

        // split entries and drop empty placeholders for a clean rebuild
        List<ItemStack> waterparked = new ArrayList<>();
        List<ItemStack> coasterItems = new ArrayList<>();
        for (ItemStack stack : displayItems) {
            if (stack.isEmpty()) continue;
            if (WaterparkedCreativeTabLayout.isWaterparkedItem(stack)) {
                waterparked.add(stack);
            } else {
                coasterItems.add(stack);
            }
        }
        if (waterparked.isEmpty()) return;

        int coasters = coasterItems.size();
        int finishLastRow = (TAB_COLUMNS - (coasters % TAB_COLUMNS)) % TAB_COLUMNS;
        int padding = finishLastRow + TAB_COLUMNS; // plus one fully empty banner row

        LinkedList<ItemStack> ordered = new LinkedList<>(coasterItems);
        for (int i = 0; i < padding; i++) ordered.add(ItemStack.EMPTY);
        ordered.addAll(waterparked);
        displayItems = ordered;
    }
}
