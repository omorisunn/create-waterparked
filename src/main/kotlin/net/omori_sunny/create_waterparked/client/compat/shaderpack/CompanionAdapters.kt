package net.omori_sunny.create_waterparked.client.compat.shaderpack

/**
 * Fallback adapter for unknown / Iris-default packs. Complementary-style
 * 32000 id is the Iris-ecosystem convention; no source injection (those packs
 * typically compute mat themselves).
 */
object GenericWaterAdapter : ShaderpackWaterAdapter {
    override val waterStampId: Int = 32000
    override fun matches(packName: String): Boolean = true
}

/**
 * Complementary-family packs: classify water as mat = int(mc_Entity.x + 0.5)
 * in [32000, 32004). They assign mat themselves, so no source injection.
 */
object ComplementaryWaterAdapter : ShaderpackWaterAdapter {
    override val waterStampId: Int = 32000
    override fun matches(packName: String): Boolean =
        packName.contains("Complementary", ignoreCase = true)
}

/**
 * Photon: material_mask = mc_Entity.x - 10000, water == 1 ->
 * mc_Entity.x == 10001. Assigns mat itself; no injection.
 */
object PhotonWaterAdapter : ShaderpackWaterAdapter {
    override val waterStampId: Int = 10001
    override fun matches(packName: String): Boolean =
        packName.contains("photon", ignoreCase = true)
}