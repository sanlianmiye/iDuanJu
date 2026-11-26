import Foundation
import SwiftUI

/// One 播完连刷推荐 candidate. `tier` 1 = next season, 2 = same category / tag match, 3 = hot feed.
struct NextDramaPick: Equatable {
    let series: HGSeries
    let tier: Int

    var reason: String {
        switch tier {
        case 1: return "下一季"
        case 2: return "同类推荐"
        default: return "热播推荐"
        }
    }

    var meta: String {
        var parts: [String] = []
        if series.episodeCount > 0 { parts.append("共 \(series.episodeCount) 集") }
        if series.playCount > 0 { parts.append(DS.count(series.playCount) + "热度") }
        return parts.joined(separator: " · ")
    }
}

/// 3-tier next-drama auto-continue recommender for iOS (mirrors Android `PlayerNextDrama.kt`):
/// (1) next season in `relatedSeries` / `seasons`, (2) same-category & tag-matched top popular dramas,
/// (3) general hot/recommended feed. Skips current drama, watched history, and dramas skipped this session.
actor NextDramaRecommender {
    static let shared = NextDramaRecommender()

    private static let poolTTL: TimeInterval = 10 * 60
    private static let topCandidates = 8

    private var skipped = Set<String>()
    private var pools: [HGRankGenre: (items: [HGSeries], at: Date)] = [:]

    func skip(_ seriesId: String) {
        if !seriesId.isEmpty { skipped.insert(seriesId) }
    }

    func pickNextDrama(
        current: HGSeriesDetail,
        seasons: [HGSeries],
        watched: Set<String>
    ) async -> NextDramaPick? {
        func allowed(_ s: HGSeries) -> Bool {
            !s.seriesId.isEmpty && s.seriesId != current.seriesId && !watched.contains(s.seriesId) && !skipped.contains(s.seriesId)
        }

        // Tier 1: next season in relatedSeries or seasons
        let related = current.relatedSeries.isEmpty ? seasons : current.relatedSeries
        if let idx = related.firstIndex(where: { $0.seriesId == current.seriesId }) {
            for candidate in related.dropFirst(idx + 1) where allowed(candidate) {
                return NextDramaPick(series: candidate, tier: 1)
            }
        } else if let firstRelated = related.first(where: allowed) {
            return NextDramaPick(series: firstRelated, tier: 1)
        }

        // Tier 2: same category + tag-matched popular pool
        let genre = Self.genreOf(tags: current.tags, title: current.title)
        let categoryPool = genre == .all ? [] : await pool(for: genre)
        let curTags = Set(current.tags)
        let tier2 = categoryPool.filter(allowed)
        if !tier2.isEmpty {
            let tagged: [HGSeries] = curTags.isEmpty ? [] : tier2
                .filter { s in s.tags.contains(where: { curTags.contains($0) }) }
                .sorted { a, b in
                    a.tags.filter { curTags.contains($0) }.count > b.tags.filter { curTags.contains($0) }.count
                }
            let base = tagged.isEmpty ? tier2 : tagged
            if let picked = Self.randomPopular(from: base) {
                return NextDramaPick(series: picked, tier: 2)
            }
        }

        // Tier 3: general hot / recommended feed
        let tier3 = await pool(for: .all).filter(allowed)
        if let picked = Self.randomPopular(from: tier3) {
            return NextDramaPick(series: picked, tier: 3)
        }
        return nil
    }

    private func pool(for genre: HGRankGenre) async -> [HGSeries] {
        if let cached = pools[genre], Date().timeIntervalSince(cached.at) < Self.poolTTL {
            return cached.items
        }
        var seen = Set<String>()
        var merged: [HGSeries] = []
        for sub in [HGRankSub.hot, HGRankSub.recommend] where genre.subs.contains(sub) {
            if let page = try? await HongguoClient.shared.rank(sub: sub, genre: genre, offset: 0) {
                for item in page.items where seen.insert(item.seriesId).inserted {
                    merged.append(item)
                }
            }
        }
        if !merged.isEmpty {
            pools[genre] = (merged, Date())
        }
        return merged
    }

    private static func randomPopular(from list: [HGSeries]) -> HGSeries? {
        guard !list.isEmpty else { return nil }
        let sorted = list.sorted { $0.playCount > $1.playCount }
        let top = Array(sorted.prefix(topCandidates))
        return top.randomElement() ?? list.first
    }

    private static func genreOf(tags: [String], title: String) -> HGRankGenre {
        let all = tags + [title]
        if all.contains(where: { $0.contains("漫剧") || $0.contains("动漫") || $0.contains("漫画") || $0.contains("国漫") }) {
            return .comic
        }
        if tags.contains(where: { $0.caseInsensitiveCompare("AI") == .orderedSame || $0.hasPrefix("AI") }) || title.contains("AI") {
            return .ai
        }
        return .human
    }
}

/// 3-second countdown overlay card shown at the finale when 播完连刷推荐 is enabled.
struct NextDramaOverlayCard: View {
    let pick: NextDramaPick
    let paused: Bool
    let onPlayNow: () -> Void
    let onShuffle: () -> Void
    let onCancel: () -> Void

    @State private var remaining: Double = 3.0
    private let total: Double = 3.0

    var body: some View {
        VStack(spacing: 16) {
            HStack(spacing: 14) {
                CoverView(url: pick.series.cover, corner: 12)
                    .frame(width: 78, height: 104)
                    .shadow(color: .black.opacity(0.4), radius: 10, y: 6)

                VStack(alignment: .leading, spacing: 6) {
                    Text(pick.reason)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(STVTheme.accent)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(STVTheme.accentSoft, in: Capsule())

                    Text(pick.series.title)
                        .font(.headline.weight(.bold))
                        .foregroundStyle(.white)
                        .lineLimit(2)

                    if !pick.meta.isEmpty {
                        Text(pick.meta)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.72))
                            .lineLimit(1)
                    }

                    if !pick.series.tags.isEmpty {
                        Text(pick.series.tags.prefix(3).joined(separator: " · "))
                            .font(.caption2)
                            .foregroundStyle(.white.opacity(0.6))
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }

            HStack(spacing: 10) {
                Button(action: onPlayNow) {
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.20))
                        GeometryReader { g in
                            Capsule()
                                .fill(STVTheme.accent)
                                .frame(width: g.size.width * CGFloat(max(0, min(1, 1 - remaining / total))))
                        }
                        HStack(spacing: 6) {
                            Image(systemName: "play.fill")
                            Text("立即播放 (\(max(1, Int(ceil(remaining))))s)")
                        }
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                    }
                    .frame(height: 42)
                }
                .buttonStyle(.plain)

                Button(action: onShuffle) {
                    Label("换一部", systemImage: "arrow.triangle.2.circlepath")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12)
                        .frame(height: 42)
                        .background(Color.white.opacity(0.14), in: Capsule())
                }
                .buttonStyle(.plain)

                Button(action: onCancel) {
                    Text("取消")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.8))
                        .padding(.horizontal, 12)
                        .frame(height: 42)
                        .background(Color.white.opacity(0.10), in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(18)
        .frame(maxWidth: 360)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Color.white.opacity(0.16), lineWidth: 1))
        .shadow(color: .black.opacity(0.45), radius: 22, y: 12)
        .task(id: pick.series.seriesId) {
            remaining = total
            while !Task.isCancelled && remaining > 0 {
                try? await Task.sleep(for: .milliseconds(100))
                if !paused {
                    remaining = max(0, remaining - 0.1)
                }
            }
            if !Task.isCancelled && remaining <= 0 {
                onPlayNow()
            }
        }
    }
}
