package com.zillit.desktop.feature.auth.data

import java.security.SecureRandom

/**
 * Generates a QR login code with cryptographic randomness.
 *
 * The code is a **credential**: whoever presents it to an already signed-in
 * device gets a linked device. A predictable one would let an attacker
 * pre-compute codes and race a real sign-in, so this uses [SecureRandom] rather
 * than `kotlin.random.Random`.
 *
 * The shape (125 chars + `ZILLIT` + 125 chars) matches the web client, which is
 * the flow this screen reproduces.
 */
fun secureLoginCode(random: SecureRandom = SecureRandom()): String =
    generateLoginCode { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.size)] }

/**
 * A device id in the web client's format: `<uuid>_<epoch millis>`
 * (`generate.js: generateAndStoreDeviceId`).
 *
 * Minted fresh per login attempt, matching the web. It is an identifier the
 * device claims, not a secret it proves — the scan is what grants it meaning.
 */
fun generateDeviceId(nowMillis: () -> Long = System::currentTimeMillis): String =
    "${java.util.UUID.randomUUID()}_${nowMillis()}"
