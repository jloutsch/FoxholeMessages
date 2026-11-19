# OTP Code Notification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the newest unread message in a thread is a one-time code, its notification shows the code in large type (user-chosen size) with a Copy code button, visible on the lock screen.

**Architecture:** `OtpDetector.extractCode` pulls the code out of the message text. `NotificationManagerImpl.update` checks the new `Preferences.otpNotificationSize`; when it's on and the newest unread message is tagged `isOtp`, it builds a `DecoratedCustomViewStyle` notification from a small RemoteViews layout instead of the `MessagingStyle` one, sets `VISIBILITY_PUBLIC`, and adds a Copy action handled by a new `CopyCodeReceiver`. The size picker lives on the Message Sorting screen, following the OTP retention picker.

**Tech Stack:** Kotlin, Android notifications (NotificationCompat, RemoteViews), rx-preferences, Conductor/QkDialog settings pattern, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-18-otp-code-notification-design.md`

## Global Constraints

- Setting: one picker "Code notification size" with **Off / Large / Larger / Huge**, default **Large**, under Settings → Message Sorting next to "OTP message retention".
- Text sizes about **32 / 40 / 48 sp**; shrink Huge if it's clipped in the floating (heads-up) view on a device.
- Only messages tagged `isOtp` change; every other notification stays exactly as today.
- Code shows on the lock screen even when the category or conversation hides previews.
- No popup, no new permissions, no change to OTP retention or sorting.
- Build/test: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew assembleDebug test`.

## Review Focus

1. A date or other number before the code ("On 2026-11-18 your code is 482193") → the code, not 2026. Test in Task 1.
2. Code before the keyword ("482193 is your verification code") → 482193. Test in Task 1.
3. Prefixed codes ("G-482193 is your Google verification code") → 482193. Test in Task 1.
4. A thread whose older unread message is a code but whose newest is ordinary text → the normal notification. Checked on device in Task 4.
5. Huge text in the floating view and on the lock screen → not clipped. Checked on device in Task 4.

---

### Task 1: Code extraction

**Files:**
- Modify: `domain/src/main/java/com/moez/QKSMS/classifier/OtpDetector.kt`
- Test: `domain/src/test/java/com/moez/QKSMS/classifier/OtpDetectorTest.kt`

**Interfaces:**
- Produces: `OtpDetector.extractCode(body: String): String?`. Returns the first 4–8 digit number after the first keyword match, else the first 4–8 digit number in the body, else null. It reuses the existing `codePattern` and `keywordPattern`.

- [ ] **Step 1: Write the failing tests** in `OtpDetectorTest`:

```kotlin
@Test fun extractsTheCode() {
    assertEquals("123456", detector.extractCode("Your verification code is 123456"))
    assertEquals(null, detector.extractCode("no digits here"))
}
@Test fun skipsNumbersBeforeTheKeyword() =
    assertEquals("482193", detector.extractCode("On 2026-11-18 your code is 482193"))
@Test fun findsCodeBeforeTheKeyword() =
    assertEquals("482193", detector.extractCode("482193 is your verification code"))
@Test fun findsPrefixedCode() =
    assertEquals("482193", detector.extractCode("G-482193 is your Google verification code"))
```

- [ ] **Step 2: Run** `./gradlew :domain:testDebugUnitTest --tests '*OtpDetectorTest*'`. Expected: compile failure, because `extractCode` is undefined.
- [ ] **Step 3: Implement `extractCode`** as described in Interfaces.
- [ ] **Step 4: Run the same command.** Expected: all `OtpDetectorTest` tests pass.
- [ ] **Step 5: Commit** "Extract the code from a one-time-code message".

### Task 2: Size setting

**Files:**
- Modify: `domain/src/main/java/com/moez/QKSMS/util/Preferences.kt` (beside `otpRetentionDays`)
- Modify: `presentation/src/main/res/values/strings.xml` (title, plus `otp_notification_size_labels` / `otp_notification_size_ids` arrays beside `otp_retention_*`)
- Modify: `presentation/src/main/res/layout/message_sorting_controller.xml` (new `PreferenceView` `@+id/otpNotificationSize` directly above `otpRetention`, same icon style)
- Modify: `MessageSortingController.kt`, `MessageSortingPresenter.kt`, `MessageSortingState.kt`, `MessageSortingView.kt` in `presentation/src/main/java/com/moez/QKSMS/feature/settings/messagesorting/`, mirroring every `otpRetention` piece (dialog, summary, selected id, click, selection → pref).

**Interfaces:**
- Produces: `Preferences.otpNotificationSize: Preference<Int>` (key `"fh_otpNotificationSize"`, default `OTP_NOTIFICATION_LARGE`). Constants in `Preferences.companion`: `OTP_NOTIFICATION_OFF = 0`, `OTP_NOTIFICATION_LARGE = 1`, `OTP_NOTIFICATION_LARGER = 2`, `OTP_NOTIFICATION_HUGE = 3`.
- Copy: title "Code notification size"; labels "Off", "Large", "Larger", "Huge"; ids 0, 1, 2, 3.

- [ ] **Step 1: Add the preference, strings, arrays, layout row and the four screen pieces.**
- [ ] **Step 2: Run** the build/test command. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** "Add the code notification size setting".

### Task 3: Copy code action

**Files:**
- Create: `data/src/main/java/com/moez/QKSMS/receiver/CopyCodeReceiver.kt`
- Modify: `presentation/src/main/AndroidManifest.xml` (register `.receiver.CopyCodeReceiver`, `android:exported="false"`, next to the other receivers)
- Modify: `presentation/src/main/res/values/strings.xml` (`notification_copy_code` "Copy code", `toast_code_copied` "Code copied")

**Interfaces:**
- Produces: `CopyCodeReceiver` with `companion object { const val EXTRA_CODE = "code" }`. In `onReceive` it puts `ClipData.newPlainText("Code", code)` on the clipboard and marks it `ClipDescription.EXTRA_IS_SENSITIVE` on API 33+. On API < 33 only, it shows the `toast_code_copied` toast, since Android 13+ shows its own copy confirmation. No injection is needed.

- [ ] **Step 1: Create the receiver, register it, add the strings.**
- [ ] **Step 2: Run** the build/test command. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** "Add a receiver that copies a code to the clipboard".

### Task 4: Large-type code notification

**Files:**
- Create: `presentation/src/main/res/layout/notification_otp_code.xml`: one TextView `@+id/code`, centered, bold, letter-spaced, primary text colour. Only RemoteViews-safe views.
- Modify: `presentation/src/main/java/com/moez/QKSMS/common/util/NotificationManagerImpl.kt` (constructor gains `private val otpDetector: OtpDetector`; the code branch replaces the `previewMode` `when` only for code messages)

**Interfaces:**
- Consumes: `OtpDetector.extractCode` (Task 1), `Preferences.otpNotificationSize` and constants (Task 2), `CopyCodeReceiver.EXTRA_CODE` (Task 3).
- Behaviour in `update(threadId)`, after the existing early returns:
  - Code mode applies when `otpNotificationSize != OTP_NOTIFICATION_OFF`, and `messages.last()` (the newest unread) has `isOtp`, and `otpDetector.extractCode(messages.last().getText())` is non-null.
  - In code mode: set `contentTitle` to the conversation title and `contentText` to the full message text (for accessibility and the system). Set `.setStyle(NotificationCompat.DecoratedCustomViewStyle())`, with `setCustomContentView` / `setCustomBigContentView` / `setCustomHeadsUpContentView` all built from `notification_otp_code` using `setTextViewTextSize(R.id.code, TypedValue.COMPLEX_UNIT_SP, size)`. Sizes: Large 32f, Larger 40f, Huge 48f. Add `.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)` and the large icon. Put a Copy code action first: a broadcast to `CopyCodeReceiver` with `EXTRA_CODE`, request code `threadId.toInt()`, `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`, icon `R.drawable.ic_content_copy_black_24dp` if it exists, else the closest existing copy icon.
  - Otherwise the existing `previewMode` path runs unchanged. User-configured actions, sound, vibration, shortcut and QRF are unchanged in both modes.

- [ ] **Step 1: Create the layout and the code-mode branch.**
- [ ] **Step 2: Run** the build/test command. Expected: BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 3: Device check (S23+, debug app as default SMS).** Inject a code with `adb shell run-as com.foxhole.messages.debug am broadcast --user 0 -n com.foxhole.messages.debug/dev.octoshrimpy.quik.receiver.DebugInjectSmsReceiver --es address 5550100999 --es body "Your verification code is 482193"`. For each of Large / Larger / Huge, confirm:
  - the floating view shows 482193, not clipped
  - the shade shows it
  - the lock screen shows it
  - Copy code puts 482193 on the clipboard
  Shrink Huge if it's clipped.
- [ ] **Step 4: Device check, Review Focus 4.** Inject the code, then an ordinary message from the same address. Expected: the normal notification. Set the size to Off and inject a code. Expected: the normal notification.
- [ ] **Step 5: Commit** "Show one-time codes in large type in their notification". Record in the commit which device checks passed.
