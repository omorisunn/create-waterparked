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
                    .scalar("wallThickness", FloatRepr.FLOAT)
                    .scalar("flowStart", FloatRepr.FLOAT)
                    .scalar("flowEnd", FloatRepr.FLOAT)
                    .scalar("phaseStart", FloatRepr.FLOAT)
                    .scalar("phaseEnd", FloatRepr.FLOAT)
                    .scalar("arcBase", FloatRepr.FLOAT)
                    .scalar("flowSign", FloatRepr.FLOAT)
                    .scalar("mirror", FloatRepr.FLOAT)
                    .scalar("flowUpstream", FloatRepr.FLOAT)
                    .scalar("phaseUpstream", FloatRepr.FLOAT)
                    .scalar("downstreamMix", FloatRepr.FLOAT)
                    .scalar("jitterScale", FloatRepr.FLOAT)
                    .scalar("jitterFrequency", FloatRepr.FLOAT)
                    .scalar("jitterTimeScale", FloatRepr.FLOAT)
                    .scalar("jitterTime", FloatRepr.FLOAT)
                    .scalar("tailFadeStart", FloatRepr.FLOAT)
                    .scalar("tailFadeEnd", FloatRepr.FLOAT)
                    .scalar("waterTileSpan", FloatRepr.FLOAT)
                    .scalar("spriteU0", FloatRepr.FLOAT)
                    .scalar("spriteU1", FloatRepr.FLOAT)
                    .scalar("spriteV0", FloatRepr.FLOAT)
                    .scalar("spriteV1", FloatRepr.FLOAT)
                    .scalar("isWater", FloatRepr.FLOAT)
                    .scalar("waterAtlasUV", FloatRepr.FLOAT)
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
                MemoryUtil.memPutFloat(ptr + 92L, instance.wallThickness);
                MemoryUtil.memPutFloat(ptr + 96L, instance.flowStart);
                MemoryUtil.memPutFloat(ptr + 100L, instance.flowEnd);
                MemoryUtil.memPutFloat(ptr + 104L, instance.phaseStart);
                MemoryUtil.memPutFloat(ptr + 108L, instance.phaseEnd);
                MemoryUtil.memPutFloat(ptr + 112L, instance.arcBase);
                MemoryUtil.memPutFloat(ptr + 116L, instance.flowSign);
                MemoryUtil.memPutFloat(ptr + 120L, instance.mirror);
                MemoryUtil.memPutFloat(ptr + 124L, instance.flowUpstream);
                MemoryUtil.memPutFloat(ptr + 128L, instance.phaseUpstream);
                MemoryUtil.memPutFloat(ptr + 132L, instance.downstreamMix);
                MemoryUtil.memPutFloat(ptr + 136L, instance.jitterScale);
                MemoryUtil.memPutFloat(ptr + 140L, instance.jitterFrequency);
                MemoryUtil.memPutFloat(ptr + 144L, instance.jitterTimeScale);
                MemoryUtil.memPutFloat(ptr + 148L, instance.jitterTime);
                MemoryUtil.memPutFloat(ptr + 152L, instance.tailFadeStart);
                MemoryUtil.memPutFloat(ptr + 156L, instance.tailFadeEnd);
                MemoryUtil.memPutFloat(ptr + 160L, instance.waterTileSpan);
                MemoryUtil.memPutFloat(ptr + 164L, instance.spriteU0);
                MemoryUtil.memPutFloat(ptr + 168L, instance.spriteU1);
                MemoryUtil.memPutFloat(ptr + 172L, instance.spriteV0);
                MemoryUtil.memPutFloat(ptr + 176L, instance.spriteV1);
                MemoryUtil.memPutFloat(ptr + 180L, instance.isWater);
                MemoryUtil.memPutFloat(ptr + 184L, instance.waterAtlasUV);
            })
            .vertexShader(VERTEX_SHADER)
            .cullShader(CULL_SHADER)
            .build();

    private WaterslideTubeInstanceType() {
    }
}
