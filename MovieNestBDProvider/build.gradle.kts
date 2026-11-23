// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch Bengali, Hindi, English, and Korean movies, web series, and anime on MovieNestBD"
    authors = listOf("error-898-15")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie")

    language = "bn"

    iconUrl = "https://www.google.com/s2/favicons?domain=movienestbd.best&sz=%size%"

    isCrossPlatform = true
}
