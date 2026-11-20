package com.studentlearning.platform2026

import android.app.Application
import com.studentlearning.platform2026.core.di.AppContainer

class HaqibatiApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
