import AVFoundation
import SwiftUI

/// 05 · TVBox 直播横屏播放页（安静播控 + 右侧半透明毛玻璃换台与信号源切换抽屉）
struct TVBoxLivePlayerView: View {
    let repoId: String
    let liveId: String
    let startChannelIndex: Int

    @ObservedObject private var store = TVBoxStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var channels: [TVBoxLiveChannel] = []
    @State private var channelIdx = 0
    @State private var lineIdx = 0
    @State private var drawerOpen = true
    @State private var controlsVisible = true
    @State private var drawerGroup = "全部"
    @State private var fillMode = false
    @StateObject private var player = TVBoxPlayerController()

    var body: some View {
        let repo = store.repos.first { $0.id == repoId } ?? store.activeRepo
        let live = repo?.lives.first { $0.id == liveId }
        let currentChannel = channels.indices.contains(channelIdx) ? channels[channelIdx] : channels.first
        let groups = ["全部", "收藏"] + Array(NSOrderedSet(array: channels.map(\.group)).compactMap { $0 as? String })
        let drawerChannels: [TVBoxLiveChannel] = {
            switch drawerGroup {
            case "全部": return channels
            case "收藏": return channels.filter { store.isFavoriteChannel($0.name) }
            default: return channels.filter { $0.group == drawerGroup }
            }
        }()
        let streamURL = currentChannel?.urls.indices.contains(lineIdx) == true ? currentChannel?.urls[lineIdx] : currentChannel?.urls.first

        ZStack {
            TVBoxEngineSurface(controller: player, fill: fillMode)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { withAnimation(.easeInOut(duration: 0.18)) { controlsVisible.toggle() } }

            if player.isBuffering && player.errorMsg == nil {
                ProgressView().tint(.white).scaleEffect(1.2)
            }
            if let err = player.errorMsg {
                VStack(spacing: 10) {
                    Text(err).font(.caption).foregroundStyle(.white.opacity(0.85)).multilineTextAlignment(.center)
                    Button("重试当前线路") {
                        player.play(urlString: streamURL, title: currentChannel?.name ?? "直播")
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 7)
                    .background(Color.white.opacity(0.18), in: Capsule())
                    .buttonStyle(.plain)
                }
                .padding(16)
                .background(Color.black.opacity(0.68), in: RoundedRectangle(cornerRadius: 14))
                .padding(.trailing, drawerOpen ? 270 : 0)
            }

            // 左侧安静播控层
            if controlsVisible {
                VStack {
                    HStack(spacing: 12) {
                        Button { dismiss() } label: {
                            Image(systemName: "chevron.left")
                                .foregroundStyle(.white)
                                .frame(width: 34, height: 34)
                                .background(Color.black.opacity(0.55), in: Circle())
                                .overlay { Circle().strokeBorder(Color.white.opacity(0.16)) }
                        }.buttonStyle(.plain)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(currentChannel?.name ?? "直播频道").font(.headline.weight(.bold)).foregroundStyle(.white)
                            HStack(spacing: 6) {
                                Circle().fill(Color(red: 1.0, green: 0.42, blue: 0.42)).frame(width: 7, height: 7)
                                Text("LIVE · \(player.engineKind.short) · 线路 \(lineIdx + 1)/\(max(1, currentChannel?.urls.count ?? 1)) · \(live?.name ?? "")")
                                    .font(.caption2).foregroundStyle(.white.opacity(0.72))
                            }
                        }
                        Spacer()
                    }

                    Spacer()

                    Button {
                        player.togglePlay()
                    } label: {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.title3).foregroundStyle(.white)
                            .frame(width: 50, height: 50)
                            .background(Color.black.opacity(0.58), in: Circle())
                            .overlay { Circle().strokeBorder(Color.white.opacity(0.22)) }
                    }.buttonStyle(.plain)

                    Spacer()

                    HStack(spacing: 8) {
                        landPill("频道列表", icon: "list.bullet", active: drawerOpen) { withAnimation(.compatSnappy) { drawerOpen.toggle() } }
                        let count = max(1, currentChannel?.urls.count ?? 1)
                        landPill("线路 \(lineIdx + 1)/\(count)") { lineIdx = (lineIdx + 1) % count }
                        landPill(fillMode ? "画面 满屏" : "画面 16:9") { fillMode.toggle() }
                        let fav = currentChannel.map { store.isFavoriteChannel($0.name) } ?? false
                        landPill(fav ? "已收藏" : "收藏", icon: fav ? "star.fill" : "star", active: fav) {
                            if let ch = currentChannel { store.toggleFavoriteChannel(ch.name) }
                        }
                        Spacer()
                    }
                }
                .padding(.horizontal, 22).padding(.vertical, 18)
                .padding(.trailing, drawerOpen ? 270 : 0)
            }

            // 右侧半透明毛玻璃换台抽屉
            if drawerOpen {
                HStack(spacing: 0) {
                    Spacer()
                    VStack(alignment: .leading, spacing: 0) {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 14) {
                                ForEach(groups, id: \.self) { g in
                                    let on = g == drawerGroup
                                    Button { drawerGroup = g } label: {
                                        VStack(spacing: 4) {
                                            Text(g)
                                                .font(.caption.weight(on ? .bold : .medium))
                                                .foregroundStyle(on ? .white : .white.opacity(0.58))
                                            Capsule().fill(on ? Color(red: 0.54, green: 0.71, blue: 1.0) : .clear).frame(width: 18, height: 2)
                                        }
                                    }.buttonStyle(.plain)
                                }
                            }.padding(.horizontal, 14).padding(.vertical, 12)
                        }
                        Divider().overlay(Color.white.opacity(0.08))

                        ScrollView {
                            LazyVStack(spacing: 2) {
                                ForEach(Array(drawerChannels.enumerated()), id: \.offset) { idx, ch in
                                    let on = ch.name == currentChannel?.name
                                    let gIdx = channels.firstIndex(where: { $0.name == ch.name }) ?? 0
                                    Button {
                                        channelIdx = gIdx
                                        lineIdx = 0
                                    } label: {
                                        HStack(spacing: 8) {
                                            Text(String(format: "%02d", idx + 1))
                                                .font(.caption2.monospacedDigit())
                                                .foregroundStyle(on ? Color(red: 0.54, green: 0.71, blue: 1.0) : .white.opacity(0.5))
                                                .frame(width: 20)
                                            Text(ch.name)
                                                .font(.caption.weight(on ? .bold : .regular))
                                                .foregroundStyle(on ? Color(red: 0.54, green: 0.71, blue: 1.0) : .white.opacity(0.86))
                                                .lineLimit(1)
                                            Spacer()
                                            Text("\(ch.urls.count)源").font(.system(size: 10)).foregroundStyle(.white.opacity(0.5))
                                        }
                                        .padding(.horizontal, 10).padding(.vertical, 8)
                                        .background(on ? Color(red: 0.54, green: 0.71, blue: 1.0).opacity(0.18) : .clear, in: RoundedRectangle(cornerRadius: 9))
                                    }.buttonStyle(.plain)
                                }
                            }.padding(8)
                        }

                        Divider().overlay(Color.white.opacity(0.08))
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                Text("信号源").font(.caption2).foregroundStyle(.white.opacity(0.62))
                                let urls = currentChannel?.urls ?? []
                                ForEach(urls.indices, id: \.self) { i in
                                    let on = i == lineIdx
                                    Button { lineIdx = i } label: {
                                        Text(i == 0 ? "主线" : "备用 \(i)")
                                            .font(.caption2.weight(.semibold))
                                            .foregroundStyle(on ? Color(red: 0.54, green: 0.71, blue: 1.0) : .white.opacity(0.8))
                                            .padding(.horizontal, 9).padding(.vertical, 4)
                                            .background(on ? Color(red: 0.54, green: 0.71, blue: 1.0).opacity(0.24) : Color.white.opacity(0.09), in: RoundedRectangle(cornerRadius: 8))
                                    }.buttonStyle(.plain)
                                }
                            }.padding(.horizontal, 14).padding(.vertical, 10)
                        }
                    }
                    .frame(width: 268)
                    .background(.ultraThinMaterial)
                    .background(Color(red: 0.06, green: 0.07, blue: 0.09).opacity(0.76))
                    .overlay(alignment: .leading) { Rectangle().fill(Color.white.opacity(0.10)).frame(width: 0.6) }
                }
                .ignoresSafeArea(edges: .vertical)
            }
        }
        .background(Color.black.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onAppear {
            StatusBarGate.shared.setHidden(true, animated: false)
            OrientationGate.enterLandscape()
            channelIdx = max(0, startChannelIndex)
        }
        .onDisappear {
            StatusBarGate.shared.setHidden(false)
            player.stop()
            OrientationGate.lockPortrait()
        }
        .task(id: "\(repoId)|\(liveId)") {
            if let live, !live.channels.isEmpty {
                channels = live.channels
            } else if let repo, let live {
                channels = (try? await store.loadLiveChannels(repoId: repo.id, liveId: live.id)) ?? []
            }
            if let ch = channels.indices.contains(channelIdx) ? channels[channelIdx] : channels.first {
                drawerGroup = ch.group
                player.play(urlString: ch.urls.first, title: ch.name)
            }
        }
        .compatOnChange(of: streamURL) { u in player.play(urlString: u, title: currentChannel?.name ?? "直播") }
    }

    private func landPill(_ title: String, icon: String? = nil, active: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let icon { Image(systemName: icon).font(.caption2) }
                Text(title).font(.caption.weight(.semibold))
            }
            .foregroundStyle(active ? Color(red: 0.54, green: 0.71, blue: 1.0) : .white)
            .padding(.horizontal, 12).frame(height: 32)
            .background(active ? Color(red: 0.54, green: 0.71, blue: 1.0).opacity(0.24) : Color.black.opacity(0.6), in: Capsule())
            .overlay { Capsule().strokeBorder(active ? Color(red: 0.54, green: 0.71, blue: 1.0).opacity(0.48) : Color.white.opacity(0.14)) }
        }.buttonStyle(.plain)
    }
}
