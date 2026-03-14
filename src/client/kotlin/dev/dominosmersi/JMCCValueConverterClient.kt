package dev.dominosmersi

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

object JMCCValueConverterClient : ClientModInitializer {

	private lateinit var startKey: KeyBinding
	private var cooldownTicks = 0

	override fun onInitializeClient() {
		startKey = KeyBindingHelper.registerKeyBinding(
			KeyBinding(
				"Конвертировать значение",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				KeyBinding.Category.CREATIVE
			)
		)

		ClientTickEvents.END_CLIENT_TICK.register { client ->
			if (cooldownTicks > 0) cooldownTicks--
				if (InputUtil.isKeyPressed(client.window, GLFW.GLFW_KEY_K) && cooldownTicks == 0) {
					MyFeature.run(client)
					cooldownTicks = 20
				}
		}
	}
}