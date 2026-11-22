package cc.hafa.inbox

/**
 * Public Firebase Android config for project `hafa-inbox`; access is enforced by Firestore rules.
 */
object FirebaseConfig {
    /** Firebase API key for the Android app */
    const val API_KEY = "AIzaSyC-j8x9NTeS-XBK0l6Zua5dPlFeNxfhHDQ"

    /** Firebase app id, `1:<project number>:android:<hash>` */
    const val APPLICATION_ID = "1:477521298784:android:7cea29da68635d660dca05"

    /** project number, also the FCM sender id */
    const val PROJECT_NUMBER = "477521298784"

    /** Firebase project id */
    const val PROJECT_ID = "hafa-inbox"

    /** Cloud Storage bucket */
    const val STORAGE_BUCKET = "hafa-inbox.firebasestorage.app"

    /** region the functions are deployed to */
    const val FUNCTIONS_REGION = "us-central1"

    /** Google OAuth Web client id; Android asks Google for a server auth code issued to it. */
    const val WEB_CLIENT_ID = "477521298784-3eti5o33bkp8es32qgrfks5qppf0qt33.apps.googleusercontent.com"

    /** Whether the config above still holds placeholders. */
    fun hasPlaceholders(): Boolean =
        APPLICATION_ID.startsWith("PLACEHOLDER") || WEB_CLIENT_ID.startsWith("PLACEHOLDER")
}
