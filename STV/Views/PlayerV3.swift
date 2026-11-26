import SwiftUI
import AVFoundation

/// Player design v3 (player-design-v3/SPEC_ZH.md): monochrome white chrome, brand blue only for the current
/// episode, the scrub delta and a non-1× rate.

// MARK: Glass

struct PlayerGlass: ViewModifier {
    var corner: CGFloat
    var strong = false
    func body(content: Content) -> some View {
        content
            .background {
                RoundedRectangle(cornerRadius: corner, style: .continuous).fill(.ultraThinMaterial)
                    .overlay(RoundedRectangle(cornerRadius: corner, style: .continuous).fill(strong ? Color(red: 28/255, green: 30/255, blue: 36/255).opacity(0.84) : Color(white: 0.09).opacity(0.45)))
            }
            .overlay { RoundedRectangle(cornerRadius: corner, style: .continuous).strokeBorder(.white.opacity(strong ? 0.12 : 0.14), lineWidth: strong ? 1 : 0.5) }
            .shadow(color: .black.opacity(strong ? 0.5 : 0), radius: 25, y: 20)
            .environment(\.colorScheme, .dark)
    }
}

extension View {
    func playerGlass(_ corner: CGFloat, strong: Bool = false) -> some View { modifier(PlayerGlass(corner: corner, strong: strong)) }
}

// MARK: Tech strip

/// Spec `.tech`: one small muted line, not a collection of badges. Player stays dark in both themes.
struct PlayerTechStrip: View {
    let tech: PlayerTech
    var landscape = false
    var body: some View {
        let parts = [tech.resolution, tech.hdr ? "HDR" : "SDR", tech.codec, tech.audio,
                     tech.fps > 0 ? "\(tech.fps) fps" : ""] .filter { !$0.isEmpty }
        HStack(spacing: 6) {
            Text(parts.joined(separator: " · "))
            if !landscape && !tech.engine.isEmpty { Text(tech.engine).accessibilityIdentifier("player.engine").opacity(0.7) }
            if !landscape && tech.network > 0 { Text(PlayerTech.speed(tech.network)).monospacedDigit() }
        }
        .font(.system(size: 11, weight: .medium)).tracking(0.3).foregroundStyle(.white.opacity(0.55))
        .lineLimit(1).fixedSize().shadow(color: .black.opacity(0.4), radius: 2)
        .accessibilityElement(children: .combine).accessibilityIdentifier("player.tech")
    }
}

// MARK: Centre controls

/// ⟲10 · ▶ (76 pt glass, the outer ring doubles as buffer / loading progress) · ⟳10.
struct PlayerCenterControls: View {
    let co: PlaybackCoordinator
    let onInteract: () -> Void
    var spacing: CGFloat = 44
    @State private var spin = false

    var body: some View {
        let loading = co.readyIndex == nil || (co.waitingForMedia && !co.paused)
        let buffered = co.duration > 0 ? min(1, co.bufferedUntil / co.duration) : 0
        HStack(spacing: spacing) {
            Button { co.seek(by: -10); onInteract() } label: {
                Image(systemName: "gobackward.10").font(.system(size: 29, weight: .regular)).frame(width: 44, height: 44).opacity(0.94)
            }.accessibilityLabel("快退10秒").accessibilityIdentifier("player.back10")
            Button { co.togglePause(); onInteract() } label: {
                ZStack {
                    Circle().fill(.ultraThinMaterial).environment(\.colorScheme, .dark)
                    Circle().fill(Color(red: 20/255, green: 20/255, blue: 24/255).opacity(0.46))
                    Circle().strokeBorder(.white.opacity(0.18), lineWidth: 1)
                    if loading {
                        Circle().trim(from: 0, to: 0.28).stroke(.white, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                            .rotationEffect(.degrees(spin ? 360 : 0))
                            .animation(.linear(duration: 0.9).repeatForever(autoreverses: false), value: spin)
                            .onAppear { spin = true }.onDisappear { spin = false }
                    } else {
                        Circle().trim(from: 0, to: buffered).stroke(.white.opacity(0.75), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                    }
                    Image(systemName: co.paused ? "play.fill" : "pause.fill").font(.system(size: 26, weight: .regular))
                        .offset(x: co.paused ? 2 : 0)
                        .compatSymbolReplace()
                }
                .frame(width: 68, height: 68)
            }
            .accessibilityLabel(co.paused ? "播放" : "暂停").accessibilityIdentifier("player.playPause")
            Button { co.seek(by: 10); onInteract() } label: {
                Image(systemName: "goforward.10").font(.system(size: 29, weight: .regular)).frame(width: 44, height: 44).opacity(0.94)
            }.accessibilityLabel("快进10秒").accessibilityIdentifier("player.fwd10")
        }
        .buttonStyle(PressScale(scale: 0.92))
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.35), radius: 6)
    }
}

// MARK: Caption

struct PlayerMetaRow: View {
    let source: String?
    let line: String
    let lineStrong: String
    let rest: String
    var showSkip = false
    var onSkip: () -> Void = {}

    var body: some View {
        HStack(spacing: 8) {
            if let source {
                Text(source).font(.system(size: 11, weight: .semibold))
                    .padding(.horizontal, 7).frame(height: 20)
                    .background(.white.opacity(0.16), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            }
            (Text(line).foregroundColor(.white.opacity(0.72)) + Text(lineStrong).foregroundColor(.white).fontWeight(.semibold) + Text(rest).foregroundColor(.white.opacity(0.72)))
                .font(.system(size: 13)).lineLimit(1)
            Spacer(minLength: 8)
            if showSkip {
                Button(action: onSkip) {
                    HStack(spacing: 4) { Text("跳过片头"); Image(systemName: "play.fill").font(.system(size: 8)) }
                        .font(.system(size: 12, weight: .semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 11).frame(height: 28)
                        .background(Color(red: 20/255, green: 20/255, blue: 24/255).opacity(0.5), in: Capsule())
                        .overlay { Capsule().strokeBorder(.white.opacity(0.2), lineWidth: 1) }
                        .contentShape(Capsule())
                }.buttonStyle(PressScale()).accessibilityIdentifier("player.skipIntro")
            }
        }
        .foregroundStyle(.white)
    }
}

// MARK: Progress

/// v3 bar: full width 3 pt (played white, buffered white 40 %, track white 22 %), 4×16 bar knob; dragging grows
/// it to 8 pt with a 20 pt round knob and a thumbnail + time bubble. Time row underneath: elapsed · −remaining.
struct PlayerTimelineV3: View {
    let co: PlaybackCoordinator
    @Binding var scrubbing: Bool
    var onInteract: () -> Void = {}
    /// Max width of the bubble positioning area (screen coordinates).
    var landscape = false
    /// Portrait redesign: times sit inline either side of the track and the 全屏 button closes the row.
    var onFullscreen: (() -> Void)? = nil
    /// Landscape redesign: times inline but no 全屏 button (exit lives in the top bar).
    var inline = false

    var body: some View {
        if inline || onFullscreen != nil {
            let t = co.scrubPreview ?? co.time
            HStack(spacing: 10) {
                Text(Fmt.clock(Int(t))).accessibilityIdentifier("player.time")
                PlayerProgressBar(co: co, scrubbing: $scrubbing, onInteract: onInteract)
                Text("−" + Fmt.clock(Int(max(0, co.duration - t)))).accessibilityIdentifier("player.remaining")
                if let onFullscreen { Button(action: onFullscreen) {
                    Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white).frame(width: 30, height: 30).contentShape(Rectangle())
                }
                .buttonStyle(PressScale()).padding(.leading, 2)
                .accessibilityLabel("横屏全屏").accessibilityIdentifier("player.fullscreen") }
            }
            .font(.system(size: 12, weight: .medium).monospacedDigit())
            .foregroundStyle(.white.opacity(0.8))
        } else {
            stacked
        }
    }

    private var stacked: some View {
        VStack(spacing: 2) {
            PlayerProgressBar(co: co, scrubbing: $scrubbing, onInteract: onInteract)
            HStack {
                let t = co.scrubPreview ?? co.time
                Text(Fmt.clock(Int(t))).accessibilityIdentifier("player.time")
                Spacer()
                Text("−" + Fmt.clock(Int(max(0, co.duration - t)))).accessibilityIdentifier("player.remaining")
            }
            .font(.system(size: 12, weight: .medium).monospacedDigit())
            .foregroundStyle(.white.opacity(0.85))
        }
    }
}

// MARK: Icon row

enum PlayerPanel: String, Identifiable {
    case episodes, quality, rates, audio, subtitles, danmaku, more
    var id: Self { self }
}

/// One 44 pt line icon (white 16 % rounded backing while its popover is open).
struct PlayerRowIcon<Label: View>: View {
    let id: String
    let a11y: String
    var active = false
    let action: () -> Void
    @ViewBuilder var label: Label

    var body: some View {
        Button(action: action) {
            label.foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(active ? Color.white.opacity(0.16) : .clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(a11y).accessibilityIdentifier(id)
    }
}

/// Redesign `.pact`: 23 pt icon over an 11 pt caption that shows the current value (`倍速 1.0×`).
struct PlayerActionButton<Icon: View>: View {
    let id: String
    let a11y: String
    let title: String
    var value: String? = nil
    var active = false
    var accent = false
    var dimmed = false
    let action: () -> Void
    @ViewBuilder var icon: Icon

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                icon.font(.system(size: 20, weight: .regular)).frame(height: 23)
                    .foregroundStyle(accent ? STVTheme.accent : .white)
                (Text(title).foregroundColor(.white.opacity(0.72))
                 + Text(value.map { " " + $0 } ?? "").fontWeight(.semibold).foregroundColor(accent ? STVTheme.accent : .white))
                    .font(.system(size: 11)).lineLimit(1).fixedSize()
            }
            .opacity(dimmed ? 0.45 : 1)
            .frame(maxWidth: .infinity).padding(.top, 7).padding(.bottom, 6)
            .background(active ? Color.white.opacity(0.15) : .clear, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .contentShape(Rectangle())
            .animation(DS.select, value: active)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(a11y).accessibilityIdentifier(id)
    }
}

/// Redesign `.pchip`: 34 pt glass capsule, icon + label + current value; `on` turns it accent.
struct PlayerChip: View {
    let id: String
    let a11y: String
    let symbol: String
    let title: String
    var value: String? = nil
    var on = false
    var dimmed = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: symbol).font(.system(size: 15, weight: .regular))
                    .foregroundStyle(on ? STVTheme.accent : .white)
                (Text(title).foregroundColor(on ? STVTheme.accent : .white.opacity(0.8))
                 + Text(value.map { " " + $0 } ?? "").fontWeight(.semibold).foregroundColor(on ? STVTheme.accent : .white))
                    .font(.system(size: 13)).lineLimit(1).fixedSize()
            }
            .padding(.horizontal, 13).frame(height: 34)
            .background {
                Capsule().fill(.ultraThinMaterial)
                    .overlay(Capsule().fill(on ? STVTheme.accent.opacity(0.16) : Color(red: 20/255, green: 20/255, blue: 24/255).opacity(0.46)))
            }
            .overlay { Capsule().strokeBorder(on ? STVTheme.accentLine : .white.opacity(0.14), lineWidth: 1) }
            .environment(\.colorScheme, .dark)
            .opacity(dimmed ? 0.45 : 1)
            .contentShape(Capsule())
            .animation(DS.select, value: on)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(a11y).accessibilityIdentifier(id)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

struct OutlinedTag: View {
    let text: String
    var body: some View {
        Text(text).font(.system(size: 11, weight: .bold)).lineLimit(1).fixedSize()
            .padding(.horizontal, 5).padding(.vertical, 2)
            .overlay { RoundedRectangle(cornerRadius: 4).strokeBorder(.white, lineWidth: 1.2) }
    }
}

struct DanmakuGlyph: View {
    let on: Bool
    var body: some View {
        Text("弹").font(.system(size: 13, weight: .bold))
            .foregroundStyle(on ? .black : .white)
            .frame(width: 22, height: 22)
            .background(on ? Color.white : .clear, in: RoundedRectangle(cornerRadius: 5))
            .overlay { if !on { RoundedRectangle(cornerRadius: 5).strokeBorder(.white, lineWidth: 1.2) } }
    }
}

// MARK: Popovers

struct PlayerPopoverRow: View {
    let title: String
    var detail: String = ""
    var subtitle: String? = nil
    let selected: Bool
    var id: String? = nil
    var rowHeight: CGFloat = 44
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            // Redesign `.prow`: 46 pt, value on the right; the current row is accent-soft with an accent ✓.
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.system(size: 15, weight: selected ? .semibold : .regular))
                    if let subtitle { Text(subtitle).font(.system(size: 12)).foregroundStyle(.white.opacity(0.55)) }
                }
                Spacer(minLength: 8)
                if !detail.isEmpty {
                    Text(detail).font(.system(size: 12, weight: selected ? .semibold : .regular).monospacedDigit())
                        .foregroundStyle(selected ? STVTheme.accent.opacity(0.8) : .white.opacity(0.55)).lineLimit(1)
                }
                if selected { Image(systemName: "checkmark").font(.system(size: 14, weight: .semibold)).foregroundStyle(STVTheme.accent) }
            }
            .foregroundStyle(selected ? STVTheme.accent : .white)
            .padding(.horizontal, 12).frame(minHeight: subtitle == nil ? (rowHeight == 44 ? 46 : rowHeight) : 54)
            .background(selected ? STVTheme.accentSoft : .clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .animation(DS.select, value: selected)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id ?? "player.option")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct PlayerSectionTitle: View {
    let text: String
    /// `.psheet h3`: the sheet's own title (17 pt bold) rather than a group label (`.lbl`, 12 pt).
    var heading = false
    var trailing: String? = nil
    var body: some View {
        if heading {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(text).font(.system(size: 17, weight: .bold)).foregroundStyle(.white)
                Spacer(minLength: 8)
                if let trailing { Text(trailing).font(.system(size: 12)).foregroundStyle(.white.opacity(0.6)) }
            }
            .padding(.horizontal, 5).padding(.top, 7).padding(.bottom, 6)
        } else {
            Text(text).font(.system(size: 12)).foregroundStyle(.white.opacity(0.55)).padding(.horizontal, 5).padding(.top, 14).padding(.bottom, 8)
        }
    }
}

struct SpeedPopover: View {
    let current: Double
    var landscape = false
    /// Active core's ceiling (AetherEngine clamps video to 2×; AVPlayer 3×).
    var maxRate: Double = 3
    let onPick: (Double) -> Void
    static let portraitSteps: [Double] = [0.5, 0.75, 1, 1.25, 1.5, 2, 3]

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            if !landscape { PlayerSectionTitle(text: "倍速", heading: true, trailing: "长按画面临时 2×") }
            ForEach((landscape ? Array(Self.portraitSteps.prefix(6)) : Self.portraitSteps).filter { $0 <= maxRate + 0.001 }, id: \.self) { r in
                PlayerPopoverRow(title: Self.label(r), selected: abs(r - current) < 0.01, id: "player.rate.\(r)", rowHeight: landscape ? 37 : 44) { onPick(r) }
            }
        }
        .padding(.horizontal, 11).padding(.top, 9).padding(.bottom, 8)
    }

    static func label(_ r: Double) -> String {
        let s = r == Double(Int(r)) ? "\(Int(r))×" : (String(format: "%g", r) + "×")
        return r == 1 ? s + " 正常速度" : s
    }
}

struct QualityPopover: View {
    let streams: [HGStream]
    let chosen: HGStream?
    let sources: [(title: String, detail: String, current: Bool, action: () -> Void)]
    var streamsTitle = "画质"
    let onPick: (HGStream) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            PlayerSectionTitle(text: streamsTitle, heading: true)
            ForEach(streams) { s in
                PlayerPopoverRow(title: s.quality ?? "自动", detail: Self.detail(s), selected: s == chosen, id: "player.stream") { onPick(s) }
            }
            if !sources.isEmpty {
                PlayerSectionTitle(text: "播放源")
                ForEach(sources.indices, id: \.self) { i in
                    let s = sources[i]
                    PlayerPopoverRow(title: s.title, detail: s.detail, selected: s.current, id: "player.source") { s.action() }
                }
            }
        }
        .padding(.horizontal, 11).padding(.top, 9).padding(.bottom, 8)
    }

    static func detail(_ s: HGStream) -> String {
        var parts: [String] = []
        if let w = s.width, let h = s.height, w > 0, h > 0 { parts.append("\(w)×\(h)") }
        else if let c = s.codec, !c.isEmpty { parts.append(c.uppercased() == "H264" ? "H.264" : c) }
        if let b = s.bitrate, b > 0 { parts.append(String(format: "%.1fMbps", Double(b) / 1_000_000)) }
        return parts.joined(separator: " · ")
    }
}

struct TrackPopover: View {
    let title: String
    let options: [MediaTrack]
    let selected: Int?
    let allowsOff: Bool
    let emptyText: String
    /// Track id; nil = off.
    let onPick: (Int?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            PlayerSectionTitle(text: title, heading: true)
            if options.isEmpty {
                Text(emptyText).font(.system(size: 14)).foregroundStyle(.white.opacity(0.7)).padding(.horizontal, 12).frame(minHeight: 44)
            } else {
                if allowsOff { PlayerPopoverRow(title: "关闭", selected: selected == nil) { onPick(nil) } }
                ForEach(options) { o in
                    PlayerPopoverRow(title: o.title, selected: o.id == selected) { onPick(o.id) }
                }
            }
        }
        .padding(.horizontal, 11).padding(.top, 9).padding(.bottom, 8)
    }
}

/// Short drama: season chips → 30-episode group chips → 6-column grid. Long video: a list (ref_2).
struct EpisodesPopover: View {
    let episodes: [HGEpisode]
    let current: Int
    let seasons: [(id: String, title: String)]
    let currentSeason: String?
    let listStyle: Bool
    var dateLine: String? = nil
    let onPick: (Int) -> Void
    var onSeason: (String) -> Void = { _ in }
    @Environment(\.layoutMetrics) private var layout
    @State private var group = 0

    var body: some View {
        let ranges = EpisodeGroups.ranges(total: episodes.count)
        VStack(alignment: .leading, spacing: 12) {
            if episodes.isEmpty {
                Text("分集还在加载").font(.system(size: 14)).foregroundStyle(.white.opacity(0.7)).frame(minHeight: 44)
            } else if !listStyle {
                HStack(alignment: .firstTextBaseline) {
                    Text("选集").font(.system(size: 17, weight: .bold))
                    Spacer()
                    Text("共 \(episodes.count) 集 · 更新至 \(episodes.last?.ep ?? episodes.count) 集").font(.system(size: 12)).foregroundStyle(.white.opacity(0.6))
                }
                if seasons.count > 1 {
                    chips(seasons.map(\.title), selected: seasons.firstIndex { $0.id == currentSeason }, height: 32, font: 14, id: "player.season") { i in
                        onSeason(seasons[i].id)
                    }
                }
                if ranges.count > 1 {
                    chips(ranges.map { EpisodeGroups.label($0, episodes: episodes) }, selected: group, height: 28, font: 13, id: "player.epgroup") { i in
                        withAnimation(.compatSnappy) { group = i }
                    }
                }
                let r = ranges.indices.contains(group) ? ranges[group] : EpisodeRange(0, max(0, episodes.count - 1))
                ScrollView {
                    LazyVGrid(columns: layout.episodeGridItems(spacing: 8), spacing: 8) {
                        ForEach(Array(r.start...min(r.end, episodes.count - 1)), id: \.self) { i in
                            let on = i == current
                            Button { onPick(i) } label: {
                                Text("\(episodes[i].ep)").font(.system(size: 15, weight: .semibold).monospacedDigit())
                                    .frame(maxWidth: .infinity, minHeight: 44)
                                    .background(on ? STVTheme.accentSoft : STVTheme.glass, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    .overlay { if on { RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(STVTheme.accentLine, lineWidth: 1) } }
                                    .overlay(alignment: .topTrailing) { if on { Circle().fill(STVTheme.accent).frame(width: 5, height: 5).padding(6) } }
                                    .foregroundStyle(on ? STVTheme.accent : .white)
                            }
                            .buttonStyle(PressScale())
                            .accessibilityIdentifier("player.ep.\(episodes[i].ep)")
                        }
                    }
                }
                .scrollIndicators(.hidden)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(spacing: 1) {
                            ForEach(episodes.indices, id: \.self) { i in
                                let e = episodes[i]
                                PlayerPopoverRow(title: "S1:E\(e.ep)  " + (e.title.isEmpty ? "第\(e.ep)集" : e.title),
                                                 detail: e.duration > 0 ? "\(max(1, e.duration / 60)) 分钟" : "",
                                                 subtitle: dateLine, selected: i == current, id: "player.ep.\(e.ep)") { onPick(i) }
                                    .id(i)
                            }
                        }
                    }
                    .scrollIndicators(.hidden)
                    .onAppear { proxy.scrollTo(current, anchor: .center) }
                }
            }
        }
        .foregroundStyle(.white)
        .padding(listStyle ? 9 : 16)
        .onAppear { if let i = ranges.firstIndex(where: { current >= $0.start && current <= $0.end }) { group = i } }
    }

    private func chips(_ titles: [String], selected: Int?, height: CGFloat, font: CGFloat, id: String, onTap: @escaping (Int) -> Void) -> some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(titles.indices, id: \.self) { i in
                        let on = i == selected
                        Button { onTap(i) } label: {
                            Text(titles[i]).font(.system(size: font, weight: on ? .semibold : .regular))
                                .padding(.horizontal, height > 30 ? 14 : 12).frame(height: height)
                                .background(on ? STVTheme.thumb : STVTheme.glass, in: Capsule())
                                .foregroundStyle(on ? STVTheme.text : STVTheme.secondary)
                        }
                        .buttonStyle(PressScale()).id(i)
                        .accessibilityIdentifier("\(id).\(i)")
                    }
                }
            }
            .onAppear { if let selected { proxy.scrollTo(selected, anchor: .leading) } }
        }
    }
}

/// Custom Liquid Glass `更多设置` panel (replaces `SwiftUI.Menu` so 0.25 s playback ticks never trigger
/// `UIContextMenuInteraction.updateVisibleMenu` backdrop-recalculation flicker on iOS 26/27; synced 1:1 with Android `MoreSettingsPanel`).
struct MoreSettingsPopover: View {
    let fillScreen: Bool
    let enhanceLevel: Int
    let sdrToHdr: Bool
    let isNativeHdr: Bool
    let sleepMode: Int
    let autoNext: Bool
    let autoNextDrama: Bool
    let showAutoNextDrama: Bool
    let showTech: Bool
    let muted: Bool
    let showDanmaku: Bool
    let danmakuSummary: String
    let prebufferInfo: String?
    let onClose: () -> Void
    let onOpenDanmaku: () -> Void
    let onFillScreen: (Bool) -> Void
    let onEnhance: (Int) -> Void
    let onSdrToHdr: (Bool) -> Void
    let onSleep: (Int) -> Void
    let onAutoNext: (Bool) -> Void
    let onAutoNextDrama: (Bool) -> Void
    let onToggleTech: () -> Void
    let onToggleMute: () -> Void
    let onCopyTitle: () -> Void

    private var sleepSubtitle: String? {
        switch sleepMode {
        case -1: return "定时：播完本集"
        case let m where m > 0: return "定时：\(m) 分钟"
        default: return prebufferInfo
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("更多设置")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(.white)
                    if let sub = sleepSubtitle, !sub.isEmpty {
                        Text(sub)
                            .font(.system(size: 11))
                            .foregroundStyle(.white.opacity(0.55))
                    }
                }
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white.opacity(0.78))
                        .frame(width: 28, height: 28)
                        .background(Color.white.opacity(0.10), in: Circle())
                }
                .buttonStyle(PressScale())
                .accessibilityLabel("关闭")
            }

            if showDanmaku {
                Button(action: onOpenDanmaku) {
                    HStack(spacing: 10) {
                        Image(systemName: "slider.horizontal.3")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(.white.opacity(0.86))
                        Text("弹幕设置")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(.white)
                        Spacer()
                        Text(danmakuSummary)
                            .font(.system(size: 12))
                            .foregroundStyle(.white.opacity(0.55))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.50))
                    }
                    .padding(.horizontal, 12)
                    .frame(height: 44)
                    .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.white.opacity(0.12), lineWidth: 0.8))
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressScale())
            }

            optionSection("画面比例", items: [("适应", false), ("铺满", true)], selected: fillScreen, onSelect: onFillScreen)

            optionSection("画质增强", items: [("关闭", 0), ("标准", 1), ("强效", 2)], selected: enhanceLevel, onSelect: onEnhance)

            if isNativeHdr {
                VStack(alignment: .leading, spacing: 6) {
                    Text("SDR 转 HDR · 当前片源已是原生 HDR")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.42))
                    HStack(spacing: 8) {
                        Text("原生 HDR")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(STVTheme.accent)
                            .padding(.horizontal, 13)
                            .padding(.vertical, 7)
                            .background(STVTheme.accent.opacity(0.16), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(STVTheme.accent.opacity(0.45), lineWidth: 0.8))
                        Spacer()
                    }
                    .opacity(0.55)
                }
            } else {
                optionSection("SDR 转 HDR (逆色调映射)", items: [("关闭", false), ("开启", true)], selected: sdrToHdr, onSelect: onSdrToHdr)
            }

            optionSection(
                "定时关闭",
                items: [("关", 0), ("本集完", -1), ("15分", 15), ("30分", 30), ("60分", 60)],
                selected: sleepMode,
                onSelect: onSleep
            )

            optionSection("自动连播", items: [("开启", true), ("关闭", false)], selected: autoNext, onSelect: onAutoNext)

            if showAutoNextDrama {
                optionSection("播完连刷推荐", items: [("开启", true), ("关闭", false)], selected: autoNextDrama, onSelect: onAutoNextDrama)
            }

            // Quick utility pills (技术信息 / 静音 / 复制剧名)
            VStack(alignment: .leading, spacing: 6) {
                Text("快捷操作")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white.opacity(0.60))
                HStack(spacing: 8) {
                    quickPill(title: "技术信息", symbol: "info.circle", active: showTech, action: onToggleTech)
                    quickPill(title: muted ? "已静音" : "静音", symbol: muted ? "speaker.slash.fill" : "speaker.wave.2", active: muted, action: onToggleMute)
                    quickPill(title: "复制剧名", symbol: "doc.on.doc", active: false, action: onCopyTitle)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 16)
        .environment(\.colorScheme, .dark)
    }

    private func optionSection<T: Equatable>(
        _ label: String,
        items: [(String, T)],
        selected: T,
        onSelect: @escaping (T) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.white.opacity(0.60))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(items.indices, id: \.self) { idx in
                        let pair = items[idx]
                        let on = pair.1 == selected
                        Button {
                            if !on {
                                UISelectionFeedbackGenerator().selectionChanged()
                                onSelect(pair.1)
                            }
                        } label: {
                            Text(pair.0)
                                .font(.system(size: 12, weight: on ? .semibold : .regular))
                                .foregroundStyle(on ? STVTheme.accent : .white.opacity(0.86))
                                .padding(.horizontal, 13)
                                .padding(.vertical, 7)
                                .background(
                                    on ? STVTheme.accent.opacity(0.18) : Color.white.opacity(0.08),
                                    in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .strokeBorder(on ? STVTheme.accent.opacity(0.55) : Color.white.opacity(0.12), lineWidth: 0.8)
                                )
                        }
                        .buttonStyle(PressScale())
                    }
                }
            }
        }
    }

    private func quickPill(title: String, symbol: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: symbol)
                    .font(.system(size: 12, weight: .semibold))
                Text(title)
                    .font(.system(size: 12, weight: active ? .semibold : .regular))
                    .lineLimit(1)
            }
            .foregroundStyle(active ? STVTheme.accent : .white.opacity(0.86))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(
                active ? STVTheme.accent.opacity(0.18) : Color.white.opacity(0.08),
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(active ? STVTheme.accent.opacity(0.55) : Color.white.opacity(0.12), lineWidth: 0.8)
            )
        }
        .buttonStyle(PressScale())
    }
}
