// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch subbed and dubbed anime series, movies, and OVAs on Re:ANIME (reanime.cz)"
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
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=reanime.cz&sz=%size%"
    isCrossPlatform = true
}
