package com.quickshare.android.e2e

import com.quickshare.android.model.FileBlockSortTest
import com.quickshare.android.model.QuickShareDirectoryPathTest
import com.quickshare.android.model.TrafficInfoTest
import com.quickshare.android.network.InterfaceEnumeratorTest
import com.quickshare.android.network.MultiPathBindingTest
import com.quickshare.android.protocol.QuickShareStreamTest
import com.quickshare.android.protocol.ProtocolCodecTest
import com.quickshare.android.protocol.RemoteCommandCodecTest
import com.quickshare.android.transfer.BufferPoolTest
import com.quickshare.android.transfer.ChecksumTest
import com.quickshare.android.transfer.ChunkPipelineTest
import com.quickshare.android.transfer.StorageManagerTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * E2ETestRunner: Master Test Suite Runner for QuickShare-Android.
 * Aggregates all protocol, model, transfer, network unit suites and Tiers 1-4 comprehensive E2E test suites.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    QuickShareStreamTest::class,
    ProtocolCodecTest::class,
    RemoteCommandCodecTest::class,
    FileBlockSortTest::class,
    QuickShareDirectoryPathTest::class,
    TrafficInfoTest::class,
    ChunkPipelineTest::class,
    BufferPoolTest::class,
    StorageManagerTest::class,
    ChecksumTest::class,
    InterfaceEnumeratorTest::class,
    MultiPathBindingTest::class,
    PushTransferE2ETest::class,
    PullTransferE2ETest::class,
    RemoteFileOpsE2ETest::class,
    Tier1FeatureTestSuite::class,
    Tier2BoundaryTestSuite::class,
    Tier3CrossFeatureTestSuite::class,
    Tier4RealWorldTestSuite::class
)
class E2ETestRunner
