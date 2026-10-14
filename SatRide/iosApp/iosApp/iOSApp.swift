import SwiftUI
import Shared
import FirebaseCore
import GoogleSignIn

@main
struct iOSApp: App {
    init() {
        // Firebase before Koin, because the auth repository reads `Firebase.auth` as soon as the
        // graph is built. Configured from `GoogleService-Info.plist`, whose BUNDLE_ID must match
        // this target's — Firebase refuses to initialise on a bundle id it does not know, and it
        // fails at runtime rather than at build time.
        FirebaseApp.configure()

        // GoogleSignIn needs the OAuth client id before it will present anything, and without it
        // `signIn(withPresenting:)` throws `NSInvalidArgumentException: No active configuration`
        // and takes the app down — which is exactly what tapping the button did the first time this
        // ran on a device.
        //
        // Taken from Firebase's own options rather than duplicated into Info.plist as `GIDClientID`.
        // Both come from `GoogleService-Info.plist`; reading it here keeps that file the single
        // source of truth, so regenerating it in the Firebase console cannot leave a stale copy of
        // the id behind in a second place.
        if let clientID = FirebaseApp.app()?.options.clientID {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        }

        // Kotlin cannot call GoogleSignIn: it is a Swift package linked into this target, and the
        // shared module has no cinterop for it. So the dependency is inverted — Kotlin declares a
        // hole and this fills it. See `GoogleSignIn.ios.kt`.
        MainViewControllerKt.setGoogleSignInHandler { completion in
            // The completion is adapted rather than passed straight through: a Kotlin `-> Unit`
            // *nested inside* another function type bridges to Swift as `-> KotlinUnit`, not
            // `-> Void`, so the two signatures do not match. Discarding the result here keeps that
            // wart at the boundary instead of spreading `KotlinUnit` through the Swift code.
            presentGoogleSignIn { idToken, accessToken, error in
                _ = completion(idToken, accessToken, error)
            }
        }

        // DI must be running before the first Compose frame.
        MainViewControllerKt.doInitKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    // Two kinds of URL arrive here and only one is ours.
                    //
                    // GoogleSignIn's OAuth callback comes back on the reversed-client-id scheme and
                    // has to be handed to the SDK to complete the flow; satride:// links are pass
                    // notifications and deep links, parsed in Kotlin so the two platforms cannot
                    // drift. Asking GoogleSignIn first is deliberate: it returns false for anything
                    // that is not its own callback, so it is a safe filter.
                    if GIDSignIn.sharedInstance.handle(url) {
                        return
                    }
                    _ = MainViewControllerKt.handleDeepLink(url: url.absoluteString)
                }
        }
    }
}

/// Presents the Google account chooser and returns an ID token.
///
/// `completion` takes `(idToken, accessToken, error)`: either the first two are set or the last one
/// is. A plain closure rather than anything richer because it has to cross into Kotlin, where the
/// simplest possible signature is the one least likely to be mangled by the Objective-C bridge.
///
/// The access token is not optional here even though SatRide never calls a Google API with it:
/// Firebase's iOS SDK takes it as a nonnull parameter, and a credential built without one is
/// rejected *after* the user has picked an account and returned to the app — which reads as
/// "sign-in failed" for no visible reason.
///
/// Cancellation is reported as the shared sentinel rather than as an error,
/// because dismissing the chooser is a decision and not a fault: the Kotlin side maps it to
/// `AuthFailure.Cancelled` and says nothing at all to the user.
private func presentGoogleSignIn(completion: @escaping (String?, String?, String?) -> Void) {
    guard let presenter = topViewController() else {
        completion(nil, nil, "No view controller to present from.")
        return
    }

    GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
        if let error = error as NSError? {
            let cancelled = error.code == GIDSignInError.canceled.rawValue
            let message = cancelled
                ? MainViewControllerKt.googleSignInCancelledSentinel()
                : error.localizedDescription
            completion(nil, nil, message)
            return
        }
        guard let user = result?.user, let idToken = user.idToken?.tokenString else {
            completion(nil, nil, "Google returned no ID token.")
            return
        }
        completion(idToken, user.accessToken.tokenString, nil)
    }
}

/// The view controller currently on screen.
///
/// `signIn(withPresenting:)` needs something to present from, and this app's only view controller
/// is the one hosting Compose — which SwiftUI owns and does not hand out. Walking the hierarchy is
/// the standard way to find it, and it has to skip presented controllers or the chooser is
/// presented on top of a sheet that is already up and never appears.
private func topViewController() -> UIViewController? {
    let scene = UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .first { $0.activationState == .foregroundActive }

    guard let root = scene?.windows.first(where: \.isKeyWindow)?.rootViewController else {
        return nil
    }

    var top = root
    while let presented = top.presentedViewController {
        top = presented
    }
    return top
}
