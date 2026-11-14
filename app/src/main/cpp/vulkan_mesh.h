/*
 * Mesh gradient background, ported to Kotlin and C++ from Apple Music-like Lyrics (AMLL)
 * https://github.com/amll-dev/applemusic-like-lyrics
 * Original work: Copyright (C) SteveXMH and the AMLL contributors, GNU AGPL-3.0.
 * This is the Vulkan version of the renderer in MeshGradientRenderer.kt. The mesh itself is built in Kotlin.
 */

#pragma once

#include <cstdint>
#include <vector>
#include <vulkan/vulkan.h>

namespace pelican {

class MeshRenderer {
public:
    // window is an ANativeWindow* on Android. Without a window the renderer draws into an image
    // that readPixels returns, which is how the tests run on a computer.
    static MeshRenderer* create(void* window, uint32_t width, uint32_t height);
    ~MeshRenderer();

    // vertices are 4 floats each (x, y, u, v), the cover is textureSize x textureSize RGBA bytes.
    // Returns the id of the new state, or -1.
    int addState(const float* vertices, uint32_t vertexCount, const uint16_t* indices, uint32_t indexCount,
                 const uint8_t* rgba, uint32_t textureSize);
    void removeState(int id);

    // Draws the states in order, each one faded by its alpha. Returns false when nothing was drawn.
    bool draw(const int* ids, const float* alphas, int count, float sinAngle, float cosAngle, float volume,
              float vignette, float grain);

    // Headless only: the last frame as RGBA bytes
    bool readPixels(std::vector<uint8_t>& rgba, uint32_t& width, uint32_t& height);

private:
    struct Buffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        void* mapped = nullptr;
    };
    struct Image {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
    };
    struct State {
        int id = 0;
        Buffer vertices;
        Buffer indices;
        uint32_t indexCount = 0;
        Image cover;
        VkDescriptorSet coverSet = VK_NULL_HANDLE;
        Image target;
        VkFramebuffer targetFramebuffer = VK_NULL_HANDLE;
        VkDescriptorSet targetSet = VK_NULL_HANDLE;
    };
    struct MeshPush {
        float aspect, volume, sinAngle, cosAngle, vignette, grain;
    };

    MeshRenderer() = default;
    bool init(void* window, uint32_t width, uint32_t height);
    bool createInstance();
    bool pickDevice();
    bool createSwapchain(uint32_t width, uint32_t height);
    bool recreateSwapchain();
    void destroySwapchain(bool keepHandle);
    bool createRenderPasses();
    bool createPipelines();
    bool createTarget(State& state);
    void destroyTarget(State& state);
    void destroyState(State& state);

    uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) const;
    bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags properties, Buffer& out);
    void destroyBuffer(Buffer& buffer);
    bool createImage(uint32_t width, uint32_t height, VkFormat format, VkImageUsageFlags usage, Image& out);
    void destroyImage(Image& image);
    bool oneTimeSubmit(void (*record)(VkCommandBuffer, void*), void* data);
    VkShaderModule shaderModule(const uint32_t* code, size_t bytes) const;

    bool headless_ = true;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physical_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat colorFormat_ = VK_FORMAT_R8G8B8A8_UNORM;
    VkExtent2D extent_ = {0, 0};
    std::vector<VkImage> swapImages_;
    std::vector<VkImageView> swapViews_;
    std::vector<VkFramebuffer> swapFramebuffers_;
    Image headlessImage_;
    VkRenderPass offscreenPass_ = VK_NULL_HANDLE;
    VkRenderPass presentPass_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout meshLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout quadLayout_ = VK_NULL_HANDLE;
    VkPipeline meshPipeline_ = VK_NULL_HANDLE;
    VkPipeline quadPipeline_ = VK_NULL_HANDLE;
    VkSampler coverSampler_ = VK_NULL_HANDLE;
    VkSampler targetSampler_ = VK_NULL_HANDLE;
    VkDescriptorPool pool_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    std::vector<VkSemaphore> imageAvailable_;
    std::vector<VkSemaphore> renderFinished_;
    uint32_t frame_ = 0;
    bool outOfDate_ = false;
    std::vector<State> states_;
    int nextId_ = 1;
};

}  // namespace pelican
