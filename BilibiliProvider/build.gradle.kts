// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch licensed anime series, simulcasts, and creator videos on BiliBili (bilibili.tv)"
    authors = listOf("error-898-15")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "Movie", "Others")

    language = "en"

    iconUrl = "https://www.google.com/s2/favicons?domain=bilibili.tv&sz=%size%"

    isCrossPlatform = true
}
