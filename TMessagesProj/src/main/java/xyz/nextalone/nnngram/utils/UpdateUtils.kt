/*
 * Copyright (C) 2019-2024 qwq233 <qwq233@qwq2333.top>
 * https://github.com/qwq233/Nullgram
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this software.
 *  If not, see
 * <https://www.gnu.org/licenses/>
 */

package xyz.nextalone.nnngram.utils

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.browser.Browser
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.TLRPC.Chat
import org.telegram.ui.ActionBar.AlertDialog
import xyz.nextalone.nnngram.config.ConfigManager
import java.util.Arrays

object UpdateUtils {

    private const val channelUsername = "Nnngram"

    private val originalChannelUsername = String(Base64.decode("TnVsbGdyYW1DbGllbnQK", Base64.DEFAULT))

    @JvmStatic
    fun postCheckFollowChannel(ctx: Context, currentAccount: Int) = CoroutineScope(Dispatchers.IO).launch {
        if (ConfigManager.getBooleanOrFalse(Defines.updateChannelSkip)) return@launch

        val messagesCollector = MessagesController.getInstance(currentAccount)
        val connectionsManager = ConnectionsManager.getInstance(currentAccount)
        val messagesStorage = MessagesStorage.getInstance(currentAccount)
        val updateChannel = messagesCollector.getUserOrChat(channelUsername)
        val originalChannel = messagesCollector.getUserOrChat(channelUsername)

        if (updateChannel is TLRPC.Chat) checkFollowChannel(ctx, currentAccount, updateChannel) else {
            connectionsManager.sendRequest(TLRPC.TL_contacts_resolveUsername().apply {
                username = channelUsername
            }) { response: TLObject?, error: TLRPC.TL_error? ->
                if (error == null) {
                    val res = response as TLRPC.TL_contacts_resolvedPeer
                    val chat = res.chats.find { it.username == channelUsername } ?: return@sendRequest
                    messagesCollector.putChats(res.chats, false)
                    messagesStorage.putUsersAndChats(res.users, res.chats, false, true)
                    checkFollowChannel(ctx, currentAccount, chat)
                }
            }
        }

        if (originalChannel is TLRPC.Chat) checkOriginalChannel(ctx, currentAccount, originalChannel) else {
            connectionsManager.sendRequest(TLRPC.TL_contacts_resolveUsername().apply {
                username = originalChannelUsername
            }) { response: TLObject?, error: TLRPC.TL_error? ->
                if (error == null) {
                    val res = response as TLRPC.TL_contacts_resolvedPeer
                    val chat = res.chats.find { it.username == channelUsername } ?: return@sendRequest
                    messagesCollector.putChats(res.chats, false)
                    messagesStorage.putUsersAndChats(res.users, res.chats, false, true)
                    checkFollowChannel(ctx, currentAccount, chat)
                }
            }
        }
    }

    private fun checkFollowChannel(ctx: Context, currentAccount: Int, channel: TLRPC.Chat) {
        if (!channel.left || channel.kicked) {
            return
        }
        ApplicationLoader.applicationHandler.post {
            val messagesCollector = MessagesController.getInstance(currentAccount)
            val userConfig = UserConfig.getInstance(currentAccount)

            val builder = AlertDialog.Builder(ctx)

            builder.setTitle(LocaleController.getString("FCTitle", R.string.FCTitle))
            builder.setMessage(LocaleController.getString("FCInfo", R.string.FCInfo))

            builder.setPositiveButton(LocaleController.getString("ChannelJoin", R.string.ChannelJoin)) { _, _ ->
                messagesCollector.addUserToChat(channel.id, userConfig.currentUser, 0, null, null, null)
                Browser.openUrl(ctx, "https://t.me/$channelUsername")
            }

            builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null)

            builder.setNeutralButton(LocaleController.getString("DoNotRemindAgain", R.string.DoNotRemindAgain)) { _, _ ->
                ConfigManager.putBoolean(Defines.updateChannelSkip, true)
            }
            try {
                builder.show()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun checkOriginalChannel(ctx: Context, currentAccount: Int, channel: Chat) {
        if (!channel.left || channel.kicked) {
            return
        }

        if (BuildConfig.APPLICATION_ID != Arrays.toString(Base64.decode("dG9wLnF3cTIzMzMubnVsbGdyYW0=", Base64.DEFAULT))) {
            ApplicationLoader.applicationHandler.post {
                val messagesCollector = MessagesController.getInstance(currentAccount)
                val userConfig = UserConfig.getInstance(currentAccount)
                val builder = AlertDialog.Builder(ctx)

                builder.setTitle(LocaleController.getString("OneMoreThing", R.string.OneMoreThing))
                builder.setMessage(LocaleController.getString("NotOriginalNullgramInfo", R.string.NotOriginalNullgramInfo))


                builder.setPositiveButton(LocaleController.getString("ChannelJoin", R.string.ChannelJoin)) { _, _ ->
                    messagesCollector.addUserToChat(channel.id, userConfig.currentUser, 0, null, null, null)
                    Browser.openUrl(ctx, "https://t.me/$originalChannelUsername")
                }
                builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null)

                builder.setNeutralButton(LocaleController.getString("DoNotRemindAgain", R.string.DoNotRemindAgain)) { _, _ ->
                    ConfigManager.putBoolean(Defines.updateChannelSkip, true)
                }
                try {
                    builder.show()
                } catch (ignored: Exception) {
                }
            }
        }
    }
}
