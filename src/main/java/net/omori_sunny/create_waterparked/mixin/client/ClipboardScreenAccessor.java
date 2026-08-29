package net.omori_sunny.create_waterparked.mixin.client;

import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// private method access for ClipboardScreenMixin
@Mixin(ClipboardScreen.class)
public interface ClipboardScreenAccessor {

    @Invoker("clearDisplayCache")
    void create_waterparked$clearDisplayCache();
}
