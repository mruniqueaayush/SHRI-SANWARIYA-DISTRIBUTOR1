package com.shrisanwariya.distributor

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings

class ShriSanwariyaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            if (FirebaseApp.getApps(this).isNotEmpty()) {
                val settings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder()
                            .setSizeBytes(100L * 1024L * 1024L)
                            .build()
                    )
                    .build()
                FirebaseFirestore.getInstance().firestoreSettings = settings
            }
        } catch (_: Exception) {
            // App runs seamlessly in offline/local mode when google-services.json is not yet added
        }
    }
}
