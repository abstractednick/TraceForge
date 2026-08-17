#include "traceforge/event_writer.h"
#include "traceforge/options.h"

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

namespace {

int failures = 0;

#define CHECK(condition)                                                     \
  do {                                                                       \
    if (!(condition)) {                                                      \
      std::cerr << __FILE__ << ':' << __LINE__ << ": check failed: "         \
                << #condition << '\n';                                       \
      ++failures;                                                            \
    }                                                                        \
  } while (false)

void TestOptions() {
  traceforge::AgentOptions options;
  std::string error;
  CHECK(traceforge::ParseOptions(
      R"(output="/tmp/events,one.jsonl", methods=no, allocations=yes, )"
      R"(include=com.example.app,max_events=256)",
      &options, &error));
  CHECK(options.output == "/tmp/events,one.jsonl");
  CHECK(!options.methods);
  CHECK(options.allocations);
  CHECK(options.include_package == "com.example.app");
  CHECK(options.max_events == 256);

  CHECK(!traceforge::ParseOptions("methods=maybe", &options, &error));
  CHECK(error.find("methods") != std::string::npos);
  CHECK(!traceforge::ParseOptions("output=/tmp/a,output=/tmp/b", &options,
                                  &error));
  CHECK(error.find("duplicate") != std::string::npos);
  CHECK(!traceforge::ParseOptions("max_events=63", &options, &error));
  CHECK(!traceforge::ParseOptions("unknown=true", &options, &error));
  CHECK(!traceforge::ParseOptions(R"(output="unterminated)", &options, &error));
}

void TestJsonAndWriter() {
  traceforge::Event event;
  event.kind = traceforge::EventKind::kMethodEntry;
  event.timestamp_ns = 42;
  event.thread = "worker\n\"one\"";
  event.class_name = "Lcom/example/Main;";
  event.method_name = "run";
  event.method_signature = "()V";
  const std::string json = traceforge::EventToJson(event);
  CHECK(json.find("\"timestamp_ns\":42") != std::string::npos);
  CHECK(json.find("worker\\n\\\"one\\\"") != std::string::npos);

  const auto nonce =
      std::chrono::steady_clock::now().time_since_epoch().count();
  const std::filesystem::path path =
      std::filesystem::temp_directory_path() /
      ("traceforge-writer-" + std::to_string(nonce) + ".jsonl");
  traceforge::EventWriter writer(64);
  std::string error;
  CHECK(writer.Start(path.string(), &error));
  CHECK(writer.Submit(event));

  traceforge::Event allocation;
  allocation.kind = traceforge::EventKind::kAllocation;
  allocation.timestamp_ns = 43;
  allocation.object_class = "Ljava/lang/String;";
  allocation.size_bytes = 24;
  CHECK(writer.Submit(std::move(allocation)));
  writer.Stop();
  CHECK(writer.healthy());

  std::ifstream input(path);
  std::string first;
  std::string second;
  CHECK(static_cast<bool>(std::getline(input, first)));
  CHECK(static_cast<bool>(std::getline(input, second)));
  CHECK(first == json);
  CHECK(second.find("\"size_bytes\":24") != std::string::npos);
  CHECK(!std::getline(input, first));
  std::error_code ignored;
  std::filesystem::remove(path, ignored);
}

}  // namespace

int main() {
  TestOptions();
  TestJsonAndWriter();
  if (failures != 0) {
    std::cerr << failures << " test(s) failed\n";
    return 1;
  }
  std::cout << "all tests passed\n";
  return 0;
}
