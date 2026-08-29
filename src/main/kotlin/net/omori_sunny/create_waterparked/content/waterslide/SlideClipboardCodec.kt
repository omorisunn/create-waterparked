package net.omori_sunny.create_waterparked.content.waterslide

import net.minecraft.resources.ResourceLocation
import net.omori_sunny.create_waterparked.config.ModConfig

// Parsed payload of one recognized clipboard line.
data class ParsedSlideConfig(val name: String, val config: WaterslideSectorConfig)

// One-line text codec for slide sector config clipboard entries.
// Line format (strict head/tail detection):
//   %SC{<name>|s:<startAngle>;<blockId>:<type>:<width>,...}
//   blockId: '~'+short name abbreviates minecraft:, '*' = block material with
//            default block, '-' = open material, otherwise a full block id
//   type: 'a' AUTO, 'f' FIXED, width: a float for FIXED, '-' for AUTO
//   the s: meta item carries the ring start angle and may be omitted
// All functions are strict and never throw.
object SlideClipboardCodec {
    const val PREFIX = "%SC{"
    const val SUFFIX = "}"
    const val MAX_NAME = 32

    private const val TYPE_AUTO = "a"
    private const val TYPE_FIXED = "f"
    private const val WIDTH_NONE = "-"
    private const val BLOCK_NONE = "-"
    private const val BLOCK_DEFAULT = "*"
    private const val START_PREFIX = "s:"
    private const val NAMESPACE_SHORT = '~'
    private const val MINECRAFT_NAMESPACE = "minecraft:"
    private const val FALLBACK_NAME = "Slide"

    // true when the line is a parseable slide config
    fun recognize(line: String): Boolean = parse(line) != null

    // serialize name + config to a single clipboard line
    fun serialize(name: String, config: WaterslideSectorConfig): String {
        val sb = StringBuilder(PREFIX)
        val clean = sanitizeName(name)
        // an all-separator name would serialize to an unparseable line
        sb.append(clean.ifEmpty { FALLBACK_NAME }).append('|')
        sb.append(START_PREFIX).append(formatFloat(config.startAngle)).append(';')
        for ((i, sector) in config.sectors.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(blockToken(sector)).append(':')
            sb.append(if (sector.type == SectorType.AUTO) TYPE_AUTO else TYPE_FIXED).append(':')
            sb.append(if (sector.type == SectorType.FIXED) formatFloat(sector.widthDegrees) else WIDTH_NONE)
        }
        sb.append(SUFFIX)
        return sb.toString()
    }

    // strict parse, null on any malformed field
    fun parse(line: String): ParsedSlideConfig? {
        if (!line.startsWith(PREFIX) || !line.endsWith(SUFFIX)) return null
        val inner = line.substring(PREFIX.length, line.length - SUFFIX.length)
        val sep = inner.indexOf('|')
        if (sep < 0) return null
        val name = inner.substring(0, sep)
        if (name.isEmpty() || name.length > MAX_NAME) return null
        val body = inner.substring(sep + 1)
        if (body.isEmpty()) return null

        val items = body.split(';')
        var startAngle = 0f
        var firstSector = 0
        if (items.firstOrNull()?.startsWith(START_PREFIX) == true) {
            startAngle = items[0].substring(START_PREFIX.length).toFloatOrNull() ?: return null
            if (startAngle.isNaN() || startAngle < 0f || startAngle > 360f) return null
            firstSector = 1
        }
        val sectorToken = items.subList(firstSector, items.size).joinToString(";")
        if (sectorToken.isEmpty()) return null

        val config = WaterslideSectorConfig()
        config.nextId = 1
        config.startAngle = startAngle
        for ((id, token) in sectorToken.split(',').withIndex()) {
            if (token.isEmpty()) return null
            val fields = token.split(':', limit = 3)
            if (fields.size != 3) return null
            val material: SectorMaterial
            val blockId: ResourceLocation?
            when (fields[0]) {
                BLOCK_NONE -> {
                    material = SectorMaterial.OPEN
                    blockId = null
                }
                BLOCK_DEFAULT -> {
                    material = SectorMaterial.BLOCK
                    blockId = null
                }
                else -> {
                    material = SectorMaterial.BLOCK
                    blockId = parseBlockId(fields[0]) ?: return null
                }
            }
            val type = when (fields[1]) {
                TYPE_AUTO -> SectorType.AUTO
                TYPE_FIXED -> SectorType.FIXED
                else -> return null
            }
            var width = 0f
            if (type == SectorType.FIXED) {
                width = fields[2].toFloatOrNull() ?: return null
                if (width.isNaN() || width < 0f || width > 360f) return null
            } else {
                if (fields[2] != WIDTH_NONE) return null
            }
            config.sectors += WaterslideSector(id, material, blockId, type, width)
        }
        if (config.sectors.isEmpty() || config.sectors.size > ModConfig.maxSectors()) return null
        config.nextId = config.sectors.size
        config.repairBrokenLayout()
        return ParsedSlideConfig(name, config)
    }

    // replace the name portion, keep the rest verbatim
    fun rename(line: String, newName: String): String? {
        if (parse(line) == null) return null
        val inner = line.substring(PREFIX.length, line.length - SUFFIX.length)
        val sep = inner.indexOf('|')
        if (sep < 0) return null
        val clean = sanitizeName(newName)
        if (clean.isEmpty()) return line
        return PREFIX + clean + inner.substring(sep) + SUFFIX
    }

    // short human summary for the GUI button, e.g. "sectors 3, start 12.5"
    fun summary(line: String): String? {
        val parsed = parse(line) ?: return null
        return "sectors ${parsed.config.sectors.size}, start ${formatFloat(parsed.config.startAngle)}"
    }

    // the name's edit window starts right after '%SC{' and ends at '|'
    fun nameEnd(line: String): Int {
        val sep = line.indexOf('|', PREFIX.length)
        return if (sep < 0) line.length else sep
    }

    private fun sanitizeName(name: String): String =
        name.replace('|', ' ').replace('{', ' ').replace('}', ' ')
            .replace('#', ' ').replace('\n', ' ').trim().take(MAX_NAME)

    private fun blockToken(sector: WaterslideSector): String {
        if (sector.material == SectorMaterial.OPEN) return BLOCK_NONE
        val id = sector.blockId ?: return BLOCK_DEFAULT
        val text = id.toString()
        if (text.startsWith(MINECRAFT_NAMESPACE)) return NAMESPACE_SHORT + text.substring(MINECRAFT_NAMESPACE.length)
        return text
    }

    private fun parseBlockId(token: String): ResourceLocation? {
        if (token.startsWith(NAMESPACE_SHORT)) {
            return ResourceLocation.tryParse(MINECRAFT_NAMESPACE + token.substring(1))
        }
        return ResourceLocation.tryParse(token)
    }

    // one decimal max, no trailing .0
    private fun formatFloat(value: Float): String {
        val rounded = Math.round(value * 10f) / 10f
        if (rounded == rounded.toInt().toFloat()) return rounded.toInt().toString()
        return rounded.toString()
    }
}
