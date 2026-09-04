package net.omori_sunny.create_waterparked.config

import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec

object ModConfig {

    // common config (both sides)
    private val BUILDER = ModConfigSpec.Builder()

    // slide
    lateinit var SLIDE_FRICTION: ModConfigSpec.DoubleValue
    lateinit var ENTRANCE_BOOST: ModConfigSpec.DoubleValue
    lateinit var SLIDE_MAX_ENTRY_SPEED: ModConfigSpec.DoubleValue
    lateinit var SLIDE_SAMPLE_SPACING: ModConfigSpec.DoubleValue
    lateinit var SLIDE_MAX_TRAJECTORY_SAMPLES: ModConfigSpec.IntValue
    // anchor
    lateinit var DEFAULT_SLIDE_RADIUS: ModConfigSpec.DoubleValue
    lateinit var MIN_SLIDE_RADIUS: ModConfigSpec.DoubleValue
    lateinit var MAX_SLIDE_RADIUS: ModConfigSpec.DoubleValue
    lateinit var MAX_SLIDE_LIFT: ModConfigSpec.DoubleValue
    lateinit var MAX_SECTORS: ModConfigSpec.IntValue
    lateinit var SECTOR_BORDER_PX: ModConfigSpec.IntValue
    lateinit var DISABLE_SLIDE_ANGLE_LIMIT: ModConfigSpec.BooleanValue

    lateinit var SPEC: ModConfigSpec

    // server only config
    private val SERVER_BUILDER = ModConfigSpec.Builder()

    // water
    lateinit var SLIDE_WATER_FRICTION: ModConfigSpec.DoubleValue
    lateinit var WATER_SIM_PARTICLES: ModConfigSpec.IntValue
    lateinit var WATER_SIM_MAX_BLOCKS: ModConfigSpec.DoubleValue
    lateinit var WATER_SIM_COOLDOWN_TICKS: ModConfigSpec.IntValue
    lateinit var WATER_SEGMENT_LENGTH: ModConfigSpec.DoubleValue
    lateinit var WATER_DRAIN_RATE_MB: ModConfigSpec.DoubleValue
    lateinit var ANCHOR_FLUID_CAPACITY: ModConfigSpec.IntValue
    // slide
    lateinit var SLIDE_MAX_TRAJECTORY_BLOCKS: ModConfigSpec.DoubleValue
    lateinit var SLIDE_CANCEL_COOLDOWN_TICKS: ModConfigSpec.IntValue

    lateinit var SERVER_SPEC: ModConfigSpec

    init {
        BUILDER.push("slide")
        SLIDE_FRICTION = BUILDER
            .comment("Horizontal friction while the player is on a water slide. Lower = faster.")
            .defineInRange("slideFriction", 0.88, 0.0, 1.0)
        ENTRANCE_BOOST = BUILDER
            .comment("Extra velocity applied when entering a water slide, blocks/tick.")
            .defineInRange("entranceBoost", 0.35, 0.0, 2.0)
        SLIDE_MAX_ENTRY_SPEED = BUILDER
            .comment("Maximum entry speed for slide trajectory computation, in blocks/second.")
            .defineInRange("slideMaxEntrySpeedBlocksPerSecond", 30.0, 1.0, 100.0)
        SLIDE_SAMPLE_SPACING = BUILDER
            .comment("Trajectory sample spacing along the slide spine, in blocks.")
            .defineInRange("slideTrajectorySampleSpacing", 0.5, 0.1, 4.0)
        SLIDE_MAX_TRAJECTORY_SAMPLES = BUILDER
            .comment("Maximum number of trajectory samples sent to the client.")
            .defineInRange("slideMaxTrajectorySamples", 4096, 64, 32768)
        BUILDER.pop()

        BUILDER.push("anchor")
        DEFAULT_SLIDE_RADIUS = BUILDER
            .comment("Default opening radius of a new slide anchor, in blocks.")
            .defineInRange("defaultSlideRadius", 1.0, 0.1, 10.0)
        MIN_SLIDE_RADIUS = BUILDER
            .comment("Minimum slide opening radius, in blocks.")
            .defineInRange("minSlideRadius", 0.35, 0.05, 10.0)
        MAX_SLIDE_RADIUS = BUILDER
            .comment("Maximum slide opening radius, in blocks.")
            .defineInRange("maxSlideRadius", 20.0, 0.1, 20.0)
        MAX_SLIDE_LIFT = BUILDER
            .comment("Maximum lift at a slide anchor, in blocks.")
            .defineInRange("maxSlideLift", 16.0, 0.5, 16.0)
        MAX_SECTORS = BUILDER
            .comment("Maximum number of sectors per water slide curve.")
            .defineInRange("maxSectors", 16, 2, 64)
        SECTOR_BORDER_PX = BUILDER
            .comment("Border size in pixels used for 9-slice tiling of block textures on sectors.")
            .defineInRange("sectorBorderPx", 2, 0, 8)
        DISABLE_SLIDE_ANGLE_LIMIT = BUILDER
            .comment("Remove bezier curve angle limits for water slides.")
            .define("disableSlideCurveAngleLimit", true)
        BUILDER.pop()

        SPEC = BUILDER.build()

        SERVER_BUILDER.push("water")
        SLIDE_WATER_FRICTION = SERVER_BUILDER
            .comment("Friction multiplier inside water-filled slide pipes.")
            .defineInRange("slideWaterFriction", 0.01, 0.0, 1.0)
        WATER_SIM_PARTICLES = SERVER_BUILDER
            .comment("Particles sampled per water source anchor for the server water simulation.")
            .defineInRange("waterSimParticleCount", 20, 1, 256)
        WATER_SIM_MAX_BLOCKS = SERVER_BUILDER
            .comment("Maximum particle path length in the server water simulation.")
            .defineInRange("waterSimMaxBlocks", 512.0, 64.0, 2048.0)
        WATER_SIM_COOLDOWN_TICKS = SERVER_BUILDER
            .comment("Minimum ticks between server water simulation recalculations.")
            .defineInRange("waterSimCooldownTicks", 20, 20, 600)
        WATER_SEGMENT_LENGTH = SERVER_BUILDER
            .comment("Length of one watered slide sub-segment, in blocks.")
            .defineInRange("waterSegmentLength", 0.5, 0.25, 4.0)
        WATER_DRAIN_RATE_MB = SERVER_BUILDER
            .comment("Water consumed per second while a slide is watered, in millibuckets.")
            .defineInRange("waterDrainRateMbPerSecond", 2.0, 0.0, 1000.0)
        ANCHOR_FLUID_CAPACITY = SERVER_BUILDER
            .comment("Water capacity of a slide anchor, in millibuckets.")
            .defineInRange("anchorFluidCapacity", 1000, 1, 10000)
        SERVER_BUILDER.pop()

        SERVER_BUILDER.push("slide")
        SLIDE_MAX_TRAJECTORY_BLOCKS = SERVER_BUILDER
            .comment("Maximum total trajectory length for one slide ride, in blocks.")
            .defineInRange("slideMaxTrajectoryBlocks", 1000.0, 50.0, 10000.0)
        SLIDE_CANCEL_COOLDOWN_TICKS = SERVER_BUILDER
            .comment("Cooldown in ticks before a player can start a new slide after cancelling with Shift.")
            .defineInRange("slideCancelCooldownTicks", 20, 0, 200)
        SERVER_BUILDER.pop()

        SERVER_SPEC = SERVER_BUILDER.build()
    }

// a never-loaded spec (corrupt or unreadable config file) used to crash with
// IllegalStateException on the first read; degrade to defaults instead
    private var warnedUnloadedSpec = false

    private fun <T> ModConfigSpec.ConfigValue<T>.safeGet(spec: ModConfigSpec): T {
        if (spec.isLoaded) return get()
        if (!warnedUnloadedSpec) {
            warnedUnloadedSpec = true
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.warn(
                "[create_waterparked] a config spec is not loaded (corrupt or unreadable create_waterparked config file?); using built-in defaults"
            )
        }
        return getDefault()
    }

    fun defaultSlideRadius(): Float = clampSlideRadius(DEFAULT_SLIDE_RADIUS.safeGet(SPEC).toFloat())

    fun maxSlideLift(): Float = MAX_SLIDE_LIFT.safeGet(SPEC).toFloat().coerceIn(0.5f, 16f)

    fun disableSlideCurveAngleLimit(): Boolean = DISABLE_SLIDE_ANGLE_LIMIT.safeGet(SPEC)

    fun clampSlideRadius(value: Float): Float {
        val min = MIN_SLIDE_RADIUS.safeGet(SPEC).toFloat()
        val max = MAX_SLIDE_RADIUS.safeGet(SPEC).toFloat()
        return value.coerceIn(minOf(min, max), maxOf(min, max))
    }

    fun slideFriction(): Double = SLIDE_FRICTION.safeGet(SPEC).coerceIn(0.0, 1.0)

    fun entranceBoost(): Double = ENTRANCE_BOOST.safeGet(SPEC).coerceIn(0.0, 5.0)

    fun maxSectors(): Int = MAX_SECTORS.safeGet(SPEC).coerceIn(2, 128)

    fun sectorBorderPx(): Int = SECTOR_BORDER_PX.safeGet(SPEC).coerceIn(0, 16)

    fun slideMaxEntrySpeed(): Double = SLIDE_MAX_ENTRY_SPEED.safeGet(SPEC).coerceIn(1.0, 100.0)

    fun slideTrajectorySampleSpacing(): Double = SLIDE_SAMPLE_SPACING.safeGet(SPEC).coerceIn(0.1, 4.0)

    fun slideMaxTrajectorySamples(): Int = SLIDE_MAX_TRAJECTORY_SAMPLES.safeGet(SPEC).coerceIn(64, 32768)

    fun slideWaterFriction(): Double = SLIDE_WATER_FRICTION.safeGet(SERVER_SPEC).coerceIn(0.0, 1.0)

    fun waterSimParticleCount(): Int = WATER_SIM_PARTICLES.safeGet(SERVER_SPEC).coerceIn(1, 256)

    fun waterSimMaxBlocks(): Double = WATER_SIM_MAX_BLOCKS.safeGet(SERVER_SPEC).coerceIn(64.0, 2048.0)

    fun waterSimCooldownTicks(): Int = WATER_SIM_COOLDOWN_TICKS.safeGet(SERVER_SPEC).coerceIn(20, 600)

    fun waterSegmentLength(): Float = WATER_SEGMENT_LENGTH.safeGet(SERVER_SPEC).toFloat().coerceIn(0.25f, 4.0f)

    fun waterDrainRateMbPerSecond(): Double = WATER_DRAIN_RATE_MB.safeGet(SERVER_SPEC).coerceIn(0.0, 1000.0)

    fun anchorFluidCapacity(): Int = ANCHOR_FLUID_CAPACITY.safeGet(SERVER_SPEC).coerceIn(1, 10000)

    fun slideMaxTrajectoryBlocks(): Double = SLIDE_MAX_TRAJECTORY_BLOCKS.safeGet(SERVER_SPEC).coerceIn(50.0, 10000.0)

    fun slideCancelCooldownTicks(): Int = SLIDE_CANCEL_COOLDOWN_TICKS.safeGet(SERVER_SPEC).coerceIn(0, 200)

    @Suppress("DEPRECATION")
    fun register() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.COMMON, SPEC)
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC)
    }
}
