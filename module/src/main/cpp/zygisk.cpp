// Modul Zygisk RoutTrans: menyuntikkan payload Java ke proses aplikasi yang
// dipilih. Berkas ini sengaja tipis: seluruh logika terjemahan ada di
// classes.dex (modul :payload).

#include <android/log.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>

#include "zygisk.hpp"

#define TAG "RoutTrans"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

constexpr const char *kAppsDir = "/data/adb/routtrans/apps/";
constexpr const char *kDexPath = "/data/adb/modules/routtrans/classes.dex";
constexpr size_t kMaxConfig = 128 * 1024;
constexpr size_t kMaxDex = 16 * 1024 * 1024;

// Nama kelas payload harus sama dengan yang ada di modul :payload.
constexpr const char *kBootstrapClass = "id.ifan.routtrans.payload.RuntimeBootstrap";

// Batas waktu komunikasi dengan companion. Pre-specialize berjalan sebelum app
// dimulai, jadi kegagalan companion tidak boleh menahan proses terlalu lama.
constexpr int kSocketTimeoutSec = 5;

// ---------------------------------------------------------------------------
// Utilitas socket dan berkas
// ---------------------------------------------------------------------------

bool readAll(int fd, void *buffer, size_t length) {
    auto *ptr = static_cast<uint8_t *>(buffer);
    size_t done = 0;
    while (done < length) {
        ssize_t read = ::read(fd, ptr + done, length - done);
        if (read <= 0) {
            return false;
        }
        done += static_cast<size_t>(read);
    }
    return true;
}

bool writeAll(int fd, const void *buffer, size_t length) {
    auto *ptr = static_cast<const uint8_t *>(buffer);
    size_t done = 0;
    while (done < length) {
        ssize_t written = ::write(fd, ptr + done, length - done);
        if (written <= 0) {
            return false;
        }
        done += static_cast<size_t>(written);
    }
    return true;
}

bool writeU32(int fd, uint32_t value) {
    return writeAll(fd, &value, sizeof(value));
}

bool readU32(int fd, uint32_t *value) {
    return readAll(fd, value, sizeof(*value));
}

bool readFile(const std::string &path, std::string *out, size_t maxSize) {
    int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }
    struct stat info {};
    if (::fstat(fd, &info) != 0 || info.st_size <= 0
            || static_cast<size_t>(info.st_size) > maxSize) {
        ::close(fd);
        return false;
    }
    out->resize(static_cast<size_t>(info.st_size));
    bool ok = readAll(fd, out->data(), out->size());
    ::close(fd);
    if (!ok) {
        out->clear();
    }
    return ok;
}

// "com.foo.bar:remote" -> "com.foo.bar". Proses anak zygote dilewati pemanggil.
std::string packageOf(const std::string &processName) {
    size_t end = processName.find_first_of(":/");
    return end == std::string::npos ? processName : processName.substr(0, end);
}

// ---------------------------------------------------------------------------
// Companion (berjalan sebagai root)
// ---------------------------------------------------------------------------

std::mutex gDexMutex;
std::string gDex;

void handleCompanion(int client) {
    struct timeval timeout {};
    timeout.tv_sec = kSocketTimeoutSec;
    ::setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    ::setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    uint32_t nameLength = 0;
    if (!readU32(client, &nameLength) || nameLength == 0 || nameLength > 512) {
        ::close(client);
        return;
    }
    std::string processName(nameLength, '\0');
    if (!readAll(client, processName.data(), nameLength)) {
        ::close(client);
        return;
    }

    const std::string configPath = std::string(kAppsDir) + packageOf(processName) + ".json";
    std::string config;
    if (!readFile(configPath, &config, kMaxConfig)) {
        // Tidak ada berkas = aplikasi ini tidak dipilih untuk diterjemahkan.
        writeU32(client, 0);
        ::close(client);
        return;
    }

    std::string dex;
    {
        std::lock_guard<std::mutex> lock(gDexMutex);
        if (gDex.empty() && !readFile(kDexPath, &gDex, kMaxDex)) {
            LOGW("classes.dex tidak bisa dibaca dari %s", kDexPath);
        }
        dex = gDex;
    }
    if (dex.empty()) {
        writeU32(client, 0);
        ::close(client);
        return;
    }

    writeU32(client, 1);
    writeU32(client, static_cast<uint32_t>(config.size()));
    writeAll(client, config.data(), config.size());
    writeU32(client, static_cast<uint32_t>(dex.size()));
    writeAll(client, dex.data(), dex.size());
    ::close(client);
}

// ---------------------------------------------------------------------------
// Pemuatan payload di proses aplikasi
// ---------------------------------------------------------------------------

struct InstallData {
    JavaVM *vm;
    std::string config;
    std::string dex;
};

/** Menunggu Application terbentuk, lalu memuat payload lewat dex class loader. */
void loadPayload(JNIEnv *env, InstallData *data, jobject application) {
    jclass byteBufferClass = env->FindClass("java/nio/ByteBuffer");
    jmethodID allocateDirect = byteBufferClass == nullptr ? nullptr
            : env->GetStaticMethodID(byteBufferClass, "allocateDirect",
                                     "(I)Ljava/nio/ByteBuffer;");
    if (allocateDirect == nullptr) {
        LOGW("ByteBuffer tidak tersedia");
        return;
    }
    jobject buffer = env->CallStaticObjectMethod(
            byteBufferClass, allocateDirect, static_cast<jint>(data->dex.size()));
    void *address = env->GetDirectBufferAddress(buffer);
    if (address == nullptr) {
        LOGW("ByteBuffer langsung tidak tersedia");
        return;
    }
    std::memcpy(address, data->dex.data(), data->dex.size());

    jclass applicationClass = env->GetObjectClass(application);
    jmethodID getClassLoader = env->GetMethodID(
            applicationClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parent = env->CallObjectMethod(application, getClassLoader);

    jclass loaderClass = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jmethodID constructor = loaderClass == nullptr ? nullptr
            : env->GetMethodID(loaderClass, "<init>",
                               "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (constructor == nullptr) {
        LOGW("InMemoryDexClassLoader tidak tersedia");
        return;
    }
    jobject loader = env->NewObject(loaderClass, constructor, buffer, parent);

    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(
            classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring className = env->NewStringUTF(kBootstrapClass);
    auto bootstrap = static_cast<jclass>(
            env->CallObjectMethod(loader, loadClass, className));
    if (bootstrap == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("Kelas payload tidak ditemukan di dex");
        return;
    }

    jmethodID install = env->GetStaticMethodID(
            bootstrap, "install", "(Landroid/app/Application;Ljava/lang/String;)V");
    if (install == nullptr) {
        env->ExceptionClear();
        return;
    }
    jstring config = env->NewStringUTF(data->config.c_str());
    env->CallStaticVoidMethod(bootstrap, install, application, config);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else {
        LOGD("Payload RoutTrans dipasang");
    }
}

void *installThread(void *raw) {
    std::unique_ptr<InstallData> data(static_cast<InstallData *>(raw));
    JNIEnv *env = nullptr;
    bool attached = false;
    if (data->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (data->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
        attached = true;
    }

    // Application belum ada saat postAppSpecialize; tunggu sampai ActivityThread
    // selesai membuatnya (maksimal 20 detik).
    jclass activityThread = env->FindClass("android/app/ActivityThread");
    jobject application = nullptr;
    jmethodID currentApplication = activityThread == nullptr
            ? nullptr
            : env->GetStaticMethodID(activityThread, "currentApplication",
                                     "()Landroid/app/Application;");
    if (currentApplication == nullptr && env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    for (int attempt = 0; attempt < 400 && currentApplication != nullptr; attempt++) {
        jobject candidate = env->CallStaticObjectMethod(activityThread, currentApplication);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        if (candidate != nullptr) {
            application = candidate;
            break;
        }
        usleep(50 * 1000);
    }

    if (application == nullptr) {
        LOGW("Application tidak terbentuk, payload dilewati");
    } else {
        loadPayload(env, data.get(), application);
    }

    if (attached) {
        data->vm->DetachCurrentThread();
    }
    return nullptr;
}

// ---------------------------------------------------------------------------
// Modul Zygisk
// ---------------------------------------------------------------------------

class RoutTransModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (args->is_child_zygote != nullptr && *args->is_child_zygote) {
            return;
        }

        if (args->nice_name == nullptr) {
            disable();
            return;
        }
        const char *raw = env->GetStringUTFChars(args->nice_name, nullptr);
        if (raw == nullptr) {
            disable();
            return;
        }
        const std::string processName(raw);
        env->ReleaseStringUTFChars(args->nice_name, raw);

        if (!fetchPayload(processName)) {
            disable();
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (dex.empty()) {
            return;
        }
        JavaVM *vm = nullptr;
        if (env->GetJavaVM(&vm) != JNI_OK || vm == nullptr) {
            return;
        }

        auto *data = new InstallData{vm, config, dex};
        pthread_t thread;
        if (pthread_create(&thread, nullptr, installThread, data) == 0) {
            pthread_detach(thread);
        } else {
            delete data;
            LOGW("Gagal membuat thread instalasi");
        }
    }

private:
    void disable() {
        // Aplikasi tidak dipilih: lepaskan library modul dari proses ini.
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

    bool fetchPayload(const std::string &processName) {
        int fd = api->connectCompanion();
        if (fd < 0) {
            LOGW("Companion tidak tersedia");
            return false;
        }

        struct timeval timeout {};
        timeout.tv_sec = kSocketTimeoutSec;
        ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

        bool ok = writeU32(fd, static_cast<uint32_t>(processName.size()))
                && writeAll(fd, processName.data(), processName.size());

        uint32_t enabled = 0;
        ok = ok && readU32(fd, &enabled);
        if (ok && enabled == 1) {
            uint32_t configSize = 0;
            uint32_t dexSize = 0;
            ok = readU32(fd, &configSize) && configSize > 0 && configSize <= kMaxConfig;
            if (ok) {
                config.resize(configSize);
                ok = readAll(fd, config.data(), configSize);
            }
            ok = ok && readU32(fd, &dexSize) && dexSize > 0 && dexSize <= kMaxDex;
            if (ok) {
                dex.resize(dexSize);
                ok = readAll(fd, dex.data(), dexSize);
            }
        }
        ::close(fd);

        if (!ok || enabled != 1) {
            config.clear();
            dex.clear();
            return false;
        }
        LOGD("Payload disiapkan untuk %s", processName.c_str());
        return true;
    }

    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
    std::string config;
    std::string dex;
};

}  // namespace

REGISTER_ZYGISK_MODULE(RoutTransModule)
REGISTER_ZYGISK_COMPANION(handleCompanion)
