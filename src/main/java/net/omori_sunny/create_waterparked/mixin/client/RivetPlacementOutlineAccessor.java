package net.omori_sunny.create_waterparked.mixin.client;
// exposes the CCS rivet preview rotation for the tube-wall outline adapter

import dev.silvergold.simulatedcoasters.client.item.RivetPlacementOutlineClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RivetPlacementOutlineClient.class)
public interface RivetPlacementOutlineAccessor {

    @Accessor("previewYRotationDegrees")
    static int waterparked$previewYRotationDegrees() {
        throw new AssertionError();
    }
}
