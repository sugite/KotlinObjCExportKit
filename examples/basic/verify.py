#!/usr/bin/env python3
"""Build the sample, check its public surface, and run the Swift iOS tests."""

import argparse
import json
import os
import platform
from pathlib import Path
import plistlib
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
EXAMPLE = ROOT / "examples/basic"
OUTPUT = EXAMPLE / "build/verification"
CHECKS = []


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def passed(name):
    CHECKS.append(name)
    print(f"PASS: {name}", flush=True)


def run(name, command, *, input_text=None, expect_failure=False, env=None):
    print(f"Running: {name}", flush=True)
    result = subprocess.run(
        command, cwd=ROOT, input=input_text, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env,
    )
    (OUTPUT / f"{name}.log").write_text(result.stdout)
    if expect_failure:
        require(result.returncode > 0, f"{name} unexpectedly compiled or terminated abnormally")
    else:
        require(result.returncode == 0, f"{name} failed; see {OUTPUT / (name + '.log')}\n{result.stdout[-6000:]}")
    return result.stdout


def framework(target):
    return EXAMPLE / f"shared/build/bin/{target}/debugFramework/DemoKit.framework"


def check_headers(target, sdk, triple):
    folder = framework(target)
    headers = {path.name: path.read_bytes() for path in (folder / "Headers").glob("*.h")}
    expected = {"DemoKit.h", "DemoKitBase.h", "DemoKitModels.h", "DemoKitServices.h"}
    require(set(headers) == expected, f"{target}: unexpected header groups: {sorted(headers)}")
    definitions = {
        "DemoKitPerson": ("DemoKitModels.h", "interface"),
        "DemoKitGreetingState": ("DemoKitModels.h", "interface"),
        "DemoKitGreetingService": ("DemoKitServices.h", "interface"),
        "DemoKitGreetingListener": ("DemoKitServices.h", "protocol"),
    }
    for name, (owner, kind) in definitions.items():
        pattern = rf"^@{kind}\s+{name}(?=\s|:|$)"
        locations = [file for file, content in headers.items() if re.search(pattern, content.decode(), re.MULTILINE)]
        require(locations == [owner], f"{target}: {name} belongs to {locations}, expected {owner}")
    umbrella = headers["DemoKit.h"].decode()
    imports = re.findall(r'^#import "([^"]+)"', umbrella, re.MULTILINE)
    require(imports == ["DemoKitBase.h", "DemoKitModels.h", "DemoKitServices.h"], "Unexpected umbrella imports/order")
    all_text = "\n".join(data.decode() for data in headers.values())
    for hidden in ["diagnosticMessage", "InternalDiagnostics", 'swift_name("internal")']:
        require(hidden not in all_text, f"Unexpected public declaration: {hidden}")
    models = headers["DemoKitModels.h"].decode()
    require('swift_name("ready")' in models and 'swift_name("done")' in models, "Selected enum entries are missing")
    require(re.search(r"@property\s*\(readonly\).*\bname\b", models), "Person.name must be read-only")
    services = headers["DemoKitServices.h"].decode()
    require(re.search(r"@property BOOL enabled\b", services), "GreetingService.enabled must be writable")
    manifest = (folder / "Modules/objc-export-headers.txt").read_text().splitlines()
    require(manifest == sorted(expected), "Unexpected generated-header ownership manifest")
    modulemap = (folder / "Modules/module.modulemap").read_text()
    require('umbrella header "DemoKit.h"' in modulemap and "module *" not in modulemap, "Unexpected modulemap")
    for name in sorted(headers):
        run(f"{target}-{name}-syntax", [
            "xcrun", "clang", "-fsyntax-only", "-x", "objective-c",
            "-target", triple, "-isysroot", sdk, str(folder / "Headers" / name),
        ])
    passed(f"{target}: exact header groups, selected/hidden API, modulemap, and independent header imports")
    return headers


def check_xcframework(reference_headers):
    folder = EXAMPLE / "shared/build/XCFrameworks/debug/DemoKit.xcframework"
    with (folder / "Info.plist").open("rb") as file:
        libraries = plistlib.load(file)["AvailableLibraries"]
    require(len(libraries) == 2, "Expected exactly a device slice and a simulator slice")
    variants = set()
    for item in libraries:
        require(item["SupportedPlatform"] == "ios", "Unexpected platform in XCFramework")
        require(item["SupportedArchitectures"] == ["arm64"], "Only arm64 slices should be present")
        variant = item.get("SupportedPlatformVariant", "device")
        variants.add(variant)
        target = "iosSimulatorArm64" if variant == "simulator" else "iosArm64"
        headers = folder / item["LibraryIdentifier"] / item["LibraryPath"] / "Headers"
        actual = {path.name: path.read_bytes() for path in headers.glob("*.h")}
        require(actual == reference_headers[target], f"XCFramework headers changed for {variant}")
    require(variants == {"device", "simulator"}, "Missing device/simulator variant")
    passed("XCFramework: exactly two arm64 slices with unchanged grouped headers")


def check_negative_swift(sdk):
    cases = {
        "hidden-method": (
            'let service = GreetingService()\n_ = service.diagnosticMessage()\n',
            ["has no member 'diagnosticMessage'"],
        ),
        "hidden-type": (
            'let _ = InternalDiagnostics(message: "hidden")\n',
            ["cannot find 'InternalDiagnostics' in scope"],
        ),
        "unselected-enum-entry": (
            'let _ = GreetingState.`internal`\n',
            ["has no member 'internal'"],
        ),
        "readonly-property": (
            'let person = Person(name: "Ada")\nperson.name = "Grace"\n',
            ["cannot assign to property", "get-only property"],
        ),
    }
    for name, (source, diagnostics) in cases.items():
        output = run(name, [
            "xcrun", "swiftc", "-typecheck", "-sdk", sdk,
            "-target", "arm64-apple-ios15.0-simulator",
            "-F", str(framework("iosSimulatorArm64").parent), "-",
        ], input_text="import DemoKit\n" + source, expect_failure=True)
        require(all(text in output for text in diagnostics), f"{name}: failed for an unexpected reason; inspect its log")
        passed(f"Swift rejects {name} with the expected diagnostic")


def select_simulator(requested):
    data = json.loads(run("simulators", ["xcrun", "simctl", "list", "devices", "available", "--json"]))
    devices = [device for runtime, entries in data["devices"].items() if ".iOS-" in runtime for device in entries]
    if requested:
        selected = next((device for device in devices if device["udid"] == requested), None)
        require(selected is not None, "The requested simulator is not available")
        return selected
    phones = [device for device in devices if device["name"].startswith("iPhone")]
    require(phones, "Install an iOS simulator runtime and an iPhone simulator in Xcode")
    return next((device for device in phones if device["state"] == "Booted"), phones[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--simulator", help="Use an available iOS simulator UDID instead of selecting an iPhone")
    args = parser.parse_args()
    require(platform.system() == "Darwin" and platform.machine() == "arm64", "This demo requires an Apple Silicon Mac")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["JAVA_HOME"] = run("jdk", ["/usr/libexec/java_home", "-F", "-v", "21"]).strip()
    run("framework-build", [
        str(ROOT / "gradlew"), "-p", "examples/basic",
        ":shared:assembleDemoKitDebugXCFramework", "--console=plain",
    ], env=env)
    simulator_sdk = run("simulator-sdk", ["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"]).strip()
    device_sdk = run("device-sdk", ["xcrun", "--sdk", "iphoneos", "--show-sdk-path"]).strip()
    headers = {
        "iosArm64": check_headers("iosArm64", device_sdk, "arm64-apple-ios15.0"),
        "iosSimulatorArm64": check_headers("iosSimulatorArm64", simulator_sdk, "arm64-apple-ios15.0-simulator"),
    }
    check_xcframework(headers)
    check_negative_swift(simulator_sdk)
    device = select_simulator(args.simulator)
    if device["state"] != "Booted":
        run("boot-simulator", ["xcrun", "simctl", "boot", device["udid"]])
    run("simulator-ready", ["xcrun", "simctl", "bootstatus", device["udid"], "-b"])
    test_output = run("swift-runtime-tests", [
        "xcodebuild", "-project", "examples/basic/iosApp/KOEKDemo.xcodeproj",
        "-scheme", "KOEKDemo", "-configuration", "Debug",
        "-destination", f"platform=iOS Simulator,id={device['udid']}",
        "-parallel-testing-enabled", "NO", "-derivedDataPath", "examples/basic/build/DerivedData",
        "CODE_SIGNING_ALLOWED=NO", "test",
    ])
    require("Executed 5 tests, with 0 failures" in test_output, "Expected all five XCTest cases to execute successfully")
    passed("Five Swift XCTest cases: DTO, method, writable property, callback, and enum identity")
    app = EXAMPLE / "build/DerivedData/Build/Products/Debug-iphonesimulator/KOEKDemo.app"
    run("install-demo", ["xcrun", "simctl", "install", device["udid"], str(app)])
    run("launch-demo", ["xcrun", "simctl", "launch", "--terminate-running-process", device["udid"], "io.github.sugite.koek.demo"])
    passed("Standalone iOS demo installed and launched")


if __name__ == "__main__":
    success = False
    try:
        main()
        success = True
    except (RuntimeError, OSError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
    finally:
        if OUTPUT.is_dir():
            (OUTPUT / "summary.json").write_text(json.dumps({"success": success, "checks": CHECKS}, indent=2) + "\n")
    if not success:
        sys.exit(1)
    print(f"All checks passed. Logs and summary: {OUTPUT}", flush=True)
