import Foundation
import WebKit
import DanyFyShared

public class IosHttpClient: NSObject, PlatformHttp {
    private let session = URLSession(configuration: .default)

    public func postJson(
        url: String,
        headers: [String : String],
        jsonBody: String,
        callback: PlatformHttpCallback
    ) {
        guard let requestUrl = URL(string: url) else {
            callback.onResponse(statusCode: 0, body: nil, error: "Invalid URL: \(url)")
            return
        }

        var request = URLRequest(url: requestUrl)
        request.httpMethod = "POST"
        request.httpBody = jsonBody.data(using: .utf8)
        request.timeoutInterval = 25.0

        for (key, val) in headers {
            request.setValue(val, forHTTPHeaderField: key)
        }

        let task = session.dataTask(with: request) { data, response, error in
            if let err = error {
                callback.onResponse(statusCode: 0, body: nil, error: err.localizedDescription)
                return
            }

            let httpResponse = response as? HTTPURLResponse
            let statusCode = Int32(httpResponse?.statusCode ?? 200)
            let bodyString = data != nil ? String(data: data!, encoding: .utf8) : nil
            callback.onResponse(statusCode: statusCode, body: bodyString, error: nil)
        }
        task.resume()
    }

    public func getBytes(
        url: String,
        headers: [String : String],
        callback: PlatformHttpCallback
    ) {
        guard let requestUrl = URL(string: url) else {
            callback.onResponse(statusCode: 0, body: nil, error: "Invalid URL: \(url)")
            return
        }

        var request = URLRequest(url: requestUrl)
        request.httpMethod = "GET"
        request.timeoutInterval = 25.0

        for (key, val) in headers {
            request.setValue(val, forHTTPHeaderField: key)
        }

        let task = session.dataTask(with: request) { data, response, error in
            if let err = error {
                callback.onResponse(statusCode: 0, body: nil, error: err.localizedDescription)
                return
            }

            let httpResponse = response as? HTTPURLResponse
            let statusCode = Int32(httpResponse?.statusCode ?? 200)
            let base64String = data?.base64EncodedString()
            callback.onResponse(statusCode: statusCode, body: base64String, error: nil)
        }
        task.resume()
    }
}

public class IosStorage: NSObject, PlatformStorage {
    private let defaults = UserDefaults.standard

    public func getString(key: String, default: String?) -> String? {
        return defaults.string(forKey: key) ?? `default`
    }

    public func setString(key: String, value: String?) {
        if let val = value {
            defaults.set(val, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    public func getBoolean(key: String, default: Bool) -> Bool {
        if defaults.object(forKey: key) == nil {
            return `default`
        }
        return defaults.bool(forKey: key)
    }

    public func setBoolean(key: String, value: Bool) {
        defaults.set(value, forKey: key)
    }
}

public class IosYtmAuth: NSObject, PlatformYtmAuth, WKNavigationDelegate {
    private var webView: WKWebView?
    private var completion: ((String) -> Void)?
    private var cancellation: (() -> Void)?

    public func openSignIn(onSuccess: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.completion = onSuccess
        self.cancellation = onCancel

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            let config = WKWebViewConfiguration()
            let wv = WKWebView(frame: .zero, configuration: config)
            wv.navigationDelegate = self
            self.webView = wv

            let alert = UIViewController()
            alert.view = wv

            let nav = UINavigationController(rootViewController: alert)
            alert.navigationItem.leftBarButtonItem = UIBarButtonItem(
                barButtonSystemItem: .cancel,
                target: self,
                action: #selector(self.didCancel)
            )

            if let root = UIApplication.shared.windows.first(where: { $0.isKeyWindow })?.rootViewController {
                root.present(nav, animated: true) {
                    if let url = URL(string: "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https://music.youtube.com") {
                        wv.load(URLRequest(url: url))
                    }
                }
            }
        }
    }

    @objc private func didCancel() {
        webView?.navigationDelegate = nil
        webView = nil
        cancellation?()
    }

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        WKWebsiteDataStore.default().httpCookieStore.getAllCookies { [weak self] cookies in
            var cookieMap: [String: String] = [:]
            for cookie in cookies {
                cookieMap[cookie.name] = cookie.value
            }

            if let sapisid = cookieMap["SAPISID"] ?? cookieMap["__Secure-3PAPISID"], !sapisid.isEmpty {
                let cookieHeader = cookies.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
                DispatchQueue.main.async {
                    webView.navigationDelegate = nil
                    self?.webView = nil
                    (UIApplication.shared.windows.first(where: { $0.isKeyWindow })?.rootViewController)?.dismiss(animated: true) {
                        self?.completion?(cookieHeader)
                    }
                }
            }
        }
    }

    public func clearCookies() {
        let store = WKWebsiteDataStore.default()
        store.fetchDataRecords(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes()) { records in
            store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), for: records) {}
        }
    }
}
