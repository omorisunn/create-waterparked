package net.omori_sunny.create_waterparked.mixin.client;

import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.client.track.CoasterCurveDyeOutlineClient;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.DyeItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// let our sector outline replace CCS dye preview on slides
@Mixin(CoasterCurveDyeOutlineClient.class)
public abstract class CoasterCurveDyeOutlineClientMixin {

    @Inject(
        method = "resolvePreviewHostedPrimary(Lnet/minecraft/client/Minecraft;)"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void waterslide$noDyeOutline(
        Minecraft mc,
        CallbackInfoReturnable<BezierConnection> cir
    ) {
        if (cir.getReturnValue() == null) return;
        if (!WaterslideTrackMaterials.isWaterslide(cir.getReturnValue())) return;
        if (mc.player == null) return;
        boolean holdingDye = mc.player.getMainHandItem().getItem() instanceof DyeItem ||
            mc.player.getOffhandItem().getItem() instanceof DyeItem;
        if (holdingDye) {
            cir.setReturnValue(null);
        }
    }
}
