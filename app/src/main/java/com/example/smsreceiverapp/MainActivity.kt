package com.example.smsreceiverapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.smsreceiverapp.ui.theme.SmsReceiverAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_MMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 결과 무시 가능 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        checkNotificationListenerPermission()
        requestBatteryOptimizationExemption()

        // 전체문자 전송 항상 ON + 서비스 자동 시작
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("send_all_sms", true).apply()
        SmsSenderService.start(this)

        setContent {
            SmsReceiverAppTheme {
                val context = LocalContext.current
                val storedPhone = loadPhoneNumber(context)?.takeIf { it.isNotBlank() }
                var myPhone: String? by remember { mutableStateOf(storedPhone) }
                var showSettings by remember { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else if (myPhone == null) {
                    PhoneNumberInputScreen {
                        myPhone = loadPhoneNumber(context)?.takeIf { it.isNotBlank() }
                    }
                } else {
                    MainScreen(
                        myPhone = myPhone!!,
                        onOpenSettings = { showSettings = true }
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val ungranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            permissionsLauncher.launch(ungranted.toTypedArray())
        }
    }

    private fun checkNotificationListenerPermission() {
        val cn = ComponentName(this, SmsNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val enabled = flat != null && flat.contains(cn.flattenToString())

        if (!enabled) {
            Toast.makeText(this, "알림 접근 권한을 허용해주세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            // 1. 시스템 배터리 최적화 무시 요청
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "배터리 최적화 요청 실패: ${e.message}")
            }
        }

        // 2. 삼성 기기 추가 설정 안내
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val samsungGuideShown = prefs.getBoolean("samsung_battery_guide_shown", false)
            if (!samsungGuideShown) {
                prefs.edit().putBoolean("samsung_battery_guide_shown", true).apply()
                Toast.makeText(
                    this,
                    "삼성 기기: 설정 → 배터리 → SmsReceiverApp → '제한 없음' 선택해주세요",
                    Toast.LENGTH_LONG
                ).show()
                // 삼성 배터리 설정으로 이동 시도
                try {
                    startActivity(Intent().apply {
                        component = ComponentName(
                            "com.samsung.android.lool",
                            "com.samsung.android.sm.battery.ui.BatteryActivity"
                        )
                    })
                } catch (e: Exception) {
                    try {
                        startActivity(Intent().apply {
                            component = ComponentName(
                                "com.samsung.android.sm_cn",
                                "com.samsung.android.sm.battery.ui.BatteryActivity"
                            )
                        })
                    } catch (e2: Exception) {
                        Log.e("MainActivity", "삼성 배터리 설정 이동 실패")
                    }
                }
            }
        }
    }
}

fun savePhoneNumber(context: Context, number: String) {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit().putString("my_phone_number", number).apply()
}

fun loadPhoneNumber(context: Context): String? {
    return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("my_phone_number", null)
}

@Composable
fun PhoneNumberInputScreen(onSaved: () -> Unit) {
    val context = LocalContext.current
    var phoneNumber by remember { mutableStateOf(TextFieldValue("")) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("내 핸드폰 번호 입력", fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = phoneNumber, onValueChange = { phoneNumber = it })
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            savePhoneNumber(context, phoneNumber.text)
            onSaved()
        }) {
            Text("번호 저장")
        }
    }
}

@Composable
fun MainScreen(myPhone: String, onOpenSettings: () -> Unit) {
    val context = LocalContext.current

    // 서버 상태
    var serverConnected by remember { mutableStateOf<Boolean?>(null) }
    var settingsCount by remember { mutableStateOf(0) }

    // 주기적 서버 상태 체크
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.getApi(context).getCSPhoneSettings()
                }
                serverConnected = response.isSuccessful
                settingsCount = response.body()?.size ?: 0
            } catch (e: Exception) {
                serverConnected = false
            }
            delay(30000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "SMS Receiver",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))

        // 서버 상태 원형 표시
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(
                    when (serverConnected) {
                        true -> Color(0xFF4CAF50)
                        false -> Color(0xFFF44336)
                        null -> Color(0xFFFF9800)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when (serverConnected) {
                    true -> "ON"
                    false -> "OFF"
                    null -> "..."
                },
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            when (serverConnected) {
                true -> "서버 연결됨"
                false -> "서버 연결 안됨"
                null -> "서버 확인 중..."
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        if (serverConnected == true) {
            Text(
                "설정 ${settingsCount}건",
                fontSize = 13.sp,
                color = Color.Gray
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            Prefs.getBaseUrl(context),
            fontSize = 12.sp,
            color = Color.Gray
        )
        Text(
            myPhone,
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}
