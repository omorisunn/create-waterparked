package net.omori_sunny.create_waterparked.mixin;
// Rivet blocks placed on waterslide tube walls have no CCS host block behind
// them, so the native hostless-rivet disassembly would break them instantly;
// rivets that sit on one of our slide curves are retained instead.

import dev.silvergold.simulatedcoasters.rivet.RivetDisassemblyEnforcer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.omori_sunny.create_waterparked.game.physics.PlayerSlideController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RivetDisassemblyEnforcer.class)
public abstract class RivetDisassemblyExemptMixin {

    @Inject(
        method = "shouldRetainRivetBlockAt",
        at = @At("RETURN"),
        cancellable = true,
        remap = false
    )
    private static void waterparked$keepSlideRivets(
        ServerLevel level, BlockPos pos, Block block,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ()) return;
        if (PlayerSlideController.INSTANCE.isRivetOnSlideWall(level, pos)) {
            cir.setReturnValue(true);
        }
    }
}
