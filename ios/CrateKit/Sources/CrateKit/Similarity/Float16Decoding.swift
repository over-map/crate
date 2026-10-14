import Foundation

/// Converts IEEE 754 half-precision values without relying on `Float16`, which is unavailable on Intel Macs.
public enum Float16Decoding {
    /// Decodes one half-precision bit pattern to `Float` (handles subnormals, infinities and NaN).
    public static func float(fromBits bits: UInt16) -> Float {
        let sign = UInt32(bits & 0x8000) << 16
        let exponent = Int((bits >> 10) & 0x1F)
        let mantissa = UInt32(bits & 0x03FF)
        let result: UInt32
        switch exponent {
        case 0:
            if mantissa == 0 {
                result = sign
            } else {
                // Subnormal: value = mantissa · 2^-24.
                let value = Float(mantissa) * Float(sign: .plus, exponent: -24, significand: 1)
                return sign == 0 ? value : -value
            }
        case 0x1F:
            result = sign | 0x7F80_0000 | (mantissa << 13)
        default:
            result = sign | (UInt32(exponent - 15 + 127) << 23) | (mantissa << 13)
        }
        return Float(bitPattern: result)
    }

    /// Decodes little-endian float16 values from `data`. Throws if the byte count is odd.
    public static func floats(fromLittleEndian data: Data) throws -> [Float] {
        guard data.count % 2 == 0 else { throw SimilarityError.malformedVectors(reason: "odd byte count \(data.count)") }
        var out = [Float]()
        out.reserveCapacity(data.count / 2)
        data.withUnsafeBytes { raw in
            let bytes = raw.bindMemory(to: UInt8.self)
            var i = 0
            while i < bytes.count {
                let bits = UInt16(bytes[i]) | (UInt16(bytes[i + 1]) << 8)
                out.append(float(fromBits: bits))
                i += 2
            }
        }
        return out
    }
}
