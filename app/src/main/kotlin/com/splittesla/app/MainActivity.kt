package com.splittesla.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.splittesla.app.ui.SplitTeslaApp

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) viewModel.log("권한 거부됨: $denied. 차량 검색이 동작하지 않을 수 있습니다")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Location is needed on every version: scans that may see beacons (a Tesla is one) require it.
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permissionLauncher.launch(permissions)
        setContent { SplitTeslaApp(viewModel) }
    }
}
