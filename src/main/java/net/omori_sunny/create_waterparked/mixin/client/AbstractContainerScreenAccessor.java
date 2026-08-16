package net.omori_sunny.create_waterparked.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("leftPos")
    int create_waterparked$getLeftPos();

    @Accessor("topPos")
    int create_waterparked$getTopPos();
}
