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
} {
  const app = getOrInitFirebaseAdminApp(config);
  const firestore = getFirestore(app);
  const auth = getAuth(app);

  const idTokenVerifier: IdTokenVerifier = {
    verifyIdToken(idToken: string, checkRevoked = true): Promise<DecodedIdTokenLike> {
      return auth.verifyIdToken(idToken, checkRevoked) as Promise<DecodedIdToken>;
    },
  };

  return { app, firestore, idTokenVerifier };
}
