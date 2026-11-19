# OTP code notification — design (approved 2026-11-18)

## Why

When a one-time code arrives, the user either reads it off the phone to type into a computer, or
copies it to paste into an app on the same phone. Today a code arrives as an ordinary message
notification: the code is small, buried in the message text, and there is no copy button of our own.
Inspired by Mezo SMS, which shows codes in a large, size-selectable notification.

## What it does

- For incoming messages tagged as codes (`Message.isOtp`), the notification shows the code itself in
  large type, plus a **Copy code** action that copies just the digits.
- The code is also **copied to the clipboard automatically** when it arrives, once per message (added
  at the user's request after the first device test; the button stays for copying it again).
- Every other message notifies exactly as it does now.
- The code shows on the **lock screen** in full, including for categories where message previews are
  hidden: turning the setting on is the user asking for that.

```
Chase · now
        4 8 2 1 9 3          ← Large / Larger / Huge
[ Copy code ]
```

## Setting

One picker under Settings → Message Sorting, next to "OTP message retention":
**Code notification size: Off / Large / Larger / Huge**. Default: **Large**.

## Goals, non-goals, anti-goals

- Goal: the code is readable at a glance from the shade and the lock screen, and copyable in one tap.
- Non-goals: no popup screen, no "Display over other apps" permission, no change to how long Android
  floats a notification (it drops from the top after a few seconds and stays in the shade/lock
  screen), no change to OTP retention or sorting.
- Anti-goal: breaking normal message notifications, or removing Android's own copy-code suggestion
  where it appears.

## Approaches considered

| Approach | Trade-off | Outcome |
|---|---|---|
| A. Popup screen like QRF Reply (shows over lock screen, stays until dismissed) | Needs "Display over other apps", more code | Not chosen |
| B. Floating overlay window | Android hides overlays on the lock screen | Ruled out |
| C. Full-screen-intent notification | Android 14+ / Play reserve it for alarm and calling apps | Ruled out |
| **D. Notification with the code in large type** | Doesn't stay floating; lives in the shade and lock screen | **Chosen by the user** |

## Decided

- Approach D, setting placement, the three sizes plus Off, default Large, lock-screen visibility.

## Left to the builder (calls made, can be overridden)

- Text sizes 24 / 30 / 36 sp. (First built at 32 / 40 / 48; the user found Huge too big on the S23+
  and asked for all three one step smaller.)
- Code extraction (`OtpDetector.extractCode`, removed as unused earlier on 2026-11-18) comes back,
  with a unit test.
- Copy goes through a broadcast receiver, the way the notification's other actions do.
