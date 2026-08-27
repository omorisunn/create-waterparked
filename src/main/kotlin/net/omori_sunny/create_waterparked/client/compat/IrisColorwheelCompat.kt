package net.omori_sunny.create_waterparked.client.compat

import net.neoforged.fml.ModList
import net.omori_sunny.create_waterparked.client.compat.shaderpack.IterationWaterAdapter
import net.omori_sunny.create_waterparked.client.compat.shaderpack.ShaderpackWaterAdapters
import net.omori_sunny.create_waterparked.config.ModClientConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

// Iris and Colorwheel compatibility detection, works without either mod
object IrisColorwheelCompat {

    @JvmStatic
    fun irisPresent(): Boolean = runCatching { ModList.get().isLoaded("iris") }.getOrDefault(false)

    @JvmStatic
    fun colorwheelPresent(): Boolean = runCatching { ModList.get().isLoaded("colorwheel") }.getOrDefault(false)

    // reflected call into Iris public API, nothing on its classpath is touched
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

    // true when the full shader water stack is active for mounted slides
    @JvmStatic
    fun waterShadingActive(): Boolean =
        ModClientConfig.shaderWaterCompat() &&
            irisPresent() && colorwheelPresent() && shadersInUse()

    // active pack name, resolved from folder name, display name and markers
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

    // ordered by adapter priority, first matching marker wins
    private val FAMILY_MARKERS: List<Pair<String, List<String>>> = listOf(
        "bsl" to listOf("bsl"),
        "complementary" to listOf("complementary"),
        "photon" to listOf("photon"),
        "iteration" to listOf("iteration", "itrp")
    )
    // small stable text files that carry a pack name comment
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