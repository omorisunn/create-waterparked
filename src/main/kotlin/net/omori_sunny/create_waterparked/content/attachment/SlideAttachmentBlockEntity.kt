package net.omori_sunny.create_waterparked.content.attachment

import com.simibubi.create.AllBlocks
import com.simibubi.create.content.kinetics.base.IRotate
import net.createmod.catnip.lang.LangBuilder
import com.simibubi.create.content.kinetics.base.KineticBlockEntity
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.omori_sunny.create_waterparked.CreateWaterparked

// holds the attachment entry; the client reads the same data for rendering
class SlideAttachmentBlockEntity(pos: BlockPos, state: BlockState) : KineticBlockEntity(
    (state.block as? SlideAttachmentBlock)?.type()?.blockEntityType?.get(), pos, state
) {

    var entry: SlideAttachmentEntry? = null
        private set

    private var behaviour: SlideAttachment? = null

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

    override fun addBehaviours(behaviours: MutableList<BlockEntityBehaviour>) {
        super.addBehaviours(behaviours)
        type()?.extraBehaviours?.invoke(this)?.let { behaviours.addAll(it) }
    }

    fun bind(entry: SlideAttachmentEntry) {
        this.entry = entry
        this.behaviour = null
        if (level != null && !level!!.isClientSide) {
            SlideAttachmentManager.register(this)
        }
        notifyBlockUpdated()
    }

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

    // the only teardown hook: removal is final and routes here
    override fun invalidate() {
        super.invalidate()
        if (level is ServerLevel) {
            SlideAttachmentManager.unregister(this)
        } else if (level != null && level!!.isClientSide) {
            net.omori_sunny.create_waterparked.client.attachment.SlideAttachmentClientIndex.remove(this)
        }
    }

    // serves both disk and client sync
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
        if (clientPacket && entry != null && level != null && level!!.isClientSide) {
            net.omori_sunny.create_waterparked.client.attachment.SlideAttachmentClientIndex.add(this)
        }
    }

    // only called while Engineer's Goggles are worn
    override fun addToGoggleTooltip(
        tooltip: MutableList<Component>,
        isPlayerSneaking: Boolean
    ): Boolean {
        LangBuilder(CreateWaterparked.ID)
            .add(blockState.block.name)
            .style(ChatFormatting.GOLD)
            .forGoggles(tooltip)
        attachment()?.addToGoggleTooltip(this, tooltip, isPlayerSneaking)
        val stressAtBase = calculateStressApplied()
        if (IRotate.StressImpact.isEnabled() && !Mth.equal(stressAtBase, 0f)) {
            addStressImpactStats(tooltip, stressAtBase)
        }
        return true
    }

    fun notifyBlockUpdated() {
        if (level != null && !level!!.isClientSide) {
            setChanged()
            @Suppress("DEPRECATION")
            level!!.sendBlockUpdated(blockPos, blockState, blockState, 3)
        }
    }
}
