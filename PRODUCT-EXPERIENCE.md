# NOVRA — Product & Experience Direction

NOVRA is an original native media home for personal video and music collections. It may learn from familiar interaction patterns in leading media products, but it must not reproduce their branding, proprietary assets, exact screen layouts, or protected content.

## Product principles
- One coherent home for video and music, without making either feel secondary.
- Content-first browsing: strong artwork, readable titles, useful metadata, clear categories and horizontal shelves where they help discovery.
- Fast access to the personal device library, folders, favorites, history, playlists and queues.
- Playback is the center of the experience: persistent mini-player, full now-playing screen, clear queue, reliable background playback and Android media controls.
- Premium-quality polish should not require a subscription for core local playback.
- Respect user privacy: local-first library and preferences; request only permissions needed for a feature.
- Original NOVRA visual identity. No copied logos, branded assets, exact layouts or forced visual theme.

## Product inspiration translated into original features

### Video discovery and player
Learn from the clarity of YouTube's browsing model:
- Home shelves, category chips, recent videos, continue watching and useful sorting.
- Artwork-led video cards with duration, title and source/folder context.
- Dedicated video details and a focused player.
- Gesture controls, playback speed, subtitle/audio-track selection, screen lock, PiP and resume position where supported by Android/Media3.

### Music library and playback
Learn from Spotify's information architecture and Lark Player's local-library practicality:
- Albums, artists, songs, folders, genres, favorites and custom playlists.
- Artwork-forward album/playlist cards and a full now-playing experience.
- Queue editing, shuffle, repeat, next/previous, seek and persistent mini-player.
- Lyrics only when available from a lawful/local source; do not scrape protected services.
- Equalizer/effects only where supported by the device and implementation.

### Premium-feeling utility
Explore these as native features, not fake subscription claims:
- Sleep timer, playback history and listening statistics.
- Audio focus, Bluetooth/headset controls, Android Auto integration only if implemented and tested, and output-device awareness.
- Customizable home shelves, compact/detailed list modes, dark/light/system appearance and accent customization.
- Backup/restore of NOVRA metadata and playlists, with clear handling of media files.
- Accessibility, Arabic/English localization and responsive layouts.

## Inspiration boundaries
- Inspiration means learning interaction principles and feature ideas, not copying proprietary visual designs or assets.
- No ad blocking, subscription bypass, DRM circumvention, private API abuse or unauthorized downloading.
- Online services and external media sources must follow their terms and applicable law.
- Clearly distinguish implemented features from planned features. Never display a control that pretends to work.

## Delivery order
1. Native shell and visual system.
2. Permission flow and MediaStore scanning for videos/audio.
3. Real collection screens: search, sorting, folders, artwork/thumbnails, favorites and history.
4. Media3 video playback and music playback.
5. MediaSessionService, notification, lock-screen, Bluetooth/headset controls and playback restoration.
6. Playlists, queue, subtitles, speed, PiP, equalizer integration and sleep timer.
7. Settings, theme/localization, backup/restore, accessibility and device testing.
8. Release signing, APK/AAB build, smoke tests and release notes.

## Current implementation status
The first Compose navigation/home foundation and initial Media3 service/data scanner files exist. Collection screens are still presentation scaffolding; media permission UX, live scanning in UI, actual player screens, playback controls and release validation remain to be implemented.
