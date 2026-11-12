package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.AppAvatarSpec
import com.example.data.model.ChannelEngagementStats
import com.example.data.model.ContactMessageItem
import com.example.data.model.LiveChannel
import com.example.data.model.SocialUserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Dedicated Repository for the Secondary Firebase Database (`foul-aeed9`) to offload the primary database:
 * - Project ID: `foul-aeed9`
 * - Project Number: `754643519034`
 * - API Key: `AIzaSyBTBYXabApdIzCMOddXtNG9tXcOkKfJ2P4`
 *
 * Handles:
 * 1. Separate Per-Channel Views Counter (`عداد المشاهدات`) & Likes Counter (`زر لايك وعداد الإعجابات`).
 * 2. User Account Registration (Email + Password + Username + In-App Boys/Girls Avatar Picker) & Login / Logout.
 * 3. Contact Us (`تواصل معنا`) real-time messaging backed by `foul-aeed9`.
 */
class SocialFirebaseRepository(private val context: Context) {

    companion object {
        const val PROJECT_ID = "foul-aeed9"
        const val PROJECT_NUMBER = "754643519034"
        const val STORAGE_BUCKET = "foul-aeed9.firebasestorage.app"
        const val API_KEY = "AIzaSyBTBYXabApdIzCMOddXtNG9tXcOkKfJ2P4"

        private val RTDB_CANDIDATE_URLS = listOf(
            "https://foul-aeed9-default-rtdb.firebaseio.com",
            "https://foul-aeed9.firebaseio.com",
            "https://foul-aeed9-default-rtdb.europe-west1.firebasedatabase.app"
        )

        private const val FIRESTORE_BASE_URL =
            "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"

        // Dedicated 3rd Firebase Database (`foul-b7671`) EXCLUSIVELY for Real-Time Connected Users Counter ("عداد المتصلين الآن في التطبيق")
        const val PRESENCE_PROJECT_ID = "foul-b7671"
        const val PRESENCE_PROJECT_NUMBER = "442910499881"
        const val PRESENCE_STORAGE_BUCKET = "foul-b7671.firebasestorage.app"
        const val PRESENCE_MOBILESDK_APP_ID = "1:442910499881:android:61149a358fe90d75d52838"
        const val PRESENCE_PACKAGE_NAME = "com.foul.k"
        const val PRESENCE_API_KEY = "AIzaSyBa-v5DeCjqEd02sf6CfZEH9_SlhkbHaak"
        const val PRESENCE_RTDB_URL = "https://foul-b7671-default-rtdb.firebaseio.com"

        private val PRESENCE_RTDB_CANDIDATE_URLS = listOf(
            "https://foul-b7671-default-rtdb.firebaseio.com",
            "https://foul-b7671.firebaseio.com",
            "https://foul-b7671-default-rtdb.europe-west1.firebasedatabase.app"
        )

        private const val PRESENCE_FIRESTORE_BASE_URL =
            "https://firestore.googleapis.com/v1/projects/$PRESENCE_PROJECT_ID/databases/(default)/documents"

        const val INITIAL_AVATAR_BATCH_SIZE = 6

        /**
         * Built-in In-App Avatars ("صور الموجودة في تطبيق بشكل صور من تطبيق بشكل ضهور 6 صور ولد وبنات وإمكانية عرض صور آخر بشكل عرض المزيد").
         * Alternating Boys and Girls so every batch of 6 contains 3 Boys and 3 Girls.
         */
        val APP_AVATARS: List<AppAvatarSpec> = listOf(
            // First 6 Avatars (3 Boys + 3 Girls)
            AppAvatarSpec("boy_1", "أحمد • رياضي", isBoy = true, 0xFF3B0764, 0xFF1E40AF, 0xFF38BDF8, 0),
            AppAvatarSpec("girl_1", "نور • أنيقة", isBoy = false, 0xFF4C1D95, 0xFF831843, 0xFFF472B6, 0),
            AppAvatarSpec("boy_2", "عمر • جيمر", isBoy = true, 0xFF1E1B4B, 0xFF065F46, 0xFF10B981, 1),
            AppAvatarSpec("girl_2", "سارة • مبدعة", isBoy = false, 0xFF311042, 0xFF701A75, 0xFFE879F9, 1),
            AppAvatarSpec("boy_3", "كريم • كابتن", isBoy = true, 0xFF172554, 0xFF3B0764, 0xFF60A5FA, 2),
            AppAvatarSpec("girl_3", "ريم • هادئة", isBoy = false, 0xFF3B0764, 0xFF4C0519, 0xFFFB7185, 2),

            // Second 6 Avatars (Show More #1: 3 Boys + 3 Girls)
            AppAvatarSpec("boy_4", "يوسف • ستايل", isBoy = true, 0xFF0F172A, 0xFF312E81, 0xFF818CF8, 3),
            AppAvatarSpec("girl_4", "ليان • متألقة", isBoy = false, 0xFF2E1065, 0xFF500724, 0xFFFBBF24, 3),
            AppAvatarSpec("boy_5", "زيد • محترف", isBoy = true, 0xFF042F2E, 0xFF1E1B4B, 0xFF2DD4BF, 4),
            AppAvatarSpec("girl_5", "مريم • كلاسيك", isBoy = false, 0xFF3B0764, 0xFF1E3A8A, 0xFFC084FC, 4),
            AppAvatarSpec("boy_6", "طارق • نجم", isBoy = true, 0xFF27272A, 0xFF3B0764, 0xFFF59E0B, 5),
            AppAvatarSpec("girl_6", "جود • مرحة", isBoy = false, 0xFF4A044E, 0xFF1E1B4B, 0xFF38BDF8, 5),

            // Third 6 Avatars (Show More #2: 3 Boys + 3 Girls)
            AppAvatarSpec("boy_7", "سامي • تقني", isBoy = true, 0xFF1E1B4B, 0xFF0C4A6E, 0xFF22D3EE, 0),
            AppAvatarSpec("girl_7", "دانا • نجمة", isBoy = false, 0xFF500724, 0xFF2E1065, 0xFFF43F5E, 1),
            AppAvatarSpec("boy_8", "ريان • ملكي", isBoy = true, 0xFF2E1065, 0xFF14532D, 0xFF4ADE80, 2),
            AppAvatarSpec("girl_8", "حلا • أميرة", isBoy = false, 0xFF3B0764, 0xFF701A75, 0xFFFDE047, 3),
            AppAvatarSpec("boy_9", "فارس • بطل", isBoy = true, 0xFF090D16, 0xFF4C1D95, 0xFFA78BFA, 4),
            AppAvatarSpec("girl_9", "لينا • ذكية", isBoy = false, 0xFF1E1B4B, 0xFF831843, 0xFFF9A8D4, 5)
        )

        fun getAvatarById(avatarId: String): AppAvatarSpec {
            return APP_AVATARS.find { it.id == avatarId } ?: APP_AVATARS.first()
        }

        private val _globalAuthenticatedUserFlow = MutableStateFlow<SocialUserProfile?>(null)
        val globalAuthenticatedUserFlow: StateFlow<SocialUserProfile?> = _globalAuthenticatedUserFlow.asStateFlow()

        private val _openAuthScreenRequestFlow = MutableStateFlow(false)
        val openAuthScreenRequestFlow: StateFlow<Boolean> = _openAuthScreenRequestFlow.asStateFlow()

        fun isUserAuthenticatedWithCompleteProfile(): Boolean {
            val u = _globalAuthenticatedUserFlow.value
            return u != null && u.uid.isNotBlank() && u.isProfileComplete && u.username.isNotBlank()
        }

        fun requestOpenFullAuthScreen() {
            _openAuthScreenRequestFlow.value = true
        }

        fun consumeOpenFullAuthScreenRequest() {
            _openAuthScreenRequestFlow.value = false
        }

        /**
         * Reads Google/Email accounts registered on the Android device via AccountManager
         * combined with any previously used Google accounts saved on this device,
         * returning pairs of (email, displayName) for 1-tap passwordless Google Sign-In.
         */
        fun getDeviceGoogleAccounts(context: Context): List<Pair<String, String>> {
            val result = linkedMapOf<String, String>()

            // 1. Read system Google accounts if accessible via AccountManager
            runCatching {
                val am = android.accounts.AccountManager.get(context)
                val googleAccounts = am.getAccountsByType("com.google")
                for (acc in googleAccounts) {
                    val email = acc.name.trim().lowercase()
                    if (email.contains("@")) {
                        val suggestedName = email.substringBefore("@")
                            .replace(".", " ")
                            .replace("_", " ")
                            .trim()
                        result[email] = suggestedName
                    }
                }
                val allAccounts = am.accounts
                for (acc in allAccounts) {
                    val email = acc.name.trim().lowercase()
                    if (email.contains("@") && !result.containsKey(email)) {
                        val suggestedName = email.substringBefore("@")
                            .replace(".", " ")
                            .replace("_", " ")
                            .trim()
                        result[email] = suggestedName
                    }
                }
            }

            // 2. Read saved Google accounts on this device from SharedPreferences
            runCatching {
                val sp = context.applicationContext.getSharedPreferences(
                    "foul_aeed9_social_prefs",
                    Context.MODE_PRIVATE
                )
                val rawJson = sp.getString("saved_device_google_accounts_json", null)
                if (!rawJson.isNullOrBlank()) {
                    val arr = org.json.JSONArray(rawJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val email = obj.optString("email").trim().lowercase()
                        val name = obj.optString("name").trim()
                        if (email.contains("@")) {
                            result[email] = name.ifBlank {
                                result[email] ?: email.substringBefore("@")
                            }
                        }
                    }
                }
                // Also include current or last logged-in user email if it's a Gmail/Google account
                val lastEmail = sp.getString("user_email", "")?.trim()?.lowercase().orEmpty()
                val lastPersonalName = sp.getString("user_personal_name", "")?.trim().orEmpty()
                val lastUsername = sp.getString("user_username", "")?.trim().orEmpty()
                if (lastEmail.contains("@") && !result.containsKey(lastEmail)) {
                    result[lastEmail] = lastPersonalName.ifBlank { lastUsername.ifBlank { lastEmail.substringBefore("@") } }
                }
            }

            return result.entries.map { it.key to it.value }
        }

        fun rememberDeviceGoogleAccount(context: Context, email: String, displayName: String = "") {
            val cleanEmail = email.trim().lowercase()
            if (!cleanEmail.contains("@")) return
            runCatching {
                val sp = context.applicationContext.getSharedPreferences(
                    "foul_aeed9_social_prefs",
                    Context.MODE_PRIVATE
                )
                val existing = linkedMapOf<String, String>()
                val rawJson = sp.getString("saved_device_google_accounts_json", null)
                if (!rawJson.isNullOrBlank()) {
                    val arr = org.json.JSONArray(rawJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val e = obj.optString("email").trim().lowercase()
                        val n = obj.optString("name").trim()
                        if (e.contains("@")) {
                            existing[e] = n
                        }
                    }
                }
                val cleanName = displayName.trim().ifBlank {
                    existing[cleanEmail]?.takeIf { it.isNotBlank() } ?: cleanEmail.substringBefore("@")
                }
                existing[cleanEmail] = cleanName
                val outArr = org.json.JSONArray()
                for ((e, n) in existing) {
                    outArr.put(
                        JSONObject().apply {
                            put("email", e)
                            put("name", n)
                        }
                    )
                }
                sp.edit().putString("saved_device_google_accounts_json", outArr.toString()).apply()
            }
        }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("foul_aeed9_social_prefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeRtdbBaseUrl: String = RTDB_CANDIDATE_URLS.first()

    @Volatile
    private var activePresenceRtdbBaseUrl: String = PRESENCE_RTDB_CANDIDATE_URLS.first()

    @Volatile
    private var userIdToken: String = prefs.getString("user_id_token", "").orEmpty()

    @Volatile
    private var userRefreshToken: String = prefs.getString("user_refresh_token", "").orEmpty()

    private val _currentUser = MutableStateFlow<SocialUserProfile?>(loadSavedUserProfile()).also {
        _globalAuthenticatedUserFlow.value = it.value
    }
    val currentUser: StateFlow<SocialUserProfile?> = _currentUser.asStateFlow()

    private val _hasSkippedOnboarding = MutableStateFlow(
        prefs.getBoolean("foul_b7671_has_skipped_onboarding", false)
    )
    val hasSkippedOnboarding: StateFlow<Boolean> = _hasSkippedOnboarding.asStateFlow()

    fun markOnboardingSkipped() {
        prefs.edit().putBoolean("foul_b7671_has_skipped_onboarding", true).apply()
        _hasSkippedOnboarding.value = true
    }

    fun clearOnboardingSkipped() {
        prefs.edit().putBoolean("foul_b7671_has_skipped_onboarding", false).apply()
        _hasSkippedOnboarding.value = false
    }

    private val _channelStatsMap = MutableStateFlow<Map<String, ChannelEngagementStats>>(loadSavedChannelStats())
    val channelStatsMap: StateFlow<Map<String, ChannelEngagementStats>> = _channelStatsMap.asStateFlow()

    private val _contactMessages = MutableStateFlow<List<ContactMessageItem>>(loadSavedContactMessages())
    val contactMessages: StateFlow<List<ContactMessageItem>> = _contactMessages.asStateFlow()

    private val _allDeveloperMessages = MutableStateFlow<List<ContactMessageItem>>(loadSavedDeveloperMessages())
    val allDeveloperMessages: StateFlow<List<ContactMessageItem>> = _allDeveloperMessages.asStateFlow()

    private val _showChannelViewsAndLikes = MutableStateFlow(prefs.getBoolean("show_channel_views_likes", true))
    val showChannelViewsAndLikes: StateFlow<Boolean> = _showChannelViewsAndLikes.asStateFlow()

    private val _showAdsInApp = MutableStateFlow(prefs.getBoolean("show_ads_in_app", true))
    val showAdsInApp: StateFlow<Boolean> = _showAdsInApp.asStateFlow()

    private val _onlineUsersCount = MutableStateFlow(prefs.getInt("cached_online_users_count", 1).coerceAtLeast(1))
    val onlineUsersCount: StateFlow<Int> = _onlineUsersCount.asStateFlow()

    private val presenceSessionId: String by lazy {
        val saved = prefs.getString("presence_session_id", null)
        if (!saved.isNullOrBlank()) {
            saved
        } else {
            val fresh = "sess_${UUID.randomUUID().toString().replace("-", "").take(14)}"
            prefs.edit().putString("presence_session_id", fresh).apply()
            fresh
        }
    }

    private val PRIMARY_RTDB_BASE_URL = "https://foul-ddbb8-default-rtdb.firebaseio.com"

    /**
     * Real-time Online Presence Heartbeat & Active Users Counter backed EXCLUSIVELY by the 3rd Firebase Database (`foul-b7671`):
     * - Project ID: `foul-b7671`
     * - Project Number: `442910499881`
     * - Firebase URL: `https://foul-b7671-default-rtdb.firebaseio.com`
     * - Package Name: `com.foul.k`
     * - MobileSDK App ID: `1:442910499881:android:61149a358fe90d75d52838`
     *
     * Registers this device's active session timestamp in `online_presence/$presenceSessionId` on `foul-b7671` ONLY
     * (never on the two older databases) and counts all users/sessions currently active in the app within the live window (45s).
     */
    suspend fun heartbeatAndSyncOnlinePresence(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val activeWindowMs = 45_000L // Active within the last 45 seconds (heartbeat runs every 12s)
        val staleCleanupMs = 180_000L // Clean up stale sessions older than 3 minutes

        val userUid = _currentUser.value?.uid.orEmpty()
        val sessionJson = JSONObject().apply {
            put("sessionId", presenceSessionId)
            put("lastActiveAt", now)
            put("appVersion", com.example.data.model.AppVersionConfig.APP_CURRENT_VERSION)
            put("packageName", PRESENCE_PACKAGE_NAME)
            if (userUid.isNotBlank()) {
                put("uid", userUid)
            }
        }

        // 1. Push heartbeat EXCLUSIVELY to `foul-b7671` Realtime Database (`online_presence/$presenceSessionId`)
        val pushedPresenceRtdb = putToPresenceRtdb("online_presence/$presenceSessionId", sessionJson.toString())

        // 2. Also mirror heartbeat EXCLUSIVELY to `foul-b7671` Firestore (`online_presence/$presenceSessionId`)
        patchToPresenceFirestore(
            collection = "online_presence",
            docId = presenceSessionId,
            fieldsJson = JSONObject().apply {
                put("sessionId", JSONObject().put("stringValue", presenceSessionId))
                put("lastActiveAt", JSONObject().put("integerValue", now.toString()))
                put("appVersion", JSONObject().put("stringValue", com.example.data.model.AppVersionConfig.APP_CURRENT_VERSION))
                if (userUid.isNotBlank()) {
                    put("uid", JSONObject().put("stringValue", userUid))
                }
            }
        )

        // 3. Query all genuinely active sessions within the 45-second window from `foul-b7671` ONLY
        val activeSessions = HashSet<String>()
        activeSessions.add(presenceSessionId) // Current user/device is actively online right now

        runCatching {
            val rtdbPresence = getFromPresenceRtdb("online_presence")
            if (rtdbPresence != null) {
                val keys = rtdbPresence.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = rtdbPresence.optJSONObject(key)
                    val lastActive = obj?.optLong("lastActiveAt", 0L) ?: rtdbPresence.optLong(key, 0L)
                    if (lastActive > 0L) {
                        val age = now - lastActive
                        if (age in -60_000L..activeWindowMs) {
                            activeSessions.add(key)
                        } else if (age > staleCleanupMs && pushedPresenceRtdb) {
                            deleteFromPresenceRtdb("online_presence/$key")
                        }
                    }
                }
            }
        }

        runCatching {
            val fsDocs = getFromPresenceFirestore("online_presence")
            if (fsDocs != null) {
                for (i in 0 until fsDocs.length()) {
                    val doc = fsDocs.optJSONObject(i) ?: continue
                    val docName = doc.optString("name", "")
                    val docId = docName.substringAfterLast("/").ifBlank { "fs_$i" }
                    val fields = doc.optJSONObject("fields") ?: continue
                    val lastActiveObj = fields.optJSONObject("lastActiveAt")
                    val lastActive = lastActiveObj?.optString("integerValue")?.toLongOrNull()
                        ?: lastActiveObj?.optLong("integerValue", 0L)
                        ?: 0L
                    val age = now - lastActive
                    if (lastActive > 0L && age in -60_000L..activeWindowMs) {
                        activeSessions.add(docId)
                    }
                }
            }
        }

        val liveCount = activeSessions.size.coerceAtLeast(1)
        _onlineUsersCount.value = liveCount
        prefs.edit().putInt("cached_online_users_count", liveCount).apply()
        liveCount
    }

    private fun putToPresenceRtdb(path: String, jsonBody: String): Boolean {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val candidates = listOf(activePresenceRtdbBaseUrl) +
            PRESENCE_RTDB_CANDIDATE_URLS.filter { it != activePresenceRtdbBaseUrl }
        for (baseUrl in candidates) {
            try {
                val url = "$baseUrl/$path.json"
                val req = Request.Builder()
                    .url(url)
                    .put(jsonBody.toRequestBody(mediaType))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        activePresenceRtdbBaseUrl = baseUrl
                        return true
                    }
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun getFromPresenceRtdb(path: String): JSONObject? {
        val candidates = listOf(activePresenceRtdbBaseUrl) +
            PRESENCE_RTDB_CANDIDATE_URLS.filter { it != activePresenceRtdbBaseUrl }
        for (baseUrl in candidates) {
            try {
                val url = "$baseUrl/$path.json"
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        activePresenceRtdbBaseUrl = baseUrl
                        val body = resp.body?.string().orEmpty().trim()
                        if (body.isNotEmpty() && body != "null" && body.startsWith("{")) {
                            return JSONObject(body)
                        }
                        return null
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun deleteFromPresenceRtdb(path: String) {
        val candidates = listOf(activePresenceRtdbBaseUrl) +
            PRESENCE_RTDB_CANDIDATE_URLS.filter { it != activePresenceRtdbBaseUrl }
        for (baseUrl in candidates) {
            try {
                val url = "$baseUrl/$path.json"
                val req = Request.Builder().url(url).delete().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun patchToPresenceFirestore(
        collection: String,
        docId: String,
        fieldsJson: JSONObject
    ): Boolean {
        return try {
            val url = "$PRESENCE_FIRESTORE_BASE_URL/$collection/$docId?key=$PRESENCE_API_KEY"
            val payload = JSONObject().put("fields", fieldsJson)
            val req = Request.Builder()
                .url(url)
                .patch(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getFromPresenceFirestore(collection: String): JSONArray? {
        return try {
            val url = "$PRESENCE_FIRESTORE_BASE_URL/$collection?pageSize=200&key=$PRESENCE_API_KEY"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty().trim()
                if (body.isNotEmpty() && body.startsWith("{")) {
                    JSONObject(body).optJSONArray("documents")
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getFromPrimaryRtdb(path: String): JSONObject? {
        return try {
            val url = "$PRIMARY_RTDB_BASE_URL/$path.json"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty().trim()
                if (body.isNotEmpty() && body != "null" && body.startsWith("{")) {
                    JSONObject(body)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun putToPrimaryRtdb(path: String, jsonBody: String): Boolean {
        return try {
            val url = "$PRIMARY_RTDB_BASE_URL/$path.json"
            val req = Request.Builder()
                .url(url)
                .put(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Syncs the online App Control configuration (`showChannelViewsAndLikes` & `showAdsInApp`)
     * from Firebase (`foul-ddbb8` primary database + `foul-aeed9` secondary database).
     *
     * The online database is ALWAYS the authoritative source of truth for all users across all devices:
     * among all reachable online database nodes, the one with the newest `updatedAt` timestamp wins.
     */
    suspend fun syncAppControlSettings() = withContext(Dispatchers.IO) {
        var bestRemoteUpdatedAt = -1L
        var bestShowViews: Boolean? = null
        var bestShowAds: Boolean? = null

        fun considerRemoteCandidate(views: Boolean?, ads: Boolean?, remoteUpdatedAt: Long) {
            if (views == null && ads == null) return
            if (remoteUpdatedAt > bestRemoteUpdatedAt) {
                bestRemoteUpdatedAt = remoteUpdatedAt
                if (views != null) bestShowViews = views
                if (ads != null) bestShowAds = ads
            } else if (remoteUpdatedAt == bestRemoteUpdatedAt) {
                if (bestShowViews == null && views != null) bestShowViews = views
                if (bestShowAds == null && ads != null) bestShowAds = ads
            } else {
                // Even if an older node has a field that the newest node didn't specify, fill missing field
                if (bestShowViews == null && views != null) bestShowViews = views
                if (bestShowAds == null && ads != null) bestShowAds = ads
            }
        }

        fun extractJsonControl(json: JSONObject) {
            val ts = json.optLong("updatedAt", json.optLong("app_control_updated_at", 0L))
            val views = when {
                json.has("showChannelViewsAndLikes") -> json.optBoolean("showChannelViewsAndLikes", true)
                json.has("show_channel_views_likes") -> json.optBoolean("show_channel_views_likes", true)
                else -> null
            }
            val ads = when {
                json.has("showAdsInApp") -> json.optBoolean("showAdsInApp", true)
                json.has("show_ads_in_app") -> json.optBoolean("show_ads_in_app", true)
                else -> null
            }
            considerRemoteCandidate(views, ads, ts)
        }

        // 1. Check primary RTDB `app_control` (`foul-ddbb8`)
        runCatching {
            val primaryUrl = "https://foul-ddbb8-default-rtdb.firebaseio.com/app_control.json"
            val req = Request.Builder().url(primaryUrl).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().trim()
                    if (body.startsWith("{")) {
                        extractJsonControl(JSONObject(body))
                    }
                }
            }
        }

        // 2. Check primary RTDB `v4_app_config` (`foul-ddbb8`)
        runCatching {
            val primaryConfigUrl = "https://foul-ddbb8-default-rtdb.firebaseio.com/v4_app_config.json"
            val req = Request.Builder().url(primaryConfigUrl).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().trim()
                    if (body.startsWith("{")) {
                        extractJsonControl(JSONObject(body))
                    }
                }
            }
        }

        // 3. Check secondary RTDB (`foul-aeed9`)
        runCatching {
            val remoteObj = getFromSecondaryRtdb("app_control")
            if (remoteObj != null) {
                extractJsonControl(remoteObj)
            }
        }

        // 4. Check secondary Firestore (`foul-aeed9`)
        runCatching {
            val fsDocs = getFromSecondaryFirestore("app_control")
            if (fsDocs != null && fsDocs.length() > 0) {
                for (i in 0 until fsDocs.length()) {
                    val doc = fsDocs.optJSONObject(i) ?: continue
                    val fields = doc.optJSONObject("fields") ?: continue
                    val tsObj = fields.optJSONObject("updatedAt")
                    val ts = tsObj?.optString("integerValue")?.toLongOrNull()
                        ?: tsObj?.optLong("integerValue", 0L)
                        ?: 0L
                    val boolObj = fields.optJSONObject("showChannelViewsAndLikes")
                    val views = if (boolObj != null && boolObj.has("booleanValue")) {
                        boolObj.optBoolean("booleanValue", true)
                    } else null
                    val adsBoolObj = fields.optJSONObject("showAdsInApp")
                    val ads = if (adsBoolObj != null && adsBoolObj.has("booleanValue")) {
                        adsBoolObj.optBoolean("booleanValue", true)
                    } else null
                    considerRemoteCandidate(views, ads, ts)
                }
            }
        }

        val editor = prefs.edit()
        var changed = false
        bestShowViews?.let { enabled ->
            _showChannelViewsAndLikes.value = enabled
            editor.putBoolean("show_channel_views_likes", enabled)
            changed = true
        }
        bestShowAds?.let { adsEnabled ->
            _showAdsInApp.value = adsEnabled
            editor.putBoolean("show_ads_in_app", adsEnabled)
            changed = true
        }
        if (bestRemoteUpdatedAt >= 0L) {
            editor.putLong("app_control_updated_at", bestRemoteUpdatedAt)
            changed = true
        }
        if (changed) {
            editor.apply()
        }
    }

    /**
     * Immediately applies online App Control flags parsed from the primary Firebase payload (`foul-ddbb8`)
     * if they are at least as recent as the last synced timestamp.
     */
    fun applyRemoteAppControlFromPrimary(
        showViews: Boolean?,
        showAds: Boolean?,
        remoteUpdatedAt: Long
    ) {
        if (showViews == null && showAds == null) return
        val lastKnownTs = prefs.getLong("app_control_updated_at", 0L)
        if (remoteUpdatedAt >= lastKnownTs || lastKnownTs == 0L) {
            val editor = prefs.edit()
            if (showViews != null) {
                _showChannelViewsAndLikes.value = showViews
                editor.putBoolean("show_channel_views_likes", showViews)
            }
            if (showAds != null) {
                _showAdsInApp.value = showAds
                editor.putBoolean("show_ads_in_app", showAds)
            }
            if (remoteUpdatedAt > 0L) {
                editor.putLong("app_control_updated_at", remoteUpdatedAt)
            }
            editor.apply()
        }
    }

    /**
     * Updates the online App Control setting (`showChannelViewsAndLikes`) in Firebase so the Views & Likes
     * counters on all channels are shown or hidden online across all users.
     */
    suspend fun setShowChannelViewsAndLikesOnline(
        enabled: Boolean,
        now: Long = System.currentTimeMillis()
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            _showChannelViewsAndLikes.value = enabled
            prefs.edit()
                .putBoolean("show_channel_views_likes", enabled)
                .putLong("app_control_updated_at", now)
                .apply()

            val currentAdsEnabled = _showAdsInApp.value
            val payload = JSONObject().apply {
                put("showChannelViewsAndLikes", enabled)
                put("show_channel_views_likes", enabled)
                put("showAdsInApp", currentAdsEnabled)
                put("show_ads_in_app", currentAdsEnabled)
                put("updatedAt", now)
            }
            val savedSecondaryRtdb = putToSecondaryRtdb("app_control", payload.toString())
            val savedSecondaryFs = patchToSecondaryFirestore(
                collection = "app_control",
                docId = "global",
                fieldsJson = JSONObject().apply {
                    put("showChannelViewsAndLikes", JSONObject().put("booleanValue", enabled))
                    put("showAdsInApp", JSONObject().put("booleanValue", currentAdsEnabled))
                    put("updatedAt", JSONObject().put("integerValue", now.toString()))
                }
            )
            // Also try unauthenticated mirror to primary RTDB in case public rules are open
            var savedPrimaryPublic = false
            runCatching {
                val primaryUrl = "https://foul-ddbb8-default-rtdb.firebaseio.com/app_control.json"
                val req = Request.Builder()
                    .url(primaryUrl)
                    .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    savedPrimaryPublic = resp.isSuccessful
                }
            }
            savedSecondaryRtdb || savedSecondaryFs || savedPrimaryPublic
        }
    }

    /**
     * Updates the online App Control setting (`showAdsInApp`) in Firebase so all ads (Banner + Native)
     * are either completely hidden and disabled or enabled across the entire app for all users.
     */
    suspend fun setShowAdsInAppOnline(
        enabled: Boolean,
        now: Long = System.currentTimeMillis()
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            _showAdsInApp.value = enabled
            prefs.edit()
                .putBoolean("show_ads_in_app", enabled)
                .putLong("app_control_updated_at", now)
                .apply()

            val currentViewsEnabled = _showChannelViewsAndLikes.value
            val payload = JSONObject().apply {
                put("showChannelViewsAndLikes", currentViewsEnabled)
                put("show_channel_views_likes", currentViewsEnabled)
                put("showAdsInApp", enabled)
                put("show_ads_in_app", enabled)
                put("updatedAt", now)
            }
            val savedSecondaryRtdb = putToSecondaryRtdb("app_control", payload.toString())
            val savedSecondaryFs = patchToSecondaryFirestore(
                collection = "app_control",
                docId = "global",
                fieldsJson = JSONObject().apply {
                    put("showChannelViewsAndLikes", JSONObject().put("booleanValue", currentViewsEnabled))
                    put("showAdsInApp", JSONObject().put("booleanValue", enabled))
                    put("updatedAt", JSONObject().put("integerValue", now.toString()))
                }
            )
            // Also try unauthenticated mirror to primary RTDB in case public rules are open
            var savedPrimaryPublic = false
            runCatching {
                val primaryUrl = "https://foul-ddbb8-default-rtdb.firebaseio.com/app_control.json"
                val req = Request.Builder()
                    .url(primaryUrl)
                    .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    savedPrimaryPublic = resp.isSuccessful
                }
            }
            savedSecondaryRtdb || savedSecondaryFs || savedPrimaryPublic
        }
    }

    // --- 1. Channel Views & Likes Management (`foul-aeed9`) ---

    /**
     * Seeds initial channel stats from the channel list and syncs latest counts from `foul-aeed9`.
     */
    suspend fun syncAllChannelStats(channels: List<LiveChannel>) = withContext(Dispatchers.IO) {
        syncAppControlSettings()
        // First ensure every channel has an entry in our map
        val currentMap = _channelStatsMap.value.toMutableMap()
        val likedSet = getLikedChannelsSet()
        for (ch in channels) {
            val existing = currentMap[ch.id]
            if (existing == null) {
                currentMap[ch.id] = ChannelEngagementStats(
                    channelId = ch.id,
                    views = ch.views.coerceAtLeast(0),
                    likes = 0,
                    isLikedByMe = likedSet.contains(ch.id)
                )
            } else {
                currentMap[ch.id] = existing.copy(
                    views = maxOf(existing.views, ch.views),
                    isLikedByMe = likedSet.contains(ch.id)
                )
            }
        }
        _channelStatsMap.value = currentMap

        // Try fetching remote stats from `foul-aeed9` RTDB
        runCatching {
            val remoteJson = getFromSecondaryRtdb("channel_stats")
            if (remoteJson != null) {
                val merged = _channelStatsMap.value.toMutableMap()
                val keys = remoteJson.keys()
                while (keys.hasNext()) {
                    val chId = keys.next()
                    val obj = remoteJson.optJSONObject(chId) ?: continue
                    val remoteViews = obj.optInt("views", 0)
                    val remoteLikes = obj.optInt("likes", 0)
                    val local = merged[chId]
                    merged[chId] = ChannelEngagementStats(
                        channelId = chId,
                        views = maxOf(remoteViews, local?.views ?: 0),
                        likes = maxOf(remoteLikes, local?.likes ?: 0),
                        isLikedByMe = likedSet.contains(chId)
                    )
                }
                _channelStatsMap.value = merged
                saveChannelStatsLocally(merged)
            }
        }

        // Also check Firestore REST on `foul-aeed9` if RTDB had no data
        runCatching {
            val fsDocs = getFromSecondaryFirestore("channel_stats")
            if (fsDocs != null && fsDocs.length() > 0) {
                val merged = _channelStatsMap.value.toMutableMap()
                for (i in 0 until fsDocs.length()) {
                    val doc = fsDocs.optJSONObject(i) ?: continue
                    val namePath = doc.optString("name")
                    val chId = namePath.substringAfterLast('/')
                    val fields = doc.optJSONObject("fields") ?: continue
                    val rViews = fields.optJSONObject("views")?.optString("integerValue")?.toIntOrNull() ?: 0
                    val rLikes = fields.optJSONObject("likes")?.optString("integerValue")?.toIntOrNull() ?: 0
                    val local = merged[chId]
                    merged[chId] = ChannelEngagementStats(
                        channelId = chId,
                        views = maxOf(rViews, local?.views ?: 0),
                        likes = maxOf(rLikes, local?.likes ?: 0),
                        isLikedByMe = likedSet.contains(chId)
                    )
                }
                _channelStatsMap.value = merged
                saveChannelStatsLocally(merged)
            }
        }
    }

    /**
     * Increments the view counter for a specific channel by +1 when opened in the player,
     * and persists it to `foul-aeed9`.
     */
    suspend fun incrementChannelView(channelId: String, fallbackInitialViews: Int = 0) = withContext(Dispatchers.IO) {
        if (channelId.isBlank()) return@withContext
        val likedSet = getLikedChannelsSet()
        var updatedStats: ChannelEngagementStats? = null

        _channelStatsMap.update { current ->
            val mutable = current.toMutableMap()
            val prev = mutable[channelId] ?: ChannelEngagementStats(
                channelId = channelId,
                views = fallbackInitialViews,
                likes = 0,
                isLikedByMe = likedSet.contains(channelId)
            )
            val next = prev.copy(views = prev.views + 1)
            mutable[channelId] = next
            updatedStats = next
            mutable
        }

        saveChannelStatsLocally(_channelStatsMap.value)
        val statsToSync = updatedStats ?: return@withContext
        pushChannelStatsToSecondaryDb(statsToSync)
    }

    /**
     * Toggles the Like button for a specific channel separately and syncs with `foul-aeed9`.
     */
    suspend fun toggleChannelLike(channelId: String) = withContext(Dispatchers.IO) {
        if (channelId.isBlank()) return@withContext
        val likedSet = getLikedChannelsSet().toMutableSet()
        val wasLiked = likedSet.contains(channelId)
        val nowLiked = !wasLiked
        if (nowLiked) {
            likedSet.add(channelId)
        } else {
            likedSet.remove(channelId)
        }
        prefs.edit().putStringSet("liked_channel_ids", likedSet).apply()

        var updatedStats: ChannelEngagementStats? = null
        _channelStatsMap.update { current ->
            val mutable = current.toMutableMap()
            val prev = mutable[channelId] ?: ChannelEngagementStats(channelId = channelId)
            val nextLikes = if (nowLiked) prev.likes + 1 else (prev.likes - 1).coerceAtLeast(0)
            val next = prev.copy(likes = nextLikes, isLikedByMe = nowLiked)
            mutable[channelId] = next
            updatedStats = next
            mutable
        }

        saveChannelStatsLocally(_channelStatsMap.value)
        val statsToSync = updatedStats ?: return@withContext
        pushChannelStatsToSecondaryDb(statsToSync)
    }

    private fun pushChannelStatsToSecondaryDb(stats: ChannelEngagementStats) {
        val bodyJson = JSONObject().apply {
            put("channelId", stats.channelId)
            put("views", stats.views)
            put("likes", stats.likes)
            put("updatedAt", System.currentTimeMillis())
        }
        // 1. Try Secondary RTDB
        val rtdbSuccess = putToSecondaryRtdb("channel_stats/${stats.channelId}", bodyJson.toString())
        // 2. Also mirror to Secondary Firestore REST (`foul-aeed9`)
        if (!rtdbSuccess) {
            patchToSecondaryFirestore(
                collection = "channel_stats",
                docId = stats.channelId,
                fieldsJson = JSONObject().apply {
                    put("views", JSONObject().put("integerValue", stats.views.toString()))
                    put("likes", JSONObject().put("integerValue", stats.likes.toString()))
                }
            )
        }
    }

    // --- 2. User Account Registration, Login, Google Gmail Sign-In & Profile Completion (`foul-b7671`) ---

    private fun hashPassword(rawPassword: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(("foul_b7671_salt::$rawPassword").toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            rawPassword
        }
    }

    private fun sanitizeEmailKey(email: String): String {
        return email.trim().lowercase()
            .replace(".", "_dot_")
            .replace("@", "_at_")
            .replace(Regex("[^a-z0-9_\\-]"), "_")
    }

    private fun sanitizeUsernameKey(username: String): String {
        return username.trim().removePrefix("@").lowercase()
            .replace(Regex("[^a-z0-9_\\-\u0600-\u06FF]"), "_")
    }

    /**
     * Registers a new user account on the latest working database `foul-b7671`
     * (`https://foul-b7671-default-rtdb.firebaseio.com`) with Personal Name, Username, Email, and Password.
     */
    suspend fun registerUserAccount(
        email: String,
        password: String,
        username: String,
        avatarId: String,
        personalName: String = ""
    ): Result<SocialUserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanEmail = email.trim().lowercase()
            val cleanPass = password.trim()
            val cleanUser = username.trim().removePrefix("@").trim()
            val cleanPersonalName = personalName.trim().ifBlank { cleanUser }
            val cleanAvatar = avatarId.ifBlank { APP_AVATARS.first().id }

            if (cleanPersonalName.isBlank()) {
                throw IllegalArgumentException("يرجى إدخال اسمك الشخصي")
            }
            if (cleanUser.isBlank() || cleanUser.length < 3) {
                throw IllegalArgumentException("يرجى إدخال اسم مستخدم صالح (3 أحرف على الأقل)")
            }
            if (cleanEmail.isBlank() || !cleanEmail.contains("@") || !cleanEmail.contains(".")) {
                throw IllegalArgumentException("يرجى إدخال بريد إلكتروني صحيح")
            }
            if (cleanPass.length < 6) {
                throw IllegalArgumentException("كلمة المرور يجب أن تكون 6 أحرف أو أرقام على الأقل")
            }

            // 1. Check if email or username is already registered in `foul-b7671` RTDB
            val emailKey = sanitizeEmailKey(cleanEmail)
            val usernameKey = sanitizeUsernameKey(cleanUser)
            val existingUsersObj = getFromPresenceRtdb("users")
            if (existingUsersObj != null) {
                val keys = existingUsersObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val uObj = existingUsersObj.optJSONObject(k) ?: continue
                    val uEmail = uObj.optString("email").trim().lowercase()
                    val uName = uObj.optString("username").trim().removePrefix("@").lowercase()
                    if (uEmail == cleanEmail) {
                        throw IllegalStateException("هذا البريد الإلكتروني مسجل مسبقاً، يرجى تسجيل الدخول به")
                    }
                    if (uName.isNotBlank() && uName == cleanUser.lowercase()) {
                        throw IllegalStateException("اسم المستخدم هذا مستخدم بالفعل، يرجى اختيار اسم مستخدم آخر")
                    }
                }
            }

            // 2. Try IdentityToolkit on `foul-b7671` (with automatic direct RTDB UID generation if IdentityToolkit email provider is not enabled)
            var uid = ""
            var idToken = ""
            var refreshToken = ""

            runCatching {
                val signUpUrl = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$PRESENCE_API_KEY"
                val payload = JSONObject().apply {
                    put("email", cleanEmail)
                    put("password", cleanPass)
                    put("returnSecureToken", true)
                }
                val req = Request.Builder()
                    .url(signUpUrl)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val respStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val json = JSONObject(respStr)
                        uid = json.optString("localId")
                        idToken = json.optString("idToken")
                        refreshToken = json.optString("refreshToken")
                    } else {
                        val errCode = runCatching {
                            JSONObject(respStr).optJSONObject("error")?.optString("message").orEmpty()
                        }.getOrDefault("")
                        if (errCode.contains("EMAIL_EXISTS")) {
                            throw IllegalStateException("هذا البريد الإلكتروني مسجل مسبقاً، يمكنك تسجيل الدخول به")
                        }
                    }
                }
            }.onFailure { err ->
                if (err is IllegalStateException) throw err
            }

            if (uid.isBlank()) {
                uid = "usr_${emailKey.take(28)}_${UUID.randomUUID().toString().replace("-", "").take(8)}"
                idToken = "token_$uid"
                refreshToken = "refresh_$uid"
            }

            userIdToken = idToken
            userRefreshToken = refreshToken

            if (idToken.isNotBlank() && !idToken.startsWith("token_")) {
                updateAuthProfileOnServer(idToken, cleanUser, cleanAvatar)
            }

            val profile = SocialUserProfile(
                uid = uid,
                email = cleanEmail,
                username = cleanUser,
                avatarId = cleanAvatar,
                personalName = cleanPersonalName,
                authProvider = "email",
                isProfileComplete = true
            )

            val now = System.currentTimeMillis()
            val passHash = hashPassword(cleanPass)
            val userJson = JSONObject().apply {
                put("uid", uid)
                put("email", cleanEmail)
                put("username", cleanUser)
                put("personalName", cleanPersonalName)
                put("avatarId", cleanAvatar)
                put("authProvider", "email")
                put("isProfileComplete", true)
                put("passwordHash", passHash)
                put("createdAt", now)
                put("updatedAt", now)
            }

            // Save strictly to the working `foul-b7671` Realtime Database (`https://foul-b7671-default-rtdb.firebaseio.com`)
            val savedRtdb = putToPresenceRtdb("users/$uid", userJson.toString())
            putToPresenceRtdb("users_by_email/$emailKey", JSONObject().apply {
                put("uid", uid)
                put("email", cleanEmail)
                put("passwordHash", passHash)
            }.toString())
            putToPresenceRtdb("users_by_username/$usernameKey", JSONObject().apply {
                put("uid", uid)
                put("username", cleanUser)
            }.toString())

            if (!savedRtdb) {
                patchToPresenceFirestore(
                    collection = "users",
                    docId = uid,
                    fieldsJson = JSONObject().apply {
                        put("uid", JSONObject().put("stringValue", uid))
                        put("email", JSONObject().put("stringValue", cleanEmail))
                        put("username", JSONObject().put("stringValue", cleanUser))
                        put("personalName", JSONObject().put("stringValue", cleanPersonalName))
                        put("avatarId", JSONObject().put("stringValue", cleanAvatar))
                        put("authProvider", JSONObject().put("stringValue", "email"))
                        put("isProfileComplete", JSONObject().put("booleanValue", true))
                        put("passwordHash", JSONObject().put("stringValue", passHash))
                    }
                )
            }

            saveUserProfileLocally(profile, idToken, refreshToken)
            rememberDeviceGoogleAccount(context, cleanEmail, cleanPersonalName)
            clearOnboardingSkipped()
            _currentUser.value = profile
            _globalAuthenticatedUserFlow.value = profile

            ensureInitialBotWelcomeMessage(profile)
            fetchContactMessages()

            profile
        }
    }

    /**
     * Logs in an existing user on the working database `foul-b7671`
     * (`https://foul-b7671-default-rtdb.firebaseio.com`) using Email & Password.
     */
    suspend fun loginUserAccount(
        email: String,
        password: String
    ): Result<SocialUserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanEmail = email.trim().lowercase()
            val cleanPass = password.trim()
            if (cleanEmail.isBlank() || cleanPass.isBlank()) {
                throw IllegalArgumentException("يرجى إدخال البريد الإلكتروني وكلمة المرور")
            }

            val passHash = hashPassword(cleanPass)
            var matchedUid = ""
            var matchedUsername = ""
            var matchedPersonalName = ""
            var matchedAvatarId = APP_AVATARS.first().id
            var idToken = ""
            var refreshToken = ""

            // 1. Check `foul-b7671` Realtime Database `users` & `users_by_email`
            runCatching {
                val emailKey = sanitizeEmailKey(cleanEmail)
                val emailIndex = getFromPresenceRtdb("users_by_email/$emailKey")
                if (emailIndex != null) {
                    val candidateUid = emailIndex.optString("uid")
                    val storedHash = emailIndex.optString("passwordHash")
                    if (candidateUid.isNotBlank() && (storedHash == passHash || storedHash == cleanPass)) {
                        val userNode = getFromPresenceRtdb("users/$candidateUid")
                        if (userNode != null) {
                            matchedUid = candidateUid
                            matchedUsername = userNode.optString("username")
                            matchedPersonalName = userNode.optString("personalName")
                            matchedAvatarId = userNode.optString("avatarId").ifBlank { APP_AVATARS.first().id }
                        }
                    } else if (candidateUid.isNotBlank() && storedHash.isNotBlank() && storedHash != passHash) {
                        throw IllegalStateException("البريد الإلكتروني أو كلمة المرور غير صحيحة")
                    }
                }

                if (matchedUid.isBlank()) {
                    val allUsers = getFromPresenceRtdb("users")
                    if (allUsers != null) {
                        val keys = allUsers.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val obj = allUsers.optJSONObject(k) ?: continue
                            if (obj.optString("email").trim().lowercase() == cleanEmail) {
                                val storedHash = obj.optString("passwordHash")
                                if (storedHash.isBlank() || storedHash == passHash || storedHash == cleanPass) {
                                    matchedUid = obj.optString("uid").ifBlank { k }
                                    matchedUsername = obj.optString("username")
                                    matchedPersonalName = obj.optString("personalName")
                                    matchedAvatarId = obj.optString("avatarId").ifBlank { APP_AVATARS.first().id }
                                    break
                                } else {
                                    throw IllegalStateException("البريد الإلكتروني أو كلمة المرور غير صحيحة")
                                }
                            }
                        }
                    }
                }
            }.onFailure { err ->
                if (err is IllegalStateException) throw err
            }

            // 2. If not matched via RTDB hash, also try IdentityToolkit on `foul-b7671`
            if (matchedUid.isBlank()) {
                val signInUrl = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$PRESENCE_API_KEY"
                val payload = JSONObject().apply {
                    put("email", cleanEmail)
                    put("password", cleanPass)
                    put("returnSecureToken", true)
                }
                val req = Request.Builder()
                    .url(signInUrl)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(req).execute().use { resp ->
                    val respStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("البريد الإلكتروني أو كلمة المرور غير صحيحة، أو الحساب غير مسجل")
                    }
                    val json = JSONObject(respStr)
                    matchedUid = json.optString("localId")
                    idToken = json.optString("idToken")
                    refreshToken = json.optString("refreshToken")
                    matchedUsername = json.optString("displayName")
                    matchedAvatarId = json.optString("photoUrl").ifBlank { APP_AVATARS.first().id }
                }

                // Fetch profile details from `foul-b7671`
                runCatching {
                    val userNode = getFromPresenceRtdb("users/$matchedUid")
                    if (userNode != null) {
                        if (matchedUsername.isBlank()) matchedUsername = userNode.optString("username")
                        if (matchedPersonalName.isBlank()) matchedPersonalName = userNode.optString("personalName")
                        val av = userNode.optString("avatarId")
                        if (av.isNotBlank()) matchedAvatarId = av
                    }
                }
            }

            if (matchedUid.isBlank()) {
                throw IllegalStateException("البريد الإلكتروني أو كلمة المرور غير صحيحة")
            }

            if (matchedUsername.isBlank()) {
                matchedUsername = cleanEmail.substringBefore("@").ifBlank { "foul_user" }
            }
            if (matchedPersonalName.isBlank()) {
                matchedPersonalName = matchedUsername
            }
            if (APP_AVATARS.none { it.id == matchedAvatarId }) {
                matchedAvatarId = APP_AVATARS.first().id
            }

            if (idToken.isBlank()) idToken = "token_$matchedUid"
            if (refreshToken.isBlank()) refreshToken = "refresh_$matchedUid"
            userIdToken = idToken
            userRefreshToken = refreshToken

            val profile = SocialUserProfile(
                uid = matchedUid,
                email = cleanEmail,
                username = matchedUsername,
                avatarId = matchedAvatarId,
                personalName = matchedPersonalName,
                authProvider = "email",
                isProfileComplete = true
            )

            saveUserProfileLocally(profile, idToken, refreshToken)
            rememberDeviceGoogleAccount(context, cleanEmail, matchedPersonalName)
            clearOnboardingSkipped()
            _currentUser.value = profile
            _globalAuthenticatedUserFlow.value = profile

            ensureInitialBotWelcomeMessage(profile)
            fetchContactMessages()

            profile
        }
    }

    /**
     * Authenticates with Google Gmail on `foul-b7671` (`https://foul-b7671-default-rtdb.firebaseio.com`).
     * After Google Gmail login, if the user has not yet set their Personal Name (`personalName`) and
     * Username (`username`) on `foul-b7671`, returns a profile with `isProfileComplete = false`
     * so the app immediately prompts them to enter their Personal Name and Username before starting!
     */
    suspend fun signInWithGoogleGmail(
        googleEmail: String,
        googleSuggestedName: String = ""
    ): Result<SocialUserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanEmail = googleEmail.trim().lowercase()
            if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
                throw IllegalArgumentException("يرجى اختيار أو إدخال بريد Google Gmail صحيح")
            }

            val emailKey = sanitizeEmailKey(cleanEmail)
            var uid = "google_$emailKey"
            var existingUsername = ""
            var existingPersonalName = ""
            var existingAvatarId = APP_AVATARS.first().id
            var isAlreadyComplete = false

            // Check if this Google Gmail user already exists in `foul-b7671`
            runCatching {
                val directNode = getFromPresenceRtdb("users/$uid")
                val targetNode = directNode ?: run {
                    val idx = getFromPresenceRtdb("users_by_email/$emailKey")
                    val mappedUid = idx?.optString("uid").orEmpty()
                    if (mappedUid.isNotBlank()) {
                        uid = mappedUid
                        getFromPresenceRtdb("users/$mappedUid")
                    } else null
                }
                if (targetNode != null) {
                    existingUsername = targetNode.optString("username").trim()
                    existingPersonalName = targetNode.optString("personalName").trim()
                    existingAvatarId = targetNode.optString("avatarId").ifBlank { APP_AVATARS.first().id }
                    isAlreadyComplete = targetNode.optBoolean("isProfileComplete", false) &&
                        existingUsername.isNotBlank() &&
                        existingPersonalName.isNotBlank()
                }
            }

            val profile = SocialUserProfile(
                uid = uid,
                email = cleanEmail,
                username = if (isAlreadyComplete) existingUsername else "",
                avatarId = existingAvatarId,
                personalName = if (isAlreadyComplete) existingPersonalName else googleSuggestedName.trim(),
                authProvider = "google",
                isProfileComplete = isAlreadyComplete
            )

            // Save initial Google session node in `foul-b7671`
            val now = System.currentTimeMillis()
            val googleJson = JSONObject().apply {
                put("uid", uid)
                put("email", cleanEmail)
                put("username", profile.username)
                put("personalName", profile.personalName)
                put("avatarId", profile.avatarId)
                put("authProvider", "google")
                put("isProfileComplete", isAlreadyComplete)
                put("updatedAt", now)
            }
            putToPresenceRtdb("users/$uid", googleJson.toString())
            putToPresenceRtdb("users_by_email/$emailKey", JSONObject().apply {
                put("uid", uid)
                put("email", cleanEmail)
                put("authProvider", "google")
            }.toString())

            saveUserProfileLocally(profile, "google_token_$uid", "google_refresh_$uid")
            rememberDeviceGoogleAccount(context, cleanEmail, profile.personalName)
            if (isAlreadyComplete) {
                clearOnboardingSkipped()
            }
            _currentUser.value = profile
            _globalAuthenticatedUserFlow.value = profile

            profile
        }
    }

    /**
     * Completes the user's profile after Google Gmail Sign-In by saving their mandatory
     * Personal Name (`personalName`) and Username (`username`) to `foul-b7671`.
     */
    suspend fun completeGoogleUserProfile(
        personalName: String,
        username: String,
        avatarId: String = ""
    ): Result<SocialUserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val current = _currentUser.value
                ?: throw IllegalStateException("يرجى تسجيل الدخول بحساب Google أولاً")
            val cleanPersonalName = personalName.trim()
            val cleanUsername = username.trim().removePrefix("@").trim()
            val cleanAvatar = avatarId.ifBlank { current.avatarId.ifBlank { APP_AVATARS.first().id } }

            if (cleanPersonalName.isBlank()) {
                throw IllegalArgumentException("يرجى إدخال اسمك الشخصي")
            }
            if (cleanUsername.isBlank() || cleanUsername.length < 3) {
                throw IllegalArgumentException("يرجى إدخال اسم المستخدم الخاص بك (3 أحرف على الأقل)")
            }

            // Ensure username is not taken by another user in `foul-b7671`
            val usernameKey = sanitizeUsernameKey(cleanUsername)
            val existingByUsername = getFromPresenceRtdb("users_by_username/$usernameKey")
            if (existingByUsername != null) {
                val ownerUid = existingByUsername.optString("uid")
                if (ownerUid.isNotBlank() && ownerUid != current.uid) {
                    throw IllegalStateException("اسم المستخدم هذا محجوز لشخص آخر، اختر اسماً مختلفاً")
                }
            }

            val completedProfile = current.copy(
                personalName = cleanPersonalName,
                username = cleanUsername,
                avatarId = cleanAvatar,
                isProfileComplete = true
            )

            val now = System.currentTimeMillis()
            val userJson = JSONObject().apply {
                put("uid", completedProfile.uid)
                put("email", completedProfile.email)
                put("username", completedProfile.username)
                put("personalName", completedProfile.personalName)
                put("avatarId", completedProfile.avatarId)
                put("authProvider", completedProfile.authProvider)
                put("isProfileComplete", true)
                put("updatedAt", now)
            }

            putToPresenceRtdb("users/${completedProfile.uid}", userJson.toString())
            putToPresenceRtdb("users_by_username/$usernameKey", JSONObject().apply {
                put("uid", completedProfile.uid)
                put("username", completedProfile.username)
            }.toString())

            saveUserProfileLocally(
                completedProfile,
                "google_token_${completedProfile.uid}",
                "google_refresh_${completedProfile.uid}"
            )
            rememberDeviceGoogleAccount(context, completedProfile.email, completedProfile.personalName)
            clearOnboardingSkipped()
            _currentUser.value = completedProfile
            _globalAuthenticatedUserFlow.value = completedProfile

            ensureInitialBotWelcomeMessage(completedProfile)
            fetchContactMessages()

            completedProfile
        }
    }

    fun logoutUserAccount() {
        userIdToken = ""
        userRefreshToken = ""
        prefs.edit()
            .remove("user_uid")
            .remove("user_email")
            .remove("user_username")
            .remove("user_personal_name")
            .remove("user_auth_provider")
            .remove("user_profile_complete")
            .remove("user_avatar_id")
            .remove("user_id_token")
            .remove("user_refresh_token")
            .putBoolean("foul_b7671_has_skipped_onboarding", false)
            .apply()
        _hasSkippedOnboarding.value = false
        _currentUser.value = null
        _globalAuthenticatedUserFlow.value = null
    }

    private fun updateAuthProfileOnServer(idToken: String, username: String, avatarId: String) {
        runCatching {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:update?key=$PRESENCE_API_KEY"
            val payload = JSONObject().apply {
                put("idToken", idToken)
                put("displayName", username)
                put("photoUrl", avatarId)
                put("returnSecureToken", false)
            }
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().close()
        }
    }

    // --- 3. Contact Us (`تواصل معنا`) & Developer User Contact (`تواصل المستخدمين`) in `foul-aeed9` ---

    private fun parseMessageFromJson(m: JSONObject, fallbackKey: String, defaultTargetUid: String = ""): ContactMessageItem {
        val parsedSenderUid = m.optString("senderUid")
        val parsedTargetUid = m.optString("targetUid").ifBlank {
            defaultTargetUid.ifBlank { parsedSenderUid }
        }
        return ContactMessageItem(
            id = m.optString("id", fallbackKey),
            senderUid = parsedSenderUid,
            senderName = m.optString("senderName"),
            senderEmail = m.optString("senderEmail"),
            senderAvatarId = m.optString("senderAvatarId", APP_AVATARS.first().id),
            text = m.optString("text"),
            timestamp = m.optLong("timestamp", System.currentTimeMillis()),
            isFromBotOrAdmin = m.optBoolean("isFromBotOrAdmin", false),
            targetUid = parsedTargetUid,
            replyToMessageId = m.optString("replyToMessageId", ""),
            replyToSenderName = m.optString("replyToSenderName", ""),
            replyToText = m.optString("replyToText", "")
        )
    }

    private fun messageToJson(msg: ContactMessageItem): JSONObject {
        return JSONObject().apply {
            put("id", msg.id)
            put("senderUid", msg.senderUid)
            put("senderName", msg.senderName)
            put("senderEmail", msg.senderEmail)
            put("senderAvatarId", msg.senderAvatarId)
            put("text", msg.text)
            put("timestamp", msg.timestamp)
            put("isFromBotOrAdmin", msg.isFromBotOrAdmin)
            put("targetUid", msg.targetUid.ifBlank { msg.senderUid })
            put("replyToMessageId", msg.replyToMessageId)
            put("replyToSenderName", msg.replyToSenderName)
            put("replyToText", msg.replyToText)
        }
    }

    private fun pushMessageToSecondaryDb(targetUserUid: String, msg: ContactMessageItem) {
        val cleanTargetUid = targetUserUid.ifBlank { msg.targetUid.ifBlank { msg.senderUid } }
        val normalizedMsg = msg.copy(targetUid = cleanTargetUid)
        val msgJsonStr = messageToJson(normalizedMsg).toString()

        // 1. Write to the user's private thread node so ONLY that user sees their messages & developer replies to them
        val savedUserNode = putToSecondaryRtdb("contact_messages/$cleanTargetUid/${normalizedMsg.id}", msgJsonStr)
        // 2. Also write to the developer's unified index node so the Developer Control Panel receives all messages rapidly
        putToSecondaryRtdb("all_contact_messages/${normalizedMsg.id}", msgJsonStr)

        if (!savedUserNode) {
            patchToSecondaryFirestore(
                collection = "contact_messages",
                docId = "${cleanTargetUid}_${normalizedMsg.id}",
                fieldsJson = JSONObject().apply {
                    put("id", JSONObject().put("stringValue", normalizedMsg.id))
                    put("senderUid", JSONObject().put("stringValue", normalizedMsg.senderUid))
                    put("senderName", JSONObject().put("stringValue", normalizedMsg.senderName))
                    put("senderEmail", JSONObject().put("stringValue", normalizedMsg.senderEmail))
                    put("senderAvatarId", JSONObject().put("stringValue", normalizedMsg.senderAvatarId))
                    put("text", JSONObject().put("stringValue", normalizedMsg.text))
                    put("timestamp", JSONObject().put("integerValue", normalizedMsg.timestamp.toString()))
                    put("isFromBotOrAdmin", JSONObject().put("booleanValue", normalizedMsg.isFromBotOrAdmin))
                    put("targetUid", JSONObject().put("stringValue", cleanTargetUid))
                    put("replyToMessageId", JSONObject().put("stringValue", normalizedMsg.replyToMessageId))
                    put("replyToSenderName", JSONObject().put("stringValue", normalizedMsg.replyToSenderName))
                    put("replyToText", JSONObject().put("stringValue", normalizedMsg.replyToText))
                }
            )
        }
    }

    /**
     * Fetches the logged-in user's private conversation (`contact_messages/${user.uid}`).
     * Strictly filters so developer replies to OTHER users never appear to this user
     * ("ولاكن لا تظهر ردود المطور للكل فقط للشخص إلي يسحب بشكل سحب ويرد عليه تظهر ذلك").
     */
    suspend fun fetchContactMessages() = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext
        ensureInitialBotWelcomeMessage(user)

        val byId = mutableMapOf<String, ContactMessageItem>()
        // Keep existing local messages that belong strictly to this user
        _contactMessages.value
            .filter { it.targetUid == user.uid || it.senderUid == user.uid || it.id == "welcome_${user.uid}" }
            .forEach { byId[it.id] = it }

        // 1. Fetch from user's private node on `foul-aeed9` RTDB
        runCatching {
            val remoteObj = getFromSecondaryRtdb("contact_messages/${user.uid}")
            if (remoteObj != null) {
                val keys = remoteObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val m = remoteObj.optJSONObject(key) ?: continue
                    val parsed = parseMessageFromJson(m, key, defaultTargetUid = user.uid)
                    if (parsed.text.isNotBlank() && (parsed.targetUid == user.uid || parsed.senderUid == user.uid)) {
                        byId[parsed.id] = parsed
                    }
                }
            }
        }

        // 2. Also check Firestore fallback if needed, strictly filtered by `targetUid == user.uid`
        if (byId.size <= 1) {
            runCatching {
                val fsDocs = getFromSecondaryFirestore("contact_messages")
                if (fsDocs != null) {
                    for (i in 0 until fsDocs.length()) {
                        val doc = fsDocs.optJSONObject(i) ?: continue
                        val fields = doc.optJSONObject("fields") ?: continue
                        val tUid = fields.optJSONObject("targetUid")?.optString("stringValue").orEmpty()
                        val sUid = fields.optJSONObject("senderUid")?.optString("stringValue").orEmpty()
                        if (tUid == user.uid || sUid == user.uid) {
                            val id = fields.optJSONObject("id")?.optString("stringValue")
                                .orEmpty()
                                .ifBlank { doc.optString("name").substringAfterLast('/') }
                            val item = ContactMessageItem(
                                id = id,
                                senderUid = sUid,
                                senderName = fields.optJSONObject("senderName")?.optString("stringValue").orEmpty(),
                                senderEmail = fields.optJSONObject("senderEmail")?.optString("stringValue").orEmpty(),
                                senderAvatarId = fields.optJSONObject("senderAvatarId")?.optString("stringValue").orEmpty().ifBlank { user.avatarId },
                                text = fields.optJSONObject("text")?.optString("stringValue").orEmpty(),
                                timestamp = fields.optJSONObject("timestamp")?.optString("integerValue")?.toLongOrNull() ?: 0L,
                                isFromBotOrAdmin = fields.optJSONObject("isFromBotOrAdmin")?.optBoolean("booleanValue") ?: false,
                                targetUid = tUid.ifBlank { user.uid },
                                replyToMessageId = fields.optJSONObject("replyToMessageId")?.optString("stringValue").orEmpty(),
                                replyToSenderName = fields.optJSONObject("replyToSenderName")?.optString("stringValue").orEmpty(),
                                replyToText = fields.optJSONObject("replyToText")?.optString("stringValue").orEmpty()
                            )
                            if (item.text.isNotBlank()) {
                                byId[item.id] = item
                            }
                        }
                    }
                }
            }
        }

        val sorted = byId.values.sortedBy { it.timestamp }
        _contactMessages.value = sorted
        saveContactMessagesLocally(sorted)
    }

    /**
     * Sends a message from the logged-in user (optionally swiped as a reply to an existing message)
     * and delivers it to both the user's thread and the Developer Control Panel's unified stream.
     */
    suspend fun sendContactMessage(
        messageText: String,
        replyTo: ContactMessageItem? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val user = _currentUser.value ?: throw IllegalStateException("يرجى تسجيل الدخول أولاً")
            val cleanText = messageText.trim()
            if (cleanText.isBlank()) return@runCatching

            val now = System.currentTimeMillis()
            val userMsg = ContactMessageItem(
                id = "msg_${now}_${UUID.randomUUID().toString().take(8)}",
                senderUid = user.uid,
                senderName = user.username,
                senderEmail = user.email,
                senderAvatarId = user.avatarId,
                text = cleanText,
                timestamp = now,
                isFromBotOrAdmin = false,
                targetUid = user.uid,
                replyToMessageId = replyTo?.id.orEmpty(),
                replyToSenderName = replyTo?.senderName.orEmpty(),
                replyToText = replyTo?.text.orEmpty()
            )

            val updatedUserList = (_contactMessages.value.filter { it.id != userMsg.id } + userMsg)
                .sortedBy { it.timestamp }
            _contactMessages.value = updatedUserList
            saveContactMessagesLocally(updatedUserList)

            // Also update local developer feed immediately for instant sync
            val updatedDevList = (_allDeveloperMessages.value.filter { it.id != userMsg.id } + userMsg)
                .sortedBy { it.timestamp }
            _allDeveloperMessages.value = updatedDevList
            saveDeveloperMessagesLocally(updatedDevList)

            // Sync to `foul-aeed9` database
            pushMessageToSecondaryDb(targetUserUid = user.uid, msg = userMsg)
        }
    }

    /**
     * Fetches ALL messages from ALL users across the app for the Developer Control Panel
     * (`قسم جديد تواصل المستخدمين - تظهر جميع الرسائل في مكان واحد`).
     */
    suspend fun fetchAllDeveloperMessages() = withContext(Dispatchers.IO) {
        val byId = mutableMapOf<String, ContactMessageItem>()
        _allDeveloperMessages.value
            .filter { !it.id.startsWith("welcome_") && it.senderUid != "foul_tv_bot" }
            .forEach { byId[it.id] = it }

        // 1. Read the root `contact_messages` tree (`{ "<uid>": { "<msgId>": { ... } } }`)
        runCatching {
            val rootObj = getFromSecondaryRtdb("contact_messages")
            if (rootObj != null) {
                val userKeys = rootObj.keys()
                while (userKeys.hasNext()) {
                    val uidKey = userKeys.next()
                    val userNode = rootObj.optJSONObject(uidKey) ?: continue
                    // Check if userNode is directly a message or a map of messages
                    if (userNode.has("text") && userNode.has("senderUid")) {
                        val msg = parseMessageFromJson(userNode, uidKey, defaultTargetUid = uidKey)
                        if (msg.text.isNotBlank() && !msg.id.startsWith("welcome_") && msg.senderUid != "foul_tv_bot") {
                            byId[msg.id] = msg
                        }
                    } else {
                        val msgKeys = userNode.keys()
                        while (msgKeys.hasNext()) {
                            val msgIdKey = msgKeys.next()
                            val msgObj = userNode.optJSONObject(msgIdKey) ?: continue
                            val msg = parseMessageFromJson(msgObj, msgIdKey, defaultTargetUid = uidKey)
                            if (msg.text.isNotBlank() && !msg.id.startsWith("welcome_") && msg.senderUid != "foul_tv_bot") {
                                byId[msg.id] = msg
                            }
                        }
                    }
                }
            }
        }

        // 2. Also read `all_contact_messages` flat index node on `foul-aeed9`
        runCatching {
            val flatObj = getFromSecondaryRtdb("all_contact_messages")
            if (flatObj != null) {
                val msgKeys = flatObj.keys()
                while (msgKeys.hasNext()) {
                    val msgIdKey = msgKeys.next()
                    val msgObj = flatObj.optJSONObject(msgIdKey) ?: continue
                    val msg = parseMessageFromJson(msgObj, msgIdKey)
                    if (msg.text.isNotBlank() && !msg.id.startsWith("welcome_") && msg.senderUid != "foul_tv_bot") {
                        byId[msg.id] = msg
                    }
                }
            }
        }

        // 3. Also check Firestore `contact_messages` fallback
        runCatching {
            val fsDocs = getFromSecondaryFirestore("contact_messages")
            if (fsDocs != null) {
                for (i in 0 until fsDocs.length()) {
                    val doc = fsDocs.optJSONObject(i) ?: continue
                    val fields = doc.optJSONObject("fields") ?: continue
                    val sUid = fields.optJSONObject("senderUid")?.optString("stringValue").orEmpty()
                    val tUid = fields.optJSONObject("targetUid")?.optString("stringValue").orEmpty().ifBlank { sUid }
                    val id = fields.optJSONObject("id")?.optString("stringValue")
                        .orEmpty()
                        .ifBlank { doc.optString("name").substringAfterLast('/') }
                    if (id.startsWith("welcome_") || sUid == "foul_tv_bot") continue
                    val item = ContactMessageItem(
                        id = id,
                        senderUid = sUid,
                        senderName = fields.optJSONObject("senderName")?.optString("stringValue").orEmpty(),
                        senderEmail = fields.optJSONObject("senderEmail")?.optString("stringValue").orEmpty(),
                        senderAvatarId = fields.optJSONObject("senderAvatarId")?.optString("stringValue").orEmpty().ifBlank { APP_AVATARS.first().id },
                        text = fields.optJSONObject("text")?.optString("stringValue").orEmpty(),
                        timestamp = fields.optJSONObject("timestamp")?.optString("integerValue")?.toLongOrNull() ?: 0L,
                        isFromBotOrAdmin = fields.optJSONObject("isFromBotOrAdmin")?.optBoolean("booleanValue") ?: false,
                        targetUid = tUid,
                        replyToMessageId = fields.optJSONObject("replyToMessageId")?.optString("stringValue").orEmpty(),
                        replyToSenderName = fields.optJSONObject("replyToSenderName")?.optString("stringValue").orEmpty(),
                        replyToText = fields.optJSONObject("replyToText")?.optString("stringValue").orEmpty()
                    )
                    if (item.text.isNotBlank()) {
                        byId[item.id] = item
                    }
                }
            }
        }

        val sorted = byId.values.sortedBy { it.timestamp }
        _allDeveloperMessages.value = sorted
        saveDeveloperMessagesLocally(sorted)
    }

    /**
     * Sends a Developer Reply to a specific user's swiped message (`عند سحب والرد على رسالة تظهر ذلك عند السحب ليكون بشكل رد منفصل`).
     * The reply is stored under `contact_messages/$targetUid/` so ONLY that user sees the developer's reply!
     */
    suspend fun sendDeveloperReply(
        targetMessage: ContactMessageItem,
        replyText: String,
        developerEmail: String = "support@foultv.app"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanReply = replyText.trim()
            if (cleanReply.isBlank()) throw IllegalArgumentException("يرجى كتابة نص الرد أولاً")

            val targetUserUid = targetMessage.targetUid.ifBlank { targetMessage.senderUid }
            if (targetUserUid.isBlank()) throw IllegalArgumentException("تعذر تحديد معرف المستخدم للرد عليه")

            val now = System.currentTimeMillis()
            val replyMsg = ContactMessageItem(
                id = "dev_reply_${now}_${UUID.randomUUID().toString().take(8)}",
                senderUid = "foul_tv_developer",
                senderName = "مطور التطبيق • Foul TV",
                senderEmail = developerEmail.ifBlank { "support@foultv.app" },
                senderAvatarId = "boy_1",
                text = cleanReply,
                timestamp = now,
                isFromBotOrAdmin = true,
                targetUid = targetUserUid,
                replyToMessageId = targetMessage.id,
                replyToSenderName = targetMessage.senderName,
                replyToText = targetMessage.text
            )

            // Update developer's unified message stream immediately
            val updatedDevList = (_allDeveloperMessages.value.filter { it.id != replyMsg.id } + replyMsg)
                .sortedBy { it.timestamp }
            _allDeveloperMessages.value = updatedDevList
            saveDeveloperMessagesLocally(updatedDevList)

            // If the current logged-in user on this device is the target user, also update their thread immediately
            val currentUser = _currentUser.value
            if (currentUser != null && currentUser.uid == targetUserUid) {
                val updatedUserList = (_contactMessages.value.filter { it.id != replyMsg.id } + replyMsg)
                    .sortedBy { it.timestamp }
                _contactMessages.value = updatedUserList
                saveContactMessagesLocally(updatedUserList)
            }

            // Push to `foul-aeed9` database under `contact_messages/$targetUserUid/${replyMsg.id}`
            pushMessageToSecondaryDb(targetUserUid = targetUserUid, msg = replyMsg)
        }
    }

    private fun ensureInitialBotWelcomeMessage(user: SocialUserProfile) {
        val userFiltered = _contactMessages.value.filter {
            it.targetUid == user.uid || it.senderUid == user.uid || it.id == "welcome_${user.uid}"
        }
        if (userFiltered.isEmpty()) {
            val welcome = ContactMessageItem(
                id = "welcome_${user.uid}",
                senderUid = "foul_tv_bot",
                senderName = "بوت التواصل الذكي • Foul TV",
                senderEmail = "support@foultv.app",
                senderAvatarId = "boy_1",
                text = "مرحباً بك يا ${user.username} في قسم تواصل معنا! أرسل رسالتك أو استفسارك هنا وستصل فوراً إلى لوحة تحكم المطور للرد عليك مباشرة.",
                timestamp = System.currentTimeMillis(),
                isFromBotOrAdmin = true,
                targetUid = user.uid
            )
            _contactMessages.value = listOf(welcome)
            saveContactMessagesLocally(listOf(welcome))
        } else if (userFiltered.size != _contactMessages.value.size) {
            _contactMessages.value = userFiltered
        }
    }

    // --- Low-Level Secondary Database (`foul-aeed9`) HTTP Helpers ---

    private fun getFromSecondaryRtdb(path: String): JSONObject? {
        for (baseUrl in listOf(activeRtdbBaseUrl) + RTDB_CANDIDATE_URLS.filter { it != activeRtdbBaseUrl }) {
            try {
                // First try without auth token (or with auth token) so public/open RTDB rules and authenticated rules both work seamlessly
                val tokenCandidates = if (userIdToken.isNotBlank()) listOf("?auth=$userIdToken", "") else listOf("")
                for (authQuery in tokenCandidates) {
                    val url = "$baseUrl/$path.json$authQuery"
                    val req = Request.Builder().url(url).get().build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            activeRtdbBaseUrl = baseUrl
                            val body = resp.body?.string().orEmpty().trim()
                            if (body.isNotEmpty() && body != "null" && body.startsWith("{")) {
                                return JSONObject(body)
                            }
                            return null
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun putToSecondaryRtdb(path: String, jsonBody: String): Boolean {
        for (baseUrl in listOf(activeRtdbBaseUrl) + RTDB_CANDIDATE_URLS.filter { it != activeRtdbBaseUrl }) {
            try {
                val tokenCandidates = if (userIdToken.isNotBlank()) listOf("?auth=$userIdToken", "") else listOf("")
                for (authQuery in tokenCandidates) {
                    val url = "$baseUrl/$path.json$authQuery"
                    val req = Request.Builder()
                        .url(url)
                        .put(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            activeRtdbBaseUrl = baseUrl
                            return true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun getFromSecondaryFirestore(collection: String): JSONArray? {
        return try {
            val reqBuilder = Request.Builder()
                .url("$FIRESTORE_BASE_URL/$collection?key=$API_KEY")
                .get()
            if (userIdToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $userIdToken")
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                JSONObject(body).optJSONArray("documents")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun patchToSecondaryFirestore(collection: String, docId: String, fieldsJson: JSONObject): Boolean {
        return try {
            val payload = JSONObject().put("fields", fieldsJson)
            val reqBuilder = Request.Builder()
                .url("$FIRESTORE_BASE_URL/$collection/$docId?key=$API_KEY")
                .patch(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (userIdToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $userIdToken")
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    // --- Local Persistence Helpers ---

    private fun getLikedChannelsSet(): Set<String> {
        return prefs.getStringSet("liked_channel_ids", emptySet()) ?: emptySet()
    }

    private fun saveUserProfileLocally(profile: SocialUserProfile, idToken: String, refreshToken: String) {
        prefs.edit()
            .putString("user_uid", profile.uid)
            .putString("user_email", profile.email)
            .putString("user_username", profile.username)
            .putString("user_personal_name", profile.personalName)
            .putString("user_auth_provider", profile.authProvider)
            .putBoolean("user_profile_complete", profile.isProfileComplete)
            .putString("user_avatar_id", profile.avatarId)
            .putString("user_id_token", idToken)
            .putString("user_refresh_token", refreshToken)
            .apply()
    }

    private fun loadSavedUserProfile(): SocialUserProfile? {
        val uid = prefs.getString("user_uid", null) ?: return null
        val email = prefs.getString("user_email", "").orEmpty()
        val username = prefs.getString("user_username", "").orEmpty()
        val personalName = prefs.getString("user_personal_name", "").orEmpty()
        val authProvider = prefs.getString("user_auth_provider", "email").orEmpty().ifBlank { "email" }
        val isProfileComplete = prefs.getBoolean("user_profile_complete", username.isNotBlank())
        val avatarId = prefs.getString("user_avatar_id", APP_AVATARS.first().id).orEmpty()
        if (uid.isBlank() || email.isBlank()) return null
        return SocialUserProfile(
            uid = uid,
            email = email,
            username = username,
            avatarId = avatarId,
            personalName = personalName.ifBlank { username },
            authProvider = authProvider,
            isProfileComplete = isProfileComplete
        )
    }

    private fun saveChannelStatsLocally(map: Map<String, ChannelEngagementStats>) {
        runCatching {
            val arr = JSONArray()
            map.values.forEach { st ->
                arr.put(
                    JSONObject().apply {
                        put("channelId", st.channelId)
                        put("views", st.views)
                        put("likes", st.likes)
                    }
                )
            }
            prefs.edit().putString("cached_channel_stats", arr.toString()).apply()
        }
    }

    private fun loadSavedChannelStats(): Map<String, ChannelEngagementStats> {
        return try {
            val raw = prefs.getString("cached_channel_stats", null) ?: return emptyMap()
            val likedSet = getLikedChannelsSet()
            val arr = JSONArray(raw)
            val map = mutableMapOf<String, ChannelEngagementStats>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("channelId")
                if (id.isNotBlank()) {
                    map[id] = ChannelEngagementStats(
                        channelId = id,
                        views = obj.optInt("views", 0),
                        likes = obj.optInt("likes", 0),
                        isLikedByMe = likedSet.contains(id)
                    )
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveContactMessagesLocally(messages: List<ContactMessageItem>) {
        runCatching {
            val arr = JSONArray()
            messages.forEach { m ->
                arr.put(messageToJson(m))
            }
            prefs.edit().putString("cached_contact_messages", arr.toString()).apply()
        }
    }

    private fun loadSavedContactMessages(): List<ContactMessageItem> {
        return try {
            val raw = prefs.getString("cached_contact_messages", null) ?: return emptyList()
            val arr = JSONArray(raw)
            val list = mutableListOf<ContactMessageItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(parseMessageFromJson(o, "local_$i"))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveDeveloperMessagesLocally(messages: List<ContactMessageItem>) {
        runCatching {
            val arr = JSONArray()
            messages.forEach { m ->
                arr.put(messageToJson(m))
            }
            prefs.edit().putString("cached_developer_all_messages", arr.toString()).apply()
        }
    }

    private fun loadSavedDeveloperMessages(): List<ContactMessageItem> {
        return try {
            val raw = prefs.getString("cached_developer_all_messages", null) ?: return emptyList()
            val arr = JSONArray(raw)
            val list = mutableListOf<ContactMessageItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(parseMessageFromJson(o, "dev_local_$i"))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}

