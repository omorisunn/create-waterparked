package net.omori_sunny.create_waterparked.mixin.client.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.irisshaders.iris.gl.shader.GlShader;
import net.omori_sunny.create_waterparked.client.compat.IrisColorwheelCompat;
import net.omori_sunny.create_waterparked.client.compat.shaderpack.ShaderpackWaterAdapter;
import net.omori_sunny.create_waterparked.client.compat.shaderpack.ShaderpackWaterAdapters;

/**
 * Colorwheel's transform patcher rewrites shaderpack vertex mains and drops
 * the pack's mc_Entity-based water classification (mat), so the compiled
 * fragment shaders keep their "water = mat in (0.98, 1.02)" logic but mat is
 * forever 0. This mixin dispatches to the active shaderpack adapter
 * (BslWaterAdapter etc.), which re-injects the classification into the vertex
 * source right before Iris compiles it.
 *
 * The mixin itself stays pack-agnostic: any pack that needs source injection
 * declares `injectsWaterMat` on its adapter.
 */
@Mixin(GlShader.class)
public abstract class GlShaderSourceProbeMixin {

    /**
     * Dispatch the water-classification rewrite to the active adapter.
     * createShader(ShaderType, String name, String source): index 2 = source.
     */
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