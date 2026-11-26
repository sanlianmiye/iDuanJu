import SwiftUI
import AVFoundation
import UIKit

/// Persisted danmaku preferences synced across iOS & Android:
/// area (1/4 · 1/3 · 半屏 · 3/4 · 全屏), opacity, fontScale, 5-step speed, avoidSubtitle, merge, likesPriority, blockColor, blockWords.
@MainActor
final class DanmakuSettings: ObservableObject {
    static let shared = DanmakuSettings()

    enum Speed: String, CaseIterable, Identifiable {
        case verySlow, slow, medium, fast, veryFast
        var id: String { rawValue }
        var label: String {
            switch self {
            case .verySlow: "极慢"
            case .slow: "较慢"
            case .medium: "适中"
            case .fast: "较快"
            case .veryFast: "极快"
            }
        }
        /// Seconds to cross the screen.
        var duration: Double {
            switch self {
            case .verySlow: 12.5
            case .slow: 10.5
            case .medium: 8.5
            case .fast: 6.5
            case .veryFast: 5.0
            }
        }
    }

    /// Legacy discrete size enum kept for backward compatibility.
    enum Size: String, CaseIterable, Identifiable {
        case small, medium, large
        var id: String { rawValue }
        var label: String { switch self { case .small: "小"; case .medium: "中"; case .large: "大" } }
        var scale: Double { switch self { case .small: 0.875; case .medium: 1.0; case .large: 1.1875 } }
    }

    /// How much of the screen (below the top bar / cutout) danmaku lanes may use.
    enum Area: String, CaseIterable, Identifiable {
        case quarter, third, half, most, full
        var id: String { rawValue }
        var label: String {
            switch self {
            case .quarter: "1/4"
            case .third: "1/3"
            case .half: "半屏"
            case .most: "3/4"
            case .full: "全屏"
            }
        }
        var areaLabel: String {
            switch self {
            case .quarter: "1/4屏"
            case .third: "1/3屏"
            case .half: "半屏"
            case .most: "3/4屏"
            case .full: "全屏"
            }
        }
        var fraction: CGFloat {
            switch self {
            case .quarter: 0.25
            case .third: 0.34
            case .half: 0.50
            case .most: 0.75
            case .full: 1.00
            }
        }
    }

    static let baseFontPoints: CGFloat = 16

    @Published var enabled: Bool { didSet { save() } }
    @Published var area: Area { didSet { save() } }
    @Published var avoidSubtitle: Bool { didSet { save() } }
    @Published var merge: Bool { didSet { save() } }
    @Published var likesPriority: Bool { didSet { save() } }
    @Published var blockColor: Bool { didSet { save() } }
    @Published var opacity: Double { didSet { save() } }
    @Published var fontScale: Double { didSet { save() } }
    @Published var speed: Speed { didSet { save() } }
    @Published var blockWords: [String] { didSet { save() } }
    /// Live status of the current episode (not persisted).
    @Published var status = ""

    var fontPoints: CGFloat { Self.baseFontPoints * CGFloat(min(1.40, max(0.65, fontScale))) }
    var summary: String { "\(area.areaLabel) · \(speed.label)" }

    private var suppressSave = false
    private let d = UserDefaults.standard

    private init() {
        enabled = d.object(forKey: "stv.dm.enabled") as? Bool ?? true
        area = Area(rawValue: d.string(forKey: "stv.dm.area") ?? "") ?? .third
        avoidSubtitle = d.object(forKey: "stv.dm.avoidSubtitle") as? Bool ?? true
        merge = d.object(forKey: "stv.dm.merge") as? Bool ?? true
        likesPriority = d.object(forKey: "stv.dm.likes") as? Bool ?? true
        blockColor = d.object(forKey: "stv.dm.blockColor") as? Bool ?? false
        opacity = min(1.0, max(0.20, d.object(forKey: "stv.dm.opacity") as? Double ?? 0.85))
        if let savedScale = d.object(forKey: "stv.dm.fontScale") as? Double {
            fontScale = min(1.40, max(0.65, savedScale))
        } else if let legacySize = Size(rawValue: d.string(forKey: "stv.dm.size") ?? "") {
            fontScale = legacySize.scale
        } else {
            fontScale = 1.0
        }
        speed = Speed(rawValue: d.string(forKey: "stv.dm.speed") ?? "") ?? .medium
        let rawWords = d.string(forKey: "stv.dm.blockWords") ?? ""
        blockWords = Self.parseWords(rawWords)
    }

    private static func parseWords(_ raw: String) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for line in raw.components(separatedBy: .newlines) {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            let key = trimmed.lowercased()
            if seen.insert(key).inserted { out.append(trimmed) }
        }
        return out
    }

    @discardableResult
    func addBlockWord(_ word: String) -> Bool {
        let trimmed = word.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        guard !blockWords.contains(where: { $0.caseInsensitiveCompare(trimmed) == .orderedSame }) else { return false }
        blockWords.append(trimmed)
        return true
    }

    func removeBlockWord(_ word: String) {
        blockWords.removeAll { $0 == word }
    }

    func resetDefaults() {
        suppressSave = true
        area = .third
        avoidSubtitle = true
        merge = true
        likesPriority = true
        blockColor = false
        opacity = 0.85
        fontScale = 1.0
        speed = .medium
        blockWords = []
        suppressSave = false
        save()
    }

    private func save() {
        guard !suppressSave else { return }
        d.set(enabled, forKey: "stv.dm.enabled")
        d.set(area.rawValue, forKey: "stv.dm.area")
        d.set(avoidSubtitle, forKey: "stv.dm.avoidSubtitle")
        d.set(merge, forKey: "stv.dm.merge")
        d.set(likesPriority, forKey: "stv.dm.likes")
        d.set(blockColor, forKey: "stv.dm.blockColor")
        d.set(opacity, forKey: "stv.dm.opacity")
        d.set(fontScale, forKey: "stv.dm.fontScale")
        d.set(speed.rawValue, forKey: "stv.dm.speed")
        if blockWords.isEmpty {
            d.removeObject(forKey: "stv.dm.blockWords")
        } else {
            d.set(blockWords.joined(separator: "\n"), forKey: "stv.dm.blockWords")
        }
    }
}

/// Screen-relative danmaku lane region (synced with Android `DanmakuOverlay`).
enum DanmakuRegion {
    struct Region: Equatable { var top: CGFloat; var rows: Int; var bottomLimit: CGFloat }

    static func compute(
        screen: CGSize,
        safeTop: CGFloat,
        safeBottom: CGFloat,
        landscape: Bool,
        showTech: Bool,
        area: CGFloat,
        laneHeight: CGFloat,
        avoidSubtitle: Bool = true,
        maxRows: Int = 40
    ) -> Region {
        let lh = max(1, laneHeight)
        let top: CGFloat = landscape ? 16 : (safeTop + 48)
        let availableH = max(0, screen.height - top)
        let subtitleGuard: CGFloat = avoidSubtitle ? max(screen.height * 0.18, 72) : 0
        let baseBottom: CGFloat = min(landscape ? 96 : 210, screen.height * 0.08)
        let bottomReserve = avoidSubtitle ? max(subtitleGuard, baseBottom) : baseBottom
        let usableH = max(lh, availableH - bottomReserve)
        let capRows = max(1, min(maxRows, Int(usableH / lh)))
        let targetH = max(lh, min(usableH, availableH * area))
        let rows = max(1, min(capRows, Int(targetH / lh)))
        let bottomLimit = max(top + lh, screen.height - bottomReserve)
        return Region(top: top, rows: rows, bottomLimit: bottomLimit)
    }
}

/// Lane scheduler (port of hongguo-open-player prioritizeDanmakuItems): collision-free tracks,
/// higher-liked comments evict lower-liked blockers when every lane is busy.
enum DanmakuLayout {
    struct Placed: Identifiable {
        let item: HGDanmaku
        let text: String
        let badge: String
        let lane: Int
        let width: CGFloat
        var id: String { item.id }
    }

    static func estimateWidth(_ s: String, font: CGFloat) -> CGFloat {
        var w: CGFloat = 8
        for u in s.unicodeScalars {
            if u.value >= 0x20 && u.value <= 0x7E { w += font * 0.56 }
            else if u.properties.isEmojiPresentation || u.value >= 0x1F000 { w += font * 1.15 }
            else if u.value == 0xFE0F || u.value == 0x200D || (0x20E3 == u.value) { continue }
            else { w += font }
        }
        return w
    }

    static func requiredGap(_ wp: CGFloat, _ wn: CGFloat, screen: CGFloat, duration: Double) -> Double {
        max(duration * Double(wp + 12) / Double(screen + wp), duration * Double(wn + 12) / Double(screen + wn))
    }

    static func layout(_ items: [HGDanmaku], rows: Int, screen: CGFloat, font: CGFloat, duration: Double, likesPriority: Bool) -> [Placed] {
        let rows = max(1, rows)
        struct C { let item: HGDanmaku; let text: String; let badge: String; let width: CGFloat; let idx: Int }
        var cs: [C] = items.enumerated().map { i, it in
            let text = DanmakuText.display(it.text)
            let badge = it.count > 1 ? "×\(it.count)" : ""
            let w = estimateWidth(text, font: font) + (badge.isEmpty ? 0 : estimateWidth(badge, font: font * 0.8) + 6)
            return C(item: it, text: text, badge: badge, width: w, idx: i)
        }
        cs.sort { a, b in
            if a.item.time != b.item.time { return a.item.time < b.item.time }
            if a.item.likes != b.item.likes { return a.item.likes > b.item.likes }
            return a.idx < b.idx
        }
        var tracks = [[C]](repeating: [], count: rows)
        for c in cs {
            var free = -1
            var bestSlack = Double.infinity
            for k in 0..<rows {
                guard let prev = tracks[k].last else { free = k; break }
                let gap = requiredGap(prev.width, c.width, screen: screen, duration: duration)
                if c.item.time >= prev.item.time + gap {
                    let slack = c.item.time - (prev.item.time + gap)
                    if slack < bestSlack { bestSlack = slack; free = k }
                }
            }
            if free >= 0 { tracks[free].append(c); continue }
            guard likesPriority, c.item.likes + c.item.count > 1 else { continue }
            let score = c.item.likes + c.item.count - 1
            var best = -1, minScore = score, maxBlock = -Double.infinity
            for k in 0..<rows {
                guard let inc = tracks[k].last else { continue }
                let incScore = inc.item.likes + inc.item.count - 1
                if incScore >= score { continue }
                if tracks[k].count >= 2 {
                    let before = tracks[k][tracks[k].count - 2]
                    if c.item.time < before.item.time + requiredGap(before.width, c.width, screen: screen, duration: duration) { continue }
                }
                let block = inc.item.time + requiredGap(inc.width, c.width, screen: screen, duration: duration) - c.item.time
                if incScore < minScore || (incScore == minScore && block > maxBlock) { minScore = incScore; maxBlock = block; best = k }
            }
            if best >= 0 { tracks[best].removeLast(); tracks[best].append(c) }
        }
        var out: [Placed] = []
        for k in 0..<rows { for c in tracks[k] { out.append(Placed(item: c.item, text: c.text, badge: c.badge, lane: k, width: c.width)) } }
        return out.sorted { $0.item.time < $1.item.time }
    }
}

private struct DanmakuPreviewSample {
    let text: String
    let color: Color
    let phaseOffset: Double
}

private let previewSamples: [DanmakuPreviewSample] = [
    .init(text: "前方高能！名场面来了", color: .white, phaseOffset: 0.14),
    .init(text: "这段演技太绝了", color: .white, phaseOffset: 0.34),
    .init(text: "彩色弹幕效果预览", color: Color(red: 1.0, green: 0.784, blue: 0.341), phaseOffset: 0.52),
    .init(text: "节奏好紧凑根本停不下来", color: .white, phaseOffset: 0.70),
    .init(text: "字号与透明度实时预览", color: .white, phaseOffset: 0.86),
]

/// Scrolling bullet-comment layer synced to playback time, with live preview & area guide when `previewActive` is true.
struct DanmakuOverlay: View {
    let co: PlaybackCoordinator
    let seriesId: String
    let vid: String
    var landscape = false
    /// Screen-space y of lane 0 (the overlay itself covers the whole screen).
    var laneTop: CGFloat = 96
    /// Lane count already bounded by area / bottom controls / subtitle guard.
    var laneCount: Int = 4
    var previewActive: Bool = false

    @ObservedObject private var settings = DanmakuSettings.shared
    @State private var raw: [HGDanmaku] = []
    @State private var placed: [DanmakuLayout.Placed] = []
    @State private var parsedColors: [Color] = []
    @State private var key = ""

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            TimelineView(.animation(minimumInterval: 1.0 / 60, paused: !previewActive && (co.paused || placed.isEmpty))) { timeline in
                let nowDate = timeline.date
                Canvas { ctx, canvas in
                    draw(&ctx, canvas, nowDate: nowDate)
                }
            }
            .onAppear { relayout(size, layoutKey(size)) }
            .compatOnChange(of: layoutKey(size)) { k in relayout(size, k) }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .task(id: vid) { await load() }
    }

    private var font: CGFloat { settings.fontPoints }
    private var laneHeight: CGFloat { font * 1.55 }
    private var topInset: CGFloat { laneTop }
    private var rows: Int { max(1, laneCount) }
    static func laneHeight(_ s: DanmakuSettings) -> CGFloat { s.fontPoints * 1.55 }

    private func layoutKey(_ size: CGSize) -> String {
        "\(vid)|\(raw.count)|\(Int(size.width))|\(rows)|\(settings.speed.rawValue)|\(Int((settings.fontScale * 100).rounded()))|\(settings.merge)|\(settings.likesPriority)|\(settings.blockWords.joined(separator: "\u{1F}"))"
    }

    private func relayout(_ size: CGSize, _ k: String) {
        guard size.width > 0, k != key else { return }
        key = k
        let words = settings.blockWords
        let filtered: [HGDanmaku]
        if words.isEmpty {
            filtered = raw
        } else {
            filtered = raw.filter { item in
                let display = DanmakuText.display(item.text)
                return !words.contains { kw in
                    item.text.localizedCaseInsensitiveContains(kw) || display.localizedCaseInsensitiveContains(kw)
                }
            }
        }
        let src = settings.merge ? DanmakuMerger.merge(filtered) : filtered
        let nextPlaced = DanmakuLayout.layout(
            src,
            rows: rows,
            screen: size.width,
            font: font,
            duration: settings.speed.duration,
            likesPriority: settings.likesPriority
        )
        placed = nextPlaced
        parsedColors = nextPlaced.map { Color(hex: $0.item.color) ?? .white }
        if !raw.isEmpty {
            settings.status = settings.merge ? "本集 \(raw.count) 条 · 合并后 \(src.count) 条 · 上屏 \(nextPlaced.count) 条"
                                             : "本集 \(raw.count) 条 · 上屏 \(nextPlaced.count) 条"
        }
    }

    private func load() async {
        raw = []; placed = []; parsedColors = []; key = ""
        guard !vid.isEmpty, seriesId.allSatisfy(\.isNumber) else { return }
        settings.status = "弹幕加载中…"
        let r = await HGDanmakuClient.shared.fetch(seriesId: seriesId, vid: vid)
        guard !Task.isCancelled else { return }
        raw = r.items
        settings.status = r.items.isEmpty ? r.notice : (r.notice.isEmpty ? "本集 \(r.items.count) 条" : r.notice)
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize, nowDate: Date) {
        let dur = settings.speed.duration
        let W = size.width
        let blockColor = settings.blockColor

        if previewActive {
            let areaH = CGFloat(rows) * laneHeight
            let areaRect = CGRect(x: 0, y: topInset, width: W, height: areaH)
            ctx.fill(Path(areaRect), with: .color(.white.opacity(0.06)))
        }

        var contentCtx = ctx
        contentCtx.opacity = settings.opacity
        contentCtx.addFilter(.shadow(color: .black.opacity(0.85), radius: 1.2, x: 0, y: 0.5))

        let t = co.playhead
        if t.isFinite, !placed.isEmpty {
            var lo = 0, hi = placed.count
            while lo < hi {
                let m = (lo + hi) / 2
                if placed[m].item.time < t - dur { lo = m + 1 } else { hi = m }
            }
            var i = lo
            while i < placed.count {
                let idx = i
                let p = placed[idx]
                if p.item.time > t { break }
                i += 1
                let elapsed = t - p.item.time
                guard elapsed >= 0, elapsed < dur, p.lane < rows else { continue }
                let y = topInset + laneHeight / 2 + CGFloat(p.lane) * laneHeight
                let x = W - CGFloat(elapsed / dur) * (W + p.width)
                let merged = p.item.count > 1
                let weight: Font.Weight = (merged || p.item.likes >= 50) ? .bold : .semibold
                let color = blockColor ? Color.white : (idx < parsedColors.count ? parsedColors[idx] : .white)
                let text = contentCtx.resolve(Text(p.text).font(.system(size: font, weight: weight)).foregroundColor(color))
                contentCtx.draw(text, at: CGPoint(x: x, y: y), anchor: .leading)
                if merged {
                    let tw = text.measure(in: CGSize(width: 2000, height: 100)).width
                    let badge = contentCtx.resolve(Text(p.badge).font(.system(size: font * 0.8, weight: .heavy)).foregroundColor(STVTheme.accentWarm))
                    contentCtx.draw(badge, at: CGPoint(x: x + tw + 4, y: y), anchor: .leading)
                }
            }
        }

        if previewActive {
            let phase = (nowDate.timeIntervalSinceReferenceDate / dur).truncatingRemainder(dividingBy: 1.0)
            let previewLanes = [
                0,
                min(rows - 1, max(0, rows / 4)),
                min(rows - 1, max(0, rows / 2)),
                min(rows - 1, max(0, (3 * rows) / 4)),
                max(0, rows - 1),
            ]
            for (idx, sample) in previewSamples.enumerated() {
                let progress = CGFloat((phase + sample.phaseOffset).truncatingRemainder(dividingBy: 1.0))
                let color = blockColor ? Color.white : sample.color
                let resolved = contentCtx.resolve(Text(sample.text).font(.system(size: font, weight: .semibold)).foregroundColor(color))
                let tw = resolved.measure(in: CGSize(width: 2000, height: 100)).width
                let x = W - progress * (W + tw)
                let y = topInset + laneHeight / 2 + CGFloat(previewLanes[idx]) * laneHeight
                contentCtx.draw(resolved, at: CGPoint(x: x, y: y), anchor: .leading)
            }

            let areaBottomY = topInset + CGFloat(rows) * laneHeight
            var linePath = Path()
            linePath.move(to: CGPoint(x: 0, y: areaBottomY))
            linePath.addLine(to: CGPoint(x: W, y: areaBottomY))
            ctx.stroke(linePath, with: .color(.white.opacity(0.45)), style: StrokeStyle(lineWidth: 1.2, dash: [6, 4]))

            let guideStr = "弹幕显示区域 · \(settings.area.areaLabel)"
            let resolvedGuide = ctx.resolve(Text(guideStr).font(.system(size: 11, weight: .medium)).foregroundColor(.white.opacity(0.92)))
            let gSize = resolvedGuide.measure(in: CGSize(width: 400, height: 40))
            let padH: CGFloat = 10, padV: CGFloat = 4
            let pillW = gSize.width + padH * 2
            let pillH = gSize.height + padV * 2
            let pillX = (W - pillW) / 2
            let maxPillY = max(topInset + 2, size.height - pillH - 4)
            let pillY = min(maxPillY, max(topInset + 2, areaBottomY - pillH / 2))
            let pillRect = CGRect(x: pillX, y: pillY, width: pillW, height: pillH)
            let pillPath = Path(roundedRect: pillRect, cornerRadius: pillH / 2)
            ctx.fill(pillPath, with: .color(Color(red: 0.078, green: 0.086, blue: 0.106).opacity(0.78)))
            ctx.stroke(pillPath, with: .color(.white.opacity(0.35)), lineWidth: 0.8)
            ctx.draw(resolvedGuide, at: CGPoint(x: pillRect.midX, y: pillRect.midY), anchor: .center)
        }
    }
}

extension Color {
    init?(hex: String) {
        var s = hex; if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(red: Double(v >> 16 & 0xFF) / 255, green: Double(v >> 8 & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

/// Translucent Liquid Glass danmaku settings panel (synced 1:1 with Android `DanmakuSettingsSheet`).
struct DanmakuSettingsSheet: View {
    @ObservedObject var settings = DanmakuSettings.shared
    var available = true
    var onDismiss: (() -> Void)? = nil
    var onReset: (() -> Void)? = nil

    @State private var wordDraft = ""
    @FocusState private var inputFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Header
            HStack(spacing: 8) {
                Text("弹幕设置")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(.white)
                Spacer()
                Button {
                    UISelectionFeedbackGenerator().selectionChanged()
                    settings.resetDefaults()
                    wordDraft = ""
                    onReset?()
                } label: {
                    Text("重置")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.88))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.white.opacity(0.10), in: Capsule())
                        .overlay(Capsule().strokeBorder(Color.white.opacity(0.16), lineWidth: 0.8))
                }
                .buttonStyle(PressScale())

                if let onDismiss {
                    Button(action: onDismiss) {
                        Image(systemName: "xmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.white.opacity(0.78))
                            .frame(width: 28, height: 28)
                            .background(Color.white.opacity(0.10), in: Circle())
                    }
                    .buttonStyle(PressScale())
                    .accessibilityLabel("关闭")
                }
            }

            if !available {
                Text("当前片源不支持弹幕（仅红果）")
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.6))
            }

            // Filter chips
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    filterChip("防挡字幕", selected: settings.avoidSubtitle) {
                        UISelectionFeedbackGenerator().selectionChanged()
                        settings.avoidSubtitle.toggle()
                    }
                    filterChip("合并重复", selected: settings.merge) {
                        UISelectionFeedbackGenerator().selectionChanged()
                        settings.merge.toggle()
                    }
                    filterChip("高赞优先", selected: settings.likesPriority) {
                        UISelectionFeedbackGenerator().selectionChanged()
                        settings.likesPriority.toggle()
                    }
                    filterChip("屏蔽彩色", selected: settings.blockColor) {
                        UISelectionFeedbackGenerator().selectionChanged()
                        settings.blockColor.toggle()
                    }
                }
            }

            // Display area (5 steps)
            VStack(alignment: .leading, spacing: 8) {
                sectionRow("显示区域", value: settings.area.areaLabel, color: STVTheme.accent)
                HStack(spacing: 6) {
                    ForEach(DanmakuSettings.Area.allCases) { item in
                        let on = settings.area == item
                        Button {
                            if !on {
                                UISelectionFeedbackGenerator().selectionChanged()
                                settings.area = item
                            }
                        } label: {
                            Text(item.label)
                                .font(.system(size: 12, weight: on ? .semibold : .regular))
                                .foregroundStyle(on ? STVTheme.accent : .white.opacity(0.84))
                                .frame(maxWidth: .infinity)
                                .frame(height: 34)
                                .background(
                                    on ? STVTheme.accent.opacity(0.18) : Color.white.opacity(0.08),
                                    in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .strokeBorder(on ? STVTheme.accent.opacity(0.58) : Color.white.opacity(0.12), lineWidth: 0.8)
                                )
                        }
                        .buttonStyle(PressScale())
                    }
                }
            }

            // Opacity continuous slider
            VStack(alignment: .leading, spacing: 6) {
                sectionRow("不透明度", value: "\(Int((settings.opacity * 100).rounded()))%", color: STVTheme.accent)
                Slider(value: $settings.opacity, in: 0.20...1.00)
                    .tint(STVTheme.accent)
            }

            // Font scale continuous slider
            VStack(alignment: .leading, spacing: 6) {
                sectionRow("弹幕字号", value: "\(Int((settings.fontScale * 100).rounded()))%", color: STVTheme.accent)
                Slider(value: $settings.fontScale, in: 0.65...1.40)
                    .tint(STVTheme.accent)
            }

            // Speed (5 steps)
            VStack(alignment: .leading, spacing: 8) {
                sectionRow("弹幕速度", value: settings.speed.label, color: STVTheme.accent)
                HStack(spacing: 6) {
                    ForEach(DanmakuSettings.Speed.allCases) { item in
                        let on = settings.speed == item
                        Button {
                            if !on {
                                UISelectionFeedbackGenerator().selectionChanged()
                                settings.speed = item
                            }
                        } label: {
                            Text(item.label)
                                .font(.system(size: 12, weight: on ? .semibold : .regular))
                                .foregroundStyle(on ? STVTheme.accent : .white.opacity(0.84))
                                .frame(maxWidth: .infinity)
                                .frame(height: 34)
                                .background(
                                    on ? STVTheme.accent.opacity(0.18) : Color.white.opacity(0.08),
                                    in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .strokeBorder(on ? STVTheme.accent.opacity(0.58) : Color.white.opacity(0.12), lineWidth: 0.8)
                                )
                        }
                        .buttonStyle(PressScale())
                    }
                }
            }

            // Block words
            VStack(alignment: .leading, spacing: 8) {
                sectionRow(
                    "弹幕屏蔽词",
                    value: settings.blockWords.isEmpty ? "未添加" : "\(settings.blockWords.count) 个",
                    color: settings.blockWords.isEmpty ? .white.opacity(0.45) : STVTheme.accent
                )
                HStack(spacing: 8) {
                    TextField("", text: $wordDraft, prompt: Text("输入屏蔽关键词…").foregroundColor(.white.opacity(0.40)))
                        .font(.system(size: 13))
                        .foregroundStyle(.white)
                        .tint(STVTheme.accent)
                        .focused($inputFocused)
                        .submitLabel(.done)
                        .onSubmit { submitWord() }
                        .padding(.horizontal, 12)
                        .frame(height: 38)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.white.opacity(0.14), lineWidth: 0.8))

                    let canAdd = !wordDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    Button { submitWord() } label: {
                        Text("添加")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(canAdd ? STVTheme.accent : .white.opacity(0.35))
                            .padding(.horizontal, 14)
                            .frame(height: 38)
                            .background(
                                canAdd ? STVTheme.accent.opacity(0.18) : Color.white.opacity(0.06),
                                in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .strokeBorder(canAdd ? STVTheme.accent.opacity(0.55) : Color.white.opacity(0.10), lineWidth: 0.8)
                            )
                    }
                    .disabled(!canAdd)
                    .buttonStyle(PressScale())
                }

                if settings.blockWords.isEmpty {
                    Text("添加后将自动过滤包含该词的弹幕（不区分大小写）")
                        .font(.system(size: 11))
                        .foregroundStyle(.white.opacity(0.42))
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(settings.blockWords, id: \.self) { word in
                                Button {
                                    UISelectionFeedbackGenerator().selectionChanged()
                                    settings.removeBlockWord(word)
                                } label: {
                                    HStack(spacing: 5) {
                                        Text(word)
                                            .font(.system(size: 12))
                                            .foregroundStyle(.white.opacity(0.90))
                                            .lineLimit(1)
                                        Image(systemName: "xmark")
                                            .font(.system(size: 10, weight: .bold))
                                            .foregroundStyle(.white.opacity(0.58))
                                    }
                                    .padding(.leading, 11)
                                    .padding(.trailing, 8)
                                    .padding(.vertical, 6)
                                    .background(Color.white.opacity(0.10), in: Capsule())
                                    .overlay(Capsule().strokeBorder(Color.white.opacity(0.14), lineWidth: 0.8))
                                }
                                .buttonStyle(PressScale())
                            }
                        }
                    }
                }
            }

            if !settings.status.isEmpty {
                Text(settings.status)
                    .font(.system(size: 11))
                    .foregroundStyle(.white.opacity(0.48))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 16)
        .environment(\.colorScheme, .dark)
    }

    private func submitWord() {
        if settings.addBlockWord(wordDraft) {
            UISelectionFeedbackGenerator().selectionChanged()
            wordDraft = ""
            inputFocused = false
        }
    }

    private func sectionRow(_ title: String, value: String, color: Color) -> some View {
        HStack {
            Text(title)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white.opacity(0.72))
            Spacer()
            Text(value)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(color)
        }
    }

    private func filterChip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(STVTheme.accent)
                }
                Text(label)
                    .font(.system(size: 12, weight: selected ? .semibold : .regular))
                    .foregroundStyle(selected ? STVTheme.accent : .white.opacity(0.84))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(selected ? STVTheme.accent.opacity(0.18) : Color.white.opacity(0.08), in: Capsule())
            .overlay(Capsule().strokeBorder(selected ? STVTheme.accent.opacity(0.55) : Color.white.opacity(0.12), lineWidth: 0.8))
        }
        .buttonStyle(PressScale())
    }
}
