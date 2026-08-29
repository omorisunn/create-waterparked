package net.omori_sunny.create_waterparked.game.command

import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideAnchorIndex
import net.omori_sunny.create_waterparked.game.water.ContraptionWaterSimulation
import net.omori_sunny.create_waterparked.game.water.ServerWaterSimulation
import net.minecraft.commands.Commands
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.function.Consumer

// recompute water fields for all loaded slides and resend them
object WaterparkedCommands {

    @Volatile
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        NeoForge.EVENT_BUS.addListener(
            Consumer { event: RegisterCommandsEvent ->
                event.dispatcher.register(
                    Commands.literal("waterparked")
                        .requires { it.hasPermission(2) }
                        .then(
                            Commands.literal("refresh")
                                .executes { ctx ->
                                    val level = ctx.source.level
                                    if (level is ServerLevel) {
                                        ServerWaterSimulation.refresh(level)
                                        ContraptionWaterSimulation.refresh(level)
                                        ctx.source.sendSuccess(
                                            { Component.literal("Waterparked: recomputed water fields for all loaded slides") },
                                            true
                                        )
                                    }
                                    1
                                }
                        )
                        .then(
                            Commands.literal("dump")
                                .executes { ctx ->
                                    val level = ctx.source.level
                                    if (level is ServerLevel) {
                                        val lines = dumpSlides(level)
                                        for (line in lines) {
                                            ctx.source.sendSuccess({ Component.literal(line) }, false)
                                            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                                                "[SlideDump] {}", line
                                            )
                                        }
                                        // one-click copy: the whole report joins with newlines and
                                        // rides a COPY_TO_CLIPBOARD click event on a summary line
                                        val fullText = lines.joinToString("\n")
                                        val copyEvent = net.minecraft.network.chat.ClickEvent(
                                            net.minecraft.network.chat.ClickEvent.Action.COPY_TO_CLIPBOARD,
                                            fullText
                                        )
                                        ctx.source.sendSuccess(
                                            {
                                                Component.literal(
                                                    "Waterparked: ${lines.size} slide lines dumped - "
                                                ).append(
                                                    Component.literal("Click to copy all")
                                                        .withStyle {
                                                            it.withClickEvent(copyEvent)
                                                                .withColor(net.minecraft.ChatFormatting.AQUA)
                                                                .withUnderlined(true)
                                                        }
                                                )
                                            },
                                            true
                                        )
                                    }
                                    1
                                }
                        )
                )
            }
        )
    }

    private fun dumpSlides(level: ServerLevel): List<String> {
        val out = ArrayList<String>()
        val anchors = SlideAnchorIndex.all(level)
        out += "== Waterparked slide dump: ${anchors.size} anchors in ${level.dimension().location()} =="
        for (pos in anchors) {
            val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            out += "Anchor @ $pos  radius=${be.radius}  waterActive=${be.waterActive}  waterAmount=${be.waterAmount()}mb"
            // primary curve(s)
            for ((peer, raw) in be.anchorPeerCurvesView) {
                if (raw == null) continue
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val first = bc.bePositions.getFirst()
                val second = bc.bePositions.getSecond()
                out += "  Curve -> peer=$peer  primary=${raw.isPrimary}  a=$first  b=$second  segments=${bc.getSegmentCount()}"
                out += "    watered=${be.isCurveWatered(peer)}  sectorFirst=${be.isCurveWatered(first)}"
                // sector config
                val config = be.sectorConfigFor(peer)
                out += "    sectors start=${config.startAngle} nextId=${config.nextId}"
                for (s in config.sectors) {
                    out += "      id=${s.id} ${s.material} ${s.type} block=${s.blockId} width=${s.widthDegrees}"
                }
            }
            // supports
            out += "  Support beam: visible=${be.supportBeamVisible} material=${be.supportMaterial(net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BEAM)} custom=${be.hasCustomSupportMaterial(net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BEAM)}"
            out += "  Support bracket: visible=${be.supportBracketVisible} material=${be.supportMaterial(net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BRACKET)} custom=${be.hasCustomSupportMaterial(net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BRACKET)}"
        }
        return out
    }
}