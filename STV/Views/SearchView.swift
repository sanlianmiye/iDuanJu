import SwiftUI

struct SearchView: View {
    var pushed = false
    var initialQuery: String? = nil
    var initialSource: DramaSource? = nil
    var startEditingHistory = false
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var submitted = ""
    @State private var source: DramaSource
    @StateObject private var pager = Pager()
    @State private var suggestions: [HGSeries] = []
    @State private var suggestionTask: Task<Void, Never>?
    @State private var hot: [HGSeries] = []
    @State private var hotError: Error?
    @State private var discoverSeed = 0
    @State private var editingHistory = false
    @Environment(\.layoutMetrics) private var layout
    @ObservedObject private var library = LibraryStore.shared
    @ObservedObject private var gate = SourceGate.shared

    @MainActor
    init(pushed: Bool = false, initialQuery: String? = nil, initialSource: DramaSource? = nil, startEditingHistory: Bool = false) {
        self.pushed = pushed
        self.initialQuery = initialQuery
        self.initialSource = initialSource
        self.startEditingHistory = startEditingHistory
        let resolved: DramaSource = {
            if let initialSource, SourceGate.shared.searchSources.contains(initialSource) { return initialSource }
            #if DEBUG
            if let s = DramaSource(rawValue: ProcessInfo.processInfo.environment["STV_SEARCH_SOURCE"] ?? "") { return s }
            #endif
            return .hongguo
        }()
        _source = State(initialValue: resolved)
    }

    private var searchPlaceholder: String {
        source.isLongVideo ? "搜索\(source.label)" : "搜索\(source.label)短剧"
    }

    var body: some View {
        VStack(spacing: 10) {
            SearchField(text: $query, placeholder: searchPlaceholder, onSubmit: submit, onCancel: pushed ? { dismiss() } : (submitted.isEmpty ? nil : { query = ""; submitted = "" }))
                .padding(.horizontal, 16).padding(.top, 6)
            if gate.searchSources.count > 1 {
                TextTabStrip(items: gate.searchSources, selection: $source) { $0.label }
            }
            ScrollView {
                if submitted.isEmpty {
                    if source == .hongguo && !suggestions.isEmpty {
                        suggestionList
                    } else {
                        landing
                    }
                } else {
                    results
                }
            }
        }
        .background(STVTheme.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task {
            if startEditingHistory { editingHistory = true }
            if let q = initialQuery, submitted.isEmpty, !q.isEmpty { query = q; submit() }
            if hot.isEmpty { await loadHot() }
        }
        .onDisappear {
            suggestionTask?.cancel()
        }
        .compatOnChange(of: query) { value in
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !submitted.isEmpty && trimmed != submitted { submitted = "" }
            updateSuggestions(value)
        }
        .compatOnChange(of: source) {
            suggestionTask?.cancel()
            suggestions = []
            if !submitted.isEmpty { run() }
            else if !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { submit() }
        }
        .compatOnChange(of: gate.searchSources) {
            if !gate.searchSources.contains(source) { source = .hongguo }
        }
    }

    // MARK: landing

    private var discover: [String] {
        guard hot.count > 3 else { return [] }
        var rng = SeededRNG(seed: UInt64(discoverSeed + 1))
        return Array(hot.dropFirst(3).map(\.title).shuffled(using: &rng).prefix(8))
    }

    @ViewBuilder private var landing: some View {
        VStack(alignment: .leading, spacing: 20) {
            if !library.searchHistory.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    SectionHeader(title: "历史搜索", trailing: AnyView(HStack(spacing: 16) {
                        if editingHistory {
                            Button("全部删除") { library.clearSearch(); editingHistory = false }.foregroundStyle(STVTheme.accent)
                            Button("完成") { editingHistory = false }.foregroundStyle(STVTheme.text)
                        } else {
                            Button { withAnimation { editingHistory = true } } label: {
                                Image(systemName: "trash").foregroundStyle(STVTheme.secondary)
                            }
                        }
                    }.font(.subheadline)))
                    ChipFlow(items: library.searchHistory, onRemove: editingHistory ? { t in withAnimation { library.removeSearch(t) } } : nil) { t in
                        if editingHistory { withAnimation { library.removeSearch(t) } } else { pick(t) }
                    }
                }
            }
            if !discover.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    SectionHeader(title: "搜索发现", trailing: AnyView(Button { withAnimation { discoverSeed += 1 } } label: {
                        Image(systemName: "arrow.triangle.2.circlepath").foregroundStyle(STVTheme.secondary)
                    }))
                    ChipFlow(items: discover, accent: { t in discover.firstIndex(of: t) == 0 ? "热" : (discover.firstIndex(of: t) == 1 ? "荐" : nil) }) { pick($0) }
                }
            }
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "搜索实时排行")
                if let hotError, hot.isEmpty { ErrorStateView(error: hotError) { Task { await loadHot() } } }
                else if hot.isEmpty {
                    HStack(spacing: 8) { ForEach(0..<3, id: \.self) { _ in ShimmerBlock().aspectRatio(0.6, contentMode: .fit) } }.padding(.horizontal, 16)
                } else {
                    HStack(spacing: 8) {
                        ForEach(0..<min(3, hot.count), id: \.self) { i in TopRankCard(series: hot[i], rank: i + 1) }
                    }.padding(.horizontal, 16)
                    LazyVGrid(columns: layout.wideGridItems(spacing: 14), spacing: 14) {
                        ForEach(Array(hot.dropFirst(3).enumerated()), id: \.element.id) { i, s in CompactRankRow(series: s, rank: i + 4) }
                    }.padding(.horizontal, 16)
                }
            }
        }.padding(.vertical, 8)
    }

    // MARK: results

    @ViewBuilder private var results: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let e = pager.error, pager.items.isEmpty { ErrorStateView(error: e) { run() } }
            else if !pager.isLoading && pager.items.isEmpty {
                CompatEmptyStateView(title: "无搜索结果", systemImage: "magnifyingglass", description: "没有找到与“\(submitted)”相关的内容")
            } else {
                NumberedGrid(items: pager.items, numbered: false, hasMore: pager.hasMore, loading: pager.isLoading) { pager.loadMore() }
            }
        }.padding(.vertical, 8)
    }

    @ViewBuilder private var suggestionList: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(suggestions, id: \.id) { item in
                NavigationLink(value: Route.series(item)) {
                    HStack(spacing: 10) {
                        Image(systemName: "magnifyingglass").font(.caption).foregroundStyle(STVTheme.secondary)
                        Text(item.title).font(.subheadline).foregroundStyle(STVTheme.text).lineLimit(1)
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right").font(.caption2).foregroundStyle(STVTheme.secondary)
                    }
                    .padding(.horizontal, 18).padding(.vertical, 11)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if item.id != suggestions.last?.id { Divider().overlay(STVTheme.glassBorder).padding(.leading, 44) }
            }
        }
        .background(STVTheme.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay { RoundedRectangle(cornerRadius: 14).strokeBorder(STVTheme.glassBorder) }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func updateSuggestions(_ value: String) {
        suggestionTask?.cancel()
        suggestions = []
        let q = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard source == .hongguo, submitted.isEmpty, !q.isEmpty else { return }
        suggestionTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            let result = try? await HongguoClient.shared.suggestions(q)
            guard !Task.isCancelled,
                  query.trimmingCharacters(in: .whitespacesAndNewlines) == q,
                  submitted.isEmpty, source == .hongguo else { return }
            suggestions = result ?? []
        }
    }

    private func pick(_ t: String) { query = t; submit() }
    private func submit() {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines); guard !q.isEmpty else { return }
        suggestionTask?.cancel(); suggestions = []
        library.addSearch(q); submitted = q; run()
    }
    private func run() {
        let q = submitted; guard !q.isEmpty else { return }
        if source == .hongguo { pager.reset { prev, _ in try await HongguoClient.shared.search(q, cursor: prev.nextCursor) } }
        else if source == .huangguo { pager.reset { _, _ in HGPage(items: try await HuangguoClient.shared.search(q), hasMore: false) } }
        else if let p = Sites.provider(source) { pager.reset { prev, _ in try await p.search(q, page: max(1, prev.nextOffset ?? 1)) } }
    }
    private func loadHot() async {
        do {
            var p = try await HongguoClient.shared.rank(sub: .hotSearch, genre: .all)
            if p.hasMore, let more = try? await HongguoClient.shared.rank(sub: .hotSearch, genre: .all, offset: p.nextOffset ?? 0, seenIds: p.items.map(\.seriesId)) { p.items += more.items }
            hot = Array(p.items.prefix(23)); hotError = nil
        } catch { hotError = error }
    }
}

struct SeededRNG: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed &* 0x9E3779B97F4A7C15 }
    mutating func next() -> UInt64 { state ^= state << 13; state ^= state >> 7; state ^= state << 17; return state }
}
