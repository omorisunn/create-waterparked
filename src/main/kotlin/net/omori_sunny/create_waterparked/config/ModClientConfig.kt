package net.omori_sunny.create_waterparked.config

import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec

// client config
object ModClientConfig {
    private val BUILDER = ModConfigSpec.Builder()

    // skeleton rings in translucent mode
    val SHOW_SKELETON_WHEN_TRANSLUCENT: ModConfigSpec.BooleanValue = BUILDER
        .comment(
            "Show segment-junction skeleton rings when the slide tube is translucent " +
                "in edit mode."
        )
        .define("showSkeletonWhenTranslucent", true)

    val SPEC: ModConfigSpec = BUILDER.build()

    fun showSkeletonWhenTranslucent(): Boolean = SHOW_SKELETON_WHEN_TRANSLUCENT.get()

    fun register() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, SPEC)
    }
}
