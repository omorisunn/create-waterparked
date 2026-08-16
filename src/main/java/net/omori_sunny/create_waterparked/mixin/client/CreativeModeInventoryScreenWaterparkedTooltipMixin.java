package net.omori_sunny.create_waterparked.mixin.client;

import dev.silvergold.simulatedcoasters.SimulatedCoasters;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.omori_sunny.create_waterparked.content.registry.WaterparkedCreativeTabLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

// Mirrors the Simulated library's section tooltip: for Waterparked items it
// injects the blue section title right under the item name and removes the
// vanilla "Simulated Coasters" tab-name line (the item is displayed in that
// tab, but its identity should read Waterparked).
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenWaterparkedTooltipMixin {

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true)
    private void waterparked$sectionTooltip(ItemStack stack, CallbackInfoReturnable<List<Component>> cir) {
        if (!WaterparkedCreativeTabLayout.isWaterparkedItem(stack)) return;

        List<Component> tooltip = cir.getReturnValue();
        if (tooltip == null) return;

        List<Component> modified = new ArrayList<>(tooltip.size() + 1);
        modified.addAll(tooltip);

        String scTabName = scTabName();
        modified.removeIf(line ->
            scTabName != null && line.getString().equals(scTabName)
        );

        Component section = Component
            .translatable("create_waterparked.simulated_section.waterparked")
            .copy()
            .withStyle(ChatFormatting.BLUE);
        modified.add(Math.min(1, modified.size()), section);
        cir.setReturnValue(modified);
    }

    private static String scTabName() {
        CreativeModeTab tab = SimulatedCoasters.MAIN_CREATIVE_TAB.get();
        return tab == null ? null : tab.getDisplayName().getString();
    }
}
