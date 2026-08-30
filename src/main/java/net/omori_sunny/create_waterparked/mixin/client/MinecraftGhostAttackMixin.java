package net.omori_sunny.create_waterparked.mixin.client;
// Mixin: synthetic ghost cell hit when Vanilla starts an attack.

import net.omori_sunny.create_waterparked.client.editor.WaterslideGhostPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftGhostAttackMixin {

    @Inject(method = "startAttack", at = @At("HEAD"))
    private void waterparked$ghostHitSource(CallbackInfoReturnable<Boolean> cir) {
        try {
            Minecraft mc = Minecraft.getInstance();
            BlockHitResult hit = WaterslideGhostPlacement.fakeGhostHit(mc);
            if (hit != null) {
                mc.hitResult = hit;
            }
        } catch (Throwable t) {
            net.omori_sunny.create_waterparked.CreateWaterparked.INSTANCE.getLOGGER().warn(
                "[WaterslideGhostHit] startAttack hook failed", t
            );
        }
    }
}