import SwiftUI
import UIKit
import WebKit

/// Native QR login sheet for 红果短剧 (mirrors Android's `HongguoLoginScreen.kt`).
/// Drives a desktop-viewport `WKWebView` on `https://hongguoduanju.com`, extracts the QR image via JS bridge,
/// and verifies login directly inside WebKit's same-origin session so `HttpOnly` cookies are never lost.
struct HongguoLoginView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var account = HongguoWebAccount.shared
    @State private var qrImage: UIImage?
    @State private var qrStatus: String = "loading"
    @State private var refreshNonce = 0
    @State private var checkNonce = 0
    @State private var saveNotice: String?
    @State private var loggedInName: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    VStack(spacing: 6) {
                        Text("红果短剧扫码登录")
                            .font(.title2.weight(.heavy))
                            .foregroundStyle(STVTheme.text)
                        Text("使用红果短剧 App 扫描下方二维码，同步本机观看进度")
                            .font(.subheadline)
                            .foregroundStyle(STVTheme.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, 12)

                    // QR card
                    VStack(spacing: 14) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(Color.white)
                                .frame(width: 224, height: 224)

                            if let loggedInName {
                                VStack(spacing: 10) {
                                    Image(systemName: "checkmark.circle.fill")
                                        .font(.system(size: 48))
                                        .foregroundStyle(.green)
                                    Text("已登录：\(loggedInName)")
                                        .font(.headline)
                                        .foregroundStyle(.black)
                                }
                            } else if let qrImage {
                                Image(uiImage: qrImage)
                                    .interpolation(.none)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 200, height: 200)
                                    .opacity(qrStatus == "expired" ? 0.25 : 1)

                                if qrStatus == "expired" {
                                    Button {
                                        saveNotice = nil
                                        qrStatus = "loading"
                                        refreshNonce += 1
                                    } label: {
                                        VStack(spacing: 6) {
                                            Image(systemName: "arrow.clockwise.circle.fill")
                                                .font(.system(size: 36))
                                            Text("二维码已过期\n点击刷新")
                                                .font(.caption.weight(.semibold))
                                                .multilineTextAlignment(.center)
                                        }
                                        .foregroundStyle(.black)
                                        .padding(14)
                                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
                                    }
                                }
                            } else {
                                VStack(spacing: 10) {
                                    ProgressView().tint(.gray)
                                    Text("正在获取二维码…")
                                        .font(.caption)
                                        .foregroundStyle(.gray)
                                }
                            }
                        }

                        Text(statusLabel)
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(qrStatus == "scanned" ? STVTheme.accent : STVTheme.secondary)
                    }
                    .padding(22)
                    .frame(maxWidth: 320)
                    .background(STVTheme.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(STVTheme.hairline, lineWidth: 1))

                    HStack(spacing: 12) {
                        Button {
                            saveNotice = nil
                            qrStatus = "loading"
                            refreshNonce += 1
                        } label: {
                            Label("刷新二维码", systemImage: "arrow.clockwise")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                                .background(STVTheme.surface, in: Capsule())
                        }
                        .foregroundStyle(STVTheme.text)

                        if let qrImage {
                            Button {
                                UIImageWriteToSavedPhotosAlbum(qrImage, nil, nil, nil)
                                saveNotice = "已保存二维码到相册，可在红果短剧 App 内相册扫码"
                            } label: {
                                Label("保存到相册", systemImage: "square.and.arrow.down")
                                    .font(.subheadline.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                                    .background(STVTheme.accent, in: Capsule())
                            }
                            .foregroundStyle(.white)
                        }
                    }
                    .frame(maxWidth: 320)

                    if let saveNotice {
                        Text(saveNotice)
                            .font(.caption)
                            .foregroundStyle(STVTheme.accent)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }

                    if loggedInName == nil && qrImage != nil {
                        Button {
                            checkNonce += 1
                            Task {
                                try? await Task.sleep(nanoseconds: 350_000_000)
                                guard loggedInName == nil else { return }
                                if let p = await account.refreshProfile() {
                                    completeLogin(nickname: p.nickname, avatarUrl: p.avatarUrl)
                                } else {
                                    saveNotice = "正在等待红果网页端确认，若刚点确认请稍等 1–2 秒再试"
                                }
                            }
                        } label: {
                            Text("我已扫码确认，立即检查登录状态")
                                .font(.footnote.weight(.medium))
                                .foregroundStyle(STVTheme.accent)
                                .padding(.vertical, 6)
                        }
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
                .padding(.bottom, 32)
            }
            .background {
                ZStack(alignment: .topLeading) {
                    // Keep WKWebView mounted in `.background` (never expanding screen layout width) at 1280×900
                    // so desktop login modal renders cleanly and WebKit timers stay active on app foregrounding.
                    HongguoQRWebView(
                        refreshNonce: refreshNonce,
                        checkNonce: checkNonce,
                        onQrUpdate: { dataURL, status in
                            qrStatus = status
                            if let img = Self.decodeDataURL(dataURL) {
                                qrImage = img
                            } else if status == "loading" && dataURL.isEmpty {
                                qrImage = nil
                            }
                        },
                        onLoggedIn: { nickname, avatarUrl in
                            completeLogin(nickname: nickname, avatarUrl: avatarUrl)
                        }
                    )
                    .frame(width: 1280, height: 900)
                    .allowsHitTesting(false)

                    STVTheme.bg.ignoresSafeArea()
                }
                .clipped()
                .ignoresSafeArea()
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
            .onChange(of: scenePhase) { phase in
                if phase == .active, loggedInName == nil {
                    checkNonce += 1
                    Task {
                        for delayNs in [500_000_000 as UInt64, 1_500_000_000 as UInt64] {
                            try? await Task.sleep(nanoseconds: delayNs)
                            guard !Task.isCancelled, loggedInName == nil else { return }
                            checkNonce += 1
                            if let p = await account.refreshProfile() {
                                completeLogin(nickname: p.nickname, avatarUrl: p.avatarUrl)
                                return
                            }
                        }
                    }
                }
            }
            .task {
                while !Task.isCancelled && loggedInName == nil {
                    try? await Task.sleep(nanoseconds: 2_200_000_000)
                    guard !Task.isCancelled, loggedInName == nil else { break }
                    checkNonce += 1
                    if let p = await account.refreshProfile() {
                        completeLogin(nickname: p.nickname, avatarUrl: p.avatarUrl)
                        break
                    }
                }
            }
        }
    }

    private func completeLogin(nickname: String, avatarUrl: String) {
        let trimmed = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        guard loggedInName == nil, !trimmed.isEmpty else { return }
        loggedInName = trimmed
        saveNotice = nil
        Task {
            await account.acceptWebViewLogin(nickname: trimmed, avatarUrl: avatarUrl)
            try? await Task.sleep(nanoseconds: 700_000_000)
            dismiss()
        }
    }

    private var statusLabel: String {
        if loggedInName != nil { return "登录成功，正在返回…" }
        switch qrStatus {
        case "scanned": return "已扫描，请在红果短剧 App 上确认登录"
        case "expired": return "二维码已过期，请点击刷新"
        case "ready": return "打开红果短剧 App 扫一扫"
        default: return "正在连接红果短剧网页登录…"
        }
    }

    private static func decodeDataURL(_ s: String) -> UIImage? {
        guard let comma = s.firstIndex(of: ",") else { return nil }
        let b64 = String(s[s.index(after: comma)...])
        guard let data = Data(base64Encoded: b64, options: .ignoreUnknownCharacters) else { return nil }
        return UIImage(data: data)
    }
}

private struct HongguoQRWebView: UIViewRepresentable {
    let refreshNonce: Int
    let checkNonce: Int
    let onQrUpdate: (String, String) -> Void
    let onLoggedIn: (String, String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onQrUpdate: onQrUpdate, onLoggedIn: onLoggedIn) }

    func makeUIView(context: Context) -> WKWebView {
        let ucc = WKUserContentController()
        ucc.add(context.coordinator, name: "idjLogin")
        ucc.addUserScript(WKUserScript(source: Self.viewportScript, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        ucc.addUserScript(WKUserScript(source: Self.bridgeJS, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        let cfg = WKWebViewConfiguration()
        cfg.userContentController = ucc
        cfg.websiteDataStore = .default()
        cfg.defaultWebpagePreferences.preferredContentMode = .desktop
        let wv = WKWebView(frame: CGRect(x: 0, y: 0, width: 1280, height: 900), configuration: cfg)
        wv.customUserAgent = HongguoWebAccount.pcUserAgent
        wv.navigationDelegate = context.coordinator
        context.coordinator.webView = wv
        HongguoWebAccount.shared.activeLoginWebView = wv
        if let url = URL(string: HongguoWebAccount.webOrigin) {
            wv.load(URLRequest(url: url))
        }
        return wv
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if context.coordinator.lastNonce != refreshNonce {
            context.coordinator.lastNonce = refreshNonce
            uiView.evaluateJavaScript("window.__idjActions && window.__idjActions.refreshQr();", completionHandler: nil)
        }
        if context.coordinator.lastCheckNonce != checkNonce {
            context.coordinator.lastCheckNonce = checkNonce
            uiView.evaluateJavaScript("window.__idjActions && window.__idjActions.checkLogin();", completionHandler: nil)
        }
    }

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        if HongguoWebAccount.shared.activeLoginWebView === uiView {
            HongguoWebAccount.shared.activeLoginWebView = nil
        }
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "idjLogin")
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "idjApi")
        uiView.stopLoading()
    }

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        let onQrUpdate: (String, String) -> Void
        let onLoggedIn: (String, String) -> Void
        weak var webView: WKWebView?
        var lastNonce = 0
        var lastCheckNonce = 0

        init(onQrUpdate: @escaping (String, String) -> Void, onLoggedIn: @escaping (String, String) -> Void) {
            self.onQrUpdate = onQrUpdate
            self.onLoggedIn = onLoggedIn
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == "idjLogin",
                  let dict = message.body as? [String: Any] else { return }
            if (dict["loggedIn"] as? Bool) == true {
                let nickname = dict["nickname"] as? String ?? ""
                let avatarUrl = dict["avatarUrl"] as? String ?? ""
                if !nickname.isEmpty {
                    DispatchQueue.main.async { [onLoggedIn] in
                        onLoggedIn(nickname, avatarUrl)
                    }
                }
                return
            }
            let src = dict["qrSrc"] as? String ?? ""
            let status = dict["qrStatus"] as? String ?? "loading"
            DispatchQueue.main.async { [onQrUpdate] in
                onQrUpdate(src, status)
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            webView.evaluateJavaScript(HongguoQRWebView.bridgeJS, completionHandler: nil)
        }
    }

    private static let viewportScript: String = """
    (function() {
      try {
        var obs = new MutationObserver(function() {
          var vp = document.querySelector('meta[name="viewport"]');
          if (vp && vp.getAttribute('content') !== 'width=1280') {
            vp.setAttribute('content', 'width=1280');
          }
        });
        obs.observe(document.documentElement, { childList: true, subtree: true });
      } catch (e) {}
    })();
    """

    private static let bridgeJS: String = """
    (function() {
      try {
        var st = document.getElementById('idj-login-fix');
        if (!st) {
          st = document.createElement('style');
          st.id = 'idj-login-fix';
          (document.head || document.documentElement).appendChild(st);
        }
        st.textContent = '*,*::before,*::after{animation:none!important;transition:none!important;-webkit-backdrop-filter:none!important;backdrop-filter:none!important;}video{display:none!important;}[role="dialog"],[class*="pc-login-dialog"]{max-height:calc(100% - 32px)!important;opacity:1!important;visibility:visible!important;}';
        var vp = document.querySelector('meta[name="viewport"]');
        if (vp) vp.setAttribute('content', 'width=1280');
        else if (document.head) {
          var m = document.createElement('meta');
          m.name = 'viewport';
          m.content = 'width=1280';
          document.head.appendChild(m);
        }

        function isElVisible(el) {
          if (!el) return false;
          if (el.offsetWidth <= 0 || el.offsetHeight <= 0) return false;
          var cs = getComputedStyle(el);
          return cs.display !== 'none' && cs.visibility !== 'hidden' && cs.opacity !== '0';
        }
        function findDialog() {
          return document.querySelector('[role="dialog"], [class*="pc-login-dialog"]');
        }
        function notifyLogin(nick, avatar) {
          if (window.__idjLoggedInNotified || !nick) return;
          window.__idjLoggedInNotified = true;
          if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.idjLogin) {
            window.webkit.messageHandlers.idjLogin.postMessage({
              loggedIn: true,
              nickname: String(nick).trim(),
              avatarUrl: String(avatar || '').trim()
            });
          }
        }
        function extractProfile(j) {
          if (!j || typeof j !== 'object') return null;
          var cands = [j.profile, j.data && j.data.profile, j.user, j.data && j.data.user];
          for (var i = 0; i < cands.length; i++) {
            var p = cands[i];
            if (p && typeof p === 'object') {
              var nick = (p.nickname || p.name || p.screen_name || p.user_name || '').trim();
              if (nick) {
                return { nickname: nick, avatarUrl: (p.avatarUrl || p.avatar_url || p.avatar || '').trim() };
              }
            }
          }
          return null;
        }
        function checkProfileInWebView() {
          if (window.__idjLoggedInNotified) return;
          try {
            var rd = window._ROUTER_DATA && window._ROUTER_DATA.loaderData;
            if (rd) {
              for (var k in rd) {
                var found = extractProfile(rd[k]);
                if (found) { notifyLogin(found.nickname, found.avatarUrl); return; }
              }
            }
          } catch (e) {}
          fetch('/api/pc/profile?t=' + Date.now(), { credentials: 'include', cache: 'no-store', headers: { 'Accept': 'application/json' } })
            .then(function(r) { return r.ok ? r.json() : null; })
            .then(function(j) {
              var found = extractProfile(j);
              if (found) notifyLogin(found.nickname, found.avatarUrl);
            })
            .catch(function() {});
        }
        window.__idjActions = {
          refreshQr: function() {
            var dlg = findDialog();
            if (dlg) {
              var mask = dlg.querySelector('.web-login-scan-code__content__qrcode-wrapper__mask, [class*="qrcode-wrapper__mask"]');
              if (mask && isElVisible(mask)) {
                mask.click();
                var innerBtn = mask.querySelector('button, [role="button"], svg, span');
                if (innerBtn) innerBtn.click();
                return 'clicked';
              }
            }
            location.reload();
            return 'reloaded';
          },
          checkLogin: function() {
            checkProfileInWebView();
          }
        };
        if (window.__idjLoginOpenTimer) clearInterval(window.__idjLoginOpenTimer);
        var openTries = 0;
        window.__idjLoginOpenTimer = setInterval(function() {
          openTries++;
          try {
            var dlg = findDialog();
            var rect = dlg ? dlg.getBoundingClientRect() : null;
            if (dlg && rect.width > 0 && rect.height > 0) {
              clearInterval(window.__idjLoginOpenTimer);
              window.__idjLoginOpenTimer = null;
              return;
            }
            if (openTries % 2 === 1) {
              var selectors = ['button', 'a, [role="button"]', 'span, div'];
              var clicked = false;
              for (var s = 0; s < selectors.length && !clicked; s++) {
                var btns = document.querySelectorAll(selectors[s]);
                for (var i = 0; i < btns.length; i++) {
                  if ((btns[i].textContent || '').trim() === '登录') {
                    btns[i].click();
                    clicked = true;
                    break;
                  }
                }
              }
            }
          } catch (e) {}
          if (openTries >= 40) {
            clearInterval(window.__idjLoginOpenTimer);
            window.__idjLoginOpenTimer = null;
          }
        }, 500);
        window.__idjLastQrKey = null;
        if (window.__idjStateObserver) clearInterval(window.__idjStateObserver);
        window.__idjStateObserver = setInterval(function() {
          try {
            var dlg = findDialog();
            var qrSrc = '';
            var qrStatus = 'loading';
            if (dlg) {
              var imgs = dlg.querySelectorAll('img');
              for (var i = 0; i < imgs.length; i++) {
                var im = imgs[i];
                var src = im.getAttribute('src') || im.src || '';
                if (src.indexOf('data:image/') === 0 && (im.naturalWidth >= 100 || !!im.closest('.web-login-scan-code') || (im.alt || '').indexOf('二维码') >= 0)) {
                  qrSrc = src;
                  break;
                }
              }
              if (!qrSrc) {
                var scanImg = dlg.querySelector('.web-login-scan-code img, [alt*="二维码"]');
                if (scanImg) {
                  var ss = scanImg.getAttribute('src') || scanImg.src || '';
                  if (ss.indexOf('data:image/') === 0) qrSrc = ss;
                }
              }
              if (!qrSrc) {
                var canvas = dlg.querySelector('.web-login-scan-code canvas');
                if (canvas && canvas.width >= 100) {
                  try { qrSrc = canvas.toDataURL('image/png'); } catch (ce) {}
                }
              }
              var dlgText = dlg.textContent || '';
              var mask = dlg.querySelector('.web-login-scan-code__content__qrcode-wrapper__mask, [class*="qrcode-wrapper__mask"]');
              if (dlgText.indexOf('已扫描') >= 0 || dlgText.indexOf('请在手机') >= 0) {
                qrStatus = 'scanned';
              } else if (isElVisible(mask) || dlgText.indexOf('点击刷新') >= 0 || dlgText.indexOf('已过期') >= 0 || dlgText.indexOf('二维码失效') >= 0) {
                qrStatus = 'expired';
              } else if (qrSrc) {
                qrStatus = 'ready';
              }
            }
            var qrKey = qrStatus + '|' + qrSrc;
            if (qrKey !== window.__idjLastQrKey) {
              window.__idjLastQrKey = qrKey;
              if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.idjLogin) {
                window.webkit.messageHandlers.idjLogin.postMessage({ qrSrc: qrSrc, qrStatus: qrStatus });
              }
            }
            checkProfileInWebView();
          } catch (e) {}
        }, 1200);
        if (!window.__idjVisBound) {
          window.__idjVisBound = true;
          document.addEventListener('visibilitychange', function() {
            if (!document.hidden) checkProfileInWebView();
          });
        }
      } catch (e) {}
    })();
    """
}
