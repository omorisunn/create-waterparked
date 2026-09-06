package net.omori_sunny.create_waterparked.client.attachment

import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import java.util.concurrent.ConcurrentHashMap

// client-side set of loaded SAB BEs (rebuilt from BE load/remove events);
// the attachment renderer walks this to draw every live attachment
@OnlyIn(Dist.CLIENT)
object SlideAttachmentClientIndex {

    private val bes = ConcurrentHashMap.newKeySet<SlideAttachmentBlockEntity>()

    fun add(be: SlideAttachmentBlockEntity) {
        if (be.entry != null && bes.add(be)) {
            CreateWaterparked.LOGGER.info(
                "[SARender] index add at {} type={} size={}",
                be.blockPos, be.entry?.typeId, bes.size
            )
        }
    }

    fun remove(be: SlideAttachmentBlockEntity) {
        if (bes.remove(be)) {
            CreateWaterparked.LOGGER.info("[SARender] index remove at {} size={}", be.blockPos, bes.size)
        }
    }

    fun all(): Collection<SlideAttachmentBlockEntity> = bes
}
