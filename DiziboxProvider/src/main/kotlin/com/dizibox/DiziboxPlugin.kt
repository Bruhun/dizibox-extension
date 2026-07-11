package com.dizibox

import android.content.Context
import com.dizibox.provider.DiziboxAltProvider
import com.dizibox.provider.DiziboxProvider
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziboxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziboxProvider())
        registerMainAPI(DiziboxAltProvider())
    }
}
