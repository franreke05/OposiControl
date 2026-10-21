package com.oposicontrol.admin.features.controlv1.di

import com.oposicontrol.admin.features.controlv1.presentation.AlertsViewModel
import com.oposicontrol.admin.features.controlv1.presentation.ConfigEditorViewModel
import com.oposicontrol.admin.features.controlv1.presentation.ExamEconomyViewModel
import com.oposicontrol.admin.features.controlv1.presentation.ForumBlocksViewModel
import com.oposicontrol.admin.features.controlv1.presentation.ForumChannelsViewModel
import com.oposicontrol.admin.features.controlv1.presentation.ForumQueueViewModel
import com.oposicontrol.admin.features.controlv1.presentation.OrdersViewModel
import com.oposicontrol.admin.features.controlv1.presentation.ReferralViewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Calificadores de los dos editores de configuración (mismo tipo, distinto grupo). */
val REFERRAL_CONFIG: org.koin.core.qualifier.Qualifier = named("control-v1-config-referido")
val FORUM_CONFIG: org.koin.core.qualifier.Qualifier = named("control-v1-config-foro")

/**
 * Control V1. Los ViewModel son `single`: viven mientras vive el panel, igual que el resto, y la pantalla los relee al
 * abrirse con su propio `Refresh`.
 */
val controlV1FeatureModule = module {
    single(REFERRAL_CONFIG) { ConfigEditorViewModel(dispatcherProvider = get(), repository = get(), group = "referido") }
    single(FORUM_CONFIG) { ConfigEditorViewModel(dispatcherProvider = get(), repository = get(), group = "foro") }
    single { ReferralViewModel(dispatcherProvider = get(), repository = get()) }
    single { ForumQueueViewModel(dispatcherProvider = get(), repository = get()) }
    single { ForumChannelsViewModel(dispatcherProvider = get(), repository = get()) }
    single { ForumBlocksViewModel(dispatcherProvider = get(), repository = get()) }
    single { ExamEconomyViewModel(dispatcherProvider = get(), repository = get(), communitiesRepository = get()) }
    single { OrdersViewModel(dispatcherProvider = get(), repository = get()) }
    single { AlertsViewModel(dispatcherProvider = get(), repository = get()) }
}
