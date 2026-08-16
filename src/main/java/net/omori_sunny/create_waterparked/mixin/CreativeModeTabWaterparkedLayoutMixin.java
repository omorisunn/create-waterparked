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

// Lays the Simulated Coasters creative tab out like the Simulated library's
// sectioned tab: all non-Waterparked (SC) items first, empty slots to finish
// their last row, one fully empty banner row, then the Waterparked items.
// The amount of padding is derived from the live SC item count, so future SC
// items are adapted to automatically.
@Mixin(CreativeModeTab.class)
public abstract class CreativeModeTabWaterparkedLayoutMixin {

    @Shadow
    private Collection<ItemStack> displayItems;

    @Inject(method = "buildContents", at = @At("RETURN"))
    private void waterparked$layoutSimulatedCoastersTab(CallbackInfo ci) {
        CreativeModeTab tab = (CreativeModeTab) (Object) this;
        if (!WaterparkedCreativeTabLayout.isSimulatedCoastersTab(tab)) return;

        // Split live entries, dropping any EMPTY placeholders from a previous
        // build so this layout is idempotent.
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
        int finishLastRow = (9 - (coasters % 9)) % 9;
        int padding = finishLastRow + 9; // + one fully empty banner row

        LinkedList<ItemStack> ordered = new LinkedList<>(coasterItems);
        for (int i = 0; i < padding; i++) ordered.add(ItemStack.EMPTY);
        ordered.addAll(waterparked);
        displayItems = ordered;
    }
}
