package com.bitic.smsgateway

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitic.smsgateway.db.CSPhoneEntity
import com.bitic.smsgateway.ui.theme.db.AppDatabase
import kotlinx.coroutines.launch

class SplashViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).csPhoneDao()

    var csPhoneList by mutableStateOf<List<CSPhoneEntity>?>(null)
        private set

    init {
        viewModelScope.launch {
            csPhoneList = dao.getAll()  // RoomDB에서 전체 불러오기
        }
    }
}