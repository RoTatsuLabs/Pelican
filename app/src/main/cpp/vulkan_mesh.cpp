/*
 * Mesh gradient background, ported to Kotlin and C++ from Apple Music-like Lyrics (AMLL)
 * https://github.com/amll-dev/applemusic-like-lyrics
 * Original work: Copyright (C) SteveXMH and the AMLL contributors, GNU AGPL-3.0.
 * Vulkan version of MeshGradientRenderer.kt: each cover is drawn into its own offscreen image,
 * then the images are blended onto the screen with the alpha of their fade.
 */

#include "vulkan_mesh.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

#include "shaders_spv.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MeshVulkan", __VA_ARGS__)
#else
#define LOGE(...) (fprintf(stderr, "MeshVulkan: " __VA_ARGS__), fprintf(stderr, "\n"))
#endif

#define CHECK(call)                                          \
    do {                                                     \
        VkResult result_ = (call);                           \
        if (result_ != VK_SUCCESS) {                         \
            LOGE("%s failed with %d", #call, (int)result_);  \
            return false;                                    \
        }                                                    \
    } while (0)

namespace pelican {

namespace {
constexpr VkFormat kTargetFormat = VK_FORMAT_R8G8B8A8_UNORM;
constexpr size_t kMaxStates = 8;
}  // namespace

MeshRenderer* MeshRenderer::create(void* window, uint32_t width, uint32_t height) {
    MeshRenderer* renderer = new MeshRenderer();
    if (!renderer->init(window, width, height)) {
        delete renderer;
        return nullptr;
    }
    return renderer;
}

MeshRenderer::~MeshRenderer() {
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
        for (State& state : states_) destroyState(state);
        states_.clear();
        for (VkSemaphore s : imageAvailable_) vkDestroySemaphore(device_, s, nullptr);
        for (VkSemaphore s : renderFinished_) vkDestroySemaphore(device_, s, nullptr);
        if (fence_ != VK_NULL_HANDLE) vkDestroyFence(device_, fence_, nullptr);
        if (commandPool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, commandPool_, nullptr);
        if (pool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(device_, pool_, nullptr);
        if (coverSampler_ != VK_NULL_HANDLE) vkDestroySampler(device_, coverSampler_, nullptr);
        if (targetSampler_ != VK_NULL_HANDLE) vkDestroySampler(device_, targetSampler_, nullptr);
        if (meshPipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device_, meshPipeline_, nullptr);
        if (quadPipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device_, quadPipeline_, nullptr);
        if (meshLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device_, meshLayout_, nullptr);
        if (quadLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device_, quadLayout_, nullptr);
        if (setLayout_ != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device_, setLayout_, nullptr);
        destroySwapchain(false);
        if (offscreenPass_ != VK_NULL_HANDLE) vkDestroyRenderPass(device_, offscreenPass_, nullptr);
        if (presentPass_ != VK_NULL_HANDLE) vkDestroyRenderPass(device_, presentPass_, nullptr);
        vkDestroyDevice(device_, nullptr);
    }
    if (instance_ != VK_NULL_HANDLE) {
        if (surface_ != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance_, surface_, nullptr);
        vkDestroyInstance(instance_, nullptr);
    }
}

bool MeshRenderer::init(void* window, uint32_t width, uint32_t height) {
    headless_ = window == nullptr;
    if (!createInstance()) return false;
#ifdef VK_USE_PLATFORM_ANDROID_KHR
    if (!headless_) {
        VkAndroidSurfaceCreateInfoKHR info{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        info.window = static_cast<ANativeWindow*>(window);
        CHECK(vkCreateAndroidSurfaceKHR(instance_, &info, nullptr, &surface_));
    }
#endif
    if (!headless_ && surface_ == VK_NULL_HANDLE) return false;
    if (!pickDevice()) return false;

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueInfo.queueFamilyIndex = queueFamily_;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char* extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = headless_ ? 0 : 1;
    deviceInfo.ppEnabledExtensionNames = extensions;
    CHECK(vkCreateDevice(physical_, &deviceInfo, nullptr, &device_));
    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);

    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily_;
    CHECK(vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_));
    VkCommandBufferAllocateInfo allocInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocInfo.commandPool = commandPool_;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    CHECK(vkAllocateCommandBuffers(device_, &allocInfo, &commandBuffer_));

    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    CHECK(vkCreateFence(device_, &fenceInfo, nullptr, &fence_));

    if (!createSwapchain(width, height)) return false;
    if (!createRenderPasses()) return false;
    if (!createPipelines()) return false;

    // Swapchain framebuffers need the present pass
    for (size_t i = 0; i < swapViews_.size(); i++) {
        VkFramebufferCreateInfo info{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        info.renderPass = presentPass_;
        info.attachmentCount = 1;
        info.pAttachments = &swapViews_[i];
        info.width = extent_.width;
        info.height = extent_.height;
        info.layers = 1;
        VkFramebuffer framebuffer;
        CHECK(vkCreateFramebuffer(device_, &info, nullptr, &framebuffer));
        swapFramebuffers_.push_back(framebuffer);
    }
    return true;
}

bool MeshRenderer::createInstance() {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Pelican";
    app.apiVersion = VK_API_VERSION_1_0;
    const char* extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, "VK_KHR_android_surface"};
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    info.enabledExtensionCount = headless_ ? 0 : 2;
    info.ppEnabledExtensionNames = extensions;
    CHECK(vkCreateInstance(&info, nullptr, &instance_));
    return true;
}

bool MeshRenderer::pickDevice() {
    uint32_t count = 0;
    CHECK(vkEnumeratePhysicalDevices(instance_, &count, nullptr));
    if (count == 0) {
        LOGE("No Vulkan device");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    CHECK(vkEnumeratePhysicalDevices(instance_, &count, devices.data()));
    for (VkPhysicalDevice device : devices) {
        uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &families, nullptr);
        std::vector<VkQueueFamilyProperties> properties(families);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &families, properties.data());
        for (uint32_t i = 0; i < families; i++) {
            if (!(properties[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) continue;
            VkBool32 present = VK_TRUE;
            if (!headless_) vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface_, &present);
            if (!present) continue;
            physical_ = device;
            queueFamily_ = i;
            return true;
        }
    }
    LOGE("No Vulkan device can draw to the screen");
    return false;
}

bool MeshRenderer::createSwapchain(uint32_t width, uint32_t height) {
    if (headless_) {
        extent_ = {width, height};
        colorFormat_ = VK_FORMAT_R8G8B8A8_UNORM;
        if (!createImage(width, height, colorFormat_,
                         VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, headlessImage_))
            return false;
        swapImages_ = {headlessImage_.image};
        swapViews_ = {headlessImage_.view};
        // The render pass does not exist yet at the first call, so the framebuffers are made later
        imageAvailable_.assign(1, VK_NULL_HANDLE);
        renderFinished_.assign(1, VK_NULL_HANDLE);
        return true;
    }

    VkSurfaceCapabilitiesKHR caps;
    CHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_, surface_, &caps));
    VkExtent2D extent = caps.currentExtent;
    if (extent.width == 0xFFFFFFFF) {
        extent.width = std::clamp(width, caps.minImageExtent.width, caps.maxImageExtent.width);
        extent.height = std::clamp(height, caps.minImageExtent.height, caps.maxImageExtent.height);
    }
    if (extent.width == 0 || extent.height == 0) return false;

    uint32_t formatCount = 0;
    CHECK(vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &formatCount, nullptr));
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    CHECK(vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &formatCount, formats.data()));
    VkSurfaceFormatKHR chosen = formats[0];
    for (const VkSurfaceFormatKHR& format : formats) {
        // UNORM, like the OpenGL version, so no sRGB conversion happens
        if (format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM) {
            chosen = format;
            break;
        }
    }
    if (swapchain_ != VK_NULL_HANDLE && chosen.format != colorFormat_) {
        LOGE("The surface format changed");
        return false;
    }

    VkCompositeAlphaFlagBitsKHR composite = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    for (VkCompositeAlphaFlagBitsKHR candidate :
         {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
          VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR}) {
        if (caps.supportedCompositeAlpha & candidate) {
            composite = candidate;
            break;
        }
    }

    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0) imageCount = std::min(imageCount, caps.maxImageCount);

    VkSwapchainCreateInfoKHR info{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    info.surface = surface_;
    info.minImageCount = imageCount;
    info.imageFormat = chosen.format;
    info.imageColorSpace = chosen.colorSpace;
    info.imageExtent = extent;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                            ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                            : caps.currentTransform;
    info.compositeAlpha = composite;
    info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    info.clipped = VK_TRUE;
    info.oldSwapchain = swapchain_;
    VkSwapchainKHR created;
    CHECK(vkCreateSwapchainKHR(device_, &info, nullptr, &created));
    if (swapchain_ != VK_NULL_HANDLE) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    swapchain_ = created;
    colorFormat_ = chosen.format;
    extent_ = extent;

    uint32_t imagesMade = 0;
    CHECK(vkGetSwapchainImagesKHR(device_, swapchain_, &imagesMade, nullptr));
    swapImages_.resize(imagesMade);
    CHECK(vkGetSwapchainImagesKHR(device_, swapchain_, &imagesMade, swapImages_.data()));
    for (VkImage image : swapImages_) {
        VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        viewInfo.image = image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = colorFormat_;
        viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageView view;
        CHECK(vkCreateImageView(device_, &viewInfo, nullptr, &view));
        swapViews_.push_back(view);
    }

    // One pair of semaphores for each image, so a semaphore is never reused while it is still waited on
    for (VkSemaphore s : imageAvailable_) vkDestroySemaphore(device_, s, nullptr);
    for (VkSemaphore s : renderFinished_) vkDestroySemaphore(device_, s, nullptr);
    imageAvailable_.clear();
    renderFinished_.clear();
    VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (uint32_t i = 0; i < imagesMade; i++) {
        VkSemaphore a, r;
        CHECK(vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &a));
        CHECK(vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &r));
        imageAvailable_.push_back(a);
        renderFinished_.push_back(r);
    }
    return true;
}

void MeshRenderer::destroySwapchain(bool keepHandle) {
    for (VkFramebuffer f : swapFramebuffers_) vkDestroyFramebuffer(device_, f, nullptr);
    swapFramebuffers_.clear();
    if (headless_) {
        destroyImage(headlessImage_);
        swapViews_.clear();
        swapImages_.clear();
        return;
    }
    for (VkImageView v : swapViews_) vkDestroyImageView(device_, v, nullptr);
    swapViews_.clear();
    swapImages_.clear();
    if (!keepHandle && swapchain_ != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }
}

bool MeshRenderer::recreateSwapchain() {
    vkDeviceWaitIdle(device_);
    destroySwapchain(true);
    if (!createSwapchain(extent_.width, extent_.height)) return false;
    for (VkImageView view : swapViews_) {
        VkFramebufferCreateInfo info{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        info.renderPass = presentPass_;
        info.attachmentCount = 1;
        info.pAttachments = &view;
        info.width = extent_.width;
        info.height = extent_.height;
        info.layers = 1;
        VkFramebuffer framebuffer;
        CHECK(vkCreateFramebuffer(device_, &info, nullptr, &framebuffer));
        swapFramebuffers_.push_back(framebuffer);
    }
    for (State& state : states_) {
        destroyTarget(state);
        if (!createTarget(state)) return false;
    }
    outOfDate_ = false;
    return true;
}

bool MeshRenderer::createRenderPasses() {
    VkAttachmentDescription color{};
    color.samples = VK_SAMPLE_COUNT_1_BIT;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkAttachmentReference reference{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &reference;

    // Offscreen: a cover is drawn into an image that the next pass reads
    color.format = kTargetFormat;
    color.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkSubpassDependency offscreenDependencies[2]{};
    offscreenDependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    offscreenDependencies[0].dstSubpass = 0;
    offscreenDependencies[0].srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    offscreenDependencies[0].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    offscreenDependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    offscreenDependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    offscreenDependencies[1].srcSubpass = 0;
    offscreenDependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    offscreenDependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    offscreenDependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    offscreenDependencies[1].dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    offscreenDependencies[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    VkRenderPassCreateInfo info{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    info.attachmentCount = 1;
    info.pAttachments = &color;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 2;
    info.pDependencies = offscreenDependencies;
    CHECK(vkCreateRenderPass(device_, &info, nullptr, &offscreenPass_));

    // Present: the covers are blended onto the screen
    color.format = colorFormat_;
    color.finalLayout = headless_ ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkSubpassDependency presentDependency{};
    presentDependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    presentDependency.dstSubpass = 0;
    presentDependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    presentDependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    presentDependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    info.dependencyCount = 1;
    info.pDependencies = &presentDependency;
    CHECK(vkCreateRenderPass(device_, &info, nullptr, &presentPass_));
    return true;
}

VkShaderModule MeshRenderer::shaderModule(const uint32_t* code, size_t bytes) const {
    VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    info.codeSize = bytes;
    info.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(device_, &info, nullptr, &module) != VK_SUCCESS) LOGE("A shader module did not build");
    return module;
}

bool MeshRenderer::createPipelines() {
    // One descriptor set layout: a combined image sampler for the cover or for an offscreen image
    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &binding;
    CHECK(vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &setLayout_));

    VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, (uint32_t)kMaxStates * 2};
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    poolInfo.maxSets = (uint32_t)kMaxStates * 2;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    CHECK(vkCreateDescriptorPool(device_, &poolInfo, nullptr, &pool_));

    VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
    CHECK(vkCreateSampler(device_, &samplerInfo, nullptr, &coverSampler_));
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    CHECK(vkCreateSampler(device_, &samplerInfo, nullptr, &targetSampler_));

    VkPushConstantRange meshPush{VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(MeshPush)};
    VkPushConstantRange quadPush{VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(float)};
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &setLayout_;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &meshPush;
    CHECK(vkCreatePipelineLayout(device_, &pipelineLayoutInfo, nullptr, &meshLayout_));
    pipelineLayoutInfo.pPushConstantRanges = &quadPush;
    CHECK(vkCreatePipelineLayout(device_, &pipelineLayoutInfo, nullptr, &quadLayout_));

    VkShaderModule meshVert = shaderModule(kMeshVertSpv, sizeof(kMeshVertSpv));
    VkShaderModule meshFrag = shaderModule(kMeshFragSpv, sizeof(kMeshFragSpv));
    VkShaderModule quadVert = shaderModule(kQuadVertSpv, sizeof(kQuadVertSpv));
    VkShaderModule quadFrag = shaderModule(kQuadFragSpv, sizeof(kQuadFragSpv));
    if (!meshVert || !meshFrag || !quadVert || !quadFrag) return false;

    VkVertexInputBindingDescription vertexBinding{0, 4 * sizeof(float), VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription attributes[2] = {
        {0, 0, VK_FORMAT_R32G32_SFLOAT, 0},
        {1, 0, VK_FORMAT_R32G32_SFLOAT, 2 * sizeof(float)},
    };
    VkPipelineVertexInputStateCreateInfo vertexInput{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineVertexInputStateCreateInfo noVertexInput = vertexInput;
    vertexInput.vertexBindingDescriptionCount = 1;
    vertexInput.pVertexBindingDescriptions = &vertexBinding;
    vertexInput.vertexAttributeDescriptionCount = 2;
    vertexInput.pVertexAttributeDescriptions = attributes;

    VkPipelineInputAssemblyStateCreateInfo assembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo viewport{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewport.viewportCount = 1;
    viewport.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo multisample{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState blend{};
    blend.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT |
                           VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blendState{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    blendState.attachmentCount = 1;
    blendState.pAttachments = &blend;
    VkDynamicState dynamics[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamicState{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamicState.dynamicStateCount = 2;
    dynamicState.pDynamicStates = dynamics;

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[0].pName = stages[1].pName = "main";

    VkGraphicsPipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = stages;
    pipelineInfo.pInputAssemblyState = &assembly;
    pipelineInfo.pViewportState = &viewport;
    pipelineInfo.pRasterizationState = &raster;
    pipelineInfo.pMultisampleState = &multisample;
    pipelineInfo.pColorBlendState = &blendState;
    pipelineInfo.pDynamicState = &dynamicState;

    // The mesh is drawn without blending
    stages[0].module = meshVert;
    stages[1].module = meshFrag;
    pipelineInfo.pVertexInputState = &vertexInput;
    pipelineInfo.layout = meshLayout_;
    pipelineInfo.renderPass = offscreenPass_;
    CHECK(vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &meshPipeline_));

    // The covers are blended onto the screen with the alpha of their fade
    blend.blendEnable = VK_TRUE;
    blend.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
    blend.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    blend.colorBlendOp = VK_BLEND_OP_ADD;
    blend.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
    blend.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    blend.alphaBlendOp = VK_BLEND_OP_ADD;
    stages[0].module = quadVert;
    stages[1].module = quadFrag;
    pipelineInfo.pVertexInputState = &noVertexInput;
    pipelineInfo.layout = quadLayout_;
    pipelineInfo.renderPass = presentPass_;
    CHECK(vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &quadPipeline_));

    vkDestroyShaderModule(device_, meshVert, nullptr);
    vkDestroyShaderModule(device_, meshFrag, nullptr);
    vkDestroyShaderModule(device_, quadVert, nullptr);
    vkDestroyShaderModule(device_, quadFrag, nullptr);
    return true;
}

uint32_t MeshRenderer::findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) const {
    VkPhysicalDeviceMemoryProperties memory;
    vkGetPhysicalDeviceMemoryProperties(physical_, &memory);
    for (uint32_t i = 0; i < memory.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) && (memory.memoryTypes[i].propertyFlags & properties) == properties) return i;
    }
    return UINT32_MAX;
}

bool MeshRenderer::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags properties,
                                Buffer& out) {
    VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    info.size = size;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    CHECK(vkCreateBuffer(device_, &info, nullptr, &out.buffer));
    VkMemoryRequirements requirements;
    vkGetBufferMemoryRequirements(device_, out.buffer, &requirements);
    uint32_t type = findMemoryType(requirements.memoryTypeBits, properties);
    if (type == UINT32_MAX) return false;
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.allocationSize = requirements.size;
    alloc.memoryTypeIndex = type;
    CHECK(vkAllocateMemory(device_, &alloc, nullptr, &out.memory));
    CHECK(vkBindBufferMemory(device_, out.buffer, out.memory, 0));
    if (properties & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) CHECK(vkMapMemory(device_, out.memory, 0, size, 0, &out.mapped));
    return true;
}

void MeshRenderer::destroyBuffer(Buffer& buffer) {
    if (buffer.mapped) vkUnmapMemory(device_, buffer.memory);
    if (buffer.buffer != VK_NULL_HANDLE) vkDestroyBuffer(device_, buffer.buffer, nullptr);
    if (buffer.memory != VK_NULL_HANDLE) vkFreeMemory(device_, buffer.memory, nullptr);
    buffer = Buffer();
}

bool MeshRenderer::createImage(uint32_t width, uint32_t height, VkFormat format, VkImageUsageFlags usage, Image& out) {
    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = format;
    info.extent = {width, height, 1};
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    CHECK(vkCreateImage(device_, &info, nullptr, &out.image));
    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(device_, out.image, &requirements);
    uint32_t type = findMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) return false;
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.allocationSize = requirements.size;
    alloc.memoryTypeIndex = type;
    CHECK(vkAllocateMemory(device_, &alloc, nullptr, &out.memory));
    CHECK(vkBindImageMemory(device_, out.image, out.memory, 0));
    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = out.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    CHECK(vkCreateImageView(device_, &viewInfo, nullptr, &out.view));
    return true;
}

void MeshRenderer::destroyImage(Image& image) {
    if (image.view != VK_NULL_HANDLE) vkDestroyImageView(device_, image.view, nullptr);
    if (image.image != VK_NULL_HANDLE) vkDestroyImage(device_, image.image, nullptr);
    if (image.memory != VK_NULL_HANDLE) vkFreeMemory(device_, image.memory, nullptr);
    image = Image();
}

bool MeshRenderer::oneTimeSubmit(void (*record)(VkCommandBuffer, void*), void* data) {
    VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    alloc.commandPool = commandPool_;
    alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    alloc.commandBufferCount = 1;
    VkCommandBuffer buffer;
    CHECK(vkAllocateCommandBuffers(device_, &alloc, &buffer));
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    CHECK(vkBeginCommandBuffer(buffer, &begin));
    record(buffer, data);
    CHECK(vkEndCommandBuffer(buffer));
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &buffer;
    CHECK(vkQueueSubmit(queue_, 1, &submit, VK_NULL_HANDLE));
    CHECK(vkQueueWaitIdle(queue_));
    vkFreeCommandBuffers(device_, commandPool_, 1, &buffer);
    return true;
}

bool MeshRenderer::createTarget(State& state) {
    if (!createImage(extent_.width, extent_.height, kTargetFormat,
                     VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, state.target))
        return false;
    VkFramebufferCreateInfo info{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    info.renderPass = offscreenPass_;
    info.attachmentCount = 1;
    info.pAttachments = &state.target.view;
    info.width = extent_.width;
    info.height = extent_.height;
    info.layers = 1;
    CHECK(vkCreateFramebuffer(device_, &info, nullptr, &state.targetFramebuffer));
    if (state.targetSet == VK_NULL_HANDLE) {
        VkDescriptorSetAllocateInfo alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        alloc.descriptorPool = pool_;
        alloc.descriptorSetCount = 1;
        alloc.pSetLayouts = &setLayout_;
        CHECK(vkAllocateDescriptorSets(device_, &alloc, &state.targetSet));
    }
    VkDescriptorImageInfo imageInfo{targetSampler_, state.target.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = state.targetSet;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &imageInfo;
    vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
    return true;
}

void MeshRenderer::destroyTarget(State& state) {
    if (state.targetFramebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device_, state.targetFramebuffer, nullptr);
    state.targetFramebuffer = VK_NULL_HANDLE;
    destroyImage(state.target);
}

void MeshRenderer::destroyState(State& state) {
    destroyTarget(state);
    if (state.targetSet != VK_NULL_HANDLE) vkFreeDescriptorSets(device_, pool_, 1, &state.targetSet);
    if (state.coverSet != VK_NULL_HANDLE) vkFreeDescriptorSets(device_, pool_, 1, &state.coverSet);
    destroyImage(state.cover);
    destroyBuffer(state.vertices);
    destroyBuffer(state.indices);
    state = State();
}

namespace {
struct UploadData {
    VkBuffer staging;
    VkImage image;
    uint32_t size;
};

void recordUpload(VkCommandBuffer buffer, void* raw) {
    UploadData* data = static_cast<UploadData*>(raw);
    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = data->image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                         nullptr, 1, &barrier);
    VkBufferImageCopy copy{};
    copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.imageExtent = {data->size, data->size, 1};
    vkCmdCopyBufferToImage(buffer, data->staging, data->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(buffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr,
                         0, nullptr, 1, &barrier);
}
}  // namespace

int MeshRenderer::addState(const float* vertices, uint32_t vertexCount, const uint16_t* indices, uint32_t indexCount,
                           const uint8_t* rgba, uint32_t textureSize) {
    if (states_.size() >= kMaxStates || vertexCount == 0 || indexCount == 0 || textureSize == 0) return -1;
    states_.emplace_back();
    State& state = states_.back();
    state.id = nextId_++;
    state.indexCount = indexCount;
    const VkMemoryPropertyFlags hostMemory = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

    bool ok = createBuffer(vertexCount * 4 * sizeof(float), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, hostMemory, state.vertices) &&
              createBuffer(indexCount * sizeof(uint16_t), VK_BUFFER_USAGE_INDEX_BUFFER_BIT, hostMemory, state.indices);
    if (ok) {
        memcpy(state.vertices.mapped, vertices, vertexCount * 4 * sizeof(float));
        memcpy(state.indices.mapped, indices, indexCount * sizeof(uint16_t));
        ok = createImage(textureSize, textureSize, kTargetFormat, VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                         state.cover);
    }
    if (ok) {
        Buffer staging;
        const uint32_t bytes = textureSize * textureSize * 4;
        ok = createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostMemory, staging);
        if (ok) {
            memcpy(staging.mapped, rgba, bytes);
            UploadData data{staging.buffer, state.cover.image, textureSize};
            ok = oneTimeSubmit(recordUpload, &data);
        }
        destroyBuffer(staging);
    }
    if (ok) {
        VkDescriptorSetAllocateInfo alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        alloc.descriptorPool = pool_;
        alloc.descriptorSetCount = 1;
        alloc.pSetLayouts = &setLayout_;
        ok = vkAllocateDescriptorSets(device_, &alloc, &state.coverSet) == VK_SUCCESS;
    }
    if (ok) {
        VkDescriptorImageInfo imageInfo{coverSampler_, state.cover.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        write.dstSet = state.coverSet;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &imageInfo;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
        ok = createTarget(state);
    }
    if (!ok) {
        vkDeviceWaitIdle(device_);
        destroyState(state);
        states_.pop_back();
        return -1;
    }
    return state.id;
}

void MeshRenderer::removeState(int id) {
    for (size_t i = 0; i < states_.size(); i++) {
        if (states_[i].id != id) continue;
        vkDeviceWaitIdle(device_);
        destroyState(states_[i]);
        states_.erase(states_.begin() + i);
        return;
    }
}

bool MeshRenderer::draw(const int* ids, const float* alphas, int count, float sinAngle, float cosAngle, float volume,
                        float vignette, float grain) {
    // The previous frame has to be done before its resources are used again
    CHECK(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX));

    if (!headless_) {
        VkSurfaceCapabilitiesKHR caps;
        CHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_, surface_, &caps));
        bool sizeChanged = caps.currentExtent.width != 0xFFFFFFFF &&
                           (caps.currentExtent.width != extent_.width || caps.currentExtent.height != extent_.height);
        if ((sizeChanged || outOfDate_) && !(caps.currentExtent.width == 0 || caps.currentExtent.height == 0)) {
            if (!recreateSwapchain()) return false;
        }
    }

    uint32_t imageIndex = 0;
    const uint32_t slot = frame_++ % (uint32_t)imageAvailable_.size();
    if (!headless_) {
        VkResult acquired = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, imageAvailable_[slot], VK_NULL_HANDLE,
                                                  &imageIndex);
        if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
            outOfDate_ = true;
            return false;
        }
        if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
            LOGE("vkAcquireNextImageKHR failed with %d", (int)acquired);
            return false;
        }
    }
    CHECK(vkResetFences(device_, 1, &fence_));

    VkCommandBuffer cmd = commandBuffer_;
    CHECK(vkResetCommandBuffer(cmd, 0));
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    CHECK(vkBeginCommandBuffer(cmd, &begin));

    const VkViewport viewport{0, 0, (float)extent_.width, (float)extent_.height, 0.0f, 1.0f};
    const VkRect2D scissor{{0, 0}, extent_};
    const MeshPush push{(float)extent_.width / (float)extent_.height, volume, sinAngle, cosAngle, vignette, grain};

    // 1. Every cover is drawn into its own image, without blending
    for (int i = 0; i < count; i++) {
        State* state = nullptr;
        for (State& candidate : states_) if (candidate.id == ids[i]) state = &candidate;
        if (!state) continue;
        VkClearValue clear{};
        VkRenderPassBeginInfo pass{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        pass.renderPass = offscreenPass_;
        pass.framebuffer = state->targetFramebuffer;
        pass.renderArea = scissor;
        pass.clearValueCount = 1;
        pass.pClearValues = &clear;
        vkCmdBeginRenderPass(cmd, &pass, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, meshPipeline_);
        vkCmdSetViewport(cmd, 0, 1, &viewport);
        vkCmdSetScissor(cmd, 0, 1, &scissor);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, meshLayout_, 0, 1, &state->coverSet, 0, nullptr);
        const VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(cmd, 0, 1, &state->vertices.buffer, &offset);
        vkCmdBindIndexBuffer(cmd, state->indices.buffer, 0, VK_INDEX_TYPE_UINT16);
        vkCmdPushConstants(cmd, meshLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(MeshPush),
                           &push);
        vkCmdDrawIndexed(cmd, state->indexCount, 1, 0, 0, 0);
        vkCmdEndRenderPass(cmd);
    }

    // 2. The images are blended onto the screen, each with the alpha of its fade
    VkClearValue black{};
    black.color = {{0.0f, 0.0f, 0.0f, 1.0f}};
    VkRenderPassBeginInfo pass{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    pass.renderPass = presentPass_;
    pass.framebuffer = swapFramebuffers_[imageIndex];
    pass.renderArea = scissor;
    pass.clearValueCount = 1;
    pass.pClearValues = &black;
    vkCmdBeginRenderPass(cmd, &pass, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, quadPipeline_);
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    for (int i = 0; i < count; i++) {
        State* state = nullptr;
        for (State& candidate : states_) if (candidate.id == ids[i]) state = &candidate;
        if (!state) continue;
        vkCmdPushConstants(cmd, quadLayout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(float), &alphas[i]);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, quadLayout_, 0, 1, &state->targetSet, 0, nullptr);
        vkCmdDraw(cmd, 6, 1, 0, 0);
    }
    vkCmdEndRenderPass(cmd);
    CHECK(vkEndCommandBuffer(cmd));

    const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    if (!headless_) {
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &imageAvailable_[slot];
        submit.pWaitDstStageMask = &waitStage;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &renderFinished_[imageIndex];
    }
    CHECK(vkQueueSubmit(queue_, 1, &submit, fence_));

    if (!headless_) {
        VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &renderFinished_[imageIndex];
        present.swapchainCount = 1;
        present.pSwapchains = &swapchain_;
        present.pImageIndices = &imageIndex;
        VkResult presented = vkQueuePresentKHR(queue_, &present);
        if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR) {
            outOfDate_ = true;
        } else if (presented != VK_SUCCESS) {
            LOGE("vkQueuePresentKHR failed with %d", (int)presented);
            return false;
        }
    }
    return true;
}

namespace {
struct ReadData {
    VkImage image;
    VkBuffer buffer;
    VkExtent2D extent;
};

void recordRead(VkCommandBuffer buffer, void* raw) {
    ReadData* data = static_cast<ReadData*>(raw);
    VkBufferImageCopy copy{};
    copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.imageExtent = {data->extent.width, data->extent.height, 1};
    vkCmdCopyImageToBuffer(buffer, data->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, data->buffer, 1, &copy);
}
}  // namespace

bool MeshRenderer::readPixels(std::vector<uint8_t>& rgba, uint32_t& width, uint32_t& height) {
    if (!headless_) return false;
    CHECK(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX));
    Buffer buffer;
    const VkDeviceSize size = (VkDeviceSize)extent_.width * extent_.height * 4;
    if (!createBuffer(size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, buffer))
        return false;
    ReadData data{headlessImage_.image, buffer.buffer, extent_};
    bool ok = oneTimeSubmit(recordRead, &data);
    if (ok) {
        rgba.assign(static_cast<uint8_t*>(buffer.mapped), static_cast<uint8_t*>(buffer.mapped) + size);
        width = extent_.width;
        height = extent_.height;
    }
    destroyBuffer(buffer);
    return ok;
}

}  // namespace pelican
