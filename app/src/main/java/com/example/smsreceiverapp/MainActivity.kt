package com.example.smsreceiverapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.smsreceiverapp.RetrofitClient
import com.example.smsreceiverapp.db.toResponse
import com.example.smsreceiverapp.ui.theme.SmsReceiverAppTheme
import com.example.smsreceiverapp.ui.theme.db.AppDatabase
import com.example.smsreceiverapp.ui.theme.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.items

class MainActivity : ComponentActivity() {

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.INTERNET)
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

        setContent {
            SmsReceiverAppTheme {
                val context = LocalContext.current

                // ✅ 1. 저장된 값 로딩 및 로깅
                val storedPhone = loadPhoneNumber(context)
                Log.d("OOMainActivity", "📞 저장된 번호 (SharedPreferences): '$storedPhone'")

                // ✅ 2. 공백 제거 후 실제 사용할 번호 결정
                val cleanPhone = storedPhone?.takeIf { it.isNotBlank() }
                var myPhone: String? by remember { mutableStateOf(cleanPhone) }

                Log.d("OOMainActivity", "📞 cleanPhone after takeIf: '$myPhone'")

                // ✅ 3. 조건 분기 로그
                var showSettings by remember { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else if (myPhone == null) {
                    Log.d("OOMainActivity", "🧾 전화번호 입력 화면 진입")
                    PhoneNumberInputScreen {
                        val newlyLoaded = loadPhoneNumber(context)?.takeIf { it.isNotBlank() }
                        Log.d("OOMainActivity", "✅ 번호 입력 후 저장된 값: '$newlyLoaded'")
                        myPhone = newlyLoaded
                    }
                } else {
                    Log.d("OOMainActivity", "📋 문자수신 리스트 화면 진입: '$myPhone'")
                    CSPhoneListScreen(
                        csPhoneNumber = myPhone!!,
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
}

// 저장 & 불러오기
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
        Text("📱 내 핸드폰 번호 입력", fontSize = 18.sp)
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
fun CSPhoneListScreen(csPhoneNumber: String, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var settingsList by remember { mutableStateOf<List<CSPhoneSettingResponse>>(emptyList()) }
    var showDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var settingToEdit by remember { mutableStateOf<CSPhoneSettingResponse?>(null) }
    var sendAllSMS by remember {
        mutableStateOf(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("send_all_sms", false)
        )
    }
    var isSyncing by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var settingToDelete by remember { mutableStateOf<CSPhoneSettingResponse?>(null) }

    // 최초 로딩
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val list = db.csPhoneDao().getMine(csPhoneNumber).map { it.toResponse() }

            Log.d("📋Room", "불러온 리스트 개수: ${list.size}")
            list.forEach {
                Log.d("📋RoomItem", "📞 ${it.csphone_number} ← ${it.checkphone_number}, 저장:${it.is_save_to_db}")
            }

            withContext(Dispatchers.Main) {
                settingsList = list  // ✅ 리스트 통째로 재할당
            }
        }
    }
    fun updateSetting(
        context: Context,
        setting: CSPhoneSettingResponse,              // 기존 세팅 전체
        updatedRequest: CSPhoneSettingRequest         // 수정된 데이터만 담긴 DTO
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d("DBUpdate", "🔄 updateSetting 호출됨 - ID: ${setting.id}")
            Log.d("DBUpdate", "👉 업데이트 요청 데이터: $updatedRequest")
            try {
                val response = RetrofitClient.getApi(context).updateCSPhoneSetting(setting.id, updatedRequest)
                Log.d("DBUpdate", "🌐 서버 응답 코드: ${response.code()}")
                if (response.isSuccessful) {
                    // 👉 DB 인스턴스 타입 명시적으로 선언
                    Log.d("DBUpdate", "✅ 서버 응답 성공")
                    val db = AppDatabase.getInstance(context)

                    // 👉 toEntity() 변환 후 update 호출
                    val entity = response.body()?.toEntity()
                    Log.d("DBUpdate", "📦 로컬 DB에 업데이트할 entity: $entity")
                    // 👉 suspend 함수 update
                    if (entity != null) {
                        Log.d("DBUpdate", "✅ 로컬 DB에 업데이트할 entity: $entity")
                        db.csPhoneDao().update(entity)
                    } else {
                        Log.e("DBUpdate", "❌ 서버 응답 body가 null입니다. 업데이트 중단됨.")
                    }

                    withContext(Dispatchers.Main) {
                        Log.d("DBUpdate", "✅ UI 스레드에서 토스트 호출")
                        Toast.makeText(context, "수정 완료", Toast.LENGTH_SHORT).show()
                        // ✅ DB에서 다시 불러와 리스트 갱신
                        val db = AppDatabase.getInstance(context)
                        withContext(Dispatchers.IO) {
                            val updatedList = db.csPhoneDao().getMine(setting.csphone_number).map { it.toResponse() }
                            withContext(Dispatchers.Main) {
                                settingsList = updatedList
                            }
                        }
                    }
                } else {
                    Log.e("DBUpdate", "서버 수정 실패: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("DBUpdate", "오류 발생", e)
            }
        }
    }
    fun deleteSetting(context: Context, setting: CSPhoneSettingResponse) {
        scope.launch {
            try {
                // 서버에서 삭제
                val res = RetrofitClient.getApi(context).deleteCSPhoneSetting(setting.id)
                if (res.isSuccessful) {
                    // Room DB에서도 삭제
                    AppDatabase.getInstance(context).csPhoneDao().delete(setting.toEntity())

                    // 리스트 갱신
                    val updatedList = AppDatabase.getInstance(context).csPhoneDao()
                        .getMine(setting.csphone_number)
                        .map { it.toResponse() }
                    withContext(Dispatchers.Main) {
                        settingsList = updatedList
                    }
                } else {
                    Log.e("deleteSetting", "서버 삭제 실패: ${res.code()}")
                }
            } catch (e: Exception) {
                Log.e("deleteSetting", "에러: ${e.message}")
            }
        }
    }
    fun toggleSendAllSMS(enabled: Boolean) {
        sendAllSMS = enabled
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("send_all_sms", enabled).apply()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📋 문자수신 리스트", fontSize = 20.sp)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "설정")
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📩 전체문자 전송")
            Switch(checked = sendAllSMS, onCheckedChange = { toggleSendAllSMS(it) })
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("➕ 리스트추가")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ✅ 리스트 부분 LazyColumn 으로 교체
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(settingsList) { setting ->
                CSPhoneSettingItem(
                    setting = setting,

                    // DB 저장 여부 토글
                    onToggleSave = { isChecked ->
                        val request = setting.toRequest().copy(is_save_to_db = isChecked)
                        updateSetting(context, setting, request)
                    },

                    // PC 알림 여부 토글
                    onTogglePC = { isChecked ->
                        val request = setting.toRequest().copy(is_notify_pc = isChecked)
                        updateSetting(context, setting, request)
                    },

                    // 텔레그램 알림 여부 토글
                    onToggleTelegram = { isChecked ->
                        val request = setting.toRequest().copy(is_notify_telegram = isChecked)
                        updateSetting(context, setting, request)
                    },

                    // 삭제
                    onDelete = {
                        settingToDelete = setting
                        showDeleteDialog = true
                    },

                    // 수정 다이얼로그 표시
                    onEdit = {
                        settingToEdit = setting
                        showEditDialog = true
                    }
                )
            }
        }

        if (isSyncing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = {
                scope.launch {
                    isSyncing = true
                    val syncedCount = performSync(context)
                    isSyncing = false
                    Toast.makeText(context, "$syncedCount 건 동기화 완료", Toast.LENGTH_SHORT).show()

                    // ✅ [중요] 동기화 후 DB 다시 읽어서 리스트 갱신
                    val updatedList = withContext(Dispatchers.IO) {
                        AppDatabase.getInstance(context).csPhoneDao()
                            .getMine(csPhoneNumber)
                            .map { it.toResponse() }
                    }
                    settingsList = updatedList
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📥 DB 동기화")
        }

        if (showDialog) {
            AddSettingDialog(
                csPhoneNumber = csPhoneNumber,
                onDismiss = { showDialog = false },
                onSubmit = { request ->
                    scope.launch {
                        val res = RetrofitClient.getApi(context).createCSPhoneSetting(request)
                        if (res.isSuccessful) {
                            settingsList += res.body()!!
                            showDialog = false
                        }
                    }
                }
            )
        }

        if (showEditDialog && settingToEdit != null) {
            val currentSetting = settingToEdit!!  // 안전하게 로컬 변수로 강제 선언

            AddSettingDialog(
                csPhoneNumber = csPhoneNumber,
                onDismiss = {
                    showEditDialog = false
                    settingToEdit = null
                },
                onSubmit = { request ->
                    scope.launch {
                        updateSetting(context, currentSetting, request)
                        showEditDialog = false
                        settingToEdit = null
                    }
                },
                initial = currentSetting.toRequest()
            )
        }
        if (showDeleteDialog && settingToDelete != null) {
            AlertDialog(
                onDismissRequest = {
                    showDeleteDialog = false
                    settingToDelete = null
                },
                title = { Text("삭제 확인") },
                text = { Text("정말 삭제하시겠습니까?") },
                confirmButton = {
                    Button(onClick = {
                        settingToDelete?.let { setting ->
                            deleteSetting(context, setting)
                        }
                        showDeleteDialog = false
                        settingToDelete = null
                    }) {
                        Text("삭제", color = Color.Red)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        showDeleteDialog = false
                        settingToDelete = null
                    }) {
                        Text("취소")
                    }
                }
            )
        }
    }
}

suspend fun performSync(context: Context): Int {
    return try {
        val response = RetrofitClient.getApi(context).getCSPhoneSettings()
        if (response.isSuccessful) {
            val list = response.body().orEmpty()

            withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(context)
                db.csPhoneDao().apply {
                    clearALL()
                    insertALL(list.map { it.toEntity() })
                }
            }

            return list.size
        } else {
            Log.e("Sync", "서버 응답 실패: ${response.code()}")
            0
        }
    } catch (e: Exception) {
        Log.e("Sync", "예외 발생: ${e.message}")
        0
    }
}

@Composable
fun CSPhoneSettingItem(
    setting: CSPhoneSettingResponse,
    onToggleSave: (Boolean) -> Unit,
    onTogglePC: (Boolean) -> Unit,
    onToggleTelegram: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("\uD83C\uDFF7\uFE0F ${setting.alias ?: "이름없음"}", fontSize = 16.sp)
            Text("\uD83D\uDCF1 ${setting.checkphone_number}", fontSize = 14.sp)
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Row {
                SettingSwitch("PC", setting.is_notify_pc, onTogglePC)
                SettingSwitch("TG", setting.is_notify_telegram, onToggleTelegram)
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "수정") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "삭제", tint = Color.Red) }
            }
        }
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(40.dp))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
fun AddSettingDialog(
    csPhoneNumber: String,
    onDismiss: () -> Unit,
    onSubmit: (CSPhoneSettingRequest) -> Unit,
    initial: CSPhoneSettingRequest? = null
) {
    var checkPhone by remember { mutableStateOf(initial?.checkphone_number ?: "") }
    var alias by remember { mutableStateOf(initial?.alias ?: "") }
    var isSaveToDb by remember { mutableStateOf(initial?.is_save_to_db ?: true) }
    var isNotifyPC by remember { mutableStateOf(initial?.is_notify_pc ?: false) }
    var isNotifyTG by remember { mutableStateOf(initial?.is_notify_telegram ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "✏️ 수정" else "📲 추가") },
        text = {
            Column {
                OutlinedTextField(value = checkPhone, onValueChange = { checkPhone = it }, label = { Text("수신처 번호") })
                OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text("이름") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingSwitch("DB", isSaveToDb) { isSaveToDb = it }
                    SettingSwitch("PC", isNotifyPC) { isNotifyPC = it }
                    SettingSwitch("TG", isNotifyTG) { isNotifyTG = it }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSubmit(
                    CSPhoneSettingRequest(
                        csphone_number = csPhoneNumber,
                        checkphone_number = checkPhone,
                        alias = alias,
                        is_save_to_db = isSaveToDb,
                        is_notify_pc = isNotifyPC,
                        is_notify_telegram = isNotifyTG
                    )
                )
            }) {
                Text(if (initial != null) "수정" else "추가")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
