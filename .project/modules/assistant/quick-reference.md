# Assistant Module — Quick Reference
**Dispatch** | Voice-First AI Messaging App
Last updated: 2026-03-19

---

## Table of Contents

1. [What It Is](#1-what-it-is)
2. [App Actions (shortcuts.xml + capabilities)](#2-app-actions)
3. [Dynamic Shortcuts for Assistant Discovery](#3-dynamic-shortcuts)
4. [Voice Interaction API](#4-voice-interaction-api)
5. [Conversation Shortcuts (Messaging-Specific)](#5-conversation-shortcuts)
6. [Bubbles API (Chat Heads)](#6-bubbles-api)
7. [AppFunctions — The Bridge to Gemini/AI Agents](#7-appfunctions)
8. [Gradle Dependencies](#8-gradle-dependencies)
9. [AndroidManifest Entries](#9-androidmanifest-entries)
10. [Testing Assistant Integrations](#10-testing)
11. [Key Gotchas](#11-key-gotchas)
12. [Official Docs](#12-official-docs)

---

## 1. What It Is

Android apps integrate with AI assistants through a layered stack:

```
User (voice/text)
    ↓
Google Assistant / Gemini (NLU, intent matching)
    ↓
App Actions (shortcuts.xml capabilities → Built-in Intents)   ← today
    ↓
Dynamic Shortcuts (conversation discovery, Direct Share)       ← today
    ↓
AppFunctions (on-device tool execution, Android 16+)           ← near future
```

**Three integration surfaces for Dispatch:**

| Surface | What it enables | Min API |
|---|---|---|
| App Actions | "Hey Google, send a message in Dispatch to Alice" | 21 |
| Conversation Shortcuts | Contact appears in system share sheet; conversation notifications | 21 (compat) |
| Bubbles | Chat-head overlay for ongoing conversations | 29 (10) / required 30 (11) |
| AppFunctions | Gemini executes Dispatch functions via natural language | 36 (16) |

**Dispatch focus areas:** messaging BIIs (`SEND_MESSAGE`, `RECEIVE_MESSAGE`, `OPEN_APP_FEATURE`), conversation shortcuts with `MessagingStyle` notifications, bubbles for active chats, and AppFunctions for Gemini voice-driven compose/reply.

---

## 2. App Actions

App Actions let users drive Dispatch via Assistant voice commands. You declare capabilities in `res/xml/shortcuts.xml` and register that file in `AndroidManifest.xml`.

### 2.1 Messaging Built-in Intents (BIIs)

| BII | Voice trigger example | Key parameter |
|---|---|---|
| `actions.intent.SEND_MESSAGE` | "Send a message to Alice on Dispatch" | `message.recipient.name` |
| `actions.intent.RECEIVE_MESSAGE` | "Show my messages on Dispatch" | — |
| `actions.intent.OPEN_APP_FEATURE` | "Open Dispatch voice recorder" | `feature` |
| `actions.intent.GET_THING` | "Search Dispatch for project files" | `thing.name` |

Full BII reference: https://developers.google.com/assistant/app/reference/built-in-intents/communication/send-message

### 2.2 shortcuts.xml

Place this file at `res/xml/shortcuts.xml` and declare it in the manifest (see section 9).

```xml
<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- SEND_MESSAGE: "Hey Google, send a message to Alice on Dispatch" -->
    <capability android:name="actions.intent.SEND_MESSAGE">
        <intent
            android:action="android.intent.action.VIEW"
            android:targetPackage="com.dispatch.app"
            android:targetClass="com.dispatch.app.compose.ComposeActivity">
            <!-- Recipient name extracted by Assistant NLU -->
            <parameter
                android:name="message.recipient.name"
                android:key="recipient_name" />
        </intent>
        <!-- Deep-link fallback -->
        <intent android:action="android.intent.action.VIEW">
            <url-template android:value="dispatch://compose{?recipient_name}" />
            <parameter
                android:name="message.recipient.name"
                android:key="recipient_name"
                android:mimeType="text/*" />
        </intent>
    </capability>

    <!-- RECEIVE_MESSAGE: "Show my Dispatch messages" -->
    <capability android:name="actions.intent.RECEIVE_MESSAGE">
        <intent
            android:action="android.intent.action.VIEW"
            android:targetPackage="com.dispatch.app"
            android:targetClass="com.dispatch.app.inbox.InboxActivity" />
    </capability>

    <!-- OPEN_APP_FEATURE: "Open voice recorder in Dispatch" -->
    <capability android:name="actions.intent.OPEN_APP_FEATURE">
        <intent
            android:action="android.intent.action.VIEW"
            android:targetPackage="com.dispatch.app"
            android:targetClass="com.dispatch.app.voice.VoiceRecorderActivity">
            <parameter
                android:name="feature"
                android:key="feature_name" />
        </intent>
    </capability>

    <!-- GET_THING: "Search Dispatch for project updates" -->
    <capability android:name="actions.intent.GET_THING">
        <intent
            android:action="android.intent.action.VIEW"
            android:targetPackage="com.dispatch.app"
            android:targetClass="com.dispatch.app.search.SearchActivity">
            <parameter android:name="thing.name" android:key="query" />
        </intent>
    </capability>

    <!-- Direct Share target: Dispatch contacts appear in system share sheet -->
    <share-target android:targetClass="com.dispatch.app.compose.ComposeActivity">
        <data android:mimeType="text/plain" />
        <data android:mimeType="image/*" />
        <data android:mimeType="audio/*" />
        <category android:name="com.dispatch.app.category.SHARE_TARGET" />
    </share-target>

</shortcuts>
```

### 2.3 Handling BII Intents in Activities

```kotlin
// ComposeActivity.kt
class ComposeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launched by SEND_MESSAGE App Action
        val recipientName = intent.getStringExtra("recipient_name")
        if (recipientName != null) {
            viewModel.prefillRecipient(recipientName)
        }

        // Launched via Direct Share shortcut
        val shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
        if (shortcutId != null) {
            viewModel.openConversation(shortcutId)
        }

        // Shared content from another app
        if (intent.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val sharedUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            viewModel.attachSharedContent(sharedText, sharedUri)
        }
    }
}
```

```kotlin
// SearchActivity.kt — handles GET_THING
class SearchActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent.getStringExtra("query")
        query?.let { viewModel.search(it) }
    }
}
```

---

## 3. Dynamic Shortcuts for Assistant Discovery

Dynamic shortcuts make Dispatch contacts discoverable by Assistant for proactive suggestions ("reply to Alice") and populate the launcher long-press menu.

### 3.1 Publishing Conversation Shortcuts

Call this when a conversation is opened or a message is sent/received:

```kotlin
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import androidx.core.people.app.PeopleManager

fun publishConversationShortcut(
    context: Context,
    conversationId: String,
    contactName: String,
    contactUri: String?,
    avatarBitmap: Bitmap?,
    isIncoming: Boolean
) {
    val person = Person.Builder()
        .setName(contactName)
        .apply {
            contactUri?.let { setUri(it) }
            avatarBitmap?.let { setIcon(IconCompat.createWithAdaptiveBitmap(it)) }
        }
        .setKey(conversationId)
        .setImportant(true)
        .build()

    val launchIntent = Intent(context, ConversationActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = "dispatch://conversation/$conversationId".toUri()
        // Must launch directly to conversation — no disambiguation
    }

    val shortcut = ShortcutInfoCompat.Builder(context, conversationId)
        .setShortLabel(contactName)           // e.g. "Alice"
        .setLongLabel("$contactName on Dispatch")
        .setIcon(
            avatarBitmap?.let { IconCompat.createWithAdaptiveBitmap(it) }
                ?: IconCompat.createWithResource(context, R.drawable.ic_default_avatar)
        )
        .setIntent(launchIntent)
        .setLongLived(true)                   // Required: allows system caching
        .setIsConversation()                  // API 32+ marks as conversation
        .setPerson(person)
        .setLocusId(LocusIdCompat(conversationId))   // Used for system ranking
        .setCategories(setOf("com.dispatch.app.category.SHARE_TARGET"))
        // Capability bindings for App Actions
        .addCapabilityBinding(
            if (isIncoming) "actions.intent.RECEIVE_MESSAGE"
            else "actions.intent.SEND_MESSAGE"
        )
        .build()

    // pushDynamicShortcut auto-manages the shortcut limit (max 5 dynamic)
    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
}
```

### 3.2 Group Conversation Shortcut

```kotlin
fun publishGroupShortcut(context: Context, groupId: String, groupName: String) {
    val shortcut = ShortcutInfoCompat.Builder(context, groupId)
        .setShortLabel(groupName)
        .setLongLived(true)
        .setIsConversation()
        .setLocusId(LocusIdCompat(groupId))
        .setIntent(Intent(context, ConversationActivity::class.java).apply {
            data = "dispatch://conversation/$groupId".toUri()
        })
        .setCategories(setOf("com.dispatch.app.category.SHARE_TARGET"))
        // "Audience" type signals group recipient to App Actions
        .addCapabilityBinding(
            "actions.intent.SEND_MESSAGE",
            "message.recipient.@type",
            listOf("Audience")
        )
        .build()

    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
}
```

### 3.3 Removing Shortcuts

Only remove when a conversation is truly deleted — removal also removes user customizations:

```kotlin
ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(conversationId))
```

---

## 4. Voice Interaction API

The `VoiceInteractor` API lets an Activity communicate back to an active voice session (Assistant). Relevant for Dispatch's voice compose flow.

### 4.1 Check if Activity Was Started by Voice

```kotlin
class ComposeActivity : AppCompatActivity() {

    override fun onResume() {
        super.onResume()
        if (isVoiceInteraction) {
            // Activity was launched by a voice assistant
            promptForRecipient()
        }
    }

    private fun promptForRecipient() {
        val interactor = voiceInteractor ?: return

        val prompt = VoiceInteractor.Prompt(
            arrayOf("Who do you want to message?"),
            "Who do you want to message?"
        )

        interactor.submitRequest(
            object : VoiceInteractor.PickOptionRequest(
                prompt,
                arrayOf(
                    VoiceInteractor.PickOptionRequest.Option("Alice", 0),
                    VoiceInteractor.PickOptionRequest.Option("Bob", 1),
                    VoiceInteractor.PickOptionRequest.Option("Carol", 2)
                ),
                null
            ) {
                override fun onPickOptionResult(
                    finished: Boolean,
                    selections: Array<out Option>,
                    result: Bundle
                ) {
                    if (finished && selections.isNotEmpty()) {
                        val selected = selections[0].label.toString()
                        viewModel.prefillRecipient(selected)
                    }
                }

                override fun onCancel() {
                    finish()
                }
            }
        )
    }

    private fun confirmAndSend(recipient: String, message: String) {
        val interactor = voiceInteractor ?: run {
            // Not a voice session — send directly
            viewModel.sendMessage(recipient, message)
            return
        }

        interactor.submitRequest(
            object : VoiceInteractor.ConfirmationRequest(
                VoiceInteractor.Prompt("Send \"$message\" to $recipient?"),
                null
            ) {
                override fun onConfirmationResult(confirmed: Boolean, result: Bundle) {
                    if (confirmed) viewModel.sendMessage(recipient, message)
                    else finish()
                }
            }
        )
    }
}
```

### 4.2 VoiceInteractionService (Advanced — Build Your Own Assistant)

Only implement this if Dispatch ships its own assistant surface (e.g., a Dispatch AI agent that handles system-level hotword):

```kotlin
// Only needed if building a custom voice assistant, not for App Actions integration
class DispatchVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Service is active and ready to receive hotword triggers
    }
}
```

Manifest for `VoiceInteractionService`:
```xml
<service android:name=".DispatchVoiceService"
    android:permission="android.permission.BIND_VOICE_INTERACTION">
    <intent-filter>
        <action android:name="android.service.voice.VoiceInteractionService" />
    </intent-filter>
    <meta-data
        android:name="android.voice_interaction"
        android:resource="@xml/interaction_service" />
</service>
```

---

## 5. Conversation Shortcuts (Messaging-Specific)

Conversation shortcuts are the foundation of Android's messaging integration. They power: system notification drawer conversation section, bubble eligibility, Direct Share targets, and Assistant contact discovery.

### 5.1 MessagingStyle Notification

This is required for Dispatch to appear in the dedicated "Conversations" section of the notification drawer:

```kotlin
fun showMessageNotification(
    context: Context,
    conversationId: String,
    senderName: String,
    senderAvatar: Bitmap?,
    messageText: String,
    timestamp: Long
) {
    // 1. Ensure shortcut exists before posting notification
    publishConversationShortcut(context, conversationId, senderName, null, senderAvatar, true)

    // 2. Build Person objects
    val sender = Person.Builder()
        .setName(senderName)
        .apply {
            senderAvatar?.let { setIcon(IconCompat.createWithAdaptiveBitmap(it)) }
        }
        .setKey(conversationId)
        .build()

    val me = Person.Builder()
        .setName(context.getString(R.string.you))
        .build()

    // 3. Build MessagingStyle
    val messagingStyle = NotificationCompat.MessagingStyle(me)
        .setConversationTitle(senderName)  // omit for 1:1 chats
        .addMessage(messageText, timestamp, sender)

    // 4. Reply action
    val replyPendingIntent = PendingIntent.getBroadcast(
        context, conversationId.hashCode(),
        Intent(context, ReplyReceiver::class.java).putExtra("conversation_id", conversationId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
    val remoteInput = RemoteInput.Builder("reply_key")
        .setLabel(context.getString(R.string.reply))
        .build()
    val replyAction = NotificationCompat.Action.Builder(
        R.drawable.ic_reply, context.getString(R.string.reply), replyPendingIntent
    ).addRemoteInput(remoteInput).build()

    // 5. Build notification
    val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
        .setSmallIcon(R.drawable.ic_notification)
        .setStyle(messagingStyle)
        .setShortcutId(conversationId)       // Links to the shortcut — required Android 11+
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .addAction(replyAction)
        // Bubble metadata (see section 6)
        .setBubbleMetadata(buildBubbleMetadata(context, conversationId))
        .build()

    NotificationManagerCompat.from(context)
        .notify(conversationId.hashCode(), notification)
}
```

### 5.2 Notification Channel Setup (required at app start)

```kotlin
fun createNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID_MESSAGES,
            context.getString(R.string.channel_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_messages_desc)
            // Bubbles require IMPORTANCE_DEFAULT or higher
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}

const val CHANNEL_ID_MESSAGES = "dispatch_messages"
```

---

## 6. Bubbles API

Bubbles float over other apps — ideal for Dispatch's "always-accessible conversation" UX. On Android 11+, bubbles require conversation shortcut compliance.

### 6.1 AndroidManifest for Bubble Activity

```xml
<activity
    android:name=".conversation.BubbleConversationActivity"
    android:theme="@style/Theme.Dispatch.Bubble"
    android:label="@string/app_name"
    android:allowEmbedded="true"
    android:resizeableActivity="true"
    android:exported="false" />
    <!-- android:documentLaunchMode="always" only needed for API 29/30;
         API 31+ sets it automatically -->
```

### 6.2 BubbleMetadata Builder

```kotlin
fun buildBubbleMetadata(context: Context, conversationId: String): NotificationCompat.BubbleMetadata {
    val bubbleIntent = PendingIntent.getActivity(
        context,
        conversationId.hashCode(),
        Intent(context, BubbleConversationActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "dispatch://conversation/$conversationId".toUri()
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.BubbleMetadata.Builder(
        bubbleIntent,
        IconCompat.createWithResource(context, R.drawable.ic_bubble)
    )
        .setDesiredHeight(600)
        .setAutoExpandBubble(false)       // true only when user explicitly opens
        .setSuppressNotification(false)   // true when bubble is open and user is reading
        .build()
}
```

### 6.3 Suppress Notification When Bubble Is Active

Call this in `BubbleConversationActivity.onResume()` to avoid double-alerting:

```kotlin
class BubbleConversationActivity : AppCompatActivity() {

    override fun onResume() {
        super.onResume()
        if (isLaunchedFromBubble) {
            suppressNotificationForConversation(conversationId)
        }
    }

    private fun suppressNotificationForConversation(conversationId: String) {
        // Re-post notification with suppress flag set
        val updatedBubble = NotificationCompat.BubbleMetadata.Builder(
            bubbleIntent, bubbleIcon
        )
            .setDesiredHeight(600)
            .setSuppressNotification(true)
            .build()

        // Re-build and post same notification ID with updated bubble metadata
        notificationManager.notify(conversationId.hashCode(), updatedNotification)
    }

    override fun onBackPressed() {
        // Required: use super to get correct bubble collapse behavior
        super.onBackPressed()
    }
}
```

### 6.4 Check Bubble Permission

```kotlin
fun canShowBubbles(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val notifManager = context.getSystemService(NotificationManager::class.java)
        notifManager.areBubblesAllowed()
    } else false
}
```

---

## 7. AppFunctions — The Bridge to Gemini/AI Agents

AppFunctions (Android 16 / API 36+) expose Dispatch functionality as on-device "tools" that Gemini and other authorized AI agents can discover and invoke — the Android equivalent of MCP server tools.

**Status as of 2026-03:** API is `1.0.0-alpha08` — experimental, subject to change. Google is onboarding developers in limited preview.

### 7.1 How the Bridge Works

```
User says to Gemini: "Send Alice a voice message about the meeting"
    ↓
Gemini queries OS for Dispatch AppFunctions (EXECUTE_APP_FUNCTIONS permission)
    ↓
OS returns schema: [sendMessage, replyToMessage, listConversations, recordVoiceNote, ...]
    ↓
Gemini selects sendMessage, populates params: {recipient: "Alice", type: "voice"}
    ↓
AppFunctionService in Dispatch executes the function on-device
    ↓
Result returned to Gemini for confirmation/display
```

### 7.2 AppFunctionService Setup

```kotlin
// AppFunctions are declared in a class that extends AppFunctionService
// (from androidx.appfunctions:appfunctions-service)
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.appfunction.AppFunction
import androidx.appfunctions.appfunction.AppFunctionSerializable

class DispatchAppFunctionService : AppFunctionService() {
    // Functions are declared here and discovered via KSP-generated schema
}
```

### 7.3 Declaring AppFunctions

```kotlin
@AppFunctionSerializable(isDescribedByKDoc = true)
data class Conversation(
    /** Unique identifier for the conversation */
    val id: String,
    /** Display name of the other participant */
    val participantName: String,
    /** ISO-8601 timestamp of the last message */
    val lastMessageTime: String,
    /** Preview text of the last message */
    val lastMessagePreview: String
)

@AppFunctionSerializable(isDescribedByKDoc = true)
data class SendMessageResult(
    /** True if message was sent successfully */
    val success: Boolean,
    /** The message ID assigned by the server, null on failure */
    val messageId: String?,
    /** Error message if success is false */
    val errorMessage: String?
)

class DispatchAppFunctionService : AppFunctionService() {

    /** Lists the user's recent conversations so an agent can find recipients */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listConversations(
        appFunctionContext: AppFunctionContext
    ): List<Conversation> {
        return conversationRepository.getRecentConversations()
            .map { Conversation(it.id, it.displayName, it.lastMessageTime, it.preview) }
    }

    /**
     * Sends a text message to a named contact.
     *
     * @param recipientName The name of the person to message
     * @param messageText The text content to send
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun sendTextMessage(
        appFunctionContext: AppFunctionContext,
        recipientName: String,
        messageText: String
    ): SendMessageResult {
        return try {
            val conversation = conversationRepository.findByName(recipientName)
                ?: return SendMessageResult(false, null, "Contact '$recipientName' not found")
            val messageId = messageRepository.send(conversation.id, messageText)
            SendMessageResult(true, messageId, null)
        } catch (e: Exception) {
            SendMessageResult(false, null, e.message)
        }
    }

    /**
     * Starts a voice recording and sends it to a named contact.
     *
     * @param recipientName The name of the person to message
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun startVoiceMessageTo(
        appFunctionContext: AppFunctionContext,
        recipientName: String
    ): SendMessageResult {
        // Launches the voice recorder UI pre-filled with recipient
        val intent = Intent(applicationContext, VoiceRecorderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("recipient_name", recipientName)
        }
        applicationContext.startActivity(intent)
        return SendMessageResult(true, null, null)
    }

    /**
     * Replies to the most recent message in a named conversation.
     *
     * @param conversationId The ID of the conversation to reply in
     * @param replyText The reply text
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun replyToConversation(
        appFunctionContext: AppFunctionContext,
        conversationId: String,
        replyText: String
    ): SendMessageResult {
        return try {
            val messageId = messageRepository.reply(conversationId, replyText)
            SendMessageResult(true, messageId, null)
        } catch (e: Exception) {
            SendMessageResult(false, null, e.message)
        }
    }
}
```

### 7.4 AppFunctionService AndroidManifest Entry

```xml
<service
    android:name=".appfunctions.DispatchAppFunctionService"
    android:exported="true"
    android:permission="android.permission.EXECUTE_APP_FUNCTIONS">
    <intent-filter>
        <action android:name="androidx.appfunctions.AppFunctionService" />
    </intent-filter>
</service>
```

### 7.5 Caller Permission Declaration

```xml
<!-- In calling apps (Gemini has this; Dispatch only needs it if it calls other apps' functions) -->
<uses-permission android:name="android.permission.EXECUTE_APP_FUNCTIONS" />
```

---

## 8. Gradle Dependencies

### App module `build.gradle.kts`

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.0.0-1.0.24" // Required for AppFunctions compiler
}

android {
    defaultConfig {
        minSdk = 21  // App Actions minimum
        // targetSdk should be 36 for AppFunctions
    }
}

dependencies {
    // ---- App Actions + Shortcuts + Conversation Shortcuts ----
    // AndroidX Core — ShortcutManagerCompat, Person, LocusIdCompat, NotificationCompat
    implementation("androidx.core:core-ktx:1.16.0")

    // Shortcut Info Compat (ShortcutInfoCompat with setIsConversation, addCapabilityBinding)
    // Bundled within core-ktx — no separate dep needed

    // ---- Sharing Shortcuts / Direct Share ----
    // ChooserTargetServiceCompat is part of androidx.sharetarget (transitively via core)
    // No separate dep needed if using core-ktx >= 1.6.0

    // ---- Bubbles ----
    // Uses NotificationCompat.BubbleMetadata — bundled in core-ktx

    // ---- AppFunctions (Android 16+, alpha) ----
    implementation("androidx.appfunctions:appfunctions:1.0.0-alpha08")
    implementation("androidx.appfunctions:appfunctions-service:1.0.0-alpha08")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha08")

    // ---- Google Shortcuts Integration Library (optional) ----
    // Pushes dynamic shortcuts to Assistant surfaces (e.g., suggestions in Assistant)
    // implementation("com.google.android.gms:play-services-shortcutmanager:...")
    // (Check current version on Maven — this is a Play Services library)
}
```

### KSP Plugin in root `build.gradle.kts`

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.0.0-1.0.24" apply false
}
```

---

## 9. AndroidManifest Entries

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.dispatch.app">

    <!-- ---- Permissions ---- -->
    <!-- Required if Dispatch calls other apps' AppFunctions -->
    <!-- <uses-permission android:name="android.permission.EXECUTE_APP_FUNCTIONS" /> -->

    <application ...>

        <!-- ======================================================
             MAIN LAUNCHER ACTIVITY
             Declare shortcuts.xml here for App Actions
             ====================================================== -->
        <activity
            android:name=".main.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- Register shortcuts.xml for App Actions + sharing shortcuts -->
            <meta-data
                android:name="android.app.shortcuts"
                android:resource="@xml/shortcuts" />
        </activity>

        <!-- ======================================================
             COMPOSE ACTIVITY
             Handles SEND_MESSAGE App Action + Direct Share
             ====================================================== -->
        <activity
            android:name=".compose.ComposeActivity"
            android:exported="true">
            <!-- Deep link for App Actions -->
            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="dispatch" android:host="compose" />
            </intent-filter>
            <!-- Direct Share handler -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
                <data android:mimeType="image/*" />
                <data android:mimeType="audio/*" />
            </intent-filter>
            <!-- Required by Sharing Shortcuts API (AndroidX backcompat) -->
            <meta-data
                android:name="android.service.chooser.chooser_target_service"
                android:value="androidx.sharetarget.ChooserTargetServiceCompat" />
        </activity>

        <!-- ======================================================
             CONVERSATION ACTIVITY
             Handles deep links to specific conversations
             ====================================================== -->
        <activity
            android:name=".conversation.ConversationActivity"
            android:exported="true">
            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="dispatch" android:host="conversation" />
            </intent-filter>
        </activity>

        <!-- ======================================================
             BUBBLE ACTIVITY
             Must have allowEmbedded + resizeable
             ====================================================== -->
        <activity
            android:name=".conversation.BubbleConversationActivity"
            android:allowEmbedded="true"
            android:resizeableActivity="true"
            android:exported="false"
            android:theme="@style/Theme.Dispatch.Bubble" />

        <!-- ======================================================
             APPFUNCTIONS SERVICE (Android 16+)
             Exposes Dispatch functions to Gemini and AI agents
             ====================================================== -->
        <service
            android:name=".appfunctions.DispatchAppFunctionService"
            android:exported="true"
            android:permission="android.permission.EXECUTE_APP_FUNCTIONS">
            <intent-filter>
                <action android:name="androidx.appfunctions.AppFunctionService" />
            </intent-filter>
        </service>

    </application>
</manifest>
```

---

## 10. Testing

### 10.1 Test App Actions with adb

```bash
# Test SEND_MESSAGE App Action deep link
adb shell am start -a android.intent.action.VIEW \
  -d "dispatch://compose?recipient_name=Alice" \
  com.dispatch.app

# Test RECEIVE_MESSAGE
adb shell am start -a android.intent.action.VIEW \
  com.dispatch.app/.inbox.InboxActivity

# Test conversation deep link (from shortcut)
adb shell am start -a android.intent.action.VIEW \
  -d "dispatch://conversation/conv_123" \
  com.dispatch.app
```

### 10.2 Google Assistant Plugin (Android Studio)

1. Install: Android Studio → Settings → Plugins → "Google Assistant"
2. Tools → Google Assistant → App Actions Test Tool
3. Select a BII (e.g., `SEND_MESSAGE`), fill in parameters, click "Run App Action"
4. Validates your `shortcuts.xml` schema and tests the intent fulfillment

### 10.3 Test App Actions via Assistant on Device

1. Use same Google account for: Android Studio, device Google app, Play Console
2. Upload app to internal test track in Play Console
3. Say "Hey Google, send a message to Alice on Dispatch"
4. Check logcat: `adb logcat -s AssistantAction`

### 10.4 Test Conversation Shortcuts

```kotlin
// Unit test: verify shortcut is published
@Test
fun publishConversationShortcut_setsRequiredFields() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    publishConversationShortcut(context, "conv_123", "Alice", null, null, false)

    val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
    val shortcut = shortcuts.find { it.id == "conv_123" }
    assertNotNull(shortcut)
    assertTrue(shortcut!!.isLongLived)
    assertEquals("Alice", shortcut.shortLabel.toString())
}
```

### 10.5 Test Bubbles

```bash
# Enable bubbles in developer options (API 29)
adb shell settings put global notification_bubbles 1

# Verify a notification has bubble metadata
adb shell dumpsys notification --noredact | grep -A5 "BubbleMetadata"
```

### 10.6 Test AppFunctions

```bash
# Verify AppFunctions schema was generated (look for generated XML)
# After building, check: app/build/generated/ksp/debug/resources/

# Check AppFunctionService is registered
adb shell dumpsys package com.dispatch.app | grep -A3 "AppFunctionService"
```

---

## 11. Key Gotchas

**App Actions**
- App must be published on Google Play (at minimum internal test track) before App Actions work on-device. Local dev builds do not work with live Assistant.
- Activities launched by App Actions must be `android:exported="true"`. Unexported activities silently fail.
- `shortcuts.xml` changes require Play Console upload to propagate; test locally via the Assistant Plugin.
- Apps in the Designed for Families (DFF) program cannot use App Actions — submissions will be rejected.

**Conversation Shortcuts**
- `setLongLived(true)` is mandatory. Without it, the shortcut is ineligible for the system conversations section and bubbles.
- Publish the shortcut *before* posting the notification. If the shortcut doesn't exist when the notification fires, Android 11+ silently demotes the notification from the conversations section.
- Use `pushDynamicShortcut()` instead of `setDynamicShortcuts()` to avoid accidentally clearing other shortcuts when the list is full.
- Do not reuse shortcut IDs across different contacts — IDs carry ranking history.

**Bubbles**
- `android:allowEmbedded="true"` and `android:resizeableActivity="true"` are both required on the bubble Activity or it will not render.
- On Android 11+, a notification only appears as a bubble if it satisfies conversation requirements (MessagingStyle + Person + valid long-lived shortcut). A bubble without this silently degrades to a normal notification.
- `setAutoExpandBubble(true)` + `setSuppressNotification(true)` should only be used when the user explicitly triggers the bubble (e.g., tapping a compose FAB). Abusing this causes Android to revoke bubble permission for your app.
- `isLaunchedFromBubble` requires API 29. Gate it: `if (Build.VERSION.SDK_INT >= 29) isLaunchedFromBubble`.

**ChooserTargetService / Direct Share**
- `ChooserTargetService` was deprecated in API 29 and stopped working in API 31. Use only the Sharing Shortcuts API.
- The share-target `category` in `shortcuts.xml` must exactly match the category set on the shortcut via `setCategories()`.

**AppFunctions**
- Requires Android 16 (API 36) on the *user's device*. Gate all AppFunction invocation code with `Build.VERSION.SDK_INT >= 36` or use `AppFunctionManagerCompat` which handles this.
- The `appfunctions-compiler` KSP plugin generates the XML schema at build time. If KSP is not configured, no functions will be discoverable by agents.
- Every `@AppFunction` method must have `AppFunctionContext` as its first parameter — omitting it causes a KSP compile error.
- Library is in alpha (`1.0.0-alpha08`). Expect breaking API changes before stable release. Pin exact versions in your `libs.versions.toml`.

**Voice Interaction API**
- `voiceInteractor` returns `null` when the Activity was *not* launched by a voice session. Always null-check before calling `submitRequest()`.
- `isVoiceInteraction` is distinct from `isVoiceInteractionRoot` — use `isVoiceInteraction` to check if any ancestor was voice-launched.

---

## 12. Official Docs

- [Google Assistant for Android — Overview](https://developer.android.com/develop/devices/assistant/overview)
- [Build App Actions — Get Started](https://developer.android.com/develop/devices/assistant/get-started)
- [Built-in Intents Reference](https://developer.android.com/develop/devices/assistant/intents)
- [App Actions Overview (developers.google.com)](https://developers.google.com/assistant/app/overview)
- [Send Message BII Reference](https://developers.google.com/assistant/app/reference/built-in-intents/communication/send-message)
- [People and Conversations (Notifications)](https://developer.android.com/develop/ui/views/notifications/conversations)
- [Provide Direct Share Targets](https://developer.android.com/training/sharing/direct-share-targets)
- [Bubbles API Guide](https://developer.android.com/develop/ui/views/notifications/bubbles)
- [AppFunctions Overview](https://developer.android.com/ai/appfunctions)
- [AppFunctions Jetpack Releases](https://developer.android.com/jetpack/androidx/releases/appfunctions)
- [The Intelligent OS — Making AI Agents More Helpful (Android Blog, Feb 2026)](https://android-developers.googleblog.com/2026/02/the-intelligent-os-making-ai-agents.html)
- [VoiceInteractor API Reference](https://developer.android.com/reference/kotlin/android/app/VoiceInteractor)
- [ShortcutInfo.Builder — addCapabilityBinding](https://developer.android.com/reference/android/content/pm/ShortcutInfo.Builder#addCapabilityBinding)
- [SociaLite Sample App (bubbles + conversations reference)](https://github.com/android/socialite)
- [AppActions Fitness Kotlin Sample](https://github.com/actions-on-google/appactions-fitness-kotlin)
- [App Actions Test Tool (Android Studio Plugin)](https://developer.android.com/develop/devices/assistant/test-tool)
- [Google Assistant Learning Pathway](https://developers.google.com/learn/pathways/app-actions)
