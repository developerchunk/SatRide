package com.developerstring.satride

import android.app.Application
import com.developerstring.satride.core.analytics.FirebaseContext
import com.developerstring.satride.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class SatRideApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before Koin, because the analytics singletons are built while the graph starts and the
        // Firebase Analytics handle needs a Context that common code has no way to supply.
        FirebaseContext.install(this)
        initKoin {
            androidLogger()
            androidContext(this@SatRideApplication)
        }
    }
}
