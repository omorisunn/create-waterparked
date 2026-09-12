package net.omori_sunny.create_waterparked.client.editor.controlpoint

import net.minecraft.core.BlockPos
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity

@OnlyIn(Dist.CLIENT)
object SlideAttachmentEditorRegistry {

    private val factories = HashMap<String, (BlockPos) -> SlideControlPointEditor>()
    private val byPos = HashMap<BlockPos, SlideControlPointEditor>()

    @JvmStatic
    fun registerFactory(key: String, factory: (BlockPos) -> SlideControlPointEditor) {
        factories[key] = factory
    }

    @JvmStatic
    fun editorFor(be: SlideAttachmentBlockEntity): SlideControlPointEditor? {
        val key = (be.attachment()
            as? net.omori_sunny.create_waterparked.content.attachment.IHaveSlideAttachmentEditor)
            ?.slideEditorKey() ?: return null
        val factory = factories[key] ?: return null
        val pos = be.blockPos
        val editor = byPos.getOrPut(pos) { factory(pos) }
        SlideControlPointEditor.register(editor)
        return editor
    }

    @JvmStatic
    fun discard(pos: BlockPos) {
        byPos.remove(pos)
    }
}
