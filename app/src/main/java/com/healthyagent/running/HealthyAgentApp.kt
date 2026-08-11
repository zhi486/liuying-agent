package com.healthyagent.running

import android.app.Application
import com.healthyagent.running.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

class HealthyAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 日志初始化
        if (com.healthyagent.running.BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Koin 依赖注入初始化
        startKoin {
            androidContext(this@HealthyAgentApp)
            modules(appModule)
        }
    }
}
