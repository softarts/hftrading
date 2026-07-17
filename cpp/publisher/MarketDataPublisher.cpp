/**
 * MarketDataPublisher.cpp
 *
 * C++ IPC publisher: reads market events from a CSV file (or real DPDK packet
 * capture in production) and publishes them over an Aeron IPC channel so the
 * JVM OrderBook process can consume them via AeronFeedSourceImpl.
 *
 * Configuration is read from the same config/default.properties file that the
 * JVM reads, preventing config drift between the two processes.
 *
 * Build:
 *   cd cpp && mkdir -p build && cd build
 *   cmake .. -DCMAKE_TOOLCHAIN_FILE=<vcpkg>/scripts/buildsystems/vcpkg.cmake
 *   cmake --build . --config Release
 *
 * Run:
 *   ./market_data_publisher [config_path]   (default: config/default.properties)
 */

#include <Aeron.h>
#include <wire_format.h>

#include <chrono>
#include <csignal>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <atomic>

// ── Signal handling ───────────────────────────────────────────────────────────
static std::atomic<bool> g_running{true};
static void sig_handler(int) { g_running.store(false); }

// ── Portable nanosecond clock ─────────────────────────────────────────────────
static inline int64_t nanos_now() {
    using namespace std::chrono;
    return duration_cast<nanoseconds>(steady_clock::now().time_since_epoch()).count();
}

// ── Minimal .properties parser ────────────────────────────────────────────────
static std::unordered_map<std::string, std::string>
load_properties(const std::string& path) {
    std::unordered_map<std::string, std::string> props;
    std::ifstream f(path);
    if (!f.is_open()) {
        std::cerr << "[publisher] Cannot open config: " << path << "\n";
        return props;
    }
    std::string line;
    while (std::getline(f, line)) {
        if (line.empty() || line[0] == '#') continue;
        auto eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string key = line.substr(0, eq);
        std::string val = line.substr(eq + 1);
        // trim whitespace
        auto trim = [](std::string& s) {
            while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) s.erase(s.begin());
            while (!s.empty() && (s.back()  == ' ' || s.back()  == '\r' || s.back() == '\n')) s.pop_back();
        };
        trim(key); trim(val);
        props[key] = val;
    }
    return props;
}

// ── CSV parser (one line) ─────────────────────────────────────────────────────
// Format: msgType,timestampNanos,symbol,orderId,side,quantity,price
static bool parse_csv_line(const std::string& line, hft::MarketEvent& ev) {
    if (line.empty() || line[0] == '#') return false;
    std::istringstream ss(line);
    std::string tok;

    if (!std::getline(ss, tok, ',')) return false;
    ev.msg_type = tok.empty() ? 0 : tok[0];

    if (!std::getline(ss, tok, ',')) return false;
    ev.timestamp_nanos = std::stoll(tok);

    if (!std::getline(ss, tok, ',')) return false;
    ev.symbol = static_cast<uint16_t>(std::stoul(tok));

    if (!std::getline(ss, tok, ',')) return false;
    ev.order_id = std::stoll(tok);

    if (!std::getline(ss, tok, ',')) return false;
    ev.side = tok.empty() ? hft::SIDE_NONE : tok[0];

    if (!std::getline(ss, tok, ',')) return false;
    ev.quantity = std::stoll(tok);

    if (!std::getline(ss, tok)) return false;
    ev.price = std::stoll(tok);

    return true;
}

// ── Main ──────────────────────────────────────────────────────────────────────
int main(int argc, char* argv[]) {
    std::signal(SIGINT,  sig_handler);
    std::signal(SIGTERM, sig_handler);

    std::string config_path = (argc > 1) ? argv[1] : "config/default.properties";
    auto props = load_properties(config_path);

    auto get = [&](const std::string& key, const std::string& def) -> std::string {
        auto it = props.find(key);
        return (it != props.end()) ? it->second : def;
    };

    std::string channel  = get("aeron.channel",   "aeron:ipc");
    int         stream   = std::stoi(get("aeron.stream.id", "1001"));
    int         cpu_pin  = std::stoi(get("aeron.publisher.cpu", "-1"));
    std::string csv_path = get("input.csv.path", "data/sample.csv");

    std::cout << "[publisher] channel=" << channel
              << " stream=" << stream
              << " csv=" << csv_path << "\n";

    // ── Thread pinning (Linux) ────────────────────────────────────────────────
#ifdef __linux__
    if (cpu_pin >= 0) {
        cpu_set_t cpus;
        CPU_ZERO(&cpus);
        CPU_SET(cpu_pin, &cpus);
        pthread_setaffinity_np(pthread_self(), sizeof(cpus), &cpus);
        std::cout << "[publisher] pinned to CPU " << cpu_pin << "\n";
    }
#endif

    // ── Aeron context ─────────────────────────────────────────────────────────
    aeron::Context ctx;
    ctx.aeronDir(aeron::Context::defaultAeronPath()); // use existing driver dir
    // NOTE: this publisher expects an external MediaDriver (or the JVM's embedded one)

    auto aeron = aeron::Aeron::connect(ctx);
    std::cout << "[publisher] connected to Aeron driver\n";

    int64_t pub_id = aeron->addPublication(channel, stream);
    std::shared_ptr<aeron::Publication> pub;
    while (!(pub = aeron->findPublication(pub_id))) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    std::cout << "[publisher] publication ready\n";

    // ── Send buffer (cache-line aligned) ─────────────────────────────────────
    alignas(64) uint8_t msg_buf[hft::MESSAGE_LENGTH];

    // ── Replay CSV ────────────────────────────────────────────────────────────
    std::ifstream csv(csv_path);
    if (!csv.is_open()) {
        std::cerr << "[publisher] Cannot open CSV: " << csv_path << "\n";
        return 1;
    }

    std::string line;
    long sent = 0;

    while (g_running && std::getline(csv, line)) {
        hft::MarketEvent ev{};
        if (!parse_csv_line(line, ev)) continue;

        ev.ingress_nanos = nanos_now(); // stamp before offer

        hft::encode(msg_buf, ev);

        aeron::concurrent::AtomicBuffer ab(msg_buf, hft::MESSAGE_LENGTH);

        int64_t result;
        do {
            result = pub->offer(ab, 0, hft::MESSAGE_LENGTH);
        } while (result < 0 && g_running);

        if (++sent % 100000 == 0) {
            std::cout << "[publisher] sent " << sent << " events\n";
        }
    }

    std::cout << "[publisher] done. total sent=" << sent << "\n";
    return 0;
}
