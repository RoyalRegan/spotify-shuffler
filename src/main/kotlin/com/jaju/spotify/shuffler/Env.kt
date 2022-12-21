package com.jaju.spotify.shuffler

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.flattenOrAccumulate
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.raise.zipOrAccumulate
import arrow.core.recover
import java.lang.System.getenv

data class Env(val spotify: Spotify) {
    data class Spotify(
        val botToken: String,
        val clientId: String,
        val clientSecret: String,
        val redirectUrl: String,
        val allowedUsers: Set<Long>
    )
}

fun env(): Either<String, Env> =
    either { Env(spotify()) }
        .recover { errors: NonEmptyList<String> ->
            val message = errors.joinToString(prefix = "Environment failed to load:\n", separator = "\n")
            raise(message)
        }

private fun Raise<String>.env(name: String): String =
    ensureNotNull(getenv(name)) { "[$name] configuration missing" }

private fun <T : Any> Raise<String>.env(name: String, mapper: Raise<String>.(String) -> T?): T {
    val actual = env(name)
    return ensureNotNull(mapper(actual)) { "[$name] configuration found with [$actual]" }
}

private fun Raise<NonEmptyList<String>>.spotify(): Env.Spotify =
    zipOrAccumulate(
        { env("SPOTIFY_BOT_TOKEN") },
        { env("SPOTIFY_CLIENT_ID") },
        { env("SPOTIFY_CLIENT_SECRET") },
        { env("SPOTIFY_REDIRECT_URL") },
        {
            env("SPOTIFY_ALLOWED_USERS") {
                it.split(",")
                    .map { userId -> Either.catch { userId.toLong() } }
                    .flattenOrAccumulate()
                    .getOrNull()
                    ?.toSet()
                    ?.let { userIds -> userIds.ifEmpty { raise("SPOTIFY_ALLOWED_USERS can't be empty") } }
            }
        }
    ) { botToken, clientId, secret, redirectUrl, allowedUsers ->
        Env.Spotify(botToken, clientId, secret, redirectUrl, allowedUsers)
    }