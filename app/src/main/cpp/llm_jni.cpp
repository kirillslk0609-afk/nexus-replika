#include <jni.h>
#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "llama.h"

namespace {
struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    ~Engine() { if (ctx) llama_free(ctx); if (model) llama_model_free(model); }
};
void fail(JNIEnv * env, const std::string & message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}
std::string string(JNIEnv * env, jstring value) {
    // Convert UTF-16 through Java's UTF-8 codec: GetStringUTFChars uses modified UTF-8.
    auto cls = env->FindClass("java/lang/String");
    auto method = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    auto charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, method, charset));
    if (env->ExceptionCheck() || !bytes) throw std::runtime_error("UTF-8 conversion failed");
    std::string out(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte *>(out.data()));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(charset); env->DeleteLocalRef(cls);
    return out;
}
void fill(llama_batch_ext * batch, const llama_token * tokens, int count, int position) {
    llama_batch_ext_clear(batch);
    for (int i = 0; i < count; ++i) {
        int index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        llama_pos pos = position + i;
        llama_batch_ext_set_pos(batch, index, &pos);
    }
    llama_batch_ext_set_output_logits(batch, count - 1, true);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_neuroscribe_app_engine_LocalLlm_nativeOpen(JNIEnv * env, jobject, jstring path, jint threads) {
    try {
        llama_backend_init();
        auto engine = std::make_unique<Engine>();
        auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
        engine->model = llama_model_load_from_file(string(env, path).c_str(), mp);
        if (!engine->model) throw std::runtime_error("Cannot load GGUF model");
        auto cp = llama_context_default_params();
        cp.n_ctx = 2048; cp.n_batch = 512; cp.n_ubatch = 128;
        cp.n_threads = threads; cp.n_threads_batch = threads;
        engine->ctx = llama_init_from_model(engine->model, cp);
        if (!engine->ctx) throw std::runtime_error("Cannot allocate LLM context");
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception & e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_neuroscribe_app_engine_LocalLlm_nativeGenerate(JNIEnv * env, jobject instance, jlong handle, jstring input, jstring transcript, jboolean optimized) {
    try {
        auto * engine = reinterpret_cast<Engine *>(handle);
        if (!engine) throw std::runtime_error("LLM is closed");
        const auto prompt = string(env, input);
        auto * vocab = llama_model_get_vocab(engine->model);
        const auto source = string(env, transcript);
        const int source_tokens = -llama_tokenize(vocab, source.data(), source.size(), nullptr, 0, false, false);
        // Punctuation should be comparable in length to its input. Bound runaway
        // completions on brief acknowledgements; the lexical guard rejects truncation.
        const int output_limit = std::clamp(source_tokens * 2 + 32, 64, 512);
        int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (count <= 0 || count > 1450) throw std::runtime_error("Transcript fragment exceeds LLM context");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) < 0)
            throw std::runtime_error("Tokenization failed");
        llama_memory_clear(llama_get_memory(engine->ctx), true);
        std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(engine->ctx), llama_batch_ext_free);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        if (!batch || !sampler) throw std::runtime_error("LLM allocation failed");
        for (int pos = 0; pos < count; ) {
            const int chunk = std::min(512, count - pos);
            fill(batch.get(), tokens.data() + pos, chunk, pos);
            if (llama_process(engine->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) throw std::runtime_error("Prompt inference failed");
            pos += chunk;
        }
        std::string output;
        jmethodID may_continue = nullptr;
        if (optimized) {
            auto cls = env->GetObjectClass(instance);
            may_continue = env->GetMethodID(cls, "mayContinue", "([B)Z");
            env->DeleteLocalRef(cls);
            if (!may_continue) return nullptr;
        }
        for (int i = 0; i < output_limit && count + i < 2048; ++i) {
            auto token = llama_sampler_sample(sampler.get(), engine->ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            std::vector<char> piece(256);
            int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (n < 0) throw std::runtime_error("Token decoding failed");
            output.append(piece.data(), n);
            if (may_continue) {
                auto partial = env->NewByteArray(static_cast<jsize>(output.size()));
                if (!partial) return nullptr;
                env->SetByteArrayRegion(partial, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
                const bool keep_going = env->CallBooleanMethod(instance, may_continue, partial);
                env->DeleteLocalRef(partial);
                if (env->ExceptionCheck()) return nullptr;
                // The unchanged final lexical guard rejects this incompatible prefix,
                // preserving the ASR text just as after a full invalid completion.
                if (!keep_going) break;
            }
            fill(batch.get(), &token, 1, count + i);
            if (llama_process(engine->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) throw std::runtime_error("LLM decoding failed");
        }
        auto result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_ru_neuroscribe_app_engine_LocalLlm_nativeClose(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}
