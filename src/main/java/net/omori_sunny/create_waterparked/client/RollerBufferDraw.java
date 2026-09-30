package net.omori_sunny.create_waterparked.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.createmod.catnip.render.SuperByteBuffer;

// kotlin cannot chain the self typed builder of a SuperByteBuffer, so the plain renderer fallback draws through
// this one line of java instead
public final class RollerBufferDraw {

    private RollerBufferDraw() {
    }

    public static void draw(SuperByteBuffer buffer, PoseStack ms, VertexConsumer consumer, int light) {
        buffer.light(light).renderInto(ms, consumer);
    }
}
