package com.liuying.app.ui.common

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.liuying.app.AppContainer

@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline make: (AppContainer) -> VM): VM {
    val c = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { make(c) } })
}
