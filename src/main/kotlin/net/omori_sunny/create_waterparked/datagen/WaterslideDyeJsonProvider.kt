package net.omori_sunny.create_waterparked.datagen

import com.google.gson.JsonObject
import net.minecraft.data.PackOutput
import net.minecraft.resources.ResourceLocation

// default waterslide_dye.json
class WaterslideDyeJsonProvider(output: PackOutput, modId: String) :
    AssetJsonProvider(output, modId, "Waterparked assets: waterslide dye rules") {

    private val COLOR_NAMES = listOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
        "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    )

    private val VANILLA_BASE_FORMS = mapOf(
        "glass" to "stained_glass",
        "glass_pane" to "stained_glass_pane",
        "terracotta" to "terracotta",
        "candle" to "candle",
        "shulker_box" to "shulker_box"
    )

    private val VANILLA_DYEABLE_FAMILIES = listOf(
        "wool", "carpet", "concrete", "concrete_powder", "terracotta",
        "stained_glass", "stained_glass_pane", "bed", "candle", "candle_cake",
        "shulker_box", "banner", "wall_banner"
    )

    override fun gather(): Map<ResourceLocation, JsonObject> {
        val blocks = JsonObject()

        fun add(id: String, target: String? = null, dyeable: Boolean? = null) {
            val rule = JsonObject()
            if (dyeable != null) rule.addProperty("dyeable", dyeable)
            if (target != null) rule.addProperty("target", target)
            blocks.add(id, rule)
        }

        for ((base, suffix) in VANILLA_BASE_FORMS) {
            add("minecraft:$base", target = "minecraft:%s_$suffix")
        }
        for (family in VANILLA_DYEABLE_FAMILIES) {
            for (color in COLOR_NAMES) {
                add("minecraft:${color}_$family", target = "minecraft:%s_$family")
            }
        }
        add("create:framed_glass", dyeable = false)

        val root = JsonObject()
        root.add("blocks", blocks)
        return mapOf(ResourceLocation.fromNamespaceAndPath(modId, "waterslide_dye") to root)
    }
}
