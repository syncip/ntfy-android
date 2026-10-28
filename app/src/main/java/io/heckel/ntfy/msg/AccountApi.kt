package io.heckel.ntfy.msg

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.util.HttpUtil
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Account endpoints of the ntfy server (/v1/account/...), as used by the web app's account page.
 * Only called while the account screen is open, never in the background.
 */
class AccountApi(private val context: Context) {
    private val repository = Repository.getInstance(context)
    private val gson = Gson()

    suspend fun get(user: User): Account {
        val body = execute(user, builder(user, "/v1/account").get())
        return gson.fromJson(body, Account::class.java)
    }

    suspend fun changePassword(user: User, currentPassword: String, newPassword: String) {
        val json = gson.toJson(mapOf("password" to currentPassword, "new_password" to newPassword))
        execute(user, builder(user, "/v1/account/password").post(json.toRequestBody()))
    }

    suspend fun addReservation(user: User, topic: String, everyone: String) {
        val json = gson.toJson(mapOf("topic" to topic, "everyone" to everyone))
        execute(user, builder(user, "/v1/account/reservation").post(json.toRequestBody()))
    }

    suspend fun deleteReservation(user: User, topic: String) {
        val path = "/v1/account/reservation/" + URLEncoder.encode(topic, "UTF-8")
        execute(user, builder(user, path).delete())
    }

    suspend fun deleteToken(user: User, token: String) {
        execute(user, builder(user, "/v1/account/token").addHeader("X-Token", token).delete())
    }

    private suspend fun builder(user: User, path: String): Request.Builder {
        return HttpUtil.requestBuilder(user.baseUrl + path, user, repository.getCustomHeaders(user.baseUrl))
    }

    private suspend fun execute(user: User, builder: Request.Builder): String {
        HttpUtil.defaultClient(context, user.baseUrl).newCall(builder.build()).execute().use { response ->
            val body = response.body.string()
            if (response.code == 401 || response.code == 403) {
                throw ApiService.UnauthorizedException(user)
            }
            if (!response.isSuccessful) {
                val error = try {
                    gson.fromJson(body, ErrorResponse::class.java)?.error
                } catch (_: Exception) {
                    null
                }
                throw Exception(error ?: "HTTP ${response.code}")
            }
            return body
        }
    }

    data class Account(
        val username: String?,
        val role: String?,
        val tier: Tier?,
        val limits: Limits?,
        val stats: Stats?,
        val reservations: List<Reservation>?,
        val tokens: List<Token>?
    )

    data class Tier(val code: String?, val name: String?)

    data class Limits(
        val messages: Long?,
        val emails: Long?,
        val calls: Long?,
        val reservations: Long?,
        @SerializedName("attachment_total_size") val attachmentTotalSize: Long?
    )

    data class Stats(
        val messages: Long?,
        val emails: Long?,
        val calls: Long?,
        val reservations: Long?,
        @SerializedName("attachment_total_size") val attachmentTotalSize: Long?
    )

    data class Reservation(val topic: String, val everyone: String?)

    data class Token(
        val token: String,
        val label: String?,
        @SerializedName("last_access") val lastAccess: Long?,
        val expires: Long?,
        val provisioned: Boolean?
    )

    private data class ErrorResponse(val error: String?)

    companion object {
        const val ROLE_ADMIN = "admin"
        const val EVERYONE_DENY_ALL = "deny-all"
        const val EVERYONE_READ_ONLY = "read-only"
        const val EVERYONE_WRITE_ONLY = "write-only"
        const val EVERYONE_READ_WRITE = "read-write"
    }
}
