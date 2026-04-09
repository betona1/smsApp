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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // API 서버 설정
    var apiHost by remember { mutableStateOf(Prefs.getApiHost(context)) }
    var apiPort by remember { mutableStateOf(Prefs.getApiPort(context)) }

    // 현재 적용 중인 URL 표시용
    var currentApiUrl by remember { mutableStateOf(Prefs.getBaseUrl(context)) }

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

            Spacer(Modifier.height(24.dp))
        }
    }
}
