package net.omori_sunny.create_waterparked

import com.simibubi.create.api.stress.BlockStressValues
import net.omori_sunny.create_waterparked.client.CreateWaterparkedClient
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentTypes
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities
import net.omori_sunny.create_waterparked.content.registry.ModBlocks
import net.omori_sunny.create_waterparked.content.registry.ModDataComponents
import net.omori_sunny.create_waterparked.content.registry.ModEntityTypes
import net.omori_sunny.create_waterparked.content.registry.ModItems
import net.omori_sunny.create_waterparked.content.registry.ModParticles
import net.omori_sunny.create_waterparked.content.registry.ModSounds
import net.omori_sunny.create_waterparked.content.registry.CoasterCreativeTabIntegration
import net.omori_sunny.create_waterparked.content.raft.ModRecipeSerializers
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Entity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorInteraction
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportInteraction
import net.omori_sunny.create_waterparked.datagen.CreateWaterparkedDataGen
import net.omori_sunny.create_waterparked.game.command.WaterparkedCommands
import net.omori_sunny.create_waterparked.game.contraption.WaterslideContraptionIntegration
import net.omori_sunny.create_waterparked.game.physics.PlayerSlideController
import net.omori_sunny.create_waterparked.network.ModPayloads
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.config.ModConfigEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.neoforged.neoforge.common.NeoForge
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.runForDist

@Mod(CreateWaterparked.ID)
@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
object CreateWaterparked {
    const val ID = "create_waterparked"

    val LOGGER: Logger = LogManager.getLogger(ID)

    init {
        ModBlocks.REGISTRY.register(MOD_BUS)
        ModBlockEntities.REGISTRY.register(MOD_BUS)
        ModItems.REGISTRY.register(MOD_BUS)
        net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments.init()
        ModEntityTypes.REGISTRY.register(MOD_BUS)
        ModRecipeSerializers.REGISTRY.register(MOD_BUS)
        ModDataComponents.REGISTRY.register(MOD_BUS)
        ModSounds.REGISTRY.register(MOD_BUS)
        ModParticles.REGISTRY.register(MOD_BUS)
        MOD_BUS.addListener(CoasterCreativeTabIntegration::onBuildCreativeModeTabContents)
        MOD_BUS.addListener<net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent> { event ->
            event.put(ModEntityTypes.INFLATABLE_BOAT_1X2, InflatableBoat1x2Entity.createAttributes().build())
        }

        MOD_BUS.addListener(ModPayloads::register)
        // onCommonSetup and onConfigReloaded are already registered through the
        // @EventBusSubscriber / @SubscribeEvent pair on this object; adding the
        // same methods here as well made both run twice on every startup.
        MOD_BUS.addListener(CreateWaterparkedDataGen::gatherData)

        NeoForge.EVENT_BUS.addListener(PlayerSlideController::onServerTick)
        NeoForge.EVENT_BUS.addListener(
            net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentManager::onServerTick
        )
        NeoForge.EVENT_BUS.addListener(WaterslideSupportInteraction::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(
            EventPriority.HIGHEST,
            WaterslideSupportInteraction::onRightClickBlock
        )
        NeoForge.EVENT_BUS.addListener(
            EventPriority.HIGHEST,
            WaterslideSupportInteraction::onRightClickItem
        )
        NeoForge.EVENT_BUS.addListener(
            EventPriority.HIGHEST,
            WaterslideAnchorInteraction::onRightClickBlock
        )
        MOD_BUS.addListener(WaterslideAnchorBlockEntity::registerCapabilities)
        NeoForge.EVENT_BUS.addListener(PlayerSlideController::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(PlayerSlideController::onPlayerLoggedIn)

        ModConfig.register()

        runForDist(
            clientTarget = {
                ModClientConfig.register()
                CreateWaterparkedClient.registerClientEvents()
                "client"
            },
            serverTarget = {
                MOD_BUS.addListener(::onServerSetup)
                "server"
            }
        )
    }

    private fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.info("Server starting...")
    }

    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        LOGGER.info("Create Waterparked loaded.")
        registerStressValues()
        WaterslideContraptionIntegration.register()
        WaterparkedCommands.register()
    }

    // Create's stress registry is a plain, non-frozen map keyed by Block and
    // expects the base impact per RPM; it must be filled once the blocks exist.
    private fun registerStressValues() {
        for (type in SlideAttachmentTypes.all()) {
            if (type.stressImpact <= 0.0) continue
            val block = type.block.get()
            BlockStressValues.IMPACTS.register(
                block,
                java.util.function.DoubleSupplier { type.stressImpact }
            )
            LOGGER.info("Stress impact {} x RPM for {}", type.stressImpact, type.id)
        }
    }

    @SubscribeEvent
    fun onConfigReloaded(event: ModConfigEvent.Reloading) {
        // rebuild tube visuals so client rendering options apply immediately
        if (event.config.spec === ModClientConfig.SPEC) {
            WaterslideTubeMesh.clearModels()
            WaterslideTubeVisual.refreshAll()
        }
    }

    @SubscribeEvent
    fun onConfigLoaded(event: ModConfigEvent.Loading) {
        if (event.config.spec !== ModConfig.SERVER_SPEC) return
        val dedicated = net.neoforged.fml.loading.FMLEnvironment.dist ==
            net.neoforged.api.distmarker.Dist.DEDICATED_SERVER
        if (!ModConfig.subLevelSlideRiding()) {
            if (dedicated) {
                LOGGER.info(
                    "[Waterparked] Sub-level slide riding is DISABLED: dedicated servers default to off for performance. " +
                        "Set slide.subLevelSlideRiding = true in create_waterparked-server.toml to enable it."
                )
            } else {
                LOGGER.info("[Waterparked] Sub-level slide riding is disabled via config.")
            }
        } else {
            LOGGER.info("[Waterparked] Sub-level slide riding is enabled.")
        }
    }
}
