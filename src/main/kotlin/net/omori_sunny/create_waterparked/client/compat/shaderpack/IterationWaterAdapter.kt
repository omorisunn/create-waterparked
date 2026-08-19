package net.omori_sunny.create_waterparked.client.compat.shaderpack

/**
 * Iteration (iterationRP): its own material system, water is classified
 * directly as `mc_Entity.x == 6000.0` in the clrwl translucent vertex
 * program. The pack ships a dedicated `PROGRAM_COLORWHEEL` vertex path that
 * keeps reading mc_Entity, so stamping is sufficient — no source injection
 * needed.
 *
 * We stamp 12000 (not 6000) so the patched pack (IterationRPPatcher) can
 * identify OUR water explicitly and map it to the pack's own MATID_WATER (6),
 * i.e. vanilla-water behavior, while 6000 water keeps working unchanged.
 * 12000 sits in a range the pack never assigns (block ids <= 10000, specials
 * in the 6000/8000 families).
 */
object IterationWaterAdapter : ShaderpackWaterAdapter {
    override val waterStampId: Int = 12000

    override fun matches(packName: String): Boolean =
        packName.contains("iteration", ignoreCase = true) ||
            packName.contains("itrp", ignoreCase = true)
}