package net.omori_sunny.create_waterparked.client.compat.shaderpack

import net.omori_sunny.create_waterparked.client.compat.IrisColorwheelCompat

/**
 * Registry of the supported shaderpack adapters. Kept in one place so the
 * stamp mixin, the source-injection mixin and the client config all resolve
 * the same adapter for the currently active shaderpack.
 */
object ShaderpackWaterAdapters {

    @JvmStatic
    val ALL: List<ShaderpackWaterAdapter> = listOf(
        BslWaterAdapter,
        ComplementaryWaterAdapter,
        PhotonWaterAdapter,
        IterationWaterAdapter
    )

    @JvmStatic
    fun resolve(packName: String?): ShaderpackWaterAdapter? {
        if (packName == null) return null
        return ALL.firstOrNull { it.matches(packName) }
    }

    @JvmStatic
    fun active(): ShaderpackWaterAdapter? = resolve(IrisColorwheelCompat.shaderpackName())

    /** Active adapter, falling back to the generic 32000 convention. */
    @JvmStatic
    fun activeOrGeneric(): ShaderpackWaterAdapter = active() ?: GenericWaterAdapter
}