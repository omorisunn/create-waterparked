package net.omori_sunny.create_waterparked.client.editor

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.item.DyeColor
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn

// resource pack dye rules
@OnlyIn(Dist.CLIENT)
object WaterslideDyeRules {

    private const val RULES_FILE = "waterslide_dye.json"

    private val COLOR_NAMES = listOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
        "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    )

// uncolored block -> colored family suffix
    private val VANILLA_BASE_FORMS = mapOf(
        "glass" to "stained_glass",
        "glass_pane" to "stained_glass_pane",
        "terracotta" to "terracotta",
        "candle" to "candle",
        "shulker_box" to "shulker_box"
    )

// colored families with 16 dye variants
    private val VANILLA_DYEABLE_FAMILIES = listOf(
        "wool", "carpet", "concrete", "concrete_powder", "terracotta",
        "stained_glass", "stained_glass_pane", "bed", "candle", "candle_cake",
        "shulker_box", "banner", "wall_banner"
    )

    private data class BlockRule(
        val dyeable: Boolean = true,
        val target: String? = null,
        val mapping: Map<String, ResourceLocation> = emptyMap()
    )

    private var cachedManager: ResourceManager? = null
    private val rules = HashMap<ResourceLocation, BlockRule>()

// dyed variant or null
    @JvmStatic
    fun dyedBlockFor(blockId: ResourceLocation?, dye: DyeColor): ResourceLocation? {
        if (blockId == null) return null
        ensureLoaded()
        val rule = rules[blockId]
        if (rule != null && !rule.dyeable) return null
        val color = dye.name.lowercase()
        val target = when {
            rule != null && rule.mapping.isNotEmpty() -> rule.mapping[color]
            rule != null && rule.target != null -> ResourceLocation.tryParse(rule.target.replace("%s", color))
            else -> builtinDyed(blockId, color)
        } ?: return null
        return if (BuiltInRegistries.BLOCK.containsKey(target)) target else null
    }

// built-in defaults
    private fun builtinDyed(blockId: ResourceLocation, color: String): ResourceLocation? {
        if (blockId.namespace != "minecraft") return null
        val path = blockId.path
        val baseSuffix = VANILLA_BASE_FORMS[path]
        val suffix = if (baseSuffix != null) {
            baseSuffix
        } else {
            val prefix = COLOR_NAMES.firstOrNull { path.startsWith(it + "_") } ?: return null
            val rest = path.removePrefix(prefix + "_")
            if (rest !in VANILLA_DYEABLE_FAMILIES) return null
            rest
        }
        val target = "${color}_$suffix"
        val id = ResourceLocation.withDefaultNamespace(target)
        return if (BuiltInRegistries.BLOCK.containsKey(id)) id else null
    }

// lowest to highest priority, later packs win
    private fun ensureLoaded() {
        val manager = Minecraft.getInstance().resourceManager ?: return
        if (cachedManager === manager) return
        cachedManager = manager
        rules.clear()
        val loc = ResourceLocation.fromNamespaceAndPath("create_waterparked", RULES_FILE)
        for (resource in manager.getResourceStack(loc).asReversed()) {
            try {
                resource.open().use { stream ->
                    val root = JsonParser.parseReader(stream.reader()).asJsonObject
                    val blocks = root.getAsJsonObject("blocks") ?: return@use
                    for ((idString, element) in blocks.entrySet()) {
                        val id = ResourceLocation.tryParse(idString) ?: continue
                        rules[id] = parseRule(element)
                    }
                }
            } catch (e: Exception) {
                net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.error(
                    "Failed to load waterslide dye rules from {}", resource.sourcePackId(), e
                )
            }
        }
    }

    private fun parseRule(element: JsonElement): BlockRule {
        val obj = element.asJsonObject
        val dyeable = if (obj.has("dyeable")) obj.get("dyeable").asBoolean else true
        val target = obj.get("target")?.asString
        val mapping = HashMap<String, ResourceLocation>()
        obj.getAsJsonObject("mapping")?.let { map ->
            for ((color, value) in map.entrySet()) {
                ResourceLocation.tryParse(value.asString)?.let { mapping[color] = it }
            }
        }
        return BlockRule(dyeable, target, mapping)
    }
}
