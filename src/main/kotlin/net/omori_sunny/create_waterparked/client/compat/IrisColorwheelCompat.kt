package net.omori_sunny.create_waterparked.client.compat

import net.neoforged.fml.ModList

// Iris + Colorwheel (ShaderPacks) compatibility detection.
//
// Deliberately dependency-light so this mod works WITHOUT Iris/Colorwheel:
//  - presence of the mods is checked through NeoForge's ModList (no classes
//    from Iris referenced), and
//  - "are shaders actually in use right now" is reflected over Iris's public
//    compat API (net.irisshaders.iris.api.v0.IrisApi#isShaderPackInUse), never
//    compiled against.
//
// When all three hold (Iris installed + Colorwheel installed + shader pack
// active with a colorwheel-compatible pack), the water inside our tubes and the
// thrown stream get the shaderpack's water shading automatically: Colorwheel is
// the Flywheel 1.0 -> Iris backend, so every translucent Flywheel water
// material (TUBE_TRANSLUCENT / WATER_TRANSLUCENT) is already drawn through the
// pack's clrwl_gbuffers_translucent program. This object only reports the
// state so other code can act on it.
object IrisColorwheelCompat {

    @JvmStatic
    fun irisPresent(): Boolean = runCatching { ModList.get().isLoaded("iris") }.getOrDefault(false)

    @JvmStatic
    fun colorwheelPresent(): Boolean = runCatching { ModList.get().isLoaded("colorwheel") }.getOrDefault(false)

    // Reflected call into Iris's public compat API; returns false when Iris is
    // absent so nothing on the Iris classpath is ever touched.
    @JvmStatic
    fun shadersInUse(): Boolean {
        if (!irisPresent()) return false
        return try {
            val apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
            val instance = apiClass.getMethod("getInstance").invoke(null)
            instance.javaClass.getMethod("isShaderPackInUse").invoke(instance) as Boolean
        } catch (t: Throwable) {
            false
        }
    }

    /** True when the full shader-waters stack is active for mounted slides. */
    @JvmStatic
    fun waterShadingActive(): Boolean =
        irisPresent() && colorwheelPresent() && shadersInUse()

    // Active shaderpack name, read from Iris's config so no Iris class is ever
    // referenced at compile time.
    @JvmStatic
    fun shaderpackName(): String? = try {
        val f = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config/iris.properties")
        java.nio.file.Files.readAllLines(f)
            .firstOrNull { it.startsWith("shaderPack=") }
            ?.substringAfter('=')
    } catch (t: Throwable) {
        null
    }

    /**
     * The per-vertex entity id we stamp onto water meshes. Shaderpacks read it
     * as mc_Entity and classify water from it, but each pack family uses its
     * own id convention:
     *  - BSL's clrwl translucent program: int(mc_Entity.x / 100) == 200,
     *    i.e. the legacy 20000-range id.
     *  - Complementary-family packs: mat = int(mc_Entity.x + 0.5), water is
     *    mat in [32000, 32004).
     *  - Photon: material_mask = mc_Entity.x - 10000, water is
     *    material_mask == 1 -> mc_Entity.x == 10001.
     * Default to 32000 (the Iris-ecosystem convention).
     */
    @JvmStatic
    fun waterStampId(): Int {
        val pack = shaderpackName() ?: return 32000
        return when {
            pack.contains("BSL", ignoreCase = true) -> 20000
            pack.contains("Complementary", ignoreCase = true) -> 32000
            pack.contains("photon", ignoreCase = true) -> 10001
            else -> 32000
        }
    }
}