dependencies {
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.3")
}

// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch public YouTube videos, live streams, channels, and playlists"
    authors = listOf("error-898-15")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Others", "Live", "TvSeries")
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=www.youtube.com&sz=%size%"
    isCrossPlatform = true
}
