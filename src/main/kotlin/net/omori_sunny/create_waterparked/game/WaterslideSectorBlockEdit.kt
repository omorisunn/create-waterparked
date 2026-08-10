package net.omori_sunny.create_waterparked.game

import com.simibubi.create.content.trains.track.BezierConnection
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor

// Server-side sector block editing.
object WaterslideSectorBlockEdit {

    private val COLOR_NAMES = listOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
        "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    )

// change a sector block by id
    fun setSectorBlock(
        level: ServerLevel,
        curveA: BlockPos,
        curveB: BlockPos,
        sectorId: Int,
        blockId: ResourceLocation?
    ): Boolean {
        if (blockId != null && !BuiltInRegistries.BLOCK.containsKey(blockId)) return false
        val curve = findCurve(level, curveA, curveB) ?: return false
        val storage = level.getBlockEntity(curve.bePositions.getFirst()) as? WaterslideAnchorBlockEntity
            ?: return false
        val peer = curve.bePositions.getSecond()
        val config = storage.sectorConfigFor(peer)
        val idx = config.sectors.indexOfFirst { it.id == sectorId }
        if (idx < 0) return false
        val old = config.sectors[idx]
        config.sectors[idx] = old.copy(
            material = if (blockId == null) SectorMaterial.OPEN else SectorMaterial.BLOCK,
            blockId = blockId
        )
        WaterslideAnchorBlockEntity.commitSectorConfig(level, curve, config)
        return true
    }

// dyed variant of a sector block
    fun dyedBlockFor(blockId: ResourceLocation?, dye: DyeColor): ResourceLocation? {
        if (blockId == null || blockId.namespace != "minecraft") return null
        val path = blockId.path
        val color = dye.name.lowercase()
        val target = when {
            path == "glass" -> "${color}_stained_glass"
            path == "glass_pane" -> "${color}_stained_glass_pane"
            path == "terracotta" -> "${color}_terracotta"
            else -> {
                val prefix = COLOR_NAMES.firstOrNull { path.startsWith(it + "_") } ?: return null
                "$color${path.removePrefix(prefix + "_")}"
            }
        }
        val id = ResourceLocation.withDefaultNamespace(target)
        return if (BuiltInRegistries.BLOCK.containsKey(id)) id else null
    }

    private fun findCurve(level: ServerLevel, a: BlockPos, b: BlockPos): BezierConnection? {
        val be = level.getBlockEntity(a) as? WaterslideAnchorBlockEntity ?: return null
        val raw = be.getAnchorPeerCurvesView()[b] ?: return null
        val primary = if (raw.isPrimary) raw else raw.secondary()
        return if (WaterslideTrackMaterials.isWaterslide(primary)) primary else null
    }
}
