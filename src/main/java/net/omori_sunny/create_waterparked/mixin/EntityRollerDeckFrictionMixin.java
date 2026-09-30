package net.omori_sunny.create_waterparked.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// vanilla looks a full half block below the feet, so the friction of our 3/16 deck plate was never queried
@Mixin(Entity.class)
public abstract class EntityRollerDeckFrictionMixin {

    @Inject(method = "getBlockPosBelowThatAffectsMyMovement", at = @At("RETURN"), cancellable = true)
    private void waterparked$deckFriction(CallbackInfoReturnable<BlockPos> cir) {
        Entity self = (Entity) (Object) this;
        Level level = self.level();
        BlockPos deck = cir.getReturnValue().above();
        if (!(level.getBlockState(deck).getBlock() instanceof RollerConveyorBlock))
            return;
        cir.setReturnValue(deck);
    }
}
