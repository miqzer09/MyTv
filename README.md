# Glass TV — MyTv Android TV launcher

A minimal launcher for Android TV / Google TV. Live weather on the home screen, tile
launcher for all installed apps, glass-dock style.

## TV

- IP: `192.168.3.7` (TCP port `5555`).
- Auth: the TV already trusts `.keys/adbkey.pub`. The `.keys/adbkey` private key is
  local only (untracked).
- The public key is mirrored from the Windows developer sysroot key (`%USERPROFILE%\.android\adbkey.pub`),
  which is what the TV was paired with. If the TV ever asks to accept a key again, re-pair
  from the Windows machine's platform-tools / Android Studio and mirror `.keys/adbkey.pub`
  again (run `.tools/get_adbkey_pub.py`).

## Build & install

- Requires Java 11+ (JDK) for `javac`. This machine currently has JRE 1.8 only on PATH;
  the available JDK is `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot`.
- `launcher/build-and-install.ps1` builds the launcher APK and attempts to install it
  over ADB once the TV is reachable.

## Weather card

The weather card is placed on the right side, above the glass dock, on the home screen.
End points and refresh config live in `config.xml` / `Config.java`.
