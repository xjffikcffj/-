import { PdfSearchMatch, PdfSearchResult } from './models';

export const MIN_PASSWORD_LENGTH = 8;
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const DOCUMENT_ID_REGEX = /^[a-zA-Z0-9_-]{2,64}$/;
const MAX_NAME_LENGTH = 120;

export function validateSignInInput(email: string, password: string): { email: string; password: string } {
  const normalizedEmail = validateEmail(email);
  if (!password.trim()) {
    throw new Error('يرجى إدخال كلمة المرور.');
  }
  return { email: normalizedEmail, password };
}

export function validateStudentSignUpInput(
  email: string,
  password: string,
  confirmPassword: string
): { email: string; password: string } {
  const normalizedEmail = validateEmail(email);
  if (!password.trim()) {
    throw new Error('يرجى إدخال كلمة المرور.');
  }
  if (password.length < MIN_PASSWORD_LENGTH) {
    throw new Error('يجب أن تتكون كلمة المرور من 8 أحرف على الأقل.');
  }
  const hasLetter = /[a-zA-Z\u0600-\u06FF]/.test(password);
  const hasDigit = /\d/.test(password);
  if (!hasLetter || !hasDigit) {
    throw new Error('يجب أن تجمع كلمة المرور بين حروف وأرقام (حرف واحد ورقم واحد على الأقل).');
  }
  if (!confirmPassword.trim()) {
    throw new Error('يرجى إدخال تأكيد كلمة المرور.');
  }
  if (password !== confirmPassword) {
    throw new Error('كلمتا المرور غير متطابقتين.');
  }
  return { email: normalizedEmail, password };
}

export function validateEmail(email: string): string {
  const trimmed = email.trim();
  if (!trimmed) {
    throw new Error('يرجى إدخال البريد الإلكتروني.');
  }
  if (!EMAIL_REGEX.test(trimmed)) {
    throw new Error('صيغة البريد الإلكتروني غير صحيحة.');
  }
  return trimmed;
}

export function validateCatalogEntryInput(
  name: string,
  order: number,
  customId?: string | null
): { customId: string | null; name: string; order: number } {
  const trimmedId = customId?.trim() || null;
  if (trimmedId && !DOCUMENT_ID_REGEX.test(trimmedId)) {
    throw new Error('المعرّف التقني غير صالح (استخدم 2–64 حرفاً إنجليزياً أو أرقاماً أو _ أو -).');
  }
  const trimmedName = name.trim();
  if (!trimmedName) {
    throw new Error('يرجى إدخال الاسم أو العنوان.');
  }
  if (trimmedName.length > MAX_NAME_LENGTH) {
    throw new Error('الاسم طويل جداً (الحد الأقصى 120 حرفاً).');
  }
  if (!Number.isInteger(order) || order < 0) {
    throw new Error('يجب أن يكون الترتيب صفراً أو عدداً صحيحاً موجباً.');
  }
  return { customId: trimmedId, name: trimmedName, order };
}

// Arabic-aware normalization & text search ported from ArabicTextNormalizer.kt
const DIACRITICS_REGEX = /[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640]/g;
const WHITESPACE_REGEX = /\s+/g;

export function normalizeArabicForSearch(input: string): string {
  if (!input.trim()) return '';
  const withoutDiacritics = input.replace(DIACRITICS_REGEX, '');
  let result = '';
  for (const ch of withoutDiacritics) {
    switch (ch) {
      case '\u0623': // أ
      case '\u0625': // إ
      case '\u0622': // آ
      case '\u0671': // ٱ
        result += '\u0627'; // ا
        break;
      case '\u0649': // ى
        result += '\u064A'; // ي
        break;
      case '\u0629': // ة
        result += '\u0647'; // ه
        break;
      default:
        result += ch.toLowerCase();
        break;
    }
  }
  return result.trim().replace(WHITESPACE_REGEX, ' ');
}

export function searchArabicAcrossPages(
  query: string,
  pages: string[]
): PdfSearchResult {
  const trimmedQuery = query.trim();
  const normalizedQuery = normalizeArabicForSearch(trimmedQuery);
  if (!normalizedQuery || pages.length === 0) {
    return {
      query: trimmedQuery,
      status: 'IDLE',
      matches: [],
    };
  }

  const matches: PdfSearchMatch[] = [];
  let hasAnyExtractableText = false;
  let globalIndex = 0;

  for (let i = 0; i < pages.length; i++) {
    const pageNumber = i + 1;
    const normalizedPageText = normalizeArabicForSearch(pages[i]);
    if (normalizedPageText.length > 0) {
      hasAnyExtractableText = true;
    } else {
      continue;
    }

    let searchFrom = 0;
    while (searchFrom < normalizedPageText.length) {
      const foundAt = normalizedPageText.indexOf(normalizedQuery, searchFrom);
      if (foundAt < 0) break;

      const snippetStart = Math.max(0, foundAt - 24);
      const snippetEnd = Math.min(normalizedPageText.length, foundAt + normalizedQuery.length + 36);
      const prefix = snippetStart > 0 ? '…' : '';
      const suffix = snippetEnd < normalizedPageText.length ? '…' : '';
      const snippet = prefix + normalizedPageText.slice(snippetStart, snippetEnd).trim() + suffix;

      matches.push({
        pageNumber,
        matchIndexInDocument: globalIndex++,
        snippet,
      });

      searchFrom = foundAt + normalizedQuery.length;
    }
  }

  const status =
    matches.length > 0
      ? 'FOUND'
      : !hasAnyExtractableText
      ? 'NO_EXTRACTABLE_TEXT'
      : 'NO_RESULTS';

  return {
    query: trimmedQuery,
    status,
    matches,
  };
}
