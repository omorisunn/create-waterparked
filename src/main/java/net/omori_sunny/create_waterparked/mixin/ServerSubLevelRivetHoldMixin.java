package net.omori_sunny.create_waterparked.mixin;
// placing a block inside a rivet's plot re-merges the sub-level mass data,
// which jolts the physics body off the wall; re-apply the wall frame in the
// same call so the jolt never becomes visible

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideRivetSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerSubLevel.class)
public abstract class ServerSubLevelRivetHoldMixin {

    @Inject(method = "updateMergedMassData", at = @At("RETURN"), remap = false)
    private void waterparked$holdRivetAfterMassUpdate(float mass, CallbackInfo ci) {
        ServerSubLevel self = (ServerSubLevel) (Object) this;
        if (self.isRemoved()) return;
        if (!WaterslideRivetSpawner.isRivetSub(self)) return;
        if (!(self.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        WaterslideRivetSpawner.hold(level, self);
    }
}
