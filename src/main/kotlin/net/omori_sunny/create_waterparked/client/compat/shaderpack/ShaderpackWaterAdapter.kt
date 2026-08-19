package net.omori_sunny.create_waterparked.client.compat.shaderpack

/**
 * One adapter per shaderpack family. Each knows how to make the mounted tube
 * water + thrown stream render through THE PACK'S OWN water program:
 *
 *  - [waterStampId]: per-vertex entity id stamped onto water meshes
 *    (ColorwheelWaterEntityMixin). Packs read it as mc_Entity and classify
 *    water, but each family uses its own id convention.
 *  - [matches]: whether this adapter owns a given Iris shaderPack name.
 *  - [injectWaterMat]: for packs whose water classification is dropped by
 *    Colorwheel's transform patcher (the vertex code declares `mat` but never
 *    assigns it), re-inject the classification into the clrwl vertex source.
 *    Returns the rewritten source, or null when this source should be left
 *    untouched.
 */
interface ShaderpackWaterAdapter {

    val waterStampId: Int

    val injectsWaterMat: Boolean
        get() = false

    fun matches(packName: String): Boolean

    fun injectWaterMat(source: String): String? = null
}