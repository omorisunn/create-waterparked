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

    val SPEC: ModConfigSpec = BUILDER.build()

    fun defaultSlideRadius(): Float = clampSlideRadius(DEFAULT_SLIDE_RADIUS.get().toFloat())

    fun clampSlideRadius(value: Float): Float {
        val min = MIN_SLIDE_RADIUS.get().toFloat()
        val max = MAX_SLIDE_RADIUS.get().toFloat()
        return value.coerceIn(minOf(min, max), maxOf(min, max))
    }

    fun slideFriction(): Double = SLIDE_FRICTION.get().coerceIn(0.0, 1.0)

    fun entranceBoost(): Double = ENTRANCE_BOOST.get().coerceIn(0.0, 5.0)

    fun maxSectors(): Int = MAX_SECTORS.get().coerceIn(2, 128)

    fun sectorBorderPx(): Int = SECTOR_BORDER_PX.get().coerceIn(0, 16)

    @Suppress("DEPRECATION")
    fun register() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.COMMON, SPEC)
    }
}
