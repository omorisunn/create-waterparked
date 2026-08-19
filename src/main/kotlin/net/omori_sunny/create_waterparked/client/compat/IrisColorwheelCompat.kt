package net.omori_sunny.create_waterparked.client.compat

import net.neoforged.fml.ModList
import net.omori_sunny.create_waterparked.client.compat.shaderpack.IterationWaterAdapter
import net.omori_sunny.create_waterparked.client.compat.shaderpack.ShaderpackWaterAdapters
import net.omori_sunny.create_waterparked.config.ModClientConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

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
        ModClientConfig.shaderWaterCompat() &&
            irisPresent() && colorwheelPresent() && shadersInUse()

    // Active shaderpack name, resolved in this order so detection survives the
    // user renaming the pack folder:
    //   1) iris.properties `shaderPack=` folder/file name, if it already matches
    //      an adapter (fast path, no pack I/O);
    //   2) the pack's OWN display-name field (shader.properties /
    //      shaders/shaders.properties `name=`). None of BSL/Comp/photon/iteration
    //      ship one today, but packs that do get their authoritative name;
    //   3) a bounded probe of a few stable in-pack text files for the known
    //      family markers (rename-proof);
    // falling back to the raw folder/file name. The expensive steps are cached
    // against iris.properties' mtime, so per-tick / per-instance calls are cheap.
    @JvmStatic
    fun shaderpackName(): String? {
        return try {
            val iris = gameDir().resolve("config/iris.properties")
            val raw = readFirstValue(iris, "shaderPack=")?.trim() ?: return null
            if (raw.isEmpty()) return null
            val stamp = Files.getLastModifiedTime(iris).toMillis()
            if (raw != cachedRaw || stamp != cachedStamp || cachedResolved == null) {
                cachedRaw = raw
                cachedStamp = stamp
                cachedResolved = resolvePackName(raw)
            }
            cachedResolved
        } catch (t: Throwable) {
            null
        }
    }

    private var cachedRaw: String? = null
    private var cachedStamp: Long? = null
    private var cachedResolved: String? = null

    private fun gameDir(): Path =
        net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()

    private fun readFirstValue(iris: Path, prefix: String): String? =
        Files.readAllLines(iris).firstOrNull { it.startsWith(prefix) }?.substringAfter('=')

    private fun resolvePackName(raw: String): String? {
        val packPath = gameDir().resolve("shaderpacks").resolve(raw)
        if (!Files.exists(packPath)) return raw
        // fast path: the folder/file name already identifies an adapter
        if (ShaderpackWaterAdapters.resolve(raw) != null) return raw
        val zip: ZipFile? = if (Files.isDirectory(packPath))
            null
        else
            try { ZipFile(packPath.toFile()) } catch (t: Throwable) { null }
        try {
            readNameField(packPath, zip)?.let { return it }
            probeFamilyMarker(packPath, zip)?.let { return it }
        } finally {
            zip?.close()
        }
        // widened adapter tokens still get a chance on the raw folder name
        return raw
    }

    // Ordered by ShaderpackWaterAdapters.ALL priority; the probe checks each
    // candidate file head for the first matching marker.
    private val FAMILY_MARKERS: List<Pair<String, List<String>>> = listOf(
        "bsl" to listOf("bsl"),
        "complementary" to listOf("complementary"),
        "photon" to listOf("photon"),
        "iteration" to listOf("iteration", "itrp")
    )
    // Small, stable, text-only entries that carry a pack's own name comment.
    private val PROBE_FILES: List<String> = listOf(
        "shaders/shaders.properties",
        "shaders/block.properties",
        "shaders/Lib/Utilities.glsl",
        "shaders/lib/Utilities.glsl",
        "shaders/Utilities.glsl",
        "shader.properties"
    )

    private fun readNameField(packPath: Path, zip: ZipFile?): String? {
        for (c in listOf("shader.properties", "shaders/shaders.properties", "shaders.properties")) {
            val content = readEntry(packPath, zip, c, 16384) ?: continue
            for (line in content.lineSequence()) {
                val t = line.trim()
                if (t.startsWith("name=", ignoreCase = true) || t.startsWith("name =", ignoreCase = true)) {
                    val v = t.substringAfter('=').trim().trim('"', '\'', ' ')
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return null
    }

    private fun probeFamilyMarker(packPath: Path, zip: ZipFile?): String? {
        for (probe in PROBE_FILES) {
            val head = readEntry(packPath, zip, probe, 4096)?.take(4096) ?: continue
            for ((token, markers) in FAMILY_MARKERS) {
                for (m in markers) {
                    if (head.contains(m, ignoreCase = true)) return token
                }
            }
        }
        return null
    }

    private fun readEntry(packPath: Path, zip: ZipFile?, rel: String, limit: Int): String? {
        return try {
            if (zip != null) {
                val e = zip.getEntry(rel) ?: return null
                zip.getInputStream(e).use { ins ->
                    String(ins.readNBytes(limit), StandardCharsets.UTF_8)
                }
            } else {
                val p = packPath.resolve(rel)
                if (!Files.isRegularFile(p)) return null
                Files.newInputStream(p).use { ins ->
                    String(ins.readNBytes(limit), StandardCharsets.UTF_8)
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * The per-vertex entity id we stamp onto water meshes, resolved from the
     * active shaderpack adapter (see shaderpack/ package for per-pack docs).
     * Falls back to 32000 (the Iris-ecosystem convention).
     */
    @JvmStatic
    fun waterStampId(): Int = ShaderpackWaterAdapters.activeOrGeneric().waterStampId

    /**
     * True when the active shaderpack samples the block atlas DIRECTLY from the
     * vertex uv inside its water program (iterationRP's gbuffers_water does
     * textureGrad(tex, v_texCoord)). Those packs need the water uv exported
     * already folded into the water sprite rect instead of raw tile coordinates,
     * otherwise the water band/stream samples arbitrary atlas regions.
     */
    @JvmStatic
    fun waterUvAtlasMode(): Boolean =
        ShaderpackWaterAdapters.active() is IterationWaterAdapter

    /**
     * True when the full water-shading stack is active AND the pack is
     * iterationRP. Used for iterationRP-only water mesh tweaks (10x water mesh
     * subdivision, vertex jitter disabled); every other pack keeps the base
     * water mesh/jitter behavior.
     */
    @JvmStatic
    fun iterationRpWaterMode(): Boolean =
        waterShadingActive() && waterUvAtlasMode()
}