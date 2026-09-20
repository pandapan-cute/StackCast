# AGENTS.md

This file defines the AI Agent operating instructions, conventions, and architectural boundaries for the **StackCast** project when generating or editing code in Android Studio.

---

## 🤖 System Overview & Role

`StackCast` is an all-in-one 24-hour AI streaming hub Android app that integrates RTMP live streaming, quota-free YouTube comment scraping, local/cloud LLM inference, TTS speech synthesis with volume envelope lip-sync analysis, and BLE control for ｽﾀｯｸﾁｬﾝ (M5Stack).

---

## 🏗️ Architecture & Development Standards

When writing, refactoring, or reviewing code for this repository, AI agents **MUST** strictly follow these rules:

### 1. UI Layer (Classic View / Fragment)

- **Do NOT use Jetpack Compose.**
- Use **Classic Android View System** with **Single Activity + Fragment** (`MainActivity` + `NavHostFragment`).
- Use **XML Layouts** (`res/layout/fragment_*.xml`) and **ViewBinding** (`FragmentXxxBinding`).
- State management must use **ViewModel** with `LiveData` or `StateFlow` observed by Fragments.

### 2. Domain-Driven Design (DDD) & Onion Architecture

Follow the 4-layer separation defined in [ドメイン駆動設計書.md](docs/03_詳細設計/ドメイン駆動設計書.md):

```text
com.pandapan.stackcast/
├── presentation/          # View, Fragment, ViewModel, ViewBinding adapters
├── application/           # UseCases (StartStreamUseCase, ProcessCommentUseCase, etc.)
├── domain/                # Entities, Value Objects, Domain Services, Ports (Interfaces)
└── infrastructure/        # Engine & Service implementations (RtmpEngine, BleController, etc.)
```

- **Domain Layer (`domain/`)**: Must be pure Kotlin. **NO** dependencies on Android SDK (`android.*`) or third-party libraries.
- **Dependency Inversion Principle (DIP)**: Hardwares and external engines (RootEncoder, BluetoothGatt, MediaPipe, TTS) must implement Ports (interfaces) defined in `domain/ports/`.
- **Use Cases (`application/`)**: Combine domain models and ports to orchestrate application scenarios.

### 3. Foreground Service & 24-Hour Guard

- Core streaming, AI processing, and BLE communication must be managed by `StreamingForegroundService` (`infrastructure/service/`).
- Always respect Android background execution limits, WakeLock (`FLAG_KEEP_SCREEN_ON`), thermal throttling notifications, and automatic BLE reconnection routines.

---

## 🛠️ Code Style & Guidelines

- **Language**: Kotlin 1.9+
- **Documentation & Comments (KDoc)**:
  - Write KDoc comments (`/** ... */`) for all public/internal classes, interfaces, functions, properties, and constants.
  - **All comments (including KDoc, inline comments, and TODOs) MUST be written in Japanese (日本語).**
  - Provide detailed explanatory comments for complex or non-obvious logic (e.g., lip-sync volume envelope calculation, BLE packet byte serialization, thermal throttling state handling, RTMP audio/video frame muxing) so human developers can easily read and understand the intent.
- **Null Safety**: Avoid non-null assertion `!!`. Use `?.let`, `if-let`, or guard clauses.
- **Asynchronous Execution**: Use Kotlin Coroutines (`Dispatchers.IO` for I/O and network, `Dispatchers.Main` for UI updates) and `Flow` / `StateFlow`.
- **Resource Cleanup**: Ensure Camera, MediaCodec, BluetoothGatt, and TTS instances are properly closed/released in `onDestroyView()` / `onDestroy()`.
- **Error Handling**: Wrap external I/O and hardware calls in `Result<T>` or sealed result classes. Never crash on BLE disconnection or network drops.

---

## 📁 Key Documentation References

Before modifying core logic, consult the relevant design documents:

- **System Overview & Hardware**: [README.md](README.md)
- **Screen Transitions**: [docs/02_基本設計/画面遷移図.md](docs/02_基本設計/画面遷移図.md)
- **Component Specifications**: [docs/03_詳細設計/コンポーネント一覧.md](docs/03_詳細設計/コンポーネント一覧.md)
- **DDD Architecture & Ports**: [docs/03_詳細設計/ドメイン駆動設計書.md](docs/03_詳細設計/ドメイン駆動設計書.md)
