package com.oposicontrol.admin.features.news.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun NewsScreen(viewModel: NewsViewModel) {
    val state by viewModel.state.collectAsState()
    NewsContent(state, viewModel::dispatch)
}
