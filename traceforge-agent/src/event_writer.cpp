#include "traceforge/event_writer.h"

#include <iomanip>
#include <sstream>
#include <utility>

namespace traceforge {
namespace {

std::string EscapeJson(const std::string& value) {
  std::ostringstream out;
  for (const char raw_ch : value) {
    const auto ch = static_cast<unsigned char>(raw_ch);
    switch (ch) {
      case '"':
        out << "\\\"";
        break;
      case '\\':
        out << "\\\\";
        break;
      case '\b':
        out << "\\b";
        break;
      case '\f':
        out << "\\f";
        break;
      case '\n':
        out << "\\n";
        break;
      case '\r':
        out << "\\r";
        break;
      case '\t':
        out << "\\t";
        break;
      default:
        if (ch < 0x20U) {
          out << "\\u" << std::hex << std::setw(4) << std::setfill('0')
              << static_cast<unsigned int>(ch) << std::dec;
        } else {
          out << static_cast<char>(ch);
        }
    }
  }
  return out.str();
}

const char* KindName(EventKind kind) {
  switch (kind) {
    case EventKind::kMethodEntry:
      return "method_entry";
    case EventKind::kMethodExit:
      return "method_exit";
    case EventKind::kAllocation:
      return "allocation";
    case EventKind::kDiagnostic:
      return "diagnostic";
  }
  return "unknown";
}

void StringField(std::ostringstream* out, const char* name,
                 const std::string& value) {
  if (!value.empty()) {
    *out << ",\"" << name << "\":\"" << EscapeJson(value) << '"';
  }
}

}  // namespace

std::string EventToJson(const Event& event) {
  std::ostringstream out;
  out << "{\"type\":\"" << KindName(event.kind) << "\",\"timestamp_ns\":"
      << event.timestamp_ns;
  StringField(&out, "thread", event.thread);
  StringField(&out, "class", event.class_name);
  StringField(&out, "method", event.method_name);
  StringField(&out, "signature", event.method_signature);
  StringField(&out, "object_class", event.object_class);
  if (event.kind == EventKind::kAllocation) {
    out << ",\"size_bytes\":" << event.size_bytes;
  }
  StringField(&out, "message", event.message);
  out << '}';
  return out.str();
}

EventWriter::EventWriter(std::size_t capacity)
    : capacity_(capacity == 0 ? 1 : capacity) {}

EventWriter::~EventWriter() { Stop(); }

bool EventWriter::Start(const std::string& path, std::string* error) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (running_) {
    if (error != nullptr) {
      *error = "writer already started";
    }
    return false;
  }
  stream_.open(path, std::ios::out | std::ios::binary | std::ios::trunc);
  if (!stream_.is_open()) {
    if (error != nullptr) {
      *error = "could not open output file: " + path;
    }
    return false;
  }
  stopping_ = false;
  healthy_ = true;
  running_ = true;
  try {
    worker_ = std::thread(&EventWriter::Run, this);
  } catch (...) {
    running_ = false;
    healthy_ = false;
    stream_.close();
    if (error != nullptr) {
      *error = "could not create writer thread";
    }
    return false;
  }
  if (error != nullptr) {
    error->clear();
  }
  return true;
}

bool EventWriter::Submit(Event event) {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!running_ || stopping_ || !healthy_ || queue_.size() >= capacity_) {
      ++dropped_;
      return false;
    }
    queue_.push_back(std::move(event));
  }
  ready_.notify_one();
  return true;
}

void EventWriter::Stop() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!running_) {
      return;
    }
    stopping_ = true;
  }
  ready_.notify_one();
  if (worker_.joinable()) {
    worker_.join();
  }
  std::lock_guard<std::mutex> lock(mutex_);
  running_ = false;
}

std::uint64_t EventWriter::dropped() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return dropped_;
}

bool EventWriter::healthy() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return healthy_;
}

void EventWriter::Run() {
  for (;;) {
    std::deque<Event> batch;
    {
      std::unique_lock<std::mutex> lock(mutex_);
      ready_.wait(lock, [this] { return stopping_ || !queue_.empty(); });
      batch.swap(queue_);
      if (batch.empty() && stopping_) {
        break;
      }
    }

    for (const Event& event : batch) {
      stream_ << EventToJson(event) << '\n';
    }
    stream_.flush();
    if (!stream_) {
      std::lock_guard<std::mutex> lock(mutex_);
      healthy_ = false;
      dropped_ += queue_.size();
      queue_.clear();
      stopping_ = true;
    }
  }
  stream_.flush();
  stream_.close();
}

}  // namespace traceforge
