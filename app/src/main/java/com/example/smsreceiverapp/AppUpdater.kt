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

    suspend fun checkForUpdate(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode != 200) {
                    Log.e(TAG, "GitHub API 응답 실패: ${conn.responseCode}")
                    return@withContext false
                }

                val response = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(response)
                val latestTag = json.getString("tag_name") // "v1.0.1"
                val latestVersion = latestTag.removePrefix("v") // "1.0.1"

                val currentVersion = getCurrentVersion(context)
                Log.d(TAG, "현재 버전: $currentVersion, 최신 버전: $latestVersion")

                if (isNewerVersion(currentVersion, latestVersion)) {
                    // APK 다운로드 URL 찾기
                    val assets = json.getJSONArray("assets")
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.getString("name")
                        if (name.endsWith(".apk")) {
                            val downloadUrl = asset.getString("browser_download_url")
                            Log.d(TAG, "새 버전 발견: $latestVersion, 다운로드: $downloadUrl")
                            downloadAndInstall(context, downloadUrl, latestVersion)
                            return@withContext true
                        }
                    }
                } else {
                    Log.d(TAG, "최신 버전 사용 중")
                }

                false
            } catch (e: Exception) {
                Log.e(TAG, "업데이트 확인 오류: ${e.message}")
                false
            }
        }
    }

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
        val fileName = "SmsReceiverApp-v$version.apk"

        // 기존 파일 삭제
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("SMS Receiver App 업데이트")
            setDescription("v$version 다운로드 중...")
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        // 다운로드 완료 후 설치
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    Log.d(TAG, "다운로드 완료, 설치 시작")
                    installApk(context, file)
                    try {
                        context.unregisterReceiver(this)
                    } catch (_: Exception) {}
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )

        Log.d(TAG, "APK 다운로드 시작: $fileName")
    }

    private fun installApk(context: Context, file: File) {
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "APK 설치 실패: ${e.message}")
        }
    }
}
