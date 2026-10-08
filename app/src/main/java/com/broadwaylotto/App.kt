package com.broadwaylotto

import android.app.Application
import android.content.pm.ApplicationInfo
import com.broadwaylotto.model.Repo
import com.broadwaylotto.sites.Sites

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Receivers and workers run in this process too, so adapters are always ready.
        Sites.init(this)

        // Debug builds only: forget which lotteries were handled on every launch, so open ones
        // re-trigger each time the app starts. Release builds never do this.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Repo.get(this).resetLotteryState()
        }
    }
}
