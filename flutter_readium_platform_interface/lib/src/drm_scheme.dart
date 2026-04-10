/// DRM / content-protection mode for [FlutterReadium.setDrmConfiguration].
enum DrmScheme {
  /// Readium LCP only (default; matches legacy [FlutterReadium.setLcpPassphrase]).
  lcp(0),

  /// Altoral lcpl-like license (JSON `encryption.profile` must contain [altoralProfileMarker] on server).
  altoral(1),

  /// Try Altoral first, then LCP (e.g. mixed `.lcpl` files).
  dual(2);

  const DrmScheme(this.nativeIndex);

  /// Value sent over the method channel.
  final int nativeIndex;

  static DrmScheme fromIndex(int index) {
    return DrmScheme.values.firstWhere(
      (e) => e.nativeIndex == index,
      orElse: () => DrmScheme.lcp,
    );
  }
}

/// Marker matched against `encryption.profile` in the license JSON (case-insensitive).
const String altoralProfileMarker = 'altoral';
