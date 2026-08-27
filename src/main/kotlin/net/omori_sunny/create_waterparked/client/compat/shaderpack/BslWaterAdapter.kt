package net.omori_sunny.create_waterparked.client.compat.shaderpack

/**
 * BSL (with Colorwheel's clrwl translucent program). Water is classified as
 * int(mc_Entity.x / 100) == 200, i.e. the legacy 20000-range id, so we stamp
 * 20000.
 *
 * Colorwheel's transform patcher rewrites shaderpack vertex mains and drops
 * the pack's mc_Entity-based water classification, so the compiled fragment
 * shaders keep their "water = mat in (0.98, 1.02)" logic but mat is forever
 * 0. [injectWaterMat] re-injects the classification into the clrwl vertex
 * source right before Iris compiles it, deriving mat from the per-vertex
 * entity attribute (which ColorwheelWaterEntityMixin stamps).
 */
object BslWaterAdapter : ShaderpackWaterAdapter {

    override val waterStampId: Int = 20000

    override val injectsWaterMat: Boolean = true

    override fun matches(packName: String): Boolean =
        packName.contains("BSL", ignoreCase = true)

    // re inject water classification into a clrwl vertex shader
    override fun injectWaterMat(source: String): String? {
        // vertex shaders only
        if (!source.contains("gl_Position")) return null
        if (!source.contains("clrwl_vertexEntity")) return null
        if (!source.contains("void main")) return null
        if (source.contains("WATERPARKED water classification")) return null
        // skip packs that compute mat themselves, injection would clash
        if (source.contains("mat =")) return null
        if (!source.matches(Regex("(?s).*\\bfloat\\s+mat\\b.*"))) return null

        val mainIdx = source.indexOf("void main")
        val lastBrace = source.lastIndexOf('}')
        if (mainIdx < 0 || lastBrace < 0 || lastBrace <= mainIdx) return null

        val hasMatDecl = source.matches(Regex("(?s).*float\\s+mat\\b.*"))
        val vm = Regex("#version\\s+(\\d+)").find(source)
        val coreProfile = vm != null && vm.groupValues[1].toIntOrNull()?.let { it >= 130 } == true
        val decl = if (hasMatDecl) "" else if (coreProfile) "out float mat;\n" else "varying float mat;\n"
        // float only comparison, compiles on every GLSL version
        val code = "\t// WATERPARKED water classification\n" +
            "\tmat = 0.0;\n" +
            "\tif (clrwl_vertexEntity.x > 19950.0 && clrwl_vertexEntity.x < 20050.0) mat = 1.0;\n"
        return source.substring(0, mainIdx) + decl +
            source.substring(mainIdx, lastBrace) + code +
            source.substring(lastBrace)
    }
}