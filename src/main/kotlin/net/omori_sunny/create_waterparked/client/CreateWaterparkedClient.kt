package net.omori_sunny.create_waterparked.client
// Client bootstrap: events, renderers, mixin hooks and payload handlers.

import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.compat.itrp.IterationRPPatcher
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideDyeOutline
import net.omori_sunny.create_waterparked.client.editor.WaterslideSupportOutline
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditorRenderTypes
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideSupportEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideGhostPlacement
import net.omori_sunny.create_waterparked.client.editor.WaterslidePlacementPreview
import net.omori_sunny.create_waterparked.client.editor.WaterslideClipboardPaste
import net.omori_sunny.create_waterparked.client.editor.SlideClipboardCopy
import net.omori_sunny.create_waterparked.client.editor.WaterslideHotbarSync
import net.omori_sunny.create_waterparked.client.particle.WaterslideSplashParticle
import net.omori_sunny.create_waterparked.client.particle.WaterslideSplashSpawner
import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer
import net.omori_sunny.create_waterparked.client.render.WaterslideGhostRenderer
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities
import net.omori_sunny.create_waterparked.content.registry.ModEntityTypes
import net.omori_sunny.create_waterparked.content.registry.ModParticles
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
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import net.neoforged.neoforge.client.gui.ConfigurationScreen
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.network.PacketDistributor
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@OnlyIn(Dist.CLIENT)
object CreateWaterparkedClient {

    fun registerClientEvents() {
        Thread { IterationRPPatcher.runIfNeeded() }.apply {
            isDaemon = true
            name = "Waterparked-IterationRPPatcher"
            start()
        }
        MOD_BUS.addListener(::onClientSetup)
        MOD_BUS.addListener(::onRegisterRenderers)
        MOD_BUS.addListener(::onRegisterParticleProviders)
        MOD_BUS.addListener(::onItemColors)
        MOD_BUS.addListener(::onRegisterClientExtensions)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideGhostPlacement::onUseItemKey)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideGhostPlacement::onRightClickBlock)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideGhostPlacement::onRightClickItem)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideGhostPlacement::onLeftClickBlock)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideGhostPlacement::onAttackKey)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideSupportEdit::onRightClickBlock)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideSupportEdit::onRightClickItem)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideSupportEdit::onUseItemKey)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideSectorEdit::onRightClickBlock)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideSectorEdit::onUseItemKey)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WaterslideClipboardPaste::onUseItemKey)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, SlideClipboardCopy::onUseItemKey)
        NeoForge.EVENT_BUS.addListener(WaterslidePlacementPreview::onClientTick)
        NeoForge.EVENT_BUS.addListener(WaterslideHotbarSync::onClientTick)
        NeoForge.EVENT_BUS.addListener(WaterslideSectorEdit::onClientTick)
        NeoForge.EVENT_BUS.addListener(WaterslideGhostPlacement::onClientTick)
        NeoForge.EVENT_BUS.addListener(WaterslideClipboardPaste::onClientTick)
        NeoForge.EVENT_BUS.addListener(::onClientTick)
        NeoForge.EVENT_BUS.addListener(SlideClientSession::onClientTickPre)
        NeoForge.EVENT_BUS.addListener(SlideClientSession::onClientTickPost)
        NeoForge.EVENT_BUS.addListener(SlideCameraHandler::onComputeCameraAngles)
        NeoForge.EVENT_BUS.addListener(SlideCameraHandler::onComputeFov)
        NeoForge.EVENT_BUS.addListener(::onRenderLevelStage)
        NeoForge.EVENT_BUS.addListener(::onClientLevelUnload)

        @Suppress("DEPRECATION")
        ModLoadingContext.get().getActiveContainer().registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { container, screen -> ConfigurationScreen(container, screen) }
        )
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        IterationRPPatcher.runIfNeeded()
        net.omori_sunny.create_waterparked.client.item.WaterslideItemTooltips.register()
        if (net.neoforged.fml.ModList.get().isLoaded("ponder") ||
            Thread.currentThread().contextClassLoader.getResource("net/createmod/ponder/foundation/PonderIndex.class") != null
        ) {
            event.enqueueWork { PonderIndex.addPlugin(WaterslidePonderPlugin()) }
        }
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
        event.registerBlockEntityRenderer(ModBlockEntities.WATERSLIDE_ANCHOR_BE) { ctx ->
            net.omori_sunny.create_waterparked.client.renderer.WaterslideTubeBlockEntityRenderer(ctx)
        }
        event.registerEntityRenderer(ModEntityTypes.INFLATABLE_BOAT_1X2) { ctx ->
            net.omori_sunny.create_waterparked.client.renderer.InflatableBoat1x2Renderer(ctx)
        }
    }

    private fun onRegisterParticleProviders(event: RegisterParticleProvidersEvent) {
        event.registerSpriteSet(ModParticles.WATER_SLIDE_SPLASH) { sprites ->
            WaterslideSplashParticle.Provider(sprites)
        }
    }

    private fun onItemColors(event: net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item) {
        val item = net.omori_sunny.create_waterparked.content.registry.ModItems.INFLATABLE_BOAT_1X2
        event.register(
            { stack, tintIndex ->
                if (tintIndex == 0) stack.get(net.minecraft.core.component.DataComponents.DYED_COLOR)?.rgb() ?: 0xFFFFFF
                else -1
            },
            item
        )
    }

    // custom rendered item, Create-package style: SimpleCustomRenderer also
    // registers the item with Create's CustomRenderedItems so the baked model
    // gets wrapped with CustomRenderedItemModel and the BEWLR is used
    private fun onRegisterClientExtensions(event: net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent) {
        val item: net.minecraft.world.item.Item =
            net.omori_sunny.create_waterparked.content.registry.ModItems.INFLATABLE_BOAT_1X2
        event.registerItem(
            com.simibubi.create.foundation.item.render.SimpleCustomRenderer.create(
                item,
                net.omori_sunny.create_waterparked.client.renderer.InflatableBoatItemRenderer()
            ),
            item
        )
    }

    private fun onRenderLevelStage(event: RenderLevelStageEvent) {
        val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
        when (event.stage) {
            RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES ->
                {
                    WaterslideCurveRenderer.renderAllInEvent(event.poseStack, buffers)
                    WaterslideGhostRenderer.renderAllInEvent(event.poseStack, buffers)
                }
            RenderLevelStageEvent.Stage.AFTER_LEVEL ->
                {
                    val mc = Minecraft.getInstance()
                    val camera = mc.gameRenderer.mainCamera
                    WaterslideCurveRenderer.endBatches(buffers)
                    WaterslideGhostRenderer.endBatches(buffers)
                    WaterslideDyeOutline.render(
                        mc, event.poseStack, buffers,
                        camera.position, event.modelViewMatrix
                    )
                    WaterslideSupportOutline.render(
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
        val mc = Minecraft.getInstance()
        WaterslideTubeVisual.tickVisibility()
        WaterslideSupportEdit.onClientTick()
        net.omori_sunny.create_waterparked.client.editor.SubLevelEditFocus.tick(mc)
        WaterSlideSoundManager.tick()
        WaterslideSplashSpawner.tickStanding(mc)
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
            WaterslideSupportEdit.clear()
            WaterslideGhostPlacement.clear()
            WaterslideGhostRenderer.clear()
            SlideSableOrientation.clearAll()
            SlideClientSession.resetActive()
            WaterFlowSimulation.clear()
            WaterSlideSoundManager.stopAll()
        }
    }
}