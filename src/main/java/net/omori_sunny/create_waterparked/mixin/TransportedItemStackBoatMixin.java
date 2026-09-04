package net.omori_sunny.create_waterparked.mixin;

import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import net.minecraft.world.item.ItemStack;
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// belt re-syncs rebuild every TransportedItemStack; the constructor's random
// angle re-derives the side offset target, which made the boat visibly slide
// sideways and twitch on every sync. Boats get a deterministic centred state
// instead - the belt renderer mixin supplies the travel-direction yaw.
@Mixin(TransportedItemStack.class)
public abstract class TransportedItemStackBoatMixin {

    @Inject(method = "<init>(Lnet/minecraft/world/item/ItemStack;)V", at = @At("TAIL"))
    private void waterparked$boatDeterministic(ItemStack stack, CallbackInfo ci) {
        if (stack.getItem() instanceof InflatableBoat1x2Item) {
            TransportedItemStack self = (TransportedItemStack) (Object) this;
            self.angle = 0;
            self.sideOffset = 0;
            self.prevSideOffset = 0;
        }
    }
}
