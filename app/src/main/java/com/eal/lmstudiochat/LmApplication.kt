package com.eal.lmstudiochat

import android.app.Application
import com.eal.lmstudiochat.data.ChatStore
import com.eal.lmstudiochat.data.LmStudioClient
import com.eal.lmstudiochat.data.SettingsRepository

class LmApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var chatStore: ChatStore
        private set
    lateinit var client: LmStudioClient
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        chatStore = ChatStore(this)
        client = LmStudioClient()
    }
}
