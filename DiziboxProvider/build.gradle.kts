version = 1

cloudstream {
    description = "Dizibox - Yabanci dizi ve film izleme eklentisi"
    authors = listOf("Dizibox")

    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "tr"

    iconUrl = "https://raw.githubusercontent.com/${System.getenv("GITHUB_REPOSITORY") ?: "user/repo"}/builds/icon.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
