//
//  Altoral lcpl-like license without ReadiumLCP / R2LCPClient.
//

import Foundation
import ReadiumShared
import ReadiumStreamer

private let altoralMarker = "altoral"
private let lcpSchemeUri = "http://readium.org/2014/01/lcp"
private let readiumLcpBasicProfile = "http://readium.org/lcp/basic-profile"

final class AltoralContentProtection: ContentProtection, @unchecked Sendable {
  private let assetRetriever: AssetRetriever
  /// When true (DRM scheme Altoral-only), accept `basic-profile` LCPL-shaped JSON with a publication link.
  private let acceptReadiumBasicProfileAsAltoral: Bool

  init(assetRetriever: AssetRetriever, acceptReadiumBasicProfileAsAltoral: Bool = false) {
    self.assetRetriever = assetRetriever
    self.acceptReadiumBasicProfileAsAltoral = acceptReadiumBasicProfileAsAltoral
  }

  func open(
    asset: Asset,
    credentials: String?,
    allowUserInteraction: Bool,
    sender: Any?,
  ) async -> Result<ContentProtectionAsset, ContentProtectionOpenError> {
    switch asset {
    case let .resource(res):
      return await openLicense(using: res, credentials: credentials)
    case .container:
      return .failure(.assetNotSupported(DebugError("Altoral expects a license resource asset")))
    }
  }

  private func openLicense(
    using asset: ResourceAsset,
    credentials: String?,
  ) async -> Result<ContentProtectionAsset, ContentProtectionOpenError> {
    guard asset.format.conformsTo(.lcpLicense) else {
      return .failure(.assetNotSupported(DebugError("Not an LCP license media type")))
    }
    return await asset.resource.read()
      .mapError { ContentProtectionOpenError.reading($0) }
      .asyncFlatMap { data -> Result<String, ContentProtectionOpenError> in
        guard let s = String(data: data, encoding: .utf8) else {
          return .failure(.reading(.decoding("License is not UTF-8")))
        }
        return .success(s)
      }
      .asyncFlatMap { json in
        guard Self.isAltoral(json, acceptReadiumBasicProfile: acceptReadiumBasicProfileAsAltoral) else {
          return .failure(.assetNotSupported(DebugError("License is not Altoral profile")))
        }
        guard let c = credentials?.trimmingCharacters(in: .whitespacesAndNewlines), !c.isEmpty else {
          return .failure(.reading(.decoding("Missing Altoral credentials")))
        }
        let engine: AltoralLcplikeEngine
        do {
          let candidates = AltoralLcplikeEngine.buildPassphraseCandidates(from: c)
          engine = try AltoralLcplikeEngine(jsonLicense: json, hashedPassphraseCandidates: candidates)
        } catch {
          return .failure(.reading(.decoding(error)))
        }
        guard
          let root = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
          let links = root["links"] as? [[String: Any]],
          let link = links.first(where: { ($0["rel"] as? String) == "publication" }),
          let href = link["href"] as? String,
          let url = HTTPURL(string: href)
        else {
          return .failure(.reading(.decoding("Missing publication link")))
        }
        let mtStr = link["type"] as? String
        let mt = mtStr.flatMap { MediaType($0) }
        return await retrievePublication(url: url, mediaType: mt)
          .asyncFlatMap { pub in
            switch pub {
            case let .container(c):
              return await Self.wrap(container: c, engine: engine, licenseJson: json)
            case .resource:
              return .failure(.assetNotSupported(DebugError("Expected container publication")))
            }
          }
      }
  }

  private func retrievePublication(url: HTTPURL, mediaType: MediaType?) async -> Result<Asset, AssetRetrieveURLError> {
    if let mediaType, mediaType.matches(.epub) {
      let format = Format(specifications: .zip, .epub, .lcp, mediaType: .epub, fileExtension: "epub")
      return await assetRetriever.retrieve(url: url, format: format)
    }
    return await assetRetriever.retrieve(url: url, hints: FormatHints(mediaType: mediaType))
  }

  private static func wrap(
    container: ContainerAsset,
    engine: AltoralLcplikeEngine,
    licenseJson: String,
  ) async -> Result<ContentProtectionAsset, ContentProtectionOpenError> {
    await altoralParseEncryption(in: container)
      .mapError { ContentProtectionOpenError.reading(.decoding($0)) }
      .asyncFlatMap { encMap in
        var a = container
        let dec = AltoralSimpleDecryptor(engine: engine, enc: encMap)
        a.container = a.container.map(transform: dec.mapResource)
        return .success(
          ContentProtectionAsset(
            asset: .container(a),
            onCreatePublication: { _, _, services in
              services.setContentProtectionServiceFactory { _ in
                AltoralCPService()
              }
            },
          ),
        )
      }
  }

  private static func isAltoral(_ json: String, acceptReadiumBasicProfile: Bool) -> Bool {
    guard let d = json.data(using: .utf8),
          let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
          let e = o["encryption"] as? [String: Any],
          let p = e["profile"] as? String
    else { return false }
    if p.lowercased().contains(altoralMarker) {
      return true
    }
    if acceptReadiumBasicProfile, p == readiumLcpBasicProfile {
      guard let links = o["links"] as? [[String: Any]] else { return false }
      return links.contains { ($0["rel"] as? String) == "publication" }
    }
    return false
  }
}

// MARK: - encryption.xml

private func altoralParseEncryption(in asset: ContainerAsset) async -> ReadResult<[AnyURL: Encryption]> {
  guard asset.format.conformsTo(.epub) else {
    return .failure(.decoding("Altoral iOS: only EPUB supported in this build"))
  }
  guard let res = asset.container[RelativeURL(path: "META-INF/encryption.xml")!] else {
    return .failure(.decoding("Missing META-INF/encryption.xml"))
  }
  return await res.read()
    .asyncFlatMap { data -> ReadResult<XMLDocument> in
      do {
        let doc = try await DefaultXMLDocumentFactory().open(data: data, namespaces: [.enc, .ds, .comp])
        return .success(doc)
      } catch {
        return .failure(.decoding(error))
      }
    }
    .flatMap { document in
      var m: [AnyURL: Encryption] = [:]
      for el in document.all("./enc:EncryptedData") {
        guard
          let alg = el.first("enc:EncryptionMethod")?.attribute(named: "Algorithm"),
          let uri = el.first("enc:CipherData/enc:CipherReference")?.attribute(named: "URI"),
          let u = RelativeURL(epubHREF: uri)?.anyURL
        else { continue }
        var scheme: String?
        if el.first("ds:KeyInfo/ds:RetrievalMethod")?.attribute(named: "URI") == "license.lcpl#/encryption/content_key" {
          scheme = lcpSchemeUri
        }
        var olen: Int?
        var comp: String?
        for p in el.all("enc:EncryptionProperties/enc:EncryptionProperty") {
          if let c = p.first("comp:Compression"), let meth = c.attribute(named: "Method"), let l = c.attribute(named: "OriginalLength") {
            olen = Int(l)
            comp = meth == "8" ? "deflate" : "none"
            break
          }
        }
        m[u] = Encryption(algorithm: alg, compression: comp, originalLength: olen, scheme: scheme)
      }
      return .success(m)
    }
}

// MARK: - decrypt (full read only; adequate for EPUB HTML)

private struct AltoralSimpleDecryptor {
  let engine: AltoralLcplikeEngine
  let enc: [AnyURL: Encryption]

  func mapResource(href: AnyURL, resource: Resource) -> Resource {
    let h = href.normalized
    guard let e = enc[h], e.scheme == lcpSchemeUri else { return resource }
    return AltoralOneResource(wrapping: resource, engine: engine, encryption: e)
  }
}

private final class AltoralOneResource: TransformingResource {
  private let engine: AltoralLcplikeEngine
  private let encryption: Encryption

  init(wrapping resource: Resource, engine: AltoralLcplikeEngine, encryption: Encryption) {
    self.engine = engine
    self.encryption = encryption
    super.init(resource)
  }

  override func transform(data: ReadResult<Data>) async -> ReadResult<Data> {
    await data.asyncFlatMap { encrypted in
      do {
        if encryption.isDeflated {
          return .failure(.decoding("Altoral iOS: deflated resources not supported"))
        }
        var plain = try engine.decipher(encrypted)
        let pad = Int(plain.last ?? 0)
        guard (1 ... 16).contains(pad), pad <= plain.count else {
          return .failure(.decoding(AltoralEngineError.decryptFailed))
        }
        plain = plain.prefix(plain.count - pad)
        return .success(Data(plain))
      } catch {
        return .failure(.decoding(error))
      }
    }
  }
}

private extension Encryption {
  var isDeflated: Bool { compression?.lowercased() == "deflate" }
}

private struct AltoralCPService: ContentProtectionService {
  let scheme = ContentProtectionScheme(rawValue: HTTPURL(string: "https://lileu.app/drm/altoral")!)
  var isRestricted: Bool { false }
  var error: Error? { nil }
}
