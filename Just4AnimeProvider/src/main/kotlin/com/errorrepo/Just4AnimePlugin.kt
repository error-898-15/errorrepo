package com.errorrepo

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Just4AnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Just4AnimeProvider())
    }
}
