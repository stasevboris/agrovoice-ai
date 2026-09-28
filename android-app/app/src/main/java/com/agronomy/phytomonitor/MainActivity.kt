package com.agronomy.phytomonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agronomy.phytomonitor.data.PhytoDbHelper
import com.agronomy.phytomonitor.ui.theme.AgroVoiceAITheme

class MainActivity : ComponentActivity() {

    private lateinit var dbHelper: PhytoDbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Инициализация нативного SQLite
        dbHelper = PhytoDbHelper(this)

        setContent {
            AgroVoiceAITheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0F172A) // Slate 900
                ) { innerPadding ->
                    DashboardScreen(
                        dbHelper = dbHelper,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(dbHelper: PhytoDbHelper, modifier: Modifier = Modifier) {
    var unsyncedCount by remember { mutableStateOf(dbHelper.getUnsyncedCount()) }
    var lastSavedId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Заголовок карточки
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🌿 AgroVoice AI",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Автономная система фитомониторинга",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Бейдж статуса SQLite
                Box(
                    modifier = Modifier
                        .background(Color(0xFF064E3B), shape = RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "● СУБД SQLite активна (PRAGMA foreign_keys = ON)",
                        color = Color(0xFF34D399),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Очередь записей
                Text(
                    text = "Записей в очереди на отправку: $unsyncedCount",
                    color = Color(0xFFE2E8F0),
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Кнопка тестовой записи осмотра
        Button(
            onClick = {
                // Имитируем сохранение результатов осмотра шпалеры
                val id = dbHelper.insertInspectionLog(
                    block = "Блок А-1",
                    rowNumber = 14,
                    photoPath = "/storage/emulated/0/DCIM/crop_014.jpg",
                    pathogenId = "powdery_mildew",
                    confidence = 0.94f,
                    infestationRate = 18.5f,
                    treatmentName = "Топаз, КЭ (2.0 мл / 10 л)"
                )
                lastSavedId = id.take(8) + "..."
                unsyncedCount = dbHelper.getUnsyncedCount()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                text = "Добавить тестовый осмотр в SQLite",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        if (lastSavedId != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "✓ Записано в SQLite! UUID: $lastSavedId",
                color = Color(0xFF34D399),
                fontSize = 12.sp
            )
        }
    }
}