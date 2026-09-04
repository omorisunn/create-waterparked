package net.omori_sunny.create_waterparked.mixin.client;

import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(DepotBehaviour.class)
public interface DepotBehaviourAccessor {

    @Accessor("heldItem")
    TransportedItemStack waterparked$getHeldItem();

    @Accessor("incoming")
    List<TransportedItemStack> waterparked$getIncoming();
}
