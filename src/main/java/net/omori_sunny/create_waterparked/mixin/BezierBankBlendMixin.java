package net.omori_sunny.create_waterparked.mixin;

import dev.silvergold.simulatedcoasters.track.BezierBankBlend;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// bank blend for waterslide curves
@Mixin(BezierBankBlend.class)
public abstract class BezierBankBlendMixin {

    @WrapOperation(
        method = "smoothJointBankParameter(FLcom/simibubi/create/content/trains/track/BezierConnection;"
            + "Lnet/minecraft/world/level/Level;)F",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$smooth(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }

    @WrapOperation(
        method = "mixBankBlendInputSignature(Lnet/minecraft/world/level/Level;"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;J)J",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$mix(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }
}
