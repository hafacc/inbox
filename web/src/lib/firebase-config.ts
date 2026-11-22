/**
 * Public Firebase web config for project `hafa-inbox`; access is enforced by Firestore rules.
 */
export const firebaseConfig = {
  apiKey: "AIzaSyAh3xhf_5Z666w7XhgkRkurz_pMkzci2mM",
  authDomain: "hafa-inbox.firebaseapp.com",
  projectId: "hafa-inbox",
  storageBucket: "hafa-inbox.firebasestorage.app",
  messagingSenderId: "477521298784",
  appId: "1:477521298784:web:61ca95ff60ed21c20dca05",
};

/** Google OAuth Web client id used for the Gmail code flow. */
export const googleClientId =
  "477521298784-3eti5o33bkp8es32qgrfks5qppf0qt33.apps.googleusercontent.com";

/**
 * Public VAPID key for web push (Firebase console → Project settings → Cloud Messaging → Web Push
 * certificates).
 */
export const vapidKey =
  "BLhkXdg-S-syH-G0djbYZWFkcICqWRX7TqKeVEiybSty40qEeX5nstxS7MpKKSMEyqMiE3R7ZXvCheLCKLqFTS0";

/** Whether the config above still holds placeholders. */
export function hasPlaceholders(): boolean {
  return (
    firebaseConfig.appId.startsWith("PLACEHOLDER") ||
    googleClientId.startsWith("PLACEHOLDER")
  );
}
