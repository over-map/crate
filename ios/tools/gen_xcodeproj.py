#!/usr/bin/env python3
"""Generate ios/Crate.xcodeproj (no third-party tools).

Uses Xcode 16+ "synchronized folders": every file under ios/Crate, ios/CrateWidget and ios/CrateShare is part of
its target automatically, and ios/Shared is part of both the app and the widget, so adding a Swift file never needs
this script again. Re-run only to change targets or build settings:  python3 ios/tools/gen_xcodeproj.py

Bundle IDs are fixed (free Apple IDs may create only 10 App IDs per 7 days): never change them.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

IOS = Path(__file__).resolve().parents[1]
PROJ = IOS / "Crate.xcodeproj"
BUNDLE = "com.overmap.crate"
DEPLOY = "17.0"
VERSION = "1.0.2"
# Apple "Personal Team" of the owner (free Apple ID), set once in Xcode on 2026-10-13. Not a secret.
TEAM = "F2ZGNLP242"

_ids: dict[str, str] = {}


def oid(name: str) -> str:
    """Stable 24-hex object id from a name, so regenerating gives the same file."""
    if name not in _ids:
        _ids[name] = hashlib.sha1(name.encode()).hexdigest()[:24].upper()
    return _ids[name]


def q(v) -> str:
    if isinstance(v, list):
        return "(" + "".join(f"{q(x)}, " for x in v) + ")"
    if isinstance(v, dict):
        return "{" + "".join(f"{k} = {q(x)}; " for k, x in v.items()) + "}"
    s = str(v)
    safe = s and all(c.isalnum() or c in "._/$" for c in s) and not s[0].isdigit() or s.isdigit()
    return s if safe else '"' + s.replace('"', '\\"') + '"'


TARGETS = {
    "Crate": {
        "type": "com.apple.product-type.application", "ext": "app", "bundle": BUNDLE,
        "settings": {
            "INFOPLIST_FILE": "Config/Crate-Info.plist",
            "INFOPLIST_KEY_CFBundleDisplayName": "Crate",
            "INFOPLIST_KEY_UILaunchScreen_Generation": "YES",
            "INFOPLIST_KEY_UIApplicationSceneManifest_Generation": "YES",
            "INFOPLIST_KEY_UISupportedInterfaceOrientations": "UIInterfaceOrientationPortrait",
            "INFOPLIST_KEY_UIUserInterfaceStyle": "Automatic",
            "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
            "CODE_SIGN_ENTITLEMENTS": "Config/Crate.entitlements",
            "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks"],
        },
        "embed": ["CrateWidget", "CrateShare"],
    },
    "CrateWidget": {
        "type": "com.apple.product-type.app-extension", "ext": "appex", "bundle": BUNDLE + ".widget",
        "settings": {
            "INFOPLIST_FILE": "Config/CrateWidget-Info.plist",
            "INFOPLIST_KEY_CFBundleDisplayName": "Crate",
            "CODE_SIGN_ENTITLEMENTS": "Config/CrateWidget.entitlements",
            "SKIP_INSTALL": "YES", "STRIP_INSTALLED_PRODUCT": "NO",
            "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks", "@executable_path/../../Frameworks"],
        },
    },
    "CrateShare": {
        "type": "com.apple.product-type.app-extension", "ext": "appex", "bundle": BUNDLE + ".share",
        "settings": {
            "INFOPLIST_FILE": "Config/CrateShare-Info.plist",
            "INFOPLIST_KEY_CFBundleDisplayName": "Crate",
            "CODE_SIGN_ENTITLEMENTS": "Config/CrateShare.entitlements",
            "SKIP_INSTALL": "YES", "STRIP_INSTALLED_PRODUCT": "NO",
            "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks", "@executable_path/../../Frameworks"],
        },
    },
}
PRODUCTS = ["CrateKit", "CrateUI"]
# ios/Shared is compiled into these targets too (the App Intent runs in the app for Siri and in the widget for its
# buttons, so both need the same type).
SHARED = ["Crate", "CrateWidget"]


def main() -> None:
    o: dict[str, dict] = {}
    proj = oid("project")
    pkg = oid("pkg:CrateKit")
    o[pkg] = {"isa": "XCLocalSwiftPackageReference", "relativePath": "CrateKit"}

    main_children, product_refs, target_ids = [], [], []
    for name, t in TARGETS.items():
        grp = oid(f"group:{name}")
        o[grp] = {"isa": "PBXFileSystemSynchronizedRootGroup", "path": name, "sourceTree": "<group>"}
        main_children.append(grp)
        ref = oid(f"product:{name}")
        o[ref] = {"isa": "PBXFileReference", "explicitFileType": "wrapper.application" if t["ext"] == "app" else "wrapper.app-extension",
                  "includeInIndex": "0", "path": f"{name}.{t['ext']}", "sourceTree": "BUILT_PRODUCTS_DIR"}
        product_refs.append(ref)

    shared_grp = oid("group:Shared")
    o[shared_grp] = {"isa": "PBXFileSystemSynchronizedRootGroup", "path": "Shared", "sourceTree": "<group>"}
    main_children.append(shared_grp)

    config_grp = oid("group:Config")
    o[config_grp] = {"isa": "PBXGroup", "children": [], "path": "Config", "sourceTree": "<group>"}
    products_grp = oid("group:Products")
    o[products_grp] = {"isa": "PBXGroup", "children": product_refs, "name": "Products", "sourceTree": "<group>"}
    mainGroup = oid("group:main")
    o[mainGroup] = {"isa": "PBXGroup", "children": main_children + [config_grp, products_grp], "sourceTree": "<group>"}

    for name, t in TARGETS.items():
        tid = oid(f"target:{name}")
        target_ids.append(tid)
        deps, frameworks, pkg_deps = [], [], []
        for prod in PRODUCTS:
            pd = oid(f"pkgdep:{name}:{prod}")
            o[pd] = {"isa": "XCSwiftPackageProductDependency", "package": pkg, "productName": prod}
            pkg_deps.append(pd)
            bf = oid(f"bf:{name}:{prod}")
            o[bf] = {"isa": "PBXBuildFile", "productRef": pd}
            frameworks.append(bf)
        src = oid(f"src:{name}")
        o[src] = {"isa": "PBXSourcesBuildPhase", "buildActionMask": "2147483647", "files": [], "runOnlyForDeploymentPostprocessing": "0"}
        fw = oid(f"fw:{name}")
        o[fw] = {"isa": "PBXFrameworksBuildPhase", "buildActionMask": "2147483647", "files": frameworks, "runOnlyForDeploymentPostprocessing": "0"}
        res = oid(f"res:{name}")
        o[res] = {"isa": "PBXResourcesBuildPhase", "buildActionMask": "2147483647", "files": [], "runOnlyForDeploymentPostprocessing": "0"}
        phases = [src, fw, res]
        if t.get("embed"):
            files = []
            for ext in t["embed"]:
                bf = oid(f"embed:{ext}")
                o[bf] = {"isa": "PBXBuildFile", "fileRef": oid(f"product:{ext}"), "settings": {"ATTRIBUTES": ["RemoveHeadersOnCopy"]}}
                files.append(bf)
                proxy = oid(f"proxy:{ext}")
                o[proxy] = {"isa": "PBXContainerItemProxy", "containerPortal": proj, "proxyType": "1",
                            "remoteGlobalIDString": oid(f"target:{ext}"), "remoteInfo": ext}
                dep = oid(f"dep:{name}:{ext}")
                o[dep] = {"isa": "PBXTargetDependency", "target": oid(f"target:{ext}"), "targetProxy": proxy}
                deps.append(dep)
            emb = oid(f"embedphase:{name}")
            o[emb] = {"isa": "PBXCopyFilesBuildPhase", "buildActionMask": "2147483647", "dstPath": "", "dstSubfolderSpec": "13",
                      "files": files, "name": "Embed Foundation Extensions", "runOnlyForDeploymentPostprocessing": "0"}
            phases.append(emb)

        cfgs = []
        for c in ("Debug", "Release"):
            cid = oid(f"cfg:{name}:{c}")
            settings = {
                "PRODUCT_BUNDLE_IDENTIFIER": t["bundle"], "PRODUCT_NAME": "$(TARGET_NAME)", "GENERATE_INFOPLIST_FILE": "YES",
                "CODE_SIGN_STYLE": "Automatic", "DEVELOPMENT_TEAM": TEAM, "MARKETING_VERSION": VERSION, "CURRENT_PROJECT_VERSION": "1",
                "TARGETED_DEVICE_FAMILY": "1", "SWIFT_VERSION": "6.0", "SWIFT_STRICT_CONCURRENCY": "complete",
                "IPHONEOS_DEPLOYMENT_TARGET": DEPLOY, "SDKROOT": "iphoneos", "SWIFT_EMIT_LOC_STRINGS": "YES",
                **t["settings"],
            }
            o[cid] = {"isa": "XCBuildConfiguration", "buildSettings": settings, "name": c}
            cfgs.append(cid)
        cl = oid(f"cfglist:{name}")
        o[cl] = {"isa": "XCConfigurationList", "buildConfigurations": cfgs, "defaultConfigurationIsVisible": "0", "defaultConfigurationName": "Release"}
        o[tid] = {"isa": "PBXNativeTarget", "buildConfigurationList": cl, "buildPhases": phases, "buildRules": [], "dependencies": deps,
                  "fileSystemSynchronizedGroups": [oid(f"group:{name}")] + ([shared_grp] if name in SHARED else []), "name": name, "packageProductDependencies": pkg_deps,
                  "productName": name, "productReference": oid(f"product:{name}"), "productType": t["type"]}

    pcfgs = []
    for c in ("Debug", "Release"):
        cid = oid(f"cfg:project:{c}")
        s = {"ALWAYS_SEARCH_USER_PATHS": "NO", "CLANG_ENABLE_MODULES": "YES", "ENABLE_STRICT_OBJC_MSGSEND": "YES",
             # Xcode 27 "recommended settings" (kept here so regenerating never brings the prompt back)
             "CLANG_ENABLE_OBJC_ARC": "YES", "CLANG_ENABLE_OBJC_WEAK": "YES", "CLANG_ANALYZER_NONNULL": "YES",
             "CLANG_WARN_BLOCK_CAPTURE_AUTORELEASING": "YES", "CLANG_WARN_BOOL_CONVERSION": "YES",
             "CLANG_WARN_COMMA": "YES", "CLANG_WARN_CONSTANT_CONVERSION": "YES",
             "CLANG_WARN_DEPRECATED_OBJC_IMPLEMENTATIONS": "YES", "CLANG_WARN_DIRECT_OBJC_ISA_USAGE": "YES_ERROR",
             "CLANG_WARN_DOCUMENTATION_COMMENTS": "YES", "CLANG_WARN_EMPTY_BODY": "YES", "CLANG_WARN_ENUM_CONVERSION": "YES",
             "CLANG_WARN_INFINITE_RECURSION": "YES", "CLANG_WARN_INT_CONVERSION": "YES",
             "CLANG_WARN_NON_LITERAL_NULL_CONVERSION": "YES", "CLANG_WARN_OBJC_IMPLICIT_RETAIN_SELF": "YES",
             "CLANG_WARN_OBJC_LITERAL_CONVERSION": "YES", "CLANG_WARN_OBJC_ROOT_CLASS": "YES_ERROR",
             "CLANG_WARN_QUOTED_INCLUDE_IN_FRAMEWORK_HEADER": "YES", "CLANG_WARN_RANGE_LOOP_ANALYSIS": "YES",
             "CLANG_WARN_STRICT_PROTOTYPES": "YES", "CLANG_WARN_SUSPICIOUS_MOVE": "YES",
             "CLANG_WARN_UNGUARDED_AVAILABILITY": "YES_AGGRESSIVE", "CLANG_WARN_UNREACHABLE_CODE": "YES",
             "CLANG_WARN__DUPLICATE_METHOD_MATCH": "YES", "GCC_C_LANGUAGE_STANDARD": "gnu17", "GCC_NO_COMMON_BLOCKS": "YES",
             "GCC_WARN_64_TO_32_BIT_CONVERSION": "YES", "GCC_WARN_ABOUT_RETURN_TYPE": "YES_ERROR",
             "GCC_WARN_UNDECLARED_SELECTOR": "YES", "GCC_WARN_UNINITIALIZED_AUTOS": "YES_AGGRESSIVE",
             "GCC_WARN_UNUSED_FUNCTION": "YES", "GCC_WARN_UNUSED_VARIABLE": "YES", "ENABLE_USER_SCRIPT_SANDBOXING": "YES",
             "DEAD_CODE_STRIPPING": "YES", "STRING_CATALOG_GENERATE_SYMBOLS": "YES", "MTL_FAST_MATH": "YES",
             "COPY_PHASE_STRIP": "NO",
             "ENABLE_USER_SCRIPT_SANDBOXING": "YES", "IPHONEOS_DEPLOYMENT_TARGET": DEPLOY, "SDKROOT": "iphoneos",
             "SWIFT_VERSION": "6.0", "ASSETCATALOG_COMPILER_GENERATE_SWIFT_ASSET_SYMBOL_EXTENSIONS": "YES",
             "LOCALIZATION_PREFERS_STRING_CATALOGS": "YES"}
        if c == "Debug":
            s.update({"DEBUG_INFORMATION_FORMAT": "dwarf", "ONLY_ACTIVE_ARCH": "YES", "SWIFT_OPTIMIZATION_LEVEL": "-Onone",
                      "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG $(inherited)", "ENABLE_TESTABILITY": "YES"})
        else:
            s.update({"DEBUG_INFORMATION_FORMAT": "dwarf-with-dsym", "SWIFT_COMPILATION_MODE": "wholemodule", "VALIDATE_PRODUCT": "YES"})
        o[cid] = {"isa": "XCBuildConfiguration", "buildSettings": s, "name": c}
        pcfgs.append(cid)
    pcl = oid("cfglist:project")
    o[pcl] = {"isa": "XCConfigurationList", "buildConfigurations": pcfgs, "defaultConfigurationIsVisible": "0", "defaultConfigurationName": "Release"}
    o[proj] = {"isa": "PBXProject", "attributes": {"BuildIndependentTargetsInParallel": "1", "LastSwiftUpdateCheck": "2700",
                                                   "LastUpgradeCheck": "2700"},
               "buildConfigurationList": pcl, "developmentRegion": "en", "hasScannedForEncodings": "0", "knownRegions": ["en", "Base"],
               "mainGroup": mainGroup, "minimizedProjectReferenceProxies": "1", "packageReferences": [pkg],
               "preferredProjectObjectVersion": "77", "productRefGroup": products_grp, "projectDirPath": "", "projectRoot": "",
               "targets": target_ids}

    lines = ["// !$*UTF8*$!", "{", "\tarchiveVersion = 1;", "\tclasses = {", "\t};", "\tobjectVersion = 77;", "\tobjects = {"]
    for k in sorted(o):
        body = "".join(f"{key} = {q(val)}; " for key, val in o[k].items())
        lines.append(f"\t\t{k} = {{{body}}};")
    lines += ["\t};", f"\trootObject = {proj};", "}", ""]
    PROJ.mkdir(exist_ok=True)
    (PROJ / "project.pbxproj").write_text("\n".join(lines))
    print(f"wrote {PROJ.relative_to(IOS)}/project.pbxproj ({len(o)} objects)")


if __name__ == "__main__":
    main()
