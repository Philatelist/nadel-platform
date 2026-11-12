/**
 * The one runtime cache of the service worker: the help screenshots, cached the first time they are shown. They are
 * public (docs/help) and hold no cooperative data, so this cache survives sign-out like the precache.
 */
export const HELP_SHOTS_CACHE = 'nadel-help-shots-v1';

/** Only the help screenshots go into the runtime cache. */
export const HELP_SHOTS_PATH = /^\/help\/img\/(uk|en)\/[a-z0-9-]+\.png$/;

/** The most help screenshots kept on the device (about 190 KiB each, so about 11 MiB at most). */
export const HELP_SHOTS_MAX_ENTRIES = 60;

/** A screenshot kept longer than this is fetched again (a new release may change it under the same name). */
export const HELP_SHOTS_MAX_AGE_SECONDS = 30 * 24 * 60 * 60;
