package com.example.smsreceiverapp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val GITHUB_OWNER = "betona1"
    private const val GITHUB_REPO = "smsApp"
    private const val CHECK_INTERVAL = 3600000L // 1시간마다 체크

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startPeriodicCheck(context: Context) {
        scope.launch {
            while (isActive) {
                try {
                    checkForUpdate(context)
                } catch (e: Exception) {
                    Log.e(TAG, "업데이트 확인 실패: ${e.message}")
                }
                delay(CHECK_INTERVAL)
            }
        }
        Log.d(TAG, "자동 업데이트 체크 시작 (1시간 간격)")
    }

    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val hasUpdate: Boolean,
        val downloadUrl: String? = null,
        val error: String? = null
    )

    suspend fun checkForUpdate(context: Context): Boolean {
        val info = checkUpdateInfo(context)
        if (info.hasUpdate && info.downloadUrl != null) {
            downloadAndInstall(context, info.downloadUrl, info.latestVersion)
            return true
        }
        return false
    }

    suspend fun checkUpdateInfo(context: Context): UpdateInfo {
        return withContext(Dispatchers.IO) {
            val currentVersion = getCurrentVersion(context)
            try {
                val url = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode != 200) {
                    return@withContext UpdateInfo(currentVersion, "?", false, null, "응답 실패: ${conn.responseCode}")
                }

                val response = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(response)
                val latestTag = json.getString("tag_name")
                val latestVersion = latestTag.removePrefix("v")

                Log.d(TAG, "현재 버전: $currentVersion, 최신 버전: $latestVersion")

                val hasUpdate = isNewerVersion(currentVersion, latestVersion)
                var downloadUrl: String? = null

                if (hasUpdate) {
                    val assets = json.getJSONArray("assets")
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }
                }

                UpdateInfo(currentVersion, latestVersion, hasUpdate, downloadUrl)
            } catch (e: Exception) {
                Log.e(TAG, "업데이트 확인 오류: ${e.message}")
                UpdateInfo(currentVersion, "?", false, null, e.message)
            }
        }
    }

    fun installUpdate(context: Context, info: UpdateInfo) {
        if (info.downloadUrl != null) {
            downloadAndInstall(context, info.downloadUrl, info.latestVersion)
        }
    }

    fun getCurrentVersionPublic(context: Context): String = getCurrentVersion(context)

    private fun getCurrentVersion(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(currentParts.size, latestParts.size)) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun downloadAndInstall(context: Context, url: String, version: String) {
        val appContext = context.applicationContext
        val fileName = "SmsReceiverApp-v$version.apk"

        val file = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

        // 이미 받은 파일이 있고 정상 크기면 바로 설치
        if (file.exists() && file.length() > 100000) {
            Log.d(TAG, "기존 APK 발견, 바로 설치")
            installApk(appContext, file)
            return
        }
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("SMS Receiver App 업데이트")
            setDescription("v$version 다운로드 중...")
            setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        }

        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        // 다운로드 완료 후 설치 - ApplicationContext 사용해야 leak 안 남
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    Log.d(TAG, "다운로드 완료, 설치 시작")
                    installApk(appContext, file)
                    try {
                        appContext.unregisterReceiver(this)
                    } catch (_: Exception) {}
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            appContext.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }

        Log.d(TAG, "APK 다운로드 시작: $fileName")
    }

    private fun installApk(context: Context, file: File) {
        try {
            if (!file.exists()) {
                Log.e(TAG, "APK 파일 없음: ${file.absolutePath}")
                return
            }
            Log.d(TAG, "APK 설치 시도: ${file.absolutePath} (${file.length()} bytes)")

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            Log.d(TAG, "APK 설치 화면 열림")
        } catch (e: Exception) {
            Log.e(TAG, "APK 설치 실패: ${e.message}", e)
        }
    }
}
