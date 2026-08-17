#include "traceforge/options.h"

#include <algorithm>
#include <charconv>
#include <cctype>
#include <limits>
#include <unordered_set>
#include <utility>
#include <vector>

namespace traceforge {
namespace {

std::string_view Trim(std::string_view value) {
  while (!value.empty() &&
         std::isspace(static_cast<unsigned char>(value.front())) != 0) {
    value.remove_prefix(1);
  }
  while (!value.empty() &&
         std::isspace(static_cast<unsigned char>(value.back())) != 0) {
    value.remove_suffix(1);
  }
  return value;
}

bool Split(std::string_view text, std::vector<std::string>* fields,
           std::string* error) {
  std::string current;
  char quote = '\0';
  bool escaped = false;
  for (char ch : text) {
    if (escaped) {
      current.push_back(ch);
      escaped = false;
    } else if (ch == '\\') {
      escaped = true;
    } else if (quote != '\0') {
      if (ch == quote) {
        quote = '\0';
      } else {
        current.push_back(ch);
      }
    } else if (ch == '\'' || ch == '"') {
      quote = ch;
    } else if (ch == ',') {
      fields->push_back(std::move(current));
      current.clear();
    } else {
      current.push_back(ch);
    }
  }
  if (escaped || quote != '\0') {
    *error = escaped ? "trailing escape in options" : "unterminated quote";
    return false;
  }
  fields->push_back(std::move(current));
  return true;
}

bool ParseBool(std::string_view value, bool* result) {
  if (value == "true" || value == "1" || value == "yes") {
    *result = true;
    return true;
  }
  if (value == "false" || value == "0" || value == "no") {
    *result = false;
    return true;
  }
  return false;
}

bool ContainsControl(std::string_view value) {
  return std::any_of(value.begin(), value.end(), [](char ch) {
    const auto byte = static_cast<unsigned char>(ch);
    return byte < 0x20U || byte == 0x7fU;
  });
}

}  // namespace

bool ParseOptions(std::string_view text, AgentOptions* options,
                  std::string* error) {
  if (options == nullptr || error == nullptr) {
    return false;
  }
  AgentOptions parsed;
  if (Trim(text).empty()) {
    *options = std::move(parsed);
    error->clear();
    return true;
  }

  std::vector<std::string> fields;
  if (!Split(text, &fields, error)) {
    return false;
  }

  std::unordered_set<std::string> seen;
  for (const std::string& raw_field : fields) {
    const std::string_view field = Trim(raw_field);
    const std::size_t separator = field.find('=');
    if (separator == std::string_view::npos) {
      *error = "option must use key=value: " + std::string(field);
      return false;
    }
    const std::string key(Trim(field.substr(0, separator)));
    const std::string_view value = Trim(field.substr(separator + 1));
    if (key.empty() || !seen.insert(key).second) {
      *error = key.empty() ? "empty option name" : "duplicate option: " + key;
      return false;
    }
    if (ContainsControl(value)) {
      *error = "control character in option: " + key;
      return false;
    }

    if (key == "output") {
      if (value.empty()) {
        *error = "output path must not be empty";
        return false;
      }
      parsed.output = std::string(value);
    } else if (key == "methods") {
      if (!ParseBool(value, &parsed.methods)) {
        *error = "methods must be true or false";
        return false;
      }
    } else if (key == "allocations") {
      if (!ParseBool(value, &parsed.allocations)) {
        *error = "allocations must be true or false";
        return false;
      }
    } else if (key == "include") {
      parsed.include_package = std::string(value);
    } else if (key == "max_events") {
      std::size_t count = 0;
      const char* begin = value.data();
      const char* end = begin + value.size();
      const auto result = std::from_chars(begin, end, count);
      if (result.ec != std::errc() || result.ptr != end || count < 64 ||
          count > 1'000'000) {
        *error = "max_events must be an integer from 64 to 1000000";
        return false;
      }
      parsed.max_events = count;
    } else {
      *error = "unknown option: " + key;
      return false;
    }
  }

  *options = std::move(parsed);
  error->clear();
  return true;
}

}  // namespace traceforge
