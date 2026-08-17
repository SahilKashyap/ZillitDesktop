# Spike: OS keychain — secret custody

**Question:** where does the desktop app keep the database key, auth tokens and
device keys, on macOS, Windows and ChromeOS?

**Answer: the operating system's own credential store.** Implemented, and
verified end to end against the real macOS Keychain.

Code: [`core:security`](../../core/security) ·
Tests: `KeychainSecureStoreTest` (8), `DatabaseKeyManagerTest` (7),
`ZillitDatabaseFactoryTest` (5)

---

## 1. Why this had to exist before encryption was real

The [SQLCipher spike](sqlcipher.md) proved the database *can* be encrypted. It
did not make the app encrypt anything, because there was nowhere to keep the
key. A key stored beside the ciphertext protects nothing.

This closes that loop. `ZillitDatabaseFactory` now generates a 256-bit key on
first launch, files it in the OS credential store, and reopens the database with
it on every subsequent launch.

## 2. Choice

| Candidate | Verdict |
|---|---|
| Shelling out to `security` (macOS) / `secret-tool` | **Rejected.** `security add-generic-password -w <secret>` puts the secret in process arguments, visible to any `ps` on the machine |
| Hand-written JNA bindings | Viable but ~400 lines across three platforms, of which I can test one. Reserved as the fallback |
| **`com.github.javakeyring:java-keyring` 1.0.4** | **Adopted** |

`java-keyring` is a 29 KB JNA binding over:

| Platform | Backend |
|---|---|
| macOS | Keychain Services (`Security.framework`) |
| Windows | Credential Manager (`Advapi32` `CredRead`/`CredWrite`) |
| Linux / ChromeOS | Freedesktop Secret Service, or KWallet on KDE |

**Staleness noted:** last release Aug 2023. Acceptable for a thin binding over
APIs that have not changed in a decade, and it sits behind our own `SecureStore`
interface so replacing it is one file. Flagged rather than hidden.

## 3. Design

```
SecureStore            (commonMain) — interface, closed SecureKey enum
  └── KeychainSecureStore   (jvmMain) — OS-backed implementation
DatabaseKeyManager     (jvmMain)  — generate-once custody of the DB key
ZillitDatabaseFactory  (jvmMain)  — joins key custody to the SQLCipher driver
```

`SecureKey` is an enum, not free-form strings, so a typo cannot silently create
a second entry that never gets cleared on sign-out — which is how credentials
outlive the session that owned them.

### Fails closed, deliberately

If the keychain is unavailable, the database does **not** open unencrypted and
the key is **not** derived from anything guessable. A silently-unencrypted
database that appears to work is the worst outcome available, so an unusable
keychain is a hard stop the app must surface.
`an unavailable keychain fails closed rather than opening unencrypted` asserts
that no database file is even created in that case.

### Sign-out and remote revoke

`SecureStore.clear()` deletes the **database key first**, so an interrupted wipe
still leaves an unreadable database rather than a readable one. It then
continues past individual failures rather than stopping at the first — this runs
on remote device revoke, where leaving entries behind is the bad outcome.

## 4. What is verified

Against the **real macOS Keychain**, not a mock:

| Test | Proves |
|---|---|
| `round-trips a secret through the OS store` | Basic custody |
| `binary values survive intact` | Arbitrary bytes — embedded nulls, high bytes — survive the String-based API via Base64 |
| `a missing entry reads as null rather than failing` | First launch is not an error |
| `writing twice replaces rather than duplicating` | No entry accumulation |
| `delete … is idempotent` | Sign-out runs unconditionally |
| `clear removes every entry` | Nothing survives sign-out |
| `the database key survives across store instances` | The launch-to-launch case |
| **`survives a simulated app restart`** | **End to end** — new objects throughout, key from the OS, encrypted data read back |
| `the file on disk is encrypted` | No plaintext, no SQLite header |
| `losing the key makes the database unreadable` | Remote revoke actually revokes |
| `destroy removes both the key and the file` | Clean uninstall |

Externally confirmed with `security find-generic-password` that entries land in
the login keychain and that the tests clean up after themselves.

## 5. Known limitation — secrets pass through a JVM String

`java-keyring`'s API is `String`-based, so a secret exists briefly as a JVM
`String` on the way in and out. Strings cannot be zeroed and may linger in a
heap dump.

Everything downstream uses `ByteArray` and zeroes it — `DatabaseKeyManager`,
`EncryptedDriverFactory` and `CryptoKeyMaterial` all do — so the exposure is
bounded to the moment of transfer. It is not nil.

Removing it means binding `SecItemAdd` / `CredWriteW` directly against byte
buffers, which is the hand-written-JNA fallback. Worth doing only if a threat
model says heap-dump exposure matters here; an attacker who can dump the app's
heap can generally also read the decrypted database.

**Decision: accept, documented.** Revisit at the §8.8 threat-modelling step.

## 6. Outstanding

1. **Windows and Linux are unverified.** The bindings exist and the code
   compiles; nobody has run them. `reports availability for this platform`
   asserts that macOS and Windows must always have a usable store, so a broken
   Windows binding fails CI rather than reaching a user.
2. **Headless Linux has no Secret Service.** The keychain tests skip on such a
   machine rather than fail — a deliberate trade, since failing on every
   headless runner trains people to ignore the result. But **the Crostini
   packaging story (plan §1) must answer what happens with no keyring daemon.**
   Options: require `gnome-keyring`, ship a passphrase-derived key as a
   documented downgrade, or refuse to run. Not decided.
3. **Not yet wired into the running app.** `ZillitDatabaseFactory` exists and is
   tested, but `desktopApp` does not construct it — there is no schema worth
   persisting until a feature module needs one.
4. **Idle-lock re-auth** (plan §8.5) — re-locking the key after an idle timeout
   is not implemented.
