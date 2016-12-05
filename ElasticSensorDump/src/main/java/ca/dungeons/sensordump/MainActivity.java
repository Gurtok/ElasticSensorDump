package ca.dungeons.sensordump;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity implements SensorEventListener {

    // Used to store sensor data before converting to JSON to submit
    public HashMap<String, Object> hmSensorData = new HashMap<String, Object>();
    public String jsonSensorData = null;
    TextView tvProgress = null;
    ArrayList<String> jsonDocuments = new ArrayList<String>();
    // Create new GPS logger
    GPSLogger gpsLogger = new GPSLogger();
    private SensorManager mSensorManager;
    private int[] usableSensors;
    private Handler refreshHandler;
    private int documentsSent = 0;
    private int documentsWritten = 0;
    private int syncErrors = 0;
    private boolean logging = false;
    private LocationManager locationManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Wakelock to prevent app from sleeping
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        /// Create a new data logger (make configurable!!)
        final ESDataLogger edl = new ESDataLogger();
        updateEsUrl(edl);
        final Intent settingsIntent = new Intent(this, SettingsActivity.class);

        // Click a button, get some sensor data
        final Button btnStart = (Button) findViewById(R.id.btnStart);
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!logging) {
                    btnStart.setText(R.string.buttonStop);
                    updateEsUrl(edl);
                    startLogging();
                } else {
                    btnStart.setText(R.string.buttonStart);
                    stopLogging();
                }
            }
        });

        // Click a button, get the settings screen
        final ImageButton ibSetup = (ImageButton) findViewById(R.id.ibSetup);
        ibSetup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(settingsIntent);
            }
        });

        // Get a list of all available sensors on the device and store in array
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        usableSensors = new int[deviceSensors.size()];
        for (int i = 0; i < deviceSensors.size(); i++) {
            usableSensors[i] = deviceSensors.get(i).getType();
        }

        // Refresh screen and store data periodically (make configurable!!)
        refreshHandler = new Handler();
        final Runnable r = new Runnable() {
            public void run() {
                if (logging) {
                    updateScreen();
                    storeData(edl);
                }
                refreshHandler.postDelayed(this, 1000);
            }
        };

        refreshHandler.postDelayed(r, 1000);

    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        // I don't really care about this yet.
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        // Update timestamp in sensor data structure
        Date logDate = new Date(System.currentTimeMillis());
        SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ");
        String dateString = logDateFormat.format(logDate);
        hmSensorData.put("@timestamp", dateString);

        // Dump gps data into document if it's ready
        if (gpsLogger.gpsHasData) {
            hmSensorData.put("location", "" + gpsLogger.gpsLat + "," + gpsLogger.gpsLong);
            hmSensorData.put("altitude", gpsLogger.gpsAlt);
            hmSensorData.put("accuracy", gpsLogger.gpsAccuracy);
            hmSensorData.put("bearing", gpsLogger.gpsBearing);
            hmSensorData.put("gps_provider", gpsLogger.gpsProvider);
            hmSensorData.put("speed", gpsLogger.gpsSpeed);
            hmSensorData.put("speed_kmh", gpsLogger.gpsSpeedKMH);
            hmSensorData.put("speed_mph", gpsLogger.gpsSpeedMPH);
        }

        // Store sensor update into sensor data structure
        for (int i = 0; i < event.values.length; i++) {

            // We don't need the android.sensor. and motorola.sensor. stuff
            // Split it out and just get the sensor name
            String sensorName = "";
            String[] sensorHierarchyName = event.sensor.getStringType().split("\\.");
            if (sensorHierarchyName.length == 0) {
                sensorName = event.sensor.getStringType();
            } else {
                sensorName = sensorHierarchyName[sensorHierarchyName.length - 1] + i;
            }

            // Store the actual sensor data now
            float sensorValue = event.values[i];
            hmSensorData.put(sensorName, sensorValue);
        }

        // Convert to JSON and add to log array
        JSONObject tempJson = new JSONObject(hmSensorData);
        jsonSensorData = tempJson.toString();
        jsonDocuments.add(jsonSensorData);
    }

    // Go through the sensor array and light them all up
    private void startLogging() {
        for (int i = 0; i < usableSensors.length; i++) {
            mSensorManager.registerListener(this,
                    mSensorManager.getDefaultSensor(usableSensors[i]),
                    SensorManager.SENSOR_DELAY_NORMAL);
        }

        // Light up the GPS if we're allowed
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsLogger);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }

        logging = true;
    }

    // Shut down the sensors by stopping listening to them
    private void stopLogging() {
        logging = false;
        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvProgress.setText(R.string.loggingStopped);
        mSensorManager.unregisterListener(this);

        // Disable GPS if we allowed it.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(gpsLogger);
        }

    }

    // Update the display with readings/written/errors
    public void updateScreen() {
        String updateText = "Sensor Readings: " + documentsSent + "\n" +
                "Documents Written: " + documentsWritten + "\n" +
                "Errors: " + syncErrors;
        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvProgress.setText(updateText);
    }

    // Store the sensor data in the configured Elastic Search system
    private void storeData(ESDataLogger edlInstance) {
        if (jsonDocuments.size() > 0) {
            edlInstance.storeHash(jsonDocuments);
            documentsSent += jsonDocuments.size();
            documentsWritten = edlInstance.documentsWritten;
            syncErrors = edlInstance.syncErrors;
        }
    }

    private void updateEsUrl(ESDataLogger edl) {
        // Load config data
        SharedPreferences sharedPrefs =
                PreferenceManager.getDefaultSharedPreferences(getBaseContext());

        // Populate the elastic data log
        edl.esHost = sharedPrefs.getString("host", "localhost");
        edl.esPort = sharedPrefs.getString("port", "9200");
        edl.esIndex = sharedPrefs.getString("index", "sensor_dump");
        edl.esType = sharedPrefs.getString("type", "phone_data");
        edl.esSSL = sharedPrefs.getBoolean("ssl", false);
        edl.esUsername = sharedPrefs.getString("user", "");
        edl.esPassword = sharedPrefs.getString("pass", "");
    }
}
