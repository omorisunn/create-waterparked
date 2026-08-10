package net.omori_sunny.create_waterparked.client

import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideDyeOutline
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditorRenderTypes
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslidePlacementPreview
import net.omori_sunny.create_waterparked.client.editor.WaterslideHotbarSync
import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.gui.ConfigurationScreen
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.LevelEvent
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

// client init
@OnlyIn(Dist.CLIENT)
object CreateWaterparkedClient {

    fun registerClientEvents() {
        MOD_BUS.addListener(::onClientSetup)
        MOD_BUS.addListener(::onRegisterRenderers)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideSectorEdit::onRightClickBlock)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideSectorEdit::onUseItemKey)
        NeoForge.EVENT_BUS.addListener(WaterslidePlacementPreview::onClientTick)
        NeoForge.EVENT_BUS.addListener(WaterslideHotbarSync::onClientTick)
        NeoForge.EVENT_BUS.addListener(WaterslideSectorEdit::onClientTick)
        NeoForge.EVENT_BUS.addListener(::onClientTick)
        NeoForge.EVENT_BUS.addListener(::onRenderLevelStage)
        NeoForge.EVENT_BUS.addListener(::onClientLevelUnload)

        @Suppress("DEPRECATION")
        ModLoadingContext.get().getActiveContainer().registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { container, screen -> ConfigurationScreen(container, screen) }
        )
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        // flywheel instanced rendering
        SimpleBlockEntityVisualizer.builder(ModBlockEntities.WATERSLIDE_ANCHOR_BE)
            .factory { ctx, be, pt -> WaterslideTubeVisual(ctx, be, pt) }
            .neverSkipVanillaRender()
            .apply()
    }

    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        // level-stage rendering
    }

    private fun onRenderLevelStage(event: RenderLevelStageEvent) {
        val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
        when (event.stage) {
            RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES ->
                WaterslideCurveRenderer.renderAllInEvent(event.poseStack, buffers)
            // flush pipe batches
            RenderLevelStageEvent.Stage.AFTER_LEVEL ->
                {
                    WaterslideCurveRenderer.endBatches(buffers)
                    val mc = Minecraft.getInstance()
                    val camera = mc.gameRenderer.mainCamera
                    WaterslideDyeOutline.render(
                        mc, event.poseStack, buffers,
                        camera.position, Matrix4f().set(camera.rotation())
                    )
                    buffers.endBatch(WaterslideEditorRenderTypes.COLORED_QUADS)
                }
            else -> {}
        }
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        WaterslideTubeVisual.tickVisibility()
    }

    private fun onClientLevelUnload(event: LevelEvent.Unload) {
        if (event.level.isClientSide) {
            WaterslideCurveRenderer.clearClientAnchors()
        }
    }
}
