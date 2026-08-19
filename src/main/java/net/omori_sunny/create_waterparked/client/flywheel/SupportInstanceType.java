package net.omori_sunny.create_waterparked.client.flywheel;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.IntegerRepr;
import dev.engine_room.flywheel.api.layout.LayoutBuilder;
import dev.engine_room.flywheel.lib.instance.SimpleInstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

public final class SupportInstanceType {
    public static final ResourceLocation VERTEX_SHADER =
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "instance/support.vert");
    public static final ResourceLocation CULL_SHADER =
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "instance/cull/support.glsl");

    public static final InstanceType<SupportInstance> INSTANCE =
        SimpleInstanceType.builder(SupportInstance::new)
            .layout(
                LayoutBuilder.create()
                    .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
                    .vector("overlay", IntegerRepr.SHORT, 2)
                    .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
                    .vector("origin", FloatRepr.FLOAT, 3)
                    .scalar("fullTileMode", FloatRepr.FLOAT)
                    .scalar("spriteU0", FloatRepr.FLOAT)
                    .scalar("spriteU1", FloatRepr.FLOAT)
                    .scalar("spriteV0", FloatRepr.FLOAT)
                    .scalar("spriteV1", FloatRepr.FLOAT)
                    .vector("boundCenter", FloatRepr.FLOAT, 3)
                    .scalar("boundRadius", FloatRepr.FLOAT)
                    .build()
            )
            .writer((ptr, instance) -> {
                MemoryUtil.memPutByte(ptr, instance.red);
                MemoryUtil.memPutByte(ptr + 1L, instance.green);
                MemoryUtil.memPutByte(ptr + 2L, instance.blue);
                MemoryUtil.memPutByte(ptr + 3L, instance.alpha);
                ExtraMemoryOps.put2x16(ptr + 4L, instance.overlay);
                ExtraMemoryOps.put2x16(ptr + 8L, instance.light);
                ExtraMemoryOps.putVector3f(ptr + 12L, instance.origin);
                MemoryUtil.memPutFloat(ptr + 24L, instance.fullTileMode);
                MemoryUtil.memPutFloat(ptr + 28L, instance.spriteU0);
                MemoryUtil.memPutFloat(ptr + 32L, instance.spriteU1);
                MemoryUtil.memPutFloat(ptr + 36L, instance.spriteV0);
                MemoryUtil.memPutFloat(ptr + 40L, instance.spriteV1);
                ExtraMemoryOps.putVector3f(ptr + 44L, instance.boundCenter);
                MemoryUtil.memPutFloat(ptr + 56L, instance.boundRadius);
            })
            .vertexShader(VERTEX_SHADER)
            .cullShader(CULL_SHADER)
            .build();

    private SupportInstanceType() {
    }
}