#pragma once

#include <cstddef>
#include <string>
#include <string_view>

namespace traceforge {

struct AgentOptions {
  std::string output;
  bool methods = true;
  bool allocations = false;
  std::string include_package;
  std::size_t max_events = 8192;
};

// Options are comma-separated key=value pairs. Values may be single- or
// double-quoted; backslash escapes the following character.
bool ParseOptions(std::string_view text, AgentOptions* options,
                  std::string* error);

}  // namespace traceforge
