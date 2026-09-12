package net.omori_sunny.create_waterparked.content.attachment.door

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions
import com.simibubi.create.foundation.gui.AllIcons
import net.omori_sunny.create_waterparked.client.gui.DoorModeIcon

// how the panels clear the opening; the frame band never moves
enum class MechanicalDoorMode(private val iconName: String) : INamedIconOptions {
    SLIDE("door_mode_slide"),
    LIFT("door_mode_lift"),
    APERTURE("door_mode_aperture");

    private val cachedIcon: AllIcons by lazy { DoorModeIcon(iconName) }

    override fun getIcon(): AllIcons = cachedIcon

    override fun getTranslationKey(): String = "create_waterparked.door.mode." + name.lowercase()
}
