package net.omori_sunny.create_waterparked.content.attachment

import net.minecraft.world.phys.Vec3

// plot-space -> render-space mapping for slides inside Sable sub-levels;
// null = the slide lives directly in the world (no transform needed)
class SlideAttachmentRenderTransform(
    val point: (Vec3) -> Vec3,
    val direction: (Vec3) -> Vec3
)
