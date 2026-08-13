package net.omori_sunny.create_waterparked.config

import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec

object ModConfig {
    private val BUILDER = ModConfigSpec.Builder()

    val DEFAULT_SLIDE_RADIUS: ModConfigSpec.DoubleValue = BUILDER
        .comment("Default opening radius of a new slide anchor, in blocks.")
        .defineInRange("defaultSlideRadius", 1.0, 0.1, 10.0)

    val MIN_SLIDE_RADIUS: ModConfigSpec.DoubleValue = BUILDER
        .comment("Minimum slide opening radius, in blocks.")
        .defineInRange("minSlideRadius", 0.35, 0.05, 10.0)

    val MAX_SLIDE_RADIUS: ModConfigSpec.DoubleValue = BUILDER
        .comment("Maximum slide opening radius, in blocks.")
        .defineInRange("maxSlideRadius", 2.5, 0.1, 20.0)

    val MAX_SLIDE_LIFT: ModConfigSpec.DoubleValue = BUILDER
        .comment("Maximum lift at a slide anchor, in blocks.")
        .defineInRange("maxSlideLift", 4.0, 0.5, 16.0)

    val DISABLE_SLIDE_ANGLE_LIMIT: ModConfigSpec.BooleanValue = BUILDER
        .comment("Remove bezier curve angle limits for water slides.")
        .define("disableSlideCurveAngleLimit", true)

    val SLIDE_FRICTION: ModConfigSpec.DoubleValue = BUILDER
        .comment("Horizontal friction while the player is on a water slide. Lower = faster.")
        .defineInRange("slideFriction", 0.88, 0.0, 1.0)

    val ENTRANCE_BOOST: ModConfigSpec.DoubleValue = BUILDER
        .comment("Extra velocity applied when entering a water slide, blocks/tick.")
        .defineInRange("entranceBoost", 0.35, 0.0, 2.0)

    val MAX_SECTORS: ModConfigSpec.IntValue = BUILDER
        .comment("Maximum number of sectors per water slide curve.")
        .defineInRange("maxSectors", 16, 2, 64)

    val SECTOR_BORDER_PX: ModConfigSpec.IntValue = BUILDER
        .comment("Border size in pixels used for 9-slice tiling of block textures on sectors.")
        .defineInRange("sectorBorderPx", 2, 0, 8)

    val SLIDE_WATER_FRICTION: ModConfigSpec.DoubleValue = BUILDER
        .comment("Friction multiplier inside water-filled slide pipes.")
        .defineInRange("slideWaterFriction", 0.03, 0.0, 1.0)

    val WATER_SIM_PARTICLES: ModConfigSpec.IntValue = BUILDER
        .comment("Particles sampled per water source anchor for the server water simulation.")
        .defineInRange("waterSimParticleCount", 64, 8, 256)

    val WATER_SIM_MAX_BLOCKS: ModConfigSpec.DoubleValue = BUILDER
        .comment("Maximum particle path length in the server water simulation.")
        .defineInRange("waterSimMaxBlocks", 512.0, 64.0, 2048.0)

    val WATER_SIM_COOLDOWN_TICKS: ModConfigSpec.IntValue = BUILDER
        .comment("Minimum ticks between server water simulation recalculations.")
        .defineInRange("waterSimCooldownTicks", 100, 20, 600)

    val WATER_SEGMENT_LENGTH: ModConfigSpec.DoubleValue = BUILDER
        .comment("Length of one watered slide sub-segment, in blocks.")
        .defineInRange("waterSegmentLength", 0.5, 0.25, 4.0)

    val WATER_DRAIN_RATE_MB: ModConfigSpec.DoubleValue = BUILDER
        .comment("Water consumed per second while a slide is watered, in millibuckets.")
        .defineInRange("waterDrainRateMbPerSecond", 2.0, 0.0, 1000.0)

    val ANCHOR_FLUID_CAPACITY: ModConfigSpec.IntValue = BUILDER
        .comment("Water capacity of a slide anchor, in millibuckets.")
        .defineInRange("anchorFluidCapacity", 1000, 1, 10000)

    val SLIDE_MAX_ENTRY_SPEED: ModConfigSpec.DoubleValue = BUILDER
        .comment("Maximum entry speed for slide trajectory computation, in blocks/second.")
        .defineInRange("slideMaxEntrySpeedBlocksPerSecond", 30.0, 1.0, 100.0)

    val SLIDE_SAMPLE_SPACING: ModConfigSpec.DoubleValue = BUILDER
        .comment("Trajectory sample spacing along the slide spine, in blocks.")
        .defineInRange("slideTrajectorySampleSpacing", 0.5, 0.1, 4.0)

    val SLIDE_MAX_PATH_BLOCKS: ModConfigSpec.DoubleValue = BUILDER
        .comment("Maximum slide path length for one trajectory, in blocks.")
        .defineInRange("slideMaxPathBlocks", 512.0, 16.0, 8192.0)

    val SLIDE_MAX_TRAJECTORY_SAMPLES: ModConfigSpec.IntValue = BUILDER
        .comment("Maximum number of trajectory samples sent to the client.")
        .defineInRange("slideMaxTrajectorySamples", 4096, 64, 32768)

    val SPEC: ModConfigSpec = BUILDER.build()

    fun defaultSlideRadius(): Float = clampSlideRadius(DEFAULT_SLIDE_RADIUS.get().toFloat())

    fun maxSlideLift(): Float = MAX_SLIDE_LIFT.get().toFloat().coerceIn(0.5f, 16f)

    fun disableSlideCurveAngleLimit(): Boolean = DISABLE_SLIDE_ANGLE_LIMIT.get()

    fun clampSlideRadius(value: Float): Float {
        val min = MIN_SLIDE_RADIUS.get().toFloat()
        val max = MAX_SLIDE_RADIUS.get().toFloat()
        return value.coerceIn(minOf(min, max), maxOf(min, max))
    }

    fun slideFriction(): Double = SLIDE_FRICTION.get().coerceIn(0.0, 1.0)

    fun entranceBoost(): Double = ENTRANCE_BOOST.get().coerceIn(0.0, 5.0)

    fun maxSectors(): Int = MAX_SECTORS.get().coerceIn(2, 128)

    fun sectorBorderPx(): Int = SECTOR_BORDER_PX.get().coerceIn(0, 16)

    fun slideWaterFriction(): Double = SLIDE_WATER_FRICTION.get().coerceIn(0.0, 1.0)

    fun waterSimParticleCount(): Int = WATER_SIM_PARTICLES.get().coerceIn(8, 256)

    fun waterSimMaxBlocks(): Double = WATER_SIM_MAX_BLOCKS.get().coerceIn(64.0, 2048.0)

    fun waterSimCooldownTicks(): Int = WATER_SIM_COOLDOWN_TICKS.get().coerceIn(20, 600)

    fun waterSegmentLength(): Float = WATER_SEGMENT_LENGTH.get().toFloat().coerceIn(0.25f, 4.0f)

    fun waterDrainRateMbPerSecond(): Double = WATER_DRAIN_RATE_MB.get().coerceIn(0.0, 1000.0)

    fun anchorFluidCapacity(): Int = ANCHOR_FLUID_CAPACITY.get().coerceIn(1, 10000)

    fun slideMaxEntrySpeed(): Double = SLIDE_MAX_ENTRY_SPEED.get().coerceIn(1.0, 100.0)

    fun slideTrajectorySampleSpacing(): Double = SLIDE_SAMPLE_SPACING.get().coerceIn(0.1, 4.0)

    fun slideMaxPathBlocks(): Double = SLIDE_MAX_PATH_BLOCKS.get().coerceIn(16.0, 8192.0)

    fun slideMaxTrajectorySamples(): Int = SLIDE_MAX_TRAJECTORY_SAMPLES.get().coerceIn(64, 32768)

    @Suppress("DEPRECATION")
    fun register() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.COMMON, SPEC)
    }
}
