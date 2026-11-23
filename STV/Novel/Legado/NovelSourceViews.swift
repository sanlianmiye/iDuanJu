import SwiftUI
import UniformTypeIdentifiers

/// 书源管理: import 「阅读」(Legado) JSON sources from a URL, the clipboard or a file; toggle / delete.
struct NovelSourcesView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = LegadoSourceStore.shared
    @State private var importing = false
    @State private var login: LegadoSource?
    @State private var filter: Filter = .all

    enum Filter: String, CaseIterable { case all = "全部", on = "已启用", login = "需登录", unsupported = "不支持" }

    private func matches(_ s: LegadoSource, _ f: Filter) -> Bool {
        switch f {
        case .all: true
        case .on: s.isOn && s.supported
        case .login: s.hasLogin && !LegadoSourceState.isLoggedIn(s.bookSourceUrl)
        case .unsupported: !s.supported
        }
    }
    private var visible: [LegadoSource] { store.sources.filter { matches($0, filter) } }

    /// ui-redesign 08: count line, status chips with counts, one card of rows with status labels, 登录 and a switch.
    var body: some View {
        VStack(spacing: 0) {
            DSNavBar(title: "书源") {
                DSBackButton { dismiss() }
            } trailing: {
                RoundIconButton(systemName: "plus", tone: .accent) { importing = true }.accessibilityIdentifier("sources.add")
            }
            List {
                Group {
                    Text("共 \(store.sources.count) 个书源 · 已启用 \(store.enabled.count) 个")
                        .font(.system(size: 13)).foregroundStyle(STVTheme.secondary)
                        .padding(.horizontal, 20).padding(.top, 12)
                    filterChips.padding(.top, 12).padding(.bottom, 16)
                }
                .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)
                if store.sources.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "books.vertical").font(.system(size: 30)).foregroundStyle(STVTheme.tertiary)
                        Text("还没有书源").font(.system(size: 16, weight: .semibold)).foregroundStyle(STVTheme.text)
                        Text("点右上角 ＋ 导入「阅读」(Legado) 的书源 JSON。App 不内置任何书源。")
                            .font(.system(size: 13)).foregroundStyle(STVTheme.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 40).padding(.horizontal, 24)
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)
                }
                let list = visible
                ForEach(Array(list.enumerated()), id: \.element.id) { i, s in
                    row(s)
                        .overlay(alignment: .top) { if i > 0 { Hairline() } }
                        .padding(.horizontal, 14)
                        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(CardSlice(first: i == 0, last: i == list.count - 1).padding(.horizontal, 16))
                }
                .onDelete { idx in store.remove(Set(idx.map { list[$0].id })) }
                if !store.sources.isEmpty {
                    Text("左滑可删除书源。").font(.system(size: 12)).foregroundStyle(STVTheme.tertiary)
                        .padding(.horizontal, 30).padding(.top, 14).padding(.bottom, 40)
                        .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .animation(DS.panel, value: filter)
        }
        .dsAmbient(cool: true)
        .tint(STVTheme.accent)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(item: $login) { s in NovelSourceLoginView(source: s) }
        .sheet(isPresented: $importing) { NovelImportSheet(showBooks: false) }
    }

    private var filterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Filter.allCases, id: \.self) { f in
                    Button { withAnimation(DS.select) { filter = f } } label: {
                        CountLabel(title: f.rawValue, count: store.sources.filter { matches($0, f) }.count)
                    }
                    .buttonStyle(ToneChipStyle(on: filter == f, neutral: f == .all))
                    .accessibilityIdentifier("sources.filter.\(f.rawValue)")
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func row(_ s: LegadoSource) -> some View {
        let loggedIn = LegadoSourceState.isLoggedIn(s.bookSourceUrl)
        let dim = !s.supported
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 7) {
                    Text(s.bookSourceName).font(.system(size: 16, weight: .medium)).foregroundStyle(STVTheme.text).lineLimit(1)
                        .opacity(dim ? 0.5 : 1)
                    if !s.supported { DSStatus(text: "暂不支持", tone: .muted) }
                    else if s.hasLogin { DSStatus(text: loggedIn ? "已登录" : "需登录", tone: loggedIn ? .ok : .warn) }
                }
                Text(subtitle(s)).font(.system(size: 12)).foregroundStyle(STVTheme.secondary).lineLimit(1).opacity(dim ? 0.5 : 1)
            }
            Spacer(minLength: 0)
            if s.supported, s.hasLogin, !loggedIn {
                Button { login = s } label: { MiniButtonLabel(title: "登录") }.buttonStyle(PressScale())
            }
            Toggle("", isOn: Binding(get: { s.isOn && s.supported }, set: { store.setEnabled(s, $0) }))
                .labelsHidden().tint(STVTheme.accent).disabled(!s.supported).opacity(s.supported ? 1 : 0.4)
        }
        .frame(minHeight: 66)
        .contentShape(Rectangle())
        .contextMenu { if s.hasLogin { Button("登录", systemImage: "person.crop.circle") { login = s } } }
        .swipeActions(edge: .leading) { if s.hasLogin { Button("登录") { login = s }.tint(STVTheme.accent) } }
    }

    private func subtitle(_ s: LegadoSource) -> String {
        let group = (s.bookSourceGroup ?? "").isEmpty ? "综合" : s.bookSourceGroup!
        if !s.supported { return group + " · " + ([2,3].contains(s.bookSourceType ?? 0) ? "暂未实现的源类型" : "没有搜索 / 发现地址") }
        return [group, URL(string: s.bookSourceUrl)?.host].compactMap { $0 }.joined(separator: " · ")
    }
}

/// A list-row background that draws one slice of a rounded card (first / middle / last row).
struct CardSlice: View {
    var first: Bool
    var last: Bool
    var radius: CGFloat = 18
    var body: some View {
        CompatUnevenRoundedRectangle(topLeadingRadius: first ? radius : 0, bottomLeadingRadius: last ? radius : 0,
                               bottomTrailingRadius: last ? radius : 0, topTrailingRadius: first ? radius : 0, style: .continuous)
            .fill(STVTheme.card)
    }
}

// MARK: - 搜书

/// Searches every enabled 书源 at once; results are merged by 书名 + 作者 as sources answer.
struct NovelSearchView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var sources = LegadoSourceStore.shared
    @State private var query = ""
    @State private var groups: [Group] = []
    @State private var done = 0
    @State private var total = 0
    @State private var failures = 0
    @State private var task: Task<Void, Never>?
    @State private var manage = false

    struct Group: Identifiable, Hashable {
        var name: String
        var author: String
        var items: [LegadoSearchItem]
        var id: String { name + "|" + author }
    }

    @State private var scrolled = false
    @State private var submitted = ""
    @FocusState private var focused: Bool

    /// Unified STVTheme 0-card search: integrated single-row top bar, live progress line, open hairline results.
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                topBar.dsPinned(scrolled)
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if sources.enabled.isEmpty {
                            empty("还没有可用的书源", "先在「书源」里导入「阅读」(Legado) 书源", action: ("管理书源", { manage = true }))
                        } else if total > 0 {
                            progress.padding(.horizontal, 18).padding(.top, 10)
                            LazyVStack(spacing: 0) {
                                ForEach(Array(groups.enumerated()), id: \.element.id) { i, g in
                                    NavigationLink(value: g) { resultRow(g, lead: i == 0) }
                                        .buttonStyle(PressScale(scale: 0.985))
                                        .overlay(alignment: .top) {
                                            if i > 0 { Hairline().padding(.leading, i == 1 ? 0 : 71) }
                                        }
                                        .accessibilityIdentifier("search.result.\(g.name)")
                                        .transition(.opacity)
                                }
                            }
                            .padding(.horizontal, 18).padding(.top, 4)
                            .animation(DS.panel, value: groups.map(\.id))
                            if done >= total && groups.isEmpty {
                                empty("没有找到书籍", "换个关键词，或在书源配置中启用其他来源。")
                            }
                        } else {
                            empty("搜索你的下一本书", "输入书名或作者，同时搜索已启用的书源，相同书籍会自动合并。")
                        }
                    }
                    .padding(.bottom, 40)
                }
                .compatOnScrolled(threshold: 4) { scrolled = $0 }
                .scrollDismissesKeyboard(.immediately)
            }
            .dsAmbient(cool: true)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Group.self) { g in NovelWebDetailView(items: g.items) }
            .fullScreenCover(isPresented: $manage) { NovelSourcesView() }
            .onAppear {
                #if DEBUG
                if let q = ProcessInfo.processInfo.environment["STV_NOVEL_QUERY"], !q.isEmpty, submitted.isEmpty {
                    query = q; focused = false; run(); return
                }
                #endif
                if query.isEmpty { focused = true }
            }
        }
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            DSBackButton { task?.cancel(); dismiss() }
            HStack(spacing: 7) {
                Image(systemName: "magnifyingglass").font(.system(size: 15, weight: .medium)).foregroundStyle(STVTheme.secondary)
                TextField("", text: $query, prompt: Text("书名或作者").foregroundColor(STVTheme.tertiary))
                    .focused($focused).submitLabel(.search).onSubmit(run)
                    .font(.system(size: 15, weight: .medium)).foregroundStyle(STVTheme.text).tint(STVTheme.accent)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
                    .accessibilityIdentifier("search.field")
                if !query.isEmpty {
                    Button { query = ""; focused = true } label: {
                        Image(systemName: "xmark").font(.system(size: 8.5, weight: .bold)).foregroundStyle(STVTheme.bg)
                            .frame(width: 18, height: 18).background(Circle().fill(STVTheme.tertiary))
                    }
                    .buttonStyle(PressScale()).accessibilityLabel("清除").transition(.scale.combined(with: .opacity))
                    Button("搜索", action: run).font(.system(size: 13.5, weight: .semibold)).foregroundStyle(STVTheme.accent)
                }
            }
            .padding(.leading, 13).padding(.trailing, 10).frame(height: 40)
            .background(Capsule().fill(STVTheme.glass))
            .overlay(Capsule().strokeBorder(STVTheme.glassBorder, lineWidth: 1))
            .animation(DS.press, value: query.isEmpty)

            Button { manage = true } label: {
                HStack(spacing: 4) {
                    Text("书源").font(.system(size: 13, weight: .semibold)).foregroundStyle(STVTheme.text)
                    if !sources.enabled.isEmpty {
                        Text("\(sources.enabled.count)").font(.system(size: 11.5, weight: .semibold)).foregroundStyle(STVTheme.accent)
                    }
                }
                .padding(.horizontal, 11).frame(height: 36)
                .background(Capsule().fill(STVTheme.glass))
                .overlay(Capsule().strokeBorder(STVTheme.hairline, lineWidth: 1))
                .contentShape(Capsule())
            }
            .buttonStyle(PressScale()).accessibilityIdentifier("search.sources")
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
    }

    private var progress: some View {
        VStack(spacing: 7) {
            HStack(spacing: 6) {
                if done < total {
                    Circle().fill(STVTheme.accent).frame(width: 6, height: 6)
                    (Text("正在搜索 ") + Text("\(done)").foregroundColor(STVTheme.text).bold() + Text(" / \(total) 个书源 · \(groups.count) 本"))
                } else {
                    Text("共 \(groups.count) 本 · \(total) 个书源")
                }
                Spacer(minLength: 8)
                if failures > 0 { Text("\(failures) 个失败").font(.system(size: 11.5, weight: .medium)).foregroundStyle(STVTheme.accentWarm) }
            }
            .font(.system(size: 12)).foregroundStyle(STVTheme.secondary)
            .accessibilityElement(children: .combine).accessibilityIdentifier("search.progress")
            if done < total {
                DSProgressBar(value: total > 0 ? Double(done) / Double(total) : 0)
            }
        }
    }

    private func empty(_ title: String, _ text: String, action: (String, () -> Void)? = nil) -> some View {
        VStack(spacing: 10) {
            Image(systemName: "magnifyingglass").font(.system(size: 30)).foregroundStyle(STVTheme.tertiary)
            Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(STVTheme.text)
            Text(text).font(.system(size: 13)).foregroundStyle(STVTheme.secondary).multilineTextAlignment(.center)
            if let action {
                Button(action: action.1) { PillButtonLabel(title: action.0, small: true) }.buttonStyle(PressScale()).padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity).padding(.horizontal, 32).padding(.top, 80)
    }

    private func highlighted(_ name: String) -> AttributedString {
        var a = AttributedString(name)
        let q = submitted
        if !q.isEmpty, let r = a.range(of: q) { a[r].foregroundColor = STVTheme.accent }
        return a
    }

    private func resultRow(_ g: Group, lead: Bool) -> some View {
        let first = g.items[0]
        let meta = [g.author.isEmpty ? nil : g.author, first.kind.isEmpty ? nil : first.kind].compactMap { $0 }.joined(separator: " · ")
        let intro = g.items.first { !$0.intro.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }?.intro
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\n", with: " ") ?? ""
        let exact = !submitted.isEmpty && g.name == submitted
        return HStack(alignment: lead ? .top : .center, spacing: 13) {
            WebCover(url: g.items.first { !$0.cover.isEmpty }?.cover, title: g.name, author: g.author, corner: lead ? 8 : 7)
                .frame(width: lead ? 64 : 58, height: lead ? 90 : 82)
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    Text(highlighted(g.name))
                        .font(.system(size: lead ? 17 : 16, weight: .bold))
                        .foregroundStyle(STVTheme.text)
                        .lineLimit(1)
                    if exact { DSStatus(text: "最佳匹配", tone: .accent) }
                    Spacer(minLength: 4)
                    DSStatus(text: "\(g.items.count) 个书源", tone: g.items.count > 1 ? .accent : .muted)
                }
                if !meta.isEmpty {
                    Text(meta).font(.system(size: 12)).foregroundStyle(STVTheme.secondary).lineLimit(1).padding(.top, 3)
                }
                if !intro.isEmpty {
                    Text(intro)
                        .font(.system(size: 12))
                        .foregroundStyle(lead ? STVTheme.secondary : STVTheme.tertiary)
                        .lineSpacing(2)
                        .lineLimit(lead ? 2 : 1)
                        .padding(.top, 4)
                }
                HStack(spacing: 8) {
                    Text(first.latest.isEmpty ? "" : "最新：" + first.latest)
                        .font(.system(size: 11.5)).foregroundStyle(STVTheme.tertiary).lineLimit(1)
                    Spacer(minLength: 4)
                    Text((g.items.count > 1 ? "首选 " : "") + first.sourceName)
                        .font(.system(size: 11)).foregroundStyle(STVTheme.secondary).lineLimit(1)
                }
                .padding(.top, 6)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, lead ? 15 : 13)
        .contentShape(Rectangle())
    }

    private func run() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        focused = false
        submitted = q
        task?.cancel()
        groups = []; done = 0; failures = 0
        let list = sources.enabled
        total = list.count
        task = Task {
            await withTaskGroup(of: (Bool, [LegadoSearchItem]).self) { tg in
                var it = list.makeIterator()
                func add() -> Bool {
                    guard let s = it.next() else { return false }
                    tg.addTask {
                        do { return (true, try await LegadoClient.run { try LegadoClient.search(s, key: q) }) } catch { return (false, []) }
                    }
                    return true
                }
                for _ in 0..<8 { if !add() { break } }
                for await (ok, items) in tg {
                    if Task.isCancelled { tg.cancelAll(); return }
                    done += 1
                    if !ok { failures += 1 }
                    merge(items, query: q)
                    _ = add()
                }
            }
        }
    }

    private func merge(_ items: [LegadoSearchItem], query q: String) {
        for it in items {
            let author = it.author.trimmingCharacters(in: .whitespaces)
            if let i = groups.firstIndex(where: { $0.name == it.name && ($0.author == author || $0.author.isEmpty || author.isEmpty) }) {
                if !groups[i].items.contains(where: { $0.sourceURL == it.sourceURL }) { groups[i].items.append(it) }
            } else {
                groups.append(Group(name: it.name, author: author, items: [it]))
            }
        }
        groups.sort { a, b in
            func rank(_ g: Group) -> Int { g.name == q ? 0 : g.name.contains(q) || g.author == q ? 1 : 2 }
            return (rank(a), -a.items.count) < (rank(b), -b.items.count)
        }
    }
}

/// Cover from a URL with a clothbound fallback, physical book spine crease, and optional top-left rank numeral.
struct WebCover: View {
    var url: String?
    var title: String
    var author: String? = nil
    var corner: CGFloat = 8
    var rank: Int? = nil

    var body: some View {
        Color.clear
            .overlay {
                AsyncImage(url: url.flatMap(URL.init(string:))) { phase in
                    if let img = phase.image { img.resizable().scaledToFill() }
                    else { GeneratedBookCover(title: title, author: author) }
                }
            }
            .overlay { BookSpineOverlay(corner: corner) }
            .overlay(alignment: .topLeading) { if let rank { PosterRankNumeral(rank: rank) } }
            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
            .shadow(color: .black.opacity(0.24), radius: 7, y: 4)
    }
}

// MARK: - 详情

/// Aligned with DramaDetailView: blurred cover hero, single 3-column metrics strip, ink CTA + round buttons, open hairline sections.
struct NovelWebDetailView: View {
    let items: [LegadoSearchItem]
    var onRead: ((NovelBook) -> Void)? = nil
    @State private var reading: NovelBook?
    var resumeFrom: NovelBook? = nil
    @State private var pick = 0
    @State private var info: LegadoBookInfo?
    @State private var toc: [LegadoChapter]?
    @State private var error: String?
    @State private var loading = false
    @State private var alternatives: [LegadoSearchItem] = []
    @State private var findingSources = false
    @State private var downloadBook: NovelBook?
    @State private var showingMedia = false
    @State private var mediaChapter = 0
    @State private var sourceTask: Task<Void, Never>?
    @State private var loadGeneration = UUID()
    @State private var pinned = false
    @State private var actionsOut = false
    @State private var introOpen = false
    @State private var cachedChapters = 0

    private var allItems: [LegadoSearchItem] { items + alternatives.filter { candidate in !items.contains { $0.id == candidate.id } } }
    @ObservedObject private var store = NovelStore.shared

    private var item: LegadoSearchItem { allItems[min(pick, allItems.count - 1)] }
    private var source: LegadoSource? { LegadoSourceStore.shared.source(item.sourceURL) }
    private var onShelf: NovelBook? { store.webBook(source: item.sourceURL, url: item.bookURL) }

    @Environment(\.dismiss) private var dismiss
    private var title: String { info?.name.novelNonEmpty ?? item.name }
    private var author: String { info?.author.novelNonEmpty ?? item.author }
    private var intro: String { (info?.intro.novelNonEmpty ?? item.intro).trimmingCharacters(in: .whitespacesAndNewlines) }
    private var kind: String { info?.kind.novelNonEmpty ?? item.kind }
    private var coverURL: String? { info?.cover.novelNonEmpty ?? item.cover.novelNonEmpty }
    private var latest: String { info?.latest.novelNonEmpty ?? item.latest }
    private var chapterCount: Int { toc?.count ?? onShelf?.chapters.count ?? 0 }
    private var cacheRefreshKey: String { "\(onShelf?.id ?? "none")|\(onShelf?.chapters.count ?? 0)|\(downloadBook == nil)" }
    private var canOpen: Bool { chapterCount > 0 }
    private var isMedia: Bool { source?.isMedia == true }
    private var readLabel: String {
        switch source?.bookSourceType {
        case 1: "开始收听"
        case 4: "开始观看"
        default: "开始阅读"
        }
    }
    private var tags: [String] {
        var result: [String] = []
        for raw in kind.components(separatedBy: CharacterSet(charactersIn: "·/,，|")) {
            let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty && !result.contains(t) { result.append(t) }
        }
        return Array(result.prefix(5))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                hero
                metricsStrip.padding(.top, 4)
                actionRow
                    .padding(.horizontal, 16)
                    .padding(.top, 15)
                    .compatOnFrame(in: .named("novelDetail")) { f in
                        let out = f.maxY < 60
                        if actionsOut != out { withAnimation(DS.panel) { actionsOut = out } }
                    }

                if !tags.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 7) {
                            ForEach(tags, id: \.self) { t in
                                Text(t).font(.system(size: 12)).foregroundStyle(STVTheme.secondary)
                                    .padding(.horizontal, 10).frame(height: 24)
                                    .background(Capsule().fill(STVTheme.glass))
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                    .padding(.top, 15)
                }

                if !intro.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(intro)
                            .font(.system(size: 13.5))
                            .lineSpacing(5)
                            .foregroundStyle(STVTheme.secondary)
                            .lineLimit(introOpen ? nil : 4)
                            .textSelection(.enabled)
                        if intro.count > 72 {
                            Button(introOpen ? "收起" : "展开") { withAnimation(DS.panel) { introOpen.toggle() } }
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(STVTheme.accent)
                                .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                }

                // Open hairline Directory & Source rows (0 cards)
                VStack(spacing: 0) {
                    Hairline()
                    Button { read(open: true, chapter: chapterCount > 0 ? chapterCount - 1 : nil) } label: {
                        HStack(spacing: 10) {
                            Text("目录").font(.system(size: 14, weight: .semibold)).foregroundStyle(STVTheme.text)
                            Text(!latest.isEmpty ? "最新：" + latest : (toc?.last?.title ?? onShelf?.chapters.last?.title ?? "暂无章节信息"))
                                .font(.system(size: 13)).foregroundStyle(STVTheme.secondary).lineLimit(1)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if chapterCount > 0 {
                                Text("共 \(chapterCount) 章").font(.system(size: 13, weight: .medium)).foregroundStyle(STVTheme.secondary)
                            }
                            Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(STVTheme.tertiary)
                        }
                        .frame(minHeight: 46)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!canOpen)

                    Hairline()

                    Menu { sourceMenuContent } label: {
                        HStack(spacing: 10) {
                            Text("书源").font(.system(size: 14, weight: .semibold)).foregroundStyle(STVTheme.text)
                            HStack(spacing: 6) {
                                Circle().fill(STVTheme.ok).frame(width: 6, height: 6)
                                Text(item.sourceName).font(.system(size: 13)).foregroundStyle(STVTheme.secondary).lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            Text(allItems.count > 1 ? "换源 (\(allItems.count))" : "换源")
                                .font(.system(size: 13, weight: .semibold)).foregroundStyle(STVTheme.accent)
                            Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(STVTheme.accent)
                        }
                        .frame(minHeight: 46)
                        .contentShape(Rectangle())
                    }
                    .accessibilityIdentifier("detail.source")

                    Hairline()
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)

                // Open hairline Preview Chapters (0 cards)
                if !isMedia, chapterCount > 0 {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(alignment: .firstTextBaseline) {
                            Text("试读章节").font(.system(size: 16.5, weight: .bold)).foregroundStyle(STVTheme.text)
                            Spacer()
                            Text("点击直接阅读").font(.system(size: 12)).foregroundStyle(STVTheme.tertiary)
                        }
                        .padding(.top, 18).padding(.bottom, 6)

                        ForEach(0..<min(3, chapterCount), id: \.self) { index in
                            if index > 0 { Hairline() }
                            Button { read(open: true, chapter: index) } label: {
                                HStack(spacing: 10) {
                                    Text(String(format: "%02d", index + 1))
                                        .font(.system(size: 12, weight: .semibold)).foregroundStyle(STVTheme.tertiary)
                                        .frame(width: 20, alignment: .leading)
                                    Text(toc?[index].title ?? onShelf?.chapters[index].title ?? "")
                                        .font(.system(size: 14)).foregroundStyle(STVTheme.text).lineLimit(1)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    if index == 0 { DSStatus(text: "试读", tone: .accent) }
                                    Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(STVTheme.tertiary)
                                }
                                .frame(minHeight: 43)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                }

                if findingSources {
                    HStack(spacing: 8) {
                        ProgressView("正在查找其他书源…").font(.system(size: 13))
                        Button("停止") { sourceTask?.cancel() }.font(.system(size: 13, weight: .semibold)).foregroundStyle(STVTheme.accent)
                    }
                    .padding(.horizontal, 16).padding(.top, 14)
                }
                if loading {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("正在读取目录…").font(.system(size: 13)).foregroundStyle(STVTheme.secondary)
                    }
                    .padding(.horizontal, 16).padding(.top, 14)
                }
                if let error {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(error).font(.system(size: 13)).foregroundStyle(STVTheme.accentWarm)
                        Button { Task { await load() } } label: { MiniButtonLabel(title: "重试") }.buttonStyle(PressScale())
                    }
                    .padding(.horizontal, 16).padding(.top, 12)
                }
            }
            .padding(.bottom, 108)
        }
        .coordinateSpace(name: "novelDetail")
        .compatOnScrolled(threshold: 210) { value in
            if pinned != value { pinned = value }
        }
        .scrollIndicators(.hidden)
        .ignoresSafeArea(edges: .top)
        .background(STVTheme.bg.ignoresSafeArea())
        .overlay(alignment: .top) { navBar }
        .overlay(alignment: .bottom) { floatingBar }
        .foregroundStyle(STVTheme.text)
        .toolbar(.hidden, for: .navigationBar)
        .task(id: pick) { await load() }
        .task(id: cacheRefreshKey) {
            guard let book = onShelf else { cachedChapters = 0; return }
            let count = await Task.detached(priority: .utility) {
                NovelStore.fastCachedCount(book)
            }.value
            if !Task.isCancelled { cachedChapters = count }
        }
        .onDisappear { sourceTask?.cancel() }
        .fullScreenCover(item: $reading) { NovelReaderView(book: $0) }
        .sheet(item: $downloadBook) { book in NovelDownloadView(book: book) }
        .fullScreenCover(isPresented: $showingMedia) {
            if let source {
                NovelMediaPlayer(
                    source: source,
                    chapters: toc ?? [],
                    startIndex: mediaChapter,
                    book: [
                        "cover": info?.cover.novelNonEmpty ?? item.cover,
                        "name": info?.name.isEmpty == false ? info!.name : item.name,
                        "author": info?.author.isEmpty == false ? info!.author : item.author,
                        "bookUrl": item.bookURL,
                    ]
                )
            }
        }
    }

    @ViewBuilder private var sourceMenuContent: some View {
        ForEach(allItems.indices, id: \.self) { i in
            Button { pick = i } label: { Label(allItems[i].sourceName, systemImage: i == pick ? "checkmark" : "book") }
        }
        Button("搜索其他书源") { sourceTask?.cancel(); sourceTask = Task { await findSources() } }
    }

    private var navBar: some View {
        HStack(spacing: 0) {
            DSBackButton(tone: pinned ? .glass : .overlay) { dismiss() }.frame(width: 96, alignment: .leading)
            Text(title).font(.system(size: 17, weight: .semibold)).foregroundStyle(STVTheme.text).lineLimit(1)
                .frame(maxWidth: .infinity)
                .opacity(pinned ? 1 : 0).offset(y: pinned ? 0 : 6)
            Color.clear.frame(width: 96, height: 1)
        }
        .frame(height: 44)
        .padding(.horizontal, 16).padding(.bottom, 6)
        .dsPinned(pinned)
        .animation(.easeOut(duration: 0.2), value: pinned)
    }

    @ViewBuilder private var floatingBar: some View {
        if canOpen, actionsOut {
            HStack(spacing: 10) {
                Button { read(open: true) } label: {
                    PillButtonLabel(title: readLabel, icon: "play.fill", tone: .ink, height: 46, expand: true)
                }
                .buttonStyle(PressScale(scale: 0.98))
                roundAction(icon: onShelf == nil ? "bookmark" : "checkmark", active: onShelf != nil, enabled: canOpen && onShelf == nil && !isMedia, size: 46) {
                    read(open: false)
                }
                roundAction(icon: "arrow.down.to.line", active: false, enabled: canOpen && !isMedia, size: 46) {
                    downloadBook = onShelf ?? store.addWeb(item, info: info, toc: toc ?? [])
                }
            }
            .padding(8)
            .dsBar(32)
            .padding(.horizontal, 16).padding(.bottom, -4)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private var hero: some View {
        let cloth = GeneratedBookCover.clothColors(title)
        return ZStack(alignment: .bottomLeading) {
            Color.clear
                .frame(height: 286)
                .overlay {
                    if let u = coverURL, !u.isEmpty {
                        CoverView(url: u, corner: 0, maxPixelSize: 320)
                            .frame(maxWidth: .infinity).frame(height: 380)
                            .blur(radius: 22, opaque: true).saturation(1.15)
                    } else {
                        LinearGradient(colors: [cloth.0, cloth.1], startPoint: .top, endPoint: .bottom)
                    }
                }
                .clipped()
                .overlay(
                LinearGradient(
                    stops: [
                        .init(color: .black.opacity(0.32), location: 0),
                        .init(color: .black.opacity(0.10), location: 0.36),
                        .init(color: STVTheme.bg.opacity(0.84), location: 0.78),
                        .init(color: STVTheme.bg, location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .allowsHitTesting(false)

            HStack(alignment: .bottom, spacing: 16) {
                WebCover(url: coverURL, title: title, author: author, corner: 11)
                    .frame(width: 106, height: 150)
                    .shadow(color: .black.opacity(0.36), radius: 14, y: 12)
                VStack(alignment: .leading, spacing: 0) {
                    Menu { sourceMenuContent } label: {
                        HStack(spacing: 4) {
                            Text(allItems.count > 1 ? "\(item.sourceName) · \(allItems.count) 源" : item.sourceName)
                                .font(.system(size: 11.5, weight: .semibold)).foregroundStyle(STVTheme.accent).lineLimit(1)
                            Image(systemName: "chevron.down").font(.system(size: 9.5, weight: .bold)).foregroundStyle(STVTheme.accent)
                        }
                        .padding(.horizontal, 9).frame(height: 23)
                        .background(STVTheme.accentSoft, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                    }
                    Text(title)
                        .font(.system(size: 23, weight: .heavy))
                        .foregroundStyle(STVTheme.text)
                        .lineLimit(2)
                        .padding(.top, 8)
                    if !author.isEmpty || !tags.isEmpty {
                        HStack(spacing: 0) {
                            if !author.isEmpty {
                                Text(author).font(.system(size: 13)).foregroundStyle(STVTheme.secondary).lineLimit(1)
                            }
                            let kindText = tags.prefix(2).joined(separator: " · ")
                            if !kindText.isEmpty {
                                Text(author.isEmpty ? kindText : " · " + kindText)
                                    .font(.system(size: 13)).foregroundStyle(STVTheme.secondary).lineLimit(1)
                            }
                        }
                        .padding(.top, 6)
                    }
                }
                .padding(.bottom, 2)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 18).padding(.bottom, 12)
        }
        .frame(height: 286)
    }

    private var metricsStrip: some View {
        HStack(spacing: 0) {
            metricCell(value: chapterCount > 0 ? "\(chapterCount)" : (loading ? "…" : "—"), unit: chapterCount > 0 ? "章" : "", label: "目录总章")
            STVTheme.hairline.frame(width: 1, height: 26)
            metricCell(value: "\(max(allItems.count, 1))", unit: "个", label: "可用书源")
            STVTheme.hairline.frame(width: 1, height: 26)
            metricCell(
                value: source?.bookSourceType == 1 ? "有声" : source?.bookSourceType == 4 ? "视频" : "\(cachedChapters)",
                unit: isMedia ? "" : "章",
                label: isMedia ? "源类型" : "离线已缓"
            )
        }
        .padding(.vertical, 13)
        .dsCard(18)
        .padding(.horizontal, 16)
    }

    private func metricCell(value: String, unit: String, label: String) -> some View {
        VStack(spacing: 2) {
            HStack(alignment: .lastTextBaseline, spacing: 2) {
                Text(value).font(.system(size: 17, weight: .bold)).foregroundStyle(STVTheme.text).lineLimit(1)
                if !unit.isEmpty { Text(unit).font(.system(size: 11.5, weight: .medium)).foregroundStyle(STVTheme.secondary) }
            }
            Text(label).font(.system(size: 11)).foregroundStyle(STVTheme.tertiary)
        }
        .frame(maxWidth: .infinity)
    }

    private var actionRow: some View {
        HStack(spacing: 10) {
            Button { read(open: true) } label: {
                PillButtonLabel(title: readLabel, icon: "play.fill", tone: .ink, height: 48, expand: true)
            }
            .buttonStyle(PressScale(scale: 0.98))
            .disabled(!canOpen)
            .opacity(canOpen ? 1 : 0.45)
            .accessibilityIdentifier("detail.read")

            roundAction(icon: onShelf == nil ? "bookmark" : "checkmark", active: onShelf != nil, enabled: canOpen && onShelf == nil && !isMedia, size: 48) {
                read(open: false)
            }
            .accessibilityLabel(onShelf == nil ? "加入书架" : "已在书架")
            .accessibilityIdentifier("detail.add")

            roundAction(icon: "arrow.down.to.line", active: false, enabled: canOpen && !isMedia, size: 48) {
                downloadBook = onShelf ?? store.addWeb(item, info: info, toc: toc ?? [])
            }
            .accessibilityLabel("下载")
            .accessibilityIdentifier("detail.download")
        }
    }

    private func roundAction(icon: String, active: Bool, enabled: Bool, size: CGFloat, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(active ? STVTheme.accent : STVTheme.text)
                .frame(width: size, height: size)
                .background(Circle().fill(active ? STVTheme.accentSoft : STVTheme.glass))
                .overlay(Circle().strokeBorder(active ? STVTheme.accentLine : STVTheme.hairline, lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(PressScale())
        .disabled(!enabled)
        .opacity(enabled || active ? 1 : 0.42)
    }

    private func findSources() async {
        guard !findingSources else { return }
        findingSources = true
        defer { findingSources = false }
        let target = item
        let list = LegadoSourceStore.shared.enabled.filter { $0.id != target.sourceURL && !($0.searchUrl ?? "").isEmpty }
        await withTaskGroup(of: [LegadoSearchItem].self) { group in
            var iterator = list.makeIterator()
            func enqueue() {
                guard let source = iterator.next() else { return }
                group.addTask { (try? await LegadoClient.run { try LegadoClient.search(source, key: target.name) }) ?? [] }
            }
            for _ in 0..<4 { enqueue() }
            for await results in group {
                if Task.isCancelled { group.cancelAll(); return }
                let matched = results.filter { $0.name == target.name && (target.author.isEmpty || $0.author.isEmpty || $0.author == target.author) }
                for match in matched where !allItems.contains(where: { $0.id == match.id }) { alternatives.append(match) }
                enqueue()
            }
        }
        if !Task.isCancelled, alternatives.isEmpty { error = "暂未找到可用的同名书籍来源，可在书源管理中启用更多源后重试" }
    }

    private func load() async {
        guard let source else { error = "书源已删除"; return }
        loading = true; error = nil; info = nil; toc = nil
        let it = item
        let token = UUID(); loadGeneration = token
        do {
            let i = try await LegadoClient.run { try LegadoClient.info(source, bookURL: it.bookURL) }
            guard token == loadGeneration, !Task.isCancelled else { return }
            info = i
            let book: [String: String] = ["name": i.name, "author": i.author, "bookUrl": it.bookURL]
            let t = try await LegadoClient.run { try LegadoClient.toc(source, tocURL: i.tocURL, book: book) }
            guard token == loadGeneration, !Task.isCancelled else { return }
            if t.isEmpty { error = "这本书没有可读目录，可能已下架、受限或书源失效。可换一本书或切换书源后重试。" } else { toc = t }
        } catch {
            if token == loadGeneration, !Task.isCancelled { self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription }
        }
        if token == loadGeneration { loading = false }
    }

    private func read(open: Bool, chapter: Int? = nil) {
        if source?.isMedia == true { if open { mediaChapter = chapter ?? 0; showingMedia = true }; return }
        if let saved = onShelf, let toc, !toc.isEmpty, saved.chapterURLs != toc.map(\.url) { store.updateToc(saved, toc) }
        let b = onShelf ?? store.addWeb(item, info: info, toc: toc ?? [])
        if open {
            if let old = resumeFrom, old.id != b.id { store.transferProgress(from: old, to: b) }
            if let chapter, b.chapters.indices.contains(chapter) { store.record(b, chapter: chapter, offset: 0) }
            if let onRead { onRead(b) } else { reading = b }
        }
    }
}

private extension String { var novelNonEmpty: String? { isEmpty ? nil : self } }
