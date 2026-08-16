package net.omori_sunny.create_waterparked.mixin.client;

import dev.silvergold.simulatedcoasters.SimulatedCoasters;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.omori_sunny.create_waterparked.content.registry.WaterparkedCreativeTabLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Draws the Waterparked banner inside the Simulated Coasters creative tab,
// using the same 162x18 banner row layout as the Simulated library sections.
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenWaterparkedBannerMixin {

    private static final ResourceLocation WATERPARKED_BANNER =
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "banner");

    @Shadow
    private static CreativeModeTab selectedTab;

    @Inject(method = "render", at = @At("TAIL"))
    private void waterparked$renderBanner(
        GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci
    ) {
        if (selectedTab != SimulatedCoasters.MAIN_CREATIVE_TAB.get()) return;

        int bannerRow = WaterparkedCreativeTabLayout.bannerRow(selectedTab);
        if (bannerRow < 0) return;

        AbstractContainerScreenAccessor screen = (AbstractContainerScreenAccessor) this;
        int leftPos = screen.create_waterparked$getLeftPos();
        int topPos = screen.create_waterparked$getTopPos();

        int x = leftPos + 8;
        int y = topPos + 17 + bannerRow * 18;
        if (y < topPos || y + 18 > topPos + 166) return;

        guiGraphics.blitSprite(WATERPARKED_BANNER, x, y, 162, 18);

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Component title = Component.translatable("create_waterparked.simulated_section.waterparked");
        int textWidth = font.width(title);
        guiGraphics.fill(x + 2, y + 2, x + 2 + textWidth + 8, y + 16, 0xBB001E3C);
        guiGraphics.drawString(font, title, x + 5, y + 5, 0xFFFFFFFF, true);
    }
}
