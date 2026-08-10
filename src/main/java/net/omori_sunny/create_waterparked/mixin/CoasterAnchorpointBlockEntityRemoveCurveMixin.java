package net.omori_sunny.create_waterparked.mixin;

import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// drop sector data with the removed curve
@Mixin(CoasterAnchorpointBlockEntity.class)
public abstract class CoasterAnchorpointBlockEntityRemoveCurveMixin {

    @Inject(
        method = "removeAnchorPeerCurve(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/core/BlockPos;)V",
        at = @At("RETURN")
    )
    private void waterslide$clearSectorData(ServerLevel level, BlockPos peer, CallbackInfo ci) {
        if (!((Object) this instanceof WaterslideAnchorBlockEntity be)) return;
        be.removeSectorConfig(peer);
        be.resetRadiusIfEmpty();
        if (level.getBlockEntity(peer) instanceof WaterslideAnchorBlockEntity peerBe) {
            peerBe.removeSectorConfig(be.getBlockPos());
            peerBe.resetRadiusIfEmpty();
        }
    }
}
