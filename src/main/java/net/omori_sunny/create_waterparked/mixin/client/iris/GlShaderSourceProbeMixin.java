package net.omori_sunny.create_waterparked.mixin.client.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.irisshaders.iris.gl.shader.GlShader;
import net.omori_sunny.create_waterparked.client.compat.IrisColorwheelCompat;
import net.omori_sunny.create_waterparked.client.compat.shaderpack.ShaderpackWaterAdapter;
import net.omori_sunny.create_waterparked.client.compat.shaderpack.ShaderpackWaterAdapters;

// dispatch pack water classification rewrite to the active shaderpack adapter
@Mixin(GlShader.class)
public abstract class GlShaderSourceProbeMixin {

    @ModifyArg(method = "<init>",
        at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/shader/GlShader;createShader(Lnet/irisshaders/iris/gl/shader/ShaderType;Ljava/lang/String;Ljava/lang/String;)I"),
        index = 2, remap = false)
    private static String waterparked$injectWaterClassification(String source) {
        if (source == null) return source;
        if (!IrisColorwheelCompat.waterShadingActive()) return source;
        // cheapest pruning before adapter work
        if (!source.contains("clrwl_vertexEntity")) return source;
        ShaderpackWaterAdapter adapter = ShaderpackWaterAdapters.active();
        if (adapter == null || !adapter.getInjectsWaterMat()) return source;
        String rewritten = adapter.injectWaterMat(source);
        return rewritten == null ? source : rewritten;
    }
}