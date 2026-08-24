// Posts one notification through UserNotifications, then exits.
//
// Compose's `TrayState.sendNotification` reaches macOS through
// `java.awt.TrayIcon.displayMessage`, and the JDK implements that with
// `NSUserNotificationCenter` — deprecated in 10.14 and inert since. The call
// returns cleanly and nothing is ever delivered, which is why notifications
// work in the Windows build (AWT there calls `Shell_NotifyIcon`, still
// supported) and silently do nothing on macOS.
//
// This runs as a separate executable rather than through JNA because
// `UNUserNotificationCenter` needs a real bundle identity, and a binary sitting
// in `Contents/MacOS/` inherits the enclosing app's: `Bundle.main` here
// resolves to com.zillit.desktop, so the banner is attributed to Zillit rather
// than to a sidecar. It must be signed with the app's identifier for the
// notification daemon to accept that claim — see resignWithFrameworks.
//
//   zillit-notify <title> <body>
//
// Exit code is 0 whether or not the banner appeared; a notification is an
// aside, and the caller treats delivery as best-effort. Diagnostics go to
// stderr for the Kotlin side to log.

import AppKit
import Foundation
import UserNotifications

let arguments = CommandLine.arguments
let title = arguments.count > 1 ? arguments[1] : "Zillit"
let body = arguments.count > 2 ? arguments[2] : ""

// UNUserNotificationCenter refuses a process that is not a UI application.
// .accessory keeps it out of the Dock and the app switcher.
let application = NSApplication.shared
application.setActivationPolicy(.accessory)

func report(_ message: String) {
    FileHandle.standardError.write("zillit-notify: \(message)\n".data(using: .utf8)!)
}

let center = UNUserNotificationCenter.current()
var finished = false

center.requestAuthorization(options: [.alert, .sound]) { granted, error in
    if let error = error {
        report("authorization failed: \(error.localizedDescription)")
        finished = true
        return
    }
    guard granted else {
        report("notifications are not permitted for this app")
        finished = true
        return
    }

    let content = UNMutableNotificationContent()
    content.title = title
    content.body = body
    content.sound = .default

    let request = UNNotificationRequest(
        identifier: UUID().uuidString, content: content, trigger: nil)

    center.add(request) { addError in
        if let addError = addError {
            report("delivery failed: \(addError.localizedDescription)")
        }
        finished = true
    }
}

// The completion handlers arrive on the framework's own queue, but the main run
// loop has to keep turning for them to be delivered at all. Bounded, so a
// wedged notification daemon cannot leave this process alive behind the app.
let deadline = Date().addingTimeInterval(10)
while !finished && Date() < deadline {
    RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.05))
}
if !finished { report("timed out waiting for the notification centre") }
