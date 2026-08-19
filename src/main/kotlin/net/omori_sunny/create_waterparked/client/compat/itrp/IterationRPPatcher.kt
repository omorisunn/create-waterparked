package net.omori_sunny.create_waterparked.client.compat.itrp

import net.minecraft.client.Minecraft
import net.neoforged.fml.loading.FMLPaths
import net.omori_sunny.create_waterparked.CreateWaterparked
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Runtime shaderpack patcher for iterationRP, modeled on ColorwheelPatcher:
 * at client start it finds the base iterationRP pack in the shaderpacks folder
 * (zip or folder, any OS), overlays our patched shader files and writes a new
 * "… + Waterparked" pack next to it, so the pack itself is never modified.
 *
 * Why this patch exists: iterationRP's colorwheel.properties disables blending
 * for its clrwl translucent pass (blend.clrwl_gbuffers_translucent = off) and
 * its composite derives water thickness from buffers that colorwheel never
 * writes, so colorwheel-rendered water (our tube band / thrown stream) is not
 * treated like vanilla water at all. The patch:
 *  1. colorwheel.properties: keep global blend off but add the SAME per-buffer
 *     blends vanilla gbuffers_water uses (colortex0 / colortex5). Water_FS
 *     writes albedo.a = 0, so blending keeps the opaque albedo under the water
 *     in colortex0; without it composite25 shades the water texture as solid
 *     albedo and everything behind the thrown stream turns white;
 *  2. Water_VS: our water (stamped entity id 12000) is classified as the
 *     pack's own MATID_WATER (6), and PROGRAM_COLORWHEEL reads the folded
 *     Flywheel uv (flw_vertexTexCoord) instead of gl_MultiTexCoord0;
 *  3. Water_FS: RENDERTARGETS 0,3,4,5 under PROGRAM_COLORWHEEL and an
 *     unconditional framebuffer_gwater write (waterData.w == 1), matching the
 *     vanilla gbuffers_water path;
 *  4. GbufferData/Translucent_FS: kept 100% vanilla - our water goes through
 *     the pack's original water path (refraction/fog/specular).
 */
object IterationRPPatcher {

    private const val PATCH_ROOT = "assets/create_waterparked/itrp_patch/"
    private const val SUFFIX = " + Waterparked"
    private const val FAMILY_TOKEN = "iteration"

    private val PATCH_FILES: List<String> = listOf(
        "shaders/colorwheel.properties",
        "shaders/Lib/Programs/Gbuffers/Water_VS.glsl",
        "shaders/Lib/Programs/Gbuffers/Water_FS.glsl",
        "shaders/Lib/GbufferData.glsl",
        "shaders/Lib/Programs/Composite/Translucent_FS.glsl"
    )

    @Volatile
    private var attempted = false

    /** Call during FMLClientSetupEvent (before Iris can load any pack at world
     *  entry); safe to call repeatedly. */
    @JvmStatic
    fun runIfNeeded() {
        if (attempted) return
        attempted = true
        try {
            val packsDir = FMLPaths.GAMEDIR.get().resolve("shaderpacks")
            if (!Files.isDirectory(packsDir)) return
            val base = Files.list(packsDir).use { stream ->
                stream.filter { isBasePack(it) }.findFirst().orElse(null)
            } ?: return

            val out = packsDir.resolve(outputName(base))

            val overlay = HashMap<String, ByteArray>()
            for (rel in PATCH_FILES) {
                val bytes = resourceBytes(PATCH_ROOT + rel)
                if (bytes == null) {
                    CreateWaterparked.LOGGER.warn(
                        "[IterationRPPatcher] missing patch resource {}", PATCH_ROOT + rel)
                    return
                }
                overlay[rel] = bytes
            }

            // Rebuild whenever the embedded patch files differ from the resources:
            // a plain existence check left stale patched packs (older shader logic)
            // in place forever, so patch changes silently never reached the game.
            if (Files.exists(out) && patchContentMatches(out, overlay)) return // up-to-date

            if (Files.isDirectory(base)) patchFolder(base, out, overlay)
            else patchZip(base, out, overlay)

            CreateWaterparked.LOGGER.info(
                "[IterationRPPatcher] created {} (patched from {})",
                out.fileName.toString(), base.fileName.toString())
        } catch (t: Throwable) {
            CreateWaterparked.LOGGER.warn("[IterationRPPatcher] failed", t)
        }
    }

    private fun isBasePack(path: Path): Boolean {
        val name = path.fileName.toString()
        if (!name.contains(FAMILY_TOKEN, ignoreCase = true)) return false
        if (name.contains(SUFFIX, ignoreCase = true)) return false
        // sanity: the pack must actually contain the file we patch
        return if (Files.isDirectory(path))
            Files.isRegularFile(path.resolve(PATCH_FILES[0]))
        else
            runCatching { ZipFile(path.toFile()).use { z -> z.getEntry(PATCH_FILES[0]) != null } }
                .getOrDefault(false)
    }

    private fun outputName(base: Path): String {
        val name = base.fileName.toString()
        val zip = name.endsWith(".zip", ignoreCase = true)
        val stem = if (zip) name.dropLast(4) else name
        return stem + SUFFIX + if (zip) ".zip" else ""
    }

    /** True when every patched entry in the existing output pack equals the
     *  current patch resources; any mismatch (or read failure) means rebuild. */
    private fun patchContentMatches(out: Path, overlay: Map<String, ByteArray>): Boolean {
        return try {
            if (Files.isDirectory(out)) {
                overlay.all { (rel, bytes) ->
                    val f = out.resolve(rel)
                    Files.isRegularFile(f) && Files.size(f) == bytes.size.toLong() &&
                        Files.readAllBytes(f).contentEquals(bytes)
                }
            } else {
                ZipFile(out.toFile()).use { z ->
                    overlay.all { (rel, bytes) ->
                        val e = z.getEntry(rel)
                        e != null && e.size == bytes.size.toLong() &&
                            z.getInputStream(e).use { it.readBytes().contentEquals(bytes) }
                    }
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun resourceBytes(resource: String): ByteArray? =
        IterationRPPatcher::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }

    private fun patchFolder(base: Path, out: Path, overlay: Map<String, ByteArray>) {
        Files.walk(base).use { stream ->
            for (src in stream) {
                val rel = base.relativize(src)
                val dst = out.resolve(rel)
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else {
                    Files.createDirectories(dst.parent)
                    val key = rel.toString().replace('\\', '/')
                    val patched = overlay[key]
                    if (patched != null) Files.write(dst, patched)
                    else Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun patchZip(base: Path, out: Path, overlay: Map<String, ByteArray>) {
        // Write to a temp name and atomically rename: Iris may validate the pack
        // while we are still copying the ~22MB zip, and reading a half-written
        // file makes the whole pack "not valid" (shaders silently disabled).
        val tmp = out.resolveSibling(out.fileName.toString() + ".tmp")
        try {
            ZipFile(base.toFile()).use { zin ->
                ZipOutputStream(Files.newOutputStream(tmp)).use { zout ->
                    val entries = zin.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        zout.putNextEntry(ZipEntry(entry.name))
                        val patched = overlay[entry.name]
                        if (patched != null) {
                            zout.write(patched)
                        } else {
                            zin.getInputStream(entry).use { it.copyTo(zout) }
                        }
                        zout.closeEntry()
                    }
                }
            }
            // On Windows, REPLACE_EXISTING + ATOMIC_MOVE onto an existing file
            // throws AccessDenied when the target is transiently locked (Defender
            // scanning the 22MB zip, a stale handle). Delete the old pack first
            // (with a short retry), then rename atomically onto fresh ground.
            replaceWithRetry(tmp, out)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }

    private fun replaceWithRetry(tmp: Path, out: Path) {
        var last: Throwable? = null
        for (attempt in 1..6) {
            try {
                Files.deleteIfExists(out)
                Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (t: Throwable) {
                last = t
                Thread.sleep(250L)
            }
        }
        throw last ?: java.io.IOException("replace failed")
    }
}
