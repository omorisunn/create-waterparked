package net.omori_sunny.create_waterparked.content.attachment

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.context.UseOnContext
import net.neoforged.neoforge.registries.DeferredBlock
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.registry.ModDataComponents
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity

// SAB item: two-phase placement. Phase 1 (slide hover right-click, handled by
// the client placement handler) stores SLIDE_ATTACHMENT_POS on the stack and
// the glint shows; phase 2 places this block on the ground, the server
// validates the stored position against the live slide and binds the entry.
class SlideAttachmentBlockItem(
    private val blockRef: DeferredBlock<out SlideAttachmentBlock>,
    private val typeRef: () -> SlideAttachmentType,
    properties: Properties
) : BlockItem(blockRef.get(), properties) {

    fun type(): SlideAttachmentType = typeRef()

    override fun isFoil(stack: ItemStack): Boolean = stack.has(ModDataComponents.SLIDE_ATTACHMENT_POS)

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val stack = context.itemInHand
        val pos = stack.get(ModDataComponents.SLIDE_ATTACHMENT_POS)
        if (pos == null) {
            if (!level.isClientSide) {
                context.player?.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                        "create_waterparked.attachment.need_position"
                    ), true
                )
            }
            return InteractionResult.FAIL
        }
        if (level.isClientSide) return InteractionResult.SUCCESS

        val type = type()
        // validate against the live slide before the block exists
        val error = validatePlacement(level, context.clickedPos, type, pos)
        if (error != null) {
            context.player?.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(error), true
            )
            return InteractionResult.FAIL
        }
        val result = super.useOn(context)
        if (!result.consumesAction()) return result
        // locate the freshly placed SAB and bind the attachment position
        val placed = findPlacedHost(level, context)
        if (placed != null) {
            placed.bind(
                SlideAttachmentEntry(
                    type.id.toString(),
                    pos.curveA, pos.curveB, pos.site, pos.t, pos.angle
                )
            )
            // frogport style success feedback
            (level as? net.minecraft.server.level.ServerLevel)?.sendParticles(
                net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                placed.blockPos.x + 0.5, placed.blockPos.y + 1.0, placed.blockPos.z + 0.5,
                10, 0.3, 0.3, 0.3, 0.1
            )
            context.player?.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("create_waterparked.attachment.placed"), true
            )
            val player = context.player
            if (player == null || !player.isCreative) stack.shrink(1)
            // strip the selection so the item starts a fresh placement
            stack.remove(ModDataComponents.SLIDE_ATTACHMENT_POS)
        }
        return result
    }

    private fun validatePlacement(
        level: net.minecraft.world.level.Level,
        clicked: BlockPos,
        type: SlideAttachmentType,
        pos: SlideAttachmentPos
    ): String? {
        val anchor = level.getBlockEntity(pos.curveA) as? WaterslideAnchorBlockEntity
            ?: return "create_waterparked.attachment.no_slide"
        if (anchor.anchorPeerCurvesView.containsKey(pos.curveB.immutable())) return null
        return "create_waterparked.attachment.no_slide"
    }

    private fun findPlacedHost(
        level: net.minecraft.world.level.Level,
        context: UseOnContext
    ): SlideAttachmentBlockEntity? {
        val center = context.clickedPos.relative(context.clickedFace)
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val p = center.offset(dx, dy, dz)
            if (level.getBlockEntity(p) is SlideAttachmentBlockEntity) {
                return level.getBlockEntity(p) as SlideAttachmentBlockEntity
            }
        }
        return null
    }

    companion object {
        // the selected slide position on a held SAB item
        val POSITION_CODEC: Codec<SlideAttachmentPos> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("a").forGetter { it.curveA },
                BlockPos.CODEC.fieldOf("b").forGetter { it.curveB },
                Codec.STRING.fieldOf("site").forGetter { it.site.name },
                Codec.FLOAT.fieldOf("t").forGetter { it.t },
                Codec.FLOAT.fieldOf("angle").forGetter { it.angle }
            ).apply(i) { a, b, site, t, angle ->
                SlideAttachmentPos(a, b, SlideAttachmentSite.valueOf(site), t, angle)
            }
        }

        val POSITION_STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideAttachmentPos> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, { it.curveA },
                BlockPos.STREAM_CODEC, { it.curveB },
                ByteBufCodecs.STRING_UTF8, { it.site.name },
                ByteBufCodecs.FLOAT, { it.t },
                ByteBufCodecs.FLOAT, { it.angle },
                { a, b, site, t, angle ->
                    SlideAttachmentPos(a, b, SlideAttachmentSite.valueOf(site), t, angle)
                }
            )
    }
}
