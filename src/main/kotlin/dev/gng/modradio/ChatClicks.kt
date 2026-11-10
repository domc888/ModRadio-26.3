package dev.gng.modradio

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.screens.ChatScreen

/**
 * Lets you use the radio while the chat is open (the mouse is free there): click prev /
 * play-pause / next, and click or drag on the progress bar to seek.
 * Isolated in its own file: if the screen-event signature shifted in your Fabric API
 * build, delete this file and the ChatClicks call in ModRadioClient, nothing else depends on it.
 */
object ChatClicks {
    fun register() {
        ScreenEvents.AFTER_INIT.register(
            ScreenEvents.AfterInit { _, screen, _, _ ->
                if (screen is ChatScreen) {
                    ScreenMouseEvents.allowMouseClick(screen).register(
                        ScreenMouseEvents.AllowMouseClick { _, event ->
                            try {
                                if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
                                    true
                                } else {
                                    val h = RadioHud.hit(event.x(), event.y())
                                    if (h >= RadioHud.HIT_PREV && h <= RadioHud.HIT_NEXT) {
                                        RadioHud.perform(h)
                                        false
                                    } else if (h == RadioHud.HIT_BAR) {
                                        RadioHud.beginScrub(event.x())
                                        false
                                    } else {
                                        true
                                    }
                                }
                            } catch (e: Throwable) {
                                true
                            }
                        },
                    )
                    ScreenMouseEvents.allowMouseDrag(screen).register(
                        ScreenMouseEvents.AllowMouseDrag { _, event, _, _ ->
                            try {
                                if (RadioHud.scrubbing) {
                                    RadioHud.dragScrub(event.x())
                                    false
                                } else {
                                    true
                                }
                            } catch (e: Throwable) {
                                true
                            }
                        },
                    )
                    ScreenMouseEvents.allowMouseRelease(screen).register(
                        ScreenMouseEvents.AllowMouseRelease { _, event ->
                            try {
                                if (RadioHud.scrubbing) {
                                    RadioHud.endScrub(event.x())
                                    false
                                } else {
                                    true
                                }
                            } catch (e: Throwable) {
                                true
                            }
                        },
                    )
                    ScreenEvents.remove(screen).register(
                        ScreenEvents.Remove { RadioHud.cancelScrub() },
                    )
                }
            },
        )
    }
}
