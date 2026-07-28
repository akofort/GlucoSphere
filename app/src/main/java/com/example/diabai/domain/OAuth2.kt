package com.example.diabai.domain

import android.net.Uri
import android.util.Base64
import com.example.diabai.data.OAuth2Config
import com.example.diabai.network.HttpStatusException
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

/** Registered in AndroidManifest.xml on MainActivity as a `diabai://` deep link -- the redirect
 * every configured OAuth2 client must use, and the only one MainActivity ever routes back into
 * the app for (see [OAuth2CallbackBus]). */
const val OAUTH2_REDIRECT_URI = "diabai://oauth-callback"

private val oauth2TokenJson = Json { ignoreUnknownKeys = true }

data class PkcePair(val verifier: String, val challenge: String)

/** RFC 7636 PKCE: a random verifier plus its SHA-256/base64url ("S256") challenge -- required
 * alongside the authorization code so a malicious app that intercepts the redirect can't replay
 * it against the token endpoint without also knowing the verifier this app kept to itself. */
fun generatePkcePair(): PkcePair {
    val randomBytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
    val verifier = randomBytes.base64UrlEncode()
    val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)).base64UrlEncode()
    return PkcePair(verifier, challenge)
}

private fun ByteArray.base64UrlEncode(): String =
    Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

fun buildAuthorizationUrl(config: OAuth2Config, state: String, codeChallenge: String): Uri =
    Uri.parse(config.authorizationEndpoint).buildUpon()
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("client_id", config.clientId)
        .appendQueryParameter("redirect_uri", OAUTH2_REDIRECT_URI)
        .appendQueryParameter("state", state)
        .appendQueryParameter("code_challenge", codeChallenge)
        .appendQueryParameter("code_challenge_method", "S256")
        .apply { if (config.scope.isNotBlank()) appendQueryParameter("scope", config.scope) }
        .build()

/** Everything needed to finish the round trip once the Custom Tab redirects back into the app --
 * stashed here (in-memory, single pending flow at a time) between [startOAuth2Login]-equivalent
 * launch and MainActivity's `onNewIntent` picking the redirect back up. A real multi-flow queue
 * isn't needed since a user can only be looking at one browser tab at a time. */
object PendingOAuth2Flow {
    /** [oauth2] is the exact snapshot the authorization URL was built from (endpoints, client
     * ID) -- carried through rather than re-read from persisted settings when the callback
     * arrives, since the user may not have hit "Speichern" yet for those fields. */
    data class Pending(val serverId: String, val verifier: String, val state: String, val oauth2: OAuth2Config)

    @Volatile
    private var pending: Pending? = null

    fun start(value: Pending) {
        pending = value
    }

    /** Returns and clears the pending flow, or null if none is outstanding (e.g. the deep link
     * fired without a login ever having been started, or it already fired once). */
    fun consume(): Pending? = pending.also { pending = null }
}

/** Bridges MainActivity's `onNewIntent`/`onCreate` (Activity-level, fires long before any
 * Composable/ViewModel exists for a cold start via the deep link) to SettingsViewModel, which
 * actually owns the OAuth2 login state and performs the token exchange. */
object OAuth2CallbackBus {
    private val _events = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val events: SharedFlow<Uri> = _events.asSharedFlow()

    fun emit(uri: Uri) {
        _events.tryEmit(uri)
    }
}

/**
 * POSTs the authorization code + PKCE verifier to [config]'s token endpoint (standard
 * `grant_type=authorization_code` form body) and returns [config] with the resulting
 * access/refresh tokens and expiry filled in. Opens and closes its own short-lived HTTP client,
 * matching the "throwaway client per one-off call" pattern already used for the Nightscout/LLM
 * "Key testen" checks elsewhere in this app.
 */
suspend fun exchangeOAuth2Code(config: OAuth2Config, authorizationCode: String, codeVerifier: String): Result<OAuth2Config> =
    runCatching {
        val httpClient = HttpClient(OkHttp) {
            install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        }
        try {
            val response = httpClient.submitForm(
                url = config.tokenEndpoint,
                formParameters = Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", authorizationCode)
                    append("redirect_uri", OAUTH2_REDIRECT_URI)
                    append("client_id", config.clientId)
                    append("code_verifier", codeVerifier)
                },
            )
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw HttpStatusException(response.status.value, body)

            val json = oauth2TokenJson.parseToJsonElement(body).jsonObject
            val accessToken = json["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Token-Antwort enthielt kein access_token")
            val expiresInSeconds = json["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            config.copy(
                accessToken = accessToken,
                refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull ?: config.refreshToken,
                expiresAtEpochMillis = expiresInSeconds?.let { System.currentTimeMillis() + it * 1000 } ?: 0L,
            )
        } finally {
            httpClient.close()
        }
    }

/**
 * Auto-configures [mcpServerUrl]'s OAuth2 client the way Claude's own MCP connector does, so the
 * user experience matches "redirected to the provider, authenticate, done" instead of having to
 * hand-copy an authorization/token endpoint and client ID first:
 *  1. OAuth 2.0 Authorization Server Metadata discovery (RFC 8414) -- tries
 *     `.well-known/oauth-authorization-server` at the MCP endpoint's own path first, then at the
 *     bare origin (the MCP Authorization spec's documented fallback order), to find the
 *     authorization/token endpoints without the user ever typing them in.
 *  2. OAuth 2.0 Dynamic Client Registration (RFC 7591), if the discovered metadata advertises a
 *     `registration_endpoint` -- registers GlucoSphere as a public client (PKCE covers auth-code
 *     interception, so no client secret is needed or requested) and gets back a `client_id`.
 * Servers that support neither still work via the manual endpoint/Client-ID fields as a fallback.
 */
suspend fun discoverOAuth2Metadata(mcpServerUrl: String): Result<OAuth2Config> = runCatching {
    val uri = URI(mcpServerUrl)
    val origin = "${uri.scheme}://${uri.authority}"
    val path = uri.rawPath?.trimEnd('/').orEmpty()
    val discoveryUrls = buildList {
        if (path.isNotBlank()) add("$origin/.well-known/oauth-authorization-server$path")
        add("$origin/.well-known/oauth-authorization-server")
    }

    val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }
    try {
        var metadata: JsonObject? = null
        for (candidateUrl in discoveryUrls) {
            val response = runCatching { httpClient.get(candidateUrl) }.getOrNull() ?: continue
            if (!response.status.isSuccess()) continue
            metadata = runCatching { oauth2TokenJson.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            if (metadata != null) break
        }
        val found = metadata
            ?: error("Keine OAuth2-Metadaten unter \"$origin\" gefunden -- bitte Endpoints manuell eintragen")
        val authorizationEndpoint = found["authorization_endpoint"]?.jsonPrimitive?.contentOrNull
            ?: error("Metadaten enthalten keinen authorization_endpoint")
        val tokenEndpoint = found["token_endpoint"]?.jsonPrimitive?.contentOrNull
            ?: error("Metadaten enthalten keinen token_endpoint")
        // RFC 8414 defines this as an array of strings, not a single value.
        val scope = (found["scopes_supported"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.joinToString(" ")

        val registrationEndpoint = found["registration_endpoint"]?.jsonPrimitive?.contentOrNull
        val clientId = if (registrationEndpoint != null) {
            registerOAuth2Client(httpClient, registrationEndpoint).getOrElse {
                error("Dynamische Client-Registrierung fehlgeschlagen: ${it.message}")
            }
        } else {
            ""
        }

        OAuth2Config(
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            clientId = clientId,
            scope = scope.orEmpty(),
        )
    } finally {
        httpClient.close()
    }
}

private suspend fun registerOAuth2Client(httpClient: HttpClient, registrationEndpoint: String): Result<String> = runCatching {
    val body = buildJsonObject {
        put("client_name", "GlucoSphere")
        putJsonArray("redirect_uris") { add(OAUTH2_REDIRECT_URI) }
        putJsonArray("grant_types") { add("authorization_code") }
        putJsonArray("response_types") { add("code") }
        put("token_endpoint_auth_method", "none")
    }
    val response = httpClient.post(registrationEndpoint) {
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }
    if (!response.status.isSuccess()) throw HttpStatusException(response.status.value, response.bodyAsText())
    val json = oauth2TokenJson.parseToJsonElement(response.bodyAsText()).jsonObject
    json["client_id"]?.jsonPrimitive?.contentOrNull
        ?: error("Registrierungs-Antwort enthielt keine client_id")
}
