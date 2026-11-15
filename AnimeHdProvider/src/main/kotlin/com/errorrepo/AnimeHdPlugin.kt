package com.errorrepo

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeHdPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeHdProvider())
    }
}
