package com.errorrepo

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HindiAnimeDubPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HindiAnimeDubProvider())
    }
}
