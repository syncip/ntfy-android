package io.heckel.ntfy.msg

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Logs in with username/password once, creates an access token (POST /v1/account/token) and
 * returns a [User] that holds the token instead of the password. The password is never stored.
 *
 * Tokens are sent as "Authorization: Bearer", see [HttpUtil.requestBuilder].
 */
class AccountTokens(private val context: Context) {
    private val repository = Repository.getInstance(context)
    private val gson = Gson()

    /**
     * Returns [user] with its password replaced by a newly created token. If the server has no
     * account API (404), the user is returned unchanged, since a password is the only option there.
     * Throws if the login fails.
     */
    suspend fun exchangePassword(user: User): User {
        if (isToken(user.password)) return user
        val body = gson.toJson(TokenRequest(label = tokenLabel()))
        val request = HttpUtil.requestBuilder(tokenUrl(user.baseUrl), user, repository.getCustomHeaders(user.baseUrl))
            .post(body.toRequestBody())
            .build()
        HttpUtil.defaultClient(context, user.baseUrl).newCall(request).execute().use { response ->
            if (response.code == 404) {
                Log.w(TAG, "Server ${user.baseUrl} cannot create tokens, keeping password")
                return user
            }
            if (response.code == 401 || response.code == 403) {
                throw ApiService.UnauthorizedException(user)
            }
            if (!response.isSuccessful) {
                throw Exception("Unexpected response ${response.code} when creating token")
            }
            val token = gson.fromJson(response.body.string(), TokenResponse::class.java)?.token
            if (token.isNullOrEmpty() || !isToken(token)) {
                throw Exception("Server returned no token")
            }
            Log.d(TAG, "Created token for ${user.username} on ${user.baseUrl}")
            return user.copy(password = token)
        }
    }

    /** Revokes the token of [user] on the server. Failures are logged and otherwise ignored. */
    suspend fun revoke(user: User) {
        if (!isToken(user.password)) return
        try {
            val request = HttpUtil.requestBuilder(tokenUrl(user.baseUrl), user, repository.getCustomHeaders(user.baseUrl))
                .addHeader("X-Token", user.password)
                .delete()
                .build()
            HttpUtil.defaultClient(context, user.baseUrl).newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Unexpected response ${response.code}")
            }
            Log.d(TAG, "Revoked token for ${user.username} on ${user.baseUrl}")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to revoke token for ${user.username} on ${user.baseUrl}: ${e.message}")
        }
    }

    private fun tokenLabel() = "ntfy Sync (${Build.MANUFACTURER} ${Build.MODEL})"

    private data class TokenRequest(val label: String)
    private data class TokenResponse(val token: String?)

    companion object {
        private const val TAG = "NtfyAccountTokens"
        private const val TOKEN_PREFIX = "tk_"

        fun isToken(password: String) = password.startsWith(TOKEN_PREFIX)
        private fun tokenUrl(baseUrl: String) = "$baseUrl/v1/account/token"
    }
}
