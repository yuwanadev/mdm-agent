package com.yuwanadev.mdm

import android.app.Application
import com.yuwanadev.mdm.data.ConfigStore
import com.yuwanadev.mdm.websocket.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/** Application class for MDM Agent. Initializes global singletons. */
class MdmApplication : Application() {

    lateinit var configStore: ConfigStore
        private set

    val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(applicationContext)
    }
}
