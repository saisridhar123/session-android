package org.thoughtcrime.securesms.loki.protocol

import android.content.Context
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.jobs.RetrieveProfileAvatarJob
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.api.messages.SignalServiceContent
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.loki.protocol.shelved.multidevice.MultiDeviceProtocol
import java.security.MessageDigest

object SessionMetaProtocol {

    private val timestamps = mutableSetOf<Long>()

    @JvmStatic
    fun dropFromTimestampCacheIfNeeded(timestamp: Long) {
        timestamps.remove(timestamp)
    }

    @JvmStatic
    fun shouldIgnoreMessage(content: SignalServiceContent): Boolean {
        val timestamp = content.timestamp
        val shouldIgnoreMessage = timestamps.contains(timestamp)
        timestamps.add(timestamp)
        return shouldIgnoreMessage
    }

    @JvmStatic
    fun shouldErrorMessageShow(context: Context, timestamp: Long): Boolean {
        val restorationTimestamp = TextSecurePreferences.getRestorationTime(context)
        return timestamp > restorationTimestamp
    }

    @JvmStatic
    fun handleProfileUpdateIfNeeded(context: Context, content: SignalServiceContent) {
        val rawDisplayName = content.senderDisplayName.orNull() ?: return
        if (rawDisplayName.isBlank()) { return }
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val userMasterPublicKey = TextSecurePreferences.getMasterHexEncodedPublicKey(context)
        val sender = content.sender.toLowerCase()
        if (userMasterPublicKey == sender) {
            // Update the user's local name if the message came from their master device
            TextSecurePreferences.setProfileName(context, rawDisplayName)
        }
        // Don't overwrite if the message came from a linked device; the device name is
        // stored as a user name
        val allUserDevices = MultiDeviceProtocol.shared.getAllLinkedDevices(userPublicKey)
        if (!allUserDevices.contains(sender)) {
            val displayName = rawDisplayName + " (..." + sender.substring(sender.length - 8) + ")"
            DatabaseFactory.getLokiUserDatabase(context).setDisplayName(sender, displayName)
        } else {
            DatabaseFactory.getLokiUserDatabase(context).setDisplayName(sender, rawDisplayName)
        }
    }

    @JvmStatic
    fun handleProfileKeyUpdate(context: Context, content: SignalServiceContent) {
        val message = content.dataMessage.get()
        if (!message.profileKey.isPresent) { return }
        val database = DatabaseFactory.getRecipientDatabase(context)
        val recipient = Recipient.from(context, Address.fromSerialized(content.sender), false)
        if (recipient.profileKey == null || !MessageDigest.isEqual(recipient.profileKey, message.profileKey.get())) {
            database.setProfileKey(recipient, message.profileKey.get())
            database.setUnidentifiedAccessMode(recipient, RecipientDatabase.UnidentifiedAccessMode.UNKNOWN)
            val url = content.senderProfilePictureURL.or("")
            ApplicationContext.getInstance(context).jobManager.add(RetrieveProfileAvatarJob(recipient, url))
            val userMasterPublicKey = TextSecurePreferences.getMasterHexEncodedPublicKey(context)
            if (userMasterPublicKey == content.sender) {
                ApplicationContext.getInstance(context).updateOpenGroupProfilePicturesIfNeeded()
            }
        }
    }

    /**
     * Should be invoked for the recipient's master device.
     */
    @JvmStatic
    fun canUserReplyToNotification(recipient: Recipient): Boolean {
        return !recipient.address.isRSSFeed
    }

    @JvmStatic
    fun shouldSendDeliveryReceipt(message: SignalServiceDataMessage, address: Address): Boolean {
        if (address.isGroup) { return false }
        val hasBody = message.body.isPresent && message.body.get().isNotEmpty()
        val hasAttachment = message.attachments.isPresent && message.attachments.get().isNotEmpty()
        val hasLinkPreview = message.previews.isPresent && message.previews.get().isNotEmpty()
        return hasBody || hasAttachment || hasLinkPreview
    }

    /**
     * Should be invoked for the recipient's master device.
     */
    @JvmStatic
    fun shouldSendReadReceipt(address: Address): Boolean {
        return !address.isGroup
    }

    /**
     * Should be invoked for the recipient's master device.
     */
    @JvmStatic
    fun shouldSendTypingIndicator(address: Address): Boolean {
        return !address.isGroup
    }
}