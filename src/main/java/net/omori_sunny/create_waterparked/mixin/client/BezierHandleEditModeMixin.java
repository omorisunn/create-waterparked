package net.omori_sunny.create_waterparked.mixin.client;

import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// no edit UI without curves
@Mixin(BezierHandleEditMode.class)
public abstract class BezierHandleEditModeMixin {

    @Accessor("activeAnchor")
    public static BlockPos getRawActiveAnchor() {
        throw new AssertionError("mixin");
    }

    @Accessor("activeAnchor")
    public static void setRawActiveAnchor(BlockPos pos) {
        throw new AssertionError("mixin");
    }

    @Inject(
        method = "tryActivate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
            + "Lnet/minecraft/core/BlockPos;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$noCurveNoEdit(
        Level level,
        Player player,
        BlockPos anchorPos,
        boolean enforceReach,
        CallbackInfo ci
    ) {
        if (level.getBlockEntity(anchorPos) instanceof WaterslideAnchorBlockEntity be && be.legCount() == 0) {
            ci.cancel();
        }
    }
}
