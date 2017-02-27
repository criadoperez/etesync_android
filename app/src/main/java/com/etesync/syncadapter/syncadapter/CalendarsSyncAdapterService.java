/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package com.etesync.syncadapter.syncadapter;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.support.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

import com.etesync.syncadapter.AccountSettings;
import com.etesync.syncadapter.App;
import com.etesync.syncadapter.Constants;
import com.etesync.syncadapter.NotificationHelper;
import com.etesync.syncadapter.R;
import com.etesync.syncadapter.journalmanager.Exceptions;
import com.etesync.syncadapter.model.CollectionInfo;
import com.etesync.syncadapter.model.ServiceDB;
import com.etesync.syncadapter.model.ServiceDB.Collections;
import com.etesync.syncadapter.model.ServiceDB.Services;
import com.etesync.syncadapter.resource.LocalCalendar;
import com.etesync.syncadapter.ui.DebugInfoActivity;
import at.bitfire.ical4android.CalendarStorageException;
import lombok.Cleanup;
import okhttp3.HttpUrl;

import static com.etesync.syncadapter.Constants.KEY_ACCOUNT;

public class CalendarsSyncAdapterService extends SyncAdapterService {

    @Override
    protected AbstractThreadedSyncAdapter syncAdapter() {
        return new SyncAdapter(this);
    }


    private static class SyncAdapter extends SyncAdapterService.SyncAdapter {

        public SyncAdapter(Context context) {
            super(context);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
            super.onPerformSync(account, extras, authority, provider, syncResult);

            NotificationHelper notificationManager = new NotificationHelper(getContext(), "journals", Constants.NOTIFICATION_CALENDAR_SYNC);
            notificationManager.cancel();

            try {
                AccountSettings settings = new AccountSettings(getContext(), account);
                if (!extras.containsKey(ContentResolver.SYNC_EXTRAS_MANUAL) && !checkSyncConditions(settings))
                    return;

                new RefreshCollections(account, CollectionInfo.Type.CALENDAR).run();

                HttpUrl principal = updateLocalCalendars(provider, account, settings);

                for (LocalCalendar calendar : (LocalCalendar[]) LocalCalendar.find(account, provider, LocalCalendar.Factory.INSTANCE, CalendarContract.Calendars.SYNC_EVENTS + "!=0", null)) {
                    App.log.info("Synchronizing calendar #" + calendar.getId() + ", URL: " + calendar.getName());
                    CalendarSyncManager syncManager = new CalendarSyncManager(getContext(), account, settings, extras, authority, syncResult, calendar, principal);
                    syncManager.performSync();
                }
            } catch (Exceptions.ServiceUnavailableException e) {
                syncResult.stats.numIoExceptions++;
                syncResult.delayUntil = (e.retryAfter > 0) ? e.retryAfter : Constants.DEFAULT_RETRY_DELAY;
            } catch (Exception | OutOfMemoryError e) {
                if (e instanceof CalendarStorageException || e instanceof SQLiteException) {
                    App.log.log(Level.SEVERE, "Couldn't prepare local calendars", e);
                    syncResult.databaseError = true;
                }

                int syncPhase = R.string.sync_phase_journals;
                String title = getContext().getString(R.string.sync_error_calendar, account.name);

                notificationManager.setThrowable(e);

                final Intent detailsIntent = notificationManager.getDetailsIntent();
                detailsIntent.putExtra(KEY_ACCOUNT, account);
                if (!(e instanceof Exceptions.UnauthorizedException)) {
                    detailsIntent.putExtra(DebugInfoActivity.KEY_AUTHORITY, authority);
                    detailsIntent.putExtra(DebugInfoActivity.KEY_PHASE, syncPhase);
                }

                notificationManager.notify(title, getContext().getString(syncPhase));
            }

            App.log.info("Calendar sync complete");
        }

        private HttpUrl updateLocalCalendars(ContentProviderClient provider, Account account, AccountSettings settings) throws CalendarStorageException {
            HttpUrl ret = null;
            ServiceDB.OpenHelper dbHelper = new ServiceDB.OpenHelper(getContext());
            try {
                // enumerate remote and local calendars
                SQLiteDatabase db = dbHelper.getReadableDatabase();
                Long service = dbHelper.getService(db, account, Services.SERVICE_CALDAV);

                ret = HttpUrl.get(settings.getUri());

                Map<String, CollectionInfo> remote = remoteCalendars(db, service);

                LocalCalendar[] local = (LocalCalendar[])LocalCalendar.find(account, provider, LocalCalendar.Factory.INSTANCE, null, null);

                boolean updateColors = settings.getManageCalendarColors();

                // delete obsolete local calendar
                for (LocalCalendar calendar : local) {
                    String url = calendar.getName();
                    if (!remote.containsKey(url)) {
                        App.log.fine("Deleting obsolete local calendar " + url);
                        calendar.delete();
                    } else {
                        // remote CollectionInfo found for this local collection, update data
                        CollectionInfo info = remote.get(url);
                        App.log.fine("Updating local calendar " + url + " with " + info);
                        calendar.update(info, updateColors);
                        // we already have a local calendar for this remote collection, don't take into consideration anymore
                        remote.remove(url);
                    }
                }

                // create new local calendars
                for (String url : remote.keySet()) {
                    CollectionInfo info = remote.get(url);
                    App.log.info("Adding local calendar list " + info);
                    LocalCalendar.create(account, provider, info);
                }
            } finally {
                dbHelper.close();
            }

            return ret;
        }

        @NonNull
        private Map<String, CollectionInfo> remoteCalendars(@NonNull SQLiteDatabase db, Long service) {
            Map<String, CollectionInfo> collections = new LinkedHashMap<>();
            if (service != null) {
                @Cleanup Cursor cursor = db.query(Collections._TABLE, null,
                        Collections.SERVICE_ID + "=? AND " + Collections.SUPPORTS_VEVENT + "!=0 AND " + Collections.SYNC,
                        new String[]{String.valueOf(service)}, null, null, null);
                while (cursor.moveToNext()) {
                    ContentValues values = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(cursor, values);
                    CollectionInfo info = CollectionInfo.fromDB(values);
                    collections.put(info.url, info);
                }
            }
            return collections;
        }
    }

}