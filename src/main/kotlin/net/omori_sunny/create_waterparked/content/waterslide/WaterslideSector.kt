package net.omori_sunny.create_waterparked.content.waterslide

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceLocation
import kotlin.math.abs

enum class SectorType {
    // auto size
    AUTO,

    // fixed size
    FIXED
}

enum class SectorMaterial {
    // block texture
    BLOCK,

    // open
    OPEN
}

// One sector on the circle.
data class WaterslideSector(
    val id: Int,
    val material: SectorMaterial,
    val blockId: ResourceLocation? = null,
    val type: SectorType = SectorType.AUTO,
    val widthDegrees: Float = 0f
) {
    fun write(tag: CompoundTag) {
        tag.putInt("Id", id)
        tag.putString("Material", material.name)
        tag.putString("Type", type.name)
        if (blockId != null) {
            tag.putString("Block", blockId.toString())
        }
        if (type == SectorType.FIXED) {
            tag.putFloat("Width", widthDegrees)
        }
    }

    companion object {
        fun read(tag: CompoundTag): WaterslideSector {
            val material = SectorMaterial.valueOf(tag.getString("Material"))
            val type = SectorType.valueOf(tag.getString("Type"))
            val blockId = if (tag.contains("Block", 8)) {
                ResourceLocation.parse(tag.getString("Block"))
            } else {
                null
            }
            val width = if (tag.contains("Width", 5)) tag.getFloat("Width") else 0f
            return WaterslideSector(tag.getInt("Id"), material, blockId, type, width)
        }
    }
}

// Sector config for one curve.
class WaterslideSectorConfig {
    val sectors = mutableListOf<WaterslideSector>()
    var nextId = 1

    fun newId(): Int = nextId++

    fun copyOf(): WaterslideSectorConfig {
        val copy = WaterslideSectorConfig()
        copy.nextId = nextId
        copy.sectors += sectors.map { it.copy() }
        return copy
    }

// repair legacy layouts
    private fun repairBrokenLayout() {
        for (i in sectors.indices) {
            val s = sectors[i]
            if (s.type == SectorType.FIXED && s.widthDegrees <= 0.5f) {
                sectors[i] = s.copy(type = SectorType.AUTO, widthDegrees = 0f)
            }
        }
        val fixedSum = sectors
            .filter { it.type == SectorType.FIXED }
            .sumOf { it.widthDegrees.toDouble() }
            .toFloat()
        val autoCount = sectors.count { it.type == SectorType.AUTO }
        if (autoCount > 0 && fixedSum >= 359.5f) {
            val widest = sectors
                .filter { it.type == SectorType.FIXED }
                .maxByOrNull { it.widthDegrees }
            if (widest != null) {
                val i = sectors.indexOf(widest)
                sectors[i] = widest.copy(type = SectorType.AUTO, widthDegrees = 0f)
            }
        }
    }

    fun write(tag: CompoundTag) {
        tag.putInt("NextId", nextId)
        val list = ListTag()
        for (sector in sectors) {
            val entry = CompoundTag()
            sector.write(entry)
            list.add(entry)
        }
        tag.put("Sectors", list)
    }

    companion object {
        fun read(tag: CompoundTag): WaterslideSectorConfig {
            val config = WaterslideSectorConfig()
            config.nextId = tag.getInt("NextId")
            for (entry in tag.getList("Sectors", 10)) {
                if (entry is CompoundTag) {
                    config.sectors += WaterslideSector.read(entry)
                }
            }
            config.repairBrokenLayout()
            return config
        }

// default config
        fun defaultConfig(): WaterslideSectorConfig {
            val config = WaterslideSectorConfig()
            config.sectors += WaterslideSector(
                id = 0,
                material = SectorMaterial.BLOCK,
                blockId = ResourceLocation.withDefaultNamespace("gray_concrete"),
                type = SectorType.AUTO,
                widthDegrees = 0f
            )
            config.nextId = 1
            return config
        }
    }
}

data class PlacedSector(
    val sector: WaterslideSector,
    val startAngle: Float,
    val endAngle: Float
) {
    val centerAngle: Float
        get() = (startAngle + endAngle) / 2f
}

// fixed first, auto shares the rest
object WaterslideSectorLayout {
    const val FULL_CIRCLE = 360f

    fun place(sectors: List<WaterslideSector>): List<PlacedSector> {
        val fixedSum = sectors
            .filter { it.type == SectorType.FIXED }
            .sumOf { it.widthDegrees.toDouble() }
            .toFloat()
        val autoCount = sectors.count { it.type == SectorType.AUTO }
        val remaining = (FULL_CIRCLE - fixedSum).coerceAtLeast(0f)
        val autoWidth = if (autoCount > 0) remaining / autoCount else 0f

        var cursor = 0f
        val out = ArrayList<PlacedSector>(sectors.size)
        for (sector in sectors) {
            val width = if (sector.type == SectorType.FIXED) sector.widthDegrees else autoWidth
            out += PlacedSector(sector, cursor, cursor + width)
            cursor += width
        }
        return out
    }

// insert near boundary
    fun insertionIndex(placed: List<PlacedSector>, clickAngle: Float): Int {
        val angle = normalize(clickAngle)
        for ((index, p) in placed.withIndex()) {
            if (angle >= p.startAngle && angle < p.endAngle) {
                val mid = (p.startAngle + p.endAngle) / 2f
                return if (angle < mid) index else index + 1
            }
        }
        return placed.size
    }

    fun sectorAt(placed: List<PlacedSector>, clickAngle: Float): PlacedSector? {
        val angle = normalize(clickAngle)
        return placed.firstOrNull { angle >= it.startAngle && angle < it.endAngle }
    }

    fun normalize(angle: Float): Float = ((angle % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE

// move sector
    fun applyMove(config: WaterslideSectorConfig, sectorId: Int, newCenterAngle: Float) {
        val index = config.sectors.indexOfFirst { it.id == sectorId }
        if (index < 0) return
        val target = config.sectors[index]
        val placed = place(config.sectors)
        val old = placed.firstOrNull { it.sector.id == sectorId } ?: return
        if (abs(normalize(newCenterAngle - old.centerAngle)) <= 0.5f) return

        val width = old.endAngle - old.startAngle
        config.sectors.removeAt(index)
        val remaining = place(config.sectors)
        val insertIndex = insertionIndex(remaining, newCenterAngle)
        val canFix = target.type == SectorType.FIXED ||
            (width > 0.5f && width < 359.5f)
        config.sectors.add(
            insertIndex,
            target.copy(
                type = if (canFix) SectorType.FIXED else SectorType.AUTO,
                widthDegrees = if (canFix) width else 0f
            )
        )
    }

// resize adjacent sectors
    fun applyBoundaryResize(config: WaterslideSectorConfig, sectorId: Int, newBoundaryAngle: Float) {
        val idx = config.sectors.indexOfFirst { it.id == sectorId }
        if (idx < 0) return
        val placed = place(config.sectors)
        val prev = placed[idx]
        val next = placed[(idx + 1) % placed.size]
        if (prev.sector.id == next.sector.id) return

        val boundary = normalize(newBoundaryAngle)
        val prevStart = prev.startAngle
        val nextEnd = next.endAngle
// full-circle span
        val spanRaw = normalize(nextEnd - prevStart)
        val span = if (spanRaw <= 0.01f) FULL_CIRCLE else spanRaw
        val wPrev = normalize(boundary - prevStart)
        val wNext = normalize(nextEnd - boundary)
// non-zero widths
        if (wPrev < 0.5f || wNext < 0.5f || wPrev + wNext > span + 0.5f) return

        config.sectors[idx] = prev.sector.copy(type = SectorType.FIXED, widthDegrees = wPrev)
        config.sectors[(idx + 1) % config.sectors.size] =
            next.sector.copy(type = SectorType.FIXED, widthDegrees = wNext)
    }
}
