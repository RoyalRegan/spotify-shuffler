package com.jaju.spotify.shuffler

import arrow.core.raise.either
import com.adamratzman.spotify.SpotifyClientApi
import com.adamratzman.spotify.SpotifyScope
import com.adamratzman.spotify.SpotifyUserAuthorization
import com.adamratzman.spotify.getSpotifyAuthorizationUrl
import com.adamratzman.spotify.models.PagingObject
import com.adamratzman.spotify.models.PlayableUri
import com.adamratzman.spotify.models.Playlist
import com.adamratzman.spotify.models.PlaylistTrack
import com.adamratzman.spotify.models.SavedTrack
import com.adamratzman.spotify.spotifyClientApi
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.handlers.CallbackQueryHandlerEnvironment
import com.github.kotlintelegrambot.dispatcher.handlers.CommandHandlerEnvironment
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup.Companion.createSingleButton
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.server.Undertow
import org.http4k.server.asServer

val processLock = Mutex()
var spotifyApi: SpotifyClientApi? = null

@OptIn(ExperimentalCoroutinesApi::class)
fun main() {
    either {
        val env = env().bind()

        bot {
            token = env.spotify.botToken

            dispatch {
                commandWithLock("start", env.spotify.allowedUsers) {
                    val spotifyAuthUrl = getSpotifyAuthorizationUrl(
                        SpotifyScope.PlaylistReadPrivate,
                        SpotifyScope.PlaylistModifyPrivate,
                        SpotifyScope.UserLibraryRead,
                        SpotifyScope.UserLibraryModify,
                        clientId = env.spotify.clientId,
                        redirectUri = env.spotify.redirectUrl
                    )

                    val authorizationCodeChannel = Channel<String>()
                    val webServer = authorizationCodeReceiveServer(authorizationCodeChannel) {
                        bot.sendMessage(currentChat, "Logged into spotify")
                    }.start()

                    bot.sendMessage(currentChat, "Login via $spotifyAuthUrl")

                    spotifyApi = spotifyClientApi(
                        env.spotify.clientId,
                        env.spotify.clientSecret,
                        env.spotify.redirectUrl,
                        SpotifyUserAuthorization(authorizationCodeChannel.receive())
                    ) {
                        onTokenRefresh = {
                            println("token refreshed")
                        }
                    }.build()

                    webServer.stop()
                }

                commandWithLock("shuffle", env.spotify.allowedUsers) {
                    requireLogin {
                        playlists.getClientPlaylists()
                            .getAllItems()
                            .filter { !it!!.name.endsWith("_shuffled") }
                            .forEach {
                                bot.sendMessage(
                                    currentChat, it!!.name, replyMarkup = createSingleButton(
                                        InlineKeyboardButton.CallbackData("shuffle", "shuffle${it.id}")
                                    )
                                )
                            }

                        bot.sendMessage(
                            currentChat, "Liked songs", replyMarkup = createSingleButton(
                                InlineKeyboardButton.CallbackData("shuffle", "shuffleLiked")
                            )
                        )
                    }
                }

                callbackQueryWithLock("shuffle") {
                    val playlistId = callbackQuery.data.substringAfter("shuffle")

                    requireLogin {
                        if (playlistId == "Liked") {
                            val shuffledName = "Liked_shuffled"

                            val savedShuffled = findClientPlaylistByName(shuffledName)
                            if (savedShuffled != null) {
                                playlists.deleteClientPlaylist(savedShuffled.id)
                                bot.sendMessage(currentChat, "Removing old shuffled playlist")
                            }

                            val createdPlaylist = playlists.createClientPlaylist(name = shuffledName, public = false)
                            bot.sendMessage(currentChat, "Playlist created, starting shuffle")

                            flow {
                                var offset = 0
                                val limit = 50
                                while (true) {
                                    emit(
                                        library.getSavedTracks(limit = limit, offset = offset)
                                    )
                                    offset += limit
                                }
                            }.takeWhile {
                                it.isNotEmpty()
                            }.flatMapConcat {
                                it.items.asPlayable().asFlow()
                            }
                                .toList()
                                .shuffled()
                                .chunked(100)
                                .forEach {
                                    playlists.addPlayablesToClientPlaylist(
                                        playlist = createdPlaylist.id,
                                        playables = it.toTypedArray()
                                    )
                                }

                            bot.sendMessage(currentChat, "Shuffled")
                            return@callbackQueryWithLock
                        }

                        val playlist = playlists.getPlaylist(playlistId)

                        if (playlist == null) {
                            bot.sendMessage(currentChat, "Playlist dont exists anymore")
                            return@callbackQueryWithLock
                        }

                        bot.sendMessage(currentChat, "Start shuffling ${playlist.name}")

                        val shuffledName = playlist.shuffledName

                        val shuffledPlaylist = findClientPlaylistByName(shuffledName)
                        if (shuffledPlaylist != null) {
                            playlists.deleteClientPlaylist(shuffledPlaylist.id)
                            bot.sendMessage(currentChat, "Removing old playlist")
                        }

                        val createdPlaylist = playlists.createClientPlaylist(name = shuffledName, public = false)

                        val tracks = mutableListOf<PlayableUri>()
                        var page = playlist.tracks
                        tracks.addAll(page.asPlayable())

                        while (page.next != null) {
                            page = page.getNext()!!
                            tracks.addAll(page.asPlayable())
                        }

                        tracks
                            .shuffled()
                            .chunked(100).forEach {
                                playlists.addPlayablesToClientPlaylist(
                                    playlist = createdPlaylist.id,
                                    playables = it.toTypedArray()
                                )
                            }

                        bot.sendMessage(currentChat, "Shuffled")
                    }
                }
            }
        }.startPolling()

    }.onLeft { err ->
        println(err)
    }
}

val Playlist.shuffledName get() = "${name}_shuffled"
fun PagingObject<PlaylistTrack>.asPlayable() = map { it!!.track!!.uri }

fun List<SavedTrack>.asPlayable() = map { it.track.uri }

val CommandHandlerEnvironment.currentChat get() = ChatId.fromId(message.chat.id)
val CallbackQueryHandlerEnvironment.currentChat get() = ChatId.fromId(callbackQuery.message!!.chat.id)

fun Dispatcher.commandWithLock(
    name: String,
    allowedUsers: Set<Long>,
    handler: suspend CommandHandlerEnvironment.() -> Unit
) {
    command(name) {
        CoroutineScope(Dispatchers.IO).launch {
            if (processLock.isLocked) {
                bot.sendMessage(currentChat, "Sorry im busy")
                return@launch
            }

            if (message.from?.id !in allowedUsers) {
                bot.sendMessage(currentChat, "You are not allowed to use this bot")
                return@launch
            }

            processLock.withLock {
                handler(this@command)
            }
        }
    }
}

fun Dispatcher.callbackQueryWithLock(
    name: String,
    handler: suspend CallbackQueryHandlerEnvironment.() -> Unit
) {
    callbackQuery(name) {
        CoroutineScope(Dispatchers.IO).launch {
            if (processLock.isLocked) {
                bot.sendMessage(currentChat, "Sorry im busy")
                return@launch
            }

            processLock.withLock {
                handler(this@callbackQuery)
            }
        }
    }
}

inline fun CommandHandlerEnvironment.requireLogin(block: SpotifyClientApi.() -> Unit) {
    if (spotifyApi == null) {
        bot.sendMessage(currentChat, "You are not logged in spotify")
        return
    }

    block.invoke(spotifyApi!!)
}

suspend fun SpotifyClientApi.findClientPlaylistByName(name: String) = playlists.getClientPlaylists()
    .getAllItems()
    .firstOrNull { it?.name == name }

inline fun CallbackQueryHandlerEnvironment.requireLogin(block: SpotifyClientApi.() -> Unit) {
    if (spotifyApi == null) {
        bot.sendMessage(currentChat, "You are not logged in spotify")
        return
    }

    block.invoke(spotifyApi!!)
}

fun authorizationCodeReceiveServer(channel: Channel<String>, onReceive: () -> Unit) = { request: Request ->
    val code = request.uri.query.split("=")[1]
    channel.trySendBlocking(code)
        .onSuccess {
            channel.close()
            onReceive.invoke()
        }
        .onFailure { println(it.toString()) }
    Response(Status.OK)
}.asServer(Undertow(8888))