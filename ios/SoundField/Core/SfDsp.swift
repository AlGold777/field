import Foundation

/// Обёртка над C-ядром sfdsp (dsp/include/sfdsp.h).
enum SfDsp {
    static var version: String { String(cString: sfdsp_version()) }
    static var abiVersion: Int { Int(sfdsp_abi_version()) }
}
