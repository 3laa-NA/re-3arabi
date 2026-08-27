package com.phoenix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimePhoenixPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimePhoenixProvider())
    }
}
