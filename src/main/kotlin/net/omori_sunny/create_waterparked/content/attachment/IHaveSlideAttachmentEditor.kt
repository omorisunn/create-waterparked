package net.omori_sunny.create_waterparked.content.attachment

// returns only an editor key so this interface stays client-free
interface IHaveSlideAttachmentEditor {

    // null = not editable
    fun slideEditorKey(): String?
}
