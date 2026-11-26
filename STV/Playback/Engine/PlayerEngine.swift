import AVFoundation
import AVKit
import UIKit

/// Which playback core plays an episode. System AVPlayer is the default (native Picture-in-Picture and
/// lower power draw); MPVKit (`libmpv` + `FFmpeg` + `libdovi` + `libplacebo`, iOS 16+) is opt-in for
/// Dolby Vision, HDR10, DTS/TrueHD/AC3/EAC3, and ASS subtitles, with AVPlayer fallback on failure.
enum PlayerEngineKind: String, CaseIterable, Sendable {
    case avplayer, mpv
    var label: String { self == .mpv ? "MPVKit (DV/DTS)" : "系统播放器" }
    var short: String { self == .mpv ? "MPV" : "AVPlayer" }
}

enum PlayerEngineSettings {
    static let key = "stv.player.engine"
    static var preferred: PlayerEngineKind {
        get {
            #if DEBUG
            if let e = ProcessInfo.processInfo.environment["STV_ENGINE"] {
                if e == "aether" || e == "mpv" { return .mpv }
                if let k = PlayerEngineKind(rawValue: e) { return k }
            }
            #endif
            guard let raw = UserDefaults.standard.string(forKey: key) else { return .avplayer }
            if raw == "aether" { return .mpv }
            return PlayerEngineKind(rawValue: raw) ?? .avplayer
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: key) }
    }
}

/// One audio / subtitle track, engine-neutral (Aether `TrackInfo` or an `AVMediaSelectionOption`).
struct MediaTrack: Identifiable, Hashable, Sendable {
    let id: Int
    let title: String
    var language: String? = nil

    /// "中文 · AAC 2.0" style title from the pieces an engine knows.
    static func title(name: String, language: String?, codec: String?, channels: Int, index: Int) -> String {
        var parts: [String] = []
        let code = language?.lowercased() ?? ""
        let lang = ["", "und", "unk", "mis", "zxx", "mul"].contains(code) ? nil : Locale.current.localizedString(forLanguageCode: code)
        // Demuxer placeholder names ("UND (aac)", "eng (ac3)") add nothing over language + codec.
        let synthesized = name.range(of: #"^[A-Za-z]{2,3} \([^)]*\)$"#, options: .regularExpression) != nil
        let name = synthesized ? "" : name
        if !name.isEmpty { parts.append(name) } else if let lang { parts.append(lang) } else { parts.append("轨道 \(index + 1)") }
        if let lang, !name.isEmpty, !name.contains(lang) { parts.append(lang) }
        if let codec, !codec.isEmpty { parts.append(codec.uppercased() + (channels > 0 ? " " + PlayerTech.channelLabel(channels) : "")) }
        return parts.isEmpty ? "轨道 \(index + 1)" : parts.joined(separator: " · ")
    }
}

/// The playback core behind `PlaybackCoordinator`. Both implementations are main-actor and drive one
/// video surface each; the coordinator mounts the active engine's surface and polls it on its tick.
@MainActor protocol PlayerEngine: AnyObject {
    var kind: PlayerEngineKind { get }
    /// Called after `load` returned when the item fails later (never for a superseded load).
    var onFailed: ((Error?) -> Void)? { get set }
    var onEnded: (() -> Void)? { get set }
    /// Explicit transport requests from native or software PiP controls.
    var onPlaybackRequested: ((Bool) -> Void)? { get set }
    var surface: UIView { get }

    /// Whether this engine can take the source at all.
    func supports(_ src: PlaybackCoordinator.Source) -> Bool
    /// Mount the source paused at `start`. Throws when it cannot be opened (the coordinator falls back).
    func load(_ src: PlaybackCoordinator.Source, stream: HGStream?, start: Double?, title: String) async throws
    func play(rate: Double)
    func pause()
    func setRate(_ rate: Double)
    func seek(to seconds: Double) async
    /// Drop the current item (engine switch / leaving the player).
    func stop()

    var currentTime: Double { get }
    var duration: Double { get }
    var bufferedUntil: Double { get }
    var presentationSize: CGSize { get }
    var isWaiting: Bool { get }
    var isPaused: Bool { get }
    var muted: Bool { get set }
    var maxRate: Double { get }
    var fill: Bool { get set }

    var audioTracks: [MediaTrack] { get }
    var selectedAudio: Int? { get }
    var subtitleTracks: [MediaTrack] { get }
    var selectedSubtitle: Int? { get }
    func selectAudio(_ id: Int)
    func selectSubtitle(_ id: Int?)
    /// Cue text the host draws (nil when nothing is selected or the platform renders the subtitles).
    var subtitleText: String? { get }

    func tech(stream: HGStream?) async -> PlayerTech
    /// The AVPlayer playing right now, if any (Now Playing session).
    var avPlayer: AVPlayer? { get }
    /// PiP controller for the current picture route; `pipIdentity` changes when it must be rebuilt.
    var pipIdentity: ObjectIdentifier? { get }
    func makePiPController() -> AVPictureInPictureController?
    func setPiPActive(_ active: Bool)
    /// Short route note for the tech strip ("Aether · loopback").
    var routeLabel: String { get }
    /// Real-time GPU sharpening level (0 = off, 1 = standard, 2 = strong).
    func setSharpen(_ level: Int)
    /// Real-time SDR-to-HDR inverse tone mapping on SDR content.
    func setSdrToHdr(_ enabled: Bool)
}

extension PlayerEngine {
    func setSharpen(_ level: Int) {}
    func setSdrToHdr(_ enabled: Bool) {}
}
