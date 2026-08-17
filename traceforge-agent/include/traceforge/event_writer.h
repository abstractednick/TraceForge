#pragma once

#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>

namespace traceforge {

enum class EventKind { kMethodEntry, kMethodExit, kAllocation, kDiagnostic };

struct Event {
  EventKind kind = EventKind::kDiagnostic;
  std::uint64_t timestamp_ns = 0;
  std::string thread;
  std::string class_name;
  std::string method_name;
  std::string method_signature;
  std::string object_class;
  std::int64_t size_bytes = 0;
  std::string message;
};

std::string EventToJson(const Event& event);

// A bounded, many-producer/single-consumer JSONL writer. Submit never blocks:
// events are dropped when the queue is full.
class EventWriter {
 public:
  explicit EventWriter(std::size_t capacity);
  ~EventWriter();

  EventWriter(const EventWriter&) = delete;
  EventWriter& operator=(const EventWriter&) = delete;

  bool Start(const std::string& path, std::string* error);
  bool Submit(Event event);
  void Stop();

  std::uint64_t dropped() const;
  bool healthy() const;

 private:
  void Run();

  const std::size_t capacity_;
  mutable std::mutex mutex_;
  std::condition_variable ready_;
  std::deque<Event> queue_;
  std::ofstream stream_;
  std::thread worker_;
  bool running_ = false;
  bool stopping_ = false;
  bool healthy_ = false;
  std::uint64_t dropped_ = 0;
};

}  // namespace traceforge
