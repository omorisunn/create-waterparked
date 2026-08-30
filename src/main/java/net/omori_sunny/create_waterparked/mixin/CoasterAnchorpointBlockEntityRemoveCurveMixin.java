package net.omori_sunny.create_waterparked.mixin;
// Mixin: waterslide curve cleanup when an anchor is removed.

import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
        be.removeGhostBlocksForPeer(peer);
        be.resetRadiusIfEmpty();
        if (level.getBlockEntity(peer) instanceof WaterslideAnchorBlockEntity peerBe) {
            peerBe.removeSectorConfig(be.getBlockPos());
            peerBe.removeGhostBlocksForPeer(be.getBlockPos());
            peerBe.resetRadiusIfEmpty();
        }
    }
}