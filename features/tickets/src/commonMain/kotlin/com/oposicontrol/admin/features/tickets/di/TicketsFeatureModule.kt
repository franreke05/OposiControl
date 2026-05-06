package com.oposicontrol.admin.features.tickets.di

import com.oposicontrol.admin.features.tickets.presentation.TicketsViewModel
import org.koin.dsl.module

val ticketsFeatureModule = module {
    factory { TicketsViewModel(get(), get(), get()) }
}
