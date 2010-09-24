/* Copyright (c) 2009 Christoph Studer <chstuder@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zegoggles.smssync;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.os.Process;
import android.util.Log;
import android.os.AsyncTask;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.zegoggles.smssync.CursorToMessage.ConversionResult;
import com.zegoggles.smssync.ServiceBase.SmsSyncState;
import com.zegoggles.smssync.R;

import java.util.List;

import static com.zegoggles.smssync.ServiceBase.SmsSyncState.*;

public class SmsBackupService extends ServiceBase {
    public static final String TAG = SmsBackupService.class.getName();

    /** Number of messages sent per sync request. */
    private static final int MAX_MSG_PER_REQUEST = 1;

    /** Flag indicating whether this service is already running. */
    private static boolean sIsRunning = false;


    /** Number of messages that currently need a sync. */
    private static int sItemsToSync;

    /** Number of messages already synced during this cycle.  */
    private static int sCurrentSyncedItems;

    /**
     * Indicates that the user canceled the current backup and that this service
     * should finish working ASAP.
     */
    private static boolean sCanceled;

    private boolean isBackground(final Intent intent) {
      return intent.hasExtra(Consts.KEY_NUM_RETRIES);
    }

    @Override
    public void onStart(final Intent intent, int startId) {
        super.onStart(intent, startId);

        if (isBackground(intent) && !getConnectivityManager().getBackgroundDataSetting()) {
            Log.d(TAG, "onStart(): Background data disabled");
            stopSelf();
        } else {
          synchronized(ServiceBase.class) {
            // Only start a sync if there's no other sync / restore going on at this time.
            if (!sIsRunning && !SmsRestoreService.isWorking()) {
              sIsRunning = true;
              new BackupTask().execute(intent);
            }
          }
        }
    }


    /** BackupTask does all the work */
    class BackupTask extends AsyncTask<Intent, SmsSyncState, Integer>
    {
        private Exception ex;
        private android.content.Context context = SmsBackupService.this;
        private int maxItemsPerSync = PrefStore.getMaxItemsPerSync(context);
        private boolean background;

        @Override
        protected void onPreExecute () {
        }

        @Override
        protected java.lang.Integer doInBackground(Intent... params) {
            final Intent intent = params[0];
            this.background = isBackground(intent);

            if (intent.getBooleanExtra(Consts.KEY_SKIP_MESSAGES, false)) {
               return skip();
            }

            Cursor items = null;
            try {
              acquireLocks(background);

              items = getItemsToSync();
              sCurrentSyncedItems = 0;
              sItemsToSync = maxItemsPerSync > 0 ? Math.min(items.getCount(), maxItemsPerSync) : items.getCount();

              if (sItemsToSync <= 0) {
                  PrefStore.setLastSync(context);
                  if (PrefStore.isFirstSync(context)) {
                      // If this is the first backup we need to write something to PREF_MAX_SYNCED_DATE
                      // such that we know that we've performed a backup before.
                      PrefStore.setMaxSyncedDate(context, PrefStore.DEFAULT_MAX_SYNCED_DATE);
                  }
                  Log.d(TAG, "Nothing to do.");
                  return 0;
              } else {
                if (!PrefStore.isLoginInformationSet(context)) {
                   throw new GeneralErrorException(getString(R.string.err_sync_requires_login_info));
                }

                publish(LOGIN);
                Folder folder = getBackupFolder();

                try {
                  return backup(folder, items);
                } finally {
                  folder.close();
                }
              }
            } catch (AuthenticationErrorException authError) {
              publish(AUTH_FAILED);
              this.ex = authError;
              return null;
            } catch (GeneralErrorException e) {
              Log.e(TAG, "error during backup", e);
              lastError = e.getMessage();
              publish(GENERAL_ERROR);
              this.ex = e;
              return null;
            } finally {
              releaseLocks();
              if (items != null) items.close();

              stopSelf();
              Alarms.scheduleRegularSync(context);
           }
        }

        @Override
        protected void onProgressUpdate(SmsSyncState... progress) {
          smsSync.statusPref.stateChanged(progress[0]);
          sState = progress[0];
        }

        @Override
        protected void onPostExecute(Integer result) {
           if (sCanceled) {
              Log.d(TAG, "backup canceled by user");
              publish(CANCELED_BACKUP);
           } else if (result != null) {
              Log.d(TAG, result + " items backed up");
              if (result == sItemsToSync) {
                publish(FINISHED_BACKUP);
              }
           }
           sIsRunning = false;
           sCanceled = false;
        }

      protected void publish(SmsSyncState s) {
        if (!background) publishProgress(s);
      }

      /**
       * @throws GeneralErrorException Thrown when there there was an error during sync.
       * @throws FolderErrorException Thrown when there was an error accessing or creating the folder
       */
      private int backup(final Folder folder, Cursor items) throws GeneralErrorException {
          Log.i(TAG, String.format("Starting backup (%d messages)", sItemsToSync));

          publish(CALC);

          CursorToMessage converter = new CursorToMessage(context, PrefStore.getLoginUsername(context));
          try {
              while (!sCanceled && (sCurrentSyncedItems < sItemsToSync)) {
                  publish(BACKUP);
                  ConversionResult result = converter.cursorToMessageArray(items, MAX_MSG_PER_REQUEST);
                  List<Message> messages = result.messageList;

                  if (messages.isEmpty()) break;

                  Log.d(TAG, "Sending " + messages.size() + " messages to server.");
                  folder.appendMessages(messages.toArray(new Message[messages.size()]));
                  sCurrentSyncedItems += messages.size();
                  publish(BACKUP);
                  updateMaxSyncedDate(result.maxDate);

                  result = null;
                  messages = null;
              }
              return sCurrentSyncedItems;
          } catch (MessagingException e) {
              throw new GeneralErrorException(getString(R.string.err_communication_error));
          }
        }

      /**
       * Returns a cursor of SMS messages that have not yet been synced with the
       * server. This includes all messages with
       * <code>date &lt; {@link #getMaxSyncedDate()}</code> which are no drafs.
       */
      private Cursor getItemsToSync() {
          String sortOrder = SmsConsts.DATE;
          if (PrefStore.getMaxItemsPerSync(context) > 0) {
            sortOrder += " LIMIT " + PrefStore.getMaxItemsPerSync(context);
          }
          return getContentResolver().query(SMS_PROVIDER, null,
                String.format("%s > ? AND %s <> ?", SmsConsts.DATE, SmsConsts.TYPE),
                new String[] { String.valueOf(getMaxSyncedDate()), String.valueOf(SmsConsts.MESSAGE_TYPE_DRAFT) },
                sortOrder);
      }

      public int skip() {
          // Only update the max synced ID, do not really sync.
          updateMaxSyncedDate(getMaxItemDate());
          PrefStore.setLastSync(context);
          sItemsToSync = 0;
          sCurrentSyncedItems = 0;
          sIsRunning = false;
          publish(IDLE);
          Log.i(TAG, "All messages skipped.");
          return 0;
      }
    }

    /**
     * Cancels the current ongoing backup.
    */
    static void cancel() {
        if (sIsRunning) {
          sCanceled = true;
        }
    }

    /**
     * Returns whether there is currently a backup going on or not.
     *
     */
    static boolean isWorking() {
        return sIsRunning;
    }

    /**
     * Returns the number of messages that require sync during the current
     * cycle.
     */
    static int getItemsToSyncCount() {
        return sItemsToSync;
    }

    /**
     * Returns the number of already synced messages during the current cycle.
     */
    static int getCurrentSyncedItems() {
        return sCurrentSyncedItems;
    }
}
