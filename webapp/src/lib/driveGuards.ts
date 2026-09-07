import { google } from "googleapis";
import { getSecondBrainFolderId, getVaultBucketNames } from "./drive";
import { escapeDriveQueryValue, isVaultBucket } from "./driveQuery";

/**
 * Guards for the routes that take a caller-supplied Drive id.
 *
 * Everything here exists because the OAuth token this app holds has full `drive` scope. A route
 * that interpolates an unvalidated id into a Drive query, or acts on any id it is handed, is
 * therefore a route that can be pointed at *any* file in Carl's Drive — including memory.md and
 * anything in a vault bucket. Single-user does not mean single-origin: a CSRF-shaped request or
 * any XSS reaches these endpoints with his session.
 */

function driveClient(accessToken: string) {
  const auth = new google.auth.OAuth2();
  auth.setCredentials({ access_token: accessToken });
  return google.drive({ version: "v3", auth });
}

/**
 * Whether [fileId] lives inside the SecondBrain folder.
 *
 * Walks up the parent chain rather than checking one level, since meeting files sit in a folder
 * inside it. Bounded: a cycle or a very deep tree stops at the limit rather than looping.
 * Fails closed — an unreadable file is not proven safe, so it is refused.
 */
export async function isUnderSecondBrain(
  accessToken: string,
  fileId: string,
  maxDepth = 5
): Promise<boolean> {
  const drive = driveClient(accessToken);
  const rootId = await getSecondBrainFolderId(accessToken);
  let currentId = fileId;

  for (let depth = 0; depth < maxDepth; depth++) {
    if (currentId === rootId) return true;
    try {
      const res = await drive.files.get({
        fileId: currentId,
        fields: "id, parents",
      });
      const parent = res.data.parents?.[0];
      if (!parent) return false;
      if (parent === rootId) return true;
      currentId = parent;
    } catch {
      return false;
    }
  }
  return false;
}

/**
 * The bucket recorded in a meeting folder's meta.json.
 *
 * Three outcomes, and the third is the one that mattered:
 *  - a name — the meeting is filed there;
 *  - `""` — there is no meta.json, or it names no bucket. Unsorted, never vault: meetings are
 *    only ever auto-sorted into non-vault buckets, so an unsorted meeting has not been hidden
 *    by omission. Same rule the meetings list applies.
 *  - `null` — the lookup itself failed. **Not the same as unsorted.**
 *
 * This used to return `""` from its catch, so an unreadable or rate-limited meta.json made a
 * vault-bucketed meeting visible and its audio streamable. `fileIsShareable` in this same file
 * already refuses a note whose bucket it cannot determine, so the two halves of the file
 * disagreed about whether unknown meant safe. It does not.
 */
async function meetingFolderBucket(
  accessToken: string,
  folderId: string
): Promise<string | null> {
  const drive = driveClient(accessToken);
  try {
    const list = await drive.files.list({
      q: `'${escapeDriveQueryValue(folderId)}' in parents and name = 'meta.json' and trashed = false`,
      fields: "files(id)",
    });
    const metaId = list.data.files?.[0]?.id;
    // A successful listing that found nothing is a real answer: no meta.json, so unsorted.
    if (!metaId) return "";
    const res = await drive.files.get(
      { fileId: metaId, alt: "media" },
      { responseType: "text" }
    );
    const meta = JSON.parse(res.data as string) as { bucket?: string };
    return meta.bucket ?? "";
  } catch {
    return null;
  }
}

/**
 * Whether a meeting folder may be served to a caller with the vault in [vaultOpen] state.
 *
 * The meetings *list* has always filtered vault buckets; the audio route did not, so a folder id
 * captured while the vault was open kept streaming the recording after it was locked again.
 */
export async function meetingFolderIsVisible(
  accessToken: string,
  folderId: string,
  vaultOpen: boolean
): Promise<boolean> {
  if (!(await isUnderSecondBrain(accessToken, folderId))) return false;
  if (vaultOpen) return true;
  const bucket = await meetingFolderBucket(accessToken, folderId);
  // null is "could not determine", which is refused rather than assumed unsorted.
  if (bucket === null) return false;
  if (!bucket) return true;
  const vaultBuckets = await getVaultBucketNames(accessToken);
  return !isVaultBucket(bucket, vaultBuckets);
}

/**
 * Whether a file may be published to the open internet by the share route.
 *
 * Two conditions, both required: it is inside SecondBrain at all, and it is not in a vault
 * bucket. Sharing is irreversible in practice — the link keeps working until someone revokes
 * the permission by hand — so this fails closed on anything it cannot verify.
 */
export async function fileIsShareable(
  accessToken: string,
  fileId: string,
  vaultOpen: boolean
): Promise<boolean> {
  const drive = driveClient(accessToken);
  if (!(await isUnderSecondBrain(accessToken, fileId))) return false;

  try {
    const res = await drive.files.get({
      fileId,
      fields: "id, name, parents, mimeType",
    });
    const name = res.data.name ?? "";
    const parentId = res.data.parents?.[0];
    const rootId = await getSecondBrainFolderId(accessToken);

    // A file in a subfolder. Identify the folder rather than assuming what it is.
    //
    // This used to treat *any* non-root parent as a meeting folder, and a meeting folder with
    // no meta.json resolves to "unsorted, therefore visible". But `media/` is also a child of
    // the root and never has a meta.json — so every attachment in the app, including those on
    // vault notes and private journal entries, passed this check and could be published to
    // "anyone with the link" with the vault closed. That link cannot be recalled.
    //
    // A real meeting file sits at meetings/<meeting folder>/<file>, so the test is whether the
    // parent's own parent is the meetings folder. Anything else — media/, or a folder this
    // code does not know — is refused, because its bucket cannot be established here.
    if (parentId && parentId !== rootId) {
      const parentRes = await drive.files.get({
        fileId: parentId,
        fields: "id, name, parents",
      });
      const grandParentId = parentRes.data.parents?.[0];
      // A direct child of the root is media/ or meetings/ itself, not a meeting folder.
      if (!grandParentId || grandParentId === rootId) return false;
      const grandParentRes = await drive.files.get({
        fileId: grandParentId,
        fields: "id, name, parents",
      });
      // Resolved by reading, never by getMeetingsFolderId, which creates the folder when it is
      // absent — a guard must not have side effects on the tree it is judging.
      if (grandParentRes.data.name !== "meetings") return false;
      if (grandParentRes.data.parents?.[0] !== rootId) return false;
      return meetingFolderIsVisible(accessToken, parentId, vaultOpen);
    }

    // A note file: the bucket is a comment in the file itself.
    if (name.startsWith("note_")) {
      const contentRes = await drive.files.get(
        { fileId, alt: "media" },
        { responseType: "text" }
      );
      const bucket =
        (contentRes.data as string).match(/<!--\s*bucket:\s*([^\n]*?)-->/)?.[1]?.trim() ?? "";
      // Unknown bucket is refused rather than assumed public — the same rule the notes list
      // follows, and sharing is the one action that cannot be taken back.
      if (!bucket) return false;
      if (vaultOpen) return true;
      const vaultBuckets = await getVaultBucketNames(accessToken);
      return !isVaultBucket(bucket, vaultBuckets);
    }

    // Anything else in the folder root — memory.md, todos.json, settings.json, a journal
    // entry — is never something Carl asked to publish from this button.
    return false;
  } catch {
    return false;
  }
}
