package net.omori_sunny.create_waterparked.client.flywheel;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.IntegerRepr;
import dev.engine_room.flywheel.api.layout.LayoutBuilder;
import dev.engine_room.flywheel.lib.instance.SimpleInstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

public final class WaterslideTubeInstanceType {
    public static final ResourceLocation VERTEX_SHADER =
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "instance/waterslide_tube.vert");
    public static final ResourceLocation CULL_SHADER =
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "instance/cull/waterslide_tube.glsl");

    public static final InstanceType<WaterslideTubeInstance> INSTANCE =
        SimpleInstanceType.builder(WaterslideTubeInstance::new)
            .layout(
                LayoutBuilder.create()
                    .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
                    .vector("overlay", IntegerRepr.SHORT, 2)
                    .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
                    .vector("prevSpine", FloatRepr.FLOAT, 3)
                    .vector("currSpine", FloatRepr.FLOAT, 3)
                    .vector("prevTangent", FloatRepr.FLOAT, 3)
                    .vector("currTangent", FloatRepr.FLOAT, 3)
                    .vector("prevLateral", FloatRepr.FLOAT, 3)
                    .vector("currLateral", FloatRepr.FLOAT, 3)
                    .scalar("prevRadius", FloatRepr.FLOAT)
                    .scalar("currRadius", FloatRepr.FLOAT)
                    .build()
            )
            .writer((ptr, instance) -> {
                MemoryUtil.memPutByte(ptr, instance.red);
                MemoryUtil.memPutByte(ptr + 1L, instance.green);
                MemoryUtil.memPutByte(ptr + 2L, instance.blue);
                MemoryUtil.memPutByte(ptr + 3L, instance.alpha);
                ExtraMemoryOps.put2x16(ptr + 4L, instance.overlay);
                ExtraMemoryOps.put2x16(ptr + 8L, instance.light);
                ExtraMemoryOps.putVector3f(ptr + 12L, instance.prevSpine);
                ExtraMemoryOps.putVector3f(ptr + 24L, instance.currSpine);
                ExtraMemoryOps.putVector3f(ptr + 36L, instance.prevTangent);
                ExtraMemoryOps.putVector3f(ptr + 48L, instance.currTangent);
                ExtraMemoryOps.putVector3f(ptr + 60L, instance.prevLateral);
                ExtraMemoryOps.putVector3f(ptr + 72L, instance.currLateral);
                MemoryUtil.memPutFloat(ptr + 84L, instance.prevRadius);
                MemoryUtil.memPutFloat(ptr + 88L, instance.currRadius);
            })
            .vertexShader(VERTEX_SHADER)
            .cullShader(CULL_SHADER)
            .build();

    private WaterslideTubeInstanceType() {
    }
}
