package net.omori_sunny.create_waterparked.config
// Shared config: slide defaults and per-curve ghost block limits.

import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec
import java.util.function.Predicate

object ModConfig {

    private val BUILDER = ModConfigSpec.Builder()

    lateinit var SLIDE_FRICTION: ModConfigSpec.DoubleValue
    lateinit var ENTRANCE_BOOST: ModConfigSpec.DoubleValue
    lateinit var SLIDE_MAX_ENTRY_SPEED: ModConfigSpec.DoubleValue
    lateinit var SLIDE_SAMPLE_SPACING: ModConfigSpec.DoubleValue
    lateinit var SLIDE_MAX_TRAJECTORY_SAMPLES: ModConfigSpec.IntValue
    lateinit var DEFAULT_SLIDE_RADIUS: ModConfigSpec.DoubleValue
    lateinit var MIN_SLIDE_RADIUS: ModConfigSpec.DoubleValue
    lateinit var MAX_SLIDE_RADIUS: ModConfigSpec.DoubleValue
    lateinit var MAX_SLIDE_LIFT: ModConfigSpec.DoubleValue
    lateinit var MAX_SECTORS: ModConfigSpec.IntValue
    lateinit var MAX_GHOST_BLOCKS_PER_CURVE: ModConfigSpec.IntValue
    lateinit var SECTOR_BORDER_PX: ModConfigSpec.IntValue
    lateinit var DISABLE_SLIDE_ANGLE_LIMIT: ModConfigSpec.BooleanValue

    lateinit var SPEC: ModConfigSpec

    private val SERVER_BUILDER = ModConfigSpec.Builder()

    lateinit var SLIDE_WATER_FRICTION: ModConfigSpec.DoubleValue
    lateinit var WATER_SIM_PARTICLES: ModConfigSpec.IntValue
    lateinit var WATER_SIM_MAX_BLOCKS: ModConfigSpec.DoubleValue
    lateinit var WATER_SIM_COOLDOWN_TICKS: ModConfigSpec.IntValue
    lateinit var WATER_SEGMENT_LENGTH: ModConfigSpec.DoubleValue
    lateinit var WATER_DRAIN_RATE_MB: ModConfigSpec.DoubleValue
    lateinit var ANCHOR_FLUID_CAPACITY: ModConfigSpec.IntValue
    lateinit var SLIDE_MAX_TRAJECTORY_BLOCKS: ModConfigSpec.DoubleValue
    lateinit var SLIDE_CANCEL_COOLDOWN_TICKS: ModConfigSpec.IntValue
    lateinit var SUB_LEVEL_SLIDE: ModConfigSpec.BooleanValue
    lateinit var WALL_THICKNESS: ModConfigSpec.DoubleValue
    lateinit var ACCELERATOR_STRESS_IMPACT: ModConfigSpec.DoubleValue
    lateinit var ACCELERATOR_BASE_SPEED: ModConfigSpec.DoubleValue
    lateinit var ACCELERATOR_REFERENCE_RPM: ModConfigSpec.DoubleValue
    lateinit var ROLLER_CONVEYOR_STRESS_IMPACT: ModConfigSpec.DoubleValue
    lateinit var ROLLER_DECK_MAX_LENGTH: ModConfigSpec.IntValue
    lateinit var ROLLER_DECK_SPEED_LIMIT: ModConfigSpec.DoubleValue
    lateinit var ROLLER_HINGE_MAX_LENGTH: ModConfigSpec.IntValue
    lateinit var ROLLER_HINGE_MAX_ANGLE: ModConfigSpec.DoubleValue
    lateinit var ROLLER_HINGE_SLIDE_SCALE: ModConfigSpec.DoubleValue
    lateinit var ROLLER_DECK_CARRY_EXEMPT_ENTITIES: ModConfigSpec.ConfigValue<List<String>>
    lateinit var ROLLER_DECK_SYNC_INTERVAL: ModConfigSpec.IntValue
    lateinit var ROLLER_DECK_CORRECTION_THRESHOLD: ModConfigSpec.DoubleValue
    lateinit var ROLLER_DECK_CORRECTION_SPEED: ModConfigSpec.DoubleValue
    lateinit var GRAB_DISTANCE: ModConfigSpec.DoubleValue
    lateinit var GRAB_CHARGE_TICKS: ModConfigSpec.IntValue

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
        MAX_GHOST_BLOCKS_PER_CURVE = BUILDER
            .comment("Maximum number of fake (ghost) blocks attached to one water slide curve.")
            .defineInRange("maxGhostBlocksPerCurve", 64, 1, 512)
        SECTOR_BORDER_PX = BUILDER
            .comment("Border size in pixels used for 9-slice tiling of block textures on sectors.")
            .defineInRange("sectorBorderPx", 2, 0, 8)
        DISABLE_SLIDE_ANGLE_LIMIT = BUILDER
            .comment("Remove bezier curve angle limits for water slides.")
            .define("disableSlideCurveAngleLimit", true)
        BUILDER.pop()

        // a client walks its own copy of a load to the place a keyframe gave it; that walk is a slide, and this is
        // how fast it goes. It is a client side knob, so it lives in the common spec and never syncs.
        BUILDER.push("roller_conveyor")
        ROLLER_DECK_CORRECTION_SPEED = BUILDER
            .comment(
                "How fast a client walks a load to the position a keyframe gave it, in blocks per tick. A correction is a slide, never a jump, and it never exceeds the run's own speed limit."
            )
            .defineInRange("deckCorrectionSpeed", 0.25, 0.05, 1.0)
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
            .defineInRange("waterDrainRate", 50.0, 0.0, 1000.0)
        ANCHOR_FLUID_CAPACITY = SERVER_BUILDER
            .comment("Water capacity of a slide anchor, in millibuckets.")
            .defineInRange("anchorFluidCapacity", 1000, 1, 10000)
        WALL_THICKNESS = SERVER_BUILDER
            .comment("Pipe wall thickness in blocks. Server authoritative, synced to clients.")
            .defineInRange("wallThickness", 0.5, 0.1, 0.5)
        SERVER_BUILDER.pop()

        SERVER_BUILDER.push("slide")
        SLIDE_MAX_TRAJECTORY_BLOCKS = SERVER_BUILDER
            .comment("Maximum total trajectory length for one slide ride, in blocks.")
            .defineInRange("slideMaxTrajectoryBlocks", 1000.0, 50.0, 10000.0)
        SLIDE_CANCEL_COOLDOWN_TICKS = SERVER_BUILDER
            .comment("Cooldown in ticks before a player can start a new slide after cancelling with Shift.")
            .defineInRange("slideCancelCooldownTicks", 20, 0, 200)
        // heavy feature: on for singleplayer, off for dedicated servers
        SUB_LEVEL_SLIDE = SERVER_BUILDER
            .comment(
                "Whole Sable sub-levels can ride water slides. Enabled by default on integrated (singleplayer/LAN) servers, disabled by default on dedicated servers for performance - set to true in create_waterparked-server.toml to enable."
            )
            .define(
                "subLevelSlideRiding",
                net.neoforged.fml.loading.FMLEnvironment.dist != net.neoforged.api.distmarker.Dist.DEDICATED_SERVER
            )
        SERVER_BUILDER.pop()

        SERVER_BUILDER.push("accelerator")
        ACCELERATOR_STRESS_IMPACT = SERVER_BUILDER
            .comment("Stress units per RPM consumed by a slide accelerator.")
            .defineInRange("acceleratorStressImpact", 8.0, 0.0, 64.0)
        ACCELERATOR_BASE_SPEED = SERVER_BUILDER
            .comment("Speed a slide accelerator adds at the reference RPM, in blocks per second.")
            .defineInRange("acceleratorBaseSpeed", 4.0, 0.5, 32.0)
        ACCELERATOR_REFERENCE_RPM = SERVER_BUILDER
            .comment("RPM at which a slide accelerator adds its base speed; faster rotation scales up linearly.")
            .defineInRange("acceleratorReferenceRpm", 16.0, 1.0, 256.0)
        SERVER_BUILDER.pop()

        SERVER_BUILDER.push("roller_conveyor")
        ROLLER_CONVEYOR_STRESS_IMPACT = SERVER_BUILDER
            .comment("Stress units per RPM consumed by one roller conveyor block of a deck.")
            .defineInRange("rollerConveyorStressImpact", 1.0, 0.0, 64.0)
        ROLLER_HINGE_MAX_LENGTH = SERVER_BUILDER
            .comment("Longest roller conveyor run that a shaft may turn into a tilting sub-level.")
            .defineInRange("hingeMaxLength", 16, 1, 64)
        ROLLER_DECK_MAX_LENGTH = SERVER_BUILDER
            .comment("Longest roller conveyor run a deck walks and carries loads along, apart from the hinge limit.")
            .defineInRange("deckMaxLength", 64, 1, 256)
        ROLLER_DECK_SPEED_LIMIT = SERVER_BUILDER
            .comment("Fastest a load may ride along a deck, in blocks per tick; 0.5 is about ten blocks a second.")
            .defineInRange("deckSpeedLimit", 0.5, 0.1, 1.0)
        ROLLER_HINGE_MAX_ANGLE = SERVER_BUILDER
            .comment("Degrees a hinged run may tilt before it stops following its shaft; 0 keeps it free.")
            .defineInRange("hingeMaxAngleDegrees", 0.0, 0.0, 360.0)
        ROLLER_HINGE_SLIDE_SCALE = SERVER_BUILDER
            .comment("How strongly main world gravity pulls a load down a tilted deck.")
            .defineInRange("hingeSlideScale", 1.0, 0.0, 8.0)
        ROLLER_DECK_CARRY_EXEMPT_ENTITIES = SERVER_BUILDER
            .comment(
                "Entity ids the deck never carries. The deck only takes over entity passengers; a floating contraption or sub-level runs on its own physics next to the deck. Add an entity id here when one of them has to be exempt."
            )
            .defineListAllowEmpty("carryExemptEntities", emptyList<String>(), null, Predicate { it is String })
        ROLLER_DECK_SYNC_INTERVAL = SERVER_BUILDER
            .comment(
                "Ticks between two authoritative keyframes of a deck's loads. Every real event still syncs at once; the path a client plays back covers two intervals of samples, so this is capped at 40 ticks and every supported setting keeps the whole window covered. A client plays half a window behind the anchor (about a second of phase at the default); a quarter window is snappier."
            )
            .defineInRange("deckSyncIntervalTicks", 20, 5, 40)
        ROLLER_DECK_CORRECTION_THRESHOLD = SERVER_BUILDER
            .comment(
                "How far a client's own load may sit from the keyframe before it is put where the keyframe says instead of being smoothed there, in blocks."
            )
            .defineInRange("deckCorrectionThreshold", 0.5, 0.05, 4.0)
        SERVER_BUILDER.pop()

        SERVER_BUILDER.push("grab_bar")
        GRAB_DISTANCE = SERVER_BUILDER
            .comment("Distance in blocks in front of a slide grab bar at which an entity takes hold.")
            .defineInRange("grabDistance", 1.5, 0.5, 8.0)
        GRAB_CHARGE_TICKS = SERVER_BUILDER
            .comment("Ticks of holding forward needed to fill the grab bar charge and start the slide.")
            .defineInRange("grabChargeTicks", 20, 5, 200)
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

    fun maxGhostBlocksPerCurve(): Int = MAX_GHOST_BLOCKS_PER_CURVE.safeGet(SPEC).coerceIn(1, 512)

    fun sectorBorderPx(): Int = SECTOR_BORDER_PX.safeGet(SPEC).coerceIn(0, 16)

    fun slideMaxEntrySpeed(): Double = SLIDE_MAX_ENTRY_SPEED.safeGet(SPEC).coerceIn(1.0, 100.0)

    fun slideTrajectorySampleSpacing(): Double = SLIDE_SAMPLE_SPACING.safeGet(SPEC).coerceIn(0.1, 4.0)

    fun slideMaxTrajectorySamples(): Int = SLIDE_MAX_TRAJECTORY_SAMPLES.safeGet(SPEC).coerceIn(64, 32768)

    fun slideWaterFriction(): Double = SLIDE_WATER_FRICTION.safeGet(SERVER_SPEC).coerceIn(0.0, 1.0)

    fun waterSimParticleCount(): Int = WATER_SIM_PARTICLES.safeGet(SERVER_SPEC).coerceIn(1, 256)

    fun waterSimMaxBlocks(): Double = WATER_SIM_MAX_BLOCKS.safeGet(SERVER_SPEC).coerceIn(64.0, 2048.0)

    fun waterSimCooldownTicks(): Int = WATER_SIM_COOLDOWN_TICKS.safeGet(SERVER_SPEC).coerceIn(20, 600)

    fun waterSegmentLength(): Float = WATER_SEGMENT_LENGTH.safeGet(SERVER_SPEC).toFloat().coerceIn(0.25f, 4.0f)

    fun waterDrainRate(): Double = WATER_DRAIN_RATE_MB.safeGet(SERVER_SPEC).coerceIn(0.0, 1000.0)

    fun anchorFluidCapacity(): Int = ANCHOR_FLUID_CAPACITY.safeGet(SERVER_SPEC).coerceIn(1, 10000)

    fun slideMaxTrajectoryBlocks(): Double = SLIDE_MAX_TRAJECTORY_BLOCKS.safeGet(SERVER_SPEC).coerceIn(50.0, 10000.0)

    fun slideCancelCooldownTicks(): Int = SLIDE_CANCEL_COOLDOWN_TICKS.safeGet(SERVER_SPEC).coerceIn(0, 200)

    fun subLevelSlideRiding(): Boolean = SUB_LEVEL_SLIDE.safeGet(SERVER_SPEC)

    fun wallThickness(): Float = WALL_THICKNESS.safeGet(SERVER_SPEC).toFloat().coerceIn(0.1f, 0.5f)

    fun acceleratorStressImpact(): Double =
        ACCELERATOR_STRESS_IMPACT.safeGet(SERVER_SPEC).coerceIn(0.0, 64.0)

    fun rollerConveyorStressImpact(): Double =
        ROLLER_CONVEYOR_STRESS_IMPACT.safeGet(SERVER_SPEC).coerceIn(0.0, 64.0)

    fun rollerHingeMaxLength(): Int = ROLLER_HINGE_MAX_LENGTH.safeGet(SERVER_SPEC).coerceIn(1, 64)

    // a deck walks its own chain up to this many segments, whatever the hinge limit says
    fun rollerDeckMaxLength(): Int = ROLLER_DECK_MAX_LENGTH.safeGet(SERVER_SPEC).coerceIn(1, 256)

    // the one speed a landing seed, a carried load and the rollers all read
    fun rollerDeckSpeedLimit(): Float = ROLLER_DECK_SPEED_LIMIT.safeGet(SERVER_SPEC).coerceIn(0.1, 1.0).toFloat()

    // how fast a client walks a load to the position a keyframe gave it: never below a slow slide, never above
    // the run's own speed limit, so a correction can only ever be gentler than the belt itself
    fun rollerDeckCorrectionSpeed(): Float =
        ROLLER_DECK_CORRECTION_SPEED.safeGet(SPEC).coerceIn(0.05, rollerDeckSpeedLimit().toDouble()).toFloat()

    fun rollerHingeMaxAngleDegrees(): Double = ROLLER_HINGE_MAX_ANGLE.safeGet(SERVER_SPEC).coerceIn(0.0, 360.0)

    fun rollerHingeSlideScale(): Double = ROLLER_HINGE_SLIDE_SCALE.safeGet(SERVER_SPEC).coerceIn(0.0, 8.0)

    // how often a run hands its clients an authoritative keyframe of the loads it carries
    fun rollerDeckSyncIntervalTicks(): Int = ROLLER_DECK_SYNC_INTERVAL.safeGet(SERVER_SPEC).coerceIn(5, 40)

    // the gap at which a client's own load is put where the keyframe says, instead of being smoothed there
    fun rollerDeckCorrectionThreshold(): Double =
        ROLLER_DECK_CORRECTION_THRESHOLD.safeGet(SERVER_SPEC).coerceIn(0.05, 4.0)

    // the entity ids the deck never carries, trimmed and lowercased so a hand written entry still matches
    fun rollerDeckCarryExemptEntities(): List<String> {
        val raw = ROLLER_DECK_CARRY_EXEMPT_ENTITIES.safeGet(SERVER_SPEC)
        if (raw.isEmpty()) return emptyList()
        return raw.mapNotNull { it?.trim()?.lowercase() }.filter { it.isNotEmpty() }
    }

    fun acceleratorBaseSpeed(): Double = ACCELERATOR_BASE_SPEED.safeGet(SERVER_SPEC).coerceIn(0.5, 32.0)

    fun acceleratorReferenceRpm(): Double =
        ACCELERATOR_REFERENCE_RPM.safeGet(SERVER_SPEC).coerceIn(1.0, 256.0)

    fun grabDistance(): Double = GRAB_DISTANCE.safeGet(SERVER_SPEC).coerceIn(0.5, 8.0)

    fun grabChargeTicks(): Int = GRAB_CHARGE_TICKS.safeGet(SERVER_SPEC).coerceIn(5, 200)

    @Suppress("DEPRECATION")
    fun register() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.COMMON, SPEC)
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC)
    }
}
