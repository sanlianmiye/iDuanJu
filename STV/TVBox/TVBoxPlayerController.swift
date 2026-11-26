import AVFoundation
import Combine
import SwiftUI
import UIKit

/// Shared playback controller for TVBox VOD & Live streams.
/// Uses `MPVPlayerEngine` (libmpv + FFmpeg + MoltenVK + libdovi) by default so HTTP-FLV, raw MPEG-TS,
/// RTMP/RTSP, MKV, non-standard HLS, Dolby Vision, and DTS/AC3/TrueHD streams all play reliably on device,
/// with automatic fallback to `AVPlayerEngine`.
@MainActor
final class TVBoxPlayerController: ObservableObject {
    @Published private(set) var isPlaying = false
    @Published private(set) var isBuffering = false
    @Published private(set) var positionSec: Double = 0
    @Published private(set) var durationSec: Double = 0
    @Published private(set) var errorMsg: String?
    @Published private(set) var engineKind: PlayerEngineKind = .avplayer
    @Published var fill: Bool = false {
        didSet { activeEngine.fill = fill }
    }

    private let av = AVPlayerEngine()
    private var mpvInstance: MPVPlayerEngine?
    private var mpvInitAttempted = false
    private var mpv: MPVPlayerEngine? {
        if !mpvInitAttempted {
            mpvInitAttempted = true
            mpvInstance = try? MPVPlayerEngine()
        }
        return mpvInstance
    }
    private var activeEngine: PlayerEngine
    private var loadTask: Task<Void, Never>?
    private var tickTask: Task<Void, Never>?
    private var rate: Double = 1.0
    private var generation = 0

    var surface: UIView { activeEngine.surface }

    init() {
        activeEngine = av
        if let m = mpv {
            activeEngine = m
            engineKind = .mpv
        } else {
            engineKind = .avplayer
        }
        startTick()
    }

    deinit {
        loadTask?.cancel()
        tickTask?.cancel()
    }

    private func activateAudioSession() {
        do {
            let s = AVAudioSession.sharedInstance()
            try s.setCategory(.playback, mode: .moviePlayback, options: [])
            try s.setActive(true)
        } catch {}
    }

    /// Parses TVBox stream URLs that may carry inline pipe headers (`url|User-Agent=...&Referer=...`)
    /// or trailing `$label` fragments.
    static func parseStream(_ raw: String) -> (url: URL, headers: [String: String])? {
        var cleaned = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return nil }
        var headers: [String: String] = ["User-Agent": PluginHostContext.defaultUA]

        if let pipeIdx = cleaned.firstIndex(of: "|") {
            let headerPart = String(cleaned[cleaned.index(after: pipeIdx)...])
            cleaned = String(cleaned[..<pipeIdx]).trimmingCharacters(in: .whitespacesAndNewlines)
            for pair in headerPart.split(separator: "&") {
                let kv = pair.split(separator: "=", maxSplits: 1)
                if kv.count == 2 {
                    let k = String(kv[0]).trimmingCharacters(in: .whitespaces)
                    let v = String(kv[1]).removingPercentEncoding ?? String(kv[1])
                    if !k.isEmpty { headers[k] = v }
                }
            }
        }
        if let dollarIdx = cleaned.firstIndex(of: "$") {
            cleaned = String(cleaned[..<dollarIdx]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard let url = URL(string: cleaned) ?? cleaned.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed).flatMap(URL.init(string:)) else {
            return nil
        }
        return (url, headers)
    }

    func play(urlString: String?, title: String = "", rate: Double = 1.0) {
        self.rate = rate
        loadTask?.cancel()
        generation += 1
        let gen = generation

        guard let raw = urlString, let parsed = Self.parseStream(raw) else {
            errorMsg = "无效的播放地址"
            isBuffering = false
            isPlaying = false
            return
        }

        activateAudioSession()
        errorMsg = nil
        isBuffering = true
        positionSec = 0
        durationSec = 0

        let src: PlaybackCoordinator.Source = .remote(parsed.url, parsed.headers)
        loadTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let primary: PlayerEngine = (PlayerEngineSettings.preferred == .mpv ? self.mpv : nil) ?? self.av
            do {
                try await self.mount(primary, src: src, title: title, gen: gen)
            } catch is CancellationError {
                return
            } catch {
                guard self.generation == gen else { return }
                // Fallback from MPV to AVPlayer (or report error if already on AVPlayer).
                if primary.kind == .mpv {
                    do {
                        try await self.mount(self.av, src: src, title: title, gen: gen)
                        return
                    } catch is CancellationError {
                        return
                    } catch let avErr {
                        guard self.generation == gen else { return }
                        self.isBuffering = false
                        self.isPlaying = false
                        self.errorMsg = "播放失败：\(avErr.localizedDescription)"
                        return
                    }
                }
                self.isBuffering = false
                self.isPlaying = false
                self.errorMsg = "播放失败：\(error.localizedDescription)"
            }
        }
    }

    private func mount(_ engine: PlayerEngine, src: PlaybackCoordinator.Source, title: String, gen: Int) async throws {
        if activeEngine !== engine {
            activeEngine.onFailed = nil
            activeEngine.onEnded = nil
            activeEngine.stop()
            activeEngine = engine
        }
        activeEngine.fill = fill
        if engineKind != engine.kind { engineKind = engine.kind }

        engine.onFailed = { [weak self] err in
            guard let self, self.generation == gen else { return }
            self.isBuffering = false
            self.isPlaying = false
            self.errorMsg = err?.localizedDescription ?? "信号源播放中断"
        }

        try await engine.load(src, stream: nil, start: nil, title: title)
        guard generation == gen else { throw CancellationError() }
        engine.play(rate: rate)
        isBuffering = false
        isPlaying = true
        errorMsg = nil
    }

    func togglePlay() {
        if isPlaying {
            activeEngine.pause()
            isPlaying = false
        } else {
            activeEngine.play(rate: rate)
            isPlaying = true
        }
    }

    func setRate(_ newRate: Double) {
        rate = newRate
        if isPlaying { activeEngine.setRate(newRate) }
    }

    func seek(to seconds: Double) {
        let clamped = max(0, durationSec > 0 ? min(seconds, durationSec) : seconds)
        positionSec = clamped
        Task { @MainActor [weak self] in
            await self?.activeEngine.seek(to: clamped)
        }
    }

    func stop() {
        generation += 1
        loadTask?.cancel()
        loadTask = nil
        tickTask?.cancel()
        tickTask = nil
        activeEngine.onFailed = nil
        activeEngine.onEnded = nil
        av.stop()
        mpvInstance?.shutdown()
        mpvInstance = nil
        activeEngine = av
        isPlaying = false
        isBuffering = false
    }

    private func startTick() {
        tickTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard let self else { return }
                let t = self.activeEngine.currentTime
                let d = self.activeEngine.duration
                if t.isFinite { self.positionSec = max(0, t) }
                if d.isFinite && d > 0 { self.durationSec = d }
                if self.errorMsg == nil {
                    self.isBuffering = self.activeEngine.isWaiting
                }
            }
        }
    }
}

/// Hosts the active `TVBoxPlayerController` video surface (`MPVMetalView` or `AVPlayerLayer`)
/// and swaps seamlessly if an episode/channel falls back from MPVKit to AVPlayer.
struct TVBoxEngineSurface: UIViewRepresentable {
    @ObservedObject var controller: TVBoxPlayerController
    var fill: Bool = false

    final class ContainerView: UIView {
        weak var hosted: UIView?
        override func layoutSubviews() {
            super.layoutSubviews()
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            hosted?.frame = bounds
            CATransaction.commit()
        }
    }

    func makeUIView(context: Context) -> ContainerView {
        let v = ContainerView()
        v.backgroundColor = .black
        mountSurface(in: v)
        return v
    }

    func updateUIView(_ uiView: ContainerView, context: Context) {
        if controller.fill != fill { controller.fill = fill }
        mountSurface(in: uiView)
    }

    private func mountSurface(in container: ContainerView) {
        let s = controller.surface
        if container.hosted !== s {
            container.hosted?.removeFromSuperview()
            s.removeFromSuperview()
            s.frame = container.bounds
            s.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            container.addSubview(s)
            container.hosted = s
        }
    }
}
