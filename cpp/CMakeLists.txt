cmake_minimum_required(VERSION 3.20)
project(market_data_publisher CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Aeron C++ client via vcpkg ───────────────────────────────────────────────
# Install: vcpkg install aeron
# Then configure: cmake -DCMAKE_TOOLCHAIN_FILE=<vcpkg>/scripts/buildsystems/vcpkg.cmake ..
find_package(aeron CONFIG REQUIRED)

# ── Executable ───────────────────────────────────────────────────────────────
add_executable(market_data_publisher
    publisher/MarketDataPublisher.cpp
)

target_include_directories(market_data_publisher PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(market_data_publisher PRIVATE
    aeron::aeron_client
)

# ── Compiler flags for a low-latency build ───────────────────────────────────
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(market_data_publisher PRIVATE
        -O3
        -march=native
        -fno-omit-frame-pointer   # keep for profiling
        -Wall
        -Wextra
    )
elseif(MSVC)
    target_compile_options(market_data_publisher PRIVATE
        /O2
        /W4
    )
endif()
