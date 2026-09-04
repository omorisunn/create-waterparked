package net.omori_sunny.create_waterparked.mixin;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Matrix4d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// authoritative sub-level world bounds for slide riding
@Mixin(SubLevel.class)
public interface SubLevelBoundsAccessor {

    @Accessor("globalBounds")
    BoundingBox3d waterparked$getGlobalBounds();

    @Accessor("globalBoundsTransform")
    Matrix4d waterparked$getGlobalBoundsTransform();
}
