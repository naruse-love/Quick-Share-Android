# E2E Test Infra: QuickShare-Android

## Test Philosophy
- **Requirement-Driven & Opaque-Box**: Derived strictly from `ORIGINAL_REQUEST.md` and QuickShareProtocol v300 specifications without coupling to internal class structures.
- **Methodology**: Category-Partition + Boundary Value Analysis (BVA) + Pairwise Combinatorial Testing + Real-World Workload Testing.
- **Dual Verification**: Unit/Local Integration Tests + Protocol Interoperability Harness.

---

## Feature Inventory Coverage Matrix

| # | Feature | Requirement Source | Tier 1 (Feature) | Tier 2 (Boundary) | Tier 3 (Pairwise) | Tier 4 (Scenario) |
|---|---------|-------------------|:----------------:|:-----------------:|:-----------------:|:-----------------:|
| 1 | Gradle Build & Wrapper | Request §5 | 5 | 5 | ✓ | ✓ |
| 2 | QuickShareStream Big-Endian Codec | Spec §2 | 5 | 5 | ✓ | ✓ |
| 3 | Protocol Constants & Magic | Spec §3 | 5 | 5 | ✓ | ✓ |
| 4 | Core Data Models (`FileBlock`, etc.) | Spec §6 | 5 | 5 | ✓ | ✓ |
| 5 | Cross-Platform Path Normalization | Spec §6.4 | 5 | 5 | ✓ | ✓ |
| 6 | Handshake Negotiation (12 steps) | Spec §4.1 | 5 | 5 | ✓ | ✓ |
| 7 | 1MB Slicing Pipeline (`ReadFileCall`) | Spec §6.2 | 5 | 5 | ✓ | ✓ |
| 8 | Out-of-Order Assembler (`WriteFileCall`)| Spec §6.2 | 5 | 5 | ✓ | ✓ |
| 9 | Per-Channel Streaming (`Send`/`Receive`) | Spec §4.3 | 5 | 5 | ✓ | ✓ |
| 10 | Zero-GC Buffer Pool | Spec §6.3 | 5 | 5 | ✓ | ✓ |
| 11 | Data Integrity (MD5/SHA256) | Request §3 | 5 | 5 | ✓ | ✓ |
| 12 | Storage Engine (Direct + SAF) | Request §3 | 5 | 5 | ✓ | ✓ |
| 13 | Multi-NIC Discovery & Enumeration | Request §1 | 5 | 5 | ✓ | ✓ |
| 14 | Physical Socket NIC Binding | Spec §7 | 5 | 5 | ✓ | ✓ |
| 15 | Client Mode Engine (`QuickShareClient`) | Request §2 | 5 | 5 | ✓ | ✓ |
| 16 | Server Mode Engine (`QuickShareServer`) | Request §2 | 5 | 5 | ✓ | ✓ |
| 17 | Remote Management RPCs (`LIST`/`MKDIR`/`DEL`) | Spec §4.2 | 5 | 5 | ✓ | ✓ |
| 18 | Dual-Mode Push/Pull Transfers | Spec §5 | 5 | 5 | ✓ | ✓ |
| 19 | Real-Time Traffic & Speed Metering | Spec §8 | 5 | 5 | ✓ | ✓ |
| 20 | Jetpack Compose M3 UI | Request §4 | 5 | 5 | ✓ | ✓ |
| 21 | Connection Screen (Custom IP/Port) | Request §4 | 5 | 5 | ✓ | ✓ |
| 22 | Server Mode Screen (Custom Listen Port) | Request §4 | 5 | 5 | ✓ | ✓ |
| 23 | Local & Remote File Explorer | Request §4 | 5 | 5 | ✓ | ✓ |
| 24 | Transfer Dashboard & Multi-NIC Badges | Request §4 | 5 | 5 | ✓ | ✓ |
| 25 | Foreground Service & Notifications | Survey §3.1 | 5 | 5 | ✓ | ✓ |
| 26 | Protocol Interop & Wire Validation | Request §5 | 5 | 5 | ✓ | ✓ |
| 27 | Gradle Debug APK Build Verification | Request §5 | 5 | 5 | ✓ | ✓ |

---

## Test Architecture

### 1. Test Suite Layout
```
d:/appdata/kuaichuan/QuickShare-Android/app/src/test/java/com/quickshare/android/
├── protocol/
│   ├── QuickShareStreamTest.kt               # Big-Endian read/write tests for all primitives
│   ├── ProtocolCodecTest.kt           # Handshake, frame headers, OpCodes validation
│   └── RemoteCommandCodecTest.kt      # LIST_FILES, MKDIR, DELETE_FILE payload codecs
├── model/
│   ├── FileBlockSortTest.kt           # Priority queue multi-channel sorting test
│   ├── QuickShareDirectoryPathTest.kt        # Windows/Unix path sanitizer & normalizer tests
│   └── TrafficInfoTest.kt             # Instantaneous & cumulative traffic calculator
├── transfer/
│   ├── ChunkPipelineTest.kt           # 1MB slicing, out-of-order reassembly, EOF tests
│   ├── BufferPoolTest.kt              # Concurrency & zero-allocation recycling tests
│   ├── StorageManagerTest.kt          # RandomAccessFile seeking & file creation tests
│   └── ChecksumTest.kt                # MD5/SHA256 streaming hash verification
├── network/
│   ├── InterfaceEnumeratorTest.kt     # IP extraction & network interface filtering
│   └── MultiPathBindingTest.kt        # Multi-socket physical binding logic
└── e2e/
    ├── E2ETestRunner.kt               # Simulated dual-node Client-Server loopback
    ├── PushTransferE2ETest.kt         # REQUEST_RECEIVE push transfer simulation
    ├── PullTransferE2ETest.kt         # REQUEST_SEND pull transfer simulation
    └── RemoteFileOpsE2ETest.kt        # Remote LIST, MKDIR, DELETE operations
```

### 2. Test Tiers
- **Tier 1: Feature Coverage**: Verify all 27 features in isolation (happy-path).
- **Tier 2: Boundary & Corner Cases**: Empty files (0 bytes), massive files (>4GB), long Unicode filenames, illegal characters (`: * ? < > | \`), empty directory tree, single vs multi-channel mode, packet framing boundaries.
- **Tier 3: Cross-Feature Combinations**: Concurrent multi-channel streaming with simulated network latency jitter, push + pull simultaneous operations, failover on channel disconnection.
- **Tier 4: Real-World Scenarios**: Full multi-file folder hierarchy transfer, mock PC server interoperability, custom port re-binding (18888, 29999).

---

## Coverage Thresholds
- **Tier 1**: ≥ 5 tests per feature (≥ 135 tests)
- **Tier 2**: ≥ 5 tests per feature (≥ 135 tests)
- **Tier 3**: ≥ 27 cross-feature interaction scenarios
- **Tier 4**: ≥ 10 realistic workload end-to-end tests
- **Total Suite Minimum**: ≥ 300 test cases
