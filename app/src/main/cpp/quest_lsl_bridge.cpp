#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <new>
#include <string>
#include <vector>

#include "liblsl_min.h"

namespace {
constexpr const char *kLogTag = "QuestLslBridge";
constexpr uint32_t kResolveBufferSize = 16;
constexpr int32_t kMaxBufferedSeconds = 5;
constexpr int32_t kMaxChunkLength = 1;
constexpr int32_t kRecoverLostStreams = 1;
constexpr double kOpenTimeoutSeconds = 2.0;

struct MonitorSession {
    lsl_inlet inlet;
    std::string resolvedName;
    std::string resolvedType;
    int32_t channelCount;
    int32_t selectedChannelIndex;
    float sampleRateHz;
};

struct JniCache {
    jclass sessionClass = nullptr;
    jmethodID sessionCtor = nullptr;
    jclass sampleClass = nullptr;
    jmethodID sampleCtor = nullptr;
    jclass illegalStateClass = nullptr;
    jclass illegalArgumentClass = nullptr;
};

JniCache gCache;

std::string SafeString(const char *value) {
    return value != nullptr ? std::string(value) : std::string();
}

std::string LastErrorString() {
    return SafeString(lsl_last_error());
}

std::string ErrorCodeName(int32_t code) {
    switch (code) {
        case lsl_no_error:
            return "no error";
        case lsl_timeout_error:
            return "timeout";
        case lsl_lost_error:
            return "stream lost";
        case lsl_argument_error:
            return "invalid argument";
        case lsl_internal_error:
            return "internal error";
        default:
            return "unknown error";
    }
}

std::string BuildErrorMessage(const std::string &prefix, int32_t code) {
    const std::string lastError = LastErrorString();
    std::string message = prefix + " (" + ErrorCodeName(code) + ")";
    if (!lastError.empty()) {
        message += ": " + lastError;
    }
    return message;
}

void ThrowJavaException(JNIEnv *env, jclass exceptionClass, const std::string &message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
    env->ThrowNew(exceptionClass, message.c_str());
}

void DestroyResolvedInfos(std::vector<lsl_streaminfo> &infos) {
    for (lsl_streaminfo info : infos) {
        if (info != nullptr) {
            lsl_destroy_streaminfo(info);
        }
    }
    infos.clear();
}

std::string JStringToStdString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jobject BuildNativeSession(JNIEnv *env, jlong handle, const MonitorSession &session) {
    jstring resolvedName = env->NewStringUTF(session.resolvedName.c_str());
    if (resolvedName == nullptr) {
        return nullptr;
    }
    jstring resolvedType = env->NewStringUTF(session.resolvedType.c_str());
    if (resolvedType == nullptr) {
        env->DeleteLocalRef(resolvedName);
        return nullptr;
    }

    jobject result = env->NewObject(
        gCache.sessionClass,
        gCache.sessionCtor,
        handle,
        resolvedName,
        resolvedType,
        session.channelCount,
        session.sampleRateHz
    );

    env->DeleteLocalRef(resolvedName);
    env->DeleteLocalRef(resolvedType);
    return result;
}

jobject BuildNativeSample(JNIEnv *env, double timestampSeconds, float value) {
    return env->NewObject(
        gCache.sampleClass,
        gCache.sampleCtor,
        timestampSeconds,
        value
    );
}

MonitorSession *AsSession(jlong handle) {
    return reinterpret_cast<MonitorSession *>(static_cast<intptr_t>(handle));
}

bool MatchesRequestedStream(
    lsl_streaminfo info,
    const std::string &requestedName,
    const std::string &requestedType
) {
    const std::string candidateName = SafeString(lsl_get_name(info));
    const std::string candidateType = SafeString(lsl_get_type(info));
    const bool nameMatches = requestedName.empty() || candidateName == requestedName;
    const bool typeMatches = requestedType.empty() || candidateType == requestedType;
    return nameMatches && typeMatches;
}

int32_t ResolveCandidates(
    const std::string &streamName,
    const std::string &streamType,
    std::vector<lsl_streaminfo> &resolvedInfos,
    double timeoutSeconds
) {
    if (!streamName.empty()) {
        return lsl_resolve_byprop(
            resolvedInfos.data(),
            static_cast<uint32_t>(resolvedInfos.size()),
            "name",
            streamName.c_str(),
            1,
            timeoutSeconds
        );
    }

    return lsl_resolve_byprop(
        resolvedInfos.data(),
        static_cast<uint32_t>(resolvedInfos.size()),
        "type",
        streamType.c_str(),
        1,
        timeoutSeconds
    );
}
} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass sessionClassLocal =
        env->FindClass("io/github/mesmerprism/rustyxr/companion/android/transport/NativeMonitorSession");
    jclass sampleClassLocal =
        env->FindClass("io/github/mesmerprism/rustyxr/companion/android/transport/NativeMonitorSample");
    jclass illegalStateLocal = env->FindClass("java/lang/IllegalStateException");
    jclass illegalArgumentLocal = env->FindClass("java/lang/IllegalArgumentException");
    if (sessionClassLocal == nullptr || sampleClassLocal == nullptr || illegalStateLocal == nullptr ||
        illegalArgumentLocal == nullptr) {
        return JNI_ERR;
    }

    gCache.sessionClass = reinterpret_cast<jclass>(env->NewGlobalRef(sessionClassLocal));
    gCache.sampleClass = reinterpret_cast<jclass>(env->NewGlobalRef(sampleClassLocal));
    gCache.illegalStateClass = reinterpret_cast<jclass>(env->NewGlobalRef(illegalStateLocal));
    gCache.illegalArgumentClass = reinterpret_cast<jclass>(env->NewGlobalRef(illegalArgumentLocal));

    env->DeleteLocalRef(sessionClassLocal);
    env->DeleteLocalRef(sampleClassLocal);
    env->DeleteLocalRef(illegalStateLocal);
    env->DeleteLocalRef(illegalArgumentLocal);

    if (gCache.sessionClass == nullptr || gCache.sampleClass == nullptr ||
        gCache.illegalStateClass == nullptr || gCache.illegalArgumentClass == nullptr) {
        return JNI_ERR;
    }

    gCache.sessionCtor = env->GetMethodID(gCache.sessionClass, "<init>", "(JLjava/lang/String;Ljava/lang/String;IF)V");
    gCache.sampleCtor = env->GetMethodID(gCache.sampleClass, "<init>", "(DF)V");
    if (gCache.sessionCtor == nullptr || gCache.sampleCtor == nullptr) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return;
    }

    if (gCache.sessionClass != nullptr) {
        env->DeleteGlobalRef(gCache.sessionClass);
        gCache.sessionClass = nullptr;
    }
    if (gCache.sampleClass != nullptr) {
        env->DeleteGlobalRef(gCache.sampleClass);
        gCache.sampleClass = nullptr;
    }
    if (gCache.illegalStateClass != nullptr) {
        env->DeleteGlobalRef(gCache.illegalStateClass);
        gCache.illegalStateClass = nullptr;
    }
    if (gCache.illegalArgumentClass != nullptr) {
        env->DeleteGlobalRef(gCache.illegalArgumentClass);
        gCache.illegalArgumentClass = nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyxr_companion_android_transport_LslNativeBridge_nativeLibraryInfo(
    JNIEnv *env,
    jclass
) {
    const std::string info = SafeString(lsl_library_info());
    return env->NewStringUTF(info.empty() ? "liblsl info unavailable" : info.c_str());
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_mesmerprism_rustyxr_companion_android_transport_LslNativeBridge_nativeOpenStream(
    JNIEnv *env,
    jclass,
    jstring streamNameValue,
    jstring streamTypeValue,
    jint channelIndex,
    jdouble resolveTimeoutSeconds
) {
    const std::string streamName = JStringToStdString(env, streamNameValue);
    const std::string streamType = JStringToStdString(env, streamTypeValue);

    if (streamName.empty() && streamType.empty()) {
        ThrowJavaException(
            env,
            gCache.illegalArgumentClass,
            "At least a stream name or type is required to resolve an LSL stream."
        );
        return nullptr;
    }
    if (channelIndex < 0) {
        ThrowJavaException(
            env,
            gCache.illegalArgumentClass,
            "LSL channel index must be zero or greater."
        );
        return nullptr;
    }

    std::vector<lsl_streaminfo> resolvedInfos(kResolveBufferSize, nullptr);
    const int32_t resolveCount =
        ResolveCandidates(streamName, streamType, resolvedInfos, resolveTimeoutSeconds);
    if (resolveCount < 0) {
        const std::string message =
            BuildErrorMessage("Could not resolve the requested LSL stream", resolveCount);
        DestroyResolvedInfos(resolvedInfos);
        ThrowJavaException(env, gCache.illegalStateClass, message);
        return nullptr;
    }
    if (resolveCount == 0) {
        DestroyResolvedInfos(resolvedInfos);
        return nullptr;
    }

    lsl_streaminfo selectedInfo = nullptr;
    for (int32_t index = 0; index < resolveCount; index++) {
        lsl_streaminfo candidate = resolvedInfos[static_cast<size_t>(index)];
        if (candidate != nullptr && MatchesRequestedStream(candidate, streamName, streamType)) {
            selectedInfo = candidate;
            break;
        }
    }
    if (selectedInfo == nullptr) {
        DestroyResolvedInfos(resolvedInfos);
        return nullptr;
    }

    const int32_t channelCount = lsl_get_channel_count(selectedInfo);
    if (channelCount <= 0) {
        DestroyResolvedInfos(resolvedInfos);
        ThrowJavaException(
            env,
            gCache.illegalStateClass,
            "Resolved LSL stream did not report any channels."
        );
        return nullptr;
    }
    if (channelIndex >= channelCount) {
        DestroyResolvedInfos(resolvedInfos);
        ThrowJavaException(
            env,
            gCache.illegalArgumentClass,
            "Requested LSL channel index is outside the resolved stream channel range."
        );
        return nullptr;
    }

    lsl_inlet inlet = lsl_create_inlet(
        selectedInfo,
        kMaxBufferedSeconds,
        kMaxChunkLength,
        kRecoverLostStreams
    );
    if (inlet == nullptr) {
        const std::string message = "Could not create an LSL inlet. " + LastErrorString();
        DestroyResolvedInfos(resolvedInfos);
        ThrowJavaException(env, gCache.illegalStateClass, message);
        return nullptr;
    }

    int32_t errorCode = lsl_no_error;
    lsl_open_stream(inlet, kOpenTimeoutSeconds, &errorCode);
    if (errorCode != lsl_no_error) {
        const std::string message =
            BuildErrorMessage("Could not open the resolved LSL stream", errorCode);
        lsl_destroy_inlet(inlet);
        DestroyResolvedInfos(resolvedInfos);
        ThrowJavaException(env, gCache.illegalStateClass, message);
        return nullptr;
    }

    errorCode = lsl_set_postprocessing(inlet, proc_ALL);
    if (errorCode != lsl_no_error) {
        const std::string message =
            BuildErrorMessage("Could not configure LSL inlet post-processing", errorCode);
        lsl_destroy_inlet(inlet);
        DestroyResolvedInfos(resolvedInfos);
        ThrowJavaException(env, gCache.illegalStateClass, message);
        return nullptr;
    }

    MonitorSession *session = new (std::nothrow) MonitorSession{
        inlet,
        SafeString(lsl_get_name(selectedInfo)),
        SafeString(lsl_get_type(selectedInfo)),
        channelCount,
        channelIndex,
        static_cast<float>(lsl_get_nominal_srate(selectedInfo))
    };
    DestroyResolvedInfos(resolvedInfos);

    if (session == nullptr) {
        lsl_destroy_inlet(inlet);
        ThrowJavaException(env, gCache.illegalStateClass, "Could not allocate monitor session state.");
        return nullptr;
    }

    jobject result = BuildNativeSession(
        env,
        static_cast<jlong>(reinterpret_cast<intptr_t>(session)),
        *session
    );
    if (result == nullptr && !env->ExceptionCheck()) {
        lsl_destroy_inlet(inlet);
        delete session;
        ThrowJavaException(env, gCache.illegalStateClass, "Could not create the native monitor session.");
        return nullptr;
    }

    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_mesmerprism_rustyxr_companion_android_transport_LslNativeBridge_nativePullSample(
    JNIEnv *env,
    jclass,
    jlong handle,
    jdouble timeoutSeconds
) {
    MonitorSession *session = AsSession(handle);
    if (session == nullptr || session->inlet == nullptr) {
        ThrowJavaException(
            env,
            gCache.illegalArgumentClass,
            "LSL monitor handle is not valid."
        );
        return nullptr;
    }

    std::vector<float> sample(static_cast<size_t>(session->channelCount), 0.0f);
    int32_t errorCode = lsl_no_error;
    const double timestampSeconds = lsl_pull_sample_f(
        session->inlet,
        sample.data(),
        session->channelCount,
        timeoutSeconds,
        &errorCode
    );
    if (errorCode != lsl_no_error) {
        const std::string message =
            BuildErrorMessage("Could not pull a sample from the LSL stream", errorCode);
        ThrowJavaException(env, gCache.illegalStateClass, message);
        return nullptr;
    }
    if (timestampSeconds == 0.0) {
        return nullptr;
    }

    return BuildNativeSample(
        env,
        timestampSeconds,
        sample[static_cast<size_t>(session->selectedChannelIndex)]
    );
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_mesmerprism_rustyxr_companion_android_transport_LslNativeBridge_nativeCloseStream(
    JNIEnv *,
    jclass,
    jlong handle
) {
    MonitorSession *session = AsSession(handle);
    if (session == nullptr) {
        return;
    }

    if (session->inlet != nullptr) {
        lsl_destroy_inlet(session->inlet);
        session->inlet = nullptr;
    }

    delete session;
}
