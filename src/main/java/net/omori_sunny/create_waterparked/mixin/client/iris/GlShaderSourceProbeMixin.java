package net.omori_sunny.create_waterparked.mixin.client.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.irisshaders.iris.gl.shader.GlShader;

/**
 * Colorwheel's transform patcher rewrites shaderpack vertex mains and drops
 * the pack's mc_Entity-based water classification (mat), so the compiled
 * fragment shaders keep their "water = mat in (0.98, 1.02)" logic but mat is
 * forever 0. This mixin re-injects the classification into the vertex source
 * right before Iris compiles it, deriving mat from the per-vertex entity
 * attribute (clrwl_vertexEntity, which we stamp with the water id in
 * ColorwheelWaterEntityMixin).
 *
 * BSL's clrwl translucent program classifies water as:
 *   int blockID = int(mc_Entity.x / 100); if (blockID == 200) mat = 1.0;
 * i.e. it expects the legacy 20000-range id.
 */
@Mixin(GlShader.class)
public abstract class GlShaderSourceProbeMixin {

    /**
     * Re-inject the water classification into clrwl vertex shaders.
     * createShader(ShaderType, String name, String source): index 2 = source.
     */
    @ModifyArg(method = "<init>",
        at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/shader/GlShader;createShader(Lnet/irisshaders/iris/gl/shader/ShaderType;Ljava/lang/String;Ljava/lang/String;)I"),
        index = 2, remap = false)
    private static String waterparked$injectWaterClassification(String source) {
        if (source == null) return source;
        // vertex shaders only (fragment shaders read mat as an input; the
        // pack's own water branches already consume it)
        if (!source.contains("gl_Position")) return source;
        if (!source.contains("clrwl_vertexEntity")) return source;
        if (!source.contains("void main")) return source;
        if (source.contains("WATERPARKED water classification")) return source;
        // If the pack computes mat itself (e.g. Complementary's
        // "mat = int(mc_Entity.x + 0.5)"), injecting our classification would
        // conflict with its declaration and clobber its own values. Only
        // packs whose mat is left unassigned by the Colorwheel transform
        // (BSL-style) need the injection: declared but never assigned.
        if (source.contains("mat =")) return source;
        if (!source.matches("(?s).*\\bfloat\\s+mat\\b.*")) return source;

        int mainIdx = source.indexOf("void main");
        int lastBrace = source.lastIndexOf('}');
        if (mainIdx < 0 || lastBrace < 0 || lastBrace <= mainIdx) return source;

        boolean hasMatDecl = source.matches("(?s).*float\\s+mat\\b.*");
        java.util.regex.Matcher vm = java.util.regex.Pattern.compile("#version\\s+(\\d+)").matcher(source);
        boolean coreProfile = vm.find() && Integer.parseInt(vm.group(1)) >= 130;
        String decl = "";
        if (!hasMatDecl) {
            decl = coreProfile ? "out float mat;\n" : "varying float mat;\n";
        }
        // float-only comparison (no int() cast): compiles on every GLSL
        // version, including legacy #version 110 packs.
        String code = "\t// WATERPARKED water classification\n"
            + "\tmat = 0.0;\n"
            + "\tif (clrwl_vertexEntity.x > 19950.0 && clrwl_vertexEntity.x < 20050.0) mat = 1.0;\n";
        return source.substring(0, mainIdx) + decl
            + source.substring(mainIdx, lastBrace) + code
            + source.substring(lastBrace);
    }
}
