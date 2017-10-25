/*
 * Copyright (C) The Android Open Source Project
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
 * limitations under the License.
 */
package ca.dungeons.sensordump;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONObject;


/**
 * A class to buffer generated data to a dataBase for later upload.
 * @author Gurtok.
 * @version First version of ESD dataBase helper.
 */
class DatabaseHelper extends SQLiteOpenHelper implements Runnable {

  /** Main database name */
  private static final String DATABASE_NAME = "dbStorage";
  /** Database version. */
  private static final int DATABASE_VERSION = 1;
  /** Table name for database. */
  private static final String TABLE_NAME = "StorageTable";
  /** Json data column name. */
  private static final String dataColumn = "JSON";
  /** Since we only have one database, we reference it on creation. */
  private SQLiteDatabase writableDatabase;
  /** Used to keep track of the database row we are working on. */
  private int deleteRowId = 0;
  /** Used to keep track of supplied database entries in case of upload failure. */
  private int deleteBulkCount = 0;
  /** Used to keep track of the database population. */
  private static long databaseCount = 0L;

  /**
   * Default constructor.
   * Creates a new dataBase if required.
   * @param context Calling method context.
   */
  DatabaseHelper(Context context) {
    super(context, DATABASE_NAME, null, DATABASE_VERSION);
    writableDatabase = getWritableDatabase();
    String query = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (ID INTEGER PRIMARY KEY, JSON TEXT);";
    writableDatabase.execSQL(query);
    databaseCount = DatabaseUtils.queryNumEntries(writableDatabase, DatabaseHelper.TABLE_NAME, null);
  }

  @Override
  public void run() {
  }

  /** Get number of database entries. */
  synchronized long databaseEntries() {
    //Log.e( "dbHelper", "database population: " + DatabaseUtils.queryNumEntries(writableDatabase, DatabaseHelper.TABLE_NAME, null) );
    return databaseCount;
  }

  /**
   * @param db - Existing dataBase.
   * @param oldVersion - Old version number ID.
   * @param newVersion - New version number ID.
   */
  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
  }

  /** Required over-ride method. Not currently used. */
  @Override
  public void onCreate(SQLiteDatabase db) {
  }

  /**
   * Pack the json object into a content values object for shipping.
   * Insert completed json object into dataBase.
   * Key will autoIncrement.
   * Will also start a background thread to upload the database to Kibana.
   * @param jsonObject - Passed object to be inserted.
   */
  void JsonToDatabase(JSONObject jsonObject) {
    long checkDB;
    ContentValues values = new ContentValues();
    values.put(dataColumn, jsonObject.toString());
    if (!writableDatabase.isOpen()) {
      writableDatabase = this.getWritableDatabase();
    }
    checkDB = writableDatabase.insert(TABLE_NAME, null, values);
    if (checkDB == -1) {
      Log.e("Failed insert", "Failed insert database.");
    } else {
      databaseCount++;
    }

  }

  /** Delete a list of rows from database. */
  synchronized void deleteUploadedIndices() {
    for (int i = 0; i <= deleteBulkCount; i++) {
      int deleteID = deleteRowId + i;
      writableDatabase.execSQL("DELETE FROM " + TABLE_NAME + " WHERE ID = " + deleteID);
    }
    databaseCount = databaseCount - deleteBulkCount;
  }

  /**
   * @return - The current database population.
   */
  int getBulkCounts() {
    return deleteBulkCount;
  }

  /**
   * Query the database for up to 100 rows. Concatenate using the supplied schema.
   * Return null if database is empty.
   */
  synchronized String getBulkString(String esIndex, String esType) {
    String bulkOutString = "";
    String separatorString = "{\"index\":{\"_index\":\"" + esIndex + "\",\"_type\":\"" + esType + "\"}}";
    String newLine = "\n";

    databaseCount = DatabaseUtils.queryNumEntries(writableDatabase, DatabaseHelper.TABLE_NAME, null);
    Cursor outCursor = writableDatabase.rawQuery("SELECT * FROM " + DatabaseHelper.TABLE_NAME + " ORDER BY ID ASC LIMIT 1000", new String[]{});
    deleteBulkCount = outCursor.getCount();

    if (deleteBulkCount != 0) {
      outCursor.moveToFirst();
      deleteRowId = outCursor.getInt(0);
      do {
        bulkOutString = bulkOutString.concat(separatorString + newLine + outCursor.getString(1) + newLine);
        outCursor.moveToNext();
      } while (!outCursor.isAfterLast());

      outCursor.close();
      return bulkOutString;
    }
    return null;
  }


}