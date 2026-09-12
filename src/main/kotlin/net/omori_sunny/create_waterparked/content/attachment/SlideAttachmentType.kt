package net.omori_sunny.create_waterparked.content.attachment

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockBehaviour.Properties
import net.minecraft.world.level.material.MapColor
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities
import net.omori_sunny.create_waterparked.content.registry.ModBlocks
import net.omori_sunny.create_waterparked.content.registry.ModItems

// kept in a plain id-keyed map, not a vanilla registry: both sides register the same way
class SlideAttachmentType(
    val id: ResourceLocation,
    val site: SlideAttachmentSite,
    val attachmentFactory: (SlideAttachmentType, SlideAttachmentEntry) -> SlideAttachment,
    val providerFactory: () -> SlideAttachmentModelProvider,
    val trigger: SlideAttachmentTriggerSpec,
    val maxHostDistance: Double,
    val stressImpact: Double,
    val extraBehaviours: ((SlideAttachmentBlockEntity) -> List<BlockEntityBehaviour>)?,
    val block: DeferredBlock<out SlideAttachmentBlock>,
    val blockEntityType: DeferredHolder<BlockEntityType<*>, BlockEntityType<SlideAttachmentBlockEntity>>,
    val item: DeferredItem<out SlideAttachmentBlockItem>
) {
    override fun toString(): String = "SlideAttachmentType($id)"
}

object SlideAttachmentTypes {
    private val byId = LinkedHashMap<ResourceLocation, SlideAttachmentType>()

    fun all(): Collection<SlideAttachmentType> = byId.values

    fun byId(id: ResourceLocation): SlideAttachmentType? = byId[id]

    fun byTypeIdString(typeId: String): SlideAttachmentType? =
        ResourceLocation.tryParse(typeId)?.let { byId[it] }

    internal fun put(type: SlideAttachmentType) {
        byId[type.id] = type
    }
}

class SlideAttachmentSpec internal constructor(
    private val name: String,
    var site: SlideAttachmentSite
) {
    internal var attachmentFactory: ((SlideAttachmentType, SlideAttachmentEntry) -> SlideAttachment)? = null
    internal var providerFactory: (() -> SlideAttachmentModelProvider)? = null
    internal var trigger: SlideAttachmentTriggerSpec = SlideAttachmentTriggerSpec.Custom
    internal var maxHostDistance: Double = 16.0
    internal var stressImpact: Double = 0.0
    internal var extraBehaviours: ((SlideAttachmentBlockEntity) -> List<BlockEntityBehaviour>)? = null
    internal var blockProperties: () -> Properties = {
        Properties.of().mapColor(MapColor.METAL).strength(1.2f).sound(SoundType.METAL).noOcclusion()
    }

    fun attachment(factory: (SlideAttachmentType, SlideAttachmentEntry) -> SlideAttachment): SlideAttachmentSpec {
        attachmentFactory = factory
        return this
    }

    fun provider(factory: () -> SlideAttachmentModelProvider): SlideAttachmentSpec {
        providerFactory = factory
        return this
    }

    fun trigger(spec: SlideAttachmentTriggerSpec): SlideAttachmentSpec {
        trigger = spec
        return this
    }

    // distance in blocks from the binding block to its slide wall point
    fun maxHostDistance(blocks: Double): SlideAttachmentSpec {
        maxHostDistance = blocks
        return this
    }

    // stress units per rpm; the shown impact is multiplied by speed
    fun stressImpact(suPerRpm: Double): SlideAttachmentSpec {
        stressImpact = suPerRpm
        return this
    }

    fun extraBehaviours(factory: (SlideAttachmentBlockEntity) -> List<BlockEntityBehaviour>): SlideAttachmentSpec {
        extraBehaviours = factory
        return this
    }

    fun blockProperties(props: () -> Properties): SlideAttachmentSpec {
        blockProperties = props
        return this
    }
}

object SlideAttachmentRegistry {
    fun register(
        name: String,
        site: SlideAttachmentSite,
        spec: SlideAttachmentSpec.() -> Unit
    ): SlideAttachmentType {
        val builder = SlideAttachmentSpec(name, site).apply(spec)
        val blockName = "${name}_attachment"
        var resolved: SlideAttachmentType? = null
        val block: DeferredBlock<SlideAttachmentBlock> =
            ModBlocks.REGISTRY.register(blockName) { ->
                SlideAttachmentBlock(builder.blockProperties()) { resolved!! }
            }
        val blockEntityType: DeferredHolder<BlockEntityType<*>, BlockEntityType<SlideAttachmentBlockEntity>> =
            ModBlockEntities.REGISTRY.register(blockName) { ->
                @Suppress("UNCHECKED_CAST")
                BlockEntityType.Builder.of(
                    { pos, state -> SlideAttachmentBlockEntity(pos, state) },
                    block.get()
                ).build(null) as BlockEntityType<SlideAttachmentBlockEntity>
            }
        val item: DeferredItem<SlideAttachmentBlockItem> =
            ModItems.REGISTRY.register(blockName) { ->
                SlideAttachmentBlockItem(block, { resolved!! }, Item.Properties())
            }
        val type = SlideAttachmentType(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, name),
            site,
            builder.attachmentFactory ?: error("attachment() missing for $name"),
            builder.providerFactory ?: error("provider() missing for $name"),
            builder.trigger,
            builder.maxHostDistance,
            builder.stressImpact,
            builder.extraBehaviours,
            block,
            blockEntityType,
            item
        )
        resolved = type
        SlideAttachmentTypes.put(type)
        return type
    }
}
