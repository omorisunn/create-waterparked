package net.omori_sunny.create_waterparked.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.omori_sunny.create_waterparked.content.roller.RollerSubLevelScope;

// only a roller run assembly claims our sub-level type, every other allocation keeps the stock one
@Mixin(ServerSubLevelContainer.class)
public abstract class ServerSubLevelContainerRollerMixin {

    @Inject(method = "createSubLevel", at = @At("HEAD"), cancellable = true, remap = false)
    private void waterparked$rollerSubLevel(int globalPlotX, int globalPlotZ, Pose3d pose, UUID uuid, CallbackInfoReturnable<SubLevel> cir) {
        if (!RollerSubLevelScope.active()) return;
        cir.setReturnValue(RollerSubLevelScope.create((ServerSubLevelContainer) (Object) this, globalPlotX, globalPlotZ, pose, uuid));
    }
}
