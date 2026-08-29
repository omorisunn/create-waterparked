package net.omori_sunny.create_waterparked.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.omori_sunny.create_waterparked.client.editor.WaterslideClipboardPaste;
import net.omori_sunny.create_waterparked.content.waterslide.ParsedSlideConfig;
import net.omori_sunny.create_waterparked.content.waterslide.SlideClipboardCodec;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

// Formula-editor style rows for slide config entries: a row shows a compact
// block (paper background, wooden frame, wrap icon, name + summary). A single
// click enters the paste mode, a double click turns the row into an editable
// raw %SC{...} line with the cursor right after the name. Vertical layout is
// computed by Create from the (short) block text, so rows never jitter; the
// hovered row is highlighted with a wooden background.
@Mixin(ClipboardScreen.class)
public class ClipboardScreenMixin {

    // clipboard paper / wooden frame colors, sampled from AllGuiTextures.CLIPBOARD
    private static final int CLIPBOARD_PAPER = 0xFFFFFAEE;
    private static final int CLIPBOARD_FRAME = 0x5B3D09;
    private static final int CLIPBOARD_HOVER = 0xFFD1BFA1;
    // icon drawn before each block row; the texture is a user-editable template
    private static final ResourceLocation WRAP_ICON =
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "textures/gui/clipboard_wrap.png");

    @Shadow
    private List<List<ClipboardEntry>> pages;

    @Shadow
    private List<ClipboardEntry> currentEntries;

    @Shadow
    private int editingIndex;

    @Shadow
    private int hoveredEntry;

    @Shadow
    private boolean hoveredCheck;

    @Shadow
    private boolean readonly;

    @Shadow
    private TextFieldHelper editContext;

    @Shadow
    public BlockPos targetedBlock;

    // original encoding per recognized entry, identity keyed
    @Unique
    private Map<ClipboardEntry, String> waterparked$originals;

    // edited result per entry, written when the editing session ends
    @Unique
    private Map<ClipboardEntry, String> waterparked$edited;

    // armed single click: the paste mode fires from tick() once the
    // double-click window has closed, so a second click can still edit
    @Unique
    private int waterparked$pendingIndex = -1;

    @Unique
    private long waterparked$pendingTime = 0L;

    // guiLeft/guiTop are not reachable as @Shadow fields: the renderWindow
    // wrapper records them when the first texture is drawn
    @Unique
    private int waterparked$guiY;

    @Unique
    private Map<ClipboardEntry, String> waterparked$originalsOrCreate() {
        if (waterparked$originals == null) {
            waterparked$originals = new IdentityHashMap<>();
        }
        return waterparked$originals;
    }

    @Unique
    private Map<ClipboardEntry, String> waterparked$editedOrCreate() {
        if (waterparked$edited == null) {
            waterparked$edited = new IdentityHashMap<>();
        }
        return waterparked$edited;
    }

    // block display text: the full name, then the summary. Our own linked-row
    // mechanism (originals map) drives the row rendering below, mirroring how
    // Create handles its address rows: no checkbox, sticker slot, plain text.
    @Unique
    private static String waterparked$blockText(String original) {
        ParsedSlideConfig parsed = SlideClipboardCodec.INSTANCE.parse(original);
        if (parsed == null) return original;
        return parsed.getName() + "\n" + Component.translatable(
            "create_waterparked.clipboard.summary",
            parsed.getConfig().getSectors().size(), parsed.getConfig().getStartAngle()).getString();
    }

    // the encoding currently in effect: the edited result when the row was
    // edited, otherwise the original line
    @Unique
    private String waterparked$effectiveFor(ClipboardEntry entry, String original) {
        if (waterparked$edited != null && waterparked$edited.containsKey(entry)) {
            return waterparked$edited.get(entry);
        }
        return original;
    }

    // restore the block display for rows that left the editing session,
    // keeping the edited encoding if it still parses
    @Unique
    private void waterparked$restoreBlockRows() {
        if (targetedBlock != null || waterparked$originals == null || waterparked$originals.isEmpty()) return;
        for (int i = 0; i < currentEntries.size(); i++) {
            ClipboardEntry entry = currentEntries.get(i);
            if (i == editingIndex) continue;
            String original = waterparked$originals.get(entry);
            if (original == null) continue;
            String text = entry.text.getString();
            String block = waterparked$blockText(waterparked$effectiveFor(entry, original));
            if (text.equals(block)) continue;
            // editing session ended: keep the edit when it still parses
            boolean editedKnown = waterparked$edited != null && waterparked$edited.containsKey(entry);
            if (!editedKnown && SlideClipboardCodec.INSTANCE.parse(text) != null) {
                waterparked$editedOrCreate().put(entry, text);
            }
            entry.text = Component.literal(block);
        }
    }

    @Inject(method = "reopenWith", at = @At("TAIL"))
    private void create_waterparked$convertEntryDisplays(ClipboardContent content, CallbackInfo ci) {
        Map<ClipboardEntry, String> originals = waterparked$originalsOrCreate();
        originals.clear();
        if (waterparked$edited != null) waterparked$edited.clear();
        waterparked$pendingIndex = -1;
        // wall clipboards keep the vanilla behavior
        if (targetedBlock != null) return;
        for (List<ClipboardEntry> page : pages) {
            for (ClipboardEntry entry : page) {
                String line = entry.text.getString();
                if (SlideClipboardCodec.INSTANCE.parse(line) == null) continue;
                originals.put(entry, line);
                // block form: short stable text, no item icon
                entry.text = Component.literal(waterparked$blockText(line));
                entry.icon = ItemStack.EMPTY;
            }
        }
    }

    // draw the button background behind recognized rows, right after the paper:
    // the wrapped call passes x = guiLeft and y = guiTop - 8 directly
    @WrapOperation(method = "renderWindow", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/gui/AllGuiTextures;render(Lnet/minecraft/client/gui/GuiGraphics;II)V",
        ordinal = 0))
    private void create_waterparked$drawButtonRows(AllGuiTextures texture, GuiGraphics graphics, int x, int y, Operation<Void> original) {
        waterparked$guiY = y;
        original.call(texture, graphics, x, y);
        if (targetedBlock != null || waterparked$originals == null || waterparked$originals.isEmpty()) return;
        int rowY = y;
        for (int i = 0; i < currentEntries.size(); i++) {
            ClipboardEntry entry = currentEntries.get(i);
            int height = Math.max(12,
                Minecraft.getInstance().font.split(entry.text, 150).size() * 9 + 3);
            if (waterparked$originals.containsKey(entry) && i != editingIndex) {
                int top = rowY + 48;
                int bottom = rowY + 48 + height;
                boolean hover = i == hoveredEntry;
                graphics.fill(x + 35, top, x + 218, bottom, hover ? CLIPBOARD_HOVER : CLIPBOARD_PAPER);
                graphics.fill(x + 35, top, x + 218, top + 1, CLIPBOARD_FRAME);
                graphics.fill(x + 35, bottom - 1, x + 218, bottom, CLIPBOARD_FRAME);
                // linked-row sticker at the Create address slot (x + 44)
                graphics.blit(WRAP_ICON, x + 44, rowY + 50, 0, 0, 8, 8, 8, 8);
            }
            rowY += height;
        }
    }

    // hide the vanilla checkbox in front of a linked row: mirror the address
    // row behavior with our own row detection instead of the text '#' marker
    @Redirect(method = "renderWindow", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I"))
    private int create_waterparked$hideBlockCheckbox(GuiGraphics graphics, net.minecraft.client.gui.Font font,
        String text, int x, int y, int color, boolean shadow) {
        if ("\u25A1".equals(text) && waterparked$isLinkedRowAt(y - 51)) {
            color = 0;
        }
        return graphics.drawString(font, text, x, y, color, shadow);
    }

    // true when the given row top (guiTop - 8 based) belongs to a linked row
    @Unique
    private boolean waterparked$isLinkedRowAt(int rowTop) {
        if (waterparked$originals == null || waterparked$originals.isEmpty()) return false;
        int rowY = waterparked$guiY;
        for (ClipboardEntry entry : currentEntries) {
            int height = Math.max(12,
                Minecraft.getInstance().font.split(entry.text, 150).size() * 9 + 3);
            if (waterparked$originals.containsKey(entry)
                && rowTop >= rowY && rowTop < rowY + height) return true;
            rowY += height;
        }
        return false;
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void create_waterparked$clickButton(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (targetedBlock != null) return;
        if (button != 0 || readonly) return;
        if (hoveredEntry < 0 || hoveredEntry >= currentEntries.size()) {
            waterparked$pendingIndex = -1;
            return;
        }
        // any non-button click cancels an armed single click
        if (hoveredEntry == editingIndex || hoveredCheck) {
            waterparked$pendingIndex = -1;
            return;
        }
        ClipboardEntry entry = currentEntries.get(hoveredEntry);
        String original = waterparked$originals == null ? null : waterparked$originals.get(entry);
        if (original == null) {
            waterparked$pendingIndex = -1;
            return;
        }

        long now = Util.getMillis();
        boolean doubleClick = hoveredEntry == waterparked$pendingIndex
            && now - waterparked$pendingTime < 300L;
        if (doubleClick) {
            // edit the raw line (the current encoding after prior edits),
            // cursor right after the name
            String effective = waterparked$effectiveFor(entry, original);
            editingIndex = hoveredEntry;
            entry.text = Component.literal(effective);
            int cursor = SlideClipboardCodec.INSTANCE.nameEnd(effective);
            editContext.setSelectionRange(cursor, cursor);
            ((ClipboardScreenAccessor) (Object) this).create_waterparked$clearDisplayCache();
            waterparked$pendingIndex = -1;
            cir.setReturnValue(true);
            return;
        }
        // single click: arm the paste entry; the tick fires it once the
        // double-click window closes
        waterparked$pendingIndex = hoveredEntry;
        waterparked$pendingTime = now;
        cir.setReturnValue(true);
    }

    // when a row becomes the edit target (e.g. after the row below was
    // deleted) and still shows the block text, switch it to the raw line
    // with the cursor at the end
    @Unique
    private void waterparked$enterEditingIfNeeded() {
        if (targetedBlock != null || waterparked$originals == null || waterparked$originals.isEmpty()) return;
        if (editingIndex < 0 || editingIndex >= currentEntries.size()) return;
        ClipboardEntry entry = currentEntries.get(editingIndex);
        String original = waterparked$originals.get(entry);
        if (original == null) return;
        String effective = waterparked$effectiveFor(entry, original);
        if (!entry.text.getString().equals(waterparked$blockText(effective))) return;
        entry.text = Component.literal(effective);
        editContext.setCursorToEnd();
        ((ClipboardScreenAccessor) (Object) this).create_waterparked$clearDisplayCache();
    }

    // flush an armed single click once the double-click window has passed,
    // then restore block displays for rows that left the editing session
    @Inject(method = "tick", at = @At("TAIL"))
    private void create_waterparked$flushPendingClick(CallbackInfo ci) {
        waterparked$restoreBlockRows();
        waterparked$enterEditingIfNeeded();
        if (waterparked$pendingIndex < 0) return;
        long now = Util.getMillis();
        // strictly larger than the 300ms double-click window, so a second
        // click always reaches mouseClicked before the GUI closes
        if (now - waterparked$pendingTime < 350L) return;
        ClipboardEntry entry = currentEntries.get(waterparked$pendingIndex);
        String original = waterparked$originals == null ? null : waterparked$originals.get(entry);
        waterparked$pendingIndex = -1;
        if (original == null) return;
        // close the GUI and enter the paste mode with the encoded line
        Minecraft.getInstance().setScreen(null);
        WaterslideClipboardPaste.INSTANCE.enter(original);
    }

    // restore the encoding before the content is sent: edited rows keep their
    // new text when it still parses, untouched rows get their encoding back
    @Inject(method = "send", at = @At("HEAD"))
    private void create_waterparked$restoreOnSend(CallbackInfo ci) {
        if (waterparked$originals == null || waterparked$originals.isEmpty()) return;
        for (Map.Entry<ClipboardEntry, String> e : waterparked$originals.entrySet()) {
            ClipboardEntry entry = e.getKey();
            String restored = e.getValue();
            if (waterparked$edited != null && waterparked$edited.containsKey(entry)) {
                restored = waterparked$edited.get(entry);
            }
            entry.text = Component.literal(restored);
        }
        waterparked$originals.clear();
        if (waterparked$edited != null) waterparked$edited.clear();
    }
}
