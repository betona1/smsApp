package com.example.phonenumbercompose

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat

@Composable
fun PhoneNumberScreen() {
    val context = LocalContext.current
    var phoneNumber by remember { mutableStateOf("권한을 요청 중입니다...") }

    val permissionGranted = remember {
        ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_NUMBERS
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneNumber = tm.line1Number ?: "전화번호를 가져올 수 없습니다."
        } else {
            phoneNumber = "전화번호 권한이 없습니다."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "내 전화번호", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = phoneNumber)
    }
}