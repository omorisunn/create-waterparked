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

    // particles for the client-side water shape simulation
    val WATER_PARTICLE_COUNT: ModConfigSpec.IntValue = BUILDER
        .comment("Number of particles used to compute the client-side water shape.")
        .defineInRange("waterParticleCount", 64, 8, 256)

    // polygon density, client-only
    val POLYGON_SCALE: ModConfigSpec.DoubleValue = BUILDER
        .comment("Mesh polygon density scale. Lower = fewer faces.")
        .defineInRange("polygonScale", 0.5, 0.05, 2.0)

    // pipe wall thickness, client-only
    val WALL_THICKNESS: ModConfigSpec.DoubleValue = BUILDER
        .comment("Pipe wall thickness in blocks. Thickens outward; inner radius stays fixed.")
        .defineInRange("wallThickness", 0.2, 0.1, 0.5)

    // water flow scroll speed scale, client-only
    val WATER_FLOW_SCALE: ModConfigSpec.DoubleValue = BUILDER
        .comment("Water flow scroll speed scale. Higher = faster.")
        .defineInRange("waterFlowScale", 1.0, 0.1, 4.0)

    // envelope polygon vertex count for the water mesh
    val WATER_ENVELOPE_VERTICES: ModConfigSpec.IntValue = BUILDER
        .comment("Vertex count of the water envelope polygon. Lower = rougher water shape.")
        .defineInRange("waterEnvelopeVertices", 16, 4, 16)

    // rendering section spacing for the water envelope
    val WATER_ENVELOPE_SPACING: ModConfigSpec.IntValue = BUILDER
        .comment("Spacing between rendered water envelope sections, in blocks.")
        .defineInRange("waterEnvelopeSpacing", 2, 1, 4)

    // debug: show the water simulation trajectories
    val WATER_SIM_DEBUG: ModConfigSpec.BooleanValue = BUILDER
        .comment("Show the server water simulation trajectories (debug).")
        .define("waterSimDebug", false)

    // cull the closing walls between water band sub-segments
    val WATER_CULL_SEGMENT_WALLS: ModConfigSpec.BooleanValue = BUILDER
        .comment("Cull the closing walls between water band sub-segments for a seamless band.")
        .define("waterCullSegmentWalls", true)

    val SPEC: ModConfigSpec = BUILDER.build()

    fun showSkeletonWhenTranslucent(): Boolean = SHOW_SKELETON_WHEN_TRANSLUCENT.get()

    fun showSlideExitHint(): Boolean = SHOW_SLIDE_EXIT_HINT.get()

    fun cameraSmoothing(): Float = CAMERA_SMOOTHING.get().toFloat().coerceIn(0f, 0.9f)

    fun waterParticleCount(): Int = WATER_PARTICLE_COUNT.get().coerceIn(8, 256)

    fun polygonScale(): Float = POLYGON_SCALE.get().toFloat().coerceIn(0.05f, 2.0f)

    fun wallThickness(): Float = WALL_THICKNESS.get().toFloat().coerceIn(0.1f, 0.5f)

    fun waterFlowScale(): Float = WATER_FLOW_SCALE.get().toFloat().coerceIn(0.1f, 4.0f)

    fun waterEnvelopeVertices(): Int = WATER_ENVELOPE_VERTICES.get().coerceIn(4, 16)

    fun waterEnvelopeSpacing(): Int = WATER_ENVELOPE_SPACING.get().coerceIn(1, 4)

    fun waterSimDebug(): Boolean = WATER_SIM_DEBUG.get()

    fun waterCullSegmentWalls(): Boolean = WATER_CULL_SEGMENT_WALLS.get()

    fun register() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, SPEC)
    }
}
