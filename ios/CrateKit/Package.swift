// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CrateKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "CrateKit", targets: ["CrateKit"]),
        .library(name: "CrateUI", targets: ["CrateUI"]),
    ],
    targets: [
        .target(
            name: "CrateKit",
            resources: [.copy("Resources/tag_synonyms.json")],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .target(
            name: "CrateUI",
            dependencies: ["CrateKit"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "CrateKitTests",
            dependencies: ["CrateKit"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
