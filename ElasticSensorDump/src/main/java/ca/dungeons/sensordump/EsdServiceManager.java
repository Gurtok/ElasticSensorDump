package ca.dungeons.sensordump;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class EsdServiceManager extends Service {
  /** String to identify this class in LogCat. */
  private static final String logTag = "EsdServiceManager";
  private final IBinder serviceManagerBinder = new ServiceBinder();
  private SensorListener sensorListener;
  /** This thread pool is the working pool. Use this to execute the sensor runnable and Uploads. */
  private final ExecutorService workingThreadPool = Executors.newFixedThreadPool(4);
  /**
   * This thread pool handles the timer in which we control this service.
   * Timer that controls if/when we should be uploading data to the server.
   */
  private final ScheduledExecutorService timerPool = Executors.newScheduledThreadPool(2);

  /** Number of sensor readings this session. */
  public int sensorReadings = 0;
  /** Number of audio readings this session. */
  public int audioReadings = 0;
  /** Number of gps locations recorded this session */
  public int gpsReadings = 0;
  /** Number of documents indexed to Elastic this session. */
  public int documentsIndexed = 0;
  /** Number of data uploaded failures this session. */
  public int uploadErrors = 0;
  /** True if we are currently reading sensor data. */
  boolean logging = false;
  /** Toggle, if we should be recording AUDIO sensor data. */
  boolean audioLogging = false;
  /** Toggle, if we should be recording GPS data. */
  boolean gpsLogging = false;
  /** Android connection manager. Use to find out if we are connected before doing any networking. */
  private ConnectivityManager connectionManager;
  /** Uploads controls the data flow between the local database and Elastic server. */
  private Uploads uploads;
  /** This is the runnable we will use to check network connectivity once every 30 min. */
  private final Runnable uploadRunnable = new Runnable() {
    @Override
    public void run() {
      if (!uploads.isWorking() && connectionManager.getActiveNetworkInfo().isConnected()) {
        workingThreadPool.submit(uploads);
      } else if (uploads.isWorking()) {
        Log.e(logTag, "Uploading already in progress.");
      } else {
        Log.e(logTag, "Failed to submit uploads runnable to thread pool!");
      }
    }
  };
  /** Main activity preferences. Holds URL and name data. */
  private SharedPreferences sharedPrefs;

  private DatabaseHelper dbHelper;

  /** Toggle, if this service is currently running. Used by the main activity. */
  private boolean serviceActive = false;
  /** Time of the last sensor recording. Used to shut down unused resources. */
  private long lastSuccessfulSensorTime;
  /**
   * Service Timeout timer runnable.
   * If we go more than an a half hour without recording any sensor data, shut down this thread.
   */
  private final Runnable serviceTimeoutRunnable = new Runnable() {
    @Override
    public void run() {
      // Last sensor result plus 1/2 hour in milliseconds is greater than the current time.
      boolean timeCheck = lastSuccessfulSensorTime + (1000 * 60 * 30) > System.currentTimeMillis();
      if (!logging && !uploads.isWorking() && !timeCheck) {
        Log.e(logTag, "Shutting down service. Not logging!");
        stopServiceThread();
      }
    }
  };

  /**
   * Default constructor:
   * Instantiate the class broadcast receiver and messageFilters.
   * Register receiver to make sure we can communicate with the other threads.
   */
  @Override
  public void onCreate() {
    sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this.getBaseContext());
    dbHelper = new DatabaseHelper(this);
    sensorListener = new SensorListener(this, sharedPrefs, dbHelper, this);
  }

  /** Return a reference to this instance of the service. */
  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return serviceManagerBinder;
  }

  /** Control method to enable/disable gps recording. */
  void setGpsPower(boolean power) {
    sensorListener.setGpsPower(power);
  }

  void setSensorRefreshTime(int updatedRefresh) {
    sensorListener.setSensorRefreshTime(updatedRefresh);
  }

  /** Stop the service manager as a whole. */
  void stopServiceThread() {
    stopLogging();
    stopSelf();
  }

  /** Set audio recording on/off. */
  void setAudioPower(boolean power) {
    sensorListener.setAudioPower(power);
  }

  /**
   * Runs when the mainActivity executes this service.
   *
   * @param intent  - Not used.
   * @param flags   - Not used.
   * @param startId - Name of mainActivity.
   * @return START_STICKY will make sure the OS restarts this process if it has to trim memory.
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    //Log.e(logTag, "ESD -- On Start Command." );
    if (!serviceActive) {

      updateUiData();
      lastSuccessfulSensorTime = System.currentTimeMillis();
      connectionManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        /* Use SensorRunnable class to start the logging process. */

      workingThreadPool.submit(sensorListener);

        /* Create an instance of Uploads, and submit to the thread pool to begin execution. */
      uploads = new Uploads(this, sharedPrefs, this);
      workingThreadPool.submit(uploads);

        /* Schedule periodic checks for internet connectivity. */
      setupUploads();
        /* Schedule periodic checks for service shutdown due to inactivity. */
      setupManagerTimeout();

        /* Send a message to the main thread to indicate the manager service has been initialized. */
      serviceActive = true;

      Log.i(logTag, "Started service manager.");
    }
    // If the service is shut down, do not restart it automatically.
    return Service.START_NOT_STICKY;
  }

  /** This method uses the passed UI handler to relay messages if/when the activity is running. */
  Bundle updateUiData() {
    Bundle outBundle = new Bundle();
    outBundle.putInt("sensorReadings", sensorReadings);
    outBundle.putInt("gpsReadings", gpsReadings);
    outBundle.putInt("audioReadings", audioReadings);
    outBundle.putInt("documentsIndexed", documentsIndexed);
    outBundle.putInt("uploadErrors", uploadErrors);
    outBundle.putLong("databasePopulation", dbHelper.databaseEntries() );
    return outBundle;
  }

  public void sensorSuccess(boolean sensorReading, boolean gpsReading, boolean audioReading) {
    if (sensorReading)
      sensorReadings++;
    if (gpsReading)
      gpsReadings++;
    if (audioReading)
      audioReadings++;
  }

  public void uploadSuccess(boolean result) {
    if (result) {
      documentsIndexed++;
    } else {
      uploadErrors++;
    }
  }

  /** Timer used to periodically check if the upload runnable needs to be executed. */
  private void setupUploads() {
    timerPool.scheduleAtFixedRate(uploadRunnable, 5, 180, TimeUnit.SECONDS);
  } // Delay the task 10 seconds out and then repeat every 30 seconds.

  /** Timer used to periodically check if this service is being used (recording data). */
  private void setupManagerTimeout() {
    timerPool.scheduleAtFixedRate(serviceTimeoutRunnable, 60, 60, TimeUnit.MINUTES);
  } // Delay the task 60 min out. Then repeat once every 60 min.

  /**
   * Start logging method:
   * Send toggle requests to the sensor thread receiver.
   * 1. SENSOR toggle.
   * 2. GPS toggle.
   * 3. AUDIO toggle.
   */
  public void startLogging() {
    logging = true;
    sensorListener.setSensorPower(true);
    sensorListener.setGpsPower(gpsLogging);
    sensorListener.setAudioPower(audioLogging);
  }

  /**
   * Stop logging method:
   * 1. Unregister listeners for both sensors and battery.
   * 2. Turn gps recording off.
   * 3. Update main thread to initialize UI changes.
   */
  public void stopLogging() {
    logging = false;
    sensorListener.setSensorPower(false);
  }

  /**
   * This runs when the service either shuts itself down or the OS trims memory.
   * StopLogging() stops all sensor logging.
   * Unregister the Upload broadcast receiver.
   * Sends a message to the UI and UPLOAD receivers that we have shut down.
   */
  @Override
  public void onDestroy() {
    sensorListener.stopThread();
    Intent messageIntent = new Intent(Uploads.STOP_UPLOAD_THREAD);
    sendBroadcast(messageIntent);
    super.onDestroy();
  }

  class ServiceBinder extends Binder {
    EsdServiceManager getService() {
      return EsdServiceManager.this;
    }
  }


}


