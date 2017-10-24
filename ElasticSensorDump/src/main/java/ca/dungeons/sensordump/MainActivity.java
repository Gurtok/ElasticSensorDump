package ca.dungeons.sensordump;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import ca.dungeons.sensordump.EsdServiceManager.ServiceBinder;

/**
 * Elastic Sensor Dump.
 * Enumerates the sensors from an android device.
 * Record the sensor data and upload it to your elastic search server.
 */
public class MainActivity extends Activity {
  /** */
  private final String logTag = "MainActivity";
  /** Do NOT record more than once every 50 milliseconds. Default value is 250ms. */
  private final int MIN_SENSOR_REFRESH = 50;
  /** Global SharedPreferences object. */
  private SharedPreferences sharedPrefs;
  /** Persistent access to the apps database to avoid creating multiple db objects. */
  private DatabaseHelper databaseHelper;

  private EsdServiceManager serviceManager;

  private boolean isBound = false;

  /** Refresh time in milliseconds. Default = 250ms. */
  private int sensorRefreshTime = 250;

  private final ScheduledExecutorService updateTimer = Executors.newSingleThreadScheduledExecutor();

  /** Number of sensor readings this session */
  private int sensorReadings, documentsIndexed, gpsReadings, uploadErrors, audioReadings = 0;
  private long databasePopulation = 0L;

  private Runnable updateRunnable = new Runnable() {
    @Override
    public void run() {
      runOnUiThread(new Runnable() {
        @Override
        public void run() {
          updateScreen();
        }
      });
    }
  };

  /**
   * Build main activity buttons.
   * @param savedInstanceState A generic object.
   */
  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    sharedPrefs = this.getPreferences(MODE_PRIVATE);
    buildButtonLogic();
    Log.e(logTag, "Started Main Activity!");
  }

  /** Method to start the service manager if we have not already. */
  private void startServiceManager() {
    Intent startIntent = new Intent(this, EsdServiceManager.class);
    bindService( startIntent, serviceManagerConnection, Context.BIND_AUTO_CREATE );
  }

  private ServiceConnection serviceManagerConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      ServiceBinder serviceBinder = (ServiceBinder) service;
      serviceManager = serviceBinder.getService();
      isBound = true;
    }
    @Override
    public void onServiceDisconnected(ComponentName name) {
      isBound = false;
    }
  };

  void getScreenUpdates(){
    if( isBound ){
      Bundle dataBundle = serviceManager.updateUiData();
      sensorReadings = dataBundle.getInt("sensorReadings" );
      documentsIndexed = dataBundle.getInt("documentsIndexed" );
      gpsReadings = dataBundle.getInt("gpsReadings" );
      uploadErrors = dataBundle.getInt("uploadErrors" );
      audioReadings = dataBundle.getInt("audioReadings" );
      databasePopulation = dataBundle.getLong("databasePopulation" );
    }
  }

  /**
   * Update preferences with new permissions.
   * @param asked      Preferences key.
   * @param permission True if we have access.
   */
  private void BooleanToPrefs(String asked, boolean permission) {
    sharedPrefs = getPreferences(MODE_PRIVATE);
    SharedPreferences.Editor sharedPref_Editor = sharedPrefs.edit();
    sharedPref_Editor.putBoolean(asked, permission);
    sharedPref_Editor.apply();
  }

  /**
   * Update the display with readings/written/errors.
   * Need to update UI based on the passed data intent.
   */
  void updateScreen() {
    getScreenUpdates();
    TextView sensorTV = (TextView) findViewById(R.id.sensor_tv);
    TextView documentsTV = (TextView) findViewById(R.id.documents_tv);
    TextView gpsTV = (TextView) findViewById(R.id.gps_TV);
    TextView errorsTV = (TextView) findViewById(R.id.errors_TV);
    TextView audioTV = (TextView) findViewById(R.id.audioCount);
    TextView databaseTV = (TextView) findViewById(R.id.databaseCount);

    sensorTV.setText(String.valueOf(sensorReadings));
    documentsTV.setText(String.valueOf(documentsIndexed));
    gpsTV.setText(String.valueOf(gpsReadings));
    errorsTV.setText(String.valueOf(uploadErrors));
    audioTV.setText(String.valueOf(audioReadings));
    databaseTV.setText(String.valueOf(databasePopulation));
  }

  /**
   * Go through the sensor array and light them all up
   * btnStart: Click a button, get some sensor data.
   * ibSetup: Settings screen.
   * seekBar: Adjust the collection rate of data.
   * gpsToggle: Turn gps collection on/off.
   * audioToggle: Turn audio recording on/off.
   */
  private void buildButtonLogic() {
    final ToggleButton startButton = (ToggleButton) findViewById(R.id.toggleStart);
    startButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        Log.e(logTag, "Are we bound to the service: " + isBound);
        if( isBound ){
          if (isChecked) {
            Log.e(logTag, "Start button ON !");
            startButton.setBackgroundResource(R.drawable.main_button_shape_on);
            serviceManager.startLogging();
          } else {
            Log.e(logTag, "Start button OFF !");
            startButton.setBackgroundResource(R.drawable.main_button_shape_off);
            serviceManager.stopLogging();
          }
        }
      }
    });

    final ImageButton settingsButton = (ImageButton) findViewById(R.id.settings);
    settingsButton.setOnClickListener(new View.OnClickListener() {
      public void onClick(View v) {
        startActivity(new Intent(getBaseContext(), SettingsActivity.class));
      }
    });


    final CheckBox gpsCheckBox = (CheckBox) findViewById(R.id.gpsCheckBox);

    gpsCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        // If gps button is turned ON.
        if (!gpsPermission() && isChecked) {
          gpsCheckBox.toggle();
          Toast.makeText(getApplicationContext(), "GPS access denied.", Toast.LENGTH_SHORT).show();
          BooleanToPrefs("gps_asked", false);
        } else {
          serviceManager.setGpsPower( isChecked );
        }

      }
    });

    final CheckBox audioCheckBox = (CheckBox) findViewById(R.id.audioCheckBox);
    audioCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

        // If audio button is turned ON.
        if (!audioPermission() && isChecked) {
          audioCheckBox.toggle();
          Toast.makeText(getApplicationContext(), "Audio access denied.", Toast.LENGTH_SHORT).show();
          BooleanToPrefs("audio_Asked", false);
        } else {
          serviceManager.setAudioPower( isChecked );
        }
      }
    });

    final SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);
    final TextView tvSeekBarText = (TextView) findViewById(R.id.TickText);
    tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + seekBar.getProgress() * 10 + getString(R.string.milliseconds));
    seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (progress * 10 < MIN_SENSOR_REFRESH) {
          seekBar.setProgress(5);
          Toast.makeText(getApplicationContext(), "Minimum sensor refresh is 50 ms", Toast.LENGTH_SHORT).show();
        } else {
          sensorRefreshTime = progress * 10;
        }
        serviceManager.setSensorRefreshTime( sensorRefreshTime );
        tvSeekBarText.setText(getString(R.string.Collection_Interval) + " " + sensorRefreshTime + getString(R.string.milliseconds));
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {
      } //intentionally blank

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
      } //intentionally blank
    });

  }

  /**
   * Prompt user for GPS access.
   * Write this result to shared preferences.
   *
   * @return True if we asked for permission and it was granted.
   */
  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean gpsPermission() {

    boolean gpsPermissionCoarse = (ContextCompat.checkSelfPermission(this, Manifest.permission.
            ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED);

    boolean gpsPermissionFine = (ContextCompat.checkSelfPermission(this, android.Manifest.permission.
            ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);

    if (!gpsPermissionFine && !gpsPermissionCoarse) {

      ActivityCompat.requestPermissions(this, new String[]{
              Manifest.permission.ACCESS_COARSE_LOCATION,
              Manifest.permission.ACCESS_FINE_LOCATION
      }, 1);

      gpsPermissionCoarse = (ContextCompat.checkSelfPermission(this, Manifest.permission.
              ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED);

      gpsPermissionFine = (ContextCompat.checkSelfPermission(this, android.Manifest.permission.
              ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);

    }
    BooleanToPrefs("gps_permission_FINE", gpsPermissionFine);
    BooleanToPrefs("gps_permission_COURSE", gpsPermissionCoarse);
    return (gpsPermissionFine || gpsPermissionCoarse);
  }

  /**
   * Prompt user for MICROPHONE access.
   * Write this result to shared preferences.
   *
   * @return True if we asked for permission and it was granted.
   */
  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean audioPermission() {
    boolean audioPermission = sharedPrefs.getBoolean("audio_permission", false);

    if (!audioPermission) {
      String[] permissions = {Manifest.permission.RECORD_AUDIO};
      ActivityCompat.requestPermissions(this, permissions, 1);

      audioPermission = (ContextCompat.checkSelfPermission(this,
              Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
      BooleanToPrefs("audio_Permission", audioPermission);
    }
    return audioPermission;
  }


  /** If our activity is paused, we need to indicate to the service manager via a static variable. */
  @Override
  protected void onPause() {
    databaseHelper.close();
    super.onPause();
  }

  /**
   * When the activity starts or resumes, we start the upload process immediately.
   * If we were logging, we need to start the logging process. ( OS memory trim only )
   */
  @Override
  protected void onResume() {
    super.onResume();
    startServiceManager();
    databaseHelper = new DatabaseHelper(this);
    updateTimer.scheduleAtFixedRate(updateRunnable, 0, 1, TimeUnit.SECONDS );
  }

  /** If the user exits the application. */
  @Override
  protected void onDestroy() {
    serviceManager.stopServiceThread();
    updateTimer.shutdown();
    databaseHelper.close();
    super.onDestroy();
  }
}
