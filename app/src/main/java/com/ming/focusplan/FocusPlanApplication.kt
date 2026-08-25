package com.ming.focusplan

import android.app.Application
import com.ming.focusplan.data.AppContainer

class FocusPlanApplication : Application() {
    val container by lazy { AppContainer(this) }
}
