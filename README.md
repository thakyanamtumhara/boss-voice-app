# Boss — offline voice assistant for the Galaxy S23

Say a wake word; the phone does what you ask. Alarms, timers, reminders,
calls, WhatsApp, opening apps, directions, torch.

## How it works

| Stage | Engine | Why |
|---|---|---|
| Wake word | Vosk, on-device, **grammar mode** | The decoder only knows your wake phrases plus "anything else", so it is cheap enough to run all day. No account, no key, no network. |
| The command itself | Android `SpeechRecognizer` (en-IN) | Far more accurate on names and detail. Falls back to typing if it cannot hear. |
| Understanding | Rules in `parse/` | Instant, free, offline, and unit-tested. Handles English and Hinglish. |

The wake word is stored as **what the recogniser actually heard** when you
taught it, recorded three times. That is why a word no English model knows —
a name, say — still works: the model never has to spell it, only be
consistent about it.

## Things you can say

```
set an alarm for 6:30 am          wake me at saade 6
timer for 10 minutes              5 minute ka timer laga do
remind me at 5 to call the CA     remind me in 2 hours to check the stock
shaam 6 baje yaad dilana godam band karna
call Rajesh                       Rajesh ko call karo
whatsapp Rajesh saying I'll be there in 10 minutes
open Instagram                    navigate to Sadar Bazar
torch on                          what's the time                battery
```

Reminders are the app's own alarm engine — loud, full-screen, and re-armed
after a reboot. Not a silent calendar entry.

## Samsung notes

One UI puts apps to sleep, which kills the wake word. The in-app Setup list
walks through it, but the two that matter:

- Settings → Battery → Background usage limits → **Never sleeping apps** → add Boss
- Settings → Apps → Boss → Battery → **Unrestricted**

## Build

```bash
./gradlew :app:testDebugUnitTest      # parser tests, no device needed
./gradlew :app:assembleDebug          # APK -> app/build/outputs/apk/debug/
./gradlew :app:connectedDebugAndroidTest   # runs real audio through Vosk
```

Requires JDK 17+ and an Android SDK. The 36 MB speech model ships in
`app/src/main/assets/`, so the build is fully offline.
