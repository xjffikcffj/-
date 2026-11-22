import {
  applicationDefault,
  cert,
  getApps,
  initializeApp,
  type App,
  type ServiceAccount,
} from 'firebase-admin/app';
import { getAuth, type DecodedIdToken } from 'firebase-admin/auth';
import { getFirestore, type Firestore } from 'firebase-admin/firestore';
import { getMessaging, type TopicMessage } from 'firebase-admin/messaging';
import type { ServerConfig } from '../config/env.js';

export interface DecodedIdTokenLike {
  uid: string;
  email?: string;
  role?: unknown;
  firebase?: {
    sign_in_provider?: string;
  };
  [key: string]: unknown;
}

export interface IdTokenVerifier {
  verifyIdToken(
    idToken: string,
    checkRevoked?: boolean,
  ): Promise<DecodedIdTokenLike>;
}

export interface StaffAuthUserRecord {
  uid: string;
  email?: string | null;
  displayName?: string | null;
  disabled: boolean;
  emailVerified: boolean;
  customClaims?: Record<string, unknown>;
}

export interface StaffAuthListUsersResult {
  users: StaffAuthUserRecord[];
  pageToken?: string;
}

export interface StaffAuthManager {
  getUser(uid: string): Promise<StaffAuthUserRecord>;
  getUserByEmail(email: string): Promise<StaffAuthUserRecord>;
  listUsers(
    maxResults?: number,
    pageToken?: string,
  ): Promise<StaffAuthListUsersResult>;
  setCustomUserClaims(
    uid: string,
    customUserClaims: Record<string, unknown> | null,
  ): Promise<void>;
  revokeRefreshTokens(uid: string): Promise<void>;
}

export interface PushNotificationMessage {
  topic: string;
  data: Record<string, string>;
}

export function buildTopicFcmMessage(message: PushNotificationMessage): TopicMessage {
  return {
    topic: message.topic,
    data: { ...message.data },
    android: {
      priority: 'high',
    },
  };
}

export interface PushNotificationDispatcher {
  sendToTopic(message: PushNotificationMessage): Promise<string>;
}

function parseServiceAccount(rawJson: string): ServiceAccount {
  let parsed: unknown;
  try {
    parsed = JSON.parse(rawJson);
  } catch {
    throw new Error(
      'FIREBASE_SERVICE_ACCOUNT_JSON is not valid JSON.',
    );
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('FIREBASE_SERVICE_ACCOUNT_JSON must be a JSON object.');
  }
  return parsed as ServiceAccount;
}

export function getOrInitFirebaseAdminApp(config: ServerConfig): App {
  const existing = getApps().find((app) => app.name === 'haqibati-backend');
  if (existing) {
    return existing;
  }

  if (config.firebaseServiceAccountJson) {
    return initializeApp(
      {
        credential: cert(parseServiceAccount(config.firebaseServiceAccountJson)),
        projectId: config.firebaseProjectId,
      },
      'haqibati-backend',
    );
  }

  if (config.firestoreEmulatorHost || config.firebaseAuthEmulatorHost) {
    return initializeApp(
      {
        projectId: config.firebaseProjectId,
      },
      'haqibati-backend',
    );
  }

  return initializeApp(
    {
      credential: applicationDefault(),
      projectId: config.firebaseProjectId,
    },
    'haqibati-backend',
  );
}

export function createFirebaseAdminServices(config: ServerConfig): {
  app: App;
  firestore: Firestore;
  idTokenVerifier: IdTokenVerifier;
  staffAuthManager: StaffAuthManager;
  pushNotificationDispatcher: PushNotificationDispatcher;
} {
  const app = getOrInitFirebaseAdminApp(config);
  const firestore = getFirestore(app);
  const auth = getAuth(app);
  const messaging = getMessaging(app);

  const idTokenVerifier: IdTokenVerifier = {
    verifyIdToken(idToken: string, checkRevoked = true): Promise<DecodedIdTokenLike> {
      return auth.verifyIdToken(idToken, checkRevoked) as Promise<DecodedIdToken>;
    },
  };

  const mapUserRecord = (record: {
    uid: string;
    email?: string;
    displayName?: string;
    disabled: boolean;
    emailVerified: boolean;
    customClaims?: Record<string, unknown>;
  }): StaffAuthUserRecord => ({
    uid: record.uid,
    email: record.email ?? null,
    displayName: record.displayName ?? null,
    disabled: record.disabled,
    emailVerified: record.emailVerified,
    customClaims: record.customClaims,
  });

  const staffAuthManager: StaffAuthManager = {
    async getUser(uid: string): Promise<StaffAuthUserRecord> {
      return mapUserRecord(await auth.getUser(uid));
    },
    async getUserByEmail(email: string): Promise<StaffAuthUserRecord> {
      return mapUserRecord(await auth.getUserByEmail(email));
    },
    async listUsers(
      maxResults = 1000,
      pageToken?: string,
    ): Promise<StaffAuthListUsersResult> {
      const result = await auth.listUsers(maxResults, pageToken);
      return {
        users: result.users.map(mapUserRecord),
        pageToken: result.pageToken,
      };
    },
    async setCustomUserClaims(
      uid: string,
      customUserClaims: Record<string, unknown> | null,
    ): Promise<void> {
      await auth.setCustomUserClaims(uid, customUserClaims);
    },
    async revokeRefreshTokens(uid: string): Promise<void> {
      await auth.revokeRefreshTokens(uid);
    },
  };

  const pushNotificationDispatcher: PushNotificationDispatcher = {
    async sendToTopic(message: PushNotificationMessage): Promise<string> {
      return messaging.send(buildTopicFcmMessage(message));
    },
  };

  return {
    app,
    firestore,
    idTokenVerifier,
    staffAuthManager,
    pushNotificationDispatcher,
  };
}
