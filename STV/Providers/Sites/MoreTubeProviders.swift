import Foundation

// YouPorn / RedTube / HQPorner. Opt-in (片源管理, default off), same TubeSafety filter as the other tubes.
// YouPorn and RedTube playervars.mediaDefinitions were cross-checked against yt-dlp (Unlicense);
// the Swift below is an independent regex/JSON implementation. HQPorner list cards and the
// mydaddy.cc embed `<source>` tags were read from those sites' public HTML (2026-11); no third-party code copied.

enum AyloPlay {
    struct Def: Equatable { var format: String; var url: String; var remote: Bool }

    static func definitions(_ html: String, page: String) -> [Def] {
        guard let raw = TubeFmt.balancedArray(after: "\"mediaDefinitions\"", in: html),
              let arr = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [[String: Any]] else { return [] }
        return arr.compactMap { d in
            let format = J.str(d["format"])
            guard format == "hls" || format == "mp4" else { return nil }
            let url = HTML.resolve(page, J.str(d["videoUrl"]))
            guard url.hasPrefix("https://"), !url.contains("premium") else { return nil }
            let remote = J.bool(d["remote"]) || !(url.contains(".m3u8") || url.contains(".mp4"))
            return Def(format: format, url: url, remote: remote)
        }
    }

    static func fromMediaJSON(_ text: String, referer: String, prefix: String) -> [HGStream] {
        let data = Data(text.utf8)
        let rows: [[String: Any]]
        if let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] { rows = arr }
        else if let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] { rows = [o] }
        else { return [] }
        return rows.compactMap { d in
            let format = J.str(d["format"])
            guard format == "hls" || format == "mp4" else { return nil }
            return stream(url: HTML.resolve(referer, J.str(d["videoUrl"])), format: format, height: J.int(d["height"]), referer: referer, prefix: prefix)
        }
    }

    static func stream(url: String, format: String, height: Int, referer: String, prefix: String) -> HGStream? {
        guard url.hasPrefix("https://"), !url.contains("premium"), url.contains(".m3u8") || url.contains(".mp4") else { return nil }
        let hls = format == "hls" || url.contains(".m3u8")
        let h = height > 0 ? height : nil
        let kind: MediaKind = hls ? .hls : .mp4Clear
        let label = hls ? "HLS" : "MP4"
        return HGStream(streamId: "\(prefix)-\(hls ? "hls" : "mp4")-\(height)",
                        quality: h.map { "\($0)p · \(label)" } ?? "自动 · \(label)",
                        url: url, key: "", height: h, kind: kind,
                        headers: ["Referer": referer, "User-Agent": TubeFmt.desktopUA])
    }

    static func ranked(_ items: [HGStream]) -> [HGStream] {
        items.sorted { a, b in
            let ah = a.kind == .hls ? 1 : 0, bh = b.kind == .hls ? 1 : 0
            if ah != bh { return ah > bh }
            return (a.height ?? 0) > (b.height ?? 0)
        }
    }

    static func streams(_ html: String, page: String, referer: String, prefix: String) async -> [HGStream] {
        var out: [HGStream] = []
        var seen = Set<String>()
        func add(_ s: HGStream) { if seen.insert(s.url).inserted { out.append(s) } }
        for d in definitions(html, page: page) {
            if !d.remote, let s = stream(url: d.url, format: d.format, height: 0, referer: referer, prefix: prefix) { add(s); continue }
            guard d.remote else { continue }
            guard let (text, status, _) = try? await SiteHTTP.shared.raw(d.url, referer: referer, headers: [
                "User-Agent": TubeFmt.desktopUA, "Accept": "application/json", "Cookie": "age_verified=1",
            ]), (200..<300).contains(status) else { continue }
            for s in fromMediaJSON(text, referer: referer, prefix: prefix) { add(s) }
        }
        return ranked(out)
    }
}

struct YouPornProvider: SiteProvider {
    let source = DramaSource.youporn
    static let mirrors = ["https://www.youporn.com"]
    static let headers = ["User-Agent": TubeFmt.desktopUA, "Cookie": "age_verified=1"]
    let categories: [SiteCategory] = [
        .init(id: "home", title: "推荐", path: "/|/?page={p}"),
        .init(id: "rated", title: "好评", path: "/top_rated/|/top_rated/?page={p}", isRank: true),
        .init(id: "viewed", title: "最多观看", path: "/most_viewed/|/most_viewed/?page={p}", isRank: true),
    ]

    private func get(_ path: String, ttl: TimeInterval = 120) async throws -> SiteHTTP.Response {
        try await SiteHTTP.shared.get(source, mirrors: Self.mirrors, path: path, headers: Self.headers, ttl: ttl)
    }
    func list(_ c: SiteCategory, page: Int) async throws -> HGPage {
        Self.parseList(try await get(TubePath.url(c.path, page: page)).text, page: page)
    }
    func search(_ q: String, page: Int) async throws -> HGPage {
        let path = "/search/?query=\(TubePath.q(q))|/search/?query=\(TubePath.q(q))&page={p}"
        return Self.parseList(try await get(TubePath.url(path, page: page)).text, page: page)
    }
    static func parseList(_ h: String, page: Int) -> HGPage {
        var items: [HGSeries] = [], seen = Set<String>()
        for chunk in h.components(separatedBy: "data-video-id=\"").dropFirst() {
            guard let id = HTML.groups("^(\\d+)\"", chunk)?[1], seen.insert(id).inserted else { continue }
            let card = String(chunk.prefix(12000))
            let title = HTML.unescape(HTML.groups("aria-label=\"([^\"]+)\"", card)?[1]
                                      ?? HTML.groups("\\balt=\"([^\"]+)\"", card)?[1] ?? "")
            guard !title.isEmpty, TubeSafety.allowed(title) else { continue }
            let cover = HTML.unescape(HTML.groups("data-poster=\"(https://[^\"]+)\"", card)?[1]
                                      ?? HTML.groups("data-src=\"(https://[^\"]+\\.(?:jpg|jpeg|webp)[^\"]*)\"", card)?[1] ?? "")
            let dur = HTML.groups("tm_video_duration\">\\s*(?:<span>)?\\s*([0-9:]+)", card).map { SiteParse.clock($0[1]) } ?? 0
            let href = HTML.groups("href=\"(/watch/\(id)/[^\"]*)\"", card)?[1] ?? "/watch/\(id)/"
            items.append(HGSeries(source: .youporn, seriesId: id, title: title, cover: cover, duration: dur,
                                  orientation: "landscape", page: "https://www.youporn.com" + href))
        }
        return HGPage(items: items, hasMore: !items.isEmpty && h.contains("page=\(page + 1)"), nextOffset: page + 1)
    }
    private func watch(_ s: HGSeries) async throws -> SiteHTTP.Response {
        try await get(s.page ?? "/watch/\(s.seriesId)/", ttl: 300)
    }
    func detail(_ s: HGSeries) async throws -> HGSeriesDetail {
        let r = try await watch(s)
        return Self.parseDetail(r.text, series: s, url: r.url)
    }
    static func parseDetail(_ h: String, series s: HGSeries, url: String) -> HGSeriesDetail {
        let title = HTML.unescape(HTML.groups("property=\"og:title\" content=\"([^\"]+)\"", h)?[1] ?? "").nonEmptyOr(s.title)
        let dur = HTML.groups("\"duration\"\\s*:\\s*\"?(\\d+)", h).flatMap { Int($0[1]) } ?? s.duration
        return HGSeriesDetail(source: .youporn, seriesId: s.seriesId, title: title, cover: HTML.ogImage(h).nonEmptyOr(s.cover),
                              desc: "", author: nil, actors: [], onlineTime: 0, totalEps: 1,
                              episodes: SiteParse.singleEpisode(s, page: url, duration: dur),
                              tags: [], episodeLabel: dur > 0 ? Fmt.clock(dur) : "长视频")
    }
    func resolve(_ s: HGSeries, _ ep: HGEpisode) async throws -> HGResolvedVideo {
        let r = try await watch(s)
        let list = await AyloPlay.streams(r.text, page: r.url, referer: "https://www.youporn.com/", prefix: "yp")
        guard let first = list.first else { throw SiteError.parse("YouPorn 页面里没有播放源（可能需要年龄验证或地区受限）") }
        return HGResolvedVideo(vid: s.seriesId, url: first.url, key: nil, duration: Double(s.duration), expiresAt: nil, err: nil, streams: list)
    }
}

struct RedTubeProvider: SiteProvider {
    let source = DramaSource.redtube
    static let mirrors = ["https://www.redtube.com"]
    static let headers = ["User-Agent": TubeFmt.desktopUA, "Cookie": "age_verified=1"]
    let categories: [SiteCategory] = [
        .init(id: "hot", title: "热门", path: "/hot|/hot?page={p}", isRank: true),
        .init(id: "viewed", title: "最多观看", path: "/mostviewed|/mostviewed?page={p}", isRank: true),
        .init(id: "top", title: "高分", path: "/top|/top?page={p}", isRank: true),
    ]

    private func get(_ path: String, ttl: TimeInterval = 120) async throws -> SiteHTTP.Response {
        try await SiteHTTP.shared.get(source, mirrors: Self.mirrors, path: path, headers: Self.headers, ttl: ttl)
    }
    func list(_ c: SiteCategory, page: Int) async throws -> HGPage {
        Self.parseList(try await get(TubePath.url(c.path, page: page)).text, page: page)
    }
    func search(_ q: String, page: Int) async throws -> HGPage {
        let path = "/?search=\(TubePath.q(q))|/?search=\(TubePath.q(q))&page={p}"
        return Self.parseList(try await get(TubePath.url(path, page: page)).text, page: page)
    }
    static func parseList(_ h: String, page: Int) -> HGPage {
        var items: [HGSeries] = [], seen = Set<String>()
        // 2026-11: the visible card is `video_block_wrapper`. `data-video-id` also appears in a later
        // blob that does not contain the title / cover, so splitting on it yields empty cards.
        for chunk in h.components(separatedBy: "video_block_wrapper").dropFirst() {
            let card = String(chunk.prefix(8000))
            guard let id = HTML.groups("href=\"/(\\d{5,})\"", card)?[1], seen.insert(id).inserted else { continue }
            let title = HTML.unescape(HTML.groups("title=\"([^\"]+)\"[^>]*video-title-text", card)?[1]
                                      ?? HTML.groups("class=\"[^\"]*video-title-text[^\"]*\"[^>]*>([^<]+)", card)?[1]
                                      ?? HTML.groups("\\balt=\"([^\"]+)\"", card)?[1] ?? "")
            guard !title.isEmpty, TubeSafety.allowed(title) else { continue }
            let cover = HTML.unescape(HTML.groups("data-src=\"(https://[^\\s\"]+)", card)?[1]
                                      ?? HTML.groups("data-srcset=\"(https://[^\\s\"]+)", card)?[1]
                                      ?? HTML.groups("data-(?:poster|thumb|image)=\"(https://[^\\s\"]+)", card)?[1] ?? "")
            let dur = HTML.groups("tm_video_duration\">\\s*([0-9:]+)", card).map { SiteParse.clock($0[1]) } ?? 0
            items.append(HGSeries(source: .redtube, seriesId: id, title: title, cover: cover, duration: dur,
                                  orientation: "landscape", page: "https://www.redtube.com/\(id)"))
        }
        return HGPage(items: items, hasMore: !items.isEmpty && h.contains("page=\(page + 1)"), nextOffset: page + 1)
    }
    private func watch(_ s: HGSeries) async throws -> SiteHTTP.Response {
        try await get(s.page ?? "/\(s.seriesId)", ttl: 300)
    }
    func detail(_ s: HGSeries) async throws -> HGSeriesDetail {
        let r = try await watch(s)
        return Self.parseDetail(r.text, series: s, url: r.url)
    }
    static func parseDetail(_ h: String, series s: HGSeries, url: String) -> HGSeriesDetail {
        let title = HTML.unescape(HTML.groups("property=\"og:title\" content=\"([^\"]+)\"", h)?[1] ?? "").nonEmptyOr(s.title)
        let dur = HTML.groups("\"duration\"\\s*:\\s*\"?(\\d+)", h).flatMap { Int($0[1]) } ?? s.duration
        return HGSeriesDetail(source: .redtube, seriesId: s.seriesId, title: title, cover: HTML.ogImage(h).nonEmptyOr(s.cover),
                              desc: "", author: nil, actors: [], onlineTime: 0, totalEps: 1,
                              episodes: SiteParse.singleEpisode(s, page: url, duration: dur),
                              tags: [], episodeLabel: dur > 0 ? Fmt.clock(dur) : "长视频")
    }
    func resolve(_ s: HGSeries, _ ep: HGEpisode) async throws -> HGResolvedVideo {
        let r = try await watch(s)
        let list = await AyloPlay.streams(r.text, page: r.url, referer: "https://www.redtube.com/", prefix: "rt")
        guard let first = list.first else { throw SiteError.parse("RedTube 页面里没有播放源（可能需要年龄验证或地区受限）") }
        return HGResolvedVideo(vid: s.seriesId, url: first.url, key: nil, duration: Double(s.duration), expiresAt: nil, err: nil, streams: list)
    }
}

struct HQPornerProvider: SiteProvider {
    let source = DramaSource.hqporner
    static let mirrors = ["https://hqporner.com"]
    static let headers = ["User-Agent": TubeFmt.desktopUA]
    let categories: [SiteCategory] = [
        .init(id: "new", title: "最新", path: "/|/hdporn/{p}"),
        .init(id: "top", title: "热门总榜", path: "/top|/top/{p}", isRank: true),
        .init(id: "week", title: "本周热门", path: "/top/week|/top/week/{p}", isRank: true),
        .init(id: "month", title: "本月热门", path: "/top/month|/top/month/{p}", isRank: true),
    ]

    private func get(_ path: String, ttl: TimeInterval = 120) async throws -> SiteHTTP.Response {
        try await SiteHTTP.shared.get(source, mirrors: Self.mirrors, path: path, headers: Self.headers, ttl: ttl)
    }
    func list(_ c: SiteCategory, page: Int) async throws -> HGPage {
        Self.parseList(try await get(TubePath.url(c.path, page: page)).text, page: page)
    }
    func search(_ q: String, page: Int) async throws -> HGPage {
        let path = "/?q=\(TubePath.q(q))|/?q=\(TubePath.q(q))&p={p}"
        return Self.parseList(try await get(TubePath.url(path, page: page)).text, page: page)
    }

    static func clock(_ s: String) -> Int {
        guard let m = HTML.groups("(\\d+)\\s*m(?:\\s+(\\d+)\\s*s)?", s) else { return TubeFmt.duration(s) }
        return (Int(m[1]) ?? 0) * 60 + (Int(m[2]) ?? 0)
    }
    static func slugTitle(_ slug: String) -> String {
        slug.replacingOccurrences(of: "---", with: " ").replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ")
    }
    static func parseList(_ h: String, page: Int) -> HGPage {
        var items: [HGSeries] = [], seen = Set<String>()
        for chunk in h.components(separatedBy: "<section class=\"box feature\">").dropFirst() {
            let card = String(chunk.prefix(8000))
            guard let link = HTML.groups("href=\"/hdporn/(\\d+)-([^\"]+)\\.html\"", card), seen.insert(link[1]).inserted else { continue }
            let texts = HTML.all(">([^<]{3,})<", card).map { HTML.cleanText($0[1]) }.filter { t in
                !t.isEmpty && HTML.groups("^\\d+\\s*m(?:\\s+\\d+\\s*s)?$", t) == nil
            }
            let title = texts.first ?? slugTitle(link[2])
            guard TubeSafety.allowed(title) else { continue }
            let dur = HTML.groups("(\\d+\\s*m(?:\\s+\\d+\\s*s)?)", card).map { clock($0[1]) } ?? 0
            let cover = HTML.resolve("https://hqporner.com/", HTML.groups("defaultImage\\([\"']([^\"']+)[\"']", card)?[1] ?? "")
            items.append(HGSeries(source: .hqporner, seriesId: link[1], title: title, cover: cover, duration: dur,
                                  orientation: "landscape", page: "https://hqporner.com/hdporn/\(link[1])-\(link[2]).html"))
        }
        let next = page + 1
        let more = h.contains("/\(next)\"") || h.contains("p=\(next)") || h.contains("page=\(next)")
        return HGPage(items: items, hasMore: !items.isEmpty && more, nextOffset: next)
    }
    static func embedURL(_ html: String) -> String? {
        guard let id = HTML.groups("//mydaddy\\.cc/video/([0-9a-z]+)/", html)?[1] else { return nil }
        return "https://mydaddy.cc/video/\(id)/"
    }
    static func streams(_ html: String) -> [HGStream] {
        let flat = html.replacingOccurrences(of: "\\\"", with: "\"")
        let hdr = ["Referer": "https://mydaddy.cc/", "User-Agent": TubeFmt.desktopUA]
        var out: [(Int, HGStream)] = []
        var seen = Set<String>()
        for m in HTML.all("<source\\b([^>]*)>", flat) {
            let tag = m[1]
            let src = HTML.resolve("https://mydaddy.cc/", HTML.attr(tag, "src"))
            guard src.hasPrefix("https://"), src.contains(".mp4"), seen.insert(src).inserted else { continue }
            let title = HTML.attr(tag, "title")
            let h = HTML.groups("(\\d{3,4})", title).flatMap { Int($0[1]) } ?? 0
            out.append((h, HGStream(streamId: "hq-\(h)", quality: h > 0 ? "\(h)p · MP4" : "MP4", url: src, key: "",
                                    height: h > 0 ? h : nil, kind: .mp4Clear, headers: hdr)))
        }
        return out.sorted { $0.0 > $1.0 }.map(\.1)
    }
    private func page(_ s: HGSeries) async throws -> SiteHTTP.Response {
        try await get(s.page ?? "/hdporn/\(s.seriesId).html", ttl: 300)
    }
    func detail(_ s: HGSeries) async throws -> HGSeriesDetail {
        let r = try await page(s)
        return Self.parseDetail(r.text, series: s, url: r.url)
    }
    static func parseDetail(_ h: String, series s: HGSeries, url: String) -> HGSeriesDetail {
        let title = HTML.pageTitle(h).nonEmptyOr(s.title)
        let dur = HTML.groups("(\\d+\\s*m(?:\\s+\\d+\\s*s)?)", h).map { clock($0[1]) } ?? s.duration
        let cover = HTML.resolve(url, HTML.groups("defaultImage\\([\"']([^\"']+)[\"']", h)?[1] ?? "").nonEmptyOr(s.cover)
        return HGSeriesDetail(source: .hqporner, seriesId: s.seriesId, title: title, cover: cover, desc: "",
                              author: nil, actors: [], onlineTime: 0, totalEps: 1,
                              episodes: SiteParse.singleEpisode(s, page: url, duration: dur),
                              tags: [], episodeLabel: dur > 0 ? Fmt.clock(dur) : "长视频")
    }
    func resolve(_ s: HGSeries, _ ep: HGEpisode) async throws -> HGResolvedVideo {
        let r = try await page(s)
        guard let embed = Self.embedURL(r.text) else { throw SiteError.parse("HQPorner 页面里没有嵌入播放页") }
        let (html, status, _) = try await SiteHTTP.shared.raw(embed, referer: "https://hqporner.com/", headers: Self.headers)
        guard (200..<300).contains(status) else { throw ProviderError.upstream("嵌入页 HTTP \(status)") }
        let list = Self.streams(html)
        guard let first = list.first else { throw SiteError.parse("HQPorner 嵌入页里没有 MP4") }
        return HGResolvedVideo(vid: s.seriesId, url: first.url, key: nil, duration: Double(s.duration), expiresAt: nil, err: nil, streams: list)
    }
}
