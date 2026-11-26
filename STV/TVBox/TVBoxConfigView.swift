import SwiftUI
import UniformTypeIdentifiers

/// 01 · TVBox 接入与多配置仓管理页
struct TVBoxConfigView: View {
    @ObservedObject private var store = TVBoxStore.shared
    @ObservedObject private var gate = SourceGate.shared
    @Environment(\.dismiss) private var dismiss

    @State private var urlText = ""
    @State private var siteQuery = ""
    @State private var busy = false
    @State private var statusMsg: String?
    @State private var statusError = false
    @State private var showPaste = false
    @State private var pasteText = ""
    @State private var showFile = false
    @State private var showManual = false
    @State private var manualName = ""
    @State private var manualAPI = ""
    @State private var manualAdult = false
    @State private var confirmDeleteRepo: TVBoxRepo?

    var body: some View {
        let activeRepo = store.activeRepo
        let allVisibleSites = (activeRepo?.sites ?? []).filter { !$0.adult || gate.unlocked }
        let q = siteQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let visibleSites = q.isEmpty ? allVisibleSites : allVisibleSites.filter {
            $0.name.lowercased().contains(q) || $0.api.lowercased().contains(q)
        }
        let lives = activeRepo?.lives ?? []

        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                // 单行 HTTP(S) 导入栏
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(STVTheme.secondary).font(.subheadline)
                    TextField("输入 HTTP(S) 配置地址（JSON / M3U）", text: $urlText)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .font(.subheadline)
                        .foregroundStyle(STVTheme.text)
                    Button {
                        let u = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !u.isEmpty else { return }
                        runTask {
                            let r = try await store.importFromURL(u) { statusMsg = $0 }
                            urlText = ""
                            return "已解析「\(r.name)」：\(r.sites.count) 点播站 · \(r.lives.count) 直播源"
                        }
                    } label: {
                        Text("解析")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(STVTheme.bg)
                            .padding(.horizontal, 15).frame(height: 32)
                            .background(STVTheme.text, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(busy || urlText.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding(.leading, 14).padding(.trailing, 5).frame(height: 44)
                .background(STVTheme.glass, in: Capsule())
                .overlay { Capsule().strokeBorder(STVTheme.glassBorder) }
                .padding(.horizontal, 16)

                // 3 个轻量导入方式胶囊
                HStack(spacing: 8) {
                    methodPill("doc.on.clipboard", "粘贴 JSON") { pasteText = ""; showPaste = true }
                    methodPill("doc.badge.plus", "导入文件") { showFile = true }
                    methodPill("plus", "手动加站") { manualName = ""; manualAPI = ""; manualAdult = false; showManual = true }
                }
                .padding(.horizontal, 16)

                // 状态反馈条
                if let msg = statusMsg {
                    HStack(spacing: 8) {
                        if busy { ProgressView().scaleEffect(0.8) }
                        Text(msg)
                            .font(.caption)
                            .foregroundStyle(statusError ? STVTheme.accentWarm : STVTheme.text)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background((statusError ? STVTheme.accentWarm : STVTheme.accent).opacity(0.13), in: RoundedRectangle(cornerRadius: 12))
                    .padding(.horizontal, 16)
                }

                // 多配置仓切换胶囊条
                if !store.repos.isEmpty {
                    HStack {
                        Text("已保存配置（\(store.repos.count) 仓可切换）")
                            .font(.caption.weight(.semibold)).foregroundStyle(STVTheme.secondary)
                        Spacer()
                        if let activeRepo {
                            Button { confirmDeleteRepo = activeRepo } label: {
                                Label("删除当前仓", systemImage: "trash")
                                    .font(.caption).foregroundStyle(STVTheme.secondary)
                            }.buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16).padding(.top, 4)

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(store.repos) { repo in
                                let on = repo.id == activeRepo?.id
                                Button { store.selectRepo(repo.id) } label: {
                                    HStack(spacing: 6) {
                                        if on { Circle().fill(Color(red: 0.37, green: 0.82, blue: 0.54)).frame(width: 7, height: 7) }
                                        Text(repo.name).font(.caption.weight(.semibold)).lineLimit(1)
                                    }
                                    .foregroundStyle(on ? STVTheme.accent : STVTheme.secondary)
                                    .padding(.horizontal, 12).frame(height: 32)
                                    .background(on ? STVTheme.accent.opacity(0.15) : STVTheme.glass, in: Capsule())
                                    .overlay { Capsule().strokeBorder(on ? STVTheme.accent.opacity(0.42) : STVTheme.glassBorder) }
                                }.buttonStyle(.plain)
                            }
                        }.padding(.horizontal, 16)
                    }
                }

                // 当前生效配置摘要条
                if let activeRepo {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 8) {
                            Text(activeRepo.name).font(.subheadline.weight(.bold)).foregroundStyle(STVTheme.text).lineLimit(1)
                            Text("当前生效")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(Color(red: 0.37, green: 0.82, blue: 0.54))
                                .padding(.horizontal, 7).padding(.vertical, 2)
                                .background(Color(red: 0.12, green: 0.62, blue: 0.34).opacity(0.16), in: Capsule())
                            Spacer()
                            if let u = activeRepo.url, !u.isEmpty {
                                Button("重新拉取") {
                                    runTask {
                                        let r = try await store.refreshRepo(activeRepo.id) { statusMsg = $0 }
                                        return "已更新「\(r.name)」：\(r.sites.count) 点播站 · \(r.lives.count) 直播源"
                                    }
                                }
                                .font(.caption.weight(.semibold)).foregroundStyle(STVTheme.accent)
                                .disabled(busy)
                            }
                        }
                        Text(activeRepo.url ?? "本地 / 剪贴板导入")
                            .font(.caption2.monospaced()).foregroundStyle(STVTheme.secondary).lineLimit(1)
                        Divider().overlay(STVTheme.text.opacity(0.08)).padding(.vertical, 3)
                        HStack(spacing: 8) {
                            Text("\(allVisibleSites.count) 点播站").font(.caption.weight(.semibold)).foregroundStyle(STVTheme.text)
                            Text("·").foregroundStyle(STVTheme.secondary)
                            Text("\(lives.count) 直播源").font(.caption.weight(.semibold)).foregroundStyle(STVTheme.text)
                            if activeRepo.skippedCount > 0 {
                                Text("·").foregroundStyle(STVTheme.secondary)
                                Text("已跳过 \(activeRepo.skippedCount) 个 JAR/脚本源").font(.caption2).foregroundStyle(STVTheme.secondary)
                            }
                        }
                    }
                    .padding(14)
                    .background(STVTheme.glass, in: RoundedRectangle(cornerRadius: 16))
                    .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(STVTheme.glassBorder) }
                    .padding(.horizontal, 16)
                }

                // 点播站点细线列表（懒加载，支持大量站点快速过滤）
                if !allVisibleSites.isEmpty, let activeRepo {
                    HStack {
                        Text("点播站点（苹果 CMS API · \(visibleSites.count)\(q.isEmpty ? "" : "/\(allVisibleSites.count)")）")
                            .font(.caption.weight(.semibold)).foregroundStyle(STVTheme.secondary)
                        Spacer()
                    }
                    .padding(.horizontal, 16).padding(.top, 6)

                    if allVisibleSites.count > 12 {
                        HStack(spacing: 6) {
                            Image(systemName: "magnifyingglass").font(.caption).foregroundStyle(STVTheme.secondary)
                            TextField("筛选点播站点名称或域名…", text: $siteQuery)
                                .textInputAutocapitalization(.never)
                                .font(.caption)
                                .foregroundStyle(STVTheme.text)
                            if !siteQuery.isEmpty {
                                Button { siteQuery = "" } label: {
                                    Image(systemName: "xmark.circle.fill").font(.caption).foregroundStyle(STVTheme.secondary)
                                }.buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 12).frame(height: 34)
                        .background(STVTheme.glass, in: Capsule())
                        .overlay { Capsule().strokeBorder(STVTheme.glassBorder) }
                        .padding(.horizontal, 16)
                    }

                    LazyVStack(spacing: 0) {
                        ForEach(visibleSites) { site in
                            TVBoxSiteRow(site: site, showDivider: site.id != visibleSites.last?.id) { enabled in
                                store.setSiteEnabled(repoId: activeRepo.id, siteKey: site.key, enabled: enabled)
                            }
                        }
                    }.padding(.horizontal, 16)
                }

                // 直播列表细线列表（懒加载）
                if !lives.isEmpty, let activeRepo {
                    Text("直播列表（HTTP(S) M3U / TXT · \(lives.count)）")
                        .font(.caption.weight(.semibold)).foregroundStyle(STVTheme.secondary)
                        .padding(.horizontal, 16).padding(.top, 10)

                    LazyVStack(spacing: 0) {
                        ForEach(lives) { live in
                            TVBoxLiveRow(
                                live: live,
                                showDivider: live.id != lives.last?.id,
                                onWatch: { Router.push(.tvboxLive(activeRepo.id, live.id, 0)) },
                                onToggle: { store.setLiveEnabled(repoId: activeRepo.id, liveId: live.id, enabled: $0) }
                            )
                        }
                    }.padding(.horizontal, 16)
                }

                Text("仅解析 HTTP(S) 苹果 CMS 接口（type 0 / 1 / 4）与 M3U/TXT 直播源；JAR、CatVod 与 Python spider 不会在本地执行。")
                    .font(.caption2).foregroundStyle(STVTheme.secondary.opacity(0.75))
                    .padding(.horizontal, 16).padding(.top, 8)
            }
            .padding(.vertical, 12)
        }
        .background(STVTheme.bg.ignoresSafeArea())
        .navigationTitle("TVBox 配置与片源")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let activeRepo, !activeRepo.sites.isEmpty {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(busy ? "处理中…" : "全部测速") {
                        runTask {
                            await store.pingAllSites(repoId: activeRepo.id) { statusMsg = $0 }
                            return "测速完成"
                        }
                    }
                    .disabled(busy)
                    .foregroundStyle(STVTheme.accent)
                }
            }
        }
        .fileImporter(isPresented: $showFile, allowedContentTypes: [.json, .plainText, .data, .item]) { res in
            guard case let .success(url) = res else { return }
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .utf8) else {
                statusMsg = "无法读取文件"; statusError = true; return
            }
            let name = url.deletingPathExtension().lastPathComponent
            runTask {
                let r = try await store.importFromText(text, fallbackName: name) { statusMsg = $0 }
                return "已导入「\(r.name)」：\(r.sites.count) 点播站 · \(r.lives.count) 直播源"
            }
        }
        .sheet(isPresented: $showPaste) {
            NavigationStack {
                VStack(spacing: 12) {
                    TextEditor(text: $pasteText)
                        .font(.caption.monospaced())
                        .padding(10)
                        .background(STVTheme.surface, in: RoundedRectangle(cornerRadius: 12))
                        .padding()
                }
                .background(STVTheme.bg)
                .navigationTitle("粘贴 TVBox JSON 或 M3U/TXT")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { showPaste = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("解析导入") {
                            let t = pasteText; showPaste = false
                            runTask {
                                let r = try await store.importFromText(t) { statusMsg = $0 }
                                return "已导入「\(r.name)」：\(r.sites.count) 点播站 · \(r.lives.count) 直播源"
                            }
                        }.disabled(pasteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
        }
        .sheet(isPresented: $showManual) {
            NavigationStack {
                Form {
                    TextField("站点名称（可选）", text: $manualName)
                    TextField("http(s)://…/api.php/provide/vod/", text: $manualAPI)
                        .textInputAutocapitalization(.never).keyboardType(.URL)
                    Toggle("成人内容（需在片源管理解锁）", isOn: $manualAdult)
                }
                .navigationTitle("手动添加苹果 CMS 站点")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { showManual = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("添加") {
                            let n = manualName; let a = manualAPI; let ad = manualAdult; showManual = false
                            runTask {
                                let s = try await store.addManualSite(name: n, apiRaw: a, adult: ad)
                                return "已添加「\(s.name)」· \(s.categories.count) 分类 · \(s.latencyMs)ms"
                            }
                        }.disabled(!manualAPI.trimmingCharacters(in: .whitespaces).hasPrefix("http://") && !manualAPI.trimmingCharacters(in: .whitespaces).hasPrefix("https://"))
                    }
                }
            }
        }
        .alert("删除配置「\(confirmDeleteRepo?.name ?? "")」？", isPresented: Binding(get: { confirmDeleteRepo != nil }, set: { if !$0 { confirmDeleteRepo = nil } })) {
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) {
                if let r = confirmDeleteRepo {
                    store.removeRepo(r.id)
                    statusMsg = "已删除「\(r.name)」"
                    statusError = false
                }
            }
        } message: { Text("删除后该仓下的站点和直播源将从 TVBox 中移除，可随时重新导入。") }
    }

    private func methodPill(_ icon: String, _ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: icon).font(.caption)
                Text(title).font(.caption.weight(.medium))
            }
            .foregroundStyle(STVTheme.text)
            .frame(maxWidth: .infinity).frame(height: 34)
            .background(STVTheme.glass, in: Capsule())
            .overlay { Capsule().strokeBorder(STVTheme.glassBorder) }
        }.buttonStyle(.plain)
    }

    private func runTask(_ work: @escaping () async throws -> String) {
        guard !busy else { return }
        busy = true; statusError = false; statusMsg = "正在处理…"
        Task {
            do {
                statusMsg = try await work()
                statusError = false
            } catch {
                statusMsg = error.localizedDescription
                statusError = true
            }
            busy = false
        }
    }
}

private struct TVBoxSiteRow: View {
    let site: TVBoxRepoSite
    let showDivider: Bool
    let onToggle: (Bool) -> Void

    private static func fastHost(_ api: String) -> String {
        guard let schemeEnd = api.range(of: "://") else { return "" }
        let rest = api[schemeEnd.upperBound...]
        let end = rest.firstIndex(where: { $0 == "/" || $0 == ":" || $0 == "?" }) ?? rest.endIndex
        return String(rest[..<end])
    }

    var body: some View {
        let slow = site.latencyMs >= 400
        let host = Self.fastHost(site.api)
        let ping = site.latencyMs > 0 && site.latencyMs < 9999 ? " · \(site.latencyMs)ms" : (site.latencyMs >= 9999 ? " · 超时" : "")
        VStack(spacing: 0) {
            HStack(spacing: 11) {
                Circle()
                    .fill(!site.enabled ? STVTheme.secondary.opacity(0.4) : (slow ? STVTheme.accentWarm : Color(red: 0.37, green: 0.82, blue: 0.54)))
                    .frame(width: 7, height: 7)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(site.name).font(.subheadline.weight(.semibold)).foregroundStyle(STVTheme.text).lineLimit(1)
                        Text(slow ? "较慢" : "m3u8")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(slow ? STVTheme.secondary : STVTheme.accent)
                            .padding(.horizontal, 6).padding(.vertical, 1.5)
                            .background((slow ? STVTheme.glass : STVTheme.accent.opacity(0.15)), in: RoundedRectangle(cornerRadius: 5))
                    }
                    Text("\(host) · \(site.categories.count) 分类\(ping)")
                        .font(.caption2).foregroundStyle(slow ? STVTheme.accentWarm : STVTheme.secondary).lineLimit(1)
                }
                Spacer()
                Toggle("", isOn: Binding(get: { site.enabled }, set: onToggle)).labelsHidden().tint(STVTheme.accent)
            }
            .padding(.vertical, 10)
            if showDivider { Divider().overlay(STVTheme.text.opacity(0.07)) }
        }
    }
}

private struct TVBoxLiveRow: View {
    let live: TVBoxRepoLive
    let showDivider: Bool
    let onWatch: () -> Void
    let onToggle: (Bool) -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 11) {
                Circle().fill(Color(red: 1.0, green: 0.42, blue: 0.42)).frame(width: 7, height: 7)
                VStack(alignment: .leading, spacing: 2) {
                    Text(live.name).font(.subheadline.weight(.semibold)).foregroundStyle(STVTheme.text).lineLimit(1)
                    Text(live.channels.isEmpty ? "进入观看时自动拉取频道列表" : "\(live.channels.count) 个频道 · HTTP(S) 直播源")
                        .font(.caption2).foregroundStyle(STVTheme.secondary).lineLimit(1)
                }
                Spacer()
                Button(action: onWatch) {
                    Text("观看")
                        .font(.caption.weight(.semibold)).foregroundStyle(STVTheme.accent)
                        .padding(.horizontal, 11).frame(height: 28)
                        .background(STVTheme.accent.opacity(0.15), in: Capsule())
                }.buttonStyle(.plain)
                Toggle("", isOn: Binding(get: { live.enabled }, set: onToggle)).labelsHidden().tint(STVTheme.accent)
            }
            .padding(.vertical, 10)
            if showDivider { Divider().overlay(STVTheme.text.opacity(0.07)) }
        }
    }
}
