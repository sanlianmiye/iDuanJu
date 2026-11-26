import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Non-scrolling text view: renders a page exactly as TextKit paginated it (and sizes itself in scroll mode).
struct NovelTextView: UIViewRepresentable {
    var text: NSAttributedString

    func makeUIView(context: Context) -> UITextView {
        let v = UITextView()
        v.isEditable = false
        v.isSelectable = false
        v.isScrollEnabled = false
        v.backgroundColor = .clear
        v.textContainerInset = .zero
        v.textContainer.lineFragmentPadding = 0
        v.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return v
    }

    func updateUIView(_ v: UITextView, context: Context) {
        if v.attributedText != text { v.attributedText = text }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        let w = proposal.width ?? 320
        if proposal.height != nil, proposal.height != .infinity { return CGSize(width: w, height: proposal.height!) }
        return CGSize(width: w, height: ceil(uiView.sizeThatFits(CGSize(width: w, height: .greatestFiniteMagnitude)).height))
    }
}

// MARK: - Shelf

/// 小说 tab: unified STVTheme shelf — one atmospheric continue hero, borderless quick strip, open 3-column grid.
struct NovelHomeView: View {
    var embedded = false
    @ObservedObject private var store = NovelStore.shared
    private enum ShelfFilter: String, CaseIterable { case all = "全部", reading = "在读", local = "本地" }

    @State private var filter: ShelfFilter = .all
    @State private var importing = false
    @State private var busy = false
    @State private var errors: [String] = []
    @State private var reading: NovelBook?
    @State private var detailBook: NovelBook?
    @State private var deleting: NovelBook?
    @State private var editingShelf = false
    @State private var selectedBookIDs: Set<String> = []
    @State private var confirmBatchDelete = false
    @State private var searching = false
    @State private var managing = false
    @State private var exploring = false
    @State private var importSheet = false
    @State private var sourceImport = false

    @Environment(\.layoutMetrics) private var layout
    private var cols: [GridItem] { layout.posterGridItems(spacing: 12, alignment: .top) }

    private var filteredShelf: [NovelBook] {
        switch filter {
        case .all: store.shelf
        case .reading: store.shelf.filter { store.fraction($0) != nil }
        case .local: store.shelf.filter { !$0.isWeb }
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let b = store.shelf.first(where: { store.fraction($0) != nil }) ?? store.shelf.first {
                    continueHero(b).padding(.top, 16).transition(.opacity)
                }
                quickStrip.padding(.top, store.books.isEmpty ? 16 : 16)
                shelfHeader.padding(.top, 16)
                if editingShelf && !filteredShelf.isEmpty {
                    let allSelected = filteredShelf.allSatisfy { selectedBookIDs.contains($0.id) }
                    HStack {
                        Button(allSelected ? "取消全选" : "全选 (\(filteredShelf.count))") {
                            selectedBookIDs = allSelected ? [] : Set(filteredShelf.map(\.id))
                        }
                        .font(.system(size: 13, weight: .semibold)).foregroundStyle(STVTheme.accent)
                        Spacer()
                        Button(selectedBookIDs.isEmpty ? "点击书籍勾选" : "删除所选 (\(selectedBookIDs.count))") {
                            confirmBatchDelete = true
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(selectedBookIDs.isEmpty ? STVTheme.secondary : STVTheme.accentWarm)
                        .disabled(selectedBookIDs.isEmpty)
                    }
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(STVTheme.surface, in: RoundedRectangle(cornerRadius: 12))
                    .overlay { RoundedRectangle(cornerRadius: 12).strokeBorder(STVTheme.glassBorder) }
                    .padding(.horizontal, 16).padding(.top, 10)
                }
                if store.books.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "books.vertical").font(.system(size: 30)).foregroundStyle(STVTheme.tertiary)
                        Text("书架是空的").font(.system(size: 16, weight: .semibold)).foregroundStyle(STVTheme.text)
                        Text("「导入」本地 TXT / EPUB，或导入「阅读」书源后「搜书」。")
                            .font(.system(size: 13)).foregroundStyle(STVTheme.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 44).padding(.horizontal, 24)
                } else if filteredShelf.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "books.vertical").font(.system(size: 28)).foregroundStyle(STVTheme.tertiary)
                        Text(filter == .local ? "暂无本地书籍" : "暂无阅读记录")
                            .font(.system(size: 15, weight: .semibold)).foregroundStyle(STVTheme.text)
                        Text(filter == .local ? "点击上方「导入」选择本地 TXT / EPUB 文件。" : "打开书架里的任意书籍开始阅读，进度会自动同步到这里。")
                            .font(.system(size: 13)).foregroundStyle(STVTheme.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 36).padding(.horizontal, 24)
                } else {
                    LazyVGrid(columns: cols, spacing: 16) {
                        ForEach(filteredShelf) { b in
                            Button {
                                if editingShelf {
                                    if selectedBookIDs.contains(b.id) { selectedBookIDs.remove(b.id) } else { selectedBookIDs.insert(b.id) }
                                } else if b.isWeb {
                                    detailBook = b
                                } else {
                                    reading = b
                                }
                            } label: { bookCell(b) }
                                .buttonStyle(PressScale(scale: 0.97))
                                .contextMenu { Button("删除", systemImage: "trash", role: .destructive) { deleting = b } }
                                .accessibilityIdentifier("novel.book.\(b.title)")
                        }
                    }
                    .padding(.horizontal, 16).padding(.top, 12)
                }
            }
            .padding(.bottom, 108)
            .animation(DS.panel, value: store.books.count)
            .animation(DS.select, value: filter)
        }
        .scrollIndicators(.hidden)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.plainText, .text, .epub, .item], allowsMultipleSelection: true) { r in
            guard case .success(let urls) = r, !urls.isEmpty else { return }
            busy = true
            Task { errors = await store.importFiles(urls); busy = false }
        }
        .alert("部分文件导入失败", isPresented: Binding(get: { !errors.isEmpty }, set: { if !$0 { errors = [] } })) {
            Button("好") { errors = [] }
        } message: { Text(errors.joined(separator: "\n")) }
        .confirmationDialog("从书架删除「\(deleting?.title ?? "")」？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("删除", role: .destructive) { if let b = deleting { store.remove(b) }; deleting = nil }
        } message: { Text("会同时删除阅读进度和本机保存的文件。") }
        .confirmationDialog("从书架删除所选的 \(selectedBookIDs.count) 本书？", isPresented: $confirmBatchDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                let ids = selectedBookIDs
                for b in store.books where ids.contains(b.id) { store.remove(b) }
                selectedBookIDs = []
                editingShelf = false
            }
        } message: { Text("会同时删除阅读进度和本机保存的文件。") }
        .fullScreenCover(item: $reading) { b in NovelReaderView(book: store.book(b.id) ?? b) }
        .fullScreenCover(item: $detailBook) { book in
            if let source = book.sourceURL, let url = book.bookURL {
                NavigationStack {
                    NovelWebDetailView(items: [LegadoSearchItem(name: book.title, author: book.author ?? "", kind: "", intro: "", cover: book.coverURL ?? "", latest: "", bookURL: url, sourceURL: source, sourceName: LegadoSourceStore.shared.source(source)?.bookSourceName ?? "当前书源")])
                }
            }
        }
        .fullScreenCover(isPresented: $searching) { NovelSearchView() }
        .fullScreenCover(isPresented: $managing) { NovelSourcesView() }
        .fullScreenCover(isPresented: $exploring) { NovelExploreView() }
        .sheet(isPresented: $importSheet) {
            NovelImportSheet(onLocalFiles: {
                importSheet = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { importing = true }
            })
        }
        .task {
            seedFixtureIfNeeded()
            #if DEBUG
            switch ProcessInfo.processInfo.environment["STV_NOVEL_PAGE"] {
            case "search": searching = true
            case "rank": exploring = true
            case "sources": managing = true
            case "import": importSheet = true
            default: break
            }
            #endif
        }
    }

    private func continueHero(_ b: NovelBook) -> some View {
        let f = store.fraction(b)
        let pct = f.map { Int(($0 * 100).rounded()) }
        let p = store.progress[b.id]
        let chapterTitle: String = {
            if let p, b.chapters.indices.contains(p.chapter) { return b.chapters[p.chapter].title }
            return [b.author, b.isWeb ? "书源" : b.format.uppercased()].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
        }()
        let remaining: Int = {
            if let p, b.chapters.count > 0 { return max(0, b.chapters.count - p.chapter - 1) }
            return b.chapters.count
        }()
        let colors = GeneratedBookCover.clothColors(b.title)

        return HStack(spacing: 15) {
            NovelCover(book: b, corner: 9).frame(width: 82, height: 82 / 0.71)
            VStack(alignment: .leading, spacing: 0) {
                Text(pct.map { "最近在读 · \($0)%" } ?? "开始阅读")
                    .font(.system(size: 11.5, weight: .bold))
                    .foregroundStyle(Color(red: 0.949, green: 0.710, blue: 0.361))
                Text(b.title)
                    .font(.system(size: 19, weight: .heavy))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .padding(.top, 4)
                if !chapterTitle.isEmpty {
                    Text(chapterTitle)
                        .font(.system(size: 12.5))
                        .foregroundStyle(Color.white.opacity(0.74))
                        .lineLimit(1)
                        .padding(.top, 3)
                }
                HStack(spacing: 8) {
                    GeometryReader { g in
                        Capsule().fill(Color.white.opacity(0.20))
                            .overlay(alignment: .leading) {
                                Capsule().fill(Color(red: 0.541, green: 0.706, blue: 1.0))
                                    .frame(width: g.size.width * min(max(f ?? 0, 0), 1))
                            }
                    }
                    .frame(height: 3)
                    Text(pct != nil && remaining > 0 ? "余 \(remaining) 章" : "共 \(b.chapters.count) 章")
                        .font(.system(size: 11))
                        .foregroundStyle(Color.white.opacity(0.70))
                }
                .padding(.top, 10)
                Button { reading = b } label: {
                    PillButtonLabel(title: f == nil ? "开始阅读" : "继续阅读", icon: "play.fill", tone: .white, small: true)
                }
                .buttonStyle(PressScale())
                .padding(.top, 12)
                .accessibilityIdentifier("novel.continue")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 15)
        .background {
            Color.clear.overlay {
                ZStack {
                    LinearGradient(colors: [colors.0, colors.1, Color(red: 0.07, green: 0.09, blue: 0.13)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    if b.hasCover, let img = UIImage(contentsOfFile: NovelStore.coverURL(b.id).path) {
                        Image(uiImage: img).resizable().scaledToFill().blur(radius: 22, opaque: true).opacity(0.65)
                    } else if let u = b.coverURL, let url = URL(string: u) {
                        AsyncImage(url: url) { phase in
                            if let img = phase.image { img.resizable().scaledToFill().blur(radius: 22, opaque: true).opacity(0.65) }
                        }
                    }
                    LinearGradient(
                        stops: [
                            .init(color: Color(red: 0.067, green: 0.09, blue: 0.14).opacity(0.45), location: 0),
                            .init(color: Color(red: 0.067, green: 0.09, blue: 0.14).opacity(0.74), location: 0.55),
                            .init(color: Color(red: 0.055, green: 0.07, blue: 0.11).opacity(0.88), location: 1),
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                }
            }
            .clipped()
        }
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Color.white.opacity(0.12), lineWidth: 1))
        .shadow(color: STVTheme.barShadow, radius: 14, y: 10)
        .padding(.horizontal, 16)
    }

    private var quickStrip: some View {
        VStack(spacing: 14) {
            HStack(spacing: 0) {
                quickOpen("搜书", "magnifyingglass", id: "novel.search") { searching = true }
                quickOpen("排行榜", "chart.bar.fill", id: "novel.rank") { exploring = true }
                quickOpen("书源", "books.vertical", id: "novel.sources") { managing = true }
                quickOpen("导入", "square.and.arrow.down", id: "novel.import") { importSheet = true }
            }
            Hairline()
        }
        .padding(.horizontal, 16)
    }

    private func quickOpen(_ title: String, _ icon: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(STVTheme.text)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(STVTheme.glass))
                Text(title).font(.system(size: 12, weight: .medium)).foregroundStyle(STVTheme.secondary)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale(scale: 0.95))
        .accessibilityIdentifier(id)
    }

    private var shelfHeader: some View {
        HStack(alignment: .center, spacing: 8) {
            Text("我的书架").font(.system(size: 18, weight: .bold)).foregroundStyle(STVTheme.text)
            if !store.books.isEmpty {
                Text("\(store.books.count) 本").font(.system(size: 13)).foregroundStyle(STVTheme.tertiary)
            }
            Spacer(minLength: 0)
            if busy {
                ProgressView().controlSize(.small)
            } else if !store.books.isEmpty {
                HStack(spacing: 4) {
                    if !editingShelf {
                        ForEach(ShelfFilter.allCases, id: \.self) { f in
                            let on = f == filter
                            Button { withAnimation(DS.select) { filter = f } } label: {
                                Text(f.rawValue)
                                    .font(.system(size: 12, weight: on ? .semibold : .regular))
                                    .foregroundStyle(on ? STVTheme.accent : STVTheme.secondary)
                                    .padding(.horizontal, 9).frame(height: 24)
                                    .background(Capsule().fill(on ? STVTheme.accentSoft : .clear))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    Button {
                        withAnimation(DS.select) {
                            editingShelf.toggle()
                            if !editingShelf { selectedBookIDs = [] }
                        }
                    } label: {
                        Text(editingShelf ? "完成" : "管理")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(editingShelf ? STVTheme.accent : STVTheme.text)
                            .padding(.horizontal, 10).frame(height: 24)
                            .background(Capsule().fill(editingShelf ? STVTheme.accentSoft : STVTheme.glass))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.horizontal, 16)
    }

    private func bookCell(_ b: NovelBook) -> some View {
        let f = store.fraction(b)
        let badge = b.isWeb ? nil : b.format.uppercased()
        let stat: String = {
            if let f {
                let p = Int((f * 100).rounded())
                return b.isWeb ? "已读 \(p)%" : "本地 · \(p)%"
            }
            if !b.isWeb { return "本地 · 未读" }
            return b.chapters.isEmpty ? "未读" : "未读 · \(b.chapters.count) 章"
        }()
        return VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topTrailing) {
                NovelCover(book: b, corner: 9, badge: badge, progress: f).aspectRatio(0.71, contentMode: .fit)
                if editingShelf {
                    let checked = selectedBookIDs.contains(b.id)
                    Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(checked ? STVTheme.accent : .white)
                        .background(Circle().fill(checked ? .white : Color.black.opacity(0.45)))
                        .padding(6)
                }
            }
            Text(b.title).font(.system(size: 13.5, weight: .semibold)).foregroundStyle(STVTheme.text).lineLimit(1).padding(.top, 7)
            Text(stat).font(.system(size: 11.5)).foregroundStyle(STVTheme.tertiary).lineLimit(1).padding(.top, 2)
        }
    }

    /// UI tests: `STV_NOVEL_SEED=/abs/file.txt` imports that file once into an empty shelf;
    /// `STV_LEGADO_FIXTURE=/abs/dir` serves a fake book site from `routes.json` and imports its `sources.json`.
    private func seedFixtureIfNeeded() {
        #if DEBUG
        if let f = ProcessInfo.processInfo.environment["STV_LEGADO_IMPORT"], let d = FileManager.default.contents(atPath: f),
        let list = try? LegadoSourceStore.parse(d) {
            LegadoSourceStore.shared.remove(Set(LegadoSourceStore.shared.sources.map(\.id)))
            LegadoSourceStore.shared.add(list)
        }
        let preview = ProcessInfo.processInfo.environment["STV_UI_PREVIEW"] == "1"
        let requested = preview ? Bundle.main.resourceURL?.appendingPathComponent("ui_novel_preview").path : ProcessInfo.processInfo.environment["STV_LEGADO_FIXTURE"]
        let fixtureDir = requested.map { FileManager.default.fileExists(atPath: $0) ? $0 : (Bundle.main.resourceURL?.appendingPathComponent("ui_legado").path ?? $0) }
        if let dir = fixtureDir, !dir.isEmpty,
        let rd = FileManager.default.contents(atPath: dir + "/routes.json"),
        let routes = (try? JSONSerialization.jsonObject(with: rd)) as? [String: String] {
            LegadoHTTP.transport = { req in
                let u = req.url!.absoluteString
                guard let f = routes[u], let d = FileManager.default.contents(atPath: dir + "/" + f) else {
                    return (Data(), HTTPURLResponse(url: req.url!, statusCode: 404, httpVersion: nil, headerFields: nil)!)
                }
                Thread.sleep(forTimeInterval: 0.2)
                return (d, HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "text/html; charset=utf-8"])!)
            }
            if ProcessInfo.processInfo.environment["STV_LEGADO_FIXTURE_IMPORT"] != "0",
            let sd = FileManager.default.contents(atPath: dir + "/sources.json"), let list = try? LegadoSourceStore.parse(sd) {
                LegadoSourceStore.shared.remove(Set(LegadoSourceStore.shared.sources.map(\.id)))
                LegadoSourceStore.shared.add(list)
            }
        }
        if preview {
            LegadoSourceState.setLoginInfo("https://a.source.invalid", ["离线合成状态": "界面验收"])
        }
        let requestedBook = ProcessInfo.processInfo.environment["STV_NOVEL_SEED"]
        let seedPath = requestedBook.map { FileManager.default.fileExists(atPath: $0) ? $0 : (Bundle.main.url(forResource: "novel_sample_gbk", withExtension: "txt")?.path ?? $0) }
        guard let p = seedPath, !p.isEmpty,
        let data = FileManager.default.contents(atPath: p),
        let parsed = try? NovelImport.parse(data, fileName: (p as NSString).lastPathComponent),
        !store.books.contains(where: { $0.title == parsed.title }) else { return }
        try? store.add(parsed, format: (p as NSString).pathExtension)
        #endif
    }
}

/// Physical book spine highlight + crease + inner rim shared by shelf, search, detail and rank covers.
struct BookSpineOverlay: View {
    var corner: CGFloat = 9
    var body: some View {
        ZStack {
            LinearGradient(
                stops: [
                    .init(color: .white.opacity(0.24), location: 0),
                    .init(color: .white.opacity(0.06), location: 0.03),
                    .init(color: .black.opacity(0.24), location: 0.055),
                    .init(color: .white.opacity(0.10), location: 0.075),
                    .init(color: .clear, location: 0.12),
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
            RoundedRectangle(cornerRadius: corner, style: .continuous)
                .strokeBorder(Color.white.opacity(0.18), lineWidth: 0.6)
        }
        .allowsHitTesting(false)
    }
}

struct NovelCover: View {
    let book: NovelBook
    var corner: CGFloat = 9
    var badge: String? = nil
    var progress: Double? = nil

    var body: some View {
        Color.clear
            .overlay {
                if book.hasCover, let img = UIImage(contentsOfFile: NovelStore.coverURL(book.id).path) {
                    Image(uiImage: img).resizable().scaledToFill()
                } else if let u = book.coverURL, let url = URL(string: u) {
                    AsyncImage(url: url) { phase in
                        if let img = phase.image { img.resizable().scaledToFill() }
                        else { GeneratedBookCover(title: book.title, author: book.author) }
                    }
                } else {
                    GeneratedBookCover(title: book.title, author: book.author)
                }
            }
            .overlay { BookSpineOverlay(corner: corner) }
        .overlay(alignment: .topTrailing) {
            if let badge, !badge.isEmpty {
                Text(badge)
                    .font(.system(size: 9.5, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 5)
                    .frame(height: 17)
                    .background(Color.black.opacity(0.58), in: RoundedRectangle(cornerRadius: 5, style: .continuous))
                    .padding(5)
            }
        }
        .overlay(alignment: .bottom) {
            if let progress {
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Color.black.opacity(0.36)
                        Color(red: 0.541, green: 0.706, blue: 1.0)
                            .frame(width: g.size.width * min(max(progress, 0.02), 1))
                    }
                }
                .frame(height: 3)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        .shadow(color: .black.opacity(0.24), radius: 7, y: 4)
    }
}

/// Clothbound editorial fallback cover (GeneratedBookCover v2) synced with Android's clothPalettes.
struct GeneratedBookCover: View {
    let title: String
    var author: String? = nil

    private static let palettes: [(UInt32, UInt32)] = [
        (0x29394F, 0x131C29),
        (0x3A2C46, 0x1B1424),
        (0x233B46, 0x0F1E24),
        (0x3A302B, 0x1D1714),
        (0x283E35, 0x12201A),
        (0x472C33, 0x221217),
        (0x2F3744, 0x161B23),
        (0x3F3626, 0x1F1A11),
    ]

    /// Java `String.hashCode`, so a title gets the same cloth tone as on Android.
    static func clothColors(_ title: String) -> (Color, Color) {
        var h: Int32 = 0
        for u in title.utf16 { h = h &* 31 &+ Int32(u) }
        let p = palettes[Int(((h % Int32(palettes.count)) + Int32(palettes.count)) % Int32(palettes.count))]
        return (Color(hex: p.0), Color(hex: p.1))
    }

    var body: some View {
        GeometryReader { g in
            let w = max(g.size.width, 1), h = max(g.size.height, 1), small = w < 74
            let inset: CGFloat = small ? 5 : 7
            let leftInset: CGFloat = small ? 8 : 11
            let c = Self.clothColors(title)
            ZStack(alignment: .top) {
                LinearGradient(colors: [c.0, c.1], startPoint: .topLeading, endPoint: .bottom)
                RadialGradient(
                    stops: [.init(color: Color(red: 0.86, green: 0.91, blue: 0.98).opacity(0.14), location: 0), .init(color: .clear, location: 0.65)],
                    center: UnitPoint(x: 0.8, y: 0.16),
                    startRadius: 0,
                    endRadius: w * 0.9
                )
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .strokeBorder(Color(red: 0.93, green: 0.95, blue: 0.98).opacity(0.24), lineWidth: 0.75)
                    .padding(.top, inset).padding(.bottom, inset).padding(.leading, leftInset).padding(.trailing, inset)
                Text(title)
                    .font(.custom("STSongti-SC-Bold", size: small ? 9.5 : 14))
                    .tracking(small ? 0.2 : 1)
                    .lineSpacing(small ? 1 : 2.5)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    .foregroundStyle(Color(red: 0.93, green: 0.95, blue: 0.97))
                    .padding(.leading, leftInset + 3)
                    .padding(.trailing, inset + 3)
                    .padding(.top, h * 0.24)
                    .frame(width: w)
                if !small, let author, !author.isEmpty {
                    Text(author)
                        .font(.system(size: 9.5))
                        .foregroundStyle(Color(red: 0.93, green: 0.95, blue: 0.97).opacity(0.66))
                        .lineLimit(1)
                        .padding(.leading, leftInset)
                        .padding(.trailing, inset)
                        .frame(width: w, height: h - 12, alignment: .bottom)
                }
            }
            .frame(width: w, height: h)
        }
    }
}
