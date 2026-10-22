// Минимальный JSON: запись метрик и чтение/сравнение базовой линии (вложенные объекты, числа, строки).
#pragma once
#include <cmath>
#include <cstdio>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace sflab {

struct Metric {
    double v = 0;
    std::string better = "info";  // lower | higher | info | equal
    double tol = 0;
};

using MetricList = std::vector<std::pair<std::string, Metric>>;  // ключи вида "chain.signal.metric"

inline std::string fmtNum(double v) {
    if (std::isnan(v)) return "null";
    char buf[64];
    std::snprintf(buf, sizeof buf, "%.6g", v);
    return buf;
}

inline std::string toJson(const MetricList& m, const std::string& header) {
    std::ostringstream o;
    o << "{\n" << header << "  \"metrics\": {\n";
    for (size_t i = 0; i < m.size(); i++) {
        o << "    \"" << m[i].first << "\": {\"v\": " << fmtNum(m[i].second.v) << ", \"better\": \"" << m[i].second.better
          << "\", \"tol\": " << fmtNum(m[i].second.tol) << "}" << (i + 1 < m.size() ? "," : "") << "\n";
    }
    o << "  }\n}\n";
    return o.str();
}

// Разбор только того, что пишет toJson: ищет блок "metrics" и читает записи "ключ": {"v":..,"better":"..","tol":..}.
inline std::map<std::string, Metric> parseMetrics(const std::string& s) {
    std::map<std::string, Metric> out;
    size_t pos = s.find("\"metrics\"");
    if (pos == std::string::npos) throw std::runtime_error("no metrics block");
    pos = s.find('{', pos);
    size_t i = pos + 1;
    while (true) {
        size_t q1 = s.find('"', i);
        size_t close = s.find('}', i);
        if (q1 == std::string::npos) break;
        // конец внешнего блока: закрывающая скобка раньше следующего ключа
        size_t nextOpen = s.find('{', i);
        if (nextOpen == std::string::npos || (close != std::string::npos && close < q1 && close < nextOpen)) break;
        size_t q2 = s.find('"', q1 + 1);
        std::string key = s.substr(q1 + 1, q2 - q1 - 1);
        size_t ob = s.find('{', q2), cb = s.find('}', ob);
        std::string body = s.substr(ob, cb - ob);
        Metric m;
        auto num = [&](const char* name, double def) {
            size_t p = body.find(std::string("\"") + name + "\"");
            if (p == std::string::npos) return def;
            p = body.find(':', p) + 1;
            while (body[p] == ' ') p++;
            if (body.compare(p, 4, "null") == 0) return (double)NAN;
            return std::strtod(body.c_str() + p, nullptr);
        };
        m.v = num("v", NAN); m.tol = num("tol", 0);
        size_t p = body.find("\"better\"");
        if (p != std::string::npos) { size_t a = body.find('"', body.find(':', p)), b = body.find('"', a + 1); m.better = body.substr(a + 1, b - a - 1); }
        out[key] = m;
        i = cb + 1;
    }
    return out;
}

}  // namespace sflab
