package dev.gng.modradio

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object ModRadioClient : ClientModInitializer {
    const val MOD_ID = "modradio"
    val LOGGER: Logger = LoggerFactory.getLogger("ModRadio")

    @Volatile
    private var renderErrorLogged = false

    override fun onInitializeClient() {
        RadioConfigManager.load()
        MediaBridge.start()

        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(MOD_ID, "radio"),
            HudElement { graphics, _ ->
                try {
                    RadioHud.render(graphics, false)
                } catch (e: Throwable) {
                    if (!renderErrorLogged) {
                        renderErrorLogged = true
                        LOGGER.warn("Radio HUD render failed", e)
                    }
                }
            },
        )

        try {
            ChatClicks.register()
        } catch (e: Throwable) {
            LOGGER.warn("Could not register chat click handler", e)
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping { MediaBridge.stop() })
    }
}
