package com.jaju.spotify.shuffler

import com.adamratzman.spotify.SpotifyClientApi
import com.adamratzman.spotify.SpotifyScope
import com.adamratzman.spotify.SpotifyUserAuthorization
import com.adamratzman.spotify.getSpotifyAuthorizationUrl
import com.adamratzman.spotify.spotifyClientApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.runBlocking
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

const val clientId =
    "__"
const val clientSecret = "__"
const val redirectUrl = "http://localhost:8888/callback"

@FlowPreview
suspend fun main() {
    val authorizationCodeChannel = Channel<String>()
    val webServer = authorizationCodeReceiveServer(authorizationCodeChannel).start()

    val url: String = getSpotifyAuthorizationUrl(
        SpotifyScope.PLAYLIST_READ_PRIVATE,
        SpotifyScope.PLAYLIST_MODIFY_PRIVATE,
        SpotifyScope.USER_LIBRARY_READ,
        SpotifyScope.USER_LIBRARY_MODIFY,
        clientId = clientId,
        redirectUri = redirectUrl
    )

    println("Accept here $url")

    val spotifyApi = spotifyClientApi(
        clientId,
        clientSecret,
        redirectUrl,
        SpotifyUserAuthorization(authorizationCode = authorizationCodeChannel.receive())
    ).build()

    println("Fetching songs")

    val shuffledTrackUris = infiniteSavedTrackFlow(spotifyApi)
        .takeWhile { it.isNotEmpty() }
        .map { it.items.asFlow() }
        .flattenConcat()
        .map { it.track.uri }
        .toList()
        .toTypedArray()
        .apply { shuffle() }

    println("Creating playlist")
    val playlist = spotifyApi.playlists.createClientPlaylist(name = generatePlaylistName(), public = false)

    println("Filling playlist")
    spotifyApi.playlists.addPlayablesToClientPlaylist(playlist = playlist.id, playables = shuffledTrackUris)

    println("Playlist filled")
    webServer.stop()
}

fun authorizationCodeReceiveServer(channel: Channel<String>) = { request: Request ->
    val code = request.uri.query.split("=")[1]
    runBlocking {
        channel.send(code)
        channel.close()
    }
    Response(Status.OK)
}.asServer(Undertow(8888))

fun infiniteSavedTrackFlow(spotifyApi: SpotifyClientApi) = flow {
    var offset = 0
    val limit = 50
    while (true) {
        emit(
            spotifyApi.library.getSavedTracks(limit = limit, offset = offset)
        )
        offset += limit
    }
}

fun generatePlaylistName() = LocalDateTime.now().format(
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .appendLiteral(' ')
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .toFormatter()
).let {
    "Shuffled at $it"
}