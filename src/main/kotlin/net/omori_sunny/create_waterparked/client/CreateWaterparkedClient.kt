package net.omori_sunny.create_waterparked.client

import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideDyeOutline
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditorRenderTypes
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslidePlacementPreview
import net.omori_sunny.create_waterparked.client.editor.WaterslideHotbarSync
import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities
import net.omori_sunny.create_waterparked.content.registry.ModEntityTypes
import net.omori_sunny.create_waterparked.content.sit.SlideSitEntity
import net.omori_sunny.create_waterparked.network.WaterslideDebugRequestPayload
import net.omori_sunny.create_waterparked.ponder.WaterslidePonderPlugin
import net.createmod.ponder.foundation.PonderIndex
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.resources.ResourceLocation
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
import net.neoforged.neoforge.network.PacketDistributor
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
        NeoForge.EVENT_BUS.addListener(SlideClientSession::onClientTickPre)
        NeoForge.EVENT_BUS.addListener(SlideClientSession::onClientTickPost)
        NeoForge.EVENT_BUS.addListener(SlideCameraHandler::onComputeCameraAngles)
        NeoForge.EVENT_BUS.addListener(::onRenderLevelStage)
        NeoForge.EVENT_BUS.addListener(::onClientLevelUnload)

        @Suppress("DEPRECATION")
        ModLoadingContext.get().getActiveContainer().registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { container, screen -> ConfigurationScreen(container, screen) }
        )
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        // ponder stories for waterslide items
        event.enqueueWork { PonderIndex.addPlugin(WaterslidePonderPlugin()) }
        // flywheel instanced rendering
        SimpleBlockEntityVisualizer.builder(ModBlockEntities.WATERSLIDE_ANCHOR_BE)
            .factory { ctx, be, pt -> WaterslideTubeVisual(ctx, be, pt) }
            .neverSkipVanillaRender()
            .apply()
    }

    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(ModEntityTypes.SLIDE_SIT) { ctx ->
            object : EntityRenderer<SlideSitEntity>(ctx) {
                override fun getTextureLocation(entity: SlideSitEntity): ResourceLocation =
                    ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "textures/entity/slide_sit.png")
            }
        }
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
                        camera.position, event.modelViewMatrix
                    )
                    buffers.endBatch(WaterslideEditorRenderTypes.COLORED_QUADS)
                }
            else -> {}
        }
    }

    private var lastDebugState: Boolean? = null

    private fun onClientTick(event: ClientTickEvent.Post) {
        WaterslideTubeVisual.tickVisibility()
        WaterSlideSoundManager.tick()
        val mc = Minecraft.getInstance()
        val debug = ModClientConfig.waterSimDebug()
        if (mc.connection != null && lastDebugState != debug) {
            lastDebugState = debug
            PacketDistributor.sendToServer(WaterslideDebugRequestPayload(debug))
            if (!debug) WaterFlowSimulation.clearDebugTrajectories()
        }
    }

    private fun onClientLevelUnload(event: LevelEvent.Unload) {
        if (event.level.isClientSide) {
            WaterslideCurveRenderer.clearClientAnchors()
            SlideSableOrientation.clearAll()
            WaterFlowSimulation.clear()
            WaterSlideSoundManager.stopAll()
        }
    }
}
