package net.omori_sunny.create_waterparked.datagen

import com.google.gson.JsonObject
import net.minecraft.data.CachedOutput
import net.minecraft.data.DataProvider
import net.minecraft.data.PackOutput
import net.minecraft.resources.ResourceLocation
import java.util.concurrent.CompletableFuture

// base for json files in assets/<namespace>
abstract class AssetJsonProvider(
    protected val output: PackOutput,
    protected val modId: String,
    private val providerName: String
) : DataProvider {

    // relative path -> json
    protected abstract fun gather(): Map<ResourceLocation, JsonObject>

    override fun run(cache: CachedOutput): CompletableFuture<*> {
        val futures = gather().map { (loc, json) ->
            val path = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK)
                .resolve("${loc.namespace}/${loc.path}.json")
            DataProvider.saveStable(cache, json, path)
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    override fun getName(): String = providerName
}
