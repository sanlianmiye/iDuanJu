import CryptoKit
import Foundation
import Combine

struct TVBoxLiveChannel: Codable, Hashable, Identifiable {
    var id: String { name }
    var name: String
    var group: String
    var urls: [String]
    var logo: String = ""
}

struct TVBoxRepoLive: Codable, Hashable, Identifiable {
    var id: String
    var name: String
    var url: String
    var enabled: Bool = true
    var channels: [TVBoxLiveChannel] = []
}

struct TVBoxRepoSite: Codable, Hashable, Identifiable {
    var id: String { key }
    var key: String
    var name: String
    var api: String
    var adult: Bool = false
    var enabled: Bool = true
    var categories: [PluginCategory] = []
    var latencyMs: Int = -1

    func toCMSSite() -> CMSSite {
        CMSSite(key: key, name: name, api: api, adult: adult, enabled: enabled, categories: categories, origin: "TVBox")
    }
}

struct TVBoxRepo: Codable, Hashable, Identifiable {
    var id: String
    var name: String
    var url: String?
    var sites: [TVBoxRepoSite]
    var lives: [TVBoxRepoLive]
    var skippedCount: Int = 0
    var updatedAt: TimeInterval = Date().timeIntervalSince1970
}

struct TVBoxRecentWatch: Codable, Hashable, Identifiable {
    var id: String { seriesId }
    var siteKey: String
    var siteName: String
    var seriesId: String
    var title: String
    var cover: String
    var episodeIndex: Int
    var episodeTitle: String
    var lineName: String
    var positionSec: Double
    var durationSec: Double
    var updatedAt: TimeInterval = Date().timeIntervalSince1970
}

private struct TVBoxStoreSnapshot: Codable {
    var activeRepoId: String?
    var repos: [TVBoxRepo]
    var favoriteChannels: [String]
    var recentWatches: [TVBoxRecentWatch]
}

@MainActor
final class TVBoxStore: ObservableObject {
    static let shared = TVBoxStore()

    @Published private(set) var repos: [TVBoxRepo] = []
    @Published private(set) var activeRepoId: String?
    @Published private(set) var favoriteChannels: [String] = []
    @Published private(set) var recentWatches: [TVBoxRecentWatch] = []

    var activeRepo: TVBoxRepo? {
        repos.first { $0.id == activeRepoId } ?? repos.first
    }

    private var fileURL: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return dir.appendingPathComponent("tvbox_store_v1.json")
    }

    private init() {
        load()
        migrateLegacySitesIfNeeded()
    }

    func migrateLegacySitesIfNeeded() {
        guard repos.isEmpty else { return }
        let legacy = CMSStore.shared.sites.filter { $0.origin == "TVBox" || $0.origin.hasPrefix("TVBox") }
        guard !legacy.isEmpty else { return }
        let repo = TVBoxRepo(
            id: "legacy-tvbox",
            name: "已导入 TVBox",
            url: nil,
            sites: legacy.map { TVBoxRepoSite(key: $0.key, name: $0.name, api: $0.api, adult: $0.adult, enabled: $0.enabled, categories: $0.categories, latencyMs: -1) },
            lives: []
        )
        repos = [repo]
        activeRepoId = repo.id
        save()
    }

    func selectRepo(_ id: String) {
        guard repos.contains(where: { $0.id == id }) else { return }
        activeRepoId = id
        save()
    }

    func removeRepo(_ id: String) {
        repos.removeAll { $0.id == id }
        if activeRepoId == id { activeRepoId = repos.first?.id }
        save()
    }

    func setSiteEnabled(repoId: String, siteKey: String, enabled: Bool) {
        guard let i = repos.firstIndex(where: { $0.id == repoId }),
              let j = repos[i].sites.firstIndex(where: { $0.key == siteKey }) else { return }
        repos[i].sites[j].enabled = enabled
        save()
    }

    func setLiveEnabled(repoId: String, liveId: String, enabled: Bool) {
        guard let i = repos.firstIndex(where: { $0.id == repoId }),
              let j = repos[i].lives.firstIndex(where: { $0.id == liveId }) else { return }
        repos[i].lives[j].enabled = enabled
        save()
    }

    func cacheCategories(repoId: String, siteKey: String, categories: [PluginCategory]) {
        guard let i = repos.firstIndex(where: { $0.id == repoId }),
              let j = repos[i].sites.firstIndex(where: { $0.key == siteKey }),
              repos[i].sites[j].categories != categories else { return }
        repos[i].sites[j].categories = categories
        save()
    }

    func findSite(_ key: String) -> TVBoxRepoSite? {
        if let s = activeRepo?.sites.first(where: { $0.key == key }) { return s }
        return repos.lazy.flatMap(\.sites).first { $0.key == key }
    }

    func isFavoriteChannel(_ name: String) -> Bool {
        favoriteChannels.contains(name)
    }

    func toggleFavoriteChannel(_ name: String) {
        if let i = favoriteChannels.firstIndex(of: name) {
            favoriteChannels.remove(at: i)
        } else {
            favoriteChannels.insert(name, at: 0)
        }
        save()
    }

    func recordWatch(_ item: TVBoxRecentWatch) {
        recentWatches.removeAll { $0.seriesId == item.seriesId }
        recentWatches.insert(item, at: 0)
        if recentWatches.count > 30 { recentWatches.removeLast(recentWatches.count - 30) }
        save()
    }

    func addManualSite(name: String, apiRaw: String, adult: Bool) async throws -> TVBoxRepoSite {
        let api = try CMSStore.normalize(apiRaw, allowHTTP: true)
        let t0 = Date()
        let cats = try await MacCMSProvider.fetchCategories(api: api)
        let ms = max(1, Int(Date().timeIntervalSince(t0) * 1000))
        let host = URL(string: api)?.host ?? "采集站"
        let site = TVBoxRepoSite(key: CMSSite.key(for: api), name: name.isEmpty ? host : name, api: api, adult: adult, enabled: true, categories: cats, latencyMs: ms)
        if let cur = activeRepo, let i = repos.firstIndex(where: { $0.id == cur.id }) {
            repos[i].sites.removeAll { $0.key == site.key }
            repos[i].sites.append(site)
            repos[i].updatedAt = Date().timeIntervalSince1970
        } else {
            let repo = TVBoxRepo(id: "manual-repo", name: "自建配置", url: nil, sites: [site], lives: [])
            repos.insert(repo, at: 0)
            activeRepoId = repo.id
        }
        save()
        return site
    }

    func importFromURL(_ rawURL: String, onProgress: @MainActor (String) -> Void = { _ in }) async throws -> TVBoxRepo {
        let url = rawURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard url.hasPrefix("https://") || url.hasPrefix("http://") else { throw PluginError.install("配置地址必须是 HTTP(S) 链接") }
        onProgress("正在拉取配置…")
        let data = try await Self.downloadData(url)
        let text = String(decoding: data, as: UTF8.self)
        let trimmed = text.trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF} \t\r\n"))
        if !trimmed.hasPrefix("{") {
            let channels = Self.parseLiveText(text)
            guard !channels.isEmpty else { throw PluginError.install("该链接既不是 TVBox JSON，也不是有效的 HTTP(S) 直播列表") }
            let host = URL(string: url)?.host ?? "直播源"
            let title = URL(string: url)?.deletingPathExtension().lastPathComponent.nilIfEmpty ?? host
            let live = TVBoxRepoLive(id: Self.hashId(url), name: title, url: url, enabled: true, channels: channels)
            let repo = TVBoxRepo(id: Self.hashId(url), name: title, url: url, sites: [], lives: [live])
            upsertAndActivate(repo)
            return repo
        }
        return try await importParsedJSON(data, sourceURL: url, fallbackName: nil, onProgress: onProgress)
    }

    func importFromText(_ text: String, fallbackName: String? = nil, onProgress: @MainActor (String) -> Void = { _ in }) async throws -> TVBoxRepo {
        let trimmed = text.trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF} \t\r\n"))
        if !trimmed.hasPrefix("{") {
            let channels = Self.parseLiveText(text)
            guard !channels.isEmpty else { throw PluginError.install("未识别到 TVBox JSON 或有效 HTTP(S) 直播源") }
            let title = fallbackName?.nilIfEmpty ?? "本地直播源"
            let id = Self.hashId(title + "\(channels.count)" + (channels.first?.urls.first ?? ""))
            let live = TVBoxRepoLive(id: id, name: title, url: "", enabled: true, channels: channels)
            let repo = TVBoxRepo(id: id, name: title, url: nil, sites: [], lives: [live])
            upsertAndActivate(repo)
            return repo
        }
        return try await importParsedJSON(Data(text.utf8), sourceURL: nil, fallbackName: fallbackName, onProgress: onProgress)
    }

    func refreshRepo(_ id: String, onProgress: @MainActor (String) -> Void = { _ in }) async throws -> TVBoxRepo {
        guard let repo = repos.first(where: { $0.id == id }), let url = repo.url, !url.isEmpty else {
            throw PluginError.install("本地导入的配置没有远程地址")
        }
        return try await importFromURL(url, onProgress: onProgress)
    }

    func pingAllSites(repoId: String, onProgress: @MainActor (String) -> Void = { _ in }) async {
        guard let idx = repos.firstIndex(where: { $0.id == repoId }) else { return }
        let sites = repos[idx].sites
        var updated: [TVBoxRepoSite] = []
        var done = 0
        for offset in stride(from: 0, to: sites.count, by: 6) {
            let slice = Array(sites[offset..<min(offset + 6, sites.count)])
            let res: [TVBoxRepoSite] = await withTaskGroup(of: TVBoxRepoSite.self) { group in
                for s in slice {
                    group.addTask {
                        let t0 = Date()
                        let cats = try? await MacCMSProvider.fetchCategories(api: s.api)
                        let ms = cats != nil ? max(1, Int(Date().timeIntervalSince(t0) * 1000)) : 9999
                        var copy = s
                        if let cats, !cats.isEmpty { copy.categories = cats }
                        copy.latencyMs = ms
                        return copy
                    }
                }
                var out: [TVBoxRepoSite] = []
                for await item in group { out.append(item) }
                return out
            }
            updated.append(contentsOf: res)
            done += res.count
            onProgress("测速中 \(done)/\(sites.count)")
        }
        if let curIdx = repos.firstIndex(where: { $0.id == repoId }) {
            let map = Dictionary(uniqueKeysWithValues: updated.map { ($0.key, $0) })
            repos[curIdx].sites = repos[curIdx].sites.map { map[$0.key] ?? $0 }
            save()
        }
    }

    func loadLiveChannels(repoId: String, liveId: String, forceRefresh: Bool = false) async throws -> [TVBoxLiveChannel] {
        guard let rIdx = repos.firstIndex(where: { $0.id == repoId }),
              let lIdx = repos[rIdx].lives.firstIndex(where: { $0.id == liveId }) else { return [] }
        let live = repos[rIdx].lives[lIdx]
        if !forceRefresh && !live.channels.isEmpty { return live.channels }
        guard !live.url.isEmpty else { return live.channels }
        let data = try await Self.downloadData(live.url)
        let parsed = Self.parseLiveText(String(decoding: data, as: UTF8.self))
        if let curR = repos.firstIndex(where: { $0.id == repoId }),
           let curL = repos[curR].lives.firstIndex(where: { $0.id == liveId }) {
            repos[curR].lives[curL].channels = parsed
            save()
        }
        return parsed
    }

    private func importParsedJSON(_ data: Data, sourceURL: String?, fallbackName: String?, onProgress: @MainActor (String) -> Void) async throws -> TVBoxRepo {
        let parsed = try TVBoxImport.parse(data)
        var validSites: [TVBoxRepoSite] = []
        let validationSites = parsed.sites.count > 60 ? Array(parsed.sites.prefix(8)) : parsed.sites
        var checked = 0
        for offset in stride(from: 0, to: validationSites.count, by: 8) {
            let slice = Array(validationSites[offset..<min(offset + 8, validationSites.count)])
            let res: [TVBoxRepoSite?] = await withTaskGroup(of: TVBoxRepoSite?.self) { group in
                for site in slice {
                    group.addTask {
                        let t0 = Date()
                        guard let cats = try? await MacCMSProvider.fetchCategories(api: site.api) else { return nil }
                        let ms = max(1, Int(Date().timeIntervalSince(t0) * 1000))
                        return TVBoxRepoSite(key: CMSSite.key(for: site.api), name: site.name, api: site.api, adult: site.adult, enabled: true, categories: cats, latencyMs: ms)
                    }
                }
                var out: [TVBoxRepoSite?] = []
                for await r in group { out.append(r) }
                return out
            }
            for item in res {
                checked += 1
                if let item { validSites.append(item) }
            }
            onProgress("正在验证点播站 \(checked)/\(validationSites.count)（可用 \(validSites.count)）")
        }
        if validationSites.count < parsed.sites.count {
            guard !validSites.isEmpty || !parsed.lives.isEmpty else {
                throw PluginError.install("大配置没有验证出可用的苹果 CMS 站点（已跳过 \(parsed.skipped) 个不兼容项）")
            }
            let verified = Dictionary(uniqueKeysWithValues: validSites.map { ($0.key, $0) })
            validSites = parsed.sites.map { site in
                verified[CMSSite.key(for: site.api)] ?? TVBoxRepoSite(
                    key: CMSSite.key(for: site.api), name: site.name, api: site.api,
                    adult: site.adult, enabled: true, categories: [], latencyMs: -1
                )
            }
            onProgress("大配置已导入 \(validSites.count) 个点播站，其余进入后按需验证")
        }
        let lives = parsed.lives.map { TVBoxRepoLive(id: Self.hashId($0.url), name: $0.name, url: $0.url, enabled: true, channels: []) }
        guard !validSites.isEmpty || !lives.isEmpty else {
            throw PluginError.install("配置里没有可用的苹果 CMS 点播站或 HTTP(S) 直播源（已跳过 \(parsed.skipped) 个不兼容项）")
        }
        let name: String = {
            if parsed.name != "TVBox 配置" { return parsed.name }
            if let f = fallbackName, !f.isEmpty { return f }
            if let u = sourceURL, let h = URL(string: u)?.host { return h }
            return parsed.name
        }()
        let id = Self.hashId(sourceURL ?? (name + (validSites.first?.api ?? "") + (lives.first?.url ?? "")))
        let repo = TVBoxRepo(
            id: id,
            name: name,
            url: sourceURL,
            sites: validSites,
            lives: lives,
            skippedCount: parsed.skipped + (parsed.sites.count - validSites.count),
            updatedAt: Date().timeIntervalSince1970
        )
        upsertAndActivate(repo)
        return repo
    }

    private func upsertAndActivate(_ repo: TVBoxRepo) {
        if let idx = repos.firstIndex(where: { $0.id == repo.id || (repo.url != nil && $0.url == repo.url) }) {
            let old = repos[idx]
            let siteEnabled = Dictionary(uniqueKeysWithValues: old.sites.map { ($0.key, $0.enabled) })
            let liveMap = Dictionary(uniqueKeysWithValues: old.lives.map { ($0.id, $0) })
            var merged = repo
            merged.id = old.id
            merged.sites = repo.sites.map { s in var c = s; if let e = siteEnabled[s.key] { c.enabled = e }; return c }
            merged.lives = repo.lives.map { l in
                guard let prev = liveMap[l.id] else { return l }
                var c = l; c.enabled = prev.enabled; if c.channels.isEmpty { c.channels = prev.channels }; return c
            }
            repos[idx] = merged
            activeRepoId = merged.id
        } else {
            repos.insert(repo, at: 0)
            activeRepoId = repo.id
        }
        save()
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let snap = try? JSONDecoder().decode(TVBoxStoreSnapshot.self, from: data) else { return }
        repos = snap.repos
        activeRepoId = snap.activeRepoId
        favoriteChannels = snap.favoriteChannels
        recentWatches = snap.recentWatches
    }

    private static let saveQueue = DispatchQueue(label: "stv.tvbox.save", qos: .utility)
    private var pendingSave: DispatchWorkItem?

    private func save() {
        let snap = TVBoxStoreSnapshot(activeRepoId: activeRepoId, repos: repos, favoriteChannels: favoriteChannels, recentWatches: recentWatches)
        let targetURL = fileURL
        pendingSave?.cancel()
        let work = DispatchWorkItem {
            if let data = try? JSONEncoder().encode(snap) {
                try? data.write(to: targetURL, options: .atomic)
            }
        }
        pendingSave = work
        Self.saveQueue.asyncAfter(deadline: .now() + 0.2, execute: work)
    }

    nonisolated static func hashId(_ seed: String) -> String {
        String(Insecure.MD5.hash(data: Data(seed.utf8)).map { String(format: "%02x", $0) }.joined().prefix(12))
    }

    nonisolated static func downloadData(_ rawURL: String) async throws -> Data {
        guard let u = URL(string: rawURL),
              u.scheme == "https" || u.scheme == "http",
              let host = u.host, !host.isEmpty else {
            throw PluginError.install("仅支持 HTTP(S) 公开地址")
        }
        let blocked = await Task.detached { NetGuard.isIPLiteral(host) || NetGuard.resolvesToBlocked(host) }.value
        if blocked { throw PluginError.install("不支持 IP 或内网地址") }
        var req = URLRequest(url: u)
        req.setValue(PluginHostContext.defaultUA, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await MacCMSProvider.session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw PluginError.install("HTTP \(code) · \(host)") }
        guard data.count <= 4 << 20 else { throw PluginError.install("文件超过 4 MB 限制") }
        return data
    }

    nonisolated static func parseLiveText(_ raw: String) -> [TVBoxLiveChannel] {
        var cleaned = raw
        while cleaned.unicodeScalars.first == "\u{FEFF}" { cleaned.unicodeScalars.removeFirst() }
        var order: [String] = []
        var map: [String: (group: String, logo: String, urls: [String])] = [:]
        var currentGroup = "默认频道"
        var pendingName: String?
        var pendingGroup: String?
        var pendingLogo = ""

        func addChannel(name: String, group: String, logo: String, rawURLPart: String) {
            let n = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
            guard !n.isEmpty else { return }
            let urls = rawURLPart.split(separator: "#").map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }.compactMap { safeStreamURL($0) }
            guard !urls.isEmpty else { return }
            if map[n] == nil {
                order.append(n)
                map[n] = (group.isEmpty ? "默认频道" : group, logo, urls)
            } else {
                for u in urls where !map[n]!.urls.contains(u) { map[n]!.urls.append(u) }
            }
        }

        let groupRegex = try? NSRegularExpression(pattern: #"group-title="([^"]*)""#)
        let logoRegex = try? NSRegularExpression(pattern: #"tvg-logo="([^"]*)""#)

        for lineRaw in cleaned.components(separatedBy: .newlines) {
            let line = lineRaw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty || line.uppercased().hasPrefix("#EXTM3U") { continue }
            if line.uppercased().hasPrefix("#EXTINF:") {
                if let comma = line.lastIndex(of: ",") {
                    pendingName = String(line[line.index(after: comma)...]).trimmingCharacters(in: .whitespaces)
                } else {
                    pendingName = ""
                }
                let ns = line as NSString
                let range = NSRange(location: 0, length: ns.length)
                pendingGroup = groupRegex?.firstMatch(in: line, range: range).map { ns.substring(with: $0.range(at: 1)).trimmingCharacters(in: .whitespaces) }
                pendingLogo = logoRegex?.firstMatch(in: line, range: range).map { ns.substring(with: $0.range(at: 1)).trimmingCharacters(in: .whitespaces) } ?? ""
                continue
            }
            if line.hasPrefix("#") { continue }
            if let name = pendingName {
                addChannel(name: name, group: pendingGroup ?? currentGroup, logo: pendingLogo, rawURLPart: line)
                pendingName = nil; pendingGroup = nil; pendingLogo = ""
                continue
            }
            if let comma = line.firstIndex(of: ",") {
                let left = String(line[..<comma]).trimmingCharacters(in: .whitespaces)
                let right = String(line[line.index(after: comma)...]).trimmingCharacters(in: .whitespaces)
                if right.caseInsensitiveCompare("#genre#") == .orderedSame {
                    if !left.isEmpty { currentGroup = String(left.prefix(30)) }
                } else {
                    addChannel(name: left, group: currentGroup, logo: "", rawURLPart: right)
                }
            }
        }

        return order.compactMap { name in
            guard let e = map[name] else { return nil }
            return TVBoxLiveChannel(name: name, group: e.group, urls: e.urls, logo: e.logo)
        }
    }

    nonisolated private static func safeStreamURL(_ raw: String) -> String? {
        let u = raw
        guard (u.hasPrefix("https://") || u.hasPrefix("http://")),
              let url = URL(string: u), let host = url.host, !host.isEmpty, !NetGuard.isIPLiteral(host) else {
            return nil
        }
        return u
    }
}
