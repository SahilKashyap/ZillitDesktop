# Spike: encrypted local database

**Question:** can we encrypt the desktop database at rest on macOS, Windows and
ChromeOS, from one Kotlin Multiplatform codebase?

**Answer: yes.** Plan §5 risk 6 is closed for macOS and can be closed for the
others as soon as CI runs. Recommendation: adopt.

Code: [`core:database`](../../core/database) ·
Tests: `EncryptedDriverFactoryTest` (9, all passing)

---

## 1. Candidates

The plan named two libraries from a web search. **Neither is on Maven Central**,
which the plan should not have implied:

| Candidate | Status | Verdict |
|---|---|---|
| `io.github.s0d3s:SQLCipherMultiplatform` | Not on Maven Central | Rejected — JitPack-only for a security-critical dependency is not acceptable |
| `com.github.dttrinh:sqlcipher-jdbc` | Not on Maven Central | Rejected — same |
| `net.zetetic:sqlcipher-android` 4.17.0 | On Central, **Android-only** | Rejected — no JVM desktop support |
| **`io.github.willena:sqlite-jdbc` 3.53.4.0** | **On Maven Central** | **Adopted** |

`io.github.willena:sqlite-jdbc` is a fork of the standard Xerial `sqlite-jdbc`
with [SQLite3MultipleCiphers](https://utelle.github.io/SQLite3MultipleCiphers/)
compiled in. Same driver API, plus page-level encryption. It offers SQLCipher,
ChaCha20, AES-128/256, Ascon128 and AEGIS; we use **SQLCipher v4 defaults**
(AES-256-CBC, HMAC-SHA512, 256k KDF iterations) for interoperability with the
scheme the rest of the industry expects.

## 2. Native coverage — the actual risk

The plan's concern was whether a community driver ships natives for every
target. Verified by inspecting the published jar:

| Target | Native present | Loading verified |
|---|---|---|
| macOS arm64 | ✅ `Mac/aarch64/libsqlitejdbc.dylib` | ✅ **SQLite 3.53.4 loaded** |
| macOS x64 | ✅ `Mac/x86_64/libsqlitejdbc.dylib` | ⏳ CI |
| Windows x64 | ✅ `Windows/x86_64/sqlitejdbc.dll` | ⏳ CI |
| Windows arm64 | ✅ `Windows/aarch64/sqlitejdbc.dll` | ⏳ CI |
| Linux x64 (Crostini) | ✅ `Linux/x86_64/libsqlitejdbc.so` | ⏳ CI |
| Linux arm64 (ARM Chromebooks) | ✅ `Linux/aarch64/libsqlitejdbc.so` | ⏳ CI |

Also ships Linux-Musl, FreeBSD, ppc64, riscv64 and 32-bit variants we don't need.

`the bundled driver reports a platform native for this host` is a test, not a
comment: it fails on any CI runner whose platform has no matching native, with a
clear message, rather than surfacing at runtime in front of a user.

**Remaining unknown:** only macOS arm64 has actually *loaded* the native. The
other five are verified as *present in the jar*. First CI run closes this.

## 3. What the tests prove

`EncryptedDriverFactoryTest`, 9 tests:

| Test | Proves |
|---|---|
| `data written through SQLDelight can be read back` | SQLDelight's generated queries work over the encrypted driver, across a close/reopen |
| `the database file contains no plaintext` | The secret string is absent from the file bytes, **and** so is the `SQLite format 3` header |
| **`an unencrypted database leaks its contents`** | **Control.** The same probe finds the secret in an unencrypted database. Without this the two assertions above could pass because the search was broken |
| `a wrong key fails to open the database` | Wrong key → `ZillitError.Storage`, not silent success |
| `no key at all fails to open the database` | A plain driver cannot read the file |
| `a missing key is reported rather than opening unencrypted` | Keychain failure fails closed — never falls back to plaintext |
| `a key provider that caches without copying is caught` | The key-ownership contract (§5) |
| `encryption overhead …` | Not pathologically slow |
| `the bundled driver reports a platform native …` | Native loads on this host |

The control test is the one that makes the rest meaningful.

## 4. Performance

2,000 single-row upserts in one transaction, macOS arm64 (M-series):

```
encrypted  34 ms
plain      21 ms
```

**~1.6× on a write-heavy path.** Acceptable. Chat history and Account Hub
imports are the workloads that would notice; neither is close to a bottleneck at
this margin. Worth re-measuring on Windows, where filesystem behaviour differs.

## 5. Two hazards found, both now guarded

### 5.1 Duplicate JDBC drivers — silent plaintext

`app.cash.sqldelight:sqlite-driver` supplies `JdbcSqliteDriver` but pulls
`org.xerial:sqlite-jdbc` transitively. Two drivers both registering for
`jdbc:sqlite:` is a coin toss over which handles the URL — **and losing that
toss means the database opens unencrypted while appearing to work.**

Fixed by excluding the transitive Xerial artifact:

```kotlin
implementation(libs.sqldelight.sqliteDriver.get().toString()) {
    exclude(group = "org.xerial", module = "sqlite-jdbc")
}
```

Verified: `jvmRuntimeClasspath` resolves exactly one SQLite JDBC driver.

### 5.2 Key ownership — zeroed-key reuse

The factory zeroes the key array after opening, so it does not linger in the
heap (plan §8.4). That makes `create()` *consume* the key — and a
`DatabaseKeyProvider` that caches one array and returns the same instance twice
hands back 32 zero bytes on its second call.

The failure mode is nasty: `SQLITE_NOTADB — file is not a database`, which reads
like corruption rather than a key problem. Someone would spend a day on it.

The contract is now documented on both sides and asserted by
`a key provider that caches without copying is caught`. Keychain-backed
providers must `return cached.copyOf()`.

*(Found by the round-trip test failing. Worth noting the test caught an API
design flaw, not just a coding slip.)*

## 6. Costs

- **Jar size: 15 MB**, because it bundles natives for ~20 platforms. We ship
  three. Stripping the unused ones at packaging time is a straightforward
  `jpackage` post-step and would recover ~12 MB — worth doing in M12, not now.
- **Community-maintained.** Actively released (3.53.4.0 tracks upstream SQLite
  3.53.4), but it is one maintainer. Mitigation: the driver sits behind
  `EncryptedDriverFactory`, so swapping it is one file. The §5 fallback
  (app-layer AES on sensitive columns) remains available and is about a day's
  work.

## 7. Decision

**Adopt `io.github.willena:sqlite-jdbc` with SQLCipher v4 defaults.**

Follow-ups:

1. **CI must run on all three OSes** before this is considered closed. The
   native-loading test is the gate.
2. **Key custody is not built yet.** `DatabaseKeyProvider` is an interface with
   no production implementation — the OS-keychain backing (macOS Keychain,
   Windows DPAPI, libsecret) is outstanding M1 work. Until then there is no
   place to keep the key, so the database is not yet encrypted in the running
   app.
3. **Strip unused natives at packaging** (M12).
4. **Re-measure performance on Windows.**
