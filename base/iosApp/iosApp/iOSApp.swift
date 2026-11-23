import SwiftUI
import DanyFyShared

@main
struct iOSApp: App {
    private let audioEngine: IosAudioEngine
    private let httpClient: IosHttpClient
    private let storage: IosStorage
    private let auth: IosYtmAuth

    init() {
        let audio = IosAudioEngine()
        let http = IosHttpClient()
        let store = IosStorage()
        let ytmAuth = IosYtmAuth()

        self.audioEngine = audio
        self.httpClient = http
        self.storage = store
        self.auth = ytmAuth

        PlatformRegistry.shared.registerIosBridge(
            httpBridge: http,
            audioBridge: audio,
            storageBridge: store,
            authBridge: ytmAuth
        )
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
                .preferredColorScheme(.dark)
                .background(Color.black)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
