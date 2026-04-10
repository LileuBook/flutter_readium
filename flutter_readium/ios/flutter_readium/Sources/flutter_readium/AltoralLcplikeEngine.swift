//
//  LCPL-like JSON unlock using the same AES-256-CBC layout as org.readium.lcp.sdk.Lcp (Android).
//

import CommonCrypto
import Foundation

enum AltoralEngineError: Error {
  case invalidLicense
  case noValidPassphrase
  case decryptFailed
}

/// Unlocks `encryption.content_key` with hashed passphrase candidates; decrypts resources (IV + CBC ciphertext).
final class AltoralLcplikeEngine {
  private let jsonLicense: String
  private let drmContext: AltoralDRMContext

  struct AltoralDRMContext {
    let hashedPassphraseHex: String
    let encryptedContentKeyB64: String
  }

  /// Mirrors Android [AltoralContentProtection]: LCP basic-profile uses SHA-256(passphrase) as 64-char hex, not plain UUID text.
  static func buildPassphraseCandidates(from passphrase: String) -> [String] {
    let t = passphrase.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !t.isEmpty else { return [] }
    let hexRe = try! NSRegularExpression(pattern: "^[0-9a-fA-F]{64}$")
    if hexRe.firstMatch(in: t, range: NSRange(location: 0, length: t.utf16.count)) != nil {
      return [t.lowercased()]
    }
    var seen = Set<String>()
    var out: [String] = []
    func addHash(of s: String) {
      let h = Self.sha256HexUtf8(s)
      if seen.insert(h).inserted { out.append(h) }
    }
    addHash(of: t)
    if t != t.lowercased() { addHash(of: t.lowercased()) }
    if t != t.uppercased() { addHash(of: t.uppercased()) }
    let compact = t.replacingOccurrences(of: "-", with: "")
    if compact.count == 32, compact != t {
      addHash(of: compact)
      addHash(of: compact.lowercased())
    }
    return out
  }

  private static func sha256HexUtf8(_ string: String) -> String {
    guard let data = string.data(using: .utf8) else { return "" }
    var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
    data.withUnsafeBytes { buf in
      _ = CC_SHA256(buf.baseAddress, CC_LONG(data.count), &digest)
    }
    return digest.map { String(format: "%02x", $0) }.joined()
  }

  init(jsonLicense: String, hashedPassphraseCandidates: [String]) throws {
    self.jsonLicense = jsonLicense
    guard let data = jsonLicense.data(using: .utf8),
          let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          let enc = root["encryption"] as? [String: Any],
          let ck = enc["content_key"] as? [String: Any],
          let encryptedValue = ck["encrypted_value"] as? String
    else {
      throw AltoralEngineError.invalidLicense
    }
    let validHex = try Self.findValidHashedPassphrase(
      jsonLicense: jsonLicense,
      encryptedContentKeyB64: encryptedValue,
      candidates: hashedPassphraseCandidates,
    )
    drmContext = AltoralDRMContext(
      hashedPassphraseHex: validHex,
      encryptedContentKeyB64: encryptedValue,
    )
  }

  func decipher(_ encryptedData: Data) throws -> Data {
    let userKey = try Self.hexToBytes(drmContext.hashedPassphraseHex)
    let encCk = Data(base64Encoded: drmContext.encryptedContentKeyB64, options: [.ignoreUnknownCharacters])
    guard let encCk else { throw AltoralEngineError.decryptFailed }
    let contentKey = try Self.aesCbcDecrypt(key: userKey, dataWithIV: encCk)
    return try Self.aesCbcDecrypt(key: contentKey, dataWithIV: encryptedData)
  }

  // MARK: - Static crypto (mirrors Lcp.kt)

  private static func findValidHashedPassphrase(
    jsonLicense: String,
    encryptedContentKeyB64: String,
    candidates: [String],
  ) throws -> String {
    guard let data = jsonLicense.data(using: .utf8),
          let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          let enc = root["encryption"] as? [String: Any],
          let ck = enc["content_key"] as? [String: Any],
          let encryptedValue = ck["encrypted_value"] as? String,
          let licenseId = root["id"] as? String
    else {
      throw AltoralEngineError.invalidLicense
    }
    let encryptedContentKeyBytes = Data(base64Encoded: encryptedValue, options: [.ignoreUnknownCharacters])
      ?? Data()
    let userKeyObj = enc["user_key"] as? [String: Any]
    let keyCheckB64 = userKeyObj?["key_check"] as? String ?? ""
    let keyCheckBytes = keyCheckB64.isEmpty ? nil : Data(base64Encoded: keyCheckB64, options: [.ignoreUnknownCharacters])

    for raw in candidates {
      let hex: String
      do {
        hex = try normalizeHashedPassphraseToHex(raw)
      } catch {
        continue
      }
      let userKey = try? hexToBytes(hex)
      guard let userKey else { continue }
      do {
        let contentKey = try aesCbcDecrypt(key: userKey, dataWithIV: encryptedContentKeyBytes)
        if !contentKey.isEmpty {
          if let kcb = keyCheckBytes {
            if let dec = try? aesCbcDecrypt(key: userKey, dataWithIV: kcb),
               String(data: dec, encoding: .utf8) != licenseId {}
          }
          return hex
        }
      } catch {
        continue
      }
    }
    throw AltoralEngineError.noValidPassphrase
  }

  private static func normalizeHashedPassphraseToHex(_ value: String) throws -> String {
    let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
    if normalized.isEmpty { throw AltoralEngineError.noValidPassphrase }
    let hexRe = try NSRegularExpression(pattern: "^[0-9a-fA-F]{64}$")
    if hexRe.firstMatch(in: normalized, range: NSRange(location: 0, length: normalized.utf16.count)) != nil {
      return normalized.lowercased()
    }
    if let b64 = Data(base64Encoded: normalized, options: [.ignoreUnknownCharacters]), b64.count == 32 {
      return b64.map { String(format: "%02x", $0) }.joined()
    }
    throw AltoralEngineError.noValidPassphrase
  }

  private static func hexToBytes(_ hex: String) throws -> Data {
    var data = Data()
    var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    guard s.count % 2 == 0 else { throw AltoralEngineError.decryptFailed }
    var i = s.startIndex
    while i < s.endIndex {
      let j = s.index(i, offsetBy: 2)
      let byteStr = String(s[i ..< j])
      guard let b = UInt8(byteStr, radix: 16) else { throw AltoralEngineError.decryptFailed }
      data.append(b)
      i = j
    }
    return data
  }

  private static func aesCbcDecrypt(key: Data, dataWithIV: Data) throws -> Data {
    guard dataWithIV.count >= 16 else { throw AltoralEngineError.decryptFailed }
    let iv = dataWithIV.prefix(16)
    let ciphertext = dataWithIV.dropFirst(16)
    guard !ciphertext.isEmpty, ciphertext.count % 16 == 0 else { throw AltoralEngineError.decryptFailed }

    var outLength: size_t = 0
    var out = Data(count: ciphertext.count + kCCBlockSizeAES128)
    let status = out.withUnsafeMutableBytes { outBuf in
      ciphertext.withUnsafeBytes { ctBuf in
        key.withUnsafeBytes { keyBuf in
          iv.withUnsafeBytes { ivBuf in
            CCCrypt(
              CCOperation(kCCDecrypt),
              CCAlgorithm(kCCAlgorithmAES),
              CCOptions(0),
              keyBuf.baseAddress, key.count,
              ivBuf.baseAddress,
              ctBuf.baseAddress, ciphertext.count,
              outBuf.baseAddress, ciphertext.count,
              &outLength
            )
          }
        }
      }
    }
    guard status == kCCSuccess else { throw AltoralEngineError.decryptFailed }
    out.removeSubrange(outLength ..< out.count)
    let padLen = Int(out.last ?? 0)
    guard padLen >= 1, padLen <= 16, padLen <= out.count else { throw AltoralEngineError.decryptFailed }
    return out.prefix(out.count - padLen)
  }
}
