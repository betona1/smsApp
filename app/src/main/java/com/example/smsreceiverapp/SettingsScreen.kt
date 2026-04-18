package com.example.smsreceiverapp

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 전화번호 설정
    var myPhone by remember { mutableStateOf(loadPhoneNumber(context) ?: "") }

    // 모두 보내기 설정
    val settingsPrefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var sendAllSms by remember { mutableStateOf(settingsPrefs.getBoolean("send_all_sms", false)) }

    // API 서버 설정 (내부)
    var apiHost by remember { mutableStateOf(Prefs.getApiHost(context)) }
    var apiPort by remember { mutableStateOf(Prefs.getApiPort(context)) }

    // 외부 서버 설정
    var extHost by remember { mutableStateOf(Prefs.getExtHost(context)) }
    var extPort by remember { mutableStateOf(Prefs.getExtPort(context)) }

    // 현재 적용 중인 URL 표시용
    var currentApiUrl by remember { mutableStateOf(RetrofitClient.activeBaseUrl ?: Prefs.getBaseUrl(context)) }

    // 텔레그램 알림 설정
    var telegramEnabled by remember { mutableStateOf(true) }
    var telegramLoading by remember { mutableStateOf(true) }

    // 서버에서 현재 텔레그램 상태 로드
    LaunchedEffect(Unit) {
        val phone = loadPhoneNumber(context) ?: ""
        if (phone.isNotBlank()) {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.getApi(context).sendHeartbeat(
                        HeartbeatRequest(phone_number = phone)
                    )
                }
                if (response.isSuccessful) {
                    telegramEnabled = response.body()?.is_notify_telegram ?: true
                }
            } catch (_: Exception) {}
        }
        telegramLoading = false
    }

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
            // ========== 내 전화번호 ==========
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "[ 내 전화번호 ]",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = myPhone,
                        onValueChange = { myPhone = it },
                        label = { Text("전화번호") },
                        placeholder = { Text("01012345678") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))

                    var saving by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            val cleaned = myPhone.trim().replace("-", "").replace(" ", "")
                            if (cleaned.isBlank()) {
                                Toast.makeText(context, "전화번호를 입력해주세요", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val oldPhone = loadPhoneNumber(context) ?: ""
                            if (oldPhone == cleaned) {
                                Toast.makeText(context, "변경 없음", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            saving = true
                            scope.launch {
                                try {
                                    if (oldPhone.isNotBlank()) {
                                        // 서버에 변경 요청 (고스트 레코드 방지)
                                        val response = withContext(Dispatchers.IO) {
                                            RetrofitClient.getApi(context).changePhoneNumber(
                                                ChangePhoneNumberRequest(old_phone = oldPhone, new_phone = cleaned)
                                            )
                                        }
                                        if (response.isSuccessful) {
                                            val action = response.body()?.action ?: "?"
                                            savePhoneNumber(context, cleaned)
                                            myPhone = cleaned
                                            Toast.makeText(context, "변경 완료 ($action): $cleaned", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "서버 변경 실패: ${response.code()}", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        // 최초 입력은 그냥 저장
                                        savePhoneNumber(context, cleaned)
                                        myPhone = cleaned
                                        Toast.makeText(context, "전화번호 저장 완료: $cleaned", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "오류: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (saving) "변경 중..." else "전화번호 저장")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ========== 모두 보내기 ==========
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "[ 문자 전송 설정 ]",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "모두 보내기",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                if (sendAllSms) "수신된 모든 문자를 서버로 전송합니다"
                                else "등록된 번호의 문자만 서버로 전송합니다",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = sendAllSms,
                            onCheckedChange = { checked ->
                                sendAllSms = checked
                                settingsPrefs.edit().putBoolean("send_all_sms", checked).apply()
                                Toast.makeText(
                                    context,
                                    if (checked) "모든 문자 서버 전송 ON" else "등록 번호만 전송",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ========== 텔레그램 알림 ==========
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "[ 텔레그램 알림 ]",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "문자 수신 알림",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (telegramLoading) "서버에서 설정 로딩 중..."
                                else if (telegramEnabled) "수신 문자를 텔레그램으로 알림합니다"
                                else "텔레그램 알림을 받지 않습니다",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = telegramEnabled,
                            enabled = !telegramLoading,
                            onCheckedChange = { checked ->
                                val phone = loadPhoneNumber(context) ?: ""
                                if (phone.isBlank()) {
                                    Toast.makeText(context, "전화번호를 먼저 설정해주세요", Toast.LENGTH_SHORT).show()
                                    return@Switch
                                }
                                telegramEnabled = checked
                                scope.launch {
                                    try {
                                        val response = withContext(Dispatchers.IO) {
                                            RetrofitClient.getApi(context).toggleTelegram(
                                                TelegramToggleRequest(phone_number = phone, enabled = checked)
                                            )
                                        }
                                        if (response.isSuccessful) {
                                            Toast.makeText(
                                                context,
                                                if (checked) "텔레그램 알림 ON" else "텔레그램 알림 OFF",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            telegramEnabled = !checked
                                            Toast.makeText(context, "서버 오류: ${response.code()}", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        telegramEnabled = !checked
                                        Toast.makeText(context, "연결 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ========== API 서버 ==========
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

                    Text(
                        "내부 서버 (사무실)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = apiHost,
                            onValueChange = { apiHost = it },
                            label = { Text("IP") },
                            placeholder = { Text("192.168.219.100") },
                            modifier = Modifier.weight(2f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = apiPort,
                            onValueChange = { apiPort = it },
                            label = { Text("포트") },
                            placeholder = { Text("8379") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "외부 서버 (공유기)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = extHost,
                            onValueChange = { extHost = it },
                            label = { Text("IP") },
                            placeholder = { Text("106.247.220.118") },
                            modifier = Modifier.weight(2f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = extPort,
                            onValueChange = { extPort = it },
                            label = { Text("포트") },
                            placeholder = { Text("8379") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "내부 연결 실패 시 자동으로 외부 서버로 전환됩니다",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            Prefs.setApiServer(context, apiHost.trim(), apiPort.trim())
                            Prefs.setExtServer(context, extHost.trim(), extPort.trim())
                            RetrofitClient.reset()
                            currentApiUrl = Prefs.getBaseUrl(context)
                            Toast.makeText(
                                context,
                                "서버 설정 저장 완료",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("서버 설정 저장")
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
