package com.eachother.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.eachother.app.ui.navigation.EachOtherNavGraph
import com.eachother.app.ui.theme.EachOtherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EachOtherApplication).container
        setContent {
            EachOtherTheme {
                EachOtherNavGraph(container = container)
            }
        }
    }
}
