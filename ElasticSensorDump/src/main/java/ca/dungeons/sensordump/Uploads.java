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

import android.content.SharedPreferences;
import android.util.Log;
import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/**
 * A class to start a thread upload the database to Kibana.
 * @author Gurtok.
 * @version First version of upload Async thread.
 */
class Uploads implements Runnable {

  /** Static variable for the indexer thread to communicate success or failure of an index attempt. */
  boolean uploadSuccess = false;
  /** Control variable to indicate if we should stop uploading to elastic. */
  private static boolean stopUploadThread = false;
  /** Name of the elastic index to ID the data. */
  private String esIndex;
  /** Name of the elastic index TYPE. */
  private String esType;
  /** ID for logcat. */
  private final String logTag = "Uploads";
  /** A reference to the apps stored preferences. */
  private final SharedPreferences sharedPreferences;
  /** A reference to the service manager, used to update data counts. */
  private final EsdServiceManager serviceManager;
  /** A reference to the database helper the service manager controls. */
  private final DatabaseHelper dbHelper;
  /** The thread class to do the actual uploading of data with. */
  private final ElasticSearchIndexer esIndexer;
  /** Control variable to indicate if this runnable is currently uploading data. */
  private boolean working = false;

  /** Default Constructor using the application context. */
  Uploads(SharedPreferences passedPreferences, EsdServiceManager serviceManager, DatabaseHelper dbHelper) {
    sharedPreferences = passedPreferences;
    this.serviceManager = serviceManager;
    this.dbHelper = dbHelper;
    esIndexer = new ElasticSearchIndexer( this );
  }

  /** Main class entry. The data we need has already been updated. So just go nuts. */
  @Override
  public void run() {
    working = true;
    Log.e(logTag, "Started upload thread.");
    startUploading();
    Log.e(logTag, "Stopped upload thread.");
    working = false;
  }

  /** A method to indicate if this thread is currently working. */
  boolean isWorking(){
    return working;
  }

  /** Control method to halt the whole thread. */
  void stopUploading() {
    stopUploadThread = true;
  }

  /** Main work of upload runnable is accomplished here. */
  private void startUploading() {
    Long globalUploadTimer = System.currentTimeMillis();
    stopUploadThread = false;
    /* If we cannot establish a connection with the elastic server. */
    if (!checkForElasticHost()) {
      // This thread is not working.
      // We should stop the service if this is true.
      stopUploadThread = true;
      Log.e(logTag, "No elastic host.");
      return;
    }
    /* Loop to keep uploading. */
    while (!stopUploadThread) {
      // If we have gone more than 20 seconds without an update, stop the thread.
      if(System.currentTimeMillis() > globalUploadTimer + 10000 || dbHelper.databaseEntries() < 10 ){
        stopUploadThread = true;
        return;
      }
      // One bulk upload per second.
      if (System.currentTimeMillis() > globalUploadTimer + 5000 && dbHelper.databaseEntries() > 100 ) {
        updateIndexerUrl();
        String nextString = dbHelper.getBulkString(esIndex, esType);
        // If setNextString has data.
        if( nextString != null ){
          esIndexer.setNextString( nextString );
          try {
            uploadSuccess = false;
            // Start the indexing thread, and join to wait for it to finish.
            esIndexer.start();
            esIndexer.join();
          } catch (InterruptedException interEx) {
            Log.e(logTag, "Failed to join ESI thread, possibly not running.");
          }
        }
        if (uploadSuccess) {
          Log.e( logTag, "Uploaded " + dbHelper.getDeleteCount() + " rows from DB." );
          globalUploadTimer = System.currentTimeMillis();
          serviceManager.uploadSuccess(true, dbHelper.getDeleteCount() );
          dbHelper.deleteUploadedIndices();
        }else{
          Log.e(logTag, "Failed index.");
          serviceManager.uploadSuccess(false, dbHelper.getDeleteCount() );
        }
      }
    }
  }

  /**
   * Extract config information from sharedPreferences.
   * Tag the current date stamp on the index name if set in preferences. Credit: GlenRSmith.
   */
  private void updateIndexerUrl() {
    // Security variables.
    String esUsername = sharedPreferences.getString("user", "");
    String esPassword = sharedPreferences.getString("pass", "");
    // X-Pack security credentials.
    if (esUsername.length() > 0 && esPassword.length() > 0) {
      esIndexer.esUsername = esUsername;
      esIndexer.esPassword = esPassword;
    }
    String esHost = sharedPreferences.getString("host", "localhost");
    String esPort = sharedPreferences.getString("port", "9200");
    esIndex = sharedPreferences.getString("index", "test_index");
    esType = sharedPreferences.getString("type", "esd");
    // Tag the current date stamp on the index name if set in preferences
    // Thanks GlenRSmith for this idea
    if (sharedPreferences.getBoolean("index_date", false)) {
      Date logDate = new Date(System.currentTimeMillis());
      SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);
      String dateString = logDateFormat.format(logDate);
      esIndex = esIndex + "-" + dateString;
    }
    String mappingURL = String.format("%s:%s/%s", esHost, esPort, esIndex);
    // Note the different URLs. Regular post ends with type. Mapping ends with index ID.
    String postingURL = String.format("%s:%s/%s", esHost, esPort, "_bulk");
    try {
      esIndexer.mapUrl = new URL(mappingURL);
      esIndexer.postUrl = new URL(postingURL);
    } catch (MalformedURLException malformedUrlEx) {
      Log.e(logTag, "Failed to update URLs.");
      esIndexer.mapUrl = null;
      esIndexer.postUrl = null;
    }
  }

  /** Helper method to determine if we currently have access to an elastic server to upload to. */
  private boolean checkForElasticHost() {
    boolean responseCodeSuccess = false;
    int responseCode = 0;
    HttpURLConnection httpConnection;
    HttpsURLConnection httpsConnection;
    URL esUrl;
    String esHost = sharedPreferences.getString("host", "192.168.1.120");
    boolean esSSL = sharedPreferences.getBoolean("ssl", false);
    // Secured Connection
    if (esSSL) {
      final String esUsername = sharedPreferences.getString("user", "");
      final String esPassword = sharedPreferences.getString("pass", "");
      try {
        esUrl = new URL(String.format("%s", esHost));
        httpsConnection = (HttpsURLConnection) esUrl.openConnection();
        Log.e(logTag+"chk",String.format("%s", esHost) );
        // Send authentication if required
        if (esUsername.length() > 0 && esPassword.length() > 0) {
          Authenticator.setDefault(new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
              return new PasswordAuthentication(esUsername, esPassword.toCharArray());
            }
          });
        }
        httpsConnection.setConnectTimeout(2000);
        httpsConnection.setReadTimeout(2000);
        httpsConnection.connect();
        responseCode = httpsConnection.getResponseCode();
        if (responseCode >= 200 && responseCode <= 299) {
          responseCodeSuccess = true;
          httpsConnection.disconnect();
        }else{
          Log.e(logTag + " chk", "Failure to open connection cause. " + responseCode + " " + httpsConnection.getResponseMessage());
        }
      } catch (IOException | NullPointerException ex) {
        Log.e(logTag + " chkHost", "Failure to open connection cause. " + ex.getMessage() + " " + responseCode);
        ex.printStackTrace();
      }
    }else{ // Else NON-secured connection.
      try {
        //Log.e("Uploads-CheckHost", esHostUrlString); // DIAGNOSTICS
        esUrl = new URL(String.format("%s", esHost));
        httpConnection = (HttpURLConnection) esUrl.openConnection();
        httpConnection.setConnectTimeout(2000);
        httpConnection.setReadTimeout(2000);
        httpConnection.connect();
        responseCode = httpConnection.getResponseCode();
        if (responseCode >= 200 && responseCode <= 299) {
          responseCodeSuccess = true;
          httpConnection.disconnect();
        }
      } catch (IOException ex) {
        Log.e(logTag + " chkHost.", "Failure to open connection cause. " + ex.getMessage() + " " + responseCode);
        //ex.printStackTrace();
      }
    }
    // Returns TRUE if the response code was valid.
    return responseCodeSuccess;
  }

}
