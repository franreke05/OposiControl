package com.oposicontrol.admin.features.audit.di

import com.oposicontrol.admin.features.audit.presentation.AuditViewModel
import org.koin.dsl.module

val auditFeatureModule = module {
    factory { AuditViewModel(get(), get(), get()) }
}
