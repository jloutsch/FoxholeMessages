/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.common

import android.app.Application
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import com.uber.rxdogtag.RxDogTag
import com.uber.rxdogtag.autodispose.AutoDisposeConfigurer
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasAndroidInjector
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.FileLoggingTree
import dev.octoshrimpy.quik.injection.AppComponentManager
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.interactor.SpeakThreads
import dev.octoshrimpy.quik.migration.QkMigration
import dev.octoshrimpy.quik.migration.QkRealmMigration
import dev.octoshrimpy.quik.util.NightModeManager
import dev.octoshrimpy.quik.util.Preferences
import dev.octoshrimpy.quik.worker.HousekeepingWorker
import dev.octoshrimpy.quik.worker.RetentionWorker
import io.realm.Realm
import io.realm.RealmConfiguration
import timber.log.Timber
import javax.inject.Inject

class QKApplication : Application(), HasAndroidInjector {

    /**
     * Inject these so that they are forced to initialize
     */
    @Suppress("unused")
    @Inject lateinit var qkMigration: QkMigration

    @Inject lateinit var androidInjector: DispatchingAndroidInjector<Any>
    @Inject lateinit var fileLoggingTree: FileLoggingTree
    @Inject lateinit var nightModeManager: NightModeManager
    @Inject lateinit var prefs: Preferences
    @Inject lateinit var realmMigration: QkRealmMigration
    @Inject lateinit var workerFactory: WorkerFactory

    override fun onCreate() {
        super.onCreate()

        // set translated "no messages" string for speakThreads interactor
        SpeakThreads.setNoMessagesString(getString(R.string.speak_no_messages))

        AppComponentManager.init(this)
        appComponent.inject(this)

        Realm.init(this)
        Realm.setDefaultConfiguration(RealmConfiguration.Builder()
                .compactOnLaunch()
                .migration(realmMigration)
                .schemaVersion(QkRealmMigration.SCHEMA_VERSION)
                .build())

        qkMigration.performMigration()

        nightModeManager.updateCurrentTheme()

        // configure timber logging
        Timber.plant(Timber.DebugTree(), fileLoggingTree)

        // FileLoggingTree only ever sees what's explicitly logged via Timber - an actual fatal
        // crash goes straight to the system's default handler and kills the process without
        // ever being logged, so exported logs never show why the app closed. Hook in a global
        // handler that logs the crash first (FileLoggingTree writes asynchronously via
        // Schedulers.io(), so give it a moment to flush before actually dying) and then defers
        // to the previous default handler so normal crash/ANR reporting still happens.
        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Timber.e(throwable, "FATAL EXCEPTION on thread ${thread.name}")
                Thread.sleep(500)
            } catch (e: Throwable) {
                // never let the crash handler itself throw
            } finally {
                defaultExceptionHandler?.uncaughtException(thread, throwable)
            }
        }

        // configure emoji compatibility with bundled package
        // (bundled library works with no play-services/gsm os versions)
        EmojiCompat.init(BundledEmojiCompatConfig(this)
            .registerInitCallback(object: EmojiCompat.InitCallback() {
                override fun onInitialized() {
                    super.onInitialized()
                    Timber.v("bundled emojicompat initialized")
                }

                override fun onFailed(throwable: Throwable?) {
                    super.onFailed(throwable)
                    Timber.e("bundled emojicompat initialization failed")
                }
            })
        )

        // rxdogtag provides 'look-back' for exceptions in rxjava2 'chains'
        RxDogTag.builder()
                .configureWith(AutoDisposeConfigurer::configure)
                .install()

        // init work manager with custom factory supporting dagger/injection capability
        WorkManager.initialize(
            this,
            Configuration.Builder().setWorkerFactory(workerFactory).build()
        )

        // register, or re-register, housekeeping work manager
        HousekeepingWorker.register(applicationContext)

        // Re-registering keeps each schedule; doing it every start also covers upgrades from the
        // JobScheduler versions, which only scheduled when the setting was changed
        RetentionWorker.cancelLegacyJobs(this)
        RetentionWorker.schedule(this, RetentionWorker.PURGE_TRASH) // not a setting: always on
        if (prefs.autoDelete.get() > 0) RetentionWorker.schedule(this, RetentionWorker.AUTO_DELETE)
        else RetentionWorker.cancel(this, RetentionWorker.AUTO_DELETE)
        if (prefs.otpRetentionDays.get() > 0) RetentionWorker.schedule(this, RetentionWorker.OTP_RETENTION)
        else RetentionWorker.cancel(this, RetentionWorker.OTP_RETENTION)
    }

    override fun androidInjector(): AndroidInjector<Any> = androidInjector

}