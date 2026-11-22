// OpenPGP for web and (as a port) Android: keys, PGP/MIME, sealing, recovery phrases, Autocrypt and
// key lookup. Contract: docs/pgp-contract.md.

export {
  type AutocryptHeader,
  autocryptFor,
  autocryptHeaderValue,
  KEYDATA_CHUNK,
  parseAutocrypt,
} from "./autocrypt";
export {
  fromBase64,
  fromHex,
  randomBytes,
  toBase64,
  toBase64Url,
  toHex,
} from "./bytes";
export { EFF_LARGE_WORDLIST } from "./eff-wordlist";
export {
  canEncryptTo,
  fingerprintOf,
  generateKeyPair,
  type KeyPair,
  keyAddresses,
  pickKeyFor,
  readAnyKeys,
  readPublicKey,
  readUnlockedPrivateKey,
} from "./keys";
export {
  ENCRYPTING_DOMAIN,
  type EncryptionDecision,
  type EncryptionReason,
  encryptionDecision,
  keyFromAnswer,
  vksUrl,
} from "./lookup";
export {
  decodedBody,
  decodedText,
  decodeHeaderValue,
  header,
  type MimeEntity,
  type MimeHeader,
  parseContentType,
  parseMime,
  walk,
} from "./mime";
export {
  ADDRESS_HEADERS,
  assemblePgpMime,
  type DecryptedMessage,
  decryptMessage,
  type EncryptionKind,
  encryptionKind,
  encryptMime,
  OBSCURED_SUBJECT,
  PGP_MIME_PREAMBLE,
  PgpError,
  type PgpErrorCode,
  PROTECTED_HEADERS,
  type ProtectedParts,
  type ProtectOptions,
  protectedParts,
  type SignatureStatus,
  verifySigned,
} from "./pgp-mime";
export {
  generatePhrase,
  type ParsedPhrase,
  PHRASE_WORDS,
  parsePhrase,
  RECOVERY_KDF,
  RECOVERY_SALT_LENGTH,
  type RecoveryKdf,
  type RecoverySealed,
  recoverySecret,
  sealWithPhrase,
  unsealWithPhrase,
  wordsFromBytes,
} from "./recovery";
export {
  SEAL_INFO,
  SEAL_IV_LENGTH,
  SEAL_SECRET_LENGTH,
  SEAL_VERSION,
  SealError,
  seal,
  unseal,
} from "./seal";
export {
  type EncryptedSend,
  type EncryptedSendInput,
  planEncryptedSends,
} from "./send-plan";
export {
  splitAddress,
  type WkdUrls,
  wkdHash,
  wkdUrls,
  ZBASE32_ALPHABET,
  zbase32,
} from "./wkd";
