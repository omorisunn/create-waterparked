package net.omori_sunny.create_waterparked.content.attachment

import com.simibubi.create.AllBlocks
import com.simibubi.create.content.kinetics.base.KineticBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.omori_sunny.create_waterparked.CreateWaterparked

// BE of the binding block: a kinetic block entity (shaft-driven along the
// block axis) that holds the attachment entry (slide position + subclass
// data) and the support-style material. The client reads the same data for
// rendering.
class SlideAttachmentBlockEntity(pos: BlockPos, state: BlockState) : KineticBlockEntity(
    (state.block as? SlideAttachmentBlock)?.type()?.blockEntityType?.get(), pos, state
) {

    var entry: SlideAttachmentEntry? = null
        private set

    private var behaviour: SlideAttachment? = null

    // material skin, support beam/bracket style: default copycat base
    var attachmentMaterial: BlockState = AllBlocks.COPYCAT_BASE.get().defaultBlockState()
        private set
    private var attachmentMaterialItem: ItemStack = ItemStack.EMPTY

    fun type(): SlideAttachmentType? = (blockState.block as? SlideAttachmentBlock)?.type()

    fun attachment(): SlideAttachment? {
        if (behaviour != null) return behaviour
        val e = entry ?: return null
        val t = SlideAttachmentTypes.byTypeIdString(e.typeId) ?: return null
        behaviour = t.attachmentFactory(t, e)
        return behaviour
    }

    fun bind(entry: SlideAttachmentEntry) {
        this.entry = entry
        this.behaviour = null
        if (level != null && !level!!.isClientSide) {
            SlideAttachmentManager.register(this)
        }
        notifyBlockUpdated()
    }

    // ---- material, mirrored from the anchor support parts ----

    fun setAttachmentMaterial(material: BlockState, consumed: ItemStack) {
        attachmentMaterial = material
        attachmentMaterialItem = consumed.copyWithCount(1)
        setChanged()
        notifyBlockUpdated()
    }

    fun resetAttachmentMaterial(): ItemStack {
        val returned = attachmentMaterialItem
        attachmentMaterial = AllBlocks.COPYCAT_BASE.get().defaultBlockState()
        attachmentMaterialItem = ItemStack.EMPTY
        setChanged()
        notifyBlockUpdated()
        return returned
    }

    fun hasCustomMaterial(): Boolean =
        !AllBlocks.COPYCAT_BASE.has(attachmentMaterial)

    // ---- lifecycle ----

    override fun tick() {
        super.tick()
        if (level is ServerLevel) {
            attachment()?.serverTick(level as ServerLevel, this)
        }
    }

    override fun onLoad() {
        super.onLoad()
        if (level is ServerLevel) {
            SlideAttachmentManager.register(this)
        } else if (level != null && level!!.isClientSide) {
            net.omori_sunny.create_waterparked.client.attachment.SlideAttachmentClientIndex.add(this)
        }
    }

    // SmartBlockEntity#setRemoved is final and routes through invalidate()
    override fun invalidate() {
        super.invalidate()
        if (level is ServerLevel) {
            SlideAttachmentManager.unregister(this)
        } else if (level != null && level!!.isClientSide) {
            net.omori_sunny.create_waterparked.client.attachment.SlideAttachmentClientIndex.remove(this)
        }
    }

    // ---- NBT: write/read(tag, registries, clientPacket) serve disk AND
    // client sync (SmartBlockEntity routes saveAdditional/readClient into them)

    override fun write(tag: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
        super.write(tag, registries, clientPacket)
        entry?.let { it.write(tag) }
        if (hasCustomMaterial() || !attachmentMaterialItem.isEmpty) {
            tag.put("AttachmentMaterial", NbtUtils.writeBlockState(attachmentMaterial))
            tag.put("AttachmentMaterialItem", attachmentMaterialItem.save(registries))
        }
    }

    override fun read(tag: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
        super.read(tag, registries, clientPacket)
        val loaded = runCatching { SlideAttachmentEntry.read(tag) }
            .onFailure { CreateWaterparked.LOGGER.warn("Failed to read slide attachment at {}", blockPos, it) }
            .getOrNull()
        entry = if (loaded != null && SlideAttachmentTypes.byTypeIdString(loaded.typeId) != null) {
            behaviour = null
            loaded
        } else null
        if (tag.contains("AttachmentMaterial")) {
            attachmentMaterial = NbtUtils.readBlockState(
                registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                tag.getCompound("AttachmentMaterial")
            )
            attachmentMaterialItem = ItemStack.parse(registries, tag.getCompound("AttachmentMaterialItem"))
                .orElse(ItemStack.EMPTY)
        } else {
            attachmentMaterial = AllBlocks.COPYCAT_BASE.get().defaultBlockState()
            attachmentMaterialItem = ItemStack.EMPTY
        }
        // the placement packet is what carries a fresh entry to an already
        // loaded client BE - make sure it reaches the render index
        if (clientPacket && entry != null && level != null && level!!.isClientSide) {
            net.omori_sunny.create_waterparked.client.attachment.SlideAttachmentClientIndex.add(this)
        }
    }

    fun notifyBlockUpdated() {
        if (level != null && !level!!.isClientSide) {
            setChanged()
            @Suppress("DEPRECATION")
            level!!.sendBlockUpdated(blockPos, blockState, blockState, 3)
        }
    }
}
