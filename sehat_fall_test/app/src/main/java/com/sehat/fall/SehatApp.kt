package com.sehat.fall

import android.app.Application
import com.sehat.fall.model.FallConfig
import com.sehat.fall.model.SettingsManager
import com.sehat.fall.storage.EventLogRepository
import com.sehat.fall.storage.TraceRepository

class SehatApp : Application() {

    lateinit var fallConfig: FallConfig
        private set

    lateinit var settingsManager: SettingsManager
        private set

    lateinit var eventLogRepo: EventLogRepository
        private set

    lateinit var traceRepo: TraceRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        fallConfig = assets.open("fall_config.json").use {
            FallConfig.fromInputStream(it)
        }
        settingsManager = SettingsManager(this, fallConfig)
        eventLogRepo = EventLogRepository(this)
        traceRepo = TraceRepository(this)
    }

    companion object {
        lateinit var instance: SehatApp
            private set
    }
}
