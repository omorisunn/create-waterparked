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

    val SHOW_SLIDE_EXIT_HINT: ModConfigSpec.BooleanValue = BUILDER
        .comment("Show the \"Shift to exit\" hint while sliding.")
        .define("showSlideExitHint", true)

    // camera smoothing while sliding, 0 = off
    val CAMERA_SMOOTHING: ModConfigSpec.DoubleValue = BUILDER
        .comment("Camera smoothing strength while sliding. 0 = off, higher = smoother.")
        .defineInRange("cameraSmoothing", 0.35, 0.0, 0.9)

    val SPEC: ModConfigSpec = BUILDER.build()

    fun showSkeletonWhenTranslucent(): Boolean = SHOW_SKELETON_WHEN_TRANSLUCENT.get()

    fun showSlideExitHint(): Boolean = SHOW_SLIDE_EXIT_HINT.get()

    fun cameraSmoothing(): Float = CAMERA_SMOOTHING.get().toFloat().coerceIn(0f, 0.9f)

    fun register() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, SPEC)
    }
}
