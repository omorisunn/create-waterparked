package net.omori_sunny.create_waterparked.mixin.ponder;

import com.llamalad7.mixinextras.sugar.Local;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.omori_sunny.create_waterparked.ponder.WaterslidePonderRestore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(PonderSceneRegistry.class)
public class WaterslidePonderSceneRegistryMixin {
    @Inject(
        method = "compile(Ljava/util/Collection;)Ljava/util/List;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/createmod/ponder/api/level/PonderLevel;createBackup()V",
            shift = Shift.BEFORE
        )
    )
    private static void create_waterparked$seedWaterslideAnchorsBeforeBackup(
        CallbackInfoReturnable<List<PonderScene>> cir,
        @Local StructureTemplate template,
        @Local PonderLevel ponderLevel
    ) {
        WaterslidePonderRestore.seedFromStructureTemplate(ponderLevel, template);
    }
}
