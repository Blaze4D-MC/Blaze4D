package me.hydos.rosella.example

import me.hydos.rosella.audio.SoundManager
import me.hydos.rosella.render.Topology
import me.hydos.rosella.render.io.Window
import me.hydos.rosella.render.material.Material
import me.hydos.rosella.render.model.GuiRenderObject
import me.hydos.rosella.render.resource.Global
import me.hydos.rosella.render.resource.Identifier
import me.hydos.rosella.render.shader.RawShaderProgram
import me.hydos.rosella.render.texture.SamplerCreateInfo
import me.hydos.rosella.render.texture.TextureFilter
import me.hydos.rosella.render.vertex.VertexFormats.Companion.POSITION_COLOR_UV
import org.lwjgl.vulkan.VK10

object Portal {

	val screen = Window("Rosella Engine", 1280, 720)
	val engine = Rosella("Portal 3", true, screen)

	val menuBackground = Identifier("example", "menu_background")
	val portalLogo = Identifier("example", "portal_logo")

	val basicShader = Identifier("rosella", "example_shader")
	val guiShader = Identifier("rosella", "gui_shader")

	val background = Identifier("example", "sounds/music/mainmenu/portal2_background01.ogg")

	@JvmStatic
	fun main(args: Array<String>) {
		loadShaders()
		loadMaterials()
		setupMainMenuScene()
		SoundManager.playback(Global.ensureResource(background))
		doMainLoop()
	}

	private fun setupMainMenuScene() {
		engine.addToScene(
			GuiRenderObject(
				menuBackground
			).apply {
				scale(1.5f, 1f)
			}
		)

		engine.addToScene(
			GuiRenderObject(
				portalLogo,
				-0.9f
			).apply {
				scale(0.4f, 0.1f)
				translate(-1f, -2.6f)
			}
		)
	}

	private fun loadMaterials() {
		engine.registerMaterial(
			menuBackground, Material(
				Global.ensureResource(Identifier("example", "textures/background/background01.png")),
				guiShader,
				VK10.VK_FORMAT_R8G8B8A8_UNORM,
				false,
				Topology.TRIANGLES,
				POSITION_COLOR_UV,
				SamplerCreateInfo(TextureFilter.NEAREST)
			)
		)
		engine.registerMaterial(
			portalLogo, Material(
				Global.ensureResource(Identifier("example", "textures/gui/portal2logo.png")),
				guiShader,
				VK10.VK_FORMAT_R8G8B8A8_SRGB,
				true,
				Topology.TRIANGLES,
				POSITION_COLOR_UV,
				SamplerCreateInfo(TextureFilter.NEAREST)
			)
		)
		engine.reloadMaterials()
	}

	private fun loadShaders() {
		engine.registerShader(
			basicShader, RawShaderProgram(
				Global.ensureResource(Identifier("rosella", "shaders/base.v.glsl")),
				Global.ensureResource(Identifier("rosella", "shaders/base.f.glsl")),
				engine.device,
				engine.memory,
				10,
				RawShaderProgram.PoolObjType.UBO,
				RawShaderProgram.PoolObjType.SAMPLER
			)
		)

		engine.registerShader(
			guiShader, RawShaderProgram(
				Global.ensureResource(Identifier("rosella", "shaders/gui.v.glsl")),
				Global.ensureResource(Identifier("rosella", "shaders/gui.f.glsl")),
				engine.device,
				engine.memory,
				10,
				RawShaderProgram.PoolObjType.UBO,
				RawShaderProgram.PoolObjType.SAMPLER
			)
		)
	}

	private fun doMainLoop() {
		engine.renderer.rebuildCommandBuffers(engine.renderer.renderPass, engine)
		screen.onMainLoop {
			engine.renderer.render(engine)
		}
		screen.startLoop()
	}
}
