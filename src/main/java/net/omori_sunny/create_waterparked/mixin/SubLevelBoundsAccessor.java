package net.omori_sunny.create_waterparked.mixin;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Matrix4d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// authoritative frame data for sub-level slide riding: globalBounds is the
// content bounds in WORLD space and globalBoundsTransform is the physics
// body -> world matrix - exactly the frame RigidBodyHandle.teleport uses
@Mixin(SubLevel.class)
public interface SubLevelBoundsAccessor {

    @Accessor("globalBounds")
    BoundingBox3d waterparked$getGlobalBounds();

    @Accessor("globalBoundsTransform")
    Matrix4d waterparked$getGlobalBoundsTransform();
}
