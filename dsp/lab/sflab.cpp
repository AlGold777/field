// sflab: лаборатория метрик ядра sfdsp (ТЗ §A0.3).
//   sflab run   [--corpus DIR] [--kotlin NAME=DIR]... [--out FILE]   метрики текущих цепочек → JSON
//   sflab gen-signals DIR [--corpus DIR]                      входные WAV для JVM-рендера Android
//   sflab check BASELINE CURRENT [--skip SUBSTR]...           сравнение с базовой линией, код 1 при регрессии
#include <dirent.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <set>

#include "sfdsp.h"
#include "suite.hpp"

using namespace sflab;

namespace {

constexpr int kBlock = 480;

std::vector<float> playoutRender(const std::vector<float>& in, double frac, double ppm, int block) {
    sf_playout* p = sf_playout_create(kSr, 960);
    std::vector<float> out(in.size(), 0.0f), tmp((size_t)block);
    for (size_t start = 0; start < in.size(); start += (size_t)block) {
        const int n = (int)std::min<size_t>((size_t)block, in.size() - start);
        const double ideal = (double)start * (1.0 + ppm * 1e-6) + frac - SF_SINC_GROUP_DELAY_FRAMES;
        sf_playout_render(p, in.data(), 0, (int64_t)in.size(), ideal, n, tmp.data());
        for (int i = 0; i < n; i++) out[start + (size_t)i] = std::isnan(tmp[(size_t)i]) ? 0.0f : tmp[(size_t)i];
    }
    sf_playout_destroy(p);
    return out;
}

ChainFn playoutChain(double frac, double ppm) {
    return [=](const std::string&, const std::vector<float>& in) { return playoutRender(in, frac, ppm, kBlock); };
}

// Ошибка L/R во времени: два корректора, дрейф кварцев ±ppm, белый шум оценки момента вывода σ (мкс), выбросы, скачок модели.
struct SyncScenario { const char* name; double ppm, sigmaUs; double spikeProb, spikeUs; double stepUs; };

void runSync(const SyncScenario& sc, Sink& sink) {
    sink.prefix = std::string("playout.sync.") + sc.name + ".";
    const int seconds = 180, blocks = seconds * kSr / kBlock, settle = 60 * kSr / kBlock;
    sf_playout* pl[2] = {sf_playout_create(kSr, 960), sf_playout_create(kSr, 960)};
    Rng rng(12345);
    const double ppmDev[2] = {+sc.ppm, -sc.ppm};
    double truth[2] = {1000000.0, 1000000.0};
    std::vector<double> err;
    for (int b = 0; b < blocks; b++) {
        double e[2];
        for (int d = 0; d < 2; d++) {
            double noise = rng.gauss() * sc.sigmaUs;
            if (sc.spikeProb > 0 && rng.uniform() < sc.spikeProb) noise += sc.spikeUs;
            if (sc.stepUs != 0 && b == blocks / 2 && d == 0) truth[0] += sc.stepUs * 1e-6 * kSr;  // реальный скачок модели времени
            sf_playout_plan(pl[d], truth[d] + noise * 1e-6 * kSr, kBlock);
            e[d] = (sf_playout_position(pl[d], 0) - truth[d]) / kSr * 1e6;  // µs, фактический момент относительно истины
            truth[d] += kBlock * (1.0 - ppmDev[d] * 1e-6);  // кварц убегает → истинное время блока
        }
        if (b >= settle) err.push_back(std::fabs(e[0] - e[1]));
    }
    sink.add("lr_error_p95_us", percentile(err, 0.95), "lower", 0.5);
    sink.add("lr_error_max_us", *std::max_element(err.begin(), err.end()), "lower", 2.0);
    for (auto* p : pl) sf_playout_destroy(p);
}

std::vector<std::string> listWavs(const std::string& dir) {
    std::vector<std::string> r;
    if (DIR* d = opendir(dir.c_str())) {
        while (dirent* e = readdir(d)) {
            std::string n = e->d_name;
            if (n.size() > 4 && n.substr(n.size() - 4) == ".wav" && n.find(".out.wav") == std::string::npos) r.push_back(n);
        }
        closedir(d);
    }
    std::sort(r.begin(), r.end());
    return r;
}

std::string stem(const std::string& f) { return f.substr(0, f.rfind('.')); }

int cmdRun(int argc, char** argv) {
    std::string corpus, outPath;
    std::vector<std::pair<std::string, std::string>> kotlin;  // имя цепочки → каталог с name.out.wav
    for (int i = 2; i < argc; i++) {
        if (!std::strcmp(argv[i], "--corpus") && i + 1 < argc) corpus = argv[++i];
        else if (!std::strcmp(argv[i], "--kotlin") && i + 1 < argc) { std::string a = argv[++i]; const auto eq = a.find('='); kotlin.push_back({a.substr(0, eq), a.substr(eq + 1)}); }
        else if (!std::strcmp(argv[i], "--out") && i + 1 < argc) outPath = argv[++i];
    }
    Sink sink;
    struct C { const char* name; double frac, ppm; bool integer, invariant; };
    // playout_int — путь без интерполяции (эквивалент iOS сегодня, битно-точный); frac05 — худшая фаза sinc; drift50 — реальный дрейф 50 ppm.
    const C chains[] = {{"playout_int", 0.0, 0.0, true, true}, {"playout_frac05", 0.5, 0.0, false, true}, {"playout_drift50", 0.0, 50.0, false, false}};
    auto wavs = corpus.empty() ? std::vector<std::string>() : listWavs(corpus);
    for (const auto& c : chains) {
        auto fn = playoutChain(c.frac, c.ppm);
        runChainSuite(c.name, fn, sink, ChainTraits{c.integer, c.invariant});
        for (const auto& f : wavs) runCorpus(c.name, fn, stem(f), readWav(corpus + "/" + f), sink, c.integer);
    }
    const SyncScenario scs[] = {
        {"drift100_jit20", 100, 20, 0, 0, 0},
        {"drift100_jit100", 100, 100, 0, 0, 0},
        {"drift0_jit50", 0, 50, 0, 0, 0},
        {"drift100_jit50_spikes", 100, 50, 0.01, 3000, 0},
        {"drift100_jit20_step100us", 100, 20, 0, 0, 100},
    };
    for (const auto& s : scs) runSync(s, sink);
    for (const auto& [kname, kdir] : kotlin) {
        auto fn = [&, kdir = kdir](const std::string& name, const std::vector<float>&) {
            return readWav(kdir + "/" + name + ".out.wav").ch[0];
        };
        runChainSuite(kname, fn, sink);
        for (const auto& f : wavs) {
            Wav w = readWav(corpus + "/" + f);
            bool have = true;
            for (size_t c = 0; c < w.ch.size(); c++) { FILE* t = std::fopen((kdir + "/corpus_" + stem(f) + "_" + std::to_string(c) + ".out.wav").c_str(), "rb"); if (t) std::fclose(t); else have = false; }
            if (have) runCorpus(kname, fn, stem(f), w, sink);
        }
    }
    char hdr[256];
    std::snprintf(hdr, sizeof hdr, "  \"sfdsp_version\": \"%s\",\n  \"sample_rate\": %d,\n", sfdsp_version(), kSr);
    const std::string json = toJson(sink.list, hdr);
    if (outPath.empty()) std::fputs(json.c_str(), stdout);
    else { FILE* f = std::fopen(outPath.c_str(), "wb"); if (!f) return 2; std::fputs(json.c_str(), f); std::fclose(f); }
    return 0;
}

int cmdGen(int argc, char** argv) {
    if (argc < 3) return 2;
    const std::string dir = argv[2];
    std::string corpus;
    for (int i = 3; i < argc; i++) if (!std::strcmp(argv[i], "--corpus") && i + 1 < argc) corpus = argv[++i];
    for (auto& [name, x] : synthSignals()) { Wav w; w.sampleRate = kSr; w.ch = {x}; writeWavF32(dir + "/" + name + ".wav", w); }
    if (!corpus.empty())
        for (const auto& f : listWavs(corpus)) {
            Wav w = readWav(corpus + "/" + f);
            for (size_t c = 0; c < w.ch.size(); c++) { Wav m; m.sampleRate = w.sampleRate; m.ch = {w.ch[c]}; writeWavF32(dir + "/corpus_" + stem(f) + "_" + std::to_string(c) + ".wav", m); }
        }
    return 0;
}

std::string slurp(const std::string& p) { auto b = readFileBytes(p); return std::string(b.begin(), b.end()); }

int cmdCheck(int argc, char** argv) {
    if (argc < 4) return 2;
    std::vector<std::string> skip;
    for (int i = 4; i < argc; i++) if (!std::strcmp(argv[i], "--skip") && i + 1 < argc) skip.push_back(argv[++i]);
    auto base = parseMetrics(slurp(argv[2])), cur = parseMetrics(slurp(argv[3]));
    int bad = 0, checked = 0, missing = 0;
    for (const auto& [key, b] : base) {
        bool skipped = false;
        for (const auto& s : skip) if (key.find(s) != std::string::npos) skipped = true;
        if (skipped) continue;
        auto it = cur.find(key);
        if (it == cur.end()) { std::printf("MISSING  %s\n", key.c_str()); bad++; missing++; continue; }
        checked++;
        const double v = it->second.v, d = v - b.v;
        bool fail = false;
        if (std::isnan(v) != std::isnan(b.v)) fail = true;
        else if (b.better == "lower") fail = d > b.tol;
        else if (b.better == "higher") fail = -d > b.tol;
        else if (b.better == "equal") fail = std::fabs(d) > b.tol;
        if (fail) { std::printf("REGRESSION %s: baseline %.6g -> %.6g (tol %.3g, %s)\n", key.c_str(), b.v, v, b.tol, b.better.c_str()); bad++; }
    }
    std::printf("sflab check: %d metrics compared, %d failed%s\n", checked, bad, missing ? " (some missing)" : "");
    return bad ? 1 : 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) { std::fputs("usage: sflab run|gen-signals|check ...\n", stderr); return 2; }
    try {
        if (!std::strcmp(argv[1], "run")) return cmdRun(argc, argv);
        if (!std::strcmp(argv[1], "gen-signals")) return cmdGen(argc, argv);
        if (!std::strcmp(argv[1], "check")) return cmdCheck(argc, argv);
    } catch (const std::exception& e) { std::fprintf(stderr, "sflab: %s\n", e.what()); return 2; }
    return 2;
}
