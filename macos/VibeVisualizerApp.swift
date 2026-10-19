import AppKit
import SwiftUI
import WebKit

@MainActor
final class AppModel: ObservableObject {
    @Published var serverAddress: String
    @Published var addressDraft: String
    @Published var addressError: String?
    @Published var loadError: String?

    weak var webView: WKWebView?
    private var pendingCallback: URL?

    init() {
        let savedAddress = UserDefaults.standard.string(forKey: "serverAddress") ?? ""
        serverAddress = savedAddress
        addressDraft = savedAddress
    }

    func saveServerAddress() {
        let trimmedAddress = addressDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        let address = trimmedAddress.contains("://") ? trimmedAddress : "https://\(trimmedAddress)"
        guard var components = URLComponents(string: address),
              components.scheme?.lowercased() == "https",
              let host = components.host,
              !host.isEmpty,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              components.url != nil else {
            addressError = "Enter your server address, for example https://your-server.ts.net."
            return
        }

        components.path = components.path.isEmpty ? "/" : components.path
        guard let normalizedURL = components.url else {
            addressError = "That server address is not valid."
            return
        }

        let normalizedAddress = normalizedURL.absoluteString
        UserDefaults.standard.set(normalizedAddress, forKey: "serverAddress")
        serverAddress = normalizedAddress
        addressDraft = normalizedAddress
        addressError = nil
        loadError = nil
        webView?.load(URLRequest(url: normalizedURL))
    }

    func attach(_ webView: WKWebView) {
        self.webView = webView
        if let pendingCallback {
            self.pendingCallback = nil
            handleCallback(pendingCallback)
            return
        }
        guard !serverAddress.isEmpty, let url = URL(string: serverAddress) else { return }
        webView.load(URLRequest(url: url))
    }

    func reload() {
        guard let webView else { return }
        loadError = nil
        webView.reload()
    }

    func handleCallback(_ callbackURL: URL) {
        guard callbackURL.scheme == "vibevisualizer",
              callbackURL.host == "callback",
              !serverAddress.isEmpty,
              var components = URLComponents(string: serverAddress) else { return }
        components.percentEncodedQuery = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)?.percentEncodedQuery
        guard let url = components.url else {
            loadError = "Spotify returned an invalid sign-in link."
            return
        }
        guard let webView else {
            pendingCallback = callbackURL
            return
        }
        webView.load(URLRequest(url: url))
    }
}

@main
struct VibeVisualizerMacApp: App {
    @NSApplicationDelegateAdaptor(VibeVisualizerAppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            MainView(model: model)
                .frame(minWidth: 760, minHeight: 520)
                .preferredColorScheme(.dark)
                .onOpenURL { model.handleCallback($0) }
        }
        .commands {
            CommandGroup(replacing: .newItem) {}
            CommandGroup(after: .textEditing) {
                Button("Reload Vibe Visualizer") { model.reload() }
                    .keyboardShortcut("r", modifiers: .command)
            }
        }
    }
}

@MainActor
final class VibeVisualizerAppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        guard let iconURL = Bundle.main.url(forResource: "AppIcon", withExtension: "icns"),
              let icon = NSImage(contentsOf: iconURL) else {
            NSLog("Vibe Visualizer: AppIcon.icns is missing or could not be loaded.")
            return
        }
        NSApplication.shared.applicationIconImage = icon
    }
}

struct MainView: View {
    @ObservedObject var model: AppModel
    @State private var showingServerSettings = false

    var body: some View {
        NavigationStack {
            ZStack {
                VibeBackdrop()
                Group {
                    if model.serverAddress.isEmpty {
                        ServerAddressForm(model: model, isFirstRun: true)
                            .frame(maxWidth: 500)
                            .padding(32)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        ZStack {
                            VibeWebView(model: model)
                            if let loadError = model.loadError {
                                VStack(spacing: 16) {
                                    Image(systemName: "network.slash")
                                        .font(.system(size: 36))
                                        .foregroundStyle(.secondary)
                                    Text("Your server can’t be reached")
                                        .font(.title2.bold())
                                    Text(loadError)
                                        .foregroundStyle(.secondary)
                                        .multilineTextAlignment(.center)
                                    HStack {
                                        Button("Try Again") { model.reload() }
                                        Button("Change Server Address") {
                                            model.addressDraft = model.serverAddress
                                            showingServerSettings = true
                                        }
                                    }
                                }
                                .padding(32)
                                .frame(maxWidth: 560)
                                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24))
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
            }
            .navigationTitle("Vibe Visualizer")
            .toolbar {
                ToolbarItemGroup {
                    if !model.serverAddress.isEmpty {
                        Button {
                            model.reload()
                        } label: {
                            Label("Reload", systemImage: "arrow.clockwise")
                        }
                        .help("Reload Vibe Visualizer")
                    }
                    Button {
                        model.addressDraft = model.serverAddress
                        showingServerSettings = true
                    } label: {
                        Label("Server Settings", systemImage: "gearshape")
                    }
                }
            }
        }
        .sheet(isPresented: $showingServerSettings) {
            ServerAddressForm(model: model, isFirstRun: false)
                .frame(width: 500)
                .padding(32)
                .background(VibeColors.background)
                .preferredColorScheme(.dark)
        }
    }
}

struct ServerAddressForm: View {
    @ObservedObject var model: AppModel
    let isFirstRun: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            if isFirstRun {
            VStack(alignment: .leading, spacing: 22) {
                HStack(spacing: 12) {
                    VibeMark(size: 44)
                    Text("VIBE VISUALIZER")
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .tracking(2.4)
                        .foregroundStyle(.white.opacity(0.72))
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("Feel every\nbeat.")
                        .font(.system(size: 46, weight: .semibold, design: .rounded))
                        .tracking(-1.6)
                        .foregroundStyle(
                            LinearGradient(
                                colors: [.white, Color(red: 0.82, green: 0.79, blue: 1)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    Text("Lyrics, color, and motion — all in sync.")
                        .font(.system(size: 15))
                        .foregroundStyle(.white.opacity(0.62))
                }
            }
            } else {
            Text("Connect to your server")
                .font(.title2.bold())
                .foregroundStyle(.white)
            }

            VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("YOUR SERVER")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1.5)
                    .foregroundStyle(.white.opacity(0.54))
                TextField("https://your-server.ts.net", text: $model.addressDraft)
                    .textFieldStyle(.plain)
                    .font(.system(size: 14, weight: .medium, design: .monospaced))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
                    .background(.black.opacity(0.28), in: RoundedRectangle(cornerRadius: 12))
                    .overlay {
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(.white.opacity(0.11), lineWidth: 1)
                    }
                    .onSubmit(submit)
            }

            Text("Keep Tailscale connected on this Mac if your server is on your tailnet.")
                .font(.system(size: 12))
                .foregroundStyle(.white.opacity(0.54))
                .fixedSize(horizontal: false, vertical: true)

            if let addressError = model.addressError {
                Text(addressError)
                    .font(.callout)
                    .foregroundStyle(Color(red: 1, green: 0.48, blue: 0.58))
            }

            HStack(spacing: 12) {
                if !isFirstRun {
                    Button("Cancel") { dismiss() }
                        .keyboardShortcut(.cancelAction)
                        .buttonStyle(.plain)
                        .foregroundStyle(.white.opacity(0.75))
                }
                Spacer()
                Button(action: submit) {
                    HStack(spacing: 8) {
                        Text("Connect")
                        Image(systemName: "arrow.right")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 11)
                    .background(
                        LinearGradient(
                            colors: [VibeColors.purple, VibeColors.pink],
                            startPoint: .leading,
                            endPoint: .trailing
                        ),
                        in: RoundedRectangle(cornerRadius: 11)
                    )
                }
                .buttonStyle(.plain)
                .keyboardShortcut(.defaultAction)
            }
            }
            .padding(20)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
            .overlay {
            RoundedRectangle(cornerRadius: 18)
                .stroke(.white.opacity(0.12), lineWidth: 1)
            }

            if isFirstRun {
            HStack(spacing: 7) {
                Image(systemName: "lock.shield")
                    .foregroundStyle(VibeColors.cyan)
                Text("Your settings stay on your server and this Mac.")
                    .foregroundStyle(.white.opacity(0.54))
            }
            .font(.system(size: 11))
            .padding(.leading, 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func submit() {
        model.saveServerAddress()
        if model.addressError == nil {
            dismiss()
        }
    }
}

private enum VibeColors {
    static let background = Color(red: 7 / 255, green: 7 / 255, blue: 12 / 255)
    static let purple = Color(red: 139 / 255, green: 124 / 255, blue: 1)
    static let pink = Color(red: 1, green: 95 / 255, blue: 162 / 255)
    static let cyan = Color(red: 54 / 255, green: 201 / 255, blue: 1)
}

private struct VibeMark: View {
    var size: CGFloat
    private let heights: [CGFloat] = [0.34, 0.48, 0.62, 0.48, 0.34]

    var body: some View {
        HStack(alignment: .center, spacing: size * 0.055) {
            ForEach(Array(heights.enumerated()), id: \.offset) { index, height in
                Capsule()
                    .fill(.white.opacity(index == 2 ? 1 : 0.82))
                    .frame(width: size * 0.105, height: size * height)
            }
        }
        .frame(width: size, height: size)
        .background(
            LinearGradient(
                colors: [VibeColors.purple, VibeColors.pink, VibeColors.cyan],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: size * 0.25)
        )
        .overlay {
            RoundedRectangle(cornerRadius: size * 0.25)
                .stroke(.white.opacity(0.18), lineWidth: 1)
        }
        .shadow(color: VibeColors.purple.opacity(0.3), radius: size * 0.42, y: size * 0.12)
    }
}

private struct VibeBackdrop: View {
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                VibeColors.background
                Circle()
                    .fill(VibeColors.purple.opacity(0.27))
                    .frame(width: geometry.size.width * 0.7, height: geometry.size.width * 0.7)
                    .blur(radius: 100)
                    .offset(x: -geometry.size.width * 0.3, y: -geometry.size.height * 0.32)
                Circle()
                    .fill(VibeColors.pink.opacity(0.15))
                    .frame(width: geometry.size.width * 0.62, height: geometry.size.width * 0.62)
                    .blur(radius: 110)
                    .offset(x: geometry.size.width * 0.34, y: geometry.size.height * 0.34)
                Circle()
                    .fill(VibeColors.cyan.opacity(0.1))
                    .frame(width: geometry.size.width * 0.5, height: geometry.size.width * 0.5)
                    .blur(radius: 105)
                    .offset(x: geometry.size.width * 0.42, y: -geometry.size.height * 0.38)
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
            .overlay {
                RadialGradient(
                    colors: [.clear, .black.opacity(0.28)],
                    center: .center,
                    startRadius: 80,
                    endRadius: max(geometry.size.width, geometry.size.height) * 0.72
                )
            }
            .ignoresSafeArea()
        }
        .background(VibeColors.background)
        .ignoresSafeArea()
    }
}

struct VibeWebView: NSViewRepresentable {
    @ObservedObject var model: AppModel

    func makeCoordinator() -> Coordinator {
        Coordinator(model: model)
    }

    func makeNSView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        // what the page can ask the app: where the Spotify login comes back to, and the app's version (Settings →
        // Help & about compares it with the newest Mac release)
        let version = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
            .filter { $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" }
        let appBridge = WKUserScript(
            source: "window.VVMac={callback:()=>\"vibevisualizer://callback\",version:()=>\"\(version)\"};",
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        )
        configuration.userContentController.addUserScript(appBridge)
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        model.attach(webView)
        return webView
    }

    func updateNSView(_ webView: WKWebView, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        private let model: AppModel

        init(model: AppModel) {
            self.model = model
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.cancel)
                return
            }
            if navigationAction.targetFrame?.isMainFrame == false {
                decisionHandler(.allow)
                return
            }
            if isServerURL(url) {
                decisionHandler(.allow)
            } else {
                guard ["http", "https", "mailto", "vibevisualizer"].contains(url.scheme?.lowercased() ?? "") else {
                    decisionHandler(.cancel)
                    return
                }
                NSWorkspace.shared.open(url)
                decisionHandler(.cancel)
            }
        }

        func webView(
            _ webView: WKWebView,
            didFailProvisionalNavigation navigation: WKNavigation!,
            withError error: Error
        ) {
            report(error)
        }

        func webView(
            _ webView: WKWebView,
            didFail navigation: WKNavigation!,
            withError error: Error
        ) {
            report(error)
        }

        func webView(
            _ webView: WKWebView,
            didFinish navigation: WKNavigation!
        ) {
            model.loadError = nil
        }

        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            if let url = navigationAction.request.url {
                NSWorkspace.shared.open(url)
            }
            return nil
        }

        func webView(
            _ webView: WKWebView,
            requestMediaCapturePermissionFor origin: WKSecurityOrigin,
            initiatedByFrame frame: WKFrameInfo,
            type: WKMediaCaptureType,
            decisionHandler: @escaping (WKPermissionDecision) -> Void
        ) {
            decisionHandler(.prompt)
        }

        func webView(
            _ webView: WKWebView,
            runOpenPanelWith parameters: WKOpenPanelParameters,
            initiatedByFrame frame: WKFrameInfo,
            completionHandler: @escaping ([URL]?) -> Void
        ) {
            let panel = NSOpenPanel()
            panel.canChooseFiles = true
            panel.canChooseDirectories = false
            panel.allowsMultipleSelection = parameters.allowsMultipleSelection
            panel.begin { response in
                completionHandler(response == .OK ? panel.urls : nil)
            }
        }

        private func isServerURL(_ url: URL) -> Bool {
            guard let server = URL(string: model.serverAddress) else { return false }
            return url.scheme?.lowercased() == server.scheme?.lowercased()
                && url.host?.lowercased() == server.host?.lowercased()
                && url.port == server.port
        }

        private func report(_ error: Error) {
            let nsError = error as NSError
            guard nsError.code != NSURLErrorCancelled else { return }
            model.loadError = error.localizedDescription
        }
    }
}
