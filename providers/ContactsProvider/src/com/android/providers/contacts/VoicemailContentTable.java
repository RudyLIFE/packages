/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.android.providers.contacts;

import static com.android.providers.contacts.util.DbQueryUtils.checkForSupportedColumns;
import static com.android.providers.contacts.util.DbQueryUtils.concatenateClauses;
import static com.android.providers.contacts.util.DbQueryUtils.getEqualityClause;

import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract;
import android.provider.OpenableColumns;
import android.provider.VoicemailContract.Voicemails;
import android.util.Log;

import com.android.common.content.ProjectionMap;
import com.android.providers.contacts.ContactsDatabaseHelper.Tables;
import com.android.providers.contacts.DialerSearchSupport.DialerSearchLookupColumns;
import com.android.providers.contacts.VoicemailContentProvider.UriData;
import com.android.providers.contacts.util.CloseUtils;
import com.mediatek.providers.contacts.VvmUtils;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Implementation of {@link VoicemailTable.Delegate} for the voicemail content table.
 */
public class VoicemailContentTable implements VoicemailTable.Delegate {
    private static final String TAG = "VoicemailContentProvider";
    private final ProjectionMap mVoicemailProjectionMap;

    /** The private directory in which to store the data associated with the voicemail. */
    private static final String DATA_DIRECTORY = "voicemail-data";

    private static final String[] FILENAME_ONLY_PROJECTION = new String[] { Voicemails._DATA };

    private static final ImmutableSet<String> ALLOWED_COLUMNS = new ImmutableSet.Builder<String>()
            .add(Voicemails._ID)
            .add(Voicemails.NUMBER)
            .add(Voicemails.DATE)
            .add(Voicemails.DURATION)
            .add(Voicemails.IS_READ)
            .add(Voicemails.STATE)
            .add(Voicemails.SOURCE_DATA)
            .add(Voicemails.SOURCE_PACKAGE)
            .add(Voicemails.HAS_CONTENT)
            .add(Voicemails.MIME_TYPE)
            .add(OpenableColumns.DISPLAY_NAME)
            .add(OpenableColumns.SIZE)
            .build();

    private final String mTableName;
    private final SQLiteOpenHelper mDbHelper;
    private final Context mContext;
    private final VoicemailTable.DelegateHelper mDelegateHelper;
    private final CallLogInsertionHelper mCallLogInsertionHelper;

    public VoicemailContentTable(String tableName, Context context, SQLiteOpenHelper dbHelper,
            VoicemailTable.DelegateHelper contentProviderHelper,
            CallLogInsertionHelper callLogInsertionHelper) {
        mTableName = tableName;
        mContext = context;
        mDbHelper = dbHelper;
        mDelegateHelper = contentProviderHelper;
        mVoicemailProjectionMap = new ProjectionMap.Builder()
                .add(Voicemails._ID)
                .add(Voicemails.NUMBER)
                .add(Voicemails.DATE)
                .add(Voicemails.DURATION)
                .add(Voicemails.IS_READ)
                .add(Voicemails.STATE)
                .add(Voicemails.SOURCE_DATA)
                .add(Voicemails.SOURCE_PACKAGE)
                .add(Voicemails.HAS_CONTENT)
                .add(Voicemails.MIME_TYPE)
                .add(Voicemails._DATA)
                .add(OpenableColumns.DISPLAY_NAME, createDisplayName(context))
                .add(OpenableColumns.SIZE, "NULL")
                .build();
        mCallLogInsertionHelper = callLogInsertionHelper;
    }

    /**
     * Calculate a suitable value for the display name column.
     * <p>
     * This is a bit of a hack, it uses a suitably localized string and uses SQL to combine this
     * with the number column.
     */
    private static String createDisplayName(Context context) {
        String prefix = context.getString(R.string.voicemail_from_column);
        return DatabaseUtils.sqlEscapeString(prefix) + " || " + Voicemails.NUMBER;
    }

    @Override
    public Uri insert(UriData uriData, ContentValues values) {
        checkForSupportedColumns(mVoicemailProjectionMap, values);
        ContentValues copiedValues = new ContentValues(values);
        checkInsertSupported(uriData);
        mDelegateHelper.checkAndAddSourcePackageIntoValues(uriData, copiedValues);

        // Add the computed fields to the copied values.
        mCallLogInsertionHelper.addComputedValues(copiedValues);

        // "_data" column is used by base ContentProvider's openFileHelper() to determine filename
        // when Input/Output stream is requested to be opened.
        copiedValues.put(Voicemails._DATA, generateDataFile());

        // call type is always voicemail.
        copiedValues.put(Calls.TYPE, Calls.VOICEMAIL_TYPE);
        // By default marked as new, unless explicitly overridden.
        if (!values.containsKey(Calls.NEW)) {
            copiedValues.put(Calls.NEW, 1);
        }

        SQLiteDatabase db = mDbHelper.getWritableDatabase();

        /**
         * M: fix cr alps00822600 @{
         */
        try {
            db.beginTransaction();
        /// M: keep google default format +++
        long rowId = getDatabaseModifier(db).insert(mTableName, null, copiedValues);
        if (rowId > 0) {
            Uri newUri = ContentUris.withAppendedId(uriData.getUri(), rowId);
            // Populate the 'voicemail_uri' field to be used by the call_log provider.
            updateVoicemailUri(db, newUri);
        /// M: keep google default format ---
                /** [VVM] M: upadte dialerSearch and send new Calls notification @{ */
                if (VvmUtils.isVvmEnabled()) {
                    DialerSearchUtils.updateDialerSearchAfterInsertedCalls(db, copiedValues, rowId,
                            (ContactsDatabaseHelper) mDbHelper, mContext);
                    CallLogProvider.notifyNewCallsCount(db, mContext);
                    notifyDialerSearchChange();
                }
                /** @} */
                db.setTransactionSuccessful();
        /// M: keep google default format +++
            return newUri;
        }
        return null;
        /// M: keep google default format ---
        } finally {
            db.endTransaction();
        }
        /** @} */

    }

    private void checkInsertSupported(UriData uriData) {
        if (uriData.hasId()) {
            throw new UnsupportedOperationException(String.format(
                    "Cannot insert URI: %s. Inserted URIs should not contain an id.",
                    uriData.getUri()));
        }
    }

    /** Generates a random file for storing audio data. */
    private String generateDataFile() {
        try {
            File dataDirectory = mContext.getDir(DATA_DIRECTORY, Context.MODE_PRIVATE);
            File voicemailFile = File.createTempFile("voicemail", "", dataDirectory);
            return voicemailFile.getAbsolutePath();
        } catch (IOException e) {
            // If we are unable to create a temporary file, something went horribly wrong.
            throw new RuntimeException("unable to create temp file", e);
        }
    }
    private void updateVoicemailUri(SQLiteDatabase db, Uri newUri) {
        ContentValues values = new ContentValues();
        values.put(Calls.VOICEMAIL_URI, newUri.toString());
        // Directly update the db because we cannot update voicemail_uri through external
        // update() due to projectionMap check. This also avoids unnecessary permission
        // checks that are already done as part of insert request.
        db.update(mTableName, values, UriData.createUriData(newUri).getWhereClause(), null);
    }

    @Override
    public int delete(UriData uriData, String selection, String[] selectionArgs) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        String combinedClause = concatenateClauses(selection, uriData.getWhereClause(),
                getCallTypeClause());

        // Delete all the files associated with this query.  Once we've deleted the rows, there will
        // be no way left to get hold of the files.
        Cursor cursor = null;
        try {
            cursor = query(uriData, FILENAME_ONLY_PROJECTION, selection, selectionArgs, null);
            while (cursor.moveToNext()) {
                String filename = cursor.getString(0);
                if (filename == null) {
                    Log.w(TAG, "No filename for uri " + uriData.getUri() + ", cannot delete file");
                    continue;
                }
                File file = new File(filename);
                if (file.exists()) {
                    boolean success = file.delete();
                    if (!success) {
                        Log.e(TAG, "Failed to delete file: " + file.getAbsolutePath());
                    }
                }
            }
        } finally {
            CloseUtils.closeQuietly(cursor);
        }

        // Now delete the rows themselves.
        /**
         * M: [VVM] trigger VVM for dialer search
         * Original code:
        return getDatabaseModifier(db).delete(mTableName, combinedClause,
                selectionArgs);
         * @{
         */
        if (VvmUtils.isVvmEnabled()) {
            if (selection == null && uriData.hasId()) {
                // single delete do not need selection, but trigger dialersearch is required
                selection = "_ID = " + uriData.getId();
                Log.d(TAG, "[delete] selection = " + selection);
            }
            if (selection != null) {
                Log.d(TAG, "[delete] call updateDialerSearchBeforeDeleteCalls(),selection:" + selection);
                DialerSearchUtils.updateDialerSearchBeforeDeleteCalls(db, mContext, selection, selectionArgs);
            }
        }
        ///M: Delete VVM entries from dialer_search { @
        int deleteCount = db.delete(Tables.DIALER_SEARCH,
                DialerSearchLookupColumns.CALL_LOG_ID + " IN ( "
                + " SELECT " + Calls._ID + " FROM " + Tables.CALLS
                + " WHERE " + combinedClause + " ) ", null);
        Log.i(TAG, "[delete] deleteCount: " + deleteCount);
        ///M: @ }
        Log.i(TAG, "[delete] combinedClause = " + combinedClause);
        int count = getDatabaseModifier(db).delete(mTableName, combinedClause, selectionArgs);
        if (count > 0) {
            notifyDialerSearchChange();
        }
        return count;
        /** M: [VVM] trigger VVM for dialer search @} */
    }

    @Override
    public Cursor query(UriData uriData, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(mTableName);
        qb.setProjectionMap(mVoicemailProjectionMap);
        qb.setStrict(true);

        String combinedClause = concatenateClauses(selection, uriData.getWhereClause(),
                getCallTypeClause());
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        Cursor c = qb.query(db, projection, combinedClause, selectionArgs, null, null, sortOrder);
        if (c != null) {
            c.setNotificationUri(mContext.getContentResolver(), Voicemails.CONTENT_URI);
        }
        return c;
    }

    @Override
    public int update(UriData uriData, ContentValues values, String selection,
            String[] selectionArgs) {

        checkForSupportedColumns(ALLOWED_COLUMNS, values, "Updates are not allowed.");
        checkUpdateSupported(uriData);

        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        // TODO: This implementation does not allow bulk update because it only accepts
        // URI that include message Id. I think we do want to support bulk update.
        String combinedClause = concatenateClauses(selection, uriData.getWhereClause(),
                getCallTypeClause());
        return getDatabaseModifier(db).update(mTableName, values, combinedClause,
                selectionArgs);
    }

    private void checkUpdateSupported(UriData uriData) {
        if (!uriData.hasId()) {
            throw new UnsupportedOperationException(String.format(
                    "Cannot update URI: %s.  Bulk update not supported", uriData.getUri()));
        }
    }

    @Override
    public String getType(UriData uriData) {
        if (uriData.hasId()) {
            return Voicemails.ITEM_TYPE;
        } else {
            return Voicemails.DIR_TYPE;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(UriData uriData, String mode)
            throws FileNotFoundException {
        return mDelegateHelper.openDataFile(uriData, mode);
    }

    /** Creates a clause to restrict the selection to only voicemail call type.*/
    private String getCallTypeClause() {
        return getEqualityClause(Calls.TYPE, Calls.VOICEMAIL_TYPE);
    }

    private DatabaseModifier getDatabaseModifier(SQLiteDatabase db) {
        return new DbModifierWithNotification(mTableName, db, mContext);
    }

    /**
     * M: Give notice when VVM is deleted or inserted. Fix CR: ALPS00945360
     */
    private void notifyDialerSearchChange() {
        mContext.getContentResolver().notifyChange(
                ContactsContract.AUTHORITY_URI.buildUpon().appendPath("dialer_search")
                        .appendPath("call_log").build(), null, false);
    }
}
