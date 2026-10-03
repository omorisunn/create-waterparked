package net.omori_sunny.create_waterparked.mixin.client;

import dev.silvergold.simulatedcoasters.client.track.CoasterWrenchCurveAnchorOutlineClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// the curve-entry outline client gates on a wrench; the plier answers too
@Mixin(CoasterWrenchCurveAnchorOutlineClient.class)
public abstract class CoasterWrenchCurveAnchorOutlineClientMixin {

    @Inject(method = "isHoldingWrench", at = @At("HEAD"), cancellable = true)
    private static void pliers$holdingPlier(LocalPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (player == null) return;
        if (net.omori_sunny.create_waterparked.client.editor.pliers.BrassPliersEditor
                .isPlierStack(player.getMainHandItem()) ||
            net.omori_sunny.create_waterparked.client.editor.pliers.BrassPliersEditor
                .isPlierStack(player.getOffhandItem())) {
            cir.setReturnValue(true);
        }
    }
}
