// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch and stream Hindi dubbed anime series and movies on AnimeVilla (animevilla.org)"
    authors = listOf("error-898-15")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "hi"
    iconUrl = "https://www.google.com/s2/favicons?domain=animevilla.org&sz=%size%"
    isCrossPlatform = true
}
