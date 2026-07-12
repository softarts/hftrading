cmake_minimum_required(VERSION 3.0)
project(orderbook)

set(CMAKE_CXX_STANDARD 17)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE "Release")
endif()



set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -fPIC -Wall ")
set(CMAKE_CXX_FLAGS_DEBUG "-O0 -g")
set(install_dest ${PROJECT_SOURCE_DIR}/bin)

include_directories(${PROJECT_SOURCE_DIR})
message("project path:" ${PROJECT_SOURCE_DIR})

set(SOURCE_FILES
        order_book.cpp
        message_parser.cpp
        feed_handler.cpp
    )

# === orderbook =======
add_executable(orderbook
        main.cpp
        ${SOURCE_FILES}
        )

# === unittest =======
add_executable(unittest
        unittest/unittest.cpp
        ${SOURCE_FILES}
        )

set_target_properties(
        unittest
        PROPERTIES
        COMPILE_DEFINITIONS UNITTEST=1)

# === msg generator =======
add_executable(msg_generator
        perftest/msg_generator.cpp
        ${SOURCE_FILES}
        )

set_target_properties(
        msg_generator
        PROPERTIES
        COMPILE_DEFINITIONS UNITTEST=1
        COMPILE_DEFINITIONS PROFILE=1)

# === perf test =======
add_executable(perf_test
        main.cpp ${SOURCE_FILES})

set_target_properties(
        perf_test
        PROPERTIES
        COMPILE_DEFINITIONS PROFILE=1)

# === install targets =======
install(TARGETS
        orderbook unittest msg_generator perf_test
        DESTINATION ${install_dest})