#include <jni.h>
#include <onnxruntime_cxx_api.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr char kQnnProvider[] = "QNNExecutionProvider";
constexpr size_t kFeatureCount = 80 * 3000;
constexpr size_t kDecoderLayers = 12;
constexpr size_t kMaskLength = 200;
constexpr size_t kVocabularySize = 51865;
constexpr double kFeatureScale = 4.677007018472068e-05;
constexpr int kFeatureZero = 32072;
constexpr double kLogitScale = 0.0012925398768857121;
constexpr uint8_t kZeroCache = 128;
constexpr uint16_t kAttentionAllowed = 65535;
constexpr int32_t kEndOfText = 50257;
constexpr int32_t kStartOfTranscript = 50258;
constexpr int32_t kFirstLanguage = 50259;
constexpr int32_t kLastLanguage = 50357;
constexpr int32_t kTranscribe = 50359;
constexpr int32_t kNoTimestamps = 50363;

// The two pinned Qualcomm Whisper Small v0.66.0 packs share their tensor layout
// and quantizers. Their cache input and output quantizers agree for every layer.
// Suppression follows openai/whisper-small generation_config.json in no-timestamp mode.
const std::array<bool, kVocabularySize> kSuppressedTokens = [] {
    std::array<bool, kVocabularySize> result{};
    constexpr int tokens[] = {
        1, 2, 7, 8, 9, 10, 14, 25, 26, 27, 28, 29, 31, 58, 59, 60, 61, 62, 63,
        90, 91, 92, 93, 359, 503, 522, 542, 873, 893, 902, 918, 922, 931,
        1350, 1853, 1982, 2460, 2627, 3246, 3253, 3268, 3536, 3846, 3961, 4183,
        4667, 6585, 6647, 7273, 9061, 9383, 10428, 10929, 11938, 12033, 12331,
        12562, 13793, 14157, 14635, 15265, 15618, 16553, 16604, 18362, 18956,
        20075, 21675, 22520, 26130, 26161, 26435, 28279, 29464, 31650, 32302,
        32470, 36865, 42863, 47425, 49870, 50254,
    };
    for (int token : tokens) result[token] = true;
    // EOS is the only non-text token allowed after the forced prompt.
    std::fill(result.begin() + kStartOfTranscript, result.end(), true);
    return result;
}();

std::string javaString(JNIEnv* jni, jstring value) {
    if (value == nullptr) throw std::invalid_argument("Missing QNN file path");
    const char* chars = jni->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) throw std::runtime_error("Cannot read QNN file path");
    const auto release = [jni, value](const char* p) { jni->ReleaseStringUTFChars(value, p); };
    const std::unique_ptr<const char, decltype(release)> owned(chars, release);
    return std::string(owned.get());
}

void throwJavaIOException(JNIEnv* jni, const char* message) {
    if (jni->ExceptionCheck()) return;
    if (jclass type = jni->FindClass("java/io/IOException")) jni->ThrowNew(type, message);
}

Ort::Env& qnnEnvironment(const std::string& plugin, const std::string& htp) {
    static Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "rtranslator-whisper-qnn");
    static std::mutex mutex;
    static std::string registeredPlugin;
    std::lock_guard<std::mutex> lock(mutex);
    if (registeredPlugin.empty()) {
        const auto separator = htp.find_last_of('/');
        if (separator == std::string::npos || htp.substr(separator + 1) != "libQnnHtp.so") {
            throw std::invalid_argument("An absolute libQnnHtp.so path is required");
        }
        // FastRPC resolves the device-specific DSP skeleton through this search path.
        const char* previous = std::getenv("ADSP_LIBRARY_PATH");
        std::string searchPath = htp.substr(0, separator);
        if (previous && *previous) searchPath += ";" + std::string(previous);
        if (setenv("ADSP_LIBRARY_PATH", searchPath.c_str(), 1) != 0) {
            throw std::runtime_error("Cannot configure the Qualcomm DSP search path");
        }
        env.DisableTelemetryEvents();
        env.RegisterExecutionProviderLibrary(kQnnProvider, plugin);
        registeredPlugin = plugin;
    }
    if (registeredPlugin != plugin) throw std::runtime_error("The QNN plugin path changed");
    return env;
}

Ort::SessionOptions sessionOptions(Ort::Env& env, const std::string& htp,
                                   const std::string& profilePrefix) {
    std::vector<Ort::ConstEpDevice> devices;
    for (auto device : env.GetEpDevices()) {
        if (std::string(device.EpName()) == kQnnProvider) devices.push_back(device);
    }
    if (devices.empty()) throw std::runtime_error("Qualcomm QNN device is unavailable");
    Ort::SessionOptions options;
    options.AppendExecutionProvider_V2(env, devices, std::unordered_map<std::string, std::string>{
            {"backend_path", htp}, {"htp_performance_mode", "balanced"},
    });
    // CPU may execute the Q/DQ wrappers. The pinned EPContext graph requires HTP,
    // and the first real invocation must independently prove the QNN assignment.
    options.EnableProfiling(profilePrefix.c_str());
    return options;
}

struct Tensor {
    std::string name;
    std::vector<int64_t> shape;
    ONNXTensorElementDataType type;
    size_t elements;
};

std::vector<Tensor> sessionTensors(Ort::Session& session, bool inputs) {
    std::vector<Tensor> tensors;
    Ort::AllocatorWithDefaultOptions allocator;
    const size_t count = inputs ? session.GetInputCount() : session.GetOutputCount();
    tensors.reserve(count);
    for (size_t index = 0; index < count; ++index) {
        const auto name = inputs ? session.GetInputNameAllocated(index, allocator)
                                 : session.GetOutputNameAllocated(index, allocator);
        const auto type = inputs ? session.GetInputTypeInfo(index) : session.GetOutputTypeInfo(index);
        const auto tensor = type.GetTensorTypeAndShapeInfo();
        auto shape = tensor.GetShape();
        size_t elements = 1;
        for (const auto dimension : shape) {
            if (dimension <= 0 || static_cast<size_t>(dimension) > 2000000 / elements) {
                throw std::runtime_error("Unexpected Whisper tensor shape");
            }
            elements *= static_cast<size_t>(dimension);
        }
        tensors.push_back({name.get(), std::move(shape), tensor.GetElementType(), elements});
    }
    return tensors;
}

std::vector<const char*> tensorNames(const std::vector<Tensor>& tensors) {
    std::vector<const char*> names;
    names.reserve(tensors.size());
    for (const auto& tensor : tensors) names.push_back(tensor.name.c_str());
    return names;
}

size_t tensorIndex(const std::vector<Tensor>& tensors, const std::string& name,
                   ONNXTensorElementDataType type, const std::vector<int64_t>& shape) {
    for (size_t index = 0; index < tensors.size(); ++index) {
        const auto& tensor = tensors[index];
        if (tensor.name == name && tensor.type == type && tensor.shape == shape) return index;
    }
    throw std::runtime_error("Unexpected Whisper tensor: " + name);
}

class Model {
public:
    Model(Ort::Env& env, const std::string& model, const std::string& htp,
          const std::string& profilePrefix)
            : session_(env, model.c_str(), sessionOptions(env, htp, profilePrefix)),
              inputs(sessionTensors(session_, true)), outputs(sessionTensors(session_, false)),
              inputNames_(tensorNames(inputs)), outputNames_(tensorNames(outputs)) {}

    ~Model() {
        if (!profiling_) return;
        try {
            Ort::AllocatorWithDefaultOptions allocator;
            const auto path = session_.EndProfilingAllocated(allocator);
            if (path && *path) std::remove(path.get());
        } catch (const std::exception&) {
            // Destruction must also be safe after failed device execution.
        }
    }

    std::vector<Ort::Value> run(const std::vector<Ort::Value>& values) {
        auto result = session_.Run(Ort::RunOptions{nullptr}, inputNames_.data(), values.data(),
                                   values.size(), outputNames_.data(), outputNames_.size());
        if (profiling_) verifyProfile();
        return result;
    }

private:
    void verifyProfile() {
        Ort::AllocatorWithDefaultOptions allocator;
        const auto path = session_.EndProfilingAllocated(allocator);
        profiling_ = false;
        if (!path || !*path) throw std::runtime_error("QNN execution profile is missing");
        std::ifstream profile(path.get());
        std::remove(path.get());
        std::string line;
        while (std::getline(profile, line)) {
            const auto provider = line.find("\"provider\"");
            if (provider != std::string::npos) {
                const auto separator = line.find(':', provider + 10);
                const auto value = line.find_first_not_of(" \t", separator + 1);
                if (separator != std::string::npos && value != std::string::npos &&
                    line.compare(value, sizeof(kQnnProvider) + 1, "\"QNNExecutionProvider\"") == 0) {
                    return;
                }
            }
        }
        throw std::runtime_error("The Whisper model did not execute on the QNN HTP provider");
    }

    Ort::Session session_;

public:
    const std::vector<Tensor> inputs;
    const std::vector<Tensor> outputs;

private:
    const std::vector<const char*> inputNames_;
    const std::vector<const char*> outputNames_;
    bool profiling_ = true;
};

enum class InputSource { Token, Position, Mask, CrossCache, SelfCache };

struct InputBinding {
    InputSource source = InputSource::Token;
    size_t outputIndex = 0;
};

struct Transcription {
    std::vector<jlong> tokens;
    double meanLogProbability;
};

class WhisperBackend {
public:
    WhisperBackend(const std::string& encoder, const std::string& decoder, const std::string& htp,
                   const std::string& plugin, const std::string& profile)
            : encoder_(qnnEnvironment(plugin, htp), encoder, htp, profile + "-encoder"),
              decoder_(qnnEnvironment(plugin, htp), decoder, htp, profile + "-decoder") {
        constexpr auto u8 = ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8;
        constexpr auto u16 = ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16;
        constexpr auto i32 = ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32;
        if (encoder_.inputs.size() != 1 || encoder_.outputs.size() != kDecoderLayers * 2 ||
            decoder_.inputs.size() != kDecoderLayers * 4 + 3 ||
            decoder_.outputs.size() != kDecoderLayers * 2 + 1) {
            throw std::runtime_error("Unexpected Whisper model signature");
        }
        tensorIndex(encoder_.inputs, "input_features", u16, {1, 80, 3000});
        logits_ = tensorIndex(decoder_.outputs, "logits", u16, {1, kVocabularySize, 1, 1});
        bindings_.resize(decoder_.inputs.size());
        bindings_[tensorIndex(decoder_.inputs, "input_ids", i32, {1, 1})] = {InputSource::Token};
        bindings_[tensorIndex(decoder_.inputs, "position_ids", i32, {1})] = {InputSource::Position};
        bindings_[tensorIndex(decoder_.inputs, "attention_mask", u16, {1, 1, 1, kMaskLength})] =
                {InputSource::Mask};
        for (size_t layer = 0; layer < kDecoderLayers; ++layer) {
            for (const char component : {'k', 'v'}) {
                const auto suffix = std::to_string(layer);
                const std::string cross = component + std::string("_cache_cross_") + suffix;
                const std::string self = component + std::string("_cache_self_") + suffix;
                const bool key = component == 'k';
                const std::vector<int64_t> crossShape = key ? std::vector<int64_t>{12, 1, 64, 1500}
                                                            : std::vector<int64_t>{12, 1, 1500, 64};
                const std::vector<int64_t> selfShape = key ? std::vector<int64_t>{12, 1, 64, 199}
                                                           : std::vector<int64_t>{12, 1, 199, 64};
                bindings_[tensorIndex(decoder_.inputs, cross, u8, crossShape)] =
                        {InputSource::CrossCache, tensorIndex(encoder_.outputs, cross, u8, crossShape)};
                bindings_[tensorIndex(decoder_.inputs, self + "_in", u8, selfShape)] =
                        {InputSource::SelfCache, tensorIndex(decoder_.outputs, self + "_out", u8, selfShape)};
            }
        }
    }

    Transcription transcribe(const std::vector<float>& mel, int32_t language) {
        if (language < kFirstLanguage || language > kLastLanguage) {
            throw std::invalid_argument("Invalid Whisper language token");
        }
        std::vector<uint16_t> features;
        features.reserve(kFeatureCount);
        for (const float value : mel) {
            if (!std::isfinite(value)) throw std::invalid_argument("Non-finite Whisper mel feature");
            const double quantized = std::nearbyint(value / kFeatureScale) + kFeatureZero;
            features.push_back(static_cast<uint16_t>(std::clamp(quantized, 0.0, 65535.0)));
        }
        std::vector<Ort::Value> featuresInput;
        featuresInput.push_back(tensorView(encoder_.inputs[0], features.data(), sizeof(uint16_t)));
        auto cross = encoder_.run(featuresInput);

        const std::array<int32_t, 4> prompt{kStartOfTranscript, language, kTranscribe, kNoTimestamps};
        std::vector<uint8_t> emptyCache(kDecoderLayers * 64 * (kMaskLength - 1), kZeroCache);
        std::array<uint16_t, kMaskLength> mask{};
        std::vector<Ort::Value> previous;
        std::vector<jlong> tokens;
        double totalLogProbability = 0;
        int32_t token = prompt.front();
        for (int32_t position = 0; position < static_cast<int32_t>(kMaskLength - 1); ++position) {
            if (position < static_cast<int32_t>(prompt.size())) token = prompt[position];
            mask[kMaskLength - 1 - position] = kAttentionAllowed;
            std::vector<Ort::Value> inputs;
            inputs.reserve(bindings_.size());
            for (size_t index = 0; index < bindings_.size(); ++index) {
                const auto& binding = bindings_[index];
                const auto& spec = decoder_.inputs[index];
                switch (binding.source) {
                    case InputSource::Token:
                        inputs.push_back(tensorView(spec, &token, sizeof(token)));
                        break;
                    case InputSource::Position:
                        inputs.push_back(tensorView(spec, &position, sizeof(position)));
                        break;
                    case InputSource::Mask:
                        inputs.push_back(tensorView(spec, mask.data(), sizeof(uint16_t)));
                        break;
                    case InputSource::CrossCache:
                        inputs.push_back(tensorView(spec, cross[binding.outputIndex].GetTensorMutableData<uint8_t>(), 1));
                        break;
                    case InputSource::SelfCache:
                        auto* cache = previous.empty() ? emptyCache.data()
                                                      : previous[binding.outputIndex].GetTensorMutableData<uint8_t>();
                        inputs.push_back(tensorView(spec, cache, 1));
                        break;
                }
            }
            auto decoded = decoder_.run(inputs);
            inputs.clear();
            previous = std::move(decoded);
            if (position + 1 < static_cast<int32_t>(prompt.size())) continue;
            const auto* logits = previous[logits_].GetTensorData<uint16_t>();
            token = bestToken(logits, tokens.empty());
            totalLogProbability += logProbability(logits, token);
            if (token == kEndOfText) {
                const double mean = totalLogProbability / (tokens.size() + 1);
                return {std::move(tokens), mean};
            }
            tokens.push_back(token);
        }
        throw std::runtime_error("Whisper exceeded the QNN decoder context");
    }

private:
    Ort::Value tensorView(const Tensor& spec, void* data, size_t width) const {
        return Ort::Value::CreateTensor(memory_, data, spec.elements * width, spec.shape.data(),
                                        spec.shape.size(), spec.type);
    }

    static int32_t bestToken(const uint16_t* logits, bool first) {
        int32_t best = -1;
        for (int32_t token = 0; token <= kEndOfText; ++token) {
            if (kSuppressedTokens[token] || (first && (token == 220 || token == kEndOfText))) continue;
            if (best == -1 || logits[token] > logits[best]) best = token;
        }
        return best;
    }

    static double logProbability(const uint16_t* logits, int32_t token) {
        const int maximum = *std::max_element(logits, logits + kVocabularySize);
        double denominator = 0;
        for (size_t index = 0; index < kVocabularySize; ++index) {
            denominator += std::exp((logits[index] - maximum) * kLogitScale);
        }
        return (logits[token] - maximum) * kLogitScale - std::log(denominator);
    }

    Ort::MemoryInfo memory_ = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    Model encoder_;
    Model decoder_;
    std::vector<InputBinding> bindings_;
    size_t logits_ = 0;
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_nie_translator_rtranslator_voice_1translation_neural_1networks_voice_QnnWhisperBackend_open(
        JNIEnv* jni, jclass, jstring encoder, jstring decoder, jstring htp, jstring plugin, jstring profile) {
    try {
        auto backend = std::make_unique<WhisperBackend>(javaString(jni, encoder), javaString(jni, decoder),
                javaString(jni, htp), javaString(jni, plugin), javaString(jni, profile));
        return reinterpret_cast<jlong>(backend.release());
    } catch (const std::exception& error) {
        throwJavaIOException(jni, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_nie_translator_rtranslator_voice_1translation_neural_1networks_voice_QnnWhisperBackend_transcribe(
        JNIEnv* jni, jclass, jlong handle, jfloatArray mel, jint language, jdoubleArray scoreOut) {
    try {
        if (!handle || !mel || jni->GetArrayLength(mel) != kFeatureCount ||
            !scoreOut || jni->GetArrayLength(scoreOut) != 1) {
            throw std::invalid_argument("Invalid QNN Whisper input");
        }
        std::vector<float> features(kFeatureCount);
        jni->GetFloatArrayRegion(mel, 0, kFeatureCount, features.data());
        if (jni->ExceptionCheck()) return nullptr;
        auto result = reinterpret_cast<WhisperBackend*>(handle)->transcribe(features, language);
        auto tokens = jni->NewLongArray(static_cast<jsize>(result.tokens.size()));
        if (!tokens) return nullptr;
        if (!result.tokens.empty()) {
            jni->SetLongArrayRegion(tokens, 0, static_cast<jsize>(result.tokens.size()), result.tokens.data());
        }
        if (!jni->ExceptionCheck()) jni->SetDoubleArrayRegion(scoreOut, 0, 1, &result.meanLogProbability);
        return tokens;
    } catch (const std::exception& error) {
        throwJavaIOException(jni, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_nie_translator_rtranslator_voice_1translation_neural_1networks_voice_QnnWhisperBackend_close(
        JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<WhisperBackend*>(handle);
}
