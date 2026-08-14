package net.omori_sunny.create_waterparked.config

import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec

// client config
object ModClientConfig {
    private val BUILDER = ModConfigSpec.Builder()

    // slide (riding)
    lateinit var SHOW_SLIDE_EXIT_HINT: ModConfigSpec.BooleanValue
    lateinit var CAMERA_SMOOTHING: ModConfigSpec.DoubleValue
    lateinit var SHOW_SKELETON_WHEN_TRANSLUCENT: ModConfigSpec.BooleanValue
    // rendering
    lateinit var POLYGON_SCALE: ModConfigSpec.DoubleValue
    lateinit var WALL_THICKNESS: ModConfigSpec.DoubleValue
    lateinit var WATER_FLOW_SCALE: ModConfigSpec.DoubleValue
    lateinit var WATER_ENVELOPE_VERTICES: ModConfigSpec.IntValue
    lateinit var WATER_ENVELOPE_SPACING: ModConfigSpec.IntValue
    lateinit var WATER_JITTER_SCALE: ModConfigSpec.DoubleValue
    lateinit var WATER_JITTER_FREQUENCY: ModConfigSpec.DoubleValue
    lateinit var WATER_JITTER_TIME_SCALE: ModConfigSpec.DoubleValue
    // water simulation
    lateinit var WATER_PARTICLE_COUNT: ModConfigSpec.IntValue
    // debug
    lateinit var WATER_SIM_DEBUG: ModConfigSpec.BooleanValue

    lateinit var SPEC: ModConfigSpec

    init {
        BUILDER.push("slide")
        SHOW_SLIDE_EXIT_HINT = BUILDER
            .comment("Show the \"Shift to exit\" hint while sliding.")
            .define("showSlideExitHint", true)
        CAMERA_SMOOTHING = BUILDER
            .comment("Camera smoothing strength while sliding. 0 = off, higher = smoother.")
            .defineInRange("cameraSmoothing", 0.9, 0.0, 0.9)
        SHOW_SKELETON_WHEN_TRANSLUCENT = BUILDER
            .comment("Show segment-junction skeleton rings when the slide tube is translucent in edit mode.")
            .define("showSkeletonWhenTranslucent", false)
        BUILDER.pop()

        BUILDER.push("rendering")
        POLYGON_SCALE = BUILDER
            .comment("Mesh polygon density scale. Lower = fewer faces.")
            .defineInRange("polygonScale", 0.5, 0.05, 2.0)
        WALL_THICKNESS = BUILDER
            .comment("Pipe wall thickness in blocks. Thickens outward; inner radius stays fixed.")
            .defineInRange("wallThickness", 0.5, 0.1, 0.5)
        WATER_FLOW_SCALE = BUILDER
            .comment("Water flow scroll speed scale. Higher = faster.")
            .defineInRange("waterFlowScale", 1.0, 0.1, 4.0)
        WATER_ENVELOPE_VERTICES = BUILDER
            .comment("Vertex count of the water envelope polygon. Lower = rougher water shape.")
            .defineInRange("waterEnvelopeVertices", 16, 4, 16)
        WATER_ENVELOPE_SPACING = BUILDER
            .comment("Spacing between rendered water envelope sections, in blocks.")
            .defineInRange("waterEnvelopeSpacing", 2, 1, 4)
        WATER_JITTER_SCALE = BUILDER
            .comment("Water vertex jitter amplitude (turbulence) in blocks. 0 = off.")
            .defineInRange("waterJitterScale", 0.2, 0.0, 1.5)
        WATER_JITTER_FREQUENCY = BUILDER
            .comment("Water vertex jitter noise frequency (cycles per block).")
            .defineInRange("waterJitterFrequency", 2.0, 1.0, 16.0)
        WATER_JITTER_TIME_SCALE = BUILDER
            .comment("Water vertex jitter noise time scale (how fast the noise evolves).")
            .defineInRange("waterJitterTimeScale", 12.0, 0.1, 16.0)
        BUILDER.pop()

        BUILDER.push("water")
        WATER_PARTICLE_COUNT = BUILDER
            .comment("Number of particles used to compute the client-side water shape.")
            .defineInRange("waterParticleCount", 64, 8, 256)
        BUILDER.pop()

        BUILDER.push("debug")
        WATER_SIM_DEBUG = BUILDER
            .comment("Show the server water simulation trajectories (debug).")
            .define("waterSimDebug", false)
        BUILDER.pop()

        SPEC = BUILDER.build()
    }

    fun showSkeletonWhenTranslucent(): Boolean = SHOW_SKELETON_WHEN_TRANSLUCENT.get()

    fun showSlideExitHint(): Boolean = SHOW_SLIDE_EXIT_HINT.get()

    fun cameraSmoothing(): Float = CAMERA_SMOOTHING.get().toFloat().coerceIn(0f, 0.9f)

    fun waterParticleCount(): Int = WATER_PARTICLE_COUNT.get().coerceIn(8, 256)

    fun polygonScale(): Float = POLYGON_SCALE.get().toFloat().coerceIn(0.05f, 2.0f)

    fun wallThickness(): Float = WALL_THICKNESS.get().toFloat().coerceIn(0.1f, 0.5f)

    fun waterFlowScale(): Float = WATER_FLOW_SCALE.get().toFloat().coerceIn(0.1f, 4.0f)

    fun waterJitterScale(): Float = WATER_JITTER_SCALE.get().toFloat().coerceIn(0f, 1.5f)

    fun waterJitterFrequency(): Float = WATER_JITTER_FREQUENCY.get().toFloat().coerceIn(1f, 16f)

    fun waterJitterTimeScale(): Float = WATER_JITTER_TIME_SCALE.get().toFloat().coerceIn(0.1f, 16f)

    fun waterEnvelopeVertices(): Int = WATER_ENVELOPE_VERTICES.get().coerceIn(4, 16)

    fun waterEnvelopeSpacing(): Int = WATER_ENVELOPE_SPACING.get().coerceIn(1, 4)

    fun waterSimDebug(): Boolean = WATER_SIM_DEBUG.get()

    fun register() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, SPEC)
    }
}
