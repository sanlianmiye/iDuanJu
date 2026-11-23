import SwiftUI
import UIKit

/// Portrait by default on iPhone; unlock all orientations on iPad and while the player is on screen.
final class OrientationGate: NSObject, UIApplicationDelegate {
    private static var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    static var mask: UIInterfaceOrientationMask = UIDevice.current.userInterfaceIdiom == .pad ? .all : .portrait
    private static var rotationGeneration = 0
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        Self.isPad ? .all : Self.mask
    }

    @MainActor static func unlock() {
        rotationGeneration += 1
        mask = isPad ? .all : .allButUpsideDown
        refresh()
    }
    /// 横屏 button: rotate now; rotation stays unlocked so turning the phone back to portrait exits.
    @MainActor static func enterLandscape() {
        rotationGeneration += 1
        mask = isPad ? .all : .allButUpsideDown
        refresh()
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { _ in }
        }
    }
    /// 退出横屏 button.
    @MainActor static func exitLandscape() {
        rotationGeneration += 1
        let generation = rotationGeneration
        if isPad {
            mask = .all
            refresh()
            return
        }
        // UIKit may ignore a portrait geometry request made while the supported mask remains unrestricted.
        // Constrain during the rotation, then restore sensor rotation after the transition completes.
        mask = .portrait
        refresh()
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }) {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) { _ in }
        }
        // `requestGeometryUpdate`'s closure is an errorHandler (never called on success).
        // Restore sensor rotation after the portrait transition settles.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 550_000_000)
            // A dismissed player must not unlock a subsequent portrait-only screen.
            if generation == rotationGeneration { mask = .allButUpsideDown; refresh() }
        }
    }
    @MainActor static func lockPortrait() {
        rotationGeneration += 1
        if isPad {
            mask = .all
            refresh()
            return
        }
        mask = .portrait
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait))
        }
        refresh()
    }
    @MainActor private static func refresh() {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for win in scene.windows {
                win.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            }
        }
    }
}
