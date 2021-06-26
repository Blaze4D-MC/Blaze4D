package me.hydos.rosella

import me.hydos.rosella.audio.SoundManager
import me.hydos.rosella.render.camera.Camera
import me.hydos.rosella.render.device.Device
import me.hydos.rosella.render.io.Window
import me.hydos.rosella.render.material.Material
import me.hydos.rosella.render.model.Renderable
import me.hydos.rosella.render.renderer.Renderer
import me.hydos.rosella.render.resource.Identifier
import me.hydos.rosella.render.shader.RawShaderProgram
import me.hydos.rosella.render.shader.ShaderManager
import me.hydos.rosella.render.swapchain.Frame
import me.hydos.rosella.render.texture.TextureManager
import me.hydos.rosella.render.util.getLogLevel
import me.hydos.rosella.render.util.memory.Memory
import me.hydos.rosella.render.util.ok
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.glfwShowWindow
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.system.MemoryStack.stackGet
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.jni.JNINativeInterface
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.function.Consumer
import java.util.stream.Collectors

/**
 * Main engine class. most interactions will happen here
 */
class Rosella(
	name: String,
	val enableValidationLayers: Boolean,
	val window: Window
) {
	val polygonMode: Int = VK_POLYGON_MODE_FILL
	var memory: Memory

	var renderer: Renderer = Renderer()

	var renderObjects = HashMap<String, Renderable>()
	var materials = HashMap<Identifier, Material>()
	var shaderManager: ShaderManager
	var textureManager: TextureManager

	val camera = Camera(window)

	internal lateinit var vulkanInstance: VkInstance
	lateinit var maxImages: IntBuffer

	val device: Device
	var surface: Long = 0

	val logger: Logger = LogManager.getLogger("Rosella")

	init {
		(logger as org.apache.logging.log4j.core.Logger).level = Level.ALL
		SoundManager.initialize()
		window.onWindowResize(renderer::windowResizeCallback)

		val validationLayers = defaultValidationLayers.toSet()
		if (enableValidationLayers && !validationLayersSupported(validationLayers)) {
			throw RuntimeException("Validation Layers are not available!")
		}

		createInstance(name, validationLayers)

		if (enableValidationLayers) {
			setupDebugMessenger(this)
		}

		createSurface()
		this.device = Device(this, validationLayers)
		this.shaderManager = ShaderManager(device)
		this.textureManager = TextureManager(device)
		this.memory = Memory(device, vulkanInstance)
		renderer.initialize(this)

		glfwShowWindow(window.windowPtr)
	}

	private fun createInstance(name: String, validationLayers: Set<String>) {
		stackPush().use { stack ->
			val applicationInfo = VkApplicationInfo.callocStack(stack)
				.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
				.pApplicationName(stack.UTF8Safe(name))
				.applicationVersion(VK_MAKE_VERSION(1, 0, 0))
				.pEngineName(stack.UTF8Safe("Rosella"))
				.engineVersion(VK_MAKE_VERSION(0, 1, 0))
				.apiVersion(VK_API_VERSION_1_2)
			val createInfo = VkInstanceCreateInfo.callocStack(stack)
				.pApplicationInfo(applicationInfo)
				.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
				.ppEnabledExtensionNames(getRequiredExtensions(enableValidationLayers))
			if (enableValidationLayers) {

				val features = stack.callocInt(3)
				features.put(EXTValidationFeatures.VK_VALIDATION_FEATURE_ENABLE_BEST_PRACTICES_EXT)
				features.put(EXTValidationFeatures.VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_EXT)
				features.put(EXTValidationFeatures.VK_VALIDATION_FEATURE_ENABLE_DEBUG_PRINTF_EXT)

				val validationFeaturesEXT = VkValidationFeaturesEXT.callocStack(stack)
				validationFeaturesEXT
					.sType(EXTValidationFeatures.VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT)
					.pEnabledValidationFeatures(features)

				createInfo.ppEnabledLayerNames(asPtrBuffer(validationLayers))
				val debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.callocStack(stack)
				populateDebugMessengerCreateInfo(this, debugCreateInfo)
				debugCreateInfo.pNext(validationFeaturesEXT.address())
				createInfo.pNext(debugCreateInfo.address())
			}
			val pInstance = stack.mallocPointer(1)
			vkCreateInstance(createInfo, null, pInstance).ok()

			vulkanInstance = VkInstance(pInstance[0], createInfo)
		}
	}

	private fun createSurface() {
		stackPush().use {
			val pSurface: LongBuffer = it.longs(VK_NULL_HANDLE)
			glfwCreateWindowSurface(vulkanInstance, window.windowPtr, null, pSurface).ok()
			this.surface = pSurface.get(0)
		}
	}

	fun free() {
		for (model in renderObjects.values) {
			model.free(memory)
		}

		renderer.freeSwapChain(this)

		renderer.inFlightFrames.forEach(Consumer { frame: Frame ->
			vkDestroySemaphore(device.device, frame.renderFinishedSemaphore(), null)
			vkDestroySemaphore(device.device, frame.imageAvailableSemaphore(), null)
			vkDestroyFence(device.device, frame.fence(), null)
		})

		vkDestroyCommandPool(device.device, renderer.commandPool, null)

		renderer.swapChain.free(device.device)

		vkDestroyDevice(device.device, null)

		DebugManager.free(vulkanInstance)

		vkDestroySurfaceKHR(vulkanInstance, surface, null)
		vkDestroyInstance(vulkanInstance, null)

		memory.free()
	}

	private fun getRequiredExtensions(validationLayersEnabled: Boolean): PointerBuffer? {
		val glfwExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions()
		if (validationLayersEnabled) {
			val stack = stackGet()
			val extensions = stack.mallocPointer(glfwExtensions!!.capacity() + 1)
			extensions.put(glfwExtensions)
			extensions.put(stack.UTF8(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME))
			return extensions.rewind()
		}
		return glfwExtensions
	}

	private val defaultValidationLayers: List<String>
		get() {
			val validationLayers: MutableList<String> = ArrayList()
			validationLayers.add("VK_LAYER_KHRONOS_validation")
			return validationLayers
		}

	internal fun asPtrBuffer(validationLayers: Set<String>): PointerBuffer {
		val stack = stackGet()
		val buffer = stack.mallocPointer(validationLayers.size)
		for (validationLayer in validationLayers) {
			val byteBuffer = stack.UTF8(validationLayer)
			buffer.put(byteBuffer)
		}
		return buffer.rewind()
	}

	private fun validationLayersSupported(validationLayers: Set<String>): Boolean {
		stackPush().use { stack ->
			val layerCount = stack.ints(0)
			vkEnumerateInstanceLayerProperties(layerCount, null).ok()
			val availableLayers = VkLayerProperties.mallocStack(layerCount[0], stack)
			vkEnumerateInstanceLayerProperties(layerCount, availableLayers).ok()
			val availableLayerNames = availableLayers.stream()
				.map { obj: VkLayerProperties -> obj.layerNameString() }
				.collect(Collectors.toSet())
			return availableLayerNames.containsAll(validationLayers)
		}
	}

	fun addRenderObject(renderObject: Renderable, name: String) {
		if (renderObjects.containsKey(name)) {
			error("An render object already exists with that name!")
		}
		renderObject.load(this)
		renderObjects[name] = renderObject
		renderObject.create(this)
	}

	fun registerMaterial(identifier: Identifier, material: Material) {
		materials[identifier] = material
	}

	fun registerShader(identifier: Identifier, rawShader: RawShaderProgram) {
		shaderManager.shaders[identifier] = rawShader
	}

	fun reloadMaterials() {
		for (material in materials.values) {
			material.loadShaders(this)
			material.loadTextures(this)
			material.shader.raw.createDescriptorSetLayout()
			material.createPipeline( //TODO: cache pipelines when stuff doesnt change
				device,
				renderer.swapChain,
				renderer.renderPass,
				material.shader.raw.descriptorSetLayout,
				polygonMode
			)
		}
	}

	fun getHeight(): Float {
		return renderer.swapChain.swapChainExtent.height().toFloat()
	}

	fun getWidth(): Float {
		return renderer.swapChain.swapChainExtent.width().toFloat()
	}

	private companion object DebugManager {
		@JvmStatic
		private var debugMessenger: Long = 0

		@JvmStatic
		private fun createDebugUtilsMessengerEXT(
				instance: VkInstance,
				createInfo: VkDebugUtilsMessengerCreateInfoEXT,
				allocationCallbacks: VkAllocationCallbacks?,
				pDebugMessenger: LongBuffer
		): Int {
			return if (vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT") != NULL) {
				EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, createInfo, allocationCallbacks, pDebugMessenger)
			} else VK_ERROR_EXTENSION_NOT_PRESENT
		}

		@JvmStatic
		fun debugCallback(severity: Int, messageType: Int, pCallbackData: Long, pUserData: Long): Int {
			val callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData)
			val message = callbackData.pMessageString()
			val engine = MemoryUtil.memGlobalRefToObject<Rosella>(pUserData)
			val type = when(messageType) {
				EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT -> "GENERAL"
				EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT -> "VALIDATION"
				EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT -> "PERFORMANCE"
				else -> "Unknown"
			}
			if (message.startsWith("Validation Error")) {
				val split = message.split("|")
				val cause = split[2]
				engine.logger.error("$type: ${split[0]} | ${split[1]}", RuntimeException(cause))
			} else {
				engine.logger.log(severity.getLogLevel(), "$type: $message")
			}
			return VK_FALSE
		}

		@JvmStatic
		private fun setupDebugMessenger(engine: Rosella) {
			stackPush().use { stack ->
				val createInfo = VkDebugUtilsMessengerCreateInfoEXT.callocStack(stack)
				populateDebugMessengerCreateInfo(engine, createInfo)
				val pDebugMessenger = stack.longs(VK_NULL_HANDLE)
				createDebugUtilsMessengerEXT(engine.vulkanInstance, createInfo, null, pDebugMessenger).ok()
				debugMessenger = pDebugMessenger[0]
			}
		}

		@JvmStatic
		private fun populateDebugMessengerCreateInfo(engine: Rosella, debugCreateInfo: VkDebugUtilsMessengerCreateInfoEXT) {
			debugCreateInfo.sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
					.messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT or EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT or EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
					.messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
					.pfnUserCallback(this::debugCallback)
					.pUserData(JNINativeInterface.NewGlobalRef(engine))
		}

		@JvmStatic
		private fun free(vulkanInstance: VkInstance) {
			if (vkGetInstanceProcAddr(vulkanInstance, "vkDestroyDebugUtilsMessengerEXT") != NULL) {
				EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(vulkanInstance, debugMessenger, null)
			}
		}
	}
}
