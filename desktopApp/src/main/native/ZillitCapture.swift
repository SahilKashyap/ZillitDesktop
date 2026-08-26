// Lists the screens and windows that can be shared, then exits.
//
// The call page runs in embedded Chromium, and an embedded Chromium has no
// picker: Chrome's own "Choose what to share" dialog lives in the browser
// shell, not in the content layer CEF exposes. So the app draws its own — and
// a Compose dialog needs a list of sources with pictures, which is what this
// prints.
//
// A separate executable rather than JNA because the API that can do this is
// ScreenCaptureKit, and the one that could have been called from JNA,
// `CGWindowListCreateImage`, no longer exists in the macOS 26 SDK. Sitting in
// `Contents/MacOS/` it also inherits the app's bundle identity, so the Screen
// Recording permission it needs is attributed to Zillit rather than to a
// nameless helper — the same reasoning as zillit-notify, and it must be signed
// with the app's identifier for the same reason.
//
//   zillit-capture [--max-thumbs N]
//
// Output is JSON Lines on stdout, in this order:
//
//   {"type":"sources","displays":[…],"windows":[…]}
//   {"type":"thumb","id":"screen:1:0","png":"<base64>"}
//   …
//   {"type":"done"}
//
// The list comes first and the pictures follow one at a time, because
// enumerating is instant while each thumbnail is a real screen capture: the
// picker can draw every tile immediately and fill in the images as they land,
// rather than showing nothing until the slowest one finishes.
//
// Source ids are Chromium's own DesktopMediaID spelling — `screen:<CGDirectDisplayID>:0`
// and `window:<CGWindowID>:0` — because that string is passed to the page
// verbatim and handed to getUserMedia's `chromeMediaSourceId`. Inventing our
// own id here would mean translating it back somewhere else.
//
// Diagnostics go to stderr. A non-zero exit means the list could not be built
// at all; the caller falls back to sharing the whole screen.

import AppKit
import Foundation
import ScreenCaptureKit

// SCScreenshotManager trips an assertion inside CoreGraphics ("CGS_REQUIRE_INIT")
// when the calling process has never initialised the window server connection.
// A plain CLI tool has not; touching NSApplication is what does it. .accessory
// keeps the helper out of the Dock and the app switcher.
let application = NSApplication.shared
application.setActivationPolicy(.accessory)

func report(_ message: String) {
    FileHandle.standardError.write("zillit-capture: \(message)\n".data(using: .utf8)!)
}

func emit(_ line: String) {
    FileHandle.standardOutput.write((line + "\n").data(using: .utf8)!)
}

/// How many thumbnails to capture before giving up on the rest.
///
/// Every one is a real capture, so a machine with eighty windows open would
/// otherwise spend seconds on tiles nobody scrolls to. The listed sources are
/// unaffected — a source without a picture still shares.
var maxThumbs = 24
if let flag = CommandLine.arguments.firstIndex(of: "--max-thumbs"),
   flag + 1 < CommandLine.arguments.count,
   let parsed = Int(CommandLine.arguments[flag + 1]) {
    maxThumbs = max(0, parsed)
}

/// JSON string escaping, hand-rolled to keep the helper dependency-free.
func quoted(_ value: String) -> String {
    var out = "\""
    for character in value.unicodeScalars {
        switch character {
        case "\"": out += "\\\""
        case "\\": out += "\\\\"
        case "\n": out += "\\n"
        case "\r": out += "\\r"
        case "\t": out += "\\t"
        default:
            if character.value < 0x20 {
                out += String(format: "\\u%04x", character.value)
            } else {
                out.unicodeScalars.append(character)
            }
        }
    }
    return out + "\""
}

let THUMB_WIDTH = 320

func thumbnail(_ filter: SCContentFilter, width: Int, height: Int) async -> String? {
    guard width > 0, height > 0 else { return nil }
    let configuration = SCStreamConfiguration()
    let scale = Double(THUMB_WIDTH) / Double(width)
    configuration.width = THUMB_WIDTH
    configuration.height = max(1, Int((Double(height) * scale).rounded()))
    configuration.showsCursor = false
    do {
        let image = try await SCScreenshotManager.captureImage(
            contentFilter: filter, configuration: configuration)
        let bitmap = NSBitmapImageRep(cgImage: image)
        guard let png = bitmap.representation(using: .png, properties: [:]) else { return nil }
        return png.base64EncodedString()
    } catch {
        report("thumbnail failed: \(error.localizedDescription)")
        return nil
    }
}

/// Windows worth offering.
///
/// The shareable list includes a great deal nobody means by "a window": the
/// desktop picture, menu-bar extras, off-screen scratch windows, and our own
/// call window — which, offered and picked, would hall-of-mirrors the call.
func isOfferable(_ window: SCWindow) -> Bool {
    guard window.isOnScreen else { return false }
    // Layer 0 is the ordinary document layer; everything above it is chrome.
    guard window.windowLayer == 0 else { return false }
    guard window.frame.width >= 120, window.frame.height >= 120 else { return false }
    let owner = window.owningApplication?.bundleIdentifier ?? ""
    if owner == Bundle.main.bundleIdentifier { return false }
    let title = window.title ?? ""
    return !title.isEmpty
}

func run() async {
    let content: SCShareableContent
    do {
        content = try await SCShareableContent.excludingDesktopWindows(
            true, onScreenWindowsOnly: true)
    } catch {
        // Overwhelmingly this is Screen Recording permission being absent —
        // the one screen-share failure that genuinely is a permission problem.
        report("cannot list shareable content: \(error.localizedDescription)")
        exit(2)
    }

    let displays = content.displays
    let windows = content.windows.filter(isOfferable).sorted {
        let leftApp = $0.owningApplication?.applicationName ?? ""
        let rightApp = $1.owningApplication?.applicationName ?? ""
        if leftApp != rightApp { return leftApp < rightApp }
        return ($0.title ?? "") < ($1.title ?? "")
    }

    let displayJson = displays.enumerated().map { index, display in
        """
        {"id":"screen:\(display.displayID):0",\
        "name":\(quoted(displays.count > 1 ? "Screen \(index + 1)" : "Entire screen")),\
        "width":\(display.width),"height":\(display.height)}
        """
    }.joined(separator: ",")

    let windowJson = windows.map { window in
        """
        {"id":"window:\(window.windowID):0",\
        "app":\(quoted(window.owningApplication?.applicationName ?? "")),\
        "title":\(quoted(window.title ?? "")),\
        "width":\(Int(window.frame.width)),"height":\(Int(window.frame.height))}
        """
    }.joined(separator: ",")

    emit("{\"type\":\"sources\",\"displays\":[\(displayJson)],\"windows\":[\(windowJson)]}")

    // Screens first: they are the common choice, and the picker shows them at
    // the top, so their pictures should be the ones that arrive first.
    var budget = maxThumbs
    for display in displays where budget > 0 {
        budget -= 1
        let filter = SCContentFilter(display: display, excludingWindows: [])
        if let png = await thumbnail(filter, width: display.width, height: display.height) {
            emit("{\"type\":\"thumb\",\"id\":\"screen:\(display.displayID):0\",\"png\":\(quoted(png))}")
        }
    }
    for window in windows where budget > 0 {
        budget -= 1
        let filter = SCContentFilter(desktopIndependentWindow: window)
        let png = await thumbnail(
            filter, width: Int(window.frame.width), height: Int(window.frame.height))
        if let png = png {
            emit("{\"type\":\"thumb\",\"id\":\"window:\(window.windowID):0\",\"png\":\(quoted(png))}")
        }
    }

    emit("{\"type\":\"done\"}")
    exit(0)
}

Task { await run() }

// The captures complete on ScreenCaptureKit's own queue, and the main run loop
// has to keep turning for them to be delivered. Bounded so a wedged capture
// daemon cannot leave this helper alive behind the app; run() exits on its own
// long before this in the normal case.
let deadline = Date().addingTimeInterval(20)
while Date() < deadline {
    RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.05))
}
report("timed out building the source list")
exit(3)
