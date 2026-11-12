#include <jni.h>
#include <onnxruntime_cxx_api.h>
#include <chrono>
#include <cstring>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

// Hardware feasibility smoke test, NOT speech recognition. Native C++ is required
// because the Java API cannot create UINT16 inputs for this official model pack.
static std::string str(JNIEnv* env, jstring s) {
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string value(p); env->ReleaseStringUTFChars(s, p); return value;
}
static std::string escape(const std::string& s) {
    std::string r; for (char c : s) {
        if (c == '"' || c == '\\') r += '\\';
        if (c == '\n') { r += "\\n"; continue; }
        if (c == '\r') { r += "\\r"; continue; }
        r += c;
    } return r;
}
static std::string run_component(Ort::Env& env, const std::string& path,
                                const std::string& backend, const std::string& profile) {
    Ort::SessionOptions options;
    options.AppendExecutionProvider("QNN", {{"backend_path", backend}, {"htp_performance_mode", "balanced"}});
    options.EnableProfiling(profile.c_str());
    Ort::Session session(env, path.c_str(), options);
    Ort::AllocatorWithDefaultOptions allocator;
    Ort::MemoryInfo memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    std::vector<std::string> inputNames, outputNames;
    std::vector<std::vector<unsigned char>> buffers;
    std::vector<Ort::Value> inputs;
    const size_t count = session.GetInputCount();
    buffers.reserve(count); inputs.reserve(count); inputNames.reserve(count);
    for (size_t i = 0; i < count; ++i) {
        inputNames.emplace_back(session.GetInputNameAllocated(i, allocator).get());
        auto typeInfo = session.GetInputTypeInfo(i);
        auto info = typeInfo.GetTensorTypeAndShapeInfo();
        const auto shape = info.GetShape(); const auto type = info.GetElementType();
        size_t n = 1;
        for (auto dim : shape) {
            if (dim <= 0 || n > 100000000 / static_cast<size_t>(dim)) throw std::runtime_error("Invalid/oversized fixed shape");
            n *= dim;
        }
        size_t width = 0;
        switch (type) {
          case ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8: width = 1; break;
          case ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16: width = 2; break;
          case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32: width = 4; break;
          default: throw std::runtime_error("Unexpected input dtype");
        }
        buffers.emplace_back(n * width, type == ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8 ? 128 : 0);
        if (inputNames.back() == "input_features") {
            // Quantized zero feature. Synthetic, not a recording/silence benchmark.
            auto* data = reinterpret_cast<uint16_t*>(buffers.back().data());
            for (size_t j=0; j<n; ++j) data[j] = 32072;
        }
        inputs.emplace_back(Ort::Value::CreateTensor(memory, buffers.back().data(),
            buffers.back().size(), shape.data(), shape.size(), type));
    }
    for (size_t i=0; i<session.GetOutputCount(); ++i)
        outputNames.emplace_back(session.GetOutputNameAllocated(i, allocator).get());
    std::vector<const char*> in, out;
    for (auto& name : inputNames) in.push_back(name.c_str());
    for (auto& name : outputNames) out.push_back(name.c_str());
    auto start = std::chrono::steady_clock::now();
    auto results = session.Run(Ort::RunOptions{nullptr}, in.data(), inputs.data(), inputs.size(), out.data(), out.size());
    auto ms = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
    auto profilePath = session.EndProfilingAllocated(allocator);
    std::ostringstream r; r << "{\"outputs\":" << results.size() << ",\"synthetic_run_ms\":" << ms
        << ",\"profile\":\"" << escape(profilePath.get()) << "\"}";
    return r.str();
}
extern "C" JNIEXPORT jstring JNICALL Java_nie_translator_rtranslator_qnnprobe_NativeProbe_run(
        JNIEnv* env, jclass, jstring pack, jstring libs, jstring prefix) {
    try {
        const std::string root=str(env,pack), backend=str(env,libs)+"/libQnnHtp.so", profile=str(env,prefix);
        Ort::Env ort(ORT_LOGGING_LEVEL_WARNING, "rtranslator-qnn-probe");
        auto encoder = run_component(ort, root+"/encoder.onnx", backend, profile+"-encoder");
        auto decoder = run_component(ort, root+"/decoder.onnx", backend, profile+"-decoder");
        std::string result="{\"ok\":true,\"speech_recognition_tested\":false,\"encoder\":"+encoder+",\"decoder\":"+decoder+"}";
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& e) {
        const std::string error="{\"ok\":false,\"speech_recognition_tested\":false,\"error\":\""+escape(e.what())+"\"}";
        return env->NewStringUTF(error.c_str());
    }
}
