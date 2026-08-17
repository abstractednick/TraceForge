cmake_minimum_required(VERSION 3.18)

project(traceforge_agent VERSION 0.1.0 LANGUAGES CXX)

option(TRACEFORGE_BUILD_TESTS "Build portable host tests" ON)
option(TRACEFORGE_BUILD_AGENT "Build the JVMTI shared library" ${ANDROID})

add_library(traceforge_portable STATIC
    src/options.cpp
    src/event_writer.cpp
)
target_include_directories(traceforge_portable PUBLIC include)
target_compile_features(traceforge_portable PUBLIC cxx_std_17)
set_target_properties(traceforge_portable PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(traceforge_portable PRIVATE -Wall -Wextra -Wpedantic)
endif()

if(TRACEFORGE_BUILD_AGENT)
    if(NOT ANDROID)
        find_package(JNI REQUIRED)
    endif()

    add_library(traceforge-agent SHARED src/agent.cpp)
    target_link_libraries(traceforge-agent PRIVATE traceforge_portable)
    target_compile_features(traceforge-agent PRIVATE cxx_std_17)

    if(ANDROID)
        target_link_libraries(traceforge-agent PRIVATE log)
    else()
        target_include_directories(traceforge-agent PRIVATE ${JNI_INCLUDE_DIRS})
        target_link_libraries(traceforge-agent PRIVATE ${JNI_LIBRARIES})
    endif()

    if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
        target_compile_options(traceforge-agent PRIVATE
            -Wall -Wextra -Wpedantic -Wconversion -Wshadow)
    endif()
endif()

if(TRACEFORGE_BUILD_TESTS)
    enable_testing()
    add_executable(traceforge_portable_tests tests/options_writer_test.cpp)
    target_link_libraries(traceforge_portable_tests PRIVATE traceforge_portable)
    target_compile_features(traceforge_portable_tests PRIVATE cxx_std_17)
    add_test(NAME traceforge_portable_tests COMMAND traceforge_portable_tests)
endif()
