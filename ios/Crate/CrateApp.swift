import CrateUI
import SwiftUI

@main
struct CrateApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(Color.accent)
                .preferredColorScheme(nil)
        }
    }
}
