import AVFoundation
import AVKit
import Libmpv
import QuartzCore
import UIKit

/// Metal-backed video host for libmpv (`vo=gpu-next` + `gpu-api=vulkan` via MoltenVK).
/// Enables EDR/HDR output so libplacebo + libdovi can drive Dolby Vision and HDR10 natively on iOS 16+.
final class MPVMetalLayer: CAMetalLayer {
    // MoltenVK briefly writes 1×1 while completing an old presentation. Do not let that
    // transient size become the drawable used by MPV.
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set {
            guard newValue.width > 1, newValue.height > 1 else { return }
            super.drawableSize = newValue
        }
    }

    // Disable implicit CoreAnimation bounds/position animations during rotation so the frame
    // never lags or jumps from the bottom-left corner.
    override func action(forKey event: String) -> CAAction? {
        if event == "bounds" || event == "position" || event == "frame" || event == "drawableSize" || event == "contents" {
            return NSNull()
        }
        return super.action(forKey: event)
    }
}

final class MPVMetalView: UIView {
    override class var layerClass: AnyClass { MPVMetalLayer.self }

    var metalLayer: MPVMetalLayer { layer as! MPVMetalLayer }
    var onResize: (() -> Void)?
    private var reportedDrawableSize = CGSize.zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        isUserInteractionEnabled = false
        contentMode = .scaleAspectFit
        metalLayer.contentsScale = UIScreen.main.scale
        metalLayer.contentsGravity = .resizeAspect
        metalLayer.wantsExtendedDynamicRangeContent = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func setFill(_ fill: Bool) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        contentMode = fill ? .scaleAspectFill : .scaleAspectFit
        metalLayer.contentsGravity = fill ? .resizeAspectFill : .resizeAspect
        CATransaction.commit()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let scale = window?.screen.scale ?? UIScreen.main.scale
        let size = CGSize(width: (bounds.width * scale).rounded(), height: (bounds.height * scale).rounded())
        guard size.width > 1, size.height > 1 else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        metalLayer.contentsScale = scale
        metalLayer.drawableSize = size
        CATransaction.commit()
        guard size != reportedDrawableSize else { return }
        reportedDrawableSize = size
        onResize?()
    }
}

/// MPVKit (`libmpv` + `FFmpeg` + `libdovi` + `libplacebo` + `libass`, LGPL, iOS 15+) playback core.
/// Handles Dolby Vision (Profile 5/7/8 via libdovi + gpu-next), HDR10/HLG, DTS/DTS-HD/TrueHD/AC3/E-AC3
/// software decoding, ASS subtitles, custom HTTP headers/cookies, and local loopback proxies (including `/tg/`).
@MainActor final class MPVPlayerEngine: PlayerEngine {
    let kind = PlayerEngineKind.mpv
    var onFailed: ((Error?) -> Void)?
    var onEnded: (() -> Void)?
    var onPlaybackRequested: ((Bool) -> Void)?

    static var loadTimeout: TimeInterval = 25

    private let hostView = MPVMetalView()
    var surface: UIView { hostView }

    private var mpv: OpaquePointer?
    private let eventQueue = DispatchQueue(label: "stv.mpv.events", qos: .userInitiated)
    private var shuttingDown = false
    private var mounted = false
    private var generation = 0
    private var loadContinuation: CheckedContinuation<Void, Error>?
    private var lastURL: URL?
    private var refitTask: Task<Void, Never>?
    private let videoQueue = DispatchQueue(label: "stv.mpv.video", qos: .userInitiated)
    private var lifecycleObservers: [NSObjectProtocol] = []
    private var suspendedForBackground = false
    private var savedVidForBackground = "auto"
    private var resumeAfterBackground = false

    // Cached playback state updated from libmpv property observations / queries.
    private var cachedTime: Double = 0
    private var cachedDuration: Double = 0
    private var cachedBuffered: Double = 0
    private var cachedSize: CGSize = .zero
    private var cachedWaiting: Bool = false
    private var cachedPaused: Bool = true
    private var cachedAudioTracks: [MediaTrack] = []
    private var cachedSubtitleTracks: [MediaTrack] = []
    private var cachedAudioID: Int?
    private var cachedSubtitleID: Int?
    private var isMuted = false

    enum Failure: LocalizedError {
        case initFailed, unsupported, timeout, engine(String)
        var errorDescription: String? {
            switch self {
            case .initFailed: return "MPVKit 初始化失败"
            case .unsupported: return "MPVKit 不支持此片源"
            case .timeout: return "MPVKit 打开超时"
            case .engine(let m): return m
            }
        }
    }

    init() throws {
        guard let handle = mpv_create() else { throw Failure.initFailed }
        mpv = handle
        configureOptions(handle)
        let status = mpv_initialize(handle)
        guard status >= 0 else {
            mpv_terminate_destroy(handle)
            mpv = nil
            throw Failure.engine("mpv_initialize 失败 (\(status))")
        }
        observeCoreProperties(handle)
        hostView.onResize = { [weak self] in
            Task { @MainActor [weak self] in self?.scheduleVideoRefit() }
        }
        observeAppLifecycle()
        startEventLoop()
    }

    deinit {
        shuttingDown = true
        refitTask?.cancel()
        lifecycleObservers.forEach { NotificationCenter.default.removeObserver($0) }
        // Drain any pending videoQueue property calls before destroying mpv_handle* to avoid use-after-free.
        videoQueue.sync {}
        if let handle = mpv {
            mpv_wakeup(handle)
            mpv_terminate_destroy(handle)
        }
    }

    private func observeAppLifecycle() {
        let nc = NotificationCenter.default
        lifecycleObservers.append(
            nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.handleDidEnterBackground() }
            }
        )
        lifecycleObservers.append(
            nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.restoreFromBackgroundIfNeeded() }
            }
        )
        lifecycleObservers.append(
            nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.restoreFromBackgroundIfNeeded() }
            }
        )
    }

    private func handleDidEnterBackground() {
        guard !shuttingDown, mounted, !suspendedForBackground, let handle = mpv else { return }
        refitTask?.cancel()
        suspendedForBackground = true
        resumeAfterBackground = !cachedPaused
        let currentVid = getStringProperty("vid") ?? "auto"
        savedVidForBackground = (currentVid == "no" || currentVid.isEmpty) ? "auto" : currentVid
        // Tear down MoltenVK/Metal VO and pause before iOS revokes background GPU access,
        // preventing frozen video / A-V desync after lock-screen unlock.
        var pauseFlag: Int32 = 1
        _ = mpv_set_property(handle, "pause", MPV_FORMAT_FLAG, &pauseFlag)
        _ = mpv_set_property_string(handle, "vid", "no")
    }

    private func restoreFromBackgroundIfNeeded() {
        guard !shuttingDown, suspendedForBackground, let handle = mpv else { return }
        suspendedForBackground = false
        guard mounted else { return }
        hostView.setNeedsLayout()
        hostView.layoutIfNeeded()
        let restoreVid = savedVidForBackground
        let shouldResume = resumeAfterBackground || !cachedPaused
        let seekPos: Double? = cachedDuration > 0 ? max(0, cachedTime) : nil
        videoQueue.async { [weak self] in
            guard let self, !self.shuttingDown else { return }
            _ = mpv_set_property_string(handle, "vid", restoreVid)
            if let seekPos {
                Self.runCommand(handle, ["seek", String(format: "%.3f", seekPos), "absolute+exact"])
            }
            if shouldResume {
                var flag: Int32 = 0
                _ = mpv_set_property(handle, "pause", MPV_FORMAT_FLAG, &flag)
            }
        }
    }

    private func configureOptions(_ handle: OpaquePointer) {
        let setOpt: (String, String) -> Void = { key, value in
            _ = mpv_set_option_string(handle, key, value)
        }
        // Pass CAMetalLayer pointer as `wid` for MoltenVK + libplacebo gpu-next rendering.
        var layerPtr = Unmanaged.passUnretained(hostView.metalLayer).toOpaque()
        _ = mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &layerPtr)

        setOpt("vo", "gpu-next")
        setOpt("gpu-api", "vulkan")
        setOpt("gpu-context", "moltenvk")
        setOpt("hwdec", "videotoolbox")
        setOpt("hwdec-codecs", "all")
        // Enable native HDR / Dolby Vision colorspace hint (libdovi parses Profile 5/7/8 RPU).
        setOpt("target-colorspace-hint", "yes")
        setOpt("tone-mapping", "auto")
        let savedEnhance = UserDefaults.standard.integer(forKey: "stv.player.enhance")
        if savedEnhance > 0 {
            setOpt("sharpen", savedEnhance >= 2 ? "1.0" : "0.55")
        }
        // Audio: AudioUnit output with FFmpeg decoding DTS / DTS-HD / TrueHD / AC3 / E-AC3.
        setOpt("ao", "audiounit")
        setOpt("audio-channels", "auto-safe")
        setOpt("keep-open", "yes")
        setOpt("pause", "yes")
        setOpt("sub-auto", "fuzzy")
        setOpt("demuxer-max-bytes", "64MiB")
        setOpt("demuxer-max-back-bytes", "16MiB")
        setOpt("network-timeout", "20")
        #if DEBUG
        setOpt("terminal", "no")
        #endif
    }

    private func observeCoreProperties(_ handle: OpaquePointer) {
        mpv_observe_property(handle, 1, "time-pos", MPV_FORMAT_DOUBLE)
        mpv_observe_property(handle, 2, "duration", MPV_FORMAT_DOUBLE)
        mpv_observe_property(handle, 3, "demuxer-cache-time", MPV_FORMAT_DOUBLE)
        mpv_observe_property(handle, 4, "paused-for-cache", MPV_FORMAT_FLAG)
        mpv_observe_property(handle, 5, "pause", MPV_FORMAT_FLAG)
        mpv_observe_property(handle, 6, "eof-reached", MPV_FORMAT_FLAG)
        mpv_observe_property(handle, 7, "dwidth", MPV_FORMAT_INT64)
        mpv_observe_property(handle, 8, "dheight", MPV_FORMAT_INT64)
        mpv_observe_property(handle, 9, "aid", MPV_FORMAT_INT64)
        mpv_observe_property(handle, 10, "sid", MPV_FORMAT_INT64)
    }

    private func startEventLoop() {
        guard let handle = mpv else { return }
        eventQueue.async { [weak self] in
            while let self, !self.shuttingDown {
                guard let eventPtr = mpv_wait_event(handle, 0.5) else { continue }
                let event = eventPtr.pointee
                if event.event_id == MPV_EVENT_NONE { continue }
                if event.event_id == MPV_EVENT_SHUTDOWN { break }

                switch event.event_id {
                case MPV_EVENT_FILE_LOADED:
                    Task { @MainActor [weak self] in self?.handleFileLoaded() }

                case MPV_EVENT_END_FILE:
                    let end = event.data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                    let reason = end.reason
                    let errCode = end.error
                    Task { @MainActor [weak self] in self?.handleEndFile(reason: reason, error: errCode) }

                case MPV_EVENT_PROPERTY_CHANGE:
                    let prop = event.data.assumingMemoryBound(to: mpv_event_property.self).pointee
                    let name = String(cString: prop.name)
                    if prop.format == MPV_FORMAT_DOUBLE, let p = prop.data {
                        let val = p.assumingMemoryBound(to: Double.self).pointee
                        Task { @MainActor [weak self] in self?.handleDoubleProp(name, val) }
                    } else if prop.format == MPV_FORMAT_FLAG, let p = prop.data {
                        let val = p.assumingMemoryBound(to: Int32.self).pointee != 0
                        Task { @MainActor [weak self] in self?.handleFlagProp(name, val) }
                    } else if prop.format == MPV_FORMAT_INT64, let p = prop.data {
                        let val = p.assumingMemoryBound(to: Int64.self).pointee
                        Task { @MainActor [weak self] in self?.handleIntProp(name, val) }
                    }

                default:
                    break
                }
            }
        }
    }

    private func handleFileLoaded() {
        cachedWaiting = false
        refreshTrackLists()
        if let cont = loadContinuation {
            loadContinuation = nil
            cont.resume()
        }
    }

    private func handleEndFile(reason: mpv_end_file_reason, error: Int32) {
        if reason == MPV_END_FILE_REASON_ERROR {
            let msg = String(cString: mpv_error_string(error))
            if let cont = loadContinuation {
                loadContinuation = nil
                cont.resume(throwing: Failure.engine("MPV 播放错误：\(msg) (\(error))"))
            } else if mounted {
                mounted = false
                onFailed?(Failure.engine("MPV 播放中断：\(msg) (\(error))"))
            }
        } else if reason == MPV_END_FILE_REASON_EOF, mounted {
            onEnded?()
        }
    }

    private func handleDoubleProp(_ name: String, _ val: Double) {
        switch name {
        case "time-pos": cachedTime = max(0, val)
        case "duration": cachedDuration = max(0, val)
        case "demuxer-cache-time": cachedBuffered = max(0, val)
        default: break
        }
    }

    private func handleFlagProp(_ name: String, _ val: Bool) {
        switch name {
        case "paused-for-cache": cachedWaiting = val
        case "pause": cachedPaused = val
        case "eof-reached": if val && mounted { onEnded?() }
        default: break
        }
    }

    private func handleIntProp(_ name: String, _ val: Int64) {
        switch name {
        case "dwidth": cachedSize.width = CGFloat(max(0, val))
        case "dheight": cachedSize.height = CGFloat(max(0, val))
        case "aid": cachedAudioID = val > 0 ? Int(val) : nil
        case "sid": cachedSubtitleID = val > 0 ? Int(val) : nil
        default: break
        }
    }

    // MARK: Source mapping

    enum Input { case url(URL, headers: [String: String], hls: Bool) }

    nonisolated static func input(for src: PlaybackCoordinator.Source) -> Input? {
        switch src {
        case .file(let u): return .url(u, headers: [:], hls: false)
        case .remote(let u, let h): return .url(u, headers: h, hls: isPlaylist(u))
        case .direct(let u): return .url(u, headers: [:], hls: isPlaylist(u))
        case .stream(let loader, let s):
            guard let u = URL(string: s), !isPlaylist(u), loader.keyHex.isEmpty else { return nil }
            return .url(u, headers: loader.headers, hls: false)
        }
    }

    nonisolated static func isPlaylist(_ u: URL) -> Bool {
        let p = u.path.lowercased()
        return p.hasSuffix(".m3u8") || p.hasSuffix("p.m3u8") || p.contains("/m3u8/")
    }

    func supports(_ src: PlaybackCoordinator.Source) -> Bool { Self.input(for: src) != nil }

    nonisolated static func headers(_ h: [String: String], for url: URL) -> [String: String] {
        var out = h
        if url.scheme?.hasPrefix("http") == true, out["Cookie"] == nil, url.host != "127.0.0.1",
           let cookies = HTTPCookieStorage.shared.cookies(for: url), !cookies.isEmpty {
            out.merge(HTTPCookie.requestHeaderFields(with: cookies)) { a, _ in a }
        }
        if out["User-Agent"] == nil, url.host != "127.0.0.1", url.scheme?.hasPrefix("http") == true {
            out["User-Agent"] = SiteHTTP.userAgent
        }
        return out
    }

    func load(_ src: PlaybackCoordinator.Source, stream: HGStream?, start: Double?, title: String) async throws {
        guard let handle = mpv else { throw Failure.initFailed }
        guard case .url(let u, let rawHeaders, _) = Self.input(for: src) else { throw Failure.unsupported }
        #if DEBUG
        if ProcessInfo.processInfo.environment["STV_MPV_FAIL"] == "1" || ProcessInfo.processInfo.environment["STV_AETHER_FAIL"] == "1" {
            throw Failure.engine("forced (STV_MPV_FAIL)")
        }
        #endif

        generation += 1
        let gen = generation
        mounted = false
        cachedWaiting = true
        cachedTime = start ?? 0
        cachedDuration = 0
        cachedBuffered = 0
        cachedSize = .zero
        cachedAudioTracks = []
        cachedSubtitleTracks = []
        lastURL = u

        let merged = Self.headers(rawHeaders, for: u)
        if let ua = merged["User-Agent"] {
            _ = mpv_set_property_string(handle, "user-agent", ua)
        }
        if let ref = merged["Referer"] {
            _ = mpv_set_property_string(handle, "referrer", ref)
        }
        let extra = merged.filter { $0.key != "User-Agent" && $0.key != "Referer" }
            .map { "\($0.key): \($0.value)" }
            .joined(separator: ",")
        _ = mpv_set_property_string(handle, "http-header-fields", extra)
        _ = mpv_set_property_string(handle, "start", (start ?? 0) > 0 ? String(format: "%.3f", start ?? 0) : "none")
        _ = mpv_set_property_string(handle, "pause", "yes")
        if suspendedForBackground {
            suspendedForBackground = false
            resumeAfterBackground = false
            _ = mpv_set_property_string(handle, "vid", "auto")
        }

        var timedOut = false
        let timer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.loadTimeout * 1_000_000_000))
            guard !Task.isCancelled, let self, self.generation == gen else { return }
            timedOut = true
            if let cont = self.loadContinuation {
                self.loadContinuation = nil
                cont.resume(throwing: Failure.timeout)
            }
            self.command(["stop"])
        }
        defer { timer.cancel() }

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            if let prev = self.loadContinuation {
                self.loadContinuation = nil
                prev.resume(throwing: CancellationError())
            }
            self.loadContinuation = cont
            let target = u.isFileURL ? u.path : u.absoluteString
            self.command(["loadfile", target, "replace"])
        }

        if timedOut { throw Failure.timeout }
        guard generation == gen else { throw CancellationError() }
        mounted = true
        refreshTrackLists()
    }

    private func command(_ args: [String]) {
        guard let handle = mpv else { return }
        Self.runCommand(handle, args)
    }

    nonisolated private static func runCommand(_ handle: OpaquePointer, _ args: [String]) {
        var cArgs: [UnsafeMutablePointer<CChar>?] = args.map { strdup($0) }
        cArgs.append(nil)
        defer { cArgs.forEach { if let p = $0 { free(p) } } }
        var constArgs: [UnsafePointer<CChar>?] = cArgs.map { UnsafePointer($0) }
        _ = constArgs.withUnsafeMutableBufferPointer { buf in
            mpv_command(handle, buf.baseAddress)
        }
    }

    func play(rate: Double) {
        guard let handle = mpv else { return }
        setRate(rate)
        if suspendedForBackground {
            resumeAfterBackground = true
            cachedPaused = false
            restoreFromBackgroundIfNeeded()
            return
        }
        // If at EOF, seek to 0 before resuming.
        var eof: Int32 = 0
        if mpv_get_property(handle, "eof-reached", MPV_FORMAT_FLAG, &eof) >= 0, eof != 0 {
            command(["seek", "0", "absolute"])
        }
        var flag: Int32 = 0
        _ = mpv_set_property(handle, "pause", MPV_FORMAT_FLAG, &flag)
        cachedPaused = false
    }

    func pause() {
        guard let handle = mpv else { return }
        resumeAfterBackground = false
        var flag: Int32 = 1
        _ = mpv_set_property(handle, "pause", MPV_FORMAT_FLAG, &flag)
        cachedPaused = true
    }

    func setRate(_ rate: Double) {
        guard let handle = mpv else { return }
        var speed = max(0.25, min(rate, maxRate))
        _ = mpv_set_property(handle, "speed", MPV_FORMAT_DOUBLE, &speed)
    }

    func seek(to seconds: Double) async {
        command(["seek", String(format: "%.3f", max(0, seconds)), "absolute"])
        cachedTime = max(0, seconds)
    }

    func stop() {
        generation += 1
        refitTask?.cancel()
        suspendedForBackground = false
        resumeAfterBackground = false
        mounted = false
        if let cont = loadContinuation {
            loadContinuation = nil
            cont.resume(throwing: CancellationError())
        }
        command(["stop"])
    }

    /// MPVKit's MoltenVK context reads the Metal drawable size when the video output is built.
    /// A rotation changes the layer but does not send MPV a resize event. Keep `contentsGravity`
    /// centred during the rotation animation, then quickly rebuild the VO once the layout settles,
    /// preserving the active `vid` track and guarding against teardown races.
    private func scheduleVideoRefit() {
        guard !suspendedForBackground else { return }
        refitTask?.cancel()
        refitTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 80_000_000)
            guard !Task.isCancelled, let self, !self.shuttingDown, !self.suspendedForBackground, self.mounted else { return }
            guard let handle = self.mpv else { return }
            let activeVid = self.getStringProperty("vid") ?? "auto"
            self.videoQueue.async { [weak self] in
                guard let self, !self.shuttingDown else { return }
                _ = mpv_set_property_string(handle, "vid", "no")
                _ = mpv_set_property_string(handle, "vid", activeVid == "no" ? "auto" : activeVid)
            }
        }
    }

    var currentTime: Double { cachedTime }
    var duration: Double { cachedDuration }
    var bufferedUntil: Double { max(cachedTime, cachedBuffered) }
    var presentationSize: CGSize { cachedSize }
    var isWaiting: Bool { cachedWaiting }
    var isPaused: Bool { cachedPaused }
    var maxRate: Double { 3.0 }

    var muted: Bool {
        get { isMuted }
        set {
            guard newValue != isMuted, let handle = mpv else { return }
            isMuted = newValue
            var flag: Int32 = newValue ? 1 : 0
            _ = mpv_set_property(handle, "mute", MPV_FORMAT_FLAG, &flag)
        }
    }

    var fill: Bool = false {
        didSet {
            hostView.setFill(fill)
            guard let handle = mpv else { return }
            var panscan: Double = fill ? 1.0 : 0.0
            _ = mpv_set_property(handle, "panscan", MPV_FORMAT_DOUBLE, &panscan)
        }
    }

    func setSharpen(_ level: Int) {
        guard let handle = mpv else { return }
        var amount: Double = level >= 2 ? 1.0 : (level == 1 ? 0.55 : 0.0)
        _ = mpv_set_property(handle, "sharpen", MPV_FORMAT_DOUBLE, &amount)
    }

    // MARK: Tracks

    private func getStringProperty(_ name: String) -> String? {
        guard let handle = mpv, let ptr = mpv_get_property_string(handle, name) else { return nil }
        defer { mpv_free(ptr) }
        let s = String(cString: ptr)
        return s.isEmpty ? nil : s
    }

    private func getDoubleProperty(_ name: String) -> Double? {
        guard let handle = mpv else { return nil }
        var v: Double = 0
        return mpv_get_property(handle, name, MPV_FORMAT_DOUBLE, &v) >= 0 ? v : nil
    }

    private func getIntProperty(_ name: String) -> Int? {
        guard let handle = mpv else { return nil }
        var v: Int64 = 0
        return mpv_get_property(handle, name, MPV_FORMAT_INT64, &v) >= 0 ? Int(v) : nil
    }

    private func refreshTrackLists() {
        let count = getIntProperty("track-list/count") ?? 0
        var audios: [MediaTrack] = []
        var subs: [MediaTrack] = []
        for idx in 0..<count {
            let prefix = "track-list/\(idx)"
            guard let type = getStringProperty("\(prefix)/type"),
                  let trackID = getIntProperty("\(prefix)/id") else { continue }
            let title = getStringProperty("\(prefix)/title") ?? ""
            let lang = getStringProperty("\(prefix)/lang")
            let codec = getStringProperty("\(prefix)/codec")
            let channels = getIntProperty("\(prefix)/demux-channel-count") ?? 0
            let selected = getStringProperty("\(prefix)/selected") == "yes"
            if type == "audio" {
                let label = MediaTrack.title(name: title, language: lang, codec: codec, channels: channels, index: audios.count)
                audios.append(MediaTrack(id: trackID, title: label, language: lang))
                if selected { cachedAudioID = trackID }
            } else if type == "sub" {
                let label = MediaTrack.title(name: title, language: lang, codec: nil, channels: 0, index: subs.count)
                subs.append(MediaTrack(id: trackID, title: label, language: lang))
                if selected { cachedSubtitleID = trackID }
            }
        }
        cachedAudioTracks = audios
        cachedSubtitleTracks = subs
    }

    var audioTracks: [MediaTrack] { cachedAudioTracks }
    var selectedAudio: Int? { cachedAudioID }
    var subtitleTracks: [MediaTrack] { cachedSubtitleTracks }
    var selectedSubtitle: Int? { cachedSubtitleID }

    func selectAudio(_ id: Int) {
        guard let handle = mpv else { return }
        var val = Int64(id)
        _ = mpv_set_property(handle, "aid", MPV_FORMAT_INT64, &val)
        cachedAudioID = id
    }

    func selectSubtitle(_ id: Int?) {
        guard let handle = mpv else { return }
        if let id {
            var val = Int64(id)
            _ = mpv_set_property(handle, "sid", MPV_FORMAT_INT64, &val)
            cachedSubtitleID = id
        } else {
            _ = mpv_set_property_string(handle, "sid", "no")
            cachedSubtitleID = nil
        }
    }

    /// libmpv renders internal & ASS subtitles directly onto the Metal/libplacebo canvas.
    var subtitleText: String? { nil }

    // MARK: Tech

    nonisolated static func codecLabel(_ c: String?) -> String {
        switch c?.lowercased() {
        case "h264": return "H.264"
        case "hevc", "h265": return "HEVC"
        case "av1": return "AV1"
        case "vp9": return "VP9"
        case "vp8": return "VP8"
        case "mpeg4": return "MPEG-4"
        case "mpeg2video": return "MPEG-2"
        case nil, "": return ""
        case let x?: return x.uppercased()
        }
    }

    func tech(stream: HGStream?) async -> PlayerTech {
        var t = PlayerTech()
        let size = presentationSize
        if size.width > 0 { t.resolution = PlayerTech.resolutionLabel(size) }
        let codec = Self.codecLabel(getStringProperty("video-codec"))
        if !codec.isEmpty { t.codec = codec }
        if let fps = getDoubleProperty("estimated-vf-fps") ?? getDoubleProperty("container-fps"), fps > 0 {
            t.fps = Int(fps.rounded())
        }
        let primaries = getStringProperty("video-params/primaries")?.lowercased() ?? ""
        let gamma = getStringProperty("video-params/gamma")?.lowercased() ?? ""
        let sigPeak = getDoubleProperty("video-params/sig-peak") ?? 1.0
        if primaries.contains("bt.2020") || gamma.contains("pq") || gamma.contains("hlg") || sigPeak > 1.5 {
            t.hdr = true
            t.bitDepth = 10
        }
        if let br = getDoubleProperty("video-bitrate"), br > 0 {
            t.bitrate = br / 1_000_000
        } else if let b = stream?.bitrate, b > 0 {
            t.bitrate = Double(b) / 1_000_000
        } else if let s = stream?.size, s > 0, duration > 0 {
            t.bitrate = Double(s) * 8 / duration / 1_000_000
        }
        let audioCodec = getStringProperty("audio-codec-name") ?? ""
        let channels = getIntProperty("audio-params/channel-count") ?? 0
        if !audioCodec.isEmpty {
            t.audio = PlayerTech.audioName(codec: audioCodec, channels: channels)
        }
        t.engine = kind.short
        return t
    }

    var avPlayer: AVPlayer? { nil }
    var pipIdentity: ObjectIdentifier? { nil }
    func makePiPController() -> AVPictureInPictureController? { nil }
    func setPiPActive(_ active: Bool) {}

    var routeLabel: String {
        let hw = getStringProperty("hwdec-current") ?? "sw"
        return "MPV · \(hw)"
    }
}
