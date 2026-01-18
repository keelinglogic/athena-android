# Athena Capture - Android App

Native Android app for frictionless input capture to the Athena system.

## Overview

Athena Capture focuses on the **CORE productivity model**:
- **Capture** ← MVP focus
- Organize
- Review
- Execute

The MVP implements **Capture** functionality only - getting information into Athena quickly from mobile.

## Capture Methods (MVP)

1. **Photo** - Camera capture for documents, whiteboards, receipts
2. **Audio** - Voice recording for thoughts, notes, meetings
3. **Chat** - Conversational text input
4. **Quick Task** - One-liner task entry

## Architecture

### Backend
- **Primary**: Hermes VPS (n8n automation workflows)
- **Database**: PostgreSQL on Hermes (`postgres-athena`)
- **Connectivity**: Embedded WireGuard VPN (app creates own tunnel)
- **Authentication**: mTLS (client certificates)

### Database Schema

Captures are stored in the `captures` table on Hermes PostgreSQL:

```sql
CREATE TABLE captures (
    id          SERIAL PRIMARY KEY,
    uuid        UUID NOT NULL UNIQUE,       -- Client-generated unique ID
    device_id   VARCHAR(255) NOT NULL,      -- Device identifier
    type        VARCHAR(50) NOT NULL,       -- voice, task, photo, chat
    content     TEXT,                       -- Text content or transcription
    metadata    JSONB,                      -- Type-specific metadata
    file_data   BYTEA,                      -- Binary file (images, audio)
    file_name   VARCHAR(255),               -- Original filename
    file_size   BIGINT,                     -- File size in bytes
    mime_type   VARCHAR(100),               -- MIME type
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

**Capture Types:**

| Type | Content | File Data | Metadata |
|------|---------|-----------|----------|
| `voice` | Transcription | Audio (optional) | `{duration_seconds, timestamp}` |
| `task` | Task text | — | `{}` |
| `photo` | Caption | Image JPEG/PNG | `{width, height, location}` |
| `chat` | Message | — | `{}` |

**Full schema**: See `project/modules/n8n-email-workflow/SCHEMA.md` in Athena repo.

### Technology
- **Language**: Kotlin
- **Min SDK**: Android 7.0 (API 24)
- **Target SDK**: Android 14 (API 34)
- **Build System**: Gradle 8.7

## Development Setup

### Prerequisites
- Android Studio 2025.2.3+
- Android SDK API 34
- Java JDK 17

### Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run tests
./gradlew test
```

**Build outputs:**
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

### Project Structure

```
athena-android/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/athena/capture/  # Kotlin source
│   │       ├── res/                      # Resources
│   │       └── AndroidManifest.xml
│   └── build.gradle.kts                  # App configuration
├── gradle/                               # Gradle wrapper
├── build.gradle.kts                      # Root build config
└── settings.gradle.kts                   # Project settings
```

## Current Status

**Phase**: MVP Development
**Version**: 0.1.0-mvp

### Completed
- ✅ Repository created
- ✅ Project structure initialized
- ✅ Basic UI scaffold (4 capture buttons)
- ✅ Build configuration
- ✅ Dependencies added (CameraX, OkHttp, Coroutines)
- ✅ Voice capture (recording + transcription + backend storage)
- ✅ Quick task capture (text + backend storage)
- ✅ Backend API client (n8n webhook integration)
- ✅ Database schema for image storage (2026-01-18)

### In Progress
- 🔨 Photo capture implementation (DB ready, app integration pending)
- 🔨 Chat input implementation
- 🔨 WireGuard VPN integration
- 🔨 mTLS client certificates

## Related

- **Main Athena Repo**: https://github.com/keelinglogic/athena
- **GitHub Issue**: [#49 - Setup: Athena Android App Repository](https://github.com/keelinglogic/athena/issues/49)
- **Development Docs**: `brain/infrastructure/iris/android-development.md` (in Athena repo)

---

**Created**: 2026-01-16
**License**: Private - Personal use only
