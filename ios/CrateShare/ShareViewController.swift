import CrateKit
import CrateUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Spotify → Share → Crate. Reads the shared link (a URL, or text containing one) and shows ``ShareView``.
@objc(ShareViewController)
final class ShareViewController: UIViewController {
    private let model = ShareModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        let root = ShareView(model: model, open: { [weak self] in self?.open($0) }, close: { [weak self] in self?.close() })
        let host = UIHostingController(rootView: root)
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
        Task { await model.load(text: await sharedText()) }
    }

    /// The first URL or plain text among the shared items.
    private func sharedText() async -> String? {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        for p in providers where p.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            if let url = try? await p.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL { return url.absoluteString }
        }
        for p in providers where p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            if let text = try? await p.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String { return text }
        }
        return nil
    }

    /// Share extensions have no API to open URLs. The host's UIApplication sits at the end of the responder chain;
    /// call its `open(_:options:completionHandler:)` by selector (the method is hidden from extensions at compile
    /// time, not at run time). If that ever stops working, the sheet just closes.
    private func open(_ url: URL) {
        let selector = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self
        while let r = responder {
            if r is UIApplication, r.responds(to: selector) {
                typealias Open = @convention(c) (NSObject, Selector, NSURL, NSDictionary, AnyObject?) -> Void
                unsafeBitCast(r.method(for: selector), to: Open.self)(r, selector, url as NSURL, NSDictionary(), nil)
                break
            }
            responder = r.next
        }
        close()
    }

    private func close() { extensionContext?.completeRequest(returningItems: nil) }
}
