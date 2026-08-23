package com.oposicontrol.admin.features.reports.di

import com.oposicontrol.admin.features.reports.presentation.ReportDetailViewModel
import com.oposicontrol.admin.features.reports.presentation.ReportsViewModel
import org.koin.dsl.module

val reportsFeatureModule = module {
    // single: persiste entre visitas a la pestana (ver DashboardFeatureModule).
    // ReportDetailViewModel sigue siendo factory: necesita un reportId nuevo
    // cada vez, nunca deberia compartirse entre informes distintos.
    single { ReportsViewModel(get(), get(), get()) }
    factory { (reportId: String) -> ReportDetailViewModel(reportId, get(), get(), get()) }
}
