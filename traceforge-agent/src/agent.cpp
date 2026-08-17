#include <jni.h>
#include <jvmti.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <utility>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "traceforge/event_writer.h"
#include "traceforge/options.h"

namespace traceforge {
namespace {

constexpr char kLogTag[] = "TraceForge";

void LogError(const std::string& message) {
#ifdef __ANDROID__
  __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
#else
  (void)kLogTag;
  std::fprintf(stderr, "TraceForge: %s\n", message.c_str());
#endif
}

std::string JvmtiErrorText(jvmtiEnv* jvmti, jvmtiError error) {
  char* name = nullptr;
  std::string result = "JVMTI error " + std::to_string(error);
  if (jvmti != nullptr &&
      jvmti->GetErrorName(error, &name) == JVMTI_ERROR_NONE &&
      name != nullptr) {
    result += " (";
    result += name;
    result += ')';
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
  }
  return result;
}

bool Check(jvmtiEnv* jvmti, jvmtiError error, const char* operation,
           std::string* detail = nullptr) {
  if (error == JVMTI_ERROR_NONE) {
    return true;
  }
  std::string message = std::string(operation) + ": " +
                        JvmtiErrorText(jvmti, error);
  LogError(message);
  if (detail != nullptr) {
    *detail = std::move(message);
  }
  return false;
}

template <typename T>
class JvmtiAllocation {
 public:
  JvmtiAllocation(jvmtiEnv* jvmti, T* value) : jvmti_(jvmti), value_(value) {}
  ~JvmtiAllocation() {
    if (value_ != nullptr) {
      jvmti_->Deallocate(reinterpret_cast<unsigned char*>(value_));
    }
  }
  T* get() const { return value_; }

 private:
  jvmtiEnv* jvmti_;
  T* value_;
};

std::uint64_t MonotonicNanos() {
  const auto now = std::chrono::steady_clock::now().time_since_epoch();
  return static_cast<std::uint64_t>(
      std::chrono::duration_cast<std::chrono::nanoseconds>(now).count());
}

std::string NormalizePackage(std::string package) {
  std::replace(package.begin(), package.end(), '.', '/');
  while (!package.empty() && package.front() == '/') {
    package.erase(package.begin());
  }
  while (!package.empty() && package.back() == '/') {
    package.pop_back();
  }
  return package;
}

class AgentState {
 public:
  AgentState(jvmtiEnv* environment, AgentOptions parsed_options)
      : jvmti(environment),
        options(std::move(parsed_options)),
        include_prefix(NormalizePackage(options.include_package)),
        writer(options.max_events) {}

  bool Included(const char* signature) const {
    if (include_prefix.empty()) {
      return true;
    }
    if (signature == nullptr || signature[0] != 'L') {
      return false;
    }
    const std::string_view name(signature + 1);
    return name.size() > include_prefix.size() &&
           name.compare(0, include_prefix.size(), include_prefix) == 0 &&
           name[include_prefix.size()] == '/';
  }

  jvmtiEnv* const jvmti;
  AgentOptions options;
  const std::string include_prefix;
  EventWriter writer;
  bool methods_enabled = false;
  bool allocations_enabled = false;
};

std::mutex g_state_mutex;
std::shared_ptr<AgentState> g_state;

std::shared_ptr<AgentState> GetState() {
  std::lock_guard<std::mutex> lock(g_state_mutex);
  return g_state;
}

std::string ThreadName(jvmtiEnv* jvmti, jthread thread) {
  jvmtiThreadInfo info{};
  if (jvmti->GetThreadInfo(thread, &info) != JVMTI_ERROR_NONE) {
    return {};
  }
  JvmtiAllocation<char> name(jvmti, info.name);
  return name.get() == nullptr ? std::string() : std::string(name.get());
}

bool MethodDetails(jvmtiEnv* jvmti, jmethodID method,
                   const AgentState& state, Event* event) {
  jclass declaring_class = nullptr;
  if (jvmti->GetMethodDeclaringClass(method, &declaring_class) !=
      JVMTI_ERROR_NONE) {
    return false;
  }

  char* class_signature_raw = nullptr;
  if (jvmti->GetClassSignature(declaring_class, &class_signature_raw, nullptr) !=
      JVMTI_ERROR_NONE) {
    return false;
  }
  JvmtiAllocation<char> class_signature(jvmti, class_signature_raw);
  if (!state.Included(class_signature.get())) {
    return false;
  }

  char* name_raw = nullptr;
  char* method_signature_raw = nullptr;
  if (jvmti->GetMethodName(method, &name_raw, &method_signature_raw, nullptr) !=
      JVMTI_ERROR_NONE) {
    return false;
  }
  JvmtiAllocation<char> name(jvmti, name_raw);
  JvmtiAllocation<char> method_signature(jvmti, method_signature_raw);
  event->class_name = class_signature.get() == nullptr
                          ? std::string()
                          : std::string(class_signature.get());
  event->method_name =
      name.get() == nullptr ? std::string() : std::string(name.get());
  event->method_signature = method_signature.get() == nullptr
                                ? std::string()
                                : std::string(method_signature.get());
  return true;
}

void JNICALL OnMethodEntry(jvmtiEnv* jvmti, JNIEnv*, jthread thread,
                           jmethodID method) {
  try {
    const std::shared_ptr<AgentState> state = GetState();
    if (state == nullptr) {
      return;
    }
    Event event;
    event.kind = EventKind::kMethodEntry;
    event.timestamp_ns = MonotonicNanos();
    if (!MethodDetails(jvmti, method, *state, &event)) {
      return;
    }
    event.thread = ThreadName(jvmti, thread);
    state->writer.Submit(std::move(event));
  } catch (...) {
    // Exceptions must never cross the JVMTI C callback boundary.
  }
}

void JNICALL OnMethodExit(jvmtiEnv* jvmti, JNIEnv*, jthread thread,
                          jmethodID method, jboolean, jvalue) {
  try {
    const std::shared_ptr<AgentState> state = GetState();
    if (state == nullptr) {
      return;
    }
    Event event;
    event.kind = EventKind::kMethodExit;
    event.timestamp_ns = MonotonicNanos();
    if (!MethodDetails(jvmti, method, *state, &event)) {
      return;
    }
    event.thread = ThreadName(jvmti, thread);
    state->writer.Submit(std::move(event));
  } catch (...) {
  }
}

void JNICALL OnObjectAllocation(jvmtiEnv* jvmti, JNIEnv*, jthread thread,
                                jobject, jclass object_class, jlong size) {
  try {
    const std::shared_ptr<AgentState> state = GetState();
    if (state == nullptr) {
      return;
    }
    char* signature_raw = nullptr;
    if (jvmti->GetClassSignature(object_class, &signature_raw, nullptr) !=
        JVMTI_ERROR_NONE) {
      return;
    }
    JvmtiAllocation<char> signature(jvmti, signature_raw);
    if (!state->Included(signature.get())) {
      return;
    }
    Event event;
    event.kind = EventKind::kAllocation;
    event.timestamp_ns = MonotonicNanos();
    event.thread = ThreadName(jvmti, thread);
    event.object_class =
        signature.get() == nullptr ? std::string() : std::string(signature.get());
    event.size_bytes = static_cast<std::int64_t>(size);
    state->writer.Submit(std::move(event));
  } catch (...) {
  }
}

bool ConfigureCapabilities(const std::shared_ptr<AgentState>& state,
                           std::string* error) {
  jvmtiCapabilities potential{};
  if (!Check(state->jvmti, state->jvmti->GetPotentialCapabilities(&potential),
             "GetPotentialCapabilities", error)) {
    return false;
  }

  jvmtiCapabilities requested{};
  if (state->options.methods) {
    if (potential.can_generate_method_entry_events == 0 ||
        potential.can_generate_method_exit_events == 0) {
      *error = "method entry/exit capabilities are unavailable";
      return false;
    }
    requested.can_generate_method_entry_events = 1;
    requested.can_generate_method_exit_events = 1;
  }
  if (state->options.allocations) {
    if (potential.can_generate_vm_object_alloc_events == 0) {
      *error = "VM object allocation capability is unavailable";
      return false;
    }
    requested.can_generate_vm_object_alloc_events = 1;
  }
  return Check(state->jvmti, state->jvmti->AddCapabilities(&requested),
               "AddCapabilities", error);
}

bool SetEvent(jvmtiEnv* jvmti, jvmtiEvent event, bool enabled,
              std::string* error = nullptr) {
  return Check(jvmti,
               jvmti->SetEventNotificationMode(
                   enabled ? JVMTI_ENABLE : JVMTI_DISABLE, event, nullptr),
               enabled ? "enable event" : "disable event", error);
}

void DisableEvents(const std::shared_ptr<AgentState>& state) {
  if (state->methods_enabled) {
    SetEvent(state->jvmti, JVMTI_EVENT_METHOD_ENTRY, false);
    SetEvent(state->jvmti, JVMTI_EVENT_METHOD_EXIT, false);
    state->methods_enabled = false;
  }
  if (state->allocations_enabled) {
    SetEvent(state->jvmti, JVMTI_EVENT_VM_OBJECT_ALLOC, false);
    state->allocations_enabled = false;
  }
}

jint StartAgent(JavaVM* vm, const char* raw_options) {
  {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    if (g_state != nullptr) {
      LogError("agent is already initialized");
      return JNI_ERR;
    }
  }

  AgentOptions options;
  std::string error;
  if (!ParseOptions(raw_options == nullptr ? "" : raw_options, &options,
                    &error)) {
    LogError("invalid options: " + error);
    return JNI_ERR;
  }
  if (options.output.empty()) {
    LogError("the output option is required");
    return JNI_ERR;
  }
  if (!options.methods && !options.allocations) {
    LogError("at least one of methods or allocations must be enabled");
    return JNI_ERR;
  }

  jvmtiEnv* jvmti = nullptr;
  const jint env_result =
      vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2);
  if (env_result != JNI_OK || jvmti == nullptr) {
    LogError("VM does not provide JVMTI 1.2");
    return JNI_ERR;
  }

  auto state = std::make_shared<AgentState>(jvmti, std::move(options));
  if (!ConfigureCapabilities(state, &error)) {
    LogError(error);
    return JNI_ERR;
  }

  jvmtiEventCallbacks callbacks{};
  callbacks.MethodEntry = &OnMethodEntry;
  callbacks.MethodExit = &OnMethodExit;
  callbacks.VMObjectAlloc = &OnObjectAllocation;
  if (!Check(jvmti,
             jvmti->SetEventCallbacks(&callbacks,
                                      static_cast<jint>(sizeof(callbacks))),
             "SetEventCallbacks", &error)) {
    return JNI_ERR;
  }
  if (!state->writer.Start(state->options.output, &error)) {
    LogError(error);
    return JNI_ERR;
  }

  {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    g_state = state;
  }

  bool events_ok = true;
  if (state->options.methods) {
    events_ok = SetEvent(jvmti, JVMTI_EVENT_METHOD_ENTRY, true, &error);
    state->methods_enabled = events_ok;
    if (events_ok) {
      events_ok = SetEvent(jvmti, JVMTI_EVENT_METHOD_EXIT, true, &error);
    }
  }
  if (events_ok && state->options.allocations) {
    events_ok = SetEvent(jvmti, JVMTI_EVENT_VM_OBJECT_ALLOC, true, &error);
    state->allocations_enabled = events_ok;
  }
  if (!events_ok) {
    DisableEvents(state);
    {
      std::lock_guard<std::mutex> lock(g_state_mutex);
      g_state.reset();
    }
    state->writer.Stop();
    return JNI_ERR;
  }

  Event diagnostic;
  diagnostic.kind = EventKind::kDiagnostic;
  diagnostic.timestamp_ns = MonotonicNanos();
  diagnostic.message = "TraceForge agent started";
  state->writer.Submit(std::move(diagnostic));
  return JNI_OK;
}

void StopAgent() {
  std::shared_ptr<AgentState> state;
  {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    state = g_state;
  }
  if (state == nullptr) {
    return;
  }
  DisableEvents(state);
  {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    g_state.reset();
  }
  Event diagnostic;
  diagnostic.kind = EventKind::kDiagnostic;
  diagnostic.timestamp_ns = MonotonicNanos();
  diagnostic.message =
      "TraceForge agent stopping; dropped_events=" +
      std::to_string(state->writer.dropped());
  state->writer.Submit(std::move(diagnostic));
  state->writer.Stop();
}

}  // namespace
}  // namespace traceforge

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options,
                                                void*) {
  try {
    return traceforge::StartAgent(vm, options);
  } catch (...) {
    return JNI_ERR;
  }
}

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options,
                                                  void*) {
  try {
    return traceforge::StartAgent(vm, options);
  } catch (...) {
    return JNI_ERR;
  }
}

extern "C" JNIEXPORT void JNICALL Agent_OnUnload(JavaVM*) {
  traceforge::StopAgent();
}
