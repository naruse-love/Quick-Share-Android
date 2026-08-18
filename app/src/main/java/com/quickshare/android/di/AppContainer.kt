package com.quickshare.android.di

import android.content.Context
import com.quickshare.android.data.AppConfigRepository
import com.quickshare.android.data.IAppConfigRepository
import com.quickshare.android.network.QuickShareClient
import com.quickshare.android.network.QuickShareServer
import com.quickshare.android.network.IQuickShareClient
import com.quickshare.android.network.IQuickShareServer
import com.quickshare.android.network.IInterfaceEnumerator
import com.quickshare.android.network.IMultiPathSocketFactory
import com.quickshare.android.network.InterfaceEnumerator
import com.quickshare.android.network.MultiPathSocketFactory
import com.quickshare.android.network.TrafficManager
import com.quickshare.android.transfer.IStorageManager
import com.quickshare.android.transfer.StorageManager

interface AppContainer {
    val context: Context
    val storageManager: IStorageManager
    val socketFactory: IMultiPathSocketFactory
    val interfaceEnumerator: IInterfaceEnumerator
    val trafficManager: TrafficManager
    val quickShareClient: IQuickShareClient
    val quickShareServer: IQuickShareServer
    val appConfigRepository: IAppConfigRepository
}

class DefaultAppContainer(override val context: Context) : AppContainer {
    override val storageManager: IStorageManager by lazy {
        StorageManager(context)
    }

    override val socketFactory: IMultiPathSocketFactory by lazy {
        MultiPathSocketFactory()
    }

    override val interfaceEnumerator: IInterfaceEnumerator by lazy {
        InterfaceEnumerator(context)
    }

    override val trafficManager: TrafficManager by lazy {
        TrafficManager()
    }

    override val quickShareClient: IQuickShareClient by lazy {
        QuickShareClient(
            storageManager = storageManager,
            socketFactory = socketFactory,
            trafficManager = trafficManager
        )
    }

    override val quickShareServer: IQuickShareServer by lazy {
        QuickShareServer(
            storageManager = storageManager,
            socketFactory = socketFactory,
            interfaceEnumerator = interfaceEnumerator,
            trafficManager = trafficManager
        )
    }

    override val appConfigRepository: IAppConfigRepository by lazy {
        AppConfigRepository(context)
    }
}
