// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch Hindi dubbed, multi-audio anime series and anime movies on AnimeDrive (animedrive.in)"
    authors = listOf("error-898-15")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie")
    language = "hi"
    iconUrl = "https://www.google.com/s2/favicons?domain=animedrive.in&sz=%size%"
    isCrossPlatform = true
}
