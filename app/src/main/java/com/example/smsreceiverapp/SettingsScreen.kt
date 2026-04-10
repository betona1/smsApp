package com.example.smsreceiverapp

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // API 서버 설정
    var apiHost by remember { mutableStateOf(Prefs.getApiHost(context)) }
    var apiPort by remember { mutableStateOf(Prefs.getApiPort(context)) }

    // 현재 적용 중인 URL 표시용
    var currentApiUrl by remember { mutableStateOf(Prefs.getBaseUrl(context)) }

    // 업데이트 상태
    val currentVersion = remember { AppUpdater.getCurrentVersionPublic(context) }
    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<AppUpdater.UpdateInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("서버 설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "[ API 서버 ] - SMS 전송 대상",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "현재: $currentApiUrl",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = apiHost,
                        onValueChange = { apiHost = it },
                        label = { Text("IP 주소") },
                        placeholder = { Text("192.168.219.100") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiPort,
                        onValueChange = { apiPort = it },
                        label = { Text("포트") },
                        placeholder = { Text("8010") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            Prefs.setApiServer(context, apiHost.trim(), apiPort.trim())
                            RetrofitClient.reset()
                            currentApiUrl = Prefs.getBaseUrl(context)
                            Toast.makeText(
                                context,
                                "API 서버 저장 완료: $currentApiUrl",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("API 서버 저장")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ========== 앱 버전 / 업데이트 ==========
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "[ 앱 버전 ]",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "현재 버전: v$currentVersion",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )

                    val info = updateInfo
                    if (info != null) {
                        Spacer(Modifier.height(4.dp))
                        when {
                            info.error != null -> Text(
                                "확인 실패: ${info.error}",
                                fontSize = 12.sp,
                                color = Color.Red
                            )
                            info.hasUpdate -> Text(
                                "새 버전 발견: v${info.latestVersion}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                            else -> Text(
                                "최신 버전입니다 (v${info.latestVersion})",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            checking = true
                            scope.launch {
                                updateInfo = AppUpdater.checkUpdateInfo(context)
                                checking = false
                                val info = updateInfo
                                if (info != null && !info.hasUpdate && info.error == null) {
                                    Toast.makeText(context, "최신 버전입니다", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (checking) "확인 중..." else "버전 확인")
                    }

                    if (info != null && info.hasUpdate) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                Toast.makeText(context, "다운로드 시작...", Toast.LENGTH_SHORT).show()
                                AppUpdater.installUpdate(context, info)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E7D32)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("업데이트 설치 (v${info.latestVersion})")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
