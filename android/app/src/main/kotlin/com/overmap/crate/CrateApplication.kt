package com.overmap.crate

import android.app.Application
import android.content.Context
import com.overmap.crate.app.AppModel

/** Holds the one [AppModel] for the app, the widget and the share screen (all run in this process). */
class CrateApplication : Application() {
    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        model = AppModel(this)
    }
}

val Context.crateModel: AppModel get() = (applicationContext as CrateApplication).model
