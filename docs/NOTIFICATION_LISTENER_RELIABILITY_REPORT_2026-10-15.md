# Notification Listener Reliability Report (2026-10-15)

## 1. Root Cause Analysis: Facts vs Hypotheses

### Established Facts
- During Phase 2 verification, `capture-previews.bat` executed 15 sequential UI preview captures. Each iteration called `am force-stop app.mini.hub` every ~2 to 5 seconds, repeatedly terminating the host process.
- When `app.mini.hub` was force-stopped, Android's `NotificationManagerService` logged connection loss and `0 notification listener binding died`.
- In commit `0dc0b05`, `MiniNotificationListenerService.onListenerDisconnected()` called `rebindIfNeeded()`, immediately invoking `requestRebind()` on every disconnect.
- Under steady locked and screen-off conditions on Pixel 10 Pro (without force-stop loops), the notification listener remains continuously bound with zero disconnects.

### Inferred / Suspected Mechanisms
- Immediate invocations of `requestRebind()` directly within `onListenerDisconnected()` are suspected of racing Android's internal `ManagedServices` unbind/teardown sequence during rapid process cycling.
- On Android 16 (Pixel 10 Pro `blazer`), this collision is suspected of causing `BoundServiceSession` to encounter mismatched binder transaction tokens (`Bad key 0 received in binderTransactionCompleted! Closing all transactions on CR{...}`), leading to aborted bindings and rate-limiting by `ManagedServices` (`not rebinding ... as a previous rebind attempt was made`).
- Omitting immediate `requestRebind()` from `onListenerDisconnected()` allows Android's `ManagedServices` to execute its scheduled reconnection cycle without interference.

---

## 2. Recovery Architecture and Fix

**Changes applied:**
1. **Removed immediate rebind on disconnect**: `onListenerDisconnected()` updates connection flags and notifies UI, leaving standard reconnection to Android's `ManagedServices`.
2. **Monotonic cooldown tracking**: Timing now uses monotonic elapsed time (`SystemClock.elapsedRealtime()`) rather than wall-clock time (`System.currentTimeMillis()`), immune to clock adjustments.
3. **Bounded single-shot deferred recovery**:
   - When `rebindIfNeeded()` is called during the 15-second cooldown window (e.g., from `MainActivity.onResume()`), a single deferred recovery attempt is scheduled for the remaining cooldown duration.
   - Duplicate scheduled attempts are suppressed while one is already pending.
   - When the deferred runnable executes, it performs a single rebind check. It does not reschedule itself in a loop, avoiding continuous polling or uncontrolled retries.
   - Pending recovery attempts are cancelled immediately if the listener reconnects, if permission is revoked, or if an immediate rebind succeeds.
4. **Diagnostic unbind helper**: `requestUnbindForDebug()` exposes instance-level `requestUnbind()` for testing in-process disconnection without killing the host process.

---

## 3. Source Tests (Unit Regression Suite)

File: `native/androidApp/src/test/kotlin/app/mini/hub/android/notification/MiniNotificationListenerServiceTest.kt`

- `shouldAttemptRebind_returnsFalse_whenAlreadyConnected`: Verified rebind is suppressed when listener is already bound.
- `shouldAttemptRebind_returnsFalse_whenPermissionNotGranted`: Verified rebind is suppressed when notification access is missing.
- `shouldAttemptRebind_returnsFalse_whenWithinTransitionCooldown`: Verified rebind is suppressed within 15 s of a disconnect transition.
- `shouldAttemptRebind_returnsFalse_whenWithinAttemptCooldown`: Verified rebind is suppressed within 15 s of a previous attempt.
- `shouldAttemptRebind_returnsTrue_whenDisconnectedPastCooldownWithPermission`: Verified rebind is allowed when disconnected past 15 s with permission.
- `deferredRecovery_schedulesSingleAttempt_whenWithinCooldown`: Verified deferred attempt is scheduled for remaining cooldown duration.
- `deferredRecovery_suppressesDuplicateScheduling`: Verified repeated triggers during cooldown do not schedule duplicate attempts.
- `deferredRecovery_cancelsOnConnection`: Verified pending recovery is cancelled immediately when listener connects.
- `deferredRecovery_cancelsOnPermissionLoss`: Verified pending recovery is cancelled when permission is revoked.
- `deferredRecovery_executesOnceWithoutLooping`: Verified deferred attempt executes once and does not loop.

All 43 Gradle tasks across `:androidApp:testDebugUnitTest` and `:shared:jvmTest` passed.

---

## 4. Live Device Observations (Pixel 10 Pro, Android 16)

*Note: Notification listener capture is separate from authenticated Gmail API sync and WhatsApp companion bridge sync.*

### Alive-Process Disconnect and Deferred Recovery
- Starting process: PID 23972 (listener connected).
- Triggered `requestUnbind()` via `TRIGGER_UNBIND` broadcast:
  - System logged `Disabling component app.mini.hub/.android.notification.MiniNotificationListenerService`.
  - App logged `listener disconnected` at 13:21:19.665.
  - Process PID remained 23972 (host process survived unbind).
- Triggered `rebindIfNeeded()` during cooldown at 13:21:22.392 (+2.7 s):
  - Cooldown active; scheduled single deferred recovery for the remaining ~12.3 s.
- Deferred recovery fired at 13:21:34.709 (+15.04 s from disconnect):
  - System logged `Enabling component ... binding: Intent { ... }`.
  - Service connected at 13:21:34.723, app logged `listener connected` at 13:21:34.818.
  - Process PID remained 23972 throughout entire cycle. Reconnected cleanly in the same alive process.

### Process Death Restart Recovery
- Process killed via `run-as app.mini.hub kill` while device was locked (`deviceLocked=1`, `isKeyguardShowing=true`, `mWakefulness=Dozing`).
- `ActivityManager` scheduled restart in 1000 ms. Process recreated at +1.08 s, listener reconnected at +4.95 s (`listener connected`). Zero rebind loops.
- Synthetic notification posted immediately after restart while still locked: admitted and committed to encrypted database in 2.27 s.

### Screen-Off and Locked Pipeline Accounting
- Posted 3 synthetic notifications (IDs 9301, 9302, 9303) through real `NotificationManager` while locked.
- Ledger accounting:
  - Total=3, Delivered=3, Dropped=0, Missed=0.
  - Stages: SourcePublished=3, ListenerReceived=3, QueueAdmitted=3, Normalized=3, DeduplicatedInsert=3, DbCommitted=3, ReadbackVerified=3.
  - Latency: p50 = 498 ms, p95 = 1258 ms. No delayed capture.
- Deduplication while locked:
  - Updated notification 9303 committed as revision 2, readback verified.
  - Identical timestamp repost recorded as `DeduplicatedDrop`, dropped without creating duplicate rows or reviving archived conversations.
- Intentional classification exclusions:
  - Non-communication packages and non-message categories (e.g., progress, ongoing services) filtered before queue admission or at listener boundary, excluded from conversation store.
- Teardown:
  - Notifications removed via `ACTION_CANCEL_SYNTHETIC`; removals processed cleanly as `DeduplicatedRemove=4`.
  - Temporary `POST_NOTIFICATIONS` permission revoked (`granted=false`).

---

## 5. Preserved Scope and Limitations

- **Third-party notifications**: Synthetic notifications exercise the complete OS notification listener pipeline, bounded queue, normalizer, and SQLCipher database. Third-party applications (e.g., WhatsApp, Signal) may apply platform-level grouping, truncation, or bundling before notifications reach the listener.
- **Prolonged Doze**: Device tests covered active screen-off and ambient doze states (~15 minutes). Deep multi-hour unplugged Doze maintenance windows remain subject to platform power management rules.
- **Data and settings integrity**: No user accounts, SQLite/SQLCipher databases, seen records, or OAuth tokens were reset or modified. FLAG_SECURE and release gates remain intact.
