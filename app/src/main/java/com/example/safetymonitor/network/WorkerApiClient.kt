package com.example.safetymonitor.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class WorkerApiException(val httpStatus: Int, val code: String?, message: String) : IOException(message)

class WorkerApiClient(baseUrl: String, allowInsecureHttp: Boolean = false) {
    private val baseUri: URI = try {
        URI(baseUrl).also { uri ->
            require(!uri.isOpaque && uri.host != null && uri.host.isNotBlank()) { "Invalid base URL host" }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Invalid base URL" }
            require(uri.port in -1..65535) { "Invalid base URL port" }
            require(uri.scheme.equals("https", ignoreCase = true) ||
                (allowInsecureHttp && uri.scheme.equals("http", ignoreCase = true))) {
                "HTTPS is required unless insecure HTTP is explicitly allowed"
            }
        }
    } catch (_: java.net.URISyntaxException) {
        throw IllegalArgumentException("Invalid base URL")
    }

    suspend fun login(request: WorkerLoginRequest): WorkerTokenResponse = withContext(Dispatchers.IO) {
        parseResponse(request("POST", "/api/worker/auth/login", 200, body = request.toJson()), { WorkerTokenResponse.fromJson(it) })
    }

    suspend fun logout(accessToken: String) = withContext(Dispatchers.IO) {
        request("POST", "/api/worker/auth/logout", 204, token = accessToken)
        Unit
    }

    suspend fun createEvent(accessToken: String, request: WorkerEventRequest): WorkerEventInfo = withContext(Dispatchers.IO) {
        parseResponse(request("POST", "/api/worker/events", 201, accessToken, request.toJson()), { WorkerEventInfo.fromJson(it) })
    }

    suspend fun updateProfile(accessToken: String, request: WorkerProfileUpdate): WorkerInfo = withContext(Dispatchers.IO) {
        parseResponse(request("PATCH", "/api/worker/me", 200, accessToken, request.toJson()), { WorkerInfo.fromJson(it) })
    }

    suspend fun changePassword(accessToken: String, request: WorkerPasswordChange) = withContext(Dispatchers.IO) {
        request("PATCH", "/api/worker/me/password", 204, accessToken, request.toJson())
        Unit
    }

    suspend fun deleteAccount(accessToken: String, request: WorkerPasswordConfirmation) = withContext(Dispatchers.IO) {
        request("DELETE", "/api/worker/me", 204, accessToken, request.toJson())
        Unit
    }

    private fun request(method: String, path: String, expectedStatus: Int, token: String? = null, body: JSONObject? = null): String? {
        if (token != null) {
            require(token.isNotBlank() && token.none { it == '\r' || it == '\n' }) { "Invalid access token" }
        }
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        val connection = baseUri.resolve(path).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (bytes != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status != expectedStatus) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                throw httpError(status, error)
            }
            return if (status == 204) null else connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpError(status: Int, body: String?): WorkerApiException {
        val json = try {
            body?.let { JSONObject(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val code = json?.opt("code") as? String
        val message = (json?.opt("message") as? String)?.takeIf { it.isNotBlank() } ?: "HTTP $status"
        return WorkerApiException(status, code, message)
    }

    private fun <T> parseResponse(body: String?, parse: (JSONObject) -> T): T = try {
        parse(JSONObject(body ?: throw IOException("Missing JSON response")))
    } catch (e: IOException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw IOException("Malformed success response")
    }
}
