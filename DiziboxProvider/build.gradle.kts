version = 2

cloudstream {
    description = "Dizibox - Yabanci dizi ve film izleme eklentisi"
    authors = listOf("Dizibox")

    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "tr"

    iconUrl = "https://cdn-icons-png.flaticon.com/512/2798/2798004.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
