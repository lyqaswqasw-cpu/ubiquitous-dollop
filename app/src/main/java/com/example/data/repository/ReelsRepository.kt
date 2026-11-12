package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.DeepLinkPayload
import com.example.data.model.ReelCommentItem
import com.example.data.model.ReelItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Repository for FOUL REELS & Smart Deep Linking / Link Copying.
 * Connected strictly to the new Firebase Realtime Database:
 * https://foul-b7671-default-rtdb.firebaseio.com (Project ID: foul-b7671)
 * and the FOUL Cloudflare Worker / Backend:
 * https://foul.lydrwty8-12f.workers.dev
 */
class ReelsRepository {

    companion object {
        const val FIREBASE_DB_URL = "https://foul-b7671-default-rtdb.firebaseio.com"
        const val SERVER_URL = "https://foul.lydrwty8-12f.workers.dev"
        const val DEEP_LINK_SCHEME = "foultv"
        const val OFFICIAL_CHANNEL_URL = "https://t.me/jdj_q"
        const val REELS_CHANNEL_URL = "https://t.me/kdndjj98383838jdjdj"
        const val DEFAULT_REELS_CHAT = "kdndjj98383838jdjdj"
        private const val TELEGRAM_BOT_TOKEN = "9228657664:AAG3ATkyVoshvHxn2-Xsv2duoormdm8Blsk"

        fun buildSmartShareUrl(
            type: String,
            id: String,
            title: String = "",
            streamUrl: String = "",
            chatId: String = "",
            messageId: String = ""
        ): String {
            val cleanType = type.trim().lowercase().ifBlank { "reel" }
            val params = mutableListOf<String>()
            if (id.isNotBlank()) params.add("id=${encode(id)}")
            if (chatId.isNotBlank()) params.add("chat_id=${encode(chatId.removePrefix("@"))}")
            if (messageId.isNotBlank()) params.add("message_id=${encode(messageId)}")
            if (streamUrl.isNotBlank()) params.add("stream_url=${encode(streamUrl)}")
            if (title.isNotBlank()) params.add("title=${encode(title)}")
            val query = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
            return "$SERVER_URL/open/$cleanType$query"
        }

        fun buildCustomDeepLink(
            type: String,
            id: String,
            title: String = "",
            streamUrl: String = "",
            chatId: String = "",
            messageId: String = ""
        ): String {
            val cleanType = type.trim().lowercase().ifBlank { "reel" }
            val params = mutableListOf<String>()
            if (id.isNotBlank()) params.add("id=${encode(id)}")
            if (chatId.isNotBlank()) params.add("chat_id=${encode(chatId.removePrefix("@"))}")
            if (messageId.isNotBlank()) params.add("message_id=${encode(messageId)}")
            if (streamUrl.isNotBlank()) params.add("stream_url=${encode(streamUrl)}")
            if (title.isNotBlank()) params.add("title=${encode(title)}")
            val query = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
            return "$DEEP_LINK_SCHEME://open/$cleanType$query"
        }

        fun parseDeepLinkUri(uri: Uri?): DeepLinkPayload? {
            if (uri == null) return null
            val scheme = (uri.scheme ?: "").lowercase()
            val host = (uri.host ?: "").lowercase()
            val pathSegments = uri.pathSegments ?: emptyList()

            // Case 1: foultv://open/{type}?id=... or foultv://{type}/{id}
            if (scheme == DEEP_LINK_SCHEME) {
                val type = when {
                    host == "open" && pathSegments.isNotEmpty() -> pathSegments[0].lowercase()
                    host in listOf("reel", "reels", "channel", "movie", "series", "story") ->
                        if (host == "reels" || host == "story") "reel" else host
                    pathSegments.isNotEmpty() -> pathSegments[0].lowercase()
                    else -> "reel"
                }
                val idFromPath = if (host == "open" && pathSegments.size >= 2) {
                    pathSegments[1]
                } else if (host != "open" && pathSegments.isNotEmpty()) {
                    pathSegments[0]
                } else ""
                val id = uri.getQueryParameter("id").orEmpty().ifBlank { idFromPath }
                val chatId = uri.getQueryParameter("chat_id").orEmpty()
                val messageId = uri.getQueryParameter("message_id").orEmpty()
                val streamUrl = uri.getQueryParameter("stream_url").orEmpty()
                val title = uri.getQueryParameter("title").orEmpty()
                val resolvedId = if (id.isBlank() && chatId.isNotBlank() && messageId.isNotBlank()) {
                    "${chatId.removePrefix("@")}_$messageId"
                } else id
                return DeepLinkPayload(
                    type = if (type == "reels" || type == "story") "reel" else type,
                    id = resolvedId,
                    chatId = chatId,
                    messageId = messageId,
                    streamUrl = streamUrl,
                    title = title
                )
            }

            // Case 2: https://foul.lydrwty8-12f.workers.dev/open/{type} or /share/{chat_id}/{message_id} or /stream_msg/{chat_id}/{message_id}
            if (scheme == "http" || scheme == "https") {
                if (pathSegments.isNotEmpty()) {
                    val first = pathSegments[0].lowercase()
                    if (first == "open" && pathSegments.size >= 2) {
                        val rawType = pathSegments[1].lowercase()
                        val type = if (rawType == "reels" || rawType == "story") "reel" else rawType
                        val pathId = if (pathSegments.size >= 3) pathSegments[2] else ""
                        val id = uri.getQueryParameter("id").orEmpty().ifBlank { pathId }
                        val chatId = uri.getQueryParameter("chat_id").orEmpty()
                        val messageId = uri.getQueryParameter("message_id").orEmpty()
                        val streamUrl = uri.getQueryParameter("stream_url").orEmpty()
                        val title = uri.getQueryParameter("title").orEmpty()
                        val resolvedId = if (id.isBlank() && chatId.isNotBlank() && messageId.isNotBlank()) {
                            "${chatId.removePrefix("@")}_$messageId"
                        } else id
                        return DeepLinkPayload(
                            type = type,
                            id = resolvedId,
                            chatId = chatId,
                            messageId = messageId,
                            streamUrl = streamUrl,
                            title = title
                        )
                    } else if (first == "share" && pathSegments.size >= 3) {
                        val chatId = pathSegments[1].removePrefix("@")
                        val messageId = pathSegments[2]
                        val id = "${chatId}_$messageId"
                        return DeepLinkPayload(
                            type = "reel",
                            id = id,
                            chatId = chatId,
                            messageId = messageId,
                            streamUrl = "$SERVER_URL/stream_msg/$chatId/$messageId",
                            title = "FOUL REELS"
                        )
                    } else if (first == "stream_msg" && pathSegments.size >= 3) {
                        val chatId = pathSegments[1].removePrefix("@")
                        val messageId = pathSegments[2]
                        val id = "${chatId}_$messageId"
                        return DeepLinkPayload(
                            type = "reel",
                            id = id,
                            chatId = chatId,
                            messageId = messageId,
                            streamUrl = "$SERVER_URL/stream_msg/$chatId/$messageId",
                            title = "FOUL REELS"
                        )
                    }
                }
            }
            return null
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8")
    }

    /**
     * Fetches reels from the FOUL Backend (/api/reels) and merges/syncs with the new
     * Firebase Realtime Database (https://foul-b7671-default-rtdb.firebaseio.com/reels.json).
     */
    suspend fun fetchReels(currentUserId: String): List<ReelItem> = withContext(Dispatchers.IO) {
        val mergedById = linkedMapOf<String, ReelItem>()

        // 1. Fetch directly from Firebase foul-b7671 (/reels.json)
        runCatching {
            val rawJson = httpGet("$FIREBASE_DB_URL/reels.json")
            if (rawJson.isNotBlank() && rawJson != "null") {
                val root = JSONObject(rawJson)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = root.optJSONObject(key) ?: continue
                    val item = parseReelJson(key, obj, currentUserId)
                    if (item.streamUrl.isNotBlank()) {
                        mergedById[item.id] = item
                    }
                }
            }
        }

        // 2. Also fetch from SERVER_URL/api/reels to pick up any worker-cached items
        runCatching {
            val apiJson = httpGet("$SERVER_URL/api/reels")
            if (apiJson.isNotBlank()) {
                val root = JSONObject(apiJson)
                val arr: JSONArray? = root.optJSONArray("reels") ?: root.optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val key = obj.optString("id").ifBlank { "reel_$i" }
                        val item = parseReelJson(key, obj, currentUserId)
                        if (item.streamUrl.isNotBlank() && !mergedById.containsKey(item.id)) {
                            mergedById[item.id] = item
                        }
                    }
                }
            }
        }

        mergedById.values.sortedByDescending { it.createdAt }
    }

    /**
     * Uploads a video selected from the user's device via the backend (/api/upload)
     * or Telegram Storage fallback and writes the enriched Reel record strictly to
     * https://foul-b7671-default-rtdb.firebaseio.com/reels/{id}.json.
     */
    suspend fun uploadReelVideo(
        context: Context,
        videoUri: Uri,
        caption: String,
        overlayText: String,
        userId: String,
        userName: String
    ): Result<ReelItem> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = resolveFileName(context, videoUri) ?: "foul_reel_${System.currentTimeMillis()}.mp4"
            val videoBytes = context.contentResolver.openInputStream(videoUri)?.use { it.readBytes() }
                ?: throw IllegalStateException("تعذر قراءة ملف الفيديو المختار")

            var uploadedItem: ReelItem? = null

            // Primary path: Upload multipart to FOUL Backend (/api/upload)
            runCatching {
                val responseStr = uploadMultipartToBackend(
                    endpoint = "$SERVER_URL/api/upload",
                    fileFieldName = "video",
                    fileName = fileName,
                    fileBytes = videoBytes,
                    fields = mapOf(
                        "caption" to caption,
                        "overlay_text" to overlayText.ifBlank { caption },
                        "user_id" to userId,
                        "user_name" to userName,
                        "user_handle" to "@foultv"
                    )
                )
                val json = JSONObject(responseStr)
                val reelObj = json.optJSONObject("reel") ?: json
                val id = reelObj.optString("id").ifBlank {
                    val cId = reelObj.optString("chat_id", DEFAULT_REELS_CHAT).removePrefix("@")
                    val mId = reelObj.optLong("message_id", System.currentTimeMillis() / 1000L)
                    "${cId}_$mId"
                }
                val parsed = parseReelJson(id, reelObj, userId)
                // Guarantee persistence in foul-b7671 Firebase Realtime Database
                saveReelToFirebase(parsed)
                uploadedItem = parsed
            }

            if (uploadedItem != null) {
                return@runCatching uploadedItem!!
            }

            // Fallback path: Upload directly to Telegram Storage Bot API (@kdndjj98383838jdjdj) + save to foul-b7671
            val tgRespStr = uploadMultipartToBackend(
                endpoint = "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendVideo",
                fileFieldName = "video",
                fileName = fileName,
                fileBytes = videoBytes,
                fields = mapOf(
                    "chat_id" to "@$DEFAULT_REELS_CHAT",
                    "caption" to caption,
                    "supports_streaming" to "true"
                )
            )
            val tgJson = JSONObject(tgRespStr)
            if (!tgJson.optBoolean("ok", false)) {
                throw IllegalStateException("تعذر رفع الفيديو إلى التخزين السحابي: ${tgJson.optString("description")}")
            }
            val resultObj = tgJson.getJSONObject("result")
            val messageId = resultObj.optLong("message_id", System.currentTimeMillis() / 1000L)
            val videoObj = resultObj.optJSONObject("video") ?: resultObj.optJSONObject("document")
            val fileId = videoObj?.optString("file_id").orEmpty()

            // Resolve direct streamable URL if possible
            var directFileUrl = ""
            if (fileId.isNotBlank()) {
                runCatching {
                    val fileInfoStr = httpGet(
                        "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getFile?file_id=${encode(fileId)}"
                    )
                    val filePath = JSONObject(fileInfoStr)
                        .optJSONObject("result")
                        ?.optString("file_path")
                        .orEmpty()
                    if (filePath.isNotBlank()) {
                        directFileUrl = "https://api.telegram.org/file/bot$TELEGRAM_BOT_TOKEN/$filePath"
                    }
                }
            }

            val reelId = "${DEFAULT_REELS_CHAT}_$messageId"
            val workerStreamUrl = "$SERVER_URL/stream_msg/$DEFAULT_REELS_CHAT/$messageId"
            val effectiveStreamUrl = workerStreamUrl
            val copyVideoUrl = "$SERVER_URL/stream_msg/$DEFAULT_REELS_CHAT/$messageId/video.mp4"
            val shareUrl = buildSmartShareUrl(
                type = "reel",
                id = reelId,
                title = caption.ifBlank { "FOUL REELS" },
                streamUrl = effectiveStreamUrl,
                chatId = DEFAULT_REELS_CHAT,
                messageId = messageId.toString()
            )
            val deepLink = buildCustomDeepLink(
                type = "reel",
                id = reelId,
                title = caption.ifBlank { "FOUL REELS" },
                streamUrl = effectiveStreamUrl,
                chatId = DEFAULT_REELS_CHAT,
                messageId = messageId.toString()
            )

            val newReel = ReelItem(
                id = reelId,
                chatId = DEFAULT_REELS_CHAT,
                messageId = messageId,
                fileId = fileId,
                caption = caption,
                overlayText = overlayText.ifBlank { caption },
                userId = userId,
                userName = userName,
                userHandle = "@foultv",
                streamUrl = directFileUrl.ifBlank { effectiveStreamUrl },
                videoUrl = effectiveStreamUrl,
                copyVideoUrl = copyVideoUrl,
                shareUrl = shareUrl,
                deepLink = deepLink,
                channelUrl = OFFICIAL_CHANNEL_URL,
                reelsChannelUrl = REELS_CHANNEL_URL,
                likesCount = 0,
                viewsCount = 0,
                sharesCount = 0,
                isLikedByMe = false,
                createdAt = System.currentTimeMillis()
            )
            saveReelToFirebase(newReel, directFileUrl)
            newReel
        }
    }

    suspend fun toggleLike(reel: ReelItem, userId: String): Result<ReelItem> = withContext(Dispatchers.IO) {
        runCatching {
            val rawStr = httpGet("$FIREBASE_DB_URL/reels/${reel.id}.json")
            val obj = if (rawStr.isNotBlank() && rawStr != "null") JSONObject(rawStr) else JSONObject()
            val likedBy = obj.optJSONObject("liked_by") ?: JSONObject()

            val currentlyLiked = likedBy.optBoolean(userId, false)
            if (currentlyLiked) {
                likedBy.remove(userId)
            } else {
                likedBy.put(userId, true)
            }
            val newLikesCount = likedBy.length()
            val patch = JSONObject().apply {
                put("liked_by", likedBy)
                put("likes_count", newLikesCount)
            }
            httpRequest("$FIREBASE_DB_URL/reels/${reel.id}.json", "PATCH", patch.toString())

            // Also notify backend asynchronously
            runCatching {
                val reqBody = JSONObject().apply { put("user_id", userId) }.toString()
                httpRequest("$SERVER_URL/api/reels/${reel.id}/like", "POST", reqBody)
            }

            reel.copy(
                likesCount = newLikesCount,
                isLikedByMe = !currentlyLiked
            )
        }
    }

    suspend fun incrementView(reel: ReelItem): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val newViews = reel.viewsCount + 1
            val patch = JSONObject().apply {
                put("views_count", newViews)
            }
            httpRequest("$FIREBASE_DB_URL/reels/${reel.id}.json", "PATCH", patch.toString())
            runCatching {
                httpRequest("$SERVER_URL/api/reels/${reel.id}/view", "POST", "{}")
            }
            newViews
        }
    }

    suspend fun incrementShareCount(reel: ReelItem): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val newShares = reel.sharesCount + 1
            val patch = JSONObject().apply {
                put("shares_count", newShares)
            }
            httpRequest("$FIREBASE_DB_URL/reels/${reel.id}.json", "PATCH", patch.toString())
            newShares
        }
    }

    suspend fun fetchComments(reelId: String): List<ReelCommentItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ReelCommentItem>()
        runCatching {
            val raw = httpGet("$FIREBASE_DB_URL/reel_comments/$reelId.json")
            if (raw.isNotBlank() && raw != "null") {
                val root = JSONObject(raw)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val obj = root.optJSONObject(k) ?: continue
                    list.add(
                        ReelCommentItem(
                            id = obj.optString("id").ifBlank { k },
                            reelId = reelId,
                            userId = obj.optString("user_id", "foul_user"),
                            userName = obj.optString("user_name", "مشاهد FOUL TV"),
                            text = obj.optString("text", ""),
                            createdAt = obj.optLong("created_at", 0L)
                        )
                    )
                }
            }
        }
        list.sortedByDescending { it.createdAt }
    }

    suspend fun addComment(
        reelId: String,
        userId: String,
        userName: String,
        text: String
    ): Result<ReelCommentItem> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val commentId = "c_${now}_${UUID.randomUUID().toString().take(6)}"
            val item = ReelCommentItem(
                id = commentId,
                reelId = reelId,
                userId = userId,
                userName = userName,
                text = text.trim(),
                createdAt = now
            )
            val payload = JSONObject().apply {
                put("id", item.id)
                put("reel_id", item.reelId)
                put("user_id", item.userId)
                put("user_name", item.userName)
                put("text", item.text)
                put("created_at", item.createdAt)
            }
            httpRequest(
                "$FIREBASE_DB_URL/reel_comments/$reelId/$commentId.json",
                "PUT",
                payload.toString()
            )
            item
        }
    }

    private fun saveReelToFirebase(reel: ReelItem, directVideoUrl: String = "") {
        val payload = JSONObject().apply {
            put("id", reel.id)
            put("chat_id", reel.chatId)
            put("message_id", reel.messageId)
            put("file_id", reel.fileId)
            put("caption", reel.caption)
            put("overlay_text", reel.overlayText)
            put("user_id", reel.userId)
            put("user_name", reel.userName)
            put("user_handle", reel.userHandle)
            put("stream_url", reel.streamUrl)
            put("video_url", reel.videoUrl)
            put("copy_video_url", reel.copyVideoUrl)
            put("share_url", reel.shareUrl)
            put("deep_link", reel.deepLink)
            put("channel_url", reel.channelUrl)
            put("reels_channel_url", reel.reelsChannelUrl)
            if (directVideoUrl.isNotBlank()) {
                put("direct_video_url", directVideoUrl)
            }
            put("likes_count", reel.likesCount)
            put("views_count", reel.viewsCount)
            put("shares_count", reel.sharesCount)
            put("created_at", reel.createdAt)
        }
        httpRequest("$FIREBASE_DB_URL/reels/${reel.id}.json", "PUT", payload.toString())
    }

    private fun parseReelJson(key: String, obj: JSONObject, currentUserId: String): ReelItem {
        val chatId = obj.optString("chat_id", DEFAULT_REELS_CHAT).removePrefix("@").ifBlank { DEFAULT_REELS_CHAT }
        val messageId = obj.optLong("message_id", obj.optLong("msg_id", 0L))
        val id = obj.optString("id").ifBlank {
            if (messageId > 0L) "${chatId}_$messageId" else key
        }

        val defaultStreamUrl = if (messageId > 0L) {
            "$SERVER_URL/stream_msg/$chatId/$messageId"
        } else ""
        val directVideo = obj.optString("direct_video_url").orEmpty()
        val rawStream = obj.optString("stream_url")
            .ifBlank { obj.optString("video_url") }
            .ifBlank { defaultStreamUrl }
        val streamUrl = if (directVideo.startsWith("http")) directVideo else rawStream
        val videoUrl = rawStream.ifBlank { streamUrl }

        val copyVideoUrl = obj.optString("copy_video_url").ifBlank {
            if (messageId > 0L) "$SERVER_URL/stream_msg/$chatId/$messageId/video.mp4" else videoUrl
        }
        val caption = obj.optString("caption").ifBlank { obj.optString("overlay_text", "") }
        val overlayText = obj.optString("overlay_text").ifBlank { caption }

        val shareUrl = obj.optString("share_url").ifBlank {
            buildSmartShareUrl(
                type = "reel",
                id = id,
                title = caption.ifBlank { "FOUL REELS" },
                streamUrl = videoUrl,
                chatId = chatId,
                messageId = if (messageId > 0L) messageId.toString() else ""
            )
        }
        val deepLink = obj.optString("deep_link").ifBlank {
            buildCustomDeepLink(
                type = "reel",
                id = id,
                title = caption.ifBlank { "FOUL REELS" },
                streamUrl = videoUrl,
                chatId = chatId,
                messageId = if (messageId > 0L) messageId.toString() else ""
            )
        }

        val likedByObj = obj.optJSONObject("liked_by")
        val isLiked = likedByObj?.optBoolean(currentUserId, false) ?: false
        val likesCount = obj.optInt(
            "likes_count",
            obj.optInt("likes", likedByObj?.length() ?: 0)
        )
        val viewsCount = obj.optInt("views_count", obj.optInt("views", 0))
        val sharesCount = obj.optInt("shares_count", obj.optInt("shares", 0))

        return ReelItem(
            id = id,
            chatId = chatId,
            messageId = messageId,
            fileId = obj.optString("file_id", ""),
            caption = caption,
            overlayText = overlayText,
            userId = obj.optString("user_id", "foul_user"),
            userName = obj.optString("user_name", "FOUL TV"),
            userHandle = obj.optString("user_handle", "@foultv"),
            streamUrl = streamUrl,
            videoUrl = videoUrl,
            copyVideoUrl = copyVideoUrl,
            shareUrl = shareUrl,
            deepLink = deepLink,
            channelUrl = obj.optString("channel_url", OFFICIAL_CHANNEL_URL).ifBlank { OFFICIAL_CHANNEL_URL },
            reelsChannelUrl = obj.optString("reels_channel_url", REELS_CHANNEL_URL).ifBlank { REELS_CHANNEL_URL },
            likesCount = likesCount,
            viewsCount = viewsCount,
            sharesCount = sharesCount,
            isLikedByMe = isLiked,
            createdAt = obj.optLong("created_at", obj.optLong("timestamp", System.currentTimeMillis()))
        )
    }

    private fun resolveFileName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            }
        }.getOrNull()
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpRequest(urlStr: String, method: String, jsonBody: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            if (method == "PATCH") {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                requestMethod = method
            }
            connectTimeout = 12000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun uploadMultipartToBackend(
        endpoint: String,
        fileFieldName: String,
        fileName: String,
        fileBytes: ByteArray,
        fields: Map<String, String>
    ): String {
        val boundary = "----FoulAndroidBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            doInput = true
            doOutput = true
            useCaches = false
            connectTimeout = 30000
            readTimeout = 90000
            requestMethod = "POST"
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        return try {
            DataOutputStream(conn.outputStream).use { dos ->
                for ((key, value) in fields) {
                    dos.writeBytes(twoHyphens + boundary + lineEnd)
                    dos.writeBytes("Content-Disposition: form-data; name=\"$key\"$lineEnd$lineEnd")
                    dos.write(value.toByteArray(Charsets.UTF_8))
                    dos.writeBytes(lineEnd)
                }

                dos.writeBytes(twoHyphens + boundary + lineEnd)
                dos.writeBytes(
                    "Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"$lineEnd"
                )
                dos.writeBytes("Content-Type: video/mp4$lineEnd$lineEnd")
                dos.write(fileBytes)
                dos.writeBytes(lineEnd)
                dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
                dos.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: $resp")
            }
            resp
        } finally {
            conn.disconnect()
        }
    }
}
