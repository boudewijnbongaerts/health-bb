package com.boudhealth

import android.app.Application
import com.boudhealth.data.ErrorLog
import com.boudhealth.data.Store
import com.boudhealth.work.Refresh

class App : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
        store = Store(this)
        Refresh.schedule(this)
    }
}
