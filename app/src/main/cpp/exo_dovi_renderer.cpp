#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <libplacebo/config.h>
#include <libdovi/rpu_parser.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <new>
#include <vector>

namespace {

constexpr int kMaxImages = 4;
constexpr size_t kMaxExpectedFrames = 16;

constexpr jint kCapabilityImageReader = 1 << 0;
constexpr jint kCapabilityVulkan11 = 1 << 1;
constexpr jint kCapabilityAhbImport = 1 << 2;
constexpr jint kCapabilityYcbcrConversion = 1 << 3;
constexpr jint kCapabilityForeignQueue = 1 << 4;
constexpr jint kCapabilityLibplacebo375 = 1 << 5;
constexpr jint kCapabilityLibdovi = 1 << 6;

struct ExpectedFrame {
    int64_t imageTimestampNs;
    int64_t presentationTimeUs;
};

struct Renderer {
    AImageReader *reader = nullptr;
    std::mutex callbackMutex;
    std::mutex expectedMutex;
    std::deque<ExpectedFrame> expectedFrames;
    std::atomic<int64_t> acquiredFrames{0};
    std::atomic<int64_t> ahbFrames{0};
    std::atomic<int64_t> sampledUsageFrames{0};
    std::atomic<int64_t> highDepthFrames{0};
    std::atomic<int64_t> matchedFrames{0};
    std::atomic<int64_t> unmatchedFrames{0};
    std::atomic<int64_t> expectedQueueDrops{0};
    std::atomic<int64_t> lastImageTimestampNs{0};
    std::atomic<int64_t> lastPresentationTimeUs{0};
    std::atomic<int64_t> lastAhbFormat{0};
};

bool hasExtension(const std::vector<VkExtensionProperties> &extensions,
                  const char *name) {
    return std::any_of(extensions.begin(), extensions.end(),
                       [name](const VkExtensionProperties &extension) {
                           return strcmp(extension.extensionName, name) == 0;
                       });
}

bool isHighDepthFormat(uint32_t format) {
    switch (format) {
        case AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT:
        case AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM:
#ifdef AHARDWAREBUFFER_FORMAT_YCbCr_P010
        case AHARDWAREBUFFER_FORMAT_YCbCr_P010:
#endif
#ifdef AHARDWAREBUFFER_FORMAT_YCbCr_P210
        case AHARDWAREBUFFER_FORMAT_YCbCr_P210:
#endif
            return true;
        default:
            return false;
    }
}

jint probeVulkanDevice(VkInstance instance, VkPhysicalDevice device) {
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(device, &properties);
    if (VK_VERSION_MAJOR(properties.apiVersion) < 1
            || (VK_VERSION_MAJOR(properties.apiVersion) == 1
            && VK_VERSION_MINOR(properties.apiVersion) < 1)) {
        return 0;
    }

    uint32_t extensionCount = 0;
    if (vkEnumerateDeviceExtensionProperties(
                device, nullptr, &extensionCount, nullptr) != VK_SUCCESS) {
        return 0;
    }
    std::vector<VkExtensionProperties> extensions(extensionCount);
    if (vkEnumerateDeviceExtensionProperties(
                device, nullptr, &extensionCount, extensions.data()) != VK_SUCCESS) {
        return 0;
    }
    if (!hasExtension(extensions,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)
            || !hasExtension(extensions,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
        return kCapabilityVulkan11;
    }

    auto getFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2"));
    if (getFeatures2 == nullptr) return kCapabilityVulkan11;
    VkPhysicalDeviceVulkan11Features features11{};
    features11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
    VkPhysicalDeviceFeatures2 features2{};
    features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features2.pNext = &features11;
    getFeatures2(device, &features2);
    if (features11.samplerYcbcrConversion != VK_TRUE) {
        return kCapabilityVulkan11 | kCapabilityAhbImport
                | kCapabilityForeignQueue;
    }

    uint32_t queueCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, nullptr);
    std::vector<VkQueueFamilyProperties> queues(queueCount);
    vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, queues.data());
    uint32_t queueFamily = UINT32_MAX;
    for (uint32_t index = 0; index < queueCount; index++) {
        if (queues[index].queueCount > 0
                && (queues[index].queueFlags
                & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) != 0) {
            queueFamily = index;
            break;
        }
    }
    if (queueFamily == UINT32_MAX) return kCapabilityVulkan11;

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char *enabledExtensions[] = {
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
    };
    VkPhysicalDeviceVulkan11Features enabledFeatures11{};
    enabledFeatures11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
    enabledFeatures11.samplerYcbcrConversion = VK_TRUE;
    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.pNext = &enabledFeatures11;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = std::size(enabledExtensions);
    deviceInfo.ppEnabledExtensionNames = enabledExtensions;
    VkDevice logicalDevice = VK_NULL_HANDLE;
    if (vkCreateDevice(device, &deviceInfo, nullptr, &logicalDevice) != VK_SUCCESS) {
        return kCapabilityVulkan11;
    }
    auto getAhbProperties = reinterpret_cast<
            PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(
                    logicalDevice,
                    "vkGetAndroidHardwareBufferPropertiesANDROID"));
    vkDestroyDevice(logicalDevice, nullptr);
    return getAhbProperties == nullptr ? kCapabilityVulkan11
            : kCapabilityVulkan11 | kCapabilityAhbImport
            | kCapabilityYcbcrConversion | kCapabilityForeignQueue;
}

jint probeVulkan() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "WebHTV Exo DV5 probe";
    appInfo.applicationVersion = 1;
    appInfo.pEngineName = "WebHTV";
    appInfo.engineVersion = 1;
    appInfo.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&instanceInfo, nullptr, &instance) != VK_SUCCESS) return 0;

    jint result = 0;
    uint32_t deviceCount = 0;
    if (vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr) == VK_SUCCESS
            && deviceCount > 0) {
        std::vector<VkPhysicalDevice> devices(deviceCount);
        if (vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data()) == VK_SUCCESS) {
            for (VkPhysicalDevice device : devices) {
                jint deviceResult = probeVulkanDevice(instance, device);
                if (deviceResult == (kCapabilityVulkan11
                        | kCapabilityAhbImport
                        | kCapabilityYcbcrConversion
                        | kCapabilityForeignQueue)) {
                    result = deviceResult;
                    break;
                }
            }
        }
    }
    vkDestroyInstance(instance, nullptr);
    return result;
}

bool probeImageReader(JNIEnv *env) {
    AImageReader *reader = nullptr;
    media_status_t status = AImageReader_newWithUsage(
            16,
            16,
            AIMAGE_FORMAT_PRIVATE,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            2,
            &reader);
    if (status != AMEDIA_OK || reader == nullptr) return false;
    ANativeWindow *window = nullptr;
    status = AImageReader_getWindow(reader, &window);
    jobject surface = status == AMEDIA_OK && window != nullptr
            ? ANativeWindow_toSurface(env, window) : nullptr;
    bool available = surface != nullptr;
    if (surface != nullptr) env->DeleteLocalRef(surface);
    AImageReader_delete(reader);
    return available;
}

void matchTimestamp(Renderer *renderer, int64_t timestampNs) {
    std::lock_guard<std::mutex> lock(renderer->expectedMutex);
    while (!renderer->expectedFrames.empty()
            && renderer->expectedFrames.front().imageTimestampNs < timestampNs) {
        renderer->expectedFrames.pop_front();
        renderer->unmatchedFrames.fetch_add(1, std::memory_order_relaxed);
    }
    if (!renderer->expectedFrames.empty()
            && renderer->expectedFrames.front().imageTimestampNs == timestampNs) {
        ExpectedFrame frame = renderer->expectedFrames.front();
        renderer->expectedFrames.pop_front();
        renderer->lastPresentationTimeUs.store(
                frame.presentationTimeUs, std::memory_order_relaxed);
        renderer->matchedFrames.fetch_add(1, std::memory_order_relaxed);
    } else {
        renderer->unmatchedFrames.fetch_add(1, std::memory_order_relaxed);
    }
}

void onImageAvailable(void *context, AImageReader *reader) {
    auto *renderer = static_cast<Renderer *>(context);
    if (renderer == nullptr) return;
    std::lock_guard<std::mutex> callbackLock(renderer->callbackMutex);
    for (;;) {
        AImage *image = nullptr;
        media_status_t status = AImageReader_acquireNextImage(reader, &image);
        if (status != AMEDIA_OK || image == nullptr) break;

        int64_t timestampNs = 0;
        if (AImage_getTimestamp(image, &timestampNs) == AMEDIA_OK) {
            renderer->lastImageTimestampNs.store(
                    timestampNs, std::memory_order_relaxed);
            matchTimestamp(renderer, timestampNs);
        }

        renderer->acquiredFrames.fetch_add(1, std::memory_order_relaxed);
        AHardwareBuffer *buffer = nullptr;
        if (AImage_getHardwareBuffer(image, &buffer) == AMEDIA_OK
                && buffer != nullptr) {
            AHardwareBuffer_Desc desc{};
            AHardwareBuffer_describe(buffer, &desc);
            renderer->ahbFrames.fetch_add(1, std::memory_order_relaxed);
            renderer->lastAhbFormat.store(desc.format, std::memory_order_relaxed);
            if ((desc.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE) != 0) {
                renderer->sampledUsageFrames.fetch_add(1, std::memory_order_relaxed);
            }
            if (isHighDepthFormat(desc.format)) {
                renderer->highDepthFrames.fetch_add(1, std::memory_order_relaxed);
            }
        }
        AImage_delete(image);
    }
}

Renderer *fromHandle(jlong handle) {
    return reinterpret_cast<Renderer *>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_fongmi_android_tv_player_exo_ExoDv5Native_nativeProbeCapabilities(
        JNIEnv *env, jclass) {
    jint result = probeVulkan();
    if (probeImageReader(env)) result |= kCapabilityImageReader;
    const char *placeboVersion = pl_version();
    if (PL_API_VER == 375 && placeboVersion != nullptr
            && placeboVersion[0] != '\0') {
        result |= kCapabilityLibplacebo375;
    }
    // Parse a deliberately invalid byte so every ABI verifies both the
    // vendored parser link and its failure-containment contract.
    const uint8_t invalidRpu = 0;
    DoviRpuOpaque *rpu = dovi_parse_unspec62_nalu(&invalidRpu, 1);
    if (rpu != nullptr) {
        if (dovi_rpu_get_error(rpu) != nullptr) result |= kCapabilityLibdovi;
        dovi_rpu_free(rpu);
    }
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fongmi_android_tv_player_exo_ExoDv5Native_nativeCreate(
        JNIEnv *, jclass, jint width, jint height) {
    if (width <= 0 || height <= 0) return 0;
    auto *renderer = new (std::nothrow) Renderer();
    if (renderer == nullptr) return 0;
    media_status_t status = AImageReader_newWithUsage(
            width,
            height,
            AIMAGE_FORMAT_PRIVATE,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            kMaxImages,
            &renderer->reader);
    if (status != AMEDIA_OK || renderer->reader == nullptr) {
        delete renderer;
        return 0;
    }
    AImageReader_ImageListener listener{
            .context = renderer,
            .onImageAvailable = onImageAvailable,
    };
    if (AImageReader_setImageListener(renderer->reader, &listener) != AMEDIA_OK) {
        AImageReader_delete(renderer->reader);
        delete renderer;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(renderer));
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_fongmi_android_tv_player_exo_ExoDv5Native_nativeGetInputSurface(
        JNIEnv *env, jclass, jlong handle) {
    Renderer *renderer = fromHandle(handle);
    if (renderer == nullptr || renderer->reader == nullptr) return nullptr;
    ANativeWindow *window = nullptr;
    if (AImageReader_getWindow(renderer->reader, &window) != AMEDIA_OK
            || window == nullptr) {
        return nullptr;
    }
    return ANativeWindow_toSurface(env, window);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fongmi_android_tv_player_exo_ExoDv5Native_nativeQueueFrame(
        JNIEnv *, jclass, jlong handle, jlong imageTimestampNs,
        jlong presentationTimeUs) {
    Renderer *renderer = fromHandle(handle);
    if (renderer == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(renderer->expectedMutex);
    if (renderer->expectedFrames.size() >= kMaxExpectedFrames) {
        renderer->expectedFrames.pop_front();
        renderer->expectedQueueDrops.fetch_add(1, std::memory_order_relaxed);
    }
    renderer->expectedFrames.push_back(
            ExpectedFrame{imageTimestampNs, presentationTimeUs});
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fongmi_android_tv_player_exo_ExoDv5Native_nativeClearFrames(
        JNIEnv *, jclass, jlong handle) {
    Renderer *renderer = fromHandle(handle);
    if (renderer == nullptr) return;
    std::lock_guard<std::mutex> lock(renderer->expectedMutex);
    renderer->expectedFrames.clear();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_fongmi_android_tv_player_exo_ExoDv5Native_nativeGetStats(
        JNIEnv *env, jclass, jlong handle) {
    Renderer *renderer = fromHandle(handle);
    if (renderer == nullptr) return nullptr;
    int64_t pending = 0;
    {
        std::lock_guard<std::mutex> lock(renderer->expectedMutex);
        pending = static_cast<int64_t>(renderer->expectedFrames.size());
    }
    jlong values[] = {
            renderer->acquiredFrames.load(std::memory_order_relaxed),
            renderer->ahbFrames.load(std::memory_order_relaxed),
            renderer->sampledUsageFrames.load(std::memory_order_relaxed),
            renderer->highDepthFrames.load(std::memory_order_relaxed),
            renderer->matchedFrames.load(std::memory_order_relaxed),
            renderer->unmatchedFrames.load(std::memory_order_relaxed),
            renderer->expectedQueueDrops.load(std::memory_order_relaxed),
            renderer->lastImageTimestampNs.load(std::memory_order_relaxed),
            renderer->lastPresentationTimeUs.load(std::memory_order_relaxed),
            renderer->lastAhbFormat.load(std::memory_order_relaxed),
            pending,
    };
    jlongArray result = env->NewLongArray(std::size(values));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, std::size(values), values);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fongmi_android_tv_player_exo_ExoDv5Native_nativeRelease(
        JNIEnv *, jclass, jlong handle) {
    Renderer *renderer = fromHandle(handle);
    if (renderer == nullptr) return;
    if (renderer->reader != nullptr) {
        AImageReader_setImageListener(renderer->reader, nullptr);
        std::lock_guard<std::mutex> callbackLock(renderer->callbackMutex);
        AImageReader_delete(renderer->reader);
        renderer->reader = nullptr;
    }
    delete renderer;
}
