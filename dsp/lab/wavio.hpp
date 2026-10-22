// Чтение/запись WAV (PCM 16/24/32, float32) для лаборатории.
#pragma once
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace sflab {

struct Wav {
    int sampleRate = 48000;
    std::vector<std::vector<float>> ch;  // ch[канал][кадр]
    size_t frames() const { return ch.empty() ? 0 : ch[0].size(); }
};

inline std::vector<uint8_t> readFileBytes(const std::string& path) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) throw std::runtime_error("cannot open " + path);
    std::vector<uint8_t> b;
    uint8_t buf[65536];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) b.insert(b.end(), buf, buf + n);
    std::fclose(f);
    return b;
}

inline Wav readWav(const std::string& path) {
    auto b = readFileBytes(path);
    auto u16 = [&](size_t o) { return (uint32_t)(b[o] | (b[o + 1] << 8)); };
    auto u32 = [&](size_t o) { return (uint32_t)(b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | ((uint32_t)b[o + 3] << 24)); };
    if (b.size() < 44 || std::memcmp(b.data(), "RIFF", 4) || std::memcmp(b.data() + 8, "WAVE", 4))
        throw std::runtime_error("not a WAV: " + path);
    size_t pos = 12;
    int fmt = 0, channels = 0, bits = 0;
    Wav w;
    while (pos + 8 <= b.size()) {
        const uint32_t size = u32(pos + 4);
        const size_t body = pos + 8;
        if (!std::memcmp(b.data() + pos, "fmt ", 4)) {
            fmt = (int)u16(body); channels = (int)u16(body + 2); w.sampleRate = (int)u32(body + 4); bits = (int)u16(body + 14);
            if (fmt == 0xFFFE && size >= 26) fmt = (int)u16(body + 24);
        } else if (!std::memcmp(b.data() + pos, "data", 4)) {
            size_t avail = std::min<size_t>(size, b.size() - body);
            const int bytes = bits / 8;
            if (channels <= 0 || bytes <= 0) throw std::runtime_error("bad fmt: " + path);
            const size_t frames = avail / ((size_t)bytes * channels);
            w.ch.assign(channels, std::vector<float>(frames));
            for (size_t i = 0; i < frames; i++)
                for (int c = 0; c < channels; c++) {
                    const uint8_t* p = b.data() + body + (i * channels + c) * bytes;
                    float v;
                    if (fmt == 3 && bits == 32) { std::memcpy(&v, p, 4); }
                    else if (fmt == 1 && bits == 16) v = (int16_t)(p[0] | (p[1] << 8)) / 32768.0f;
                    else if (fmt == 1 && bits == 24) { int32_t s = (p[0] << 8) | (p[1] << 16) | ((int32_t)p[2] << 24); v = (float)((double)s / 2147483648.0); }
                    else if (fmt == 1 && bits == 32) { int32_t s; std::memcpy(&s, p, 4); v = (float)((double)s / 2147483648.0); }
                    else throw std::runtime_error("unsupported WAV format in " + path);
                    w.ch[c][i] = v;
                }
            return w;
        }
        pos = body + size + (size & 1);
    }
    throw std::runtime_error("no data chunk: " + path);
}

inline void writeWavF32(const std::string& path, const Wav& w) {
    const uint32_t channels = (uint32_t)w.ch.size(), frames = (uint32_t)w.frames();
    const uint32_t dataBytes = frames * channels * 4;
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) throw std::runtime_error("cannot write " + path);
    auto w32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
    auto w16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
    std::fwrite("RIFF", 1, 4, f); w32(36 + dataBytes); std::fwrite("WAVEfmt ", 1, 8, f);
    w32(16); w16(3); w16((uint16_t)channels); w32((uint32_t)w.sampleRate); w32((uint32_t)w.sampleRate * channels * 4);
    w16((uint16_t)(channels * 4)); w16(32);
    std::fwrite("data", 1, 4, f); w32(dataBytes);
    for (uint32_t i = 0; i < frames; i++)
        for (uint32_t c = 0; c < channels; c++) std::fwrite(&w.ch[c][i], 4, 1, f);
    std::fclose(f);
}

}  // namespace sflab
