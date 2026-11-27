# AureDial 0.2.0-beta01

Implemented in source: default dialer role, Telecom in-call service and notifications, answer/reject/end, hold/mute, DTMF, endpoint selection, SIM selection, local contacts/favorites and recents with role/permissions, offline opt-in incoming announcement when call UI is visible, existing local rules, light/dark themes.

Not validated on real devices. Do not use as primary dialer until physical regression tests pass. System controls ringing; no guaranteed ducking, Bluetooth TTS routing or background speech. No commercial spam data provider, server, automated reputation classification, call recording, visual voicemail, Google proprietary call screening, contact editing, full T9 or advanced accessibility validation. Call history bounded at 250; contacts bounded at 5000. No secrets or internet permission.

CI must confirm compilation and lint. A green build is not certification of telephony safety.
